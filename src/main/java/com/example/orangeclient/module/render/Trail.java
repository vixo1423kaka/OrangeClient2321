package com.example.orangeclient.module.render;

import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.setting.BoolSetting;
import com.example.orangeclient.setting.ColorSetting;
import com.example.orangeclient.setting.NumberSetting;
import com.example.orangeclient.util.RenderUtil;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.awt.Color;
import java.util.ArrayDeque;

/** Trail: swietlista (teczowa) wstega ciagnaca sie za Toba. */
public class Trail extends Module {
    private final NumberSetting length = add(new NumberSetting("Length", 40, 10, 120, 5));
    private final BoolSetting rainbow = add(new BoolSetting("Rainbow", true));
    private final ColorSetting color = add(new ColorSetting("Color", 0xFF7A00));
    private final NumberSetting speed = add(new NumberSetting("Speed", 1.0, 0.2, 3.0, 0.1));

    private final ArrayDeque<Vec3d> points = new ArrayDeque<>();

    public Trail() {
        super("Trail", Category.RENDER);
    }

    @Override
    protected void onDisable() { points.clear(); }

    private int col(float f, float a) {
        int al = Math.max(0, Math.min(255, Math.round(a * 255f)));
        int rgb;
        if (rainbow.get()) {
            float h = (float) ((System.currentTimeMillis() / 4000.0 * speed.get() + f) % 1.0);
            rgb = Color.HSBtoRGB(h < 0 ? h + 1f : h, 0.8f, 1f) & 0xFFFFFF;
        } else {
            rgb = color.rgb();
        }
        return (al << 24) | rgb;
    }

    @Override
    public void onTick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) { points.clear(); return; }
        points.addFirst(new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
        while (points.size() > length.get()) points.removeLast();
    }

    @Override
    public void onWorldRender(WorldRenderContext ctx, MinecraftClient mc) {
        MatrixStack ms = ctx.matrixStack();
        if (ms == null || mc.player == null || points.size() < 2) return;
        Matrix4f m = ms.peek().getPositionMatrix();
        Vec3d cam = ctx.camera().getPos();
        Vec3d prev = mc.player.getLerpedPos(ctx.tickCounter().getTickDelta(false));

        BufferBuilder b = RenderUtil.beginFill();
        int n = points.size(), i = 0;
        for (Vec3d p : points) {
            float f0 = i / (float) n, f1 = (i + 1) / (float) n;
            ribbon(b, m, cam, prev, p, f0, (1f - f0) * 0.55f, (1f - f1) * 0.55f);
            prev = p;
            i++;
        }
        RenderUtil.endFill(b);
    }

    /** Pionowy pas miedzy dwoma punktami: dol (przy stopach) jasniejszy, gora przezroczysta. */
    private void ribbon(BufferBuilder b, Matrix4f m, Vec3d cam, Vec3d a, Vec3d c, float f, float aa, float ac) {
        float h = 1.9f;
        int colA = col(f, aa), colC = col(f + 0.03f, ac);
        int topA = col(f, aa * 0.15f), topC = col(f + 0.03f, ac * 0.15f);
        float ax = (float) (a.x - cam.x), ay = (float) (a.y - cam.y), az = (float) (a.z - cam.z);
        float cx = (float) (c.x - cam.x), cy = (float) (c.y - cam.y), cz = (float) (c.z - cam.z);
        b.vertex(m, ax, ay, az).color(colA);
        b.vertex(m, ax, ay + h, az).color(topA);
        b.vertex(m, cx, cy + h, cz).color(topC);
        b.vertex(m, cx, cy, cz).color(colC);
    }
}
