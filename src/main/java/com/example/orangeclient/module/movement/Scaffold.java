package com.example.orangeclient.module.movement;

import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.setting.BoolSetting;
import com.example.orangeclient.setting.NumberSetting;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;

/** Client-side scaffold helper for Fabric 1.21.4. */
public final class Scaffold extends Module {
    private final NumberSetting delay = add(new NumberSetting("DelayTicks", 0, 0, 5, 1));
    private final BoolSetting edgeOnly = add(new BoolSetting("EdgeOnly", false));
    private final BoolSetting keepY = add(new BoolSetting("KeepY", false));
    private final BoolSetting rotate = add(new BoolSetting("Rotate", true));
    private final BoolSetting silentSwitch = add(new BoolSetting("SilentSwitch", true));
    private int nextPlaceTick;
    private int lockedY = Integer.MIN_VALUE;

    public Scaffold() { super("Scaffold", Category.MOVEMENT); }

    @Override protected void onEnable() {
        lockedY = Integer.MIN_VALUE;
        nextPlaceTick = 0;
    }

    @Override protected void onDisable() { lockedY = Integer.MIN_VALUE; }

    @Override public void onTick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.currentScreen != null) return;
        if (mc.player.isSneaking() || mc.player.age < nextPlaceTick) return;

        int baseY = MathHelper.floor(mc.player.getY() - 1.0);
        if (keepY.get()) {
            if (lockedY == Integer.MIN_VALUE) lockedY = baseY;
            baseY = lockedY;
        }

        // Aim slightly ahead while moving to avoid repeatedly targeting the block behind the player.
        double forward = mc.player.forwardSpeed;
        double sideways = mc.player.sidewaysSpeed;
        double yaw = Math.toRadians(mc.player.getYaw());
        double dx = (-Math.sin(yaw) * forward + Math.cos(yaw) * sideways) * 0.35;
        double dz = ( Math.cos(yaw) * forward + Math.sin(yaw) * sideways) * 0.35;
        BlockPos target = BlockPos.ofFloored(mc.player.getX() + dx, baseY, mc.player.getZ() + dz);
        if (!mc.world.getBlockState(target).isReplaceable()) {
            target = BlockPos.ofFloored(mc.player.getX(), baseY, mc.player.getZ());
            if (!mc.world.getBlockState(target).isReplaceable()) return;
        }
        if (edgeOnly.get() && mc.player.isOnGround()) {
            BlockPos underPlayer = BlockPos.ofFloored(mc.player.getX(), baseY, mc.player.getZ());
            if (!mc.world.getBlockState(underPlayer).isReplaceable()) return;
        }

        int blockSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() instanceof BlockItem item && !item.getBlock().getDefaultState().isReplaceable()) {
                blockSlot = i;
                break;
            }
        }
        if (blockSlot < 0) return;

        int oldSlot = mc.player.getInventory().selectedSlot;
        // Changing selectedSlot locally does not notify the server. Send the hotbar packet
        // before placing, otherwise the server may try to place the old held item.
        mc.player.getInventory().selectedSlot = blockSlot;
        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(blockSlot));
        try {
            BlockHitResult hit = findSupport(mc, target);
            if (hit == null) return;
            if (rotate.get()) {
                Vec3d eye = mc.player.getEyePos();
                Vec3d delta = hit.getPos().subtract(eye);
                double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
                mc.player.setYaw((float)(Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0));
                mc.player.setPitch((float)-Math.toDegrees(Math.atan2(delta.y, horizontal)));
            }
            ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            if (result.isAccepted()) {
                mc.player.swingHand(Hand.MAIN_HAND);
                nextPlaceTick = mc.player.age + (int) delay.get();
            }
        } finally {
            mc.player.getInventory().selectedSlot = oldSlot;
            if (silentSwitch.get()) {
                mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(oldSlot));
            }
        }
    }

    private BlockHitResult findSupport(MinecraftClient mc, BlockPos target) {
        // Prefer directly adjacent support blocks, then diagonals. This handles edges/corners too.
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        for (Direction side : order) {
            BlockPos neighbor = target.offset(side);
            BlockState state = mc.world.getBlockState(neighbor);
            if (!state.isSolidBlock(mc.world, neighbor) || !state.getFluidState().isEmpty()) continue;
            Direction face = side.getOpposite();
            Vec3d hit = Vec3d.ofCenter(neighbor).add(Vec3d.of(face.getVector()).multiply(0.499));
            return new BlockHitResult(hit, face, neighbor, false);
        }
        for (Direction horizontal : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            for (Direction other : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
                if (horizontal == other || horizontal == other.getOpposite()) continue;
                BlockPos neighbor = target.offset(horizontal).offset(other);
                BlockState state = mc.world.getBlockState(neighbor);
                if (!state.isSolidBlock(mc.world, neighbor) || !state.getFluidState().isEmpty()) continue;
                Direction face = horizontal.getOpposite();
                Vec3d hit = Vec3d.ofCenter(neighbor).add(Vec3d.of(face.getVector()).multiply(0.499));
                return new BlockHitResult(hit, face, neighbor, false);
            }
        }
        return null;
    }
}
