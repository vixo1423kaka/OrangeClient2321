package com.example.orangeclient.module.render;

import com.example.orangeclient.friend.Friends;
import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.setting.BoolSetting;
import com.example.orangeclient.setting.ColorSetting;
import com.example.orangeclient.setting.ModeSetting;
import com.example.orangeclient.setting.NumberSetting;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.state.EndCrystalEntityRenderState;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EntityPose;
import net.minecraft.util.Identifier;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Chams: model gracza / end crystala rysowany drugi raz, bez testu glebokosci (widac go przez sciany).
 * Port z ThunderHack (Pan4ur, GPL-3.0) na MC 1.21.4 (render states, ShaderProgramKeys).
 * Rysowanie jest wywolywane z mixinow: LivingEntityRendererMixin i EndCrystalEntityRendererMixin.
 */
public class Chams extends Module {
    private static final Identifier CRYSTAL_TEXTURE = Identifier.of("textures/entity/end_crystal/end_crystal.png");

    private final BoolSetting players = add(new BoolSetting("Players", true));
    private final ColorSetting playerColor = add(new ColorSetting("Player Color", 0xFF7A00));
    private final ColorSetting friendColor = add(new ColorSetting("Friend Color", 0x55FFFF));
    private final BoolSetting playerTexture = add(new BoolSetting("Player Texture", true));
    private final BoolSetting simple = add(new BoolSetting("Simple", false));

    private final BoolSetting crystals = add(new BoolSetting("Crystals", false));
    private final ColorSetting crystalColor = add(new ColorSetting("Crystal Color", 0xFF7A00));
    private final ModeSetting crystalMode = add(new ModeSetting("Crystal Mode", "Flat", "Flat", "Texture"));

    private final NumberSetting alpha = add(new NumberSetting("Alpha", 55, 5, 100, 5));
    private final BoolSetting additive = add(new BoolSetting("Additive Blend", true));

    /** Stany renderowania graczy oznaczone jako znajomi (wypelniane w updateRenderState). */
    private static final Map<LivingEntityRenderState, Boolean> FRIEND_STATES = new WeakHashMap<>();
    private static final Map<Class<?>, Field> MODEL_FIELDS = new HashMap<>();

    public Chams() {
        super("Chams", Category.RENDER);
    }

    // ---------- API dla mixinow ----------

    public static void markFriend(LivingEntityRenderState state, boolean friend) {
        if (friend) FRIEND_STATES.put(state, Boolean.TRUE); else FRIEND_STATES.remove(state);
    }

    public boolean rendersPlayers(LivingEntityRenderState state) {
        return players.get() && state instanceof PlayerEntityRenderState && !state.isInPose(EntityPose.SLEEPING);
    }

    /** True, gdy vanillowy model gracza ma zostac ukryty (zostaje tylko chams). */
    public boolean hidesPlayerBody(LivingEntityRenderState state) {
        return rendersPlayers(state) && !playerTexture.get();
    }

    public boolean rendersCrystals() { return crystals.get(); }

    /** Rysuje model gracza; macierz musi byc juz ustawiona jak w LivingEntityRenderer (po transformacjach). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void drawPlayer(PlayerEntityRenderState state, EntityModel model, MatrixStack matrices, int light) {
        int rgb = FRIEND_STATES.containsKey(state) ? friendColor.rgb() : playerColor.rgb();
        boolean textured = !simple.get() && state.skinTextures != null;
        BufferBuilder buffer = begin(textured, textured ? state.skinTextures.texture() : null, rgb, true);
        try {
            model.setAngles(state);
            model.render(matrices, buffer, light, OverlayTexture.DEFAULT_UV, -1);
        } finally {
            end(buffer);
        }
    }

    /** Rysuje end crystala (transformacje jak w EndCrystalEntityRenderer: scale 2, translate -0.5). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void drawCrystal(Object renderer, EndCrystalEntityRenderState state, MatrixStack matrices, int light) {
        EntityModel model = modelOf(renderer);
        if (model == null) return;
        boolean textured = crystalMode.is("Texture");
        matrices.push();
        matrices.scale(2.0f, 2.0f, 2.0f);
        matrices.translate(0.0f, -0.5f, 0.0f);
        BufferBuilder buffer = begin(textured, textured ? CRYSTAL_TEXTURE : null, crystalColor.rgb(), false);
        try {
            model.setAngles(state);
            model.render(matrices, buffer, light, OverlayTexture.DEFAULT_UV, -1);
        } finally {
            end(buffer);
            matrices.pop();
        }
    }

    // ---------- rysowanie ----------

    private BufferBuilder begin(boolean textured, Identifier texture, int rgb, boolean cull) {
        RenderSystem.enableBlend();
        if (additive.get()) RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
        else RenderSystem.defaultBlendFunc();
        if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
        RenderSystem.disableDepthTest();

        BufferBuilder buffer;
        if (textured) {
            RenderSystem.setShaderTexture(0, texture);
            RenderSystem.setShader(ShaderProgramKeys.POSITION_TEX);
            buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
        } else {
            RenderSystem.setShader(ShaderProgramKeys.POSITION);
            buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
        }
        RenderSystem.setShaderColor(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f,
                (float) (alpha.get() / 100.0));
        return buffer;
    }

    private static void end(BufferBuilder buffer) {
        BuiltBuffer built = buffer.endNullable();
        if (built != null) BufferRenderer.drawWithGlobalProgram(built);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
    }

    /** Model renderera znajdujemy po typie pola (niezalezne od nazw yarn/intermediary). */
    private static EntityModel<?> modelOf(Object renderer) {
        try {
            Field f = MODEL_FIELDS.get(renderer.getClass());
            if (f == null) {
                outer:
                for (Class<?> c = renderer.getClass(); c != null; c = c.getSuperclass()) {
                    for (Field x : c.getDeclaredFields()) {
                        if (EntityModel.class.isAssignableFrom(x.getType())) {
                            x.setAccessible(true);
                            f = x;
                            break outer;
                        }
                    }
                }
                if (f == null) return null;
                MODEL_FIELDS.put(renderer.getClass(), f);
            }
            return (EntityModel<?>) f.get(renderer);
        } catch (Exception e) {
            return null;
        }
    }
}
