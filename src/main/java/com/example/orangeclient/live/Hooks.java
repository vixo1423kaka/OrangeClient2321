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

    /** ClientPlayerEntity.tick() HEAD. */
    public static void killAuraTick(Object self) {
        try {
            fired("killAuraTick");
            KillAura ka = ModuleManager.get(KillAura.class);
            if (ka != null && ka.isEnabled()) {
                fired("killAuraTick (module enabled)");
                ka.tickRotation(MinecraftClient.getInstance());
            }
        } catch (Throwable t) { fail("killAuraTick", t); }
    }

    /** ClientPlayerEntity.sendMovementPackets() HEAD. */
    public static void killAuraPre(Object self) {
        try {
            fired("killAuraPre");
            KillAura ka = ModuleManager.get(KillAura.class);
            if (ka != null && ka.isEnabled()) {
                fired("killAuraPre (module enabled)");
                ka.preMotion(MinecraftClient.getInstance());
            }
        } catch (Throwable t) { fail("killAuraPre", t); }
    }

    /** ClientPlayerEntity.sendMovementPackets() RETURN. */
    public static void killAuraPost(Object self) {
        try {
            fired("killAuraPost");
            KillAura ka = ModuleManager.get(KillAura.class);
            if (ka != null && ka.isEnabled()) {
                fired("killAuraPost (module enabled)");
                ka.postMotion(MinecraftClient.getInstance());
            }
        } catch (Throwable t) { fail("killAuraPost", t); }
    }
}
