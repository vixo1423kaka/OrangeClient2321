package com.example.orangeclient.mixin;

import com.example.orangeclient.friend.Friends;
import com.example.orangeclient.module.ModuleManager;
import com.example.orangeclient.module.combat.KillAura;
import com.example.orangeclient.module.render.Chams;
import com.example.orangeclient.module.render.Nametags;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModel;

import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    /** Chowa vanillowy nametag graczy, gdy wlaczony jest modul Nametags (opcja HideVanilla). */
    @Inject(method = "hasLabel", at = @At("HEAD"), cancellable = true)
    private void orange$hideVanillaLabel(LivingEntity entity, double squaredDistance, CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof PlayerEntity)) return;
        Nametags nt = ModuleManager.get(Nametags.class);
        if (nt != null && nt.hidesVanilla()) cir.setReturnValue(false);
    }

    /**
     * KillAura (Silent): kamera zostaje na miejscu, ale model WLASNEGO gracza (3. osoba) jest rysowany
     * z rotacja wysylana do serwera (opcja SilentModel).
     */
    @Inject(method = "updateRenderState(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;F)V",
            at = @At("RETURN"), require = 0)
    private void orange$silentRotationModel(LivingEntity entity, LivingEntityRenderState state, float tickDelta, CallbackInfo ci) {
        if (entity != MinecraftClient.getInstance().player) return;
        KillAura ka = ModuleManager.get(KillAura.class);
        if (ka == null || !ka.isRenderRotating()) return;
        state.bodyYaw = ka.renderYaw(tickDelta);
        state.yawDegrees = 0f;
        state.pitch = ka.renderPitch(tickDelta);
    }

    @Shadow protected abstract void setupTransforms(LivingEntityRenderState state, MatrixStack matrices, float bodyYaw, float baseHeight);
    @Shadow protected abstract void scale(LivingEntityRenderState state, MatrixStack matrices);

    /** Chams: zapamietaj, ktore stany renderowania naleza do znajomych (kolor FriendColor). */
    @Inject(method = "updateRenderState(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;F)V",
            at = @At("RETURN"), require = 0)
    private void orange$chamsMarkFriend(LivingEntity entity, LivingEntityRenderState state, float tickDelta, CallbackInfo ci) {
        if (entity instanceof PlayerEntity) Chams.markFriend(state, Friends.isFriend(entity));
    }

    /** Chams: dodatkowy przebieg modelu gracza bez testu glebokosci (transformacje jak w vanilla render). */
    @Inject(method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("HEAD"), require = 0)
    private void orange$chamsPlayer(LivingEntityRenderState state, MatrixStack matrices,
                                    VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        Chams chams = ModuleManager.get(Chams.class);
        if (chams == null || !chams.isEnabled() || !chams.rendersPlayers(state)) return;
        EntityModel<?> model = ((LivingEntityRenderer<?, ?, ?>) (Object) this).getModel();
        matrices.push();
        float scale = state.baseScale;
        matrices.scale(scale, scale, scale);
        this.setupTransforms(state, matrices, state.bodyYaw, scale);
        matrices.scale(-1.0f, -1.0f, 1.0f);
        this.scale(state, matrices);
        matrices.translate(0.0f, -1.501f, 0.0f);
        try {
            chams.drawPlayer((PlayerEntityRenderState) state, model, matrices, light);
        } finally {
            matrices.pop();
        }
    }

    /** Chams (Player Texture off): ukryj vanillowe cialo gracza, zostaje sam chams (pancerz i itemy bez zmian). */
    @Inject(method = "getRenderLayer", at = @At("RETURN"), cancellable = true, require = 0)
    private void orange$chamsHideBody(LivingEntityRenderState state, boolean showBody, boolean translucent,
                                      boolean showOutline, CallbackInfoReturnable<RenderLayer> cir) {
        Chams chams = ModuleManager.get(Chams.class);
        if (chams != null && chams.isEnabled() && chams.hidesPlayerBody(state)) cir.setReturnValue(null);
    }
}
