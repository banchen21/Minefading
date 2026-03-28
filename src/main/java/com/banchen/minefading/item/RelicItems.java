package com.banchen.minefading.item;

import com.banchen.minefading.Minefading;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

// 所有药芯物品的注册表，统一管理 7 件药芯
public class RelicItems
{
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Minefading.MODID);

    public static final RegistryObject<Item> INHALER   = ITEMS.register("inhaler",
            InhalerItem::new);
    public static final RegistryObject<Item> SHEDDING  = ITEMS.register("shedding",
            () -> new RelicItem(RelicAction.SHEDDING,  "item.minefading.shedding.desc"));
    public static final RegistryObject<Item> DISCONNECT = ITEMS.register("disconnect",
            () -> new RelicItem(RelicAction.DISCONNECT, "item.minefading.disconnect.desc"));
    public static final RegistryObject<Item> TOWER     = ITEMS.register("tower",
            () -> new RelicItem(RelicAction.TOWER,     "item.minefading.tower.desc"));
    public static final RegistryObject<Item> FINE_SAND = ITEMS.register("fine_sand",
            FineSandItem::new); // 细沙有独立的交互逻辑，使用专属类
    public static final RegistryObject<Item> CAUSALITY = ITEMS.register("causality",
            () -> new RelicItem(RelicAction.CAUSALITY, "item.minefading.causality.desc"));
    public static final RegistryObject<Item> KRONOS    = ITEMS.register("kronos",
            () -> new RelicItem(RelicAction.CHRONOS,   "item.minefading.kronos.desc"));

    private RelicItems()
    {
    }
}
