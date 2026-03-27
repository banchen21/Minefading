package com.banchen.minefading.client;

import com.banchen.minefading.Minefading;
import com.banchen.minefading.day.DayMode;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// 客户端 HUD 覆盖层：
// 1. 新一天或回档时显示全屏黑幕过渡
// 2. 持续在血条上方显示当前天数信息
@Mod.EventBusSubscriber(modid = Minefading.MODID, value = Dist.CLIENT)
public class ClientDayOverlay
{
    // 全屏黑幕过渡用的文字和剩余时长
    private static String overlayMessage = "";
    private static int overlayTicksLeft;

    // 持续显示在血条上方的 HUD 文字
    private static String hudText = "";

    // 触发一次全屏黑幕过渡（新一天/回档时调用）
    public static void showDayMessage(DayMode mode, int displayedDay, int remainingDays, int overlayTicks)
    {
        overlayMessage = mode == DayMode.COUNTDOWN
                ? "还剩" + remainingDays + "天"
                : "第" + displayedDay + "天";
        overlayTicksLeft = overlayTicks;
    }

    // 更新血条上方的持久 HUD 文字（每 tick 由 DaySystemEvents 调用）
    public static void updateHud(DayMode mode, int displayedDay, int remainingDays)
    {
        hudText = mode == DayMode.COUNTDOWN
                ? "还剩" + remainingDays + "天"
                : "第" + displayedDay + "天";
    }

    // 清除 HUD 文字（玩家退出游戏时调用）
    public static void clearHud()
    {
        hudText = "";
    }

    // 每客户端 tick 减少全屏黑幕的剩余时长（暂停时不减少）
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || overlayTicksLeft <= 0)
            return;

        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isPaused())
            overlayTicksLeft--;
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event)
    {
        Minecraft minecraft = Minecraft.getInstance();
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();

        // 全屏黑幕过渡：alpha 随剩余时长降低，实现淡出效果
        if (overlayTicksLeft > 0)
        {
            int alpha = Math.min(200, 60 + overlayTicksLeft * 2);
            event.getGuiGraphics().fill(0, 0, width, height, alpha << 24);
            event.getGuiGraphics().drawCenteredString(minecraft.font, overlayMessage, width / 2, height / 2 - 4, 0xFFFFFF);
        }

        // 持久 HUD：显示在血条上方（血条约在底部 39px 处）
        if (!hudText.isEmpty() && minecraft.player != null && !minecraft.player.isDeadOrDying())
        {
            // 左侧与血条对齐，y 在血条上方约 10px
            int x = width / 2 - 91;
            int y = height - 52;
            event.getGuiGraphics().drawString(minecraft.font, hudText, x, y, 0xFFFFFF, false);
        }
    }
}
