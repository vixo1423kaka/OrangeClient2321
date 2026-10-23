package com.example.orangeclient.live;

import com.example.orangeclient.module.ModuleManager;
import com.example.orangeclient.module.combat.KillAura;
import com.example.orangeclient.module.player.ErrorKiller;
import net.minecraft.client.MinecraftClient;

public final class Hooks {
    private static boolean failedLogged;

    private Hooks() { }

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
            ErrorKiller ek = ModuleManager.get(ErrorKiller.class);
            return ek != null && ek.isEnabled() && ek.tryIntercept(MinecraftClient.getInstance());
        } catch (Throwable t) { fail("errorKiller", t); return false; }
    }

    public static void killAuraTick(Object self) {
        try {
            KillAura ka = ModuleManager.get(KillAura.class);
            if (ka != null && ka.isEnabled()) ka.tickRotation(MinecraftClient.getInstance());
        } catch (Throwable t) { fail("killAuraTick", t); }
    }

    public static void killAuraPre(Object self) {
        try {
            KillAura ka = ModuleManager.get(KillAura.class);
            if (ka != null && ka.isEnabled()) ka.preMotion(MinecraftClient.getInstance());
        } catch (Throwable t) { fail("killAuraPre", t); }
    }

    public static void killAuraPost(Object self) {
        try {
            KillAura ka = ModuleManager.get(KillAura.class);
            if (ka != null) ka.postMotion(MinecraftClient.getInstance());
        } catch (Throwable t) { fail("killAuraPost", t); }
    }
}
