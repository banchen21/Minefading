package com.banchen.minefading.relic;

import com.banchen.minefading.WorldRollbackManager;
import com.banchen.minefading.day.DayStateData;
import com.banchen.minefading.item.RelicAction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// 遗物系统的核心逻辑处理器，管理所有遗物的行为和状态
public class RelicRuntime
{
    // 因果效果持续时长（20 秒）
    private static final int CAUSALITY_TICKS = 20 * 20;
    // 柯罗诺斯效果持续时长（20 秒）
    private static final int KRONOS_TICKS = 20 * 20;

    // 当前激活因果效果的玩家及其剩余 tick 数
    private static final Map<UUID, Integer> causalityActiveTicks = new HashMap<>();
    // 当前激活柯罗诺斯效果的玩家及其剩余 tick 数
    private static final Map<UUID, Integer> kronosActiveTicks = new HashMap<>();
    // 柯罗诺斯效果结束后是否需要再次存档的标志
    private static final Map<UUID, Boolean> kronosNeedFinalizeSave = new HashMap<>();

    // 是否正在按下柯罗诺斯快捷键（客户端写入，服务端读取）
    private static boolean slowTimeKeyDown;

    // 根据遗物行为类型分发执行逻辑
    public static void handleAction(ServerPlayer player, RelicAction action)
    {
        switch (action)
        {
            case INHALER -> player.displayClientMessage(Component.literal("吸入器已装填。"), true);
            case SHEDDING -> rollback(player);
            case DISCONNECT -> createCheckpoint(player, "已存档。");
            case TOWER -> killPlayer(player, Component.literal("你选择了高塔。"));
            case CAUSALITY -> activateCausality(player);
            case CHRONOS -> activateKronos(player);
        }
    }

    // 细沙：将目标实体加入追踪，并立即保存整个世界快照
    public static void trackEntity(ServerPlayer player, LivingEntity entity)
    {
        MinecraftServer server = player.server;
        DayStateData data = DayStateData.get(server);

        data.trackEntity(entity.getUUID()); // 在 DayStateData 中记录追踪 UUID
        WorldRollbackManager.takeSnapshot(server); // 保存整个世界快照（含追踪实体信息）
        player.displayClientMessage(Component.literal("细沙记录完成，目标会在回溯时被带回。"), true);
    }

    // 每服务端 tick 调用：倒计时效果、柯罗诺斯放缓时间、结束后存档
    public static void onServerTick(MinecraftServer server)
    {
        tickMap(causalityActiveTicks);
        tickMap(kronosActiveTicks);

        // 柯罗诺斯激活中且按住快捷键时，每隔一 tick 将时间倒退 1（实现慢时间效果）
        if (slowTimeKeyDown && !kronosActiveTicks.isEmpty())
        {
            for (ServerLevel level : server.getAllLevels())
            {
                if (level.getGameTime() % 2L == 0L)
                    level.setDayTime(level.getDayTime() - 1L);
            }
        }

        // 柯罗诺斯效果结束后，为该玩家补存一次档
        for (UUID playerId : kronosNeedFinalizeSave.keySet().toArray(UUID[]::new))
        {
            if (Boolean.TRUE.equals(kronosNeedFinalizeSave.get(playerId)) && !kronosActiveTicks.containsKey(playerId))
            {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null)
                    createCheckpoint(player, "柯罗诺斯效果结束，已再次存档。");
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
        player.displayClientMessage(Component.literal("因果生效：替身承受了死亡。"), true);
        causalityActiveTicks.remove(player.getUUID());
        return true;
    }

    // 由客户端 MinefadingKeybinds 每 tick 写入按键状态
    public static void setSlowTimeKeyDown(boolean keyDown)
    {
        slowTimeKeyDown = keyDown;
    }

    // 蜕皮：触发全世界回档（调度器将在客户端 tick 执行断线→还原→重载）
    private static void rollback(ServerPlayer player)
    {
        MinecraftServer server = player.server;
        if (!WorldRollbackManager.hasSnapshot(server))
        {
            player.displayClientMessage(Component.literal("尚无可用快照，无法蜕皮。"), true);
            return;
        }
        WorldRollbackManager.scheduleRestore(server);
        player.displayClientMessage(Component.literal("蜕皮激活，世界将回溯……"), true);
    }

    // 断线/柯罗诺斯：创建全世界快照作为存档点
    private static void createCheckpoint(ServerPlayer player, String message)
    {
        WorldRollbackManager.takeSnapshot(player.server); // 保存整个世界（含玩家状态）
        player.displayClientMessage(Component.literal(message), true);
    }

    private static void activateCausality(ServerPlayer player)
    {
        causalityActiveTicks.put(player.getUUID(), CAUSALITY_TICKS);
        player.displayClientMessage(Component.literal("因果已激活。"), true);
    }

    private static void activateKronos(ServerPlayer player)
    {
        createCheckpoint(player, "柯罗诺斯激活，已先存档。");
        kronosActiveTicks.put(player.getUUID(), KRONOS_TICKS);
        kronosNeedFinalizeSave.put(player.getUUID(), true);
        player.displayClientMessage(Component.literal("按住快捷键可放缓时间。"), true);
    }

    // 高塔：玩家主动死亡
    private static void killPlayer(ServerPlayer player, Component message)
    {
        player.displayClientMessage(message, false);
        player.kill();
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
