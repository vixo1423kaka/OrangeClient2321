package com.example.orangeclient.module.player;

import com.example.orangeclient.OrangeClient;
import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.AbstractButtonBlock;
import net.minecraft.block.AbstractPressurePlateBlock;
import net.minecraft.block.Block;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.block.TorchBlock;
import net.minecraft.block.TripwireBlock;
import net.minecraft.block.TripwireHookBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.Set;

/**
 * ErrorKiller: uses a liquid bucket to remove fragile target blocks, waits for the
 * liquid to become a source, collects it, and only then places the held block.
 * The waits are intentional: the server cannot place and collect a bucket liquid
 * reliably when both actions are sent in the same tick.
 */
public class ErrorKiller extends Module {
    private static Class<?> targetClass;
    private static boolean targetResolved;

    private enum Stage { IDLE, WAIT_SOURCE, WAIT_PICKUP }

    private Stage stage = Stage.IDLE;
    private BlockHitResult originalHit;
    private BlockPos targetPos;
    private BlockPos liquidPos;
    private int blockSlot = -1;
    private int liquidSlot = -1;
    private ItemStack liquidBucket = ItemStack.EMPTY;
    private Fluid liquid = Fluids.EMPTY;
    private final Set<BlockPos> fluidsBefore = new HashSet<>();
    private int stageTicks;
    private int lastPickupAttempt;

    public ErrorKiller() {
        super("ErrorKiller", Category.PLAYER);
    }

    /**
     * Called by MinecraftClient#doItemUse. True means the vanilla click is consumed.
     */
    public boolean tryIntercept(MinecraftClient mc) {
        if (stage != Stage.IDLE) return true;
        if (mc.player == null || mc.world == null || mc.interactionManager == null
                || mc.currentScreen != null || mc.crosshairTarget == null) return false;
        if (mc.crosshairTarget.getType() != HitResult.Type.BLOCK) return false;

        ItemStack held = mc.player.getStackInHand(Hand.MAIN_HAND);
        if (!(held.getItem() instanceof BlockItem)) return false;

        BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
        BlockPos placePos = hit.getBlockPos().offset(hit.getSide());
        if (!isTargetBlock(mc.world.getBlockState(placePos).getBlock())) return false;

        int foundSlot = -1;
        ItemStack foundBucket = ItemStack.EMPTY;
        Fluid foundFluid = Fluids.EMPTY;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(Items.WATER_BUCKET)) {
                foundSlot = i;
                foundBucket = stack.copy();
                foundFluid = Fluids.WATER;
                break;
            }
            if (foundSlot == -1 && stack.isOf(Items.LAVA_BUCKET)) {
                foundSlot = i;
                foundBucket = stack.copy();
                foundFluid = Fluids.LAVA;
            }
        }
        if (foundSlot == -1) {
            mc.player.sendMessage(Text.literal("§c[Error Killer] Put a water or lava bucket in your hotbar."), true);
            return false;
        }

        this.originalHit = hit;
        this.targetPos = placePos.toImmutable();
        this.blockSlot = mc.player.getInventory().selectedSlot;
        this.liquidSlot = foundSlot;
        this.liquidBucket = foundBucket;
        this.liquid = foundFluid;
        this.liquidPos = null;
        this.stageTicks = 0;
        this.lastPickupAttempt = Integer.MIN_VALUE / 2;
        this.fluidsBefore.clear();
        rememberExistingSources(mc, targetPos, hit.getBlockPos());

        mc.player.getInventory().selectedSlot = liquidSlot;
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        stage = Stage.WAIT_SOURCE;
        return true;
    }

    @Override
    public void onTick(MinecraftClient mc) {
        if (stage == Stage.IDLE) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            resetState();
            return;
        }
        stageTicks++;

        if (stage == Stage.WAIT_SOURCE) {
            liquidPos = findNewSource(mc, liquid, targetPos, originalHit.getBlockPos(), 4);
            if (liquidPos != null && mc.player.getInventory().getStack(liquidSlot).isOf(Items.BUCKET)) {
                // Wait for the bucket inventory prediction too; don't try to scoop before placement completed.
                BlockHitResult sourceHit = hitSource(liquidPos);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, sourceHit);
                lastPickupAttempt = mc.player.age;
                stage = Stage.WAIT_PICKUP;
                stageTicks = 0;
                return;
            }

            // If the bucket was not consumed, placement failed. If it was consumed, keep
            // searching for the source rather than placing the requested block over live liquid.
            if (stageTicks > 20 && (mc.player.getInventory().getStack(liquidSlot).isOf(Items.WATER_BUCKET)
                    || mc.player.getInventory().getStack(liquidSlot).isOf(Items.LAVA_BUCKET))) {
                mc.player.getInventory().selectedSlot = blockSlot;
                mc.player.sendMessage(Text.literal("§c[Error Killer] Could not place liquid; block placement cancelled."), true);
                resetState();
            }
            return;
        }

        if (stage == Stage.WAIT_PICKUP) {
            ItemStack current = mc.player.getInventory().getStack(liquidSlot);
            boolean bucketFilled = current.isOf(liquidBucket.getItem());
            boolean sourceGone = liquidPos == null || !isSource(mc, liquidPos, liquid);

            if (bucketFilled && sourceGone) {
                // Restore the original block slot and place only after the bucket has actually
                // been refilled and the source has disappeared from the client world.
                mc.player.getInventory().selectedSlot = blockSlot;
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, originalHit);
                resetState();
                return;
            }

            // Retry pickup occasionally if the source is still there. Never place the block
            // while the bucket is empty, which was the cause of water being left behind.
            if (current.isOf(Items.BUCKET) && liquidPos != null && isSource(mc, liquidPos, liquid)
                    && mc.player.age - lastPickupAttempt >= 6) {
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitSource(liquidPos));
                lastPickupAttempt = mc.player.age;
            }
        }
    }

    @Override
    protected void onDisable() {
        // Do not leave the module stuck in a half-finished state after it is toggled off.
        MinecraftClient mc = MinecraftClient.getInstance();
        if (stage != Stage.IDLE && mc.player != null && blockSlot >= 0) {
            mc.player.getInventory().selectedSlot = blockSlot;
        }
        resetState();
    }

    private void rememberExistingSources(MinecraftClient mc, BlockPos... centers) {
        for (BlockPos center : centers) {
            for (int x = -4; x <= 4; x++) {
                for (int y = -3; y <= 3; y++) {
                    for (int z = -4; z <= 4; z++) {
                        BlockPos pos = center.add(x, y, z);
                        if (isSource(mc, pos, Fluids.WATER) || isSource(mc, pos, Fluids.LAVA)) {
                            fluidsBefore.add(pos.toImmutable());
                        }
                    }
                }
            }
        }
    }

    private BlockPos findNewSource(MinecraftClient mc, Fluid fluid, BlockPos first, BlockPos second, int radius) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        Set<BlockPos> checked = new HashSet<>();
        for (BlockPos center : new BlockPos[]{first, second}) {
            for (int x = -radius; x <= radius; x++) {
                for (int y = -3; y <= 3; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        BlockPos pos = center.add(x, y, z).toImmutable();
                        if (!checked.add(pos) || fluidsBefore.contains(pos) || !isSource(mc, pos, fluid)) continue;
                        double distance = pos.getSquaredDistance(targetPos);
                        if (distance < bestDistance) {
                            best = pos;
                            bestDistance = distance;
                        }
                    }
                }
            }
        }
        return best;
    }

    private static boolean isSource(MinecraftClient mc, BlockPos pos, Fluid fluid) {
        if (mc.world == null) return false;
        var state = mc.world.getBlockState(pos).getFluidState();
        return state.isOf(fluid) && state.isStill();
    }

    private static BlockHitResult hitSource(BlockPos pos) {
        return new BlockHitResult(
                new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5),
                Direction.UP, pos, false);
    }

    private void resetState() {
        stage = Stage.IDLE;
        originalHit = null;
        targetPos = null;
        liquidPos = null;
        blockSlot = -1;
        liquidSlot = -1;
        liquidBucket = ItemStack.EMPTY;
        liquid = Fluids.EMPTY;
        fluidsBefore.clear();
        stageTicks = 0;
    }

    private static boolean isTargetBlock(Block block) {
        if (!targetResolved) {
            targetResolved = true;
            try {
                String name = FabricLoader.getInstance().getMappingResolver()
                        .mapClassName("intermediary", "net.minecraft.class_2401");
                targetClass = Class.forName(name);
            } catch (Throwable t) {
                OrangeClient.LOGGER.error("ErrorKiller: failed to resolve original target block class", t);
            }
        }

        // Keep the original target and also support fragile/redstone attachments that
        // water removes, such as buttons, levers, pressure plates, torches and tripwire.
        return (targetClass != null && targetClass.isInstance(block))
                || block instanceof AbstractButtonBlock
                || block instanceof LeverBlock
                || block instanceof AbstractPressurePlateBlock
                || block instanceof TorchBlock
                || block instanceof RedstoneWireBlock
                || block instanceof TripwireHookBlock
                || block instanceof TripwireBlock;
    }
}
