package com.banchen.minefading.relic;

import com.banchen.minefading.Config;
import com.banchen.minefading.Minefading;
import com.banchen.minefading.WorldRollbackManager;
import com.banchen.minefading.day.DayStateData;
import com.banchen.minefading.effect.ModEffects;
import com.banchen.minefading.item.RelicAction;
import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// 药芯系统的核心逻辑处理器，管理所有药芯的行为和状态
public class RelicRuntime
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final String SPECTATOR_LOCK_FILE = "spectator_locked.json";
    // 当前激活因果效果的玩家及其剩余 tick 数
    private static final Map<UUID, Integer> causalityActiveTicks = new HashMap<>();
    // 当前激活柯罗诺斯效果的玩家及其剩余 tick 数
    private static final Map<UUID, Integer> kronosActiveTicks = new HashMap<>();
    // 柯罗诺斯效果结束后是否需要再次存档的标志
    private static final Map<UUID, Boolean> kronosNeedFinalizeSave = new HashMap<>();
    // 复活后需要切换为旁观者模式的玩家（极限死亡后锁定）
    private static final Set<UUID> pendingSpectatorLock = new HashSet<>();
    // 已被锁定为旁观者、不得切换回其他模式的玩家
    private static final Set<UUID> spectatorLocked = new HashSet<>();

    // 是否正在按下柯罗诺斯快捷键（客户端写入，客户端读取）
    private static volatile boolean slowTimeKeyDown;

    // 根据药芯行为类型分发执行逻辑
    public static boolean handleAction(ServerPlayer player, RelicAction action)
    {
        return switch (action)
        {
            case INHALER -> false;
            case SHEDDING -> rollback(player);
            case DISCONNECT -> createCheckpoint(player, "message.minefading.disconnect_saved");
            case TOWER -> killPlayer(player, "message.minefading.tower_selected");
            case CAUSALITY -> activateCausality(player);
            case CHRONOS -> activateKronos(player);
        };
    }

    // 细沙：将目标实体加入追踪，保存其完整NBT，并立即保存整个世界快照
    public static void trackEntity(ServerPlayer player, LivingEntity entity)
    {
        MinecraftServer server = player.server;
        DayStateData data = DayStateData.get(server);

        data.trackEntity(entity.getUUID(), entity, server); // 保存UUID、NBT和维度信息
        WorldRollbackManager.takeSnapshot(server); // 保存整个世界快照
        player.displayClientMessage(Component.translatable("message.minefading.fine_sand_recorded"), true);
    }

    // 每客户端 tick 调用：倒计时效果、柯罗诺斯放缓时间、结束后存档
    public static void onServerTick(MinecraftServer server)
    {
        tickMap(causalityActiveTicks);
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

    // 因果死亡拦截：若玩家激活了因果且存在符合条件的替身，替身替玩家承受死亡
    // 返回 true 表示拦截成功（事件应被取消）
    public static boolean tryHandleCausalityDeath(ServerPlayer player, DamageSource source)
    {
        if (!causalityActiveTicks.containsKey(player.getUUID()))
            return false;

        LivingEntity substitute = findCausalitySubstitute(player);
        if (substitute == null)
            return false;

        player.setHealth(Math.max(1.0F, player.getMaxHealth() * 0.5F));
        if (substitute.level() instanceof ServerLevel level)
            player.teleportTo(level, substitute.getX(), substitute.getY(), substitute.getZ(), player.getYRot(), player.getXRot());
        substitute.hurt(substitute.damageSources().magic(), Float.MAX_VALUE);
        player.displayClientMessage(Component.translatable("message.minefading.causality_triggered"), true);
        causalityActiveTicks.remove(player.getUUID());
        return true;
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

    private static boolean activateCausality(ServerPlayer player)
    {
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
        for (ServerLevel level : server.getAllLevels())
        {
            for (Entity entity : level.getAllEntities())
            {
                if (!(entity instanceof LivingEntity living))
                    continue;
                if (living == player)
                    continue;
                if (!living.hasCustomName())
                    continue;
                if (!expectedName.equals(living.getCustomName().getString()))
                    continue;
                if (living.getMaxHealth() <= 20.0F)
                    continue;
                return living;
            }
        }
        return null;
    }
}
