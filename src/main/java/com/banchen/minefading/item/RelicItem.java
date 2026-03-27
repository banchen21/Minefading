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

// 遗物物品的通用基类，持有行为类型和描述语言键
public class RelicItem extends Item
{
    private final RelicAction action;  // 使用时触发的行为
    private final String flavorKey;    // 语言文件中的 Tooltip 描述键

    public RelicItem(RelicAction action, String flavorKey)
    {
        super(new Properties().stacksTo(1)); // 遗物不可叠加
        this.action = action;
        this.flavorKey = flavorKey;
    }

    // 右键使用时，在服务端执行对应的遗物行为
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, net.minecraft.world.entity.player.Player player, InteractionHand usedHand)
    {
        ItemStack stack = player.getItemInHand(usedHand);
        if (level.isClientSide)
            return InteractionResultHolder.success(stack); // 客户端侧不处理

        if (!(player instanceof ServerPlayer serverPlayer))
            return InteractionResultHolder.pass(stack);

        RelicRuntime.handleAction(serverPlayer, action);
        return InteractionResultHolder.consume(stack);
    }

    // 显示斜体灰色的 Tooltip 描述文本
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag)
    {
        tooltip.add(Component.translatable(flavorKey).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }
}
