package com.example.orangeclient.live;

import com.example.orangeclient.module.ModuleManager;
import com.example.orangeclient.module.combat.KillAura;
import com.example.orangeclient.module.player.ErrorKiller;
import net.minecraft.client.MinecraftClient;

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
}
