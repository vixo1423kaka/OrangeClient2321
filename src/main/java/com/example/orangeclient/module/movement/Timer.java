package com.example.orangeclient.module.movement;

import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.setting.BoolSetting;
import com.example.orangeclient.setting.NumberSetting;
import net.minecraft.client.MinecraftClient;

/** Orange-tuned client timer inspired by configurable timer modules. */
public final class Timer extends Module {
    private final NumberSetting speed = add(new NumberSetting("Speed", 1.2, 0.1, 3.0, 0.1));
    private final BoolSetting movingOnly = add(new BoolSetting("MovingOnly", false));
    private final BoolSetting pauseInGui = add(new BoolSetting("PauseInGui", true));
    private final BoolSetting pauseInWater = add(new BoolSetting("PauseInWater", false));
    private final BoolSetting pauseWhileSneaking = add(new BoolSetting("PauseWhileSneaking", false));
    public Timer() { super("Timer", Category.MOVEMENT); }
    /** Read by the RenderTickCounter mixin. */
    public float getTickTime(MinecraftClient mc) {
        if (mc == null) return 50f;
        boolean apply = isEnabled() && mc.player != null && mc.world != null;
        if (apply && pauseInGui.get() && mc.currentScreen != null) apply = false;
        if (apply && pauseInWater.get() && (mc.player.isTouchingWater() || mc.player.isInLava())) apply = false;
        if (apply && pauseWhileSneaking.get() && mc.player.isSneaking()) apply = false;
        if (apply && movingOnly.get()) apply = mc.options.forwardKey.isPressed() || mc.options.backKey.isPressed() || mc.options.leftKey.isPressed() || mc.options.rightKey.isPressed() || mc.options.jumpKey.isPressed();
        return 50f / (apply ? (float)speed.get() : 1f);
    }
}
