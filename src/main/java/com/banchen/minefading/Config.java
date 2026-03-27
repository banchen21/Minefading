package com.banchen.minefading;

import com.banchen.minefading.day.DayMode;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

// 模组配置类，所有配置项存储在 common 配置文件中
@Mod.EventBusSubscriber(modid = Minefading.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config
{
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // 天数显示模式：NORMAL（正计时）或 COUNTDOWN（倒计时）
    private static final ForgeConfigSpec.EnumValue<DayMode> MODE = BUILDER
            .comment("切换天数显示模式：NORMAL 为正常模式，COUNTDOWN 为倒计时模式。")
            .defineEnum("mode", DayMode.NORMAL);

    // 倒计时模式下的总天数上限
    private static final ForgeConfigSpec.IntValue COUNTDOWN_DAYS = BUILDER
            .comment("倒计时模式下的总天数。")
            .defineInRange("countdownDays", 30, 1, Integer.MAX_VALUE);

    // 黑屏覆盖层显示时长（秒），内部转换为 tick 使用
    private static final ForgeConfigSpec.IntValue OVERLAY_SECONDS = BUILDER
            .comment("黑屏天数提示持续显示的时长，单位为秒。")
            .defineInRange("overlaySeconds", 3, 1, 30);

    // 启动时输出到日志的介绍文本
    private static final ForgeConfigSpec.ConfigValue<String> INTRODUCTION = BUILDER
            .comment("常规初始化阶段输出到日志中的介绍文本。")
            .define("introduction", "Relics of the Fading City Special: Minefading | 《亡都遗骨》特别篇：我的余晖");

    static final ForgeConfigSpec SPEC = BUILDER.build();

    // 以下为运行时读取的配置值，供其他模块直接引用
    public static DayMode mode;
    public static int countdownDays;
    public static int overlayTicks; // 已转换为 tick 单位（秒 × 20）
    public static String introduction;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event)
    {
        mode = MODE.get();
        countdownDays = COUNTDOWN_DAYS.get();
        overlayTicks = OVERLAY_SECONDS.get() * 20; // 秒转 tick
        introduction = INTRODUCTION.get();
    }
}