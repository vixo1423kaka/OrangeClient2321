package com.example.orangeclient.module.player;

import com.example.orangeclient.OrangeClient;
import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.setting.BoolSetting;
import com.example.orangeclient.setting.NumberSetting;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.AbstractPressurePlateBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
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
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Port moda "Error Killer" (errorkiller 1.11.1 dla MC 1.21.4, autor: sigmapowazna, licencja MIT).
 *
 * Zadanie: gdy chcesz postawic blok, a w docelowej komorce stoi przeszkoda (dzwignia, przycisk,
 * plytka, tripwire), mod stawia tam wode, zbiera ja z powrotem, a na koncu stawia twoj blok.
 *
 * Zasada ktora naprawia gubienie wody:
 *   BLOK STAWIA SIE TYLKO WTEDY, GDY W placePos NIE MA JUZ PLYNU.
 *   (postawienie bloku w komorce z woda nadpisuje zrodlo i woda przepada)
 * Dodatkowo:
 *   - przed zbieraniem celujemy dokladnie w zrodlo plynu (raycast zbierania to SOURCE_ONLY),
 *   - obrot ustawiamy w onPreTick, zeby zdazyl dojsc do serwera przed pakietem zbierania,
 *   - po zbieraniu czekamy i sprawdzamy, czy wiadro jest NAPRAWDE pelne (serwer koryguje ekwipunek),
 *   - zbieranie ponawiamy, a gdy klient i serwer sie rozjada - wymuszamy plyn client-side i probujemy znow,
 *   - jak plyn w ogole sie nie pojawil, nic nie jest tracone i blok stawiamy normalnie.
 */
public class ErrorKiller extends Module {

    /** Twardy limit dlugosci sekwencji w tickach (zabezpieczenie przed zawieszeniem). */
    private static final int TIMEOUT = 160;
    /** Ile tickow czekamy po zbieraniu, zanim sprawdzimy wynik (serwer zdazy skorygowac ekwipunek). */
    private static final int VERIFY_WAIT = 3;

    /** Przerwa miedzy krokami w tickach (0 = co tick, max 5). */
    private final NumberSetting delay = add(new NumberSetting("Delay", 1, 0, 5, 1));
    /** Ile razy probowac zebrac plyn, zanim odpuscimy. */
    private final NumberSetting tries = add(new NumberSetting("PickupTries", 6, 1, 20, 1));
    /** Celowanie w zrodlo plynu (mocno zwieksza skutecznosc zbierania). */
    private final BoolSetting aim = add(new BoolSetting("Aim", true));

    private static Class<?> targetClass;
    private static boolean targetResolved;

    // 0 = idle, 1 = stawianie plynu, 2 = celowanie, 3 = zbieranie, 4 = stawianie bloku, 5 = weryfikacja bloku
    private int phase;
    private int cooldown;
    private int life;
    private int placeTries;
    private int pickTries;
    private int blockSlot = -1;
    private int fluidSlot = -1;
    private Block fluidBlock = Blocks.WATER;
    private BlockHitResult target;
    private BlockPos placePos;
    private BlockPos fluidPos;
    private float savedYaw, savedPitch;

    public ErrorKiller() {
        super("ErrorKiller", Category.PLAYER);
    }

    @Override
    protected void onDisable() {
        finish(MinecraftClient.getInstance(), true);
    }

    /**
     * Celowanie robimy na POCZATKU ticku (przed myszka i przed wyslaniem pakietu ruchu),
     * dzieki temu obrot jest juz po stronie serwera zanim wyslemy pakiet zbierania.
     */
    @Override
    public void onPreTick(MinecraftClient mc) {
        if (phase == 0 || !aim.get() || mc.player == null || mc.world == null) return;
        Vec3d point = aimPoint(mc);
        if (point != null) lookAt(mc, point);
    }

    /** Dopoki plyn nie istnieje, celujemy w pierwotny punkt trafienia (zeby plyn trafil do placePos). */
    private Vec3d aimPoint(MinecraftClient mc) {
        if (fluidPos != null && hasFluid(mc, fluidPos)) return Vec3d.ofCenter(fluidPos);
        return target != null ? target.getPos() : null;
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
            mc.player.sendMessage(Text.literal("§c[Error Killer] Brak wiadra z woda/lawa w hotbarze!"), true);
            return false;
        }

        blockSlot = mc.player.getInventory().selectedSlot;
        fluidSlot = bucket;
        fluidBlock = mc.player.getInventory().getStack(bucket).isOf(Items.LAVA_BUCKET) ? Blocks.LAVA : Blocks.WATER;

        target = hit;
        placePos = pos;
        fluidPos = pos;
        savedYaw = mc.player.getYaw();
        savedPitch = mc.player.getPitch();

        placeTries = 2;
        pickTries = (int) tries.get();
        life = 0;

        // Krok 1: przelacz na wiadro i postaw plyn.
        switchTo(mc, fluidSlot);
        interactItem(mc);

        phase = 1;
        cooldown = Math.max(1, (int) delay.get());
        return true;
    }

    @Override
    public void onTick(MinecraftClient mc) {
        if (phase == 0) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            finish(mc, true);
            return;
        }
        if (++life > TIMEOUT) {
            finish(mc, true);
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        switch (phase) {
            case 1 -> stepPlaceFluid(mc);
            case 2 -> {
                phase = 3;
                cooldown = Math.max(1, (int) delay.get()) + 1; // obrot musi dojsc do serwera
            }
            case 3 -> stepPickup(mc);
            case 4 -> stepPlaceBlock(mc);
            case 5 -> stepVerifyBlock(mc);
        }
    }

    /** Czekamy az plyn sie pojawi. Jesli sie nie pojawil - nic nie tracimy, stawiamy blok. */
    private void stepPlaceFluid(MinecraftClient mc) {
        if (hasFluid(mc, placePos)) {
            fluidPos = placePos;
            phase = 2;
            cooldown = 0;
            return;
        }

        BlockPos near = findFluidNear(mc);
        if (near != null) { // plyn trafil obok (np. przed dzwignia zamiast w nia)
            fluidPos = near;
            phase = 2;
            cooldown = 0;
            return;
        }

        if (--placeTries > 0) {
            switchTo(mc, fluidSlot);
            interactItem(mc);
            cooldown = Math.max(1, (int) delay.get());
        } else {
            phase = 4;
            cooldown = 0;
        }
    }

    /**
     * Zbieranie z weryfikacja. Blok stawiamy dopiero gdy plyn zniknal z placePos
     * i mamy pelne wiadro - inaczej blok nadpisalby wode.
     */
    private void stepPickup(MinecraftClient mc) {
        boolean full = bucketFull(mc);
        boolean fluid = hasFluid(mc, fluidPos);

        if (full && !fluid) { // sukces
            phase = 4;
            cooldown = 0;
            return;
        }

        if (--pickTries <= 0) {
            phase = 4;
            cooldown = 0;
            return;
        }

        if (full) {
            // wiadro pelne, a plyn w swiecie -> oprozniamy wiadro (postawienie), wtedy zbierzemy
            interactItem(mc);
        } else {
            // klient i serwer mogly sie rozjechac (serwer odrzucil zbieranie) -> wymuszamy plyn client-side
            if (!fluid) forceFluid(mc);
            interactItem(mc);
        }

        cooldown = VERIFY_WAIT;
    }

    private void stepPlaceBlock(MinecraftClient mc) {
        switchTo(mc, blockSlot);
        if (target != null) mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, target);
        phase = 5;
        cooldown = 3;
    }

    private void stepVerifyBlock(MinecraftClient mc) {
        if (!mc.world.getBlockState(placePos).isAir() || !mc.world.getFluidState(placePos).isEmpty()) {
            finish(mc, true);
            return;
        }
        if (--placeTries > 0) {
            phase = 4;
            cooldown = 0;
        } else {
            finish(mc, true);
        }
    }

    private void finish(MinecraftClient mc, boolean restoreAim) {
        boolean waterLeft = false;
        if (mc != null && mc.player != null && mc.world != null && fluidPos != null) {
            waterLeft = hasFluid(mc, fluidPos) && !bucketFull(mc);
        }
        if (mc != null && mc.player != null) {
            if (blockSlot >= 0) switchTo(mc, blockSlot);
            if (restoreAim && aim.get()) {
                mc.player.setYaw(savedYaw);
                mc.player.setPitch(savedPitch);
            }
            if (waterLeft) mc.player.sendMessage(Text.literal("§e[Error Killer] Nie udalo sie zebrac plynu."), true);
        }
        phase = 0;
        cooldown = 0;
        life = 0;
        placeTries = 0;
        pickTries = 0;
        blockSlot = -1;
        fluidSlot = -1;
        target = null;
        placePos = null;
        fluidPos = null;
    }

    private static void interactItem(MinecraftClient mc) {
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
    }

    private static void switchTo(MinecraftClient mc, int slot) {
        if (slot >= 0 && slot < 9) mc.player.getInventory().selectedSlot = slot;
    }

    /** Awaryjne wyrównanie klienta z serwerem: stawiamy plyn tylko po stronie klienta. */
    private void forceFluid(MinecraftClient mc) {
        if (fluidPos != null) mc.world.setBlockState(fluidPos, fluidBlock.getDefaultState(), 3);
    }

    private static void lookAt(MinecraftClient mc, Vec3d point) {
        Vec3d eye = mc.player.getEyePos();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) (-(MathHelper.atan2(dy, flat) * (180.0 / Math.PI)));
        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
    }

    private static boolean hasFluid(MinecraftClient mc, BlockPos pos) {
        return pos != null && !mc.world.getFluidState(pos).isEmpty();
    }

    private BlockPos findFluidNear(MinecraftClient mc) {
        if (placePos == null) return null;
        for (Direction d : Direction.values()) {
            BlockPos p = placePos.offset(d);
            if (hasFluid(mc, p)) return p;
        }
        return null;
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