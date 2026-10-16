package com.example.orangeclient.module.player;

import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.setting.NumberSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

/** Transfers items from open chest-like containers into the player's inventory. */
public final class ChestStealer extends Module {
    private final NumberSetting delay = add(new NumberSetting("Delay", 2, 0, 10, 1));
    private int cooldown;
    public ChestStealer() { super("ChestStealer", Category.PLAYER); }
    @Override protected void onEnable() { cooldown = 0; }
    @Override public void onTick(MinecraftClient mc) {
        if (mc.player == null || mc.interactionManager == null || !(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler h)) return;
        if (cooldown > 0) { cooldown--; return; }
        int containerSlots = h.getRows() * 9;
        for (int i = 0; i < containerSlots; i++) {
            if (h.getSlot(i).hasStack()) {
                mc.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                cooldown = (int) delay.get();
                return;
            }
        }
    }
}
