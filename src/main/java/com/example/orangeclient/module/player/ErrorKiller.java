package com.example.orangeclient.module.player;

import com.example.orangeclient.OrangeClient;
import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.setting.NumberSetting;
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
 * Port moda "Error Killer" (errorkiller 1.11.1 dla MC 1.21.4, autor: sigmapowazna, licencja MIT) na modul klienta.
 *
 * Zamiast robic wszystko w jednym ticku, sekwencja jest rozlozona na ticki:
 *   1) przelacz na wiadro i postaw plyn,
 *   2) poczekaj az plyn sie pojawi i zbierz go,
 *   3) sprawdz ze wiadro jest znowu pelne, w razie potrzeby ponow zbieranie,
 *   4) dopiero wtedy postaw blok.
 * Dzieki temu blok nie nadpisuje wody, gdy zbieranie sie nie uda.
 */
public class ErrorKiller extends Module {
    /** Przerwa miedzy krokami w tickach (0 = co tick, max 5). */
    private final NumberSetting delay = add(new NumberSetting("Delay", 1, 0, 5, 1));
    /** Ile razy probowac zebrac plyn, zanim odpuscimy. */
    private final NumberSetting tries = add(new NumberSetting("PickupTries", 4, 1, 10, 1));

    private static Class<?> targetClass;
    private static boolean targetResolved;

    // --- stan sekwencji ---
    private int phase;          // 0 = idle, 1 = plyn postawiony, 2 = zbieranie plynu
    private int cooldown;
    private int triesLeft;
    private int blockSlot = -1, fluidSlot = -1;
    private BlockHitResult target;
    private BlockPos placePos;

    public ErrorKiller() {
        super("ErrorKiller", Category.PLAYER);
    }

    @Override
    protected void onDisable() {
        reset();
    }

    private void reset() {
        phase = 0;
        cooldown = 0;
        triesLeft = 0;
        blockSlot = -1;
        fluidSlot = -1;
        target = null;
        placePos = null;
    }

    /**
     * Wywolywane z mixina na poczatku MinecraftClient#doItemUse.
     * @return true, jesli zwykle uzycie przedmiotu ma zostac anulowane
     */
    public boolean tryIntercept(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return false;
        if (phase != 0) return true;

        if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) return false;

        ItemStack main = mc.player.getStackInHand(Hand.MAIN_HAND);
        if (!(main.getItem() instanceof BlockItem)) return false;

        BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
        BlockPos pos = hit.getBlockPos().offset(hit.getSide());
        if (!isTargetBlock(mc.world.getBlockState(pos).getBlock())) return false;

        int bucket = findBucket(mc);
        if (bucket == -1) {
            mc.player.sendMessage(Text.literal("§c[Error Killer] Brak wiadra z woda/lawa!"), true);
            return false;
        }

        blockSlot = mc.player.getInventory().selectedSlot;
        fluidSlot = bucket;
        target = hit;
        placePos = pos;

        // Krok 1: przelacz na wiadro i postaw plyn.
        mc.player.getInventory().selectedSlot = fluidSlot;
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);

        phase = 1;
        triesLeft = (int) tries.get();
        cooldown = (int) delay.get();
        return true;
    }

    @Override
    public void onTick(MinecraftClient mc) {
        if (phase == 0 || mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (cooldown > 0) { cooldown--; return; }

        switch (phase) {
            case 1 -> {
                if (fluidPlaced(mc)) {
                    switchTo(mc, fluidSlot);
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                    phase = 2;
                    cooldown = (int) delay.get();
                } else if (--triesLeft <= 0) {
                    placeBlock(mc);
                }
            }
            case 2 -> {
                if (bucketFull(mc)) {
                    placeBlock(mc);
                } else if (--triesLeft > 0) {
                    switchTo(mc, fluidSlot);
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                    cooldown = (int) delay.get();
                } else {
                    placeBlock(mc);
                }
            }
        }
    }

    private void placeBlock(MinecraftClient mc) {
        switchTo(mc, blockSlot);
        if (target != null) mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, target);
        reset();
    }

    private static void switchTo(MinecraftClient mc, int slot) {
        if (slot >= 0 && slot < 9) mc.player.getInventory().selectedSlot = slot;
    }

    /** Czy w miejscu docelowym jest juz plyn (woda/lawa). */
    private boolean fluidPlaced(MinecraftClient mc) {
        return placePos != null && !mc.world.getFluidState(placePos).isEmpty();
    }

    /** Czy w slocie z wiadrem jest znowu pelne wiadro. */
    private boolean bucketFull(MinecraftClient mc) {
        if (fluidSlot < 0) return false;
        ItemStack s = mc.player.getInventory().getStack(fluidSlot);
        return s.isOf(Items.WATER_BUCKET) || s.isOf(Items.LAVA_BUCKET);
    }

    private static int findBucket(MinecraftClient mc) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isOf(Items.WATER_BUCKET) || s.isOf(Items.LAVA_BUCKET)) return i;
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
                OrangeClient.LOGGER.error("ErrorKiller: nie udalo sie rozwiazac klasy bloku-celu", t);
            }
        }
        return targetClass != null && targetClass.isInstance(block);
    }
}
