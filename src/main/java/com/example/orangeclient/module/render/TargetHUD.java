package com.example.orangeclient.module.render;

import com.example.orangeclient.module.Category;
import com.example.orangeclient.gui.ClickGui;
import com.example.orangeclient.util.UiFont;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.setting.BoolSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.render.Camera;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public class TargetHUD extends Module {
    private final BoolSetting playersOnly = add(new BoolSetting("Players only", true));
    private LivingEntity lastTarget;
    private long shownAt;
    private long lostAt;
    private float anim;
    private float shownFrac = 1f;
    private float projectedX = -1, projectedY = -1;
    public TargetHUD() { super("TargetHUD", Category.RENDER); }

    @Override public void onWorldRender(WorldRenderContext ctx, MinecraftClient mc) {
        projectedX = projectedY = -1;
        if (mc.player == null || mc.world == null) return;
        LivingEntity target = findTarget(mc);
        if (target == null && lastTarget != null && lastTarget.isAlive()) target = lastTarget;
        if (target == null) return;
        Camera camera = ctx.camera();
        Matrix4f mvp = new Matrix4f(ctx.projectionMatrix()).mul(new Matrix4f().rotation(camera.getRotation().conjugate(new Quaternionf())));
        Vec3d rel = target.getLerpedPos(ctx.tickCounter().getTickDelta(false)).add(0, target.getHeight() * 0.58, 0).subtract(camera.getPos());
        Vector4f v = new Vector4f((float)rel.x,(float)rel.y,(float)rel.z,1f); mvp.transform(v);
        if (v.w <= 0.01f) return;
        projectedX = (v.x / v.w * 0.5f + 0.5f) * mc.getWindow().getScaledWidth();
        projectedY = (0.5f - v.y / v.w * 0.5f) * mc.getWindow().getScaledHeight();
    }

    @Override public void onHudRender(DrawContext ctx, MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return;
        LivingEntity target = findTarget(mc);
        long now=Util.getMeasuringTimeMs();
        if(target!=null){if(target!=lastTarget){lastTarget=target;shownAt=now;shownFrac=Math.min(1f,Math.max(0,target.getHealth())/Math.max(1,target.getMaxHealth()));}lostAt=0;}
        else if(lastTarget!=null){if(lostAt==0)lostAt=now;if(now-lostAt>180){lastTarget=null;}}
        if(lastTarget==null)return;
        float desired=target!=null?1f:0f; float step=Math.max(0.04f,Math.min(0.22f,mc.getRenderTickCounter().getLastFrameDuration()*0.045f));
        anim += (desired-anim)*step*4.0f; if(anim<0.015f&&desired==0)return;
        LivingEntity living=lastTarget; float enter=Math.min(1f,(now-shownAt)/220f); float alpha=Math.max(0,Math.min(1,Math.min(enter,anim)));
        int sw=mc.getWindow().getScaledWidth(), sh=mc.getWindow().getScaledHeight();
        int w=178,h=58;
        if (projectedX < 0 || projectedY < 0) return;
        int x=(int)(projectedX + 12);
        int y=(int)(projectedY-h*0.5f);
        x=Math.max(5,Math.min(sw-w-5,x)); y=Math.max(5,Math.min(sh-h-5,y));
        int slide=(int)((1-alpha)*14); x+=slide;
        int a=(int)(alpha*255);
        int bg=(a<<24)|0x171A20; int accent=(a<<24)|0xFF8617;
        int white=(a<<24)|0xF3F5F7,muted=(a<<24)|0xAAB3C0;

        // Tlo: pomaranczowy ksztalt (tylko gora) + ciemny panel o tym samym promieniu przesuniety w dol.
        // Zaokraglone rogi ciemnego panelu odslaniaja pomarancz, wiec pasek "wplywa" w boki panelu.
        final int r=12, bar=4;
        ClickGui.roundRectGradient(ctx,x,y,x+w,y+r+bar+2,r,accent,accent,false);
        ClickGui.roundRect(ctx,x,y+bar,x+w,y+h,r,bg);

        // Glowa gracza w zaokraglonej ramce
        int fx=x+11, fy=y+bar+10, fs=28;
        ClickGui.roundRect(ctx,fx-1,fy-1,fx+fs+1,fy+fs+1,6,(a<<24)|0x3B424D);
        if(living instanceof AbstractClientPlayerEntity player){
            Identifier skin=player.getSkinTextures().texture();
            ctx.drawTexture(RenderLayer::getGuiTextured,skin,fx,fy,8f,8f,fs,fs,8,8,64,64);
            ctx.drawTexture(RenderLayer::getGuiTextured,skin,fx,fy,40f,8f,fs,fs,8,8,64,64);
        } else {UiFont.drawCentered(ctx,mc.textRenderer,UiFont.of("?"),fx+fs/2,fy+fs/2-4,white);}

        int tx=x+46;
        String name=living.getName().getString();
        double dist=mc.player.distanceTo(living);
        UiFont.draw(ctx,mc.textRenderer,name,tx,y+bar+8,white);
        int nameWidth=UiFont.width(mc.textRenderer,name);
        UiFont.draw(ctx,mc.textRenderer,String.format(java.util.Locale.ROOT,"%.1fm",dist),tx+nameWidth+5,y+bar+9,muted);

        float hp=Math.max(0,living.getHealth()), max=Math.max(1,living.getMaxHealth());
        float frac=Math.min(1f,hp/max);
        shownFrac+=(frac-shownFrac)*0.25f; if(Math.abs(frac-shownFrac)<0.002f)shownFrac=frac;
        int bx=tx, by=y+bar+24, bw=w-(tx-x)-11, bh=6;
        ClickGui.roundRect(ctx,bx,by,bx+bw,by+bh,3,(a<<24)|0x3A3F48);
        int fill=Math.round(bw*shownFrac);
        int hpColor=(a<<24)|(frac>0.6f?0x62D98B:frac>0.3f?0xFFC15A:0xFF5968);
        if(fill>0) ClickGui.roundRect(ctx,bx,by,bx+Math.max(fill,6),by+bh,3,hpColor);
        UiFont.draw(ctx,mc.textRenderer,String.format(java.util.Locale.ROOT,"HP %.1f / %.1f",hp,max),tx,by+bh+3,white);
    }
    private LivingEntity findTarget(MinecraftClient mc){
        if(mc.crosshairTarget instanceof EntityHitResult hit && hit.getEntity() instanceof LivingEntity e && valid(mc,e)) return e;
        // Forgiving aim: a ray can select a player when it passes within about 3 blocks of their hitbox.
        Vec3d start=mc.player.getCameraPosVec(1.0f); Vec3d dir=mc.player.getRotationVec(1.0f); Vec3d end=start.add(dir.multiply(6.0));
        Box search=mc.player.getBoundingBox().stretch(dir.multiply(6.0)).expand(3.0);
        LivingEntity best=null; double bestAlong=Double.MAX_VALUE;
        for(Entity entity:mc.world.getOtherEntities(mc.player,search,e->e instanceof LivingEntity)){
            if(!(entity instanceof LivingEntity living)||!valid(mc,living))continue;
            Box expanded=living.getBoundingBox().expand(0.45,0.45,0.45);
            var hit=expanded.raycast(start,end); if(hit.isEmpty())continue;
            double along=start.squaredDistanceTo(hit.get()); if(along<bestAlong){bestAlong=along;best=living;}
        }
        return best;
    }
    private boolean valid(MinecraftClient mc,LivingEntity e){return e!=mc.player&&e.isAlive()&&(!playersOnly.get()||e instanceof PlayerEntity)&&mc.player.distanceTo(e)<=6.0f;}
}
