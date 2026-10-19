package com.example.orangeclient.module.movement;

import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.setting.BoolSetting;
import com.example.orangeclient.setting.NumberSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.util.math.MathHelper;

/** Lets the camera look around without changing the player's actual yaw/pitch. */
public final class FreeLook extends Module {
    private static FreeLook instance;
    private final NumberSetting sensitivity = add(new NumberSetting("Sensitivity", 1.0, 0.1, 3.0, 0.1));
    private final BoolSetting invertY = add(new BoolSetting("InvertY", false));
    private final BoolSetting thirdPerson = add(new BoolSetting("ThirdPerson", true));
    private float yaw, pitch;
    private Perspective prevPerspective;

    public FreeLook() { super("FreeLook", Category.MOVEMENT); instance = this; }
    public static boolean isActive() { return instance != null && instance.isEnabled(); }
    public static float cameraYaw() { return instance == null ? 0 : instance.yaw; }
    public static float cameraPitch() { return instance == null ? 0 : instance.pitch; }
    public static void look(double dx, double dy) {
        FreeLook f = instance;
        if (f == null || !f.isEnabled()) return;
        f.yaw = MathHelper.wrapDegrees(f.yaw + (float) dx * 0.15f * (float) f.sensitivity.get());
        // Entity.changeLookDirection vanilla scales raw cursor deltas by 0.15.
        // Keep the same base scale so FreeLook feels like normal Minecraft mouse look.
        f.pitch = MathHelper.clamp(f.pitch + (float) dy * 0.15f * (float) f.sensitivity.get() * (f.invertY.get() ? -1f : 1f), -90f, 90f);
    }
    @Override protected void onEnable() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) { setEnabled(false); return; }
        yaw = mc.player.getYaw(); pitch = mc.player.getPitch();
        prevPerspective = mc.options.getPerspective();
        if (thirdPerson.get()) mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
    }
    @Override protected void onDisable() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (prevPerspective != null && mc.options != null) mc.options.setPerspective(prevPerspective);
        prevPerspective = null;
    }
}
