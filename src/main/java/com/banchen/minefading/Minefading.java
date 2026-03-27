package com.banchen.minefading;

import com.banchen.minefading.item.RelicItems;
import com.mojang.logging.LogUtils;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

// 模组主入口类，负责初始化所有系统
@Mod(Minefading.MODID)
public class Minefading
{
    // 模组 ID，与 mods.toml 保持一致
    public static final String MODID = "minefading";

    private static final Logger LOGGER = LogUtils.getLogger();

    public Minefading(FMLJavaModLoadingContext context)
    {
        IEventBus modEventBus = context.getModEventBus();

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::addCreative); // 注入创造模式物品栏
        MinecraftForge.EVENT_BUS.register(this);
        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC); // 注册配置文件
        RelicItems.ITEMS.register(modEventBus); // 注册所有遗物物品
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {
        LOGGER.info("Minefading common setup initialized.");
        LOGGER.info(Config.introduction); // 输出配置中的介绍文本到日志
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event)
    {
        LOGGER.info("Minefading server starting.");
    }

    // 将所有遗物物品注入"材料"和"搜索"两个创造模式标签页
    private void addCreative(BuildCreativeModeTabContentsEvent event)
    {
        if (event.getTabKey() != CreativeModeTabs.INGREDIENTS && event.getTabKey() != CreativeModeTabs.SEARCH)
            return;

        event.accept(RelicItems.INHALER);    // 吸入器
        event.accept(RelicItems.SHEDDING);   // 蜕皮
        event.accept(RelicItems.DISCONNECT); // 断线
        event.accept(RelicItems.TOWER);      // 高塔
        event.accept(RelicItems.FINE_SAND);  // 细沙
        event.accept(RelicItems.CAUSALITY);  // 因果
        event.accept(RelicItems.KRONOS);     // 柯罗诺斯
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ClientModEvents
    {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event)
        {
            LOGGER.info("Minefading client setup initialized.");
        }
    }
}