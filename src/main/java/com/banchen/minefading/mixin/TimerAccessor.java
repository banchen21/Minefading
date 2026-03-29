package com.banchen.minefading.mixin;

import net.minecraft.client.Timer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Timer.class)
public interface TimerAccessor
{
    @Accessor(value = "msPerTick", remap = false)
    float minefading$getMsPerTick();

    @Mutable
    @Accessor(value = "msPerTick", remap = false)
    void minefading$setMsPerTick(float msPerTick);

    @Accessor(value = "lastMs", remap = false)
    void minefading$setLastMs(long lastMs);
}
