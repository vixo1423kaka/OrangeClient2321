package com.example.orangeclient.mixin;

import com.example.orangeclient.module.render.HandAnimations;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntitySwingMixin {
    /** HandAnimations > SlowAnimation: wolniejsze (dluzsze) machniecie lokalnego gracza. */
    @Inject(method = "getHandSwingDuration", at = @At("HEAD"), cancellable = true, require = 0)
    private void orange$slowSwing(CallbackInfoReturnable<Integer> cir) {
        if (!((Object) this instanceof ClientPlayerEntity)) return;
        int d = HandAnimations.swingDuration();
        if (d > 0) cir.setReturnValue(d);
    }
}
