package com.example.orangeclient.mixin;

import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderTickCounter.Dynamic.class)
public interface RenderTickCounterDynamicAccessor {
    @Mutable
    @Accessor("tickTime")
    void orange$setTickTime(float tickTime);
}
