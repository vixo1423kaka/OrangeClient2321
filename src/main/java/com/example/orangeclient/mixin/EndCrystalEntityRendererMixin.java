package com.example.orangeclient.mixin;

import com.example.orangeclient.module.ModuleManager;
import com.example.orangeclient.module.render.Chams;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EndCrystalEntityRenderer;
import net.minecraft.client.render.entity.state.EndCrystalEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EndCrystalEntityRenderer.class)
public abstract class EndCrystalEntityRendererMixin {
    /** Chams: dodatkowy przebieg modelu end crystala bez testu glebokosci. */
    @Inject(method = "render(Lnet/minecraft/client/render/entity/state/EndCrystalEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("HEAD"), require = 0)
    private void orange$chamsCrystal(EndCrystalEntityRenderState state, MatrixStack matrices,
                                     VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        Chams chams = ModuleManager.get(Chams.class);
        if (chams == null || !chams.isEnabled() || !chams.rendersCrystals()) return;
        chams.drawCrystal(this, state, matrices, light);
    }
}
