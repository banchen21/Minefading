package com.banchen.minefading.relic;

import com.banchen.minefading.Config;
import com.banchen.minefading.Minefading;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import org.slf4j.Logger;

// 药芯系统的客户端事件订阅器
// 负责驱动 RelicRuntime 的 tick 逻辑，以及拦截因果效果下的死亡事件
@Mod.EventBusSubscriber(modid = Minefading.MODID)
public class RelicGameplayEvents
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile double kronosSlowFactor;

    /** 返回当前时缓强度 (0.0 = 无, 1.0 = 最大)，客户端渲染用 */
    public static double getKronosSlowFactor() { return kronosSlowFactor; }

    public static void resetKronosSlowState()
    {
        // 时缓核心已移除，保留空状态重置
        kronosSlowFactor = 0.0D;
    }

    // 每客户端 tick 结束时驱动药芯效果计时器
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        kronosSlowFactor = 0.0D;

        RelicRuntime.onServerTick(event.getServer());
    }

    // 客户端启动时从文件加载旁观者锁定数据
    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event)
    {
        RelicRuntime.loadSpectatorLocked(event.getServer());
        RelicRuntime.loadCausalityCandidate(event.getServer());
    }

    // 玩家受到致死伤害时优先由因果接管，避免进入原版死亡流程
    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;

        if (player.getHealth() > event.getAmount())
            return;

        if (RelicRuntime.tryHandleCausalityLethalDamage(player))
            event.setCanceled(true);
    }

    // 兜底：若仍进入死亡事件，继续尝试取消死亡
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;

        if (RelicRuntime.tryHandleCausalityLethalDamage(player))
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
