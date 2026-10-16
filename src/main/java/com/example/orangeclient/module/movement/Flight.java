package com.example.orangeclient.module.movement;

import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.setting.ModeSetting;
import com.example.orangeclient.setting.NumberSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

/** Client-side flight controls. Multiplayer servers may correct or reject these movements. */
public final class Flight extends Module {
    private final ModeSetting mode = add(new ModeSetting("Mode", "Vanilla", "Vanilla", "Glide", "Creative"));
    private final NumberSetting horizontal = add(new NumberSetting("Horizontal", 0.5, 0.1, 2.0, 0.1));
    private final NumberSetting vertical = add(new NumberSetting("Vertical", 0.45, 0.1, 1.5, 0.05));
    private final NumberSetting glide = add(new NumberSetting("Glide", 0.04, 0.0, 0.15, 0.01));
    public Flight() { super("Flight", Category.MOVEMENT); }
    @Override public void onTick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || mc.currentScreen != null) return;
        var p = mc.player;
        double forward = (mc.options.forwardKey.isPressed() ? 1 : 0) - (mc.options.backKey.isPressed() ? 1 : 0);
        double side = (mc.options.leftKey.isPressed() ? 1 : 0) - (mc.options.rightKey.isPressed() ? 1 : 0);
        double yaw = Math.toRadians(p.getYaw());
        double x = (-Math.sin(yaw) * forward + Math.cos(yaw) * side) * horizontal.get();
        double z = (Math.cos(yaw) * forward + Math.sin(yaw) * side) * horizontal.get();
        double y = (mc.options.jumpKey.isPressed() ? vertical.get() : 0) - (mc.options.sneakKey.isPressed() ? vertical.get() : 0);
        switch (mode.get()) {
            case "Creative" -> { p.getAbilities().allowFlying = true; p.getAbilities().flying = true; p.getAbilities().setFlySpeed((float)(horizontal.get() * 0.1)); }
            case "Glide" -> { p.setVelocity(x, mc.options.jumpKey.isPressed() ? vertical.get() : mc.options.sneakKey.isPressed() ? -vertical.get() : -glide.get(), z); }
            default -> { p.setVelocity(new Vec3d(x, y, z)); }
        }
    }
    @Override protected void onDisable() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && !mc.player.isCreative() && !mc.player.isSpectator()) {
            mc.player.getAbilities().flying = false; mc.player.getAbilities().allowFlying = false; mc.player.getAbilities().setFlySpeed(0.05f);
        }
    }
}
