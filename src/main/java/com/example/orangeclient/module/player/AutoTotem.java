package com.example.orangeclient.module.player;

import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.setting.BoolSetting;
import com.example.orangeclient.setting.ModeSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

/** Automatically swaps a totem from the inventory into the offhand when needed. */
public class AutoTotem extends Module {
    private final ModeSetting priority = add(new ModeSetting("Priority", "Hotbar", "Hotbar", "Inventory"));
    private final BoolSetting hotbarFirst = add(new BoolSetting("HotbarFirst", true));
    private final BoolSetting keepInOffhand = add(new BoolSetting("KeepInOffhand", true));

    public AutoTotem() { super("AutoTotem", Category.PLAYER); }

    @Override public void onTick(MinecraftClient mc) {
        if (mc.player == null || mc.interactionManager == null || mc.currentScreen != null) return;
        if (!keepInOffhand.get() && !mc.player.getOffHandStack().isEmpty()) return;
        if (mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) return;
        var inv = mc.player.getInventory();
        int found = -1;
        boolean preferHotbar = priority.is("Hotbar") && hotbarFirst.get();
        if (preferHotbar) {
            for (int i = 0; i < 9; i++) if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) { found = i; break; }
        }
        if (found == -1) {
            for (int i = 9; i < 36; i++) if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) { found = i; break; }
        }
        if (found == -1 && !preferHotbar) {
            for (int i = 0; i < 9; i++) if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) { found = i; break; }
        }
        if (found == -1) return;
        int slotId = found < 9 ? 36 + found : found;
        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slotId, 40, SlotActionType.SWAP, mc.player);
    }
}
