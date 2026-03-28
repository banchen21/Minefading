package com.banchen.minefading.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Overlay;

/**
 * 回档重载期间的全黑 Overlay，用于覆盖原版世界加载动画。
 * <p>
 * 可包装另一个 Overlay（如原版 LevelLoadingScreen）：
 * 先让被包装的 Overlay 正常 render/tick（保持其内部状态机和完成回调），
 * 然后在其上绘制纯黑，用户看不到被包装 Overlay 的画面。
 */
public class BlackTransitionOverlay extends Overlay
{
    private final Overlay wrapped;

    /** 独立黑幕（不包装其他 Overlay） */
    public BlackTransitionOverlay()
    {
        this(null);
    }

    /** 包装另一个 Overlay，在其渲染后覆盖纯黑 */
    public BlackTransitionOverlay(Overlay wrapped)
    {
        this.wrapped = wrapped;
    }

    @Override
    public boolean isPauseScreen()
    {
        // 必须返回 false，否则集成服务端会被暂停，世界无法完成加载
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick)
    {
        // 先让被包装的 Overlay 正常渲染（保持其完成回调 onFinish 等逻辑正常触发）
        if (wrapped != null)
        {
            wrapped.render(graphics, mouseX, mouseY, partialTick);
        }
        // 然后用纯黑覆盖全屏
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), 0xFF000000);
    }
}
