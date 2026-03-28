package com.banchen.minefading.item;

import com.banchen.minefading.Config;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

// 吸入器：不可堆叠，使用药芯时消耗 1 点耐久。
public class InhalerItem extends RelicItem
{
    public InhalerItem()
    {
        super(RelicAction.INHALER, "item.minefading.inhaler.desc", new Properties().durability(Config.inhalerDurability));
    }

    // 主手吸入器右键生物时，优先把交互转发给副手细沙，避免被通用药芯判定拦截。
    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, net.minecraft.world.entity.player.Player player, LivingEntity interactionTarget, InteractionHand usedHand)
    {
        if (usedHand != InteractionHand.MAIN_HAND)
            return InteractionResult.PASS;

        ItemStack offhand = player.getOffhandItem();
        if (offhand.getItem() instanceof FineSandItem fineSandItem)
            return fineSandItem.interactLivingEntity(offhand, player, interactionTarget, InteractionHand.OFF_HAND);

        return InteractionResult.PASS;
    }
}