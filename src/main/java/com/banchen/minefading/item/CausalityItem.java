package com.banchen.minefading.item;

import com.banchen.minefading.relic.RelicRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

// 因果：右键生物后将其绑定为代死目标，并立即激活效果
public class CausalityItem extends RelicItem
{
    public CausalityItem()
    {
        super(RelicAction.CAUSALITY, "item.minefading.causality.desc");
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, net.minecraft.world.entity.player.Player player, LivingEntity interactionTarget, InteractionHand usedHand)
    {
        if (player.level().isClientSide)
            return InteractionResult.SUCCESS;

        if (!(player instanceof ServerPlayer serverPlayer))
            return InteractionResult.PASS;

        if (usedHand != InteractionHand.OFF_HAND || player.getMainHandItem().getItem() != RelicItems.INHALER.get())
        {
            serverPlayer.displayClientMessage(Component.translatable("message.minefading.use_causality_with_inhaler"), true);
            return InteractionResult.CONSUME;
        }

        if (RelicRuntime.bindCausalityTarget(serverPlayer, interactionTarget))
        {
            player.getMainHandItem().hurtAndBreak(1, serverPlayer, brokenPlayer -> brokenPlayer.broadcastBreakEvent(InteractionHand.MAIN_HAND));
            stack.shrink(1);
        }
        return InteractionResult.CONSUME;
    }
}
