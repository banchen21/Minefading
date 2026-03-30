package com.banchen.minefading.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

// 柯罗诺斯生效时显示的自定义状态效果，仅用于可视化提示
public class KronosStatusEffect extends MobEffect
{
    protected KronosStatusEffect()
    {
        super(MobEffectCategory.BENEFICIAL, 0x6FA8FF);
    }

    @Override
    public boolean isDurationEffectTick(int duration, int amplifier)
    {
        return false;
    }
}
