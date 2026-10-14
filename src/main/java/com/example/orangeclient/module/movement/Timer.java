package com.example.orangeclient.module.movement;

import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.setting.BoolSetting;
import com.example.orangeclient.setting.NumberSetting;
import net.minecraft.client.MinecraftClient;

/** Client tick-rate modifier. Servers can still correct or reject accelerated movement. */
public final class Timer extends Module {
    private final NumberSetting speed = add(new NumberSetting("Speed", 1.2, 0.1, 5.0, 0.1));
    private final BoolSetting movingOnly = add(new BoolSetting("MovingOnly", false));
    private final BoolSetting pauseInGui = add(new BoolSetting("PauseInGui", true));
    private final BoolSetting pauseInWater = add(new BoolSetting("PauseInWater", false));

    public Timer() { super("Timer", Category.MOVEMENT); }
    /** Called before Dynamic.beginRenderTick calculates this frame's client ticks. */
    public float getTickTime(MinecraftClient mc) {
        if (mc == null) return 50.0f;
        boolean apply = isEnabled() && mc.player != null && mc.world != null;
        if (apply && pauseInGui.get() && mc.currentScreen != null) apply = false;
        if (apply && pauseInWater.get() && (mc.player.isTouchingWater() || mc.player.isInLava())) apply = false;
        if (apply && movingOnly.get()) {
            apply = mc.options.forwardKey.isPressed() || mc.options.backKey.isPressed()
                    || mc.options.leftKey.isPressed() || mc.options.rightKey.isPressed()
                    || mc.options.jumpKey.isPressed() || mc.options.sneakKey.isPressed();
        }
        float multiplier = apply ? (float) speed.get() : 1.0f;
        return 50.0f / multiplier;
    }
}
