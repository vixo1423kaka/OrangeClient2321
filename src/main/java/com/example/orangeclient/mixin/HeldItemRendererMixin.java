package com.example.orangeclient.mixin;

import com.example.orangeclient.module.ModuleManager;
import com.example.orangeclient.module.render.Chams;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds an optional color/glass pass to items rendered in the local player's first-person hands. */
@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
    @Inject(method = "renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/render/model/json/ModelTransformationMode;ZLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("HEAD"), require = 0)
    private void orange$beginHeldItemChams(LivingEntity entity, ItemStack stack, ModelTransformationMode mode,
                                           boolean leftHanded, MatrixStack matrices,
                                           VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        if (entity != MinecraftClient.getInstance().player || stack.isEmpty()) return;
        Chams chams = ModuleManager.get(Chams.class);
        if (chams != null && chams.isEnabled() && chams.rendersHeldItems()) chams.beginHeldItemEffect();
    }

    @Inject(method = "renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/render/model/json/ModelTransformationMode;ZLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("RETURN"), require = 0)
    private void orange$endHeldItemChams(LivingEntity entity, ItemStack stack, ModelTransformationMode mode,
                                         boolean leftHanded, MatrixStack matrices,
                                         VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        if (entity != MinecraftClient.getInstance().player || stack.isEmpty()) return;
        Chams chams = ModuleManager.get(Chams.class);
        if (chams != null && chams.isEnabled() && chams.rendersHeldItems()) chams.endHeldItemEffect();
    }
}
