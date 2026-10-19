package com.example.orangeclient.module.render;

import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.setting.BoolSetting;
import com.example.orangeclient.setting.ColorSetting;
import com.example.orangeclient.setting.ModeSetting;
import com.example.orangeclient.setting.NumberSetting;
import com.example.orangeclient.util.RenderUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * HitFX: wlasne czasteczki (pomarancze, gwiazdki, kulki, serduszka, bloom) wokol trafionego gracza.
 * Co tick, gdy encja ma hurtTime > 0, wypuszcza Amount czasteczek w losowych kierunkach. Czasteczki
 * spadaja (Fall) albo lataja (Fly), odbijaja sie od blokow, obracaja sie i na koncu zycia zanikaja.
 * Rysowane jako billboardy zwrocone do kamery.
 */
public class HitFX extends Module {
    private static final int CAP = 700;
    private static final float GRAVITY = 0.035f, DRAG = 1f / 1.005f;
    /** Pomarancz z tla menu (owoc zajmuje ok. 46% tekstury). */
    private static final Identifier ORANGE_TEX = Identifier.of("orangeclient", "textures/gui/orange_small.png");
    private static final float FRUIT_SHARE = 0.46f;

    // kolejnosc ustawien w menu
    private final ModeSetting look = add(new ModeSetting("Mode", "Orange", "Orange", "Stars", "Hearts", "Orbs", "Bloom"));
    private final NumberSetting scale = add(new NumberSetting("Scale", 3, 1, 10, 0.5));
    private final NumberSetting amount = add(new NumberSetting("Amount", 2, 1, 5, 1));
    private final NumberSetting speed = add(new NumberSetting("Speed", 2, 1, 20, 1));
    private final NumberSetting lifeTime = add(new NumberSetting("LifeTime", 2, 1, 10, 1));
    private final ModeSetting gravity = add(new ModeSetting("Physics", "Fall", "Fall", "Fly"));
    private final BoolSetting rainbow = add(new BoolSetting("Rainbow", true));
    private final ColorSetting color = add(new ColorSetting("Color", 0xFFB020));
    private final BoolSetting mobs = add(new BoolSetting("Mobs", false));
    private final BoolSetting includeSelf = add(new BoolSetting("Self", false));

    /** Pojedyncza czasteczka; czas zycia liczymy w tickach, wiec animacja stoi razem z gra. */
    private final class Bit {
        Vec3d pos, prev, vel;
        final float spin, spinRate;
        final int rgb;
        int age;
        final int maxAge;

        Bit(Entity src, ThreadLocalRandom r) {
            double h = r.nextDouble() * src.getHeight();
            pos = prev = new Vec3d(src.getX(), src.getY() + h, src.getZ());
            double s = speed.get() / 50.0;
            vel = new Vec3d(r.nextDouble(-s, s), r.nextDouble(-s, s), r.nextDouble(-s, s));
            spin = r.nextFloat() * 180f;
            spinRate = 10f + r.nextFloat() * 50f;
            rgb = rainbow.get() ? Color.HSBtoRGB(r.nextFloat(), 0.75f, 1f) & 0xFFFFFF : color.rgb();
            maxAge = (int) Math.round(lifeTime.get() * 20.0);
        }

        boolean dead() { return age >= maxAge; }

        /** Jeden tick fizyki: ruch, odbicia od blokow, grawitacja, tlumienie. */
        void tick(MinecraftClient mc) {
            age++;
            prev = pos;
            pos = pos.add(vel);

            double side = Math.hypot(vel.x, vel.z);
            Vec3d v = vel;
            if (blocked(mc, pos.add(0, -scale.get() / 10.0, 0))) {
                v = new Vec3d(v.x / 1.1, -v.y / 1.1, v.z / 1.1);
            } else if (touchesWall(mc, side)) {
                v = new Vec3d(-v.x, v.y, -v.z);
            }
            if (gravity.is("Fall")) v = v.add(0, -GRAVITY, 0);
            vel = v.multiply(DRAG);
        }

        private boolean touchesWall(MinecraftClient mc, double d) {
            double[][] probes = {{-d, -d}, {d, d}, {d, -d}, {-d, d}, {d, 0}, {-d, 0}, {0, d}, {0, -d}};
            for (double[] o : probes) if (blocked(mc, pos.add(o[0], 0, o[1]))) return true;
            return false;
        }

        float fade(float partial) {
            float life = (age + partial) / (float) Math.max(1, maxAge);
            return life > 0.7f ? Math.max(0f, (1f - life) / 0.3f) : 1f;
        }
    }

    private final List<Bit> bits = new ArrayList<>();

    public HitFX() {
        super("HitFX", Category.RENDER);
    }

    @Override
    protected void onDisable() { bits.clear(); }

    private static boolean blocked(MinecraftClient mc, Vec3d p) {
        BlockPos bp = BlockPos.ofFloored(p);
        return !mc.world.getBlockState(bp).getCollisionShape(mc.world, bp).isEmpty();
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void onTick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) { bits.clear(); return; }

        for (Iterator<Bit> it = bits.iterator(); it.hasNext(); ) {
            Bit b = it.next();
            b.tick(mc);
            if (b.dead()) it.remove();
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity le) || !e.isAlive() || le.hurtTime <= 0) continue;
            if (!wanted(mc, e) || mc.player.squaredDistanceTo(e) > 48 * 48) continue;
            for (int n = 0; n < amount.get() && bits.size() < CAP; n++) bits.add(new Bit(e, rnd));
        }
    }

    /** Czy dla tej encji pokazywac efekt: Ty tylko z wlaczonym Self, inni gracze zawsze, moby z Mobs. */
    private boolean wanted(MinecraftClient mc, Entity e) {
        if (e == mc.player) return includeSelf.get();
        return e instanceof PlayerEntity || mobs.get();
    }

    // ------------------------------------------------------------------ render

    @Override
    public void onWorldRender(WorldRenderContext ctx, MinecraftClient mc) {
        MatrixStack ms = ctx.matrixStack();
        if (ms == null || mc.player == null || bits.isEmpty()) return;

        Frame f = new Frame(ms.peek().getPositionMatrix(), ctx.camera().getPos(),
                ctx.tickCounter().getTickDelta(false), ctx.camera().getPitch(), ctx.camera().getYaw());

        if (look.is("Orange")) drawOranges(f);
        else drawShapes(f);
    }

    /** Dane jednej klatki: macierz, kamera i osie billboardu (prawo / gora). */
    private record Frame(Matrix4f m, Vec3d cam, float partial, Vec3d right, Vec3d up) {
        Frame(Matrix4f m, Vec3d cam, float partial, float pitch, float yaw) {
            this(m, cam, partial, axes(pitch, yaw)[0], axes(pitch, yaw)[1]);
        }

        private static Vec3d[] axes(float pitch, float yaw) {
            Vec3d fwd = Vec3d.fromPolar(pitch, yaw);
            Vec3d r = fwd.crossProduct(new Vec3d(0, 1, 0));
            r = r.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : r.normalize();
            return new Vec3d[]{r, r.crossProduct(fwd).normalize()};
        }

        Vec3d where(Bit b) {
            return b.prev.add(b.pos.subtract(b.prev).multiply(partial)).add(0, 0.1, 0).subtract(cam);
        }
    }

    private float turn(Bit b, float partial) {
        return (float) Math.toRadians(b.spin + (b.age + partial) / 20f * b.spinRate);
    }

    private void drawOranges(Frame f) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(ShaderProgramKeys.POSITION_TEX_COLOR);
        RenderSystem.setShaderTexture(0, ORANGE_TEX);
        BufferBuilder buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);

        float half = (float) (scale.get() * 0.035) * 1.4f / FRUIT_SHARE;
        float[][] corner = {{-1, 1, 0, 0}, {1, 1, 1, 0}, {1, -1, 1, 1}, {-1, -1, 0, 1}};
        for (Bit b : bits) {
            float a = b.fade(f.partial());
            if (a <= 0f) continue;
            Vec3d c = f.where(b);
            double cs = Math.cos(turn(b, f.partial())), sn = Math.sin(turn(b, f.partial()));
            int col = withAlpha(0xFFFFFF, a);
            for (float[] k : corner) {
                double u = (k[0] * cs - k[1] * sn) * half, v = (k[0] * sn + k[1] * cs) * half;
                Vec3d w = c.add(f.right().multiply(u)).add(f.up().multiply(v));
                buf.vertex(f.m(), (float) w.x, (float) w.y, (float) w.z).texture(k[2], k[3]).color(col);
            }
        }
        BuiltBuffer built = buf.endNullable();
        if (built != null) BufferRenderer.drawWithGlobalProgram(built);
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private void drawShapes(Frame f) {
        BufferBuilder buf = RenderUtil.beginFill();
        float unit = (float) (scale.get() * 0.035);
        for (Bit b : bits) {
            float a = b.fade(f.partial());
            if (a <= 0f) continue;
            float t = turn(b, f.partial());
            Plane pl = new Plane(buf, f, f.where(b), Math.cos(t), Math.sin(t), unit);
            int base = b.rgb, light = blend(base, 0xFFFFFF, 0.55f);
            switch (look.get()) {
                case "Orbs" -> {
                    pl.fan(1.0f, 20, withAlpha(base, 0.40f * a), withAlpha(base, 0f));
                    pl.fan(0.42f, 16, withAlpha(light, a), withAlpha(base, a));
                }
                case "Hearts" -> pl.heart(withAlpha(blend(base, 0xFFFFFF, 0.35f), a), withAlpha(base, a));
                case "Bloom" -> {
                    pl.fan(1.8f, 24, withAlpha(base, 0.55f * a), withAlpha(base, 0f));
                    pl.fan(0.35f, 12, withAlpha(0xFFFFFF, a), withAlpha(base, 0.6f * a));
                }
                default -> {
                    pl.fan(1.2f, 16, withAlpha(base, 0.22f * a), withAlpha(base, 0f));
                    pl.star(5, 1.0f, 0.45f, withAlpha(blend(base, 0xFFFFFF, 0.6f), a), withAlpha(base, a));
                }
            }
        }
        RenderUtil.endFill(buf);
    }

    private static int withAlpha(int rgb, float a) {
        return (Math.max(0, Math.min(255, Math.round(a * 255f))) << 24) | (rgb & 0xFFFFFF);
    }

    private static int blend(int a, int c, float t) {
        int r = Math.round(((a >> 16) & 255) * (1 - t) + ((c >> 16) & 255) * t);
        int g = Math.round(((a >> 8) & 255) * (1 - t) + ((c >> 8) & 255) * t);
        int bl = Math.round((a & 255) * (1 - t) + (c & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /** Plaszczyzna billboardu: rysuje ksztalty 2D jako wachlarze trojkatow (kazdy trojkat = zdegenerowany quad). */
    private static final class Plane {
        private final BufferBuilder buf;
        private final Frame f;
        private final Vec3d origin;
        private final double cs, sn;
        private final float unit;

        Plane(BufferBuilder buf, Frame f, Vec3d origin, double cs, double sn, float unit) {
            this.buf = buf; this.f = f; this.origin = origin; this.cs = cs; this.sn = sn; this.unit = unit;
        }

        private void vert(double x, double y, int col) {
            double u = (x * cs - y * sn) * unit, v = (x * sn + y * cs) * unit;
            Vec3d w = origin.add(f.right().multiply(u)).add(f.up().multiply(v));
            buf.vertex(f.m(), (float) w.x, (float) w.y, (float) w.z).color(col);
        }

        private void wedge(double x1, double y1, double x2, double y2, int inner, int outer) {
            vert(0, 0, inner); vert(x1, y1, outer); vert(x2, y2, outer); vert(x2, y2, outer);
        }

        void fan(float radius, int seg, int inner, int outer) {
            for (int i = 0; i < seg; i++) {
                double a = Math.PI * 2 * i / seg, b = Math.PI * 2 * (i + 1) / seg;
                wedge(Math.cos(a) * radius, Math.sin(a) * radius, Math.cos(b) * radius, Math.sin(b) * radius, inner, outer);
            }
        }

        void star(int arms, float outer, float inner, int cc, int ce) {
            int n = arms * 2;
            for (int i = 0; i < n; i++) {
                double a = Math.PI / 2 + Math.PI * 2 * i / n, b = Math.PI / 2 + Math.PI * 2 * (i + 1) / n;
                float ra = i % 2 == 0 ? outer : inner, rb = i % 2 == 0 ? inner : outer;
                wedge(Math.cos(a) * ra, Math.sin(a) * ra, Math.cos(b) * rb, Math.sin(b) * rb, cc, ce);
            }
        }

        void heart(int cc, int ce) {
            int seg = 28;
            for (int i = 0; i < seg; i++) {
                double a = Math.PI * 2 * i / seg, b = Math.PI * 2 * (i + 1) / seg;
                wedge(hx(a), hy(a), hx(b), hy(b), cc, ce);
            }
        }

        private static double hx(double t) { return Math.pow(Math.sin(t), 3); }

        private static double hy(double t) {
            return (13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t) + 2.5) / 16.0;
        }
    }
}
