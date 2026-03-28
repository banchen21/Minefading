package com.banchen.minefading.item;

import com.banchen.minefading.Minefading;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

// 模组自定义创造模式物品栏标签页
public class ModCreativeTabs
{
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Minefading.MODID);

    public static final RegistryObject<CreativeModeTab> MINEFADING_TAB = CREATIVE_MODE_TABS.register("minefading", () ->
            CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.minefading"))
                    .icon(() -> new ItemStack(RelicItems.INHALER.get()))
                    .displayItems((parameters, output) ->
                    {
                        output.accept(RelicItems.INHALER.get());
                        output.accept(RelicItems.SHEDDING.get());
                        output.accept(RelicItems.DISCONNECT.get());
                        output.accept(RelicItems.TOWER.get());
                        output.accept(RelicItems.FINE_SAND.get());
                        output.accept(RelicItems.CAUSALITY.get());
                        output.accept(RelicItems.KRONOS.get());
                    })
                    .build());

    private ModCreativeTabs()
    {
    }
}
