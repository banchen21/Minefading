package com.banchen.minefading;

import com.banchen.minefading.effect.ModEffects;
import com.banchen.minefading.item.RelicItems;
import com.banchen.minefading.item.ModCreativeTabs;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
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
        MinecraftForge.EVENT_BUS.register(this);
        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC); // 注册配置文件
        ModEffects.EFFECTS.register(modEventBus); // 注册自定义状态效果
        RelicItems.ITEMS.register(modEventBus); // 注册所有药芯物品
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus); // 注册自定义创造模式标签页
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