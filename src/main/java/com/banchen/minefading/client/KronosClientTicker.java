package com.banchen.minefading.client;

import com.banchen.minefading.Config;
import com.banchen.minefading.Minefading;
import com.banchen.minefading.relic.RelicRuntime;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Timer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// 将客户端本地 timer 与服务端慢时间同步，避免只出现“服务器慢、客户端观感不慢”的错位。
@Mod.EventBusSubscriber(modid = Minefading.MODID, value = Dist.CLIENT)
public class KronosClientTicker
{
    private static final float VANILLA_MS_PER_TICK = 50.0F;
    private static final float EPSILON = 0.001F;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;

        Minecraft minecraft = Minecraft.getInstance();
        Timer timer = minecraft.timer;

        float targetMsPerTick = resolveTargetMsPerTick(minecraft);
        if (Math.abs(timer.msPerTick - targetMsPerTick) <= EPSILON)
            return;

        timer.msPerTick = targetMsPerTick;
        timer.lastMs = Util.getMillis();
    }

    private static float resolveTargetMsPerTick(Minecraft minecraft)
    {
        if (minecraft.level == null || minecraft.player == null || minecraft.isPaused())
            return VANILLA_MS_PER_TICK;

        if (!RelicRuntime.isKronosSlowActive())
            return VANILLA_MS_PER_TICK;

        return (float) (VANILLA_MS_PER_TICK / Math.max(0.01D, Config.kronosSpeedRatio));
    }
}
