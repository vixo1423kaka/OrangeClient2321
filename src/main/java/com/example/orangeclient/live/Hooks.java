package com.example.orangeclient.live;

import com.example.orangeclient.module.ModuleManager;
import com.example.orangeclient.module.combat.Hitbox;
import com.example.orangeclient.module.combat.KillAura;
import com.example.orangeclient.module.player.AntiTrap;
import com.example.orangeclient.module.player.ErrorKiller;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class Hooks {
    private static final Set<String> FIRED = ConcurrentHashMap.newKeySet();
    private static boolean failedLogged;
    /** True when killAuraTick ran this tick, so killAuraPost (end of tick) must attack and restore the camera. */
    private static boolean killAuraActive;

    private Hooks() {}

    private static void fired(String name) {
        if (FIRED.add(name)) LiveMixins.log("hook fired: " + name);
    }

    private static void fail(String where, Throwable t) {
        if (failedLogged) return;
        failedLogged = true;
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        LiveMixins.log("hook error in " + where + " (further errors suppressed):\n" + sw);
    }

    /** MinecraftClient.doItemUse() HEAD, cancellable. */
    public static boolean errorKiller(Object self) {
        try {
            fired("errorKiller");
            ErrorKiller ek = ModuleManager.get(ErrorKiller.class);
            if (ek != null && ek.isEnabled()) fired("errorKiller (module enabled)");
            return ek != null && ek.isEnabled() && ek.tryIntercept(MinecraftClient.getInstance());
        } catch (Throwable t) { fail("errorKiller", t); return false; }
    }

    /** MinecraftClient.tick() HEAD: set the server rotation before the player ticks and sends movement packets. */
    public static void killAuraTick(Object self) {
        killAuraActive = false;
        try {
            fired("killAuraTick");
            KillAura ka = ModuleManager.get(KillAura.class);
            if (ka != null && ka.isEnabled()) {
                fired("killAuraTick (module enabled)");
                killAuraActive = true;
                ka.tickRotation(MinecraftClient.getInstance());
            }
        } catch (Throwable t) { fail("killAuraTick", t); }
    }

    /** Not used any more (ClientPlayerEntity.sendMovementPackets() could not be patched). Kept for compatibility. */
    public static void killAuraPre(Object self) { }

    /** MinecraftClient.tick() RETURN: the movement packet with the rotation was already sent, so attack and restore the camera. */
    public static void killAuraPost(Object self) {
        if (!killAuraActive) return;
        killAuraActive = false;
        try {
            fired("killAuraPost");
            KillAura ka = ModuleManager.get(KillAura.class);
            if (ka != null) {
                fired("killAuraPost (module enabled)");
                ka.postMotion(MinecraftClient.getInstance());
            }
        } catch (Throwable t) { fail("killAuraPost", t); }
    }

    /** GameRenderer.findCrosshairTarget() RETURN: Hitbox first, then AntiTrap (same logic as the former GameRendererMixin). */
    public static Object crosshair(Object self, Object ret, Object cameraObj, double blockRange, double entityRange, float tickDelta) {
        try {
            fired("crosshair");
            HitResult original = (HitResult) ret;
            Entity camera = (Entity) cameraObj;
            HitResult hr = Hitbox.adjust(original, camera, blockRange, entityRange, tickDelta);
            HitResult result = hr;
            if (hr != null && hr.getType() == HitResult.Type.ENTITY
                    && (AntiTrap.active() || AntiTrap.hides(((EntityHitResult) hr).getEntity()))) {
                result = camera.raycast(blockRange, tickDelta, false);
            }
            // Must be last: forced target for digging a trap "through" a cobweb.
            BlockHitResult forced = AntiTrap.forcedHit();
            if (forced != null) result = forced;
            return result;
        } catch (Throwable t) { fail("crosshair", t); return ret; }
    }

    /** EntityRenderDispatcher.shouldRender()/render() HEAD: hide item frames, paintings and minecarts. */
    public static boolean antiTrapHides(Object entity) {
        try {
            return entity instanceof Entity e && AntiTrap.hides(e);
        } catch (Throwable t) { fail("antiTrapHides", t); return false; }
    }

    /** ClientPlayerInteractionManager.interactBlock() HEAD: right click on a GUI block does nothing. Returns null to continue. */
    public static Object antiTrapNoGui(Object self, Object player, Object hand, Object hit) {
        try {
            if (player instanceof PlayerEntity p && hit instanceof BlockHitResult h && AntiTrap.blocksGui(p, h)) {
                fired("antiTrapNoGui (blocked a GUI block)");
                return ActionResult.FAIL;
            }
            return null;
        } catch (Throwable t) { fail("antiTrapNoGui", t); return null; }
    }
}