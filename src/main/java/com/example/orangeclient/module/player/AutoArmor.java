package com.example.orangeclient.module.player;

import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.setting.NumberSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;

/** Equips better armor pieces from the inventory. */
public final class AutoArmor extends Module {
    private final NumberSetting delay = add(new NumberSetting("Delay", 2, 0, 10, 1));
    private int cooldown;
    public AutoArmor() { super("AutoArmor", Category.PLAYER); }
    @Override public void onTick(MinecraftClient mc) {
        if (mc.player == null || mc.interactionManager == null || mc.currentScreen != null) return;
        if (cooldown > 0) { cooldown--; return; }
        var inv = mc.player.getInventory();
        for (int i = 9; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            EquippableComponent equippable = stack.get(DataComponentTypes.EQUIPPABLE);
            if (equippable == null) continue;
            int armorSlot = switch (equippable.slot()) {
                case FEET -> 8; case LEGS -> 7; case CHEST -> 6; case HEAD -> 5; default -> -1;
            };
            if (armorSlot < 0) continue;
            ItemStack worn = mc.player.currentScreenHandler.getSlot(armorSlot).getStack();
            if (!worn.isEmpty() && protection(worn) >= protection(stack)) continue;
            mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
            cooldown = (int) delay.get(); return;
        }
    }

    private static int protection(ItemStack stack) {
        // Stable fallback for 1.21.4: rank common armor materials by item id.
        // The equipped slot itself comes from the item's EQUIPPABLE component.
        String id = Registries.ITEM.getId(stack.getItem()).getPath();
        int material = id.contains("netherite") ? 7
                : id.contains("diamond") ? 6
                : id.contains("turtle") ? 5
                : id.contains("iron") ? 4
                : id.contains("chainmail") ? 3
                : id.contains("golden") ? 2
                : id.contains("leather") ? 1 : 0;
        return material * 100;
    }
}
