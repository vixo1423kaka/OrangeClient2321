package com.example.orangeclient.mixin;

import com.example.orangeclient.module.ModuleManager;
import com.example.orangeclient.render.HeldItemChamsVertexConsumer;
import com.example.orangeclient.module.render.Chams;
import com.example.orangeclient.module.render.HandAnimations;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.item.ModelTransformationMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
    @ModifyVariable(
            method = "renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ModelTransformationMode;ZLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("HEAD"),
            argsOnly = true
    )
    private VertexConsumerProvider orange$applyHeldItemChams(VertexConsumerProvider original) {
        Chams chams = ModuleManager.get(Chams.class);
        if (chams == null || !chams.isEnabled() || !chams.rendersHeldItems()) return original;
        return HeldItemChamsVertexConsumer.wrap(original, chams);
    }

    @Shadow
    public abstract void renderItem(LivingEntity entity, ItemStack stack, ModelTransformationMode mode, boolean leftHanded,
                                    MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light);

    /**
     * HandAnimations: podmienia zwykle machniecie reka/itemem. Uzywanie itemow (jedzenie, luk, kusza, tarcza),
     * mapy, luneta i pusta reka zostaja vanillowe.
     */
    @Inject(method = "renderFirstPersonItem", at = @At("HEAD"), cancellable = true)
    private void orange$handAnimations(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand,
                                       float swingProgress, ItemStack item, float equipProgress, MatrixStack matrices,
                                       VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        HandAnimations ha = ModuleManager.get(HandAnimations.class);
        if (ha == null || !ha.shouldAnimate()) return;
        if (item.isEmpty() || item.isOf(Items.FILLED_MAP) || item.isOf(Items.CROSSBOW) || player.isUsingSpyglass()) return;
        if (player.isUsingItem() && player.getItemUseTimeLeft() > 0 && player.getActiveHand() == hand) return;
        if (player.isUsingRiptide()) return;

        Arm arm = hand == Hand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        boolean right = arm == Arm.RIGHT;
        matrices.push();
        ha.apply(matrices, arm, swingProgress, equipProgress);
        this.renderItem(player, item, right ? ModelTransformationMode.FIRST_PERSON_RIGHT_HAND
                : ModelTransformationMode.FIRST_PERSON_LEFT_HAND, !right, matrices, vertexConsumers, light);
        matrices.pop();
        ci.cancel();
    }
}
