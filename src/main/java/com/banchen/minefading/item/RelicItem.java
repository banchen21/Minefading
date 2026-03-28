package com.banchen.minefading.item;

import com.banchen.minefading.relic.RelicRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

// 药芯物品的通用基类，持有行为类型和描述语言键
public class RelicItem extends Item
{
    private final RelicAction action;  // 使用时触发的行为
    private final String flavorKey;    // 语言文件中的 Tooltip 描述键

    public RelicItem(RelicAction action, String flavorKey)
    {
        this(action, flavorKey, new Properties().stacksTo(16));
    }

    protected RelicItem(RelicAction action, String flavorKey, Properties properties)
    {
        super(properties);
        this.action = action;
        this.flavorKey = flavorKey;
    }

    // 右键使用时，在客户端执行对应的药芯行为
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, net.minecraft.world.entity.player.Player player, InteractionHand usedHand)
    {
        ItemStack stack = player.getItemInHand(usedHand);
        if (level.isClientSide)
            return InteractionResultHolder.success(stack); // 客户端侧不处理

        if (!(player instanceof ServerPlayer serverPlayer))
            return InteractionResultHolder.pass(stack);

        // 使用规则：主手吸入器 + 副手药芯。
        if (action == RelicAction.INHALER)
        {
            if (usedHand != InteractionHand.MAIN_HAND)
                return InteractionResultHolder.pass(stack);

            ItemStack offhand = player.getOffhandItem();
            if (offhand.isEmpty())
            {
                serverPlayer.displayClientMessage(Component.translatable("message.minefading.inhaler_requires_core"), true);
                return InteractionResultHolder.consume(stack);
            }

            if (offhand.getItem() instanceof FineSandItem)
            {
                serverPlayer.displayClientMessage(Component.translatable("message.minefading.use_fine_sand_with_inhaler"), true);
                return InteractionResultHolder.consume(stack);
            }

            if (offhand.getItem() instanceof RelicItem core && core.action != RelicAction.INHALER)
            {
                if (RelicRuntime.handleAction(serverPlayer, core.action))
                {
                    stack.hurtAndBreak(1, serverPlayer, brokenPlayer -> brokenPlayer.broadcastBreakEvent(InteractionHand.MAIN_HAND));
                    offhand.shrink(1);
                }
                return InteractionResultHolder.consume(stack);
            }

            serverPlayer.displayClientMessage(Component.translatable("message.minefading.invalid_core"), true);
            return InteractionResultHolder.consume(stack);
        }

        // 药芯本体不直接触发，必须通过主手吸入器驱动。
        serverPlayer.displayClientMessage(Component.translatable("message.minefading.use_inhaler_with_core"), true);
        return InteractionResultHolder.consume(stack);
    }

    // 显示功能说明（白色）+ 斜体灰色的 Tooltip 味道文本
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag)
    {
        String funcKey = flavorKey.replace(".desc", ".func");
        tooltip.add(Component.translatable(funcKey).withStyle(ChatFormatting.WHITE));
        tooltip.add(Component.translatable(flavorKey).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }
}
