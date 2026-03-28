package com.banchen.minefading.effect;

import com.banchen.minefading.Minefading;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

// 自定义状态效果注册表
public class ModEffects
{
    public static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, Minefading.MODID);

    public static final RegistryObject<MobEffect> KRONOS_ACTIVE = EFFECTS.register("kronos_active",
            KronosStatusEffect::new);

    private ModEffects()
    {
    }
}