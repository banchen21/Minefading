package com.banchen.minefading.client;

import com.banchen.minefading.Minefading;
import com.banchen.minefading.relic.RelicRuntime;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// 客户端按键绑定注册，以及每 tick 将按键状态同步到 RelicRuntime
@Mod.EventBusSubscriber(modid = Minefading.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class MinefadingKeybinds
{
    // 柯罗诺斯慢时间快捷键，默认绑定 Z 键
    public static final KeyMapping KRONOS_SLOW = new KeyMapping(
            "key.minefading.kronos_slow",
            InputConstants.KEY_V,
            "key.categories.minefading"
    );

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event)
    {
        event.register(KRONOS_SLOW);
    }

    // 内部类订阅普通事件总线，每 tick 将按键状态写入 RelicRuntime
    @Mod.EventBusSubscriber(modid = Minefading.MODID, value = Dist.CLIENT)
    public static class RuntimeInput
    {
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event)
        {
            if (event.phase != TickEvent.Phase.END)
                return;

            RelicRuntime.setSlowTimeKeyDown(KRONOS_SLOW.isDown());
        }
    }
}
