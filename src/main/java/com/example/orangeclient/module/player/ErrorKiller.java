package com.example.orangeclient.module.player;

import com.example.orangeclient.OrangeClient;
import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.AbstractPressurePlateBlock;
import net.minecraft.block.Block;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.TripwireHookBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

/**
 * ErrorKiller: when placing a block into a cell occupied by a small attachable
 * block, use the original two bucket interactions, then place the selected block.
 */
public class ErrorKiller extends Module {
    private static Class<?> targetClass;
    private static boolean targetResolved;

    public ErrorKiller() {
        super("ErrorKiller", Category.PLAYER);
    }

    /**
     * Called from MinecraftClient#doItemUse HEAD. Returns true only when the
     * attempted placement was handled by ErrorKiller.
     */
    public boolean tryIntercept(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null
                || mc.crosshairTarget == null) {
            return false;
        }
        if (mc.crosshairTarget.getType() != HitResult.Type.BLOCK) return false;

        ItemStack selected = mc.player.getStackInHand(Hand.MAIN_HAND);
        if (!(selected.getItem() instanceof BlockItem)) return false;

        BlockHitResult target = (BlockHitResult) mc.crosshairTarget;
        BlockPos placePos = target.getBlockPos().offset(target.getSide());
        if (!isTargetBlock(mc.world.getBlockState(placePos).getBlock())) return false;

        int blockSlot = mc.player.getInventory().selectedSlot;
        int bucketSlot = findBucket(mc);
        if (bucketSlot < 0) {
            mc.player.sendMessage(Text.literal("§c[Error Killer] Brak wiadra z wodą/lawą!"), true);
            return false;
        }

        // Important: preserve the original mod's exact interaction sequence.
        // interactItem performs Minecraft's normal bucket raycast. Replacing it
        // with interactBlock(target) targets the support block, not the bucket's
        // actual fluid-placement target; waiting for fluid at placePos then stalls.
        mc.player.getInventory().selectedSlot = bucketSlot;
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);

        mc.player.getInventory().selectedSlot = blockSlot;
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, target);
        return true;
    }

    private static int findBucket(MinecraftClient mc) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(Items.WATER_BUCKET) || stack.isOf(Items.LAVA_BUCKET)) return i;
        }
        return -1;
    }

    private static boolean isTargetBlock(Block block) {
        if (block instanceof ButtonBlock
                || block instanceof AbstractPressurePlateBlock
                || block instanceof LeverBlock
                || block instanceof TripwireHookBlock) {
            return true;
        }

        if (!targetResolved) {
            targetResolved = true;
            try {
                String name = FabricLoader.getInstance().getMappingResolver()
                        .mapClassName("intermediary", "net.minecraft.class_2401");
                targetClass = Class.forName(name);
            } catch (Throwable t) {
                OrangeClient.LOGGER.error("ErrorKiller: nie udało się rozwiązać klasy bloku-celu", t);
            }
        }
        return targetClass != null && targetClass.isInstance(block);
    }
}
