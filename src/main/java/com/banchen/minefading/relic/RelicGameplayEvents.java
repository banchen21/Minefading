package com.banchen.minefading.relic;

import com.banchen.minefading.Config;
import com.banchen.minefading.Minefading;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

// 药芯系统的客户端事件订阅器
// 负责驱动 RelicRuntime 的 tick 逻辑，以及拦截因果效果下的死亡事件
@Mod.EventBusSubscriber(modid = Minefading.MODID)
public class RelicGameplayEvents
{
    private static final long VANILLA_TICK_MS = 50L;
    private static final Field NEXT_TICK_TIME_FIELD = resolveNextTickTimeField();
    private static boolean wasSlowActive;
    private static double currentExtraWaitMs;

    // 每客户端 tick 结束时驱动药芯效果计时器
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;

        boolean slowActive = RelicRuntime.isKronosSlowActive();
        double maxExtraWaitMs = Math.max(0.0D, Config.kronosExtraWaitMaxMs);
        double rampStepMs = Math.max(0.1D, Config.kronosRampStepMs);
        if (slowActive)
            currentExtraWaitMs = Math.min(maxExtraWaitMs, currentExtraWaitMs + rampStepMs);
        else
            currentExtraWaitMs = Math.max(0.0D, currentExtraWaitMs - rampStepMs);

        long extraWaitMs = Math.round(currentExtraWaitMs);

        if (extraWaitMs > 0L)
        {
            // 小步长 sleep，降低“卡一下走一下”的体感
            try
            {
                Thread.sleep(extraWaitMs);
            }
            catch (InterruptedException ignored)
            {
                Thread.currentThread().interrupt();
            }

            // sleep 结束后再取当前时间，确保额外延迟真正计入 tick 间隔
            setNextTickTarget(event.getServer(), Util.getMillis() + VANILLA_TICK_MS);
        }
        else if (wasSlowActive)
        {
            // 松键瞬间立刻恢复正常节奏，不保留历史欠账
            setNextTickTarget(event.getServer(), Util.getMillis() + VANILLA_TICK_MS);
        }

        wasSlowActive = slowActive;

        RelicRuntime.onServerTick(event.getServer());
    }

    private static void setNextTickTarget(MinecraftServer server, long targetMillis)
    {
        if (NEXT_TICK_TIME_FIELD == null)
            return;

        try
        {
            NEXT_TICK_TIME_FIELD.setLong(server, targetMillis);
        }
        catch (IllegalAccessException ignored)
        {
        }
    }

    private static Field resolveNextTickTimeField()
    {
        // 开发环境优先命中可读字段名
        try
        {
            Field field = MinecraftServer.class.getDeclaredField("nextTickTime");
            field.setAccessible(true);
            return field;
        }
        catch (NoSuchFieldException ignored)
        {
        }

        // 生产环境回退：找 MinecraftServer 中唯一的 volatile long 字段
        for (Field field : MinecraftServer.class.getDeclaredFields())
        {
            if (field.getType() == long.class && Modifier.isVolatile(field.getModifiers()))
            {
                field.setAccessible(true);
                return field;
            }
        }

        return null;
    }

    // 客户端启动时从文件加载旁观者锁定数据
    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event)
    {
        RelicRuntime.loadSpectatorLocked(event.getServer());
    }

    // 玩家死亡时检查是否有因果效果可以拦截，若可以则取消死亡事件
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;

        if (RelicRuntime.tryHandleCausalityDeath(player, event.getSource()))
            event.setCanceled(true);
    }

    // 玩家复活时检查是否需要锁定为旁观者（高塔/倒计时极限死亡后）
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;

        if (RelicRuntime.consumeSpectatorLock(player.getUUID(), player.server))
            player.setGameMode(GameType.SPECTATOR);
    }

    // 拦截游戏模式切换：已被极限死亡锁定的玩家不得切换离开旁观者
    @SubscribeEvent
    public static void onPlayerChangeGameMode(PlayerEvent.PlayerChangeGameModeEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;

        if (RelicRuntime.isSpectatorLocked(player.getUUID()) && event.getNewGameMode() != GameType.SPECTATOR)
        {
            event.setCanceled(true);
            player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable("message.minefading.spectator_locked"),
                true
            );
        }
    }
}
