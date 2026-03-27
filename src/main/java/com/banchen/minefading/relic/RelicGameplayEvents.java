package com.banchen.minefading.relic;

import com.banchen.minefading.Minefading;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// 遗物系统的服务端事件订阅器
// 负责驱动 RelicRuntime 的 tick 逻辑，以及拦截因果效果下的死亡事件
@Mod.EventBusSubscriber(modid = Minefading.MODID)
public class RelicGameplayEvents
{
    // 每服务端 tick 结束时驱动遗物效果计时器
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;

        RelicRuntime.onServerTick(event.getServer());
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
}
