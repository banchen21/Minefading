package com.banchen.minefading.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 回档过渡期间全屏黑幕 Screen。
 * - 覆盖全部画面（包括主菜单）
 * - 不响应任何输入，不允许 ESC 关闭
 * - 在 WorldRollbackManager 完成回档前持续显示
 */
public class BlackTransitionScreen extends Screen
{
    public BlackTransitionScreen()
    {
        super(Component.empty());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick)
    {
        // 纯黑，不调用 super.render() 避免绘制背景纹理
        graphics.fill(0, 0, this.width, this.height, 0xFF000000);
    }

    @Override
    public boolean shouldCloseOnEsc()
    {
        return false;
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }
}
