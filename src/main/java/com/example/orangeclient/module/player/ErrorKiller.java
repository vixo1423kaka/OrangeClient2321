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
 * Port moda "Error Killer" (errorkiller 1.11.1 dla MC 1.21.4, autor: sigmapowazna, licencja MIT).
 *
 * Gdy chcesz postawic blok, a w docelowej komorce stoi przeszkoda (dzwignia, przycisk, plytka,
 * tripwire), mod stawia tam wode, zbiera ja z powrotem, a na koncu stawia twoj blok.
 *
 * Delay:
 *   0 = minimalna przerwa jednego ticku (serwer musi zdazyc przetworzyc postawienie plynu)
 *   1-3 = przerwa w tickach miedzy krokami (wolniej, ale stabilniej na serwerze)
 */
public class ErrorKiller extends Module {
    /** Przerwa miedzy krokami w tickach: 0 = od razu, 3 = najwolniej. */
    private final NumberSetting delay = add(new NumberSetting("Delay", 1, 0, 3, 1));

    private static Class<?> targetClass;
    private static boolean targetResolved;

    // 0 = idle, 1 = plyn postawiony (czekamy na zbieranie), 2 = plyn zebrany (czekamy na blok)
    private int phase;
    private int cooldown;
    private int blockSlot = -1;
    private int fluidSlot = -1;
    private BlockHitResult target;

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
        blockSlot = -1;
        fluidSlot = -1;
        target = null;
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

        // Krok 1: przelacz na wiadro i postaw plyn.
        switchTo(mc, fluidSlot);
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);

        // Nie zbieraj plynu w tym samym ticku, w ktorym zostal postawiony.
        // Interakcje ida do serwera asynchronicznie; natychmiastowe postawienie i
        // zebranie w jednym ticku czesto powoduje, ze kolejne akcje nie trafiaja.
        phase = 1;
        cooldown = Math.max(1, (int) delay.get());
        return true;
    }

    @Override
    public void onTick(MinecraftClient mc) {
        if (phase == 0) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            reset();
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        if (phase == 1) {
            pickUp(mc);
            phase = 2;
            cooldown = Math.max(1, (int) delay.get());
        } else {
            placeBlock(mc);
        }
    }

    private void pickUp(MinecraftClient mc) {
        switchTo(mc, fluidSlot);
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
    }

    private void placeBlock(MinecraftClient mc) {
        switchTo(mc, blockSlot);
        if (target != null) mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, target);
        reset();
    }

    private static void switchTo(MinecraftClient mc, int slot) {
        if (slot >= 0 && slot < 9) mc.player.getInventory().selectedSlot = slot;
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
