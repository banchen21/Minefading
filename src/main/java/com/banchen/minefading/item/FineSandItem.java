package com.banchen.minefading.item;

import com.banchen.minefading.relic.RelicRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

// 细沙：右键有自定义名称的生物，将其注册为追踪目标（回档时会被传送回位置）
public class FineSandItem extends Item
{
    public FineSandItem()
    {
        super(new Properties().stacksTo(16));
    }

    // 右键攻击生物时触发，检查是否有自定义名称
    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, net.minecraft.world.entity.player.Player player, LivingEntity interactionTarget, InteractionHand usedHand)
    {
        if (player.level().isClientSide)
            return InteractionResult.SUCCESS;

        if (!(player instanceof ServerPlayer serverPlayer))
            return InteractionResult.PASS;

        // 只允许追踪有命名的生物（用于叙事目标角色）
        if (!interactionTarget.hasCustomName())
        {
            serverPlayer.displayClientMessage(Component.translatable("message.minefading.fine_sand_requires_named"), true);
            return InteractionResult.PASS;
        }

        RelicRuntime.trackEntity(serverPlayer, interactionTarget);
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag)
    {
        tooltip.add(Component.translatable("item.minefading.fine_sand.desc").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }
}
