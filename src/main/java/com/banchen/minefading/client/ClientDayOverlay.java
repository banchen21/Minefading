package com.banchen.minefading.client;

import com.banchen.minefading.Config;
import com.banchen.minefading.Minefading;
import com.banchen.minefading.WorldRollbackManager;
import com.banchen.minefading.day.DayMode;
import com.banchen.minefading.day.DayStateData;
import com.banchen.minefading.relic.RelicRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;
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

    // 时缓朦胧层的客户端平滑插值因子（0.0~1.0）
    private static float kronosOverlayAlpha = 0.0F;

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
        if (WorldRollbackManager.isRestoring())
            return;

        Minecraft minecraft = Minecraft.getInstance();
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();

        // 全屏黑幕过渡：alpha 随剩余时长降低，实现淡出效果
        if (overlayTicksLeft > 0)
        {
            int alpha = Math.min(200, 60 + overlayTicksLeft * 2);
            event.getGuiGraphics().fill(0, 0, width, height, alpha << 24);

            // 放大中间提示文字，提升可读性
            float overlayTextScale = Config.overlayTextScale;
            event.getGuiGraphics().pose().pushPose();
            event.getGuiGraphics().pose().scale(overlayTextScale, overlayTextScale, 1.0F);
            int scaledCenterX = Math.round((width / 2.0F) / overlayTextScale);
            int scaledY = Math.round((height / 2.0F - 8.0F) / overlayTextScale);
            event.getGuiGraphics().drawCenteredString(minecraft.font, overlayMessage, scaledCenterX, scaledY, 0xFFFFFF);
            event.getGuiGraphics().pose().popPose();
        }

        // 持久 HUD：显示在血条上方（血条约在底部 39px 处）
        if (!hudText.isEmpty() && minecraft.player != null && !minecraft.player.isDeadOrDying())
        {
            // 左侧与血条对齐，y 在血条上方约 10px
            int x = width / 2 - 91;
            int y = height - 52;
            event.getGuiGraphics().drawString(minecraft.font, hudText, x, y, 0xFFFFFF, false);

            int statusX = x + minecraft.font.width(hudText) + 4;

            // 因果激活时在天数文字右侧显示标识
            if (RelicRuntime.isCausalityActive(minecraft.player.getUUID()))
            {
                event.getGuiGraphics().drawString(minecraft.font, "因果", statusX, y, 0xFFD700, false);
                statusX += minecraft.font.width("因果") + 4;
            }

            MinecraftServer server = minecraft.getSingleplayerServer();
            if (server != null && DayStateData.get(server).hasTrackedEntity())
            {
                event.getGuiGraphics().drawString(minecraft.font, "细沙", statusX, y, 0xE6D3A3, false);
            }
        }

        // 时缓朦胧层：客户端侧平滑渐变进入/退出
        float targetAlpha = (float) KronosSlowController.getOverlayStrength();
        float lerpSpeed = 0.05F; // 每帧插值步长，越小越平滑
        if (kronosOverlayAlpha < targetAlpha)
            kronosOverlayAlpha = Math.min(targetAlpha, kronosOverlayAlpha + lerpSpeed);
        else if (kronosOverlayAlpha > targetAlpha)
            kronosOverlayAlpha = Math.max(targetAlpha, kronosOverlayAlpha - lerpSpeed);

        if (kronosOverlayAlpha > 0.001F)
        {
            int maxAlpha = (int) (kronosOverlayAlpha * 130); // 边缘最大 alpha（加强）
            if (maxAlpha > 0)
                renderKronosEllipticalMist(event.getGuiGraphics(), width, height, maxAlpha, kronosOverlayAlpha);
        }
    }

    private static void renderKronosEllipticalMist(net.minecraft.client.gui.GuiGraphics guiGraphics, int width, int height, int maxAlpha, float strength)
    {
        final int color = 0x4E8FFF;
        final int centerX = width / 2;
        final int centerY = height / 2;

        // 随强度从中心向外扩散：strength 越高，覆盖半径越大
        final double outerRx = width * (0.25D + 0.85D * strength);
        final double outerRy = height * (0.22D + 0.82D * strength);
        final int bands = 18;

        for (int i = 0; i < bands; i++)
        {
            double t0 = i / (double) bands;
            double t1 = (i + 1) / (double) bands;

            double innerRx = outerRx * t0;
            double innerRy = outerRy * t0;
            double outerBandRx = outerRx * t1;
            double outerBandRy = outerRy * t1;

            // 越靠边缘 alpha 越高，中间更浅
            double edgeCurve = Math.pow(t1, 1.8D);
            double edgeBoost = 0.42D * Math.pow(t1, 4.0D); // 让最外圈更深
            int bandAlpha = (int) (maxAlpha * Math.min(1.0D, edgeCurve + edgeBoost));
            if (bandAlpha <= 0)
                continue;

            drawEllipseRing(guiGraphics, centerX, centerY, innerRx, innerRy, outerBandRx, outerBandRy, (bandAlpha << 24) | color, width, height);
        }
    }

    private static void drawEllipseRing(net.minecraft.client.gui.GuiGraphics guiGraphics,
                                        int centerX,
                                        int centerY,
                                        double innerRx,
                                        double innerRy,
                                        double outerRx,
                                        double outerRy,
                                        int argb,
                                        int width,
                                        int height)
    {
        int yStart = Math.max(0, (int) Math.floor(centerY - outerRy));
        int yEnd = Math.min(height - 1, (int) Math.ceil(centerY + outerRy));

        for (int y = yStart; y <= yEnd; y++)
        {
            double dy = (y + 0.5D) - centerY;
            double outerTerm = 1.0D - (dy * dy) / (outerRy * outerRy);
            if (outerTerm <= 0.0D)
                continue;

            double outerDx = Math.sqrt(outerTerm) * outerRx;
            int leftOuter = Math.max(0, (int) Math.floor(centerX - outerDx));
            int rightOuter = Math.min(width, (int) Math.ceil(centerX + outerDx));
            if (leftOuter >= rightOuter)
                continue;

            double innerDx = 0.0D;
            if (innerRx > 0.0D && innerRy > 0.0D && Math.abs(dy) < innerRy)
            {
                double innerTerm = 1.0D - (dy * dy) / (innerRy * innerRy);
                if (innerTerm > 0.0D)
                    innerDx = Math.sqrt(innerTerm) * innerRx;
            }

            int leftInner = Math.max(0, (int) Math.floor(centerX - innerDx));
            int rightInner = Math.min(width, (int) Math.ceil(centerX + innerDx));

            // 左半环
            if (leftOuter < leftInner)
                guiGraphics.fill(leftOuter, y, leftInner, y + 1, argb);
            // 右半环
            if (rightInner < rightOuter)
                guiGraphics.fill(rightInner, y, rightOuter, y + 1, argb);
        }
    }

    /**
     * 唯一黑幕机制：每帧 Screen 渲染完成后、Overlay 渲染前触发。
     */
    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event)
    {
        if (!WorldRollbackManager.isRestoring())
            return;

        Minecraft mc = Minecraft.getInstance();

        // 在 Screen 层绘制纯黑
        event.getGuiGraphics().fill(0, 0,
                mc.getWindow().getGuiScaledWidth(),
                mc.getWindow().getGuiScaledHeight(), 0xFF000000);

        // 确保 Overlay 也是黑幕（包装原版 LevelLoadingScreen 使其 render() 仍运行、
        // 进度检测和 onFinish 回调正常触发，但画面被黑色覆盖）
        net.minecraft.client.gui.screens.Overlay overlay = mc.getOverlay();
        if (overlay == null)
        {
            mc.setOverlay(new BlackTransitionOverlay());
        }
        else if (!(overlay instanceof BlackTransitionOverlay))
        {
            mc.setOverlay(new BlackTransitionOverlay(overlay));
        }
    }

    // 打开世界选择列表时，清理已被删除世界的孤立快照
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event)
    {
        if (event.getNewScreen() instanceof SelectWorldScreen)
        {
            WorldRollbackManager.cleanOrphanedSnapshots();
        }
    }
}
