package com.banchen.minefading.relic;

import com.banchen.minefading.Config;
import com.banchen.minefading.Minefading;
import com.banchen.minefading.WorldRollbackManager;
import com.banchen.minefading.day.DayStateData;
import com.banchen.minefading.effect.ModEffects;
import com.banchen.minefading.item.RelicAction;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// 药芯系统的核心逻辑处理器，管理所有药芯的行为和状态
public class RelicRuntime
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final String SPECTATOR_LOCK_FILE = "spectator_locked.json";
    private static final String CAUSALITY_CANDIDATE_FILE = "causality_candidate.json";
    private static final int CAUSALITY_RECOVERY_TICKS = 20;
    // 当前激活因果效果的玩家及其剩余 tick 数
    private static final Map<UUID, Integer> causalityActiveTicks = new ConcurrentHashMap<>();
    // 因果刚刚成功救命后的保护窗口，防止客户端同 tick 误判死亡并触发回档
    private static final Map<UUID, Integer> causalityRecoveryTicks = new ConcurrentHashMap<>();
    // 当前激活柯罗诺斯效果的玩家及其剩余 tick 数
    private static final Map<UUID, Integer> kronosActiveTicks = new ConcurrentHashMap<>();
    // 柯罗诺斯效果结束后是否需要再次存档的标志
    private static final Map<UUID, Boolean> kronosNeedFinalizeSave = new ConcurrentHashMap<>();
    // 复活后需要切换为旁观者模式的玩家（极限死亡后锁定）
    private static final Set<UUID> pendingSpectatorLock = ConcurrentHashMap.newKeySet();
    // 已被锁定为旁观者、不得切换回其他模式的玩家
    private static final Set<UUID> spectatorLocked = ConcurrentHashMap.newKeySet();

    // 因果替身候选记录（只保留最后一个命名的实体），持久化到文件
    private record CandidateLocation(UUID entityId, ResourceKey<Level> dimension, double x, double y, double z) {}
    private static volatile CandidateLocation causalityCandidate = null;

    // 是否正在按下柯罗诺斯快捷键（客户端写入，客户端读取）
    private static volatile boolean slowTimeKeyDown;

    // 根据药芯行为类型分发执行逻辑
    public static boolean handleAction(ServerPlayer player, RelicAction action)
    {
        return switch (action)
        {
            // 装填提示
            case INHALER -> false;
            // 蜕皮：回到上一存档点
            case SHEDDING -> rollback(player);
            // 断线：手动创建存档点
            case DISCONNECT -> createCheckpoint(player, "message.minefading.disconnect_saved");
            // 高塔：主动死亡
            case TOWER -> killPlayer(player, "message.minefading.tower_selected");
            // 因果：激活替身保护
            case CAUSALITY -> activateCausality(player);
            // 柯罗诺斯：按键放缓时间
            case CHRONOS -> activateKronos(player);
        };
    }

    // 蜕皮：触发全世界回档（调度器将在客户端 tick 执行断线→还原→重载）
    private static boolean rollback(ServerPlayer player)
    {
        MinecraftServer server = player.server;
        if (!WorldRollbackManager.hasSnapshot(server))
        {
            player.displayClientMessage(Component.translatable("message.minefading.rollback_missing_snapshot"), true);
            return false;
        }
        WorldRollbackManager.writeShedding(server);
        WorldRollbackManager.scheduleRestore(server);
        player.displayClientMessage(Component.translatable("message.minefading.rollback_scheduled"), true);
        return true;
    }

    // 断线/柯罗诺斯：创建全世界快照作为存档点
    private static boolean createCheckpoint(ServerPlayer player, String messageKey)
    {
        WorldRollbackManager.takeSnapshot(player.server); // 保存整个世界（含玩家状态）
        player.displayClientMessage(Component.translatable(messageKey), true);
        return true;
    }

    // 细沙：将目标实体加入追踪，保存其完整NBT；真正回溯时再把它带到过去
    public static void trackEntity(ServerPlayer player, LivingEntity entity)
    {
        MinecraftServer server = player.server;
        DayStateData data = DayStateData.get(server);

        data.trackEntity(entity.getUUID(), entity, server); // 保存UUID、NBT和维度信息
        player.displayClientMessage(Component.translatable("message.minefading.fine_sand_recorded"), true);
    }

    // 每客户端 tick 调用：倒计时效果、柯罗诺斯放缓时间、结束后存档
    public static void onServerTick(MinecraftServer server)
    {
        tickMap(causalityActiveTicks);
        tickMap(causalityRecoveryTicks);
        tickMap(kronosActiveTicks);

        // 柯罗诺斯效果结束后，为该玩家补存一次档
        for (UUID playerId : kronosNeedFinalizeSave.keySet().toArray(UUID[]::new))
        {
            if (Boolean.TRUE.equals(kronosNeedFinalizeSave.get(playerId)) && !kronosActiveTicks.containsKey(playerId))
            {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null)
                    createCheckpoint(player, "message.minefading.kronos_saved_after_end");
                kronosNeedFinalizeSave.remove(playerId);
            }
        }
    }

    // 因果致死伤害拦截：若玩家激活了因果且存在符合条件的替身，替身替玩家承受致死伤害
    // 返回 true 表示拦截成功（本次伤害/死亡事件应被取消）
    public static boolean tryHandleCausalityLethalDamage(ServerPlayer player)
    {
        UUID playerId = player.getUUID();
        if (!causalityActiveTicks.containsKey(playerId))
            return false;
        if (!WorldRollbackManager.hasSnapshot(player.server))
            return false;

        LivingEntity substitute = findCausalitySubstitute(player);
        if (substitute == null)
            return false;

        player.setHealth(Math.max(1.0F, player.getMaxHealth() * 0.5F));
        if (substitute.level() instanceof ServerLevel level)
            player.teleportTo(level, substitute.getX(), substitute.getY(), substitute.getZ(), player.getYRot(), player.getXRot());
        substitute.hurt(substitute.damageSources().magic(), Float.MAX_VALUE);
        player.displayClientMessage(Component.translatable("message.minefading.causality_triggered"), true);
        causalityActiveTicks.remove(playerId);
        causalityRecoveryTicks.put(playerId, CAUSALITY_RECOVERY_TICKS);
        return true;
    }

    public static boolean wouldCausalityPreventLethalDamage(ServerPlayer player)
    {
        return WorldRollbackManager.hasSnapshot(player.server)
                && causalityActiveTicks.containsKey(player.getUUID())
                && findCausalitySubstitute(player) != null;
    }

    public static boolean isCausalityRecoveryActive(UUID playerId)
    {
        return causalityRecoveryTicks.containsKey(playerId);
    }

    public static boolean isCausalityActive(UUID playerId)
    {
        return causalityActiveTicks.containsKey(playerId);
    }

    public static boolean canCausalityPreventDeath(ServerPlayer player)
    {
        return wouldCausalityPreventLethalDamage(player);
    }

    // 由客户端 MinefadingKeybinds 每 tick 写入按键状态
    public static void setSlowTimeKeyDown(boolean keyDown)
    {
        slowTimeKeyDown = keyDown;
    }

    // 当前是否处于“柯罗诺斯减 tick 流速”状态（按键按下且已激活柯罗诺斯）
    public static boolean isKronosSlowActive()
    {
        return slowTimeKeyDown && !kronosActiveTicks.isEmpty();
    }

    

    

    private static boolean activateCausality(ServerPlayer player)
    {
        if (!WorldRollbackManager.hasSnapshot(player.server))
        {
            player.displayClientMessage(Component.translatable("message.minefading.causality_requires_snapshot"), true);
            return false;
        }

        causalityActiveTicks.put(player.getUUID(), Config.causalityTicks);
        player.displayClientMessage(Component.translatable("message.minefading.causality_activated"), true);
        return true;
    }

    private static boolean activateKronos(ServerPlayer player)
    {
        if (!kronosActiveTicks.isEmpty())
        {
            player.displayClientMessage(Component.translatable("message.minefading.kronos_already_active"), true);
            return false;
        }

        createCheckpoint(player, "message.minefading.kronos_saved_before_start");
        kronosActiveTicks.put(player.getUUID(), Config.kronosTicks);
        kronosNeedFinalizeSave.put(player.getUUID(), true);
        player.addEffect(new MobEffectInstance(ModEffects.KRONOS_ACTIVE.get(), Config.kronosTicks, 0, false, true, true));
        player.displayClientMessage(Component.translatable("message.minefading.kronos_hold_key"), true);
        return true;
    }

    // 标记玩家复活后切换为旁观者（供高塔/倒计时调用）
    public static void markForSpectatorLock(UUID uuid)
    {
        pendingSpectatorLock.add(uuid);
    }

    // 检查并消费旁观者锁定标记（复活事件中调用），同时写入持久锁定集合并保存到文件
    public static boolean consumeSpectatorLock(UUID uuid, MinecraftServer server)
    {
        if (pendingSpectatorLock.remove(uuid))
        {
            spectatorLocked.add(uuid);
            saveSpectatorLocked(server);
            return true;
        }
        return false;
    }

    // 查询玩家是否已被持久锁定为旁观者
    public static boolean isSpectatorLocked(UUID uuid)
    {
        return spectatorLocked.contains(uuid);
    }

    // 从 <世界>/data/minefading/spectator_locked.json 加载锁定集合（客户端启动时调用）
    public static void loadSpectatorLocked(MinecraftServer server)
    {
        spectatorLocked.clear();
        File file = getSpectatorLockFile(server);
        if (!file.exists())
            return;
        try (FileReader reader = new FileReader(file))
        {
            String[] arr = GSON.fromJson(reader, String[].class);
            if (arr != null)
                for (String s : arr)
                    spectatorLocked.add(UUID.fromString(s));
            LOGGER.info("[Minefading] 已加载 {} 条旁观者锁定记录", spectatorLocked.size());
        }
        catch (IOException | IllegalArgumentException e)
        {
            LOGGER.error("[Minefading] 加载旁观者锁定数据失败", e);
        }
    }

    // 将锁定集合持久化到 JSON 文件
    private static void saveSpectatorLocked(MinecraftServer server)
    {
        File file = getSpectatorLockFile(server);
        List<String> list = spectatorLocked.stream().map(UUID::toString).collect(Collectors.toList());
        try (FileWriter writer = new FileWriter(file))
        {
            GSON.toJson(list, writer);
        }
        catch (IOException e)
        {
            LOGGER.error("[Minefading] 保存旁观者锁定数据失败", e);
        }
    }

    // 获取 JSON 文件路径
    private static File getSpectatorLockFile(MinecraftServer server)
    {
        File modDataDir = new File(
            server.getWorldPath(LevelResource.ROOT).toFile(),
            "data" + File.separator + Minefading.MODID
        );
        if (!modDataDir.exists())
            modDataDir.mkdirs();
        return new File(modDataDir, SPECTATOR_LOCK_FILE);
    }

    // 高塔：玩家主动死亡
    private static boolean killPlayer(ServerPlayer player, String messageKey)
    {
        WorldRollbackManager.deleteSnapshot(player.server);
        markForSpectatorLock(player.getUUID());
        player.displayClientMessage(Component.translatable(messageKey), false);
        player.kill();
        return true;
    }

    // 将 Map 中所有计数器减 1，归零时移除（用于管理效果持续时间）
    private static void tickMap(Map<UUID, Integer> map)
    {
        for (UUID key : map.keySet().toArray(UUID[]::new))
        {
            int next = map.get(key) - 1;
            if (next <= 0)
                map.remove(key);
            else
                map.put(key, next);
        }
    }

    // 在所有维度中寻找因果替身：与玩家同名、有自定义名称、最大生命值大于 20 的生物
    private static LivingEntity findCausalitySubstitute(ServerPlayer player)
    {
        String expectedName = player.getName().getString();
        MinecraftServer server = player.server;

        // 1. 优先在已加载实体中查找
        for (ServerLevel level : server.getAllLevels())
        {
            for (Entity entity : level.getAllEntities())
            {
                if (matchesCausalitySubstitute(entity, player, expectedName))
                    return (LivingEntity) entity;
            }
        }

        // 2. 已加载实体未找到，尝试通过候选记录加载区块
        CandidateLocation loc = causalityCandidate;
        if (loc == null)
            return null;

        ServerLevel level = server.getLevel(loc.dimension());
        if (level == null)
            return null;

        // 强制加载实体所在区块
        ChunkPos chunkPos = new ChunkPos((int) loc.x() >> 4, (int) loc.z() >> 4);
        level.getChunk(chunkPos.x, chunkPos.z);

        Entity entity = level.getEntity(loc.entityId());
        if (entity == null)
        {
            causalityCandidate = null;
            saveCausalityCandidate(server);
            return null;
        }

        if (matchesCausalitySubstitute(entity, player, expectedName))
        {
            // 更新位置（实体可能已移动）
            causalityCandidate = new CandidateLocation(
                    loc.entityId(), level.dimension(), entity.getX(), entity.getY(), entity.getZ());
            saveCausalityCandidate(server);
            return (LivingEntity) entity;
        }
        else
        {
            // 实体已不符合条件（被改名或死亡），清除候选
            causalityCandidate = null;
            saveCausalityCandidate(server);
            return null;
        }
    }

    // 检查实体是否符合因果替身条件
    private static boolean matchesCausalitySubstitute(Entity entity, ServerPlayer player, String expectedName)
    {
        if (!(entity instanceof LivingEntity living))
            return false;
        if (living == player)
            return false;
        if (!living.hasCustomName())
            return false;
        if (!expectedName.equals(living.getCustomName().getString()))
            return false;
        return living.getMaxHealth() > 20.0F;
    }

    /**
     * 当玩家用命名牌命名实体时调用：如果实体符合因果替身条件就记录为唯一候选。
     * 多次命名以最后一次为准。由 RelicGameplayEvents.onEntityInteract 触发。
     */
    public static void onEntityNamed(ServerPlayer player, LivingEntity entity, String newName)
    {
        String playerName = player.getName().getString();
        if (playerName.equals(newName) && entity.getMaxHealth() > 20.0F)
        {
            ResourceKey<Level> dim = entity.level().dimension();
            causalityCandidate = new CandidateLocation(
                    entity.getUUID(), dim, entity.getX(), entity.getY(), entity.getZ());
            saveCausalityCandidate(player.server);
            LOGGER.info("[Minefading] 已记录因果替身候选：{} (UUID={}) 位于 {}({}, {}, {})",
                    newName, entity.getUUID(), dim.location(),
                    (int) entity.getX(), (int) entity.getY(), (int) entity.getZ());
        }
        else if (causalityCandidate != null && causalityCandidate.entityId().equals(entity.getUUID()))
        {
            // 原来的候选被改了名字，不再符合条件
            causalityCandidate = null;
            saveCausalityCandidate(player.server);
        }
    }

    // 从文件加载因果替身候选（服务端启动时调用）
    public static void loadCausalityCandidate(MinecraftServer server)
    {
        causalityCandidate = null;
        File file = getCausalityCandidateFile(server);
        if (!file.exists())
            return;
        try (FileReader reader = new FileReader(file))
        {
            JsonObject obj = GSON.fromJson(reader, JsonObject.class);
            if (obj == null)
                return;
            UUID entityId = UUID.fromString(obj.get("uuid").getAsString());
            ResourceKey<Level> dim = ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    ResourceLocation.tryParse(obj.get("dimension").getAsString()));
            double x = obj.get("x").getAsDouble();
            double y = obj.get("y").getAsDouble();
            double z = obj.get("z").getAsDouble();
            causalityCandidate = new CandidateLocation(entityId, dim, x, y, z);
            LOGGER.info("[Minefading] 已加载因果替身候选：UUID={} 位于 {}({}, {}, {})",
                    entityId, dim.location(), (int) x, (int) y, (int) z);
        }
        catch (Exception e)
        {
            LOGGER.error("[Minefading] 加载因果替身候选数据失败", e);
        }
    }

    // 将因果替身候选持久化到 JSON 文件
    private static void saveCausalityCandidate(MinecraftServer server)
    {
        File file = getCausalityCandidateFile(server);
        CandidateLocation loc = causalityCandidate;
        if (loc == null)
        {
            file.delete();
            return;
        }
        try (FileWriter writer = new FileWriter(file))
        {
            JsonObject obj = new JsonObject();
            obj.addProperty("uuid", loc.entityId().toString());
            obj.addProperty("dimension", loc.dimension().location().toString());
            obj.addProperty("x", loc.x());
            obj.addProperty("y", loc.y());
            obj.addProperty("z", loc.z());
            GSON.toJson(obj, writer);
        }
        catch (IOException e)
        {
            LOGGER.error("[Minefading] 保存因果替身候选数据失败", e);
        }
    }

    private static File getCausalityCandidateFile(MinecraftServer server)
    {
        File modDataDir = new File(
            server.getWorldPath(LevelResource.ROOT).toFile(),
            "data" + File.separator + Minefading.MODID
        );
        if (!modDataDir.exists())
            modDataDir.mkdirs();
        return new File(modDataDir, CAUSALITY_CANDIDATE_FILE);
    }
}
