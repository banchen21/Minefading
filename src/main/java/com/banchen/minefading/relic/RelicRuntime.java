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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// 药芯系统的核心逻辑处理器，管理所有药芯的行为和状态
public class RelicRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final String SPECTATOR_LOCK_FILE = "spectator_locked.json";
    private static final String CAUSALITY_CANDIDATE_FILE = "causality_candidate.json";
    private static final String FINE_SAND_MARK_KEY = Minefading.MODID + ".fine_sand_marked";
    private static final String CAUSALITY_MARK_KEY = Minefading.MODID + ".causality_marked";
    private static final String CAUSALITY_OWNER_KEY = Minefading.MODID + ".causality_owner";
    private static final float CAUSALITY_HEALTH_COST = 20.0F;
    private static final int CAUSALITY_RECOVERY_TICKS = 20;
    // 当前激活因果效果的玩家及其剩余 tick 数
    private static final Map<UUID, Integer> causalityActiveTicks = new ConcurrentHashMap<>();
    // 因果刚刚成功救命后的保护窗口，防止客户端同 tick 误判死亡并触发回档
    private static final Map<UUID, Integer> causalityRecoveryTicks = new ConcurrentHashMap<>();
    // 复活后需要切换为旁观者模式的玩家（极限死亡后锁定）
    private static final Set<UUID> pendingSpectatorLock = ConcurrentHashMap.newKeySet();
    // 已被锁定为旁观者、不得切换回其他模式的玩家
    private static final Set<UUID> spectatorLocked = ConcurrentHashMap.newKeySet();

    // 因果替身候选记录（只保留最后一次绑定的实体），持久化到文件
    private record CandidateLocation(UUID entityId, ResourceKey<Level> dimension, double x, double y, double z) {
    }

    private static volatile CandidateLocation causalityCandidate = null;

    // 根据药芯行为类型分发执行逻辑
    public static boolean handleAction(ServerPlayer player, RelicAction action) {
        return switch (action) {
            // 装填提示
            case INHALER -> false;
            // 蜕皮：回到上一存档点
            case SHEDDING -> rollback(player);
            // 断线：手动创建存档点
            case DISCONNECT -> createCheckpoint(player, "message.minefading.disconnect_saved");
            // 高塔：主动死亡
            case TOWER -> killPlayer(player, "message.minefading.tower_selected");
            // 因果：需对生物定向使用，不响应空放
            case CAUSALITY -> false;
            // 柯罗诺斯：时缓核心已移除，仅保留存档行为
            case CHRONOS -> activateKronos(player);
        };
    }

    // 蜕皮：触发全世界回档（调度器将在客户端 tick 执行断线→还原→重载）
    private static boolean rollback(ServerPlayer player) {
        MinecraftServer server = player.server;
        if (!WorldRollbackManager.hasSnapshot(server)) {
            player.displayClientMessage(Component.translatable("message.minefading.rollback_missing_snapshot"), true);
            return false;
        }
        WorldRollbackManager.writeShedding(server);
        WorldRollbackManager.scheduleRestore(server);
        player.displayClientMessage(Component.translatable("message.minefading.rollback_scheduled"), true);
        return true;
    }

    // 断线/柯罗诺斯：创建全世界快照作为存档点
    private static boolean createCheckpoint(ServerPlayer player, String messageKey) {
        return createCheckpoint(player, messageKey, false);
    }

    private static boolean createCheckpoint(ServerPlayer player, String messageKey, boolean blocking) {
        if (blocking)
            WorldRollbackManager.takeSnapshotBlocking(player.server);
        else
            WorldRollbackManager.takeSnapshot(player.server); // 保存整个世界（含玩家状态）
        player.displayClientMessage(Component.translatable(messageKey), true);
        return true;
    }

    // 细沙：将目标实体标记并加入追踪，真正回溯时再把它带到过去
    public static boolean trackEntity(ServerPlayer player, LivingEntity entity) {
        if (entity instanceof net.minecraft.world.entity.player.Player) {
            player.displayClientMessage(Component.translatable("message.minefading.fine_sand_requires_non_player"),
                    true);
            return false;
        }

        MinecraftServer server = player.server;
        DayStateData data = DayStateData.get(server);

        markEntityForTracking(entity, FINE_SAND_MARK_KEY);

        data.trackEntity(entity.getUUID(), entity, server); // 保存UUID、NBT和维度信息
        player.displayClientMessage(Component.translatable("message.minefading.fine_sand_recorded"), true);
        return true;
    }

    // 因果：对目标实体施加发光/命名/持久化标记，并立即激活替身效果
    public static boolean bindCausalityTarget(ServerPlayer player, LivingEntity entity) {
        if (!WorldRollbackManager.hasSnapshot(player.server)) {
            player.displayClientMessage(Component.translatable("message.minefading.causality_requires_snapshot"), true);
            return false;
        }
        if (entity instanceof net.minecraft.world.entity.player.Player) {
            player.displayClientMessage(Component.translatable("message.minefading.causality_requires_non_player"),
                    true);
            return false;
        }

        markEntityForTracking(entity, CAUSALITY_MARK_KEY);
        entity.getPersistentData().putUUID(CAUSALITY_OWNER_KEY, player.getUUID());
        entity.setCustomName(player.getName().copy());

        causalityCandidate = new CandidateLocation(
                entity.getUUID(),
                entity.level().dimension(),
                entity.getX(),
                entity.getY(),
                entity.getZ());
        saveCausalityCandidate(player.server);
        causalityActiveTicks.put(player.getUUID(), Config.causalityTicks);
        player.displayClientMessage(Component.translatable("message.minefading.causality_activated"), true);
        return true;
    }

    // 每服务端 tick 调用：驱动药芯持续效果
    public static void onServerTick(MinecraftServer server) {
        tickMap(causalityActiveTicks);
        tickMap(causalityRecoveryTicks);
    }

    // 因果致死伤害拦截：若玩家激活了因果且存在符合条件的替身，
    // 当前血量 >= 20 时扣除替身 20 点生命并为玩家恢复 20 点生命；
    // 当前血量 < 20 时与玩家互换位置并让替身立即死亡
    // 返回 true 表示拦截成功（本次伤害/死亡事件应被取消）
    public static boolean tryHandleCausalityLethalDamage(ServerPlayer player) {
        UUID playerId = player.getUUID();
        if (!causalityActiveTicks.containsKey(playerId))
            return false;
        if (!WorldRollbackManager.hasSnapshot(player.server))
            return false;

        LivingEntity substitute = findCausalitySubstitute(player);
        if (substitute == null)
            return false;

        boolean handled;
        if (substitute.getHealth() >= CAUSALITY_HEALTH_COST)
            handled = applyCausalityHealthTransfer(player, substitute);
        else
            handled = swapPlayerWithSubstitute(player, substitute);

        if (!handled)
            return false;

        clearCausalityCandidate(player.server);
        player.displayClientMessage(Component.translatable("message.minefading.causality_triggered"), true);
        causalityActiveTicks.remove(playerId);
        causalityRecoveryTicks.put(playerId, CAUSALITY_RECOVERY_TICKS);
        return true;
    }

    public static boolean wouldCausalityPreventLethalDamage(ServerPlayer player) {
        return WorldRollbackManager.hasSnapshot(player.server)
                && causalityActiveTicks.containsKey(player.getUUID())
                && findCausalitySubstitute(player) != null;
    }

    public static boolean isCausalityRecoveryActive(UUID playerId) {
        return causalityRecoveryTicks.containsKey(playerId);
    }

    public static boolean isCausalityActive(UUID playerId) {
        return causalityActiveTicks.containsKey(playerId);
    }

    public static boolean canCausalityPreventDeath(ServerPlayer player) {
        return wouldCausalityPreventLethalDamage(player);
    }

    public static void cancelKronosForRestore() {
        // 时缓核心已移除，保留空实现以兼容现有调用点
    }

    private static void markEntityForTracking(LivingEntity entity, String markKey) {
        entity.setGlowingTag(true);
        entity.getPersistentData().putBoolean(markKey, true);
        if (entity instanceof Mob mob)
            mob.setPersistenceRequired();
    }

    public static void clearFineSandTrackingState(Entity entity) {
        clearEntityTrackingState(entity, FINE_SAND_MARK_KEY);
    }

    private static void clearCausalityBindingState(Entity entity) {
        clearEntityTrackingState(entity, CAUSALITY_MARK_KEY);
        entity.getPersistentData().remove(CAUSALITY_OWNER_KEY);
    }

    private static void clearEntityTrackingState(Entity entity, String markKey) {
        entity.getPersistentData().remove(markKey);
        boolean stillTrackedByFineSand = entity.getPersistentData().getBoolean(FINE_SAND_MARK_KEY);
        boolean stillTrackedByCausality = entity.getPersistentData().getBoolean(CAUSALITY_MARK_KEY);
        if (!stillTrackedByFineSand && !stillTrackedByCausality)
            entity.setGlowingTag(false);
    }

    private static boolean applyCausalityHealthTransfer(ServerPlayer player, LivingEntity substitute) {
        float remainingHealth = substitute.getHealth() - CAUSALITY_HEALTH_COST;
        // 按规则，当前血量 >= 20 时都应走“扣血拦截”分支，因此最低保留 1 点生命避免 20 血边界直接死亡。
        substitute.setHealth(Math.max(1.0F, remainingHealth));
        clearCausalityBindingState(substitute);
        restorePlayerHealth(player, CAUSALITY_HEALTH_COST);
        return true;
    }

    private static boolean swapPlayerWithSubstitute(ServerPlayer player, LivingEntity substitute) {
        ensurePlayerSurvives(player);

        if (!(substitute.level() instanceof ServerLevel substituteLevel)) {
            LOGGER.warn("[Minefading] 因果替身不在服务端维度中，无法交换位置：{}", substitute.getUUID());
            return false;
        }

        ServerLevel playerLevel = player.serverLevel();
        double playerX = player.getX();
        double playerY = player.getY();
        double playerZ = player.getZ();
        float playerYRot = player.getYRot();
        float playerXRot = player.getXRot();

        double substituteX = substitute.getX();
        double substituteY = substitute.getY();
        double substituteZ = substitute.getZ();

        Entity movedEntity = substitute;
        if (substituteLevel != playerLevel) {
            Entity changed = substitute.changeDimension(playerLevel);
            if (changed == null) {
                LOGGER.warn("[Minefading] 因果替身跨维度交换失败：{}", substitute.getUUID());
                return false;
            }
            movedEntity = changed;
        }

        movedEntity.teleportTo(playerX, playerY, playerZ);
        movedEntity.kill();
        player.teleportTo(substituteLevel, substituteX, substituteY, substituteZ, playerYRot, playerXRot);
        clearCausalityBindingState(movedEntity);
        return true;
    }

    private static void ensurePlayerSurvives(ServerPlayer player) {
        player.setHealth(Math.max(1.0F, player.getHealth()));
    }

    private static void restorePlayerHealth(ServerPlayer player, float amount) {
        float baseHealth = Math.max(1.0F, player.getHealth());
        player.setHealth(Math.min(player.getMaxHealth(), baseHealth + amount));
    }

    public static void setSlowTimeKeyDown(boolean keyDown) {
        // 时缓核心已移除，保留空实现以兼容现有调用点
    }

    public static boolean isKronosSlowActive() {
        return false;
    }

    private static boolean activateKronos(ServerPlayer player) {
        // 时缓核心已移除：柯罗诺斯仅保留“立即存档”语义
        createCheckpoint(player, "message.minefading.kronos_saved_before_start", true);
        player.addEffect(new MobEffectInstance(ModEffects.KRONOS_ACTIVE.get(), Config.kronosTicks, 0, false, true, true));
        return true;
    }

    // 标记玩家复活后切换为旁观者（供高塔/倒计时调用）
    public static void markForSpectatorLock(UUID uuid) {
        pendingSpectatorLock.add(uuid);
    }

    // 检查并消费旁观者锁定标记（复活事件中调用），同时写入持久锁定集合并保存到文件
    public static boolean consumeSpectatorLock(UUID uuid, MinecraftServer server) {
        if (pendingSpectatorLock.remove(uuid)) {
            spectatorLocked.add(uuid);
            saveSpectatorLocked(server);
            return true;
        }
        return false;
    }

    // 查询玩家是否已被持久锁定为旁观者
    public static boolean isSpectatorLocked(UUID uuid) {
        return spectatorLocked.contains(uuid);
    }

    // 从 <世界>/data/minefading/spectator_locked.json 加载锁定集合（客户端启动时调用）
    public static void loadSpectatorLocked(MinecraftServer server) {
        spectatorLocked.clear();
        File file = getSpectatorLockFile(server);
        if (!file.exists())
            return;
        try (FileReader reader = new FileReader(file)) {
            String[] arr = GSON.fromJson(reader, String[].class);
            if (arr != null)
                for (String s : arr)
                    spectatorLocked.add(UUID.fromString(s));
            LOGGER.info("[Minefading] 已加载 {} 条旁观者锁定记录", spectatorLocked.size());
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.error("[Minefading] 加载旁观者锁定数据失败", e);
        }
    }

    // 将锁定集合持久化到 JSON 文件
    private static void saveSpectatorLocked(MinecraftServer server) {
        File file = getSpectatorLockFile(server);
        List<String> list = spectatorLocked.stream().map(UUID::toString).collect(Collectors.toList());
        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(list, writer);
        } catch (IOException e) {
            LOGGER.error("[Minefading] 保存旁观者锁定数据失败", e);
        }
    }

    // 获取 JSON 文件路径
    private static File getSpectatorLockFile(MinecraftServer server) {
        File modDataDir = new File(
                server.getWorldPath(LevelResource.ROOT).toFile(),
                "data" + File.separator + Minefading.MODID);
        if (!modDataDir.exists())
            modDataDir.mkdirs();
        return new File(modDataDir, SPECTATOR_LOCK_FILE);
    }

    // 高塔：玩家主动死亡
    private static boolean killPlayer(ServerPlayer player, String messageKey) {
        WorldRollbackManager.deleteSnapshot(player.server);
        markForSpectatorLock(player.getUUID());
        player.displayClientMessage(Component.translatable(messageKey), false);
        player.kill();
        return true;
    }

    // 将 Map 中所有计数器减 1，归零时移除（用于管理效果持续时间）
    private static void tickMap(Map<UUID, Integer> map) {
        for (UUID key : map.keySet().toArray(UUID[]::new)) {
            int next = map.get(key) - 1;
            if (next <= 0)
                map.remove(key);
            else
                map.put(key, next);
        }
    }

    // 查找当前绑定的因果替身；若实体未加载则按上次位置补加载对应区块
    private static LivingEntity findCausalitySubstitute(ServerPlayer player) {
        MinecraftServer server = player.server;
        CandidateLocation loc = causalityCandidate;
        if (loc == null)
            return null;

        Entity entity = findEntity(server, loc.entityId());
        if (entity == null) {
            ServerLevel level = server.getLevel(loc.dimension());
            if (level == null)
                return null;

            ChunkPos chunkPos = new ChunkPos((int) loc.x() >> 4, (int) loc.z() >> 4);
            level.getChunk(chunkPos.x, chunkPos.z);
            entity = level.getEntity(loc.entityId());
        }

        if (entity == null) {
            clearCausalityCandidate(server);
            return null;
        }

        if (matchesCausalitySubstitute(entity, player)) {
            causalityCandidate = new CandidateLocation(
                    loc.entityId(), entity.level().dimension(), entity.getX(), entity.getY(), entity.getZ());
            saveCausalityCandidate(server);
            return (LivingEntity) entity;
        }

        clearCausalityCandidate(server);
        return null;
    }

    private static void clearCausalityCandidate(MinecraftServer server) {
        causalityCandidate = null;
        saveCausalityCandidate(server);
    }

    // 检查实体是否仍符合因果替身条件
    private static boolean matchesCausalitySubstitute(Entity entity, ServerPlayer player) {
        if (!(entity instanceof LivingEntity living))
            return false;
        if (living == player)
            return false;
        if (living instanceof net.minecraft.world.entity.player.Player)
            return false;
        if (!living.hasCustomName())
            return false;
        if (!player.getName().getString().equals(living.getCustomName().getString()))
            return false;
        if (!living.getPersistentData().getBoolean(CAUSALITY_MARK_KEY))
            return false;
        return living.getPersistentData().hasUUID(CAUSALITY_OWNER_KEY)
                && player.getUUID().equals(living.getPersistentData().getUUID(CAUSALITY_OWNER_KEY));
    }

    private static Entity findEntity(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null)
                return entity;
        }
        return null;
    }

    // 从文件加载因果替身候选（服务端启动时调用）
    public static void loadCausalityCandidate(MinecraftServer server) {
        causalityCandidate = null;
        File file = getCausalityCandidateFile(server);
        if (!file.exists())
            return;
        try (FileReader reader = new FileReader(file)) {
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
        } catch (Exception e) {
            LOGGER.error("[Minefading] 加载因果替身候选数据失败", e);
        }
    }

    // 将因果替身候选持久化到 JSON 文件
    private static void saveCausalityCandidate(MinecraftServer server) {
        File file = getCausalityCandidateFile(server);
        CandidateLocation loc = causalityCandidate;
        if (loc == null) {
            file.delete();
            return;
        }
        try (FileWriter writer = new FileWriter(file)) {
            JsonObject obj = new JsonObject();
            obj.addProperty("uuid", loc.entityId().toString());
            obj.addProperty("dimension", loc.dimension().location().toString());
            obj.addProperty("x", loc.x());
            obj.addProperty("y", loc.y());
            obj.addProperty("z", loc.z());
            GSON.toJson(obj, writer);
        } catch (IOException e) {
            LOGGER.error("[Minefading] 保存因果替身候选数据失败", e);
        }
    }

    private static File getCausalityCandidateFile(MinecraftServer server) {
        File modDataDir = new File(
                server.getWorldPath(LevelResource.ROOT).toFile(),
                "data" + File.separator + Minefading.MODID);
        if (!modDataDir.exists())
            modDataDir.mkdirs();
        return new File(modDataDir, CAUSALITY_CANDIDATE_FILE);
    }
}
