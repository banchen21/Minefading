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

    // 中央黑幕提示文字的缩放倍率
    private static final ForgeConfigSpec.DoubleValue OVERLAY_TEXT_SCALE = BUILDER
            .comment("中央黑幕提示文字的缩放倍率。值越大，字体越大。")
            .defineInRange("overlayTextScale", 2.0D, 0.5D, 10.0D);

    // 因果效果持续时长（秒）
    private static final ForgeConfigSpec.IntValue CAUSALITY_SECONDS = BUILDER
            .comment("因果效果的持续时长，单位为秒。")
            .defineInRange("causalitySeconds", 20, 1, 3600);

    // 柯罗诺斯效果持续时长（秒）
    private static final ForgeConfigSpec.IntValue KRONOS_SECONDS = BUILDER
            .comment("柯罗诺斯效果的持续时长，单位为秒。")
            .defineInRange("kronosSeconds", 20, 1, 3600);

    // 柯罗诺斯慢时最大额外延迟（毫秒）
    private static final ForgeConfigSpec.DoubleValue KRONOS_EXTRA_WAIT_MAX_MS = BUILDER
            .comment("柯罗诺斯慢时时每 tick 的最大额外延迟，单位毫秒。值越大，时间越慢。")
            .defineInRange("kronosExtraWaitMaxMs", 55.0D, 0.0D, 1000.0D);

    // 柯罗诺斯慢时渐变步长（毫秒）
    private static final ForgeConfigSpec.DoubleValue KRONOS_RAMP_STEP_MS = BUILDER
            .comment("柯罗诺斯慢时每 tick 增减的延迟步长，单位毫秒。值越大，进入和退出慢时越快。")
            .defineInRange("kronosRampStepMs", 3.0D, 0.1D, 100.0D);

    // 吸入器最大耐久
    private static final ForgeConfigSpec.IntValue INHALER_DURABILITY = BUILDER
            .comment("吸入器的最大耐久。每次成功使用药芯消耗 1 点。")
            .defineInRange("inhalerDurability", 30, 1, 10000);

    // 启动时输出到日志的介绍文本
    private static final ForgeConfigSpec.ConfigValue<String> INTRODUCTION = BUILDER
            .comment("常规初始化阶段输出到日志中的介绍文本。")
            .define("introduction", "Relics of the Fading City Special: Minefading | 《亡都遗骨》特别篇：我的余晖");

    static final ForgeConfigSpec SPEC = BUILDER.build();

    // 以下为运行时读取的配置值，供其他模块直接引用
    public static DayMode mode;
    public static int countdownDays;
    public static int overlayTicks; // 已转换为 tick 单位（秒 × 20）
    public static float overlayTextScale = 2.0F;
    public static int causalityTicks = 20 * 20;
    public static int kronosTicks = 20 * 20;
    public static double kronosExtraWaitMaxMs = 55.0D;
    public static double kronosRampStepMs = 3.0D;
    public static int inhalerDurability = 30;
    public static String introduction;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event)
    {
        mode = MODE.get();
        countdownDays = COUNTDOWN_DAYS.get();
        overlayTicks = OVERLAY_SECONDS.get() * 20; // 秒转 tick
        overlayTextScale = OVERLAY_TEXT_SCALE.get().floatValue();
        causalityTicks = CAUSALITY_SECONDS.get() * 20;
        kronosTicks = KRONOS_SECONDS.get() * 20;
        kronosExtraWaitMaxMs = KRONOS_EXTRA_WAIT_MAX_MS.get();
        kronosRampStepMs = KRONOS_RAMP_STEP_MS.get();
        inhalerDurability = INHALER_DURABILITY.get();
        introduction = INTRODUCTION.get();
    }
}