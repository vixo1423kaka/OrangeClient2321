package com.example.orangeclient.module.render;

import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.setting.BoolSetting;
import com.example.orangeclient.setting.NumberSetting;
import com.example.orangeclient.util.HudStyle;
import com.example.orangeclient.util.RenderUtil;
import com.example.orangeclient.util.UiFont;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.render.Camera;
import org.joml.Quaternionf;
import org.joml.Vector4f;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Orange trajectory prediction for pearls, thrown tridents and visible Riptide launches. */
public class ProjectileTrajectory extends Module {
    private final BoolSetting pearls = add(new BoolSetting("Pearls", true));
    private final BoolSetting tridents = add(new BoolSetting("Tridents", true));
    private final BoolSetting riptide = add(new BoolSetting("Riptide players", true));
    private final BoolSetting landingMarker = add(new BoolSetting("Landing marker", true));
    private final BoolSetting ownerLabels = add(new BoolSetting("Owner labels", true));
    private final NumberSetting maxTicks = add(new NumberSetting("Prediction Ticks", 40, 10, 100, 5));

    private static final int ORANGE = 0xFFFF8617;
    private static final int ORANGE_SOFT = 0xFFFFB45A;
    private static final int MARKER = 0xAAFF8617;
    private final List<Prediction> predictions = new ArrayList<>();

    public ProjectileTrajectory() { super("Projectile trajectory", Category.RENDER); }

    @Override public void onWorldRender(WorldRenderContext ctx, MinecraftClient mc) {
        MatrixStack matrices = ctx.matrixStack();
        if (matrices == null || mc.world == null || mc.player == null) return;
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        Vec3d camera = ctx.camera().getPos();
        predictions.clear();
        BufferBuilder lines = RenderUtil.begin(5.0f);
        BufferBuilder markers = null;
        boolean drew = false;

        for (Entity entity : mc.world.getEntities()) {
            boolean pearl = pearls.get() && entity instanceof EnderPearlEntity;
            boolean trident = tridents.get() && entity instanceof TridentEntity;
            if (!pearl && !trident) continue;
            Entity owner = entity instanceof EnderPearlEntity p ? p.getOwner() : ((TridentEntity) entity).getOwner();
            String ownerName = resolveOwnerName(mc, entity, owner);
            Prediction prediction = predictProjectile(mc, entity, ownerName, pearl ? "Ender Pearl" : "Trident");
            if (prediction == null) continue;
            projectLabel(ctx, mc, prediction);
            predictions.add(prediction);
            for (int i = 1; i < prediction.path.size(); i++) {
                RenderUtil.lineRel(lines, matrix, prediction.path.get(i - 1).subtract(camera), prediction.path.get(i).subtract(camera), ORANGE);
                drew = true;
            }
            if (landingMarker.get() && prediction.landing != null) {
                if (markers == null) markers = RenderUtil.begin(2.5f);
                BlockPos bp = prediction.landing;
                Box block = new Box(bp.getX(), bp.getY(), bp.getZ(), bp.getX()+1, bp.getY()+1, bp.getZ()+1).expand(0.015);
                RenderUtil.box(markers, matrix, camera, block, MARKER);
            }
        }

        // Riptide does not spawn a thrown-trident entity: the player is propelled instead.
        if (riptide.get()) {
            for (PlayerEntity player : mc.world.getPlayers()) {
                if (player == mc.player || !looksLikeRiptideLaunch(player)) continue;
                Prediction prediction = predictPlayer(mc, player);
                if (prediction == null) continue;
                prediction.owner = player.getName().getString() + " (Riptide)";
                projectLabel(ctx, mc, prediction);
                predictions.add(prediction);
                for (int i = 1; i < prediction.path.size(); i++) {
                    RenderUtil.lineRel(lines, matrix, prediction.path.get(i - 1).subtract(camera), prediction.path.get(i).subtract(camera), ORANGE_SOFT);
                    drew = true;
                }
                if (landingMarker.get() && prediction.landing != null) {
                    if (markers == null) markers = RenderUtil.begin(2.5f);
                    BlockPos bp = prediction.landing;
                    RenderUtil.box(markers, matrix, camera, new Box(bp.getX(),bp.getY(),bp.getZ(),bp.getX()+1,bp.getY()+1,bp.getZ()+1).expand(0.015), MARKER);
                }
            }
        }
        RenderUtil.end(lines);
        if (markers != null) RenderUtil.end(markers);
    }

    @Override public void onHudRender(DrawContext ctx, MinecraftClient mc) {
        if (!ownerLabels.get() || mc.player == null || predictions.isEmpty()) return;
        int sw=mc.getWindow().getScaledWidth(), sh=mc.getWindow().getScaledHeight();
        for (Prediction p : predictions) {
            if (p.screenX < 0 || p.screenY < 0) continue;
            String pos = p.landing == null ? "?" : String.format(Locale.ROOT, "%d %d %d", p.landing.getX(), p.landing.getY(), p.landing.getZ());
            String title = p.owner;
            String sub = p.kind + "  " + pos;
            int w1 = UiFont.width(mc.textRenderer, title), w2 = UiFont.width(mc.textRenderer, sub);
            // karta w stylu TargetHUD: pomaranczowy pasek, ciemny panel, biala nazwa + szary opis
            int bar = 4, padX = 11;
            int w = Math.max(w1, w2) + padX * 2, h = bar + 8 + 9 + 3 + 9 + 8;
            int x = Math.round(p.screenX - w * 0.5f);
            int y = Math.round(p.screenY - h - 6);
            x = Math.max(5, Math.min(sw - w - 5, x)); y = Math.max(5, Math.min(sh - h - 5, y));
            HudStyle.panel(ctx, x, y, x + w, y + h, 10, bar, HudStyle.ACCENT, 255);
            UiFont.draw(ctx, mc.textRenderer, title, x + padX, y + bar + 7, HudStyle.argb(255, HudStyle.WHITE));
            UiFont.draw(ctx, mc.textRenderer, sub, x + padX, y + bar + 7 + 12, HudStyle.argb(255, HudStyle.MUTED));
        }
    }

    private void projectLabel(WorldRenderContext ctx, MinecraftClient mc, Prediction p) {
        Camera camera=ctx.camera();
        Matrix4f mvp=new Matrix4f(ctx.projectionMatrix()).mul(new Matrix4f().rotation(camera.getRotation().conjugate(new Quaternionf())));
        Vec3d rel=p.entity.getLerpedPos(ctx.tickCounter().getTickDelta(false)).add(0,p.entity.getHeight()*0.65,0).subtract(camera.getPos());
        Vector4f v=new Vector4f((float)rel.x,(float)rel.y,(float)rel.z,1f); mvp.transform(v);
        if(v.w<=0.01f){p.screenX=p.screenY=-1;return;}
        p.screenX=(v.x/v.w*0.5f+0.5f)*mc.getWindow().getScaledWidth();
        p.screenY=(0.5f-v.y/v.w*0.5f)*mc.getWindow().getScaledHeight();
        if(p.screenX<0||p.screenX>mc.getWindow().getScaledWidth()||p.screenY<0||p.screenY>mc.getWindow().getScaledHeight()) p.screenX=p.screenY=-1;
    }
    private Prediction predictProjectile(MinecraftClient mc, Entity entity, String owner, String kind) {
        Vec3d pos = entity.getPos();
        Vec3d velocity = entity.getVelocity();
        List<Vec3d> path = new ArrayList<>(); path.add(pos);
        BlockPos landing = null;
        for (int i=0; i<(int)maxTicks.get(); i++) {
            Vec3d next = pos.add(velocity);
            BlockHitResult hit = mc.world.raycast(new net.minecraft.world.RaycastContext(pos, next,
                    net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, entity));
            if (hit.getType() == HitResult.Type.BLOCK) { next = hit.getPos(); landing = hit.getBlockPos(); path.add(next); break; }
            path.add(next);
            if (next.y < mc.world.getBottomY()) break;
            pos = next;
            velocity = velocity.multiply(0.99).add(0, -0.03, 0);
        }
        if (landing == null && !path.isEmpty()) {
            Vec3d end = path.get(path.size()-1);
            BlockPos below = BlockPos.ofFloored(end.x, end.y-0.1, end.z);
            if (!mc.world.getBlockState(below).isAir()) landing = below;
        }
        return new Prediction(kind, owner, path, landing, entity);
    }

    private Prediction predictPlayer(MinecraftClient mc, PlayerEntity player) {
        Vec3d pos = player.getPos().add(0, player.getHeight()*0.5, 0);
        Vec3d velocity = player.getVelocity();
        List<Vec3d> path = new ArrayList<>(); path.add(pos);
        BlockPos landing = null;
        for (int i=0; i<(int)maxTicks.get(); i++) {
            Vec3d next = pos.add(velocity);
            Box moved = player.getBoundingBox().offset(next.subtract(player.getPos().add(0,player.getHeight()*0.5,0)));
            if (!mc.world.isSpaceEmpty(player, moved)) { landing = BlockPos.ofFloored(next.x, next.y-player.getHeight()*0.5, next.z); path.add(next); break; }
            path.add(next); pos=next;
            velocity = velocity.multiply(0.98).add(0,-0.08,0);
        }
        return new Prediction("Riptide", player.getName().getString(), path, landing, player);
    }

    private boolean looksLikeRiptideLaunch(PlayerEntity player) {
        Vec3d v = player.getVelocity();
        boolean fast = v.lengthSquared() > 0.75;
        boolean holdsTrident = player.getMainHandStack().isOf(Items.TRIDENT) || player.getOffHandStack().isOf(Items.TRIDENT);
        return fast && holdsTrident && (player.isTouchingWater() || player.isWet());
    }

    private String resolveOwnerName(MinecraftClient mc, Entity projectile, Entity owner) {
        if (owner instanceof PlayerEntity player) return player.getName().getString();
        // Client/server versions sometimes do not expose the owner for a remote projectile.
        // Infer only when a player is very close to the projectile's launch position; otherwise say unknown.
        PlayerEntity nearest = null; double best = 2.25;
        for (PlayerEntity p : mc.world.getPlayers()) {
            double d = p.squaredDistanceTo(projectile);
            if (d < best) { best = d; nearest = p; }
        }
        return nearest == null ? "owner unknown" : nearest.getName().getString() + " (?)";
    }

    private static final class Prediction {
        final String kind; String owner; final List<Vec3d> path; final BlockPos landing; final Entity entity;
        float screenX=-1, screenY=-1;
        Prediction(String kind, String owner, List<Vec3d> path, BlockPos landing, Entity entity) { this.kind=kind; this.owner=owner; this.path=path; this.landing=landing; this.entity=entity; }
    }
}
