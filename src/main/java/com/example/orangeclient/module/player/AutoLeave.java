package com.example.orangeclient.module.player;

import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.setting.NumberSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;

/** Disconnects as soon as another player is present inside the client's configured view distance. */
public final class AutoLeave extends Module {
    private final NumberSetting distance = add(new NumberSetting("Distance", 64, 8, 256, 8));

    public AutoLeave() { super("AutoLeave", Category.PLAYER); }

    @Override
    public void onTick(MinecraftClient mc) {
        if (mc == null || mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;

        // Configurable radius in blocks; only currently loaded players can trigger it.
        double configuredDistance = distance.get();
        double maxDistanceSquared = configuredDistance * configuredDistance;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.isRemoved()) continue;
            if (mc.player.squaredDistanceTo(player) <= maxDistanceSquared) {
                // Disable first so it cannot trigger repeatedly during the disconnect transition.
                setEnabled(false);
                mc.player.sendMessage(Text.literal("§c[AutoLeave] Player detected — leaving server."), true);
                mc.world.disconnect();
                mc.disconnect(new TitleScreen());
                return;
            }
        }
    }
}
