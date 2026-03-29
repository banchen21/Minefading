package com.banchen.minefading.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Timer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface MinecraftAccessor
{
    @Accessor(value = "timer", remap = false)
    Timer minefading$getTimer();
}
