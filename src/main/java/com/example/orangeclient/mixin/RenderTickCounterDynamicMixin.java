package com.example.orangeclient.mixin;

import com.example.orangeclient.module.ModuleManager;
import com.example.orangeclient.module.movement.Timer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderTickCounter.Dynamic.class)
public abstract class RenderTickCounterDynamicMixin {
    @Inject(method = "beginRenderTick", at = @At("HEAD"))
    private void orange$applyTimerBeforeTickCalculation(long timeMillis, boolean tick, CallbackInfoReturnable<Integer> cir) {
        Timer timer = ModuleManager.get(Timer.class);
        if (timer == null) return;
        ((RenderTickCounterDynamicAccessor) (Object) this)
                .orange$setTickTime(timer.getTickTime(MinecraftClient.getInstance()));
    }
}
