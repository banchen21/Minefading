package com.banchen.minefading.item;

import com.banchen.minefading.Minefading;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

// 所有药芯物品的注册表，统一管理 7 件药芯
public class RelicItems
{
    // 物品延迟注册器
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Minefading.MODID);

    // 吸入器（主手使用，配合副手药芯触发效果）
    public static final RegistryObject<Item> INHALER   = ITEMS.register("inhaler",
            InhalerItem::new);
    // 蜕皮：触发回溯
    public static final RegistryObject<Item> SHEDDING  = ITEMS.register("shedding",
            () -> new RelicItem(RelicAction.SHEDDING,  "item.minefading.shedding.desc"));
    // 断线：立即创建存档点
    public static final RegistryObject<Item> DISCONNECT = ITEMS.register("disconnect",
            () -> new RelicItem(RelicAction.DISCONNECT, "item.minefading.disconnect.desc"));
    // 高塔：主动死亡（并清空当前回溯点）
    public static final RegistryObject<Item> TOWER     = ITEMS.register("tower",
            () -> new RelicItem(RelicAction.TOWER,     "item.minefading.tower.desc"));
    // 细沙：对命名生物记录追踪信息，回溯时带回
    public static final RegistryObject<Item> FINE_SAND = ITEMS.register("fine_sand",
            FineSandItem::new); // 细沙有独立的交互逻辑，使用专属类
    // 因果：替身代死并传送到替身位置
    public static final RegistryObject<Item> CAUSALITY = ITEMS.register("causality",
            () -> new RelicItem(RelicAction.CAUSALITY, "item.minefading.causality.desc"));
    // 柯罗诺斯：慢时并在前后自动存档
    public static final RegistryObject<Item> KRONOS    = ITEMS.register("kronos",
            () -> new RelicItem(RelicAction.CHRONOS,   "item.minefading.kronos.desc"));
}
