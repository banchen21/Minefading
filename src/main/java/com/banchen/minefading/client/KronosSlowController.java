package com.banchen.minefading.client;

import com.banchen.minefading.Config;
import com.banchen.minefading.Minefading;
import com.banchen.minefading.effect.ModEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Minefading.MODID, value = Dist.CLIENT)
public class KronosSlowController
{
    private static boolean lastKeyDown;
    private static boolean slowActive;

    public static double getOverlayStrength()
    {
        return slowActive ? 1.0D : 0.0D;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null)
        {
            if (slowActive)
                restoreNormalTickrate(minecraft);
            lastKeyDown = false;
            slowActive = false;
            return;
        }

        boolean keyDown = MinefadingKeybinds.KRONOS_SLOW_KEY.isDown();
        boolean kronosActive = minecraft.player.hasEffect(ModEffects.KRONOS_ACTIVE.get());

        // 没有柯罗诺斯状态时，任何按键都不允许触发时缓，并且立即恢复默认 tickrate。
        if (!kronosActive)
        {
            if (slowActive)
                restoreNormalTickrate(minecraft);
            lastKeyDown = keyDown;
            return;
        }

        if (keyDown && !lastKeyDown)
            applySlow(minecraft);
        else if (!keyDown && lastKeyDown)
            restoreNormalTickrate(minecraft);

        lastKeyDown = keyDown;
    }

    private static void applySlow(Minecraft minecraft)
    {
        int targetTickrate = Math.max(1, Math.min(20, (int) Math.round(20.0D * Config.kronosSpeedRatio)));
        executeTimeclockCommands(minecraft,
                "timeclock pauseTime false",
                "timeclock tickrate " + targetTickrate);
        slowActive = true;
    }

    private static void restoreNormalTickrate(Minecraft minecraft)
    {
        executeTimeclockCommands(minecraft,
                "timeclock tickrate 20",
                "timeclock pauseTime false");
        slowActive = false;
    }

    private static void executeTimeclockCommands(Minecraft minecraft, String... commands)
    {
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server != null)
        {
            server.execute(() -> {
                CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput().withPermission(4);
                for (String command : commands)
                    server.getCommands().performPrefixedCommand(source, command);
            });
            return;
        }

        if (minecraft.player != null && minecraft.player.connection != null)
        {
            for (String command : commands)
                minecraft.player.connection.sendCommand(command);
        }
    }
}
