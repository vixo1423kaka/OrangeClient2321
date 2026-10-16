package com.example.orangeclient.module.combat;

import com.example.orangeclient.friend.Friends;
import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.module.movement.Freecam;
import com.example.orangeclient.setting.BoolSetting;
import com.example.orangeclient.setting.ModeSetting;
import com.example.orangeclient.setting.NumberSetting;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import java.util.ArrayList;
import java.util.List;

/** Automatically places cobwebs in a nearby player's feet space or immediately below them. */
public class AutoWeb extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 4.5, 1.0, 6.0, 0.1));
    private final ModeSetting placement = add(new ModeSetting("Placement", "Legs", "Legs", "Head", "Both"));
    private final NumberSetting delay = add(new NumberSetting("Delay", 1, 0, 10, 1));
    private final BoolSetting ignoreFriends = add(new BoolSetting("IgnoreFriends", true));
    private final BoolSetting throughWalls = add(new BoolSetting("ThroughWalls", false));
    private final BoolSetting switchBack = add(new BoolSetting("SwitchBack", true));
    private final BoolSetting noLever = add(new BoolSetting("NoLever", false));
    private BlockPos breakingObstacle;

    private int placeCooldown;

    public AutoWeb() {
        super("AutoWeb", Category.COMBAT);
    }

    @Override
    protected void onEnable() {
        placeCooldown = 0;
        breakingObstacle = null;
    }

    @Override
    public void onTick(MinecraftClient mc) {
        if (placeCooldown > 0) placeCooldown--;
        if (placeCooldown > 0 || mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (mc.currentScreen != null || Freecam.isActive() || mc.player.isSpectator()
                || !mc.player.isAlive() || mc.player.isUsingItem()) return;

        PlayerEntity player = mc.player;
        PlayerEntity target = findTarget(mc, player);
        if (target == null) return;

        PlayerInventory inv = player.getInventory();
        int webSlot = findCobwebSlot(inv);
        if (webSlot == -1) return;

        List<BlockPos> placePositions = findPlacePositions(mc, target.getBlockPos());
        if (placePositions.isEmpty()) return;

        // If a redstone control occupies the desired web position, mine it first (one block at a time).
        if (noLever.get()) {
            for (BlockPos pos : placePositions) {
                BlockState state = mc.world.getBlockState(pos);
                if (!state.isReplaceable() && isRedstoneControl(mc, pos)) {
                    breakingObstacle = pos.toImmutable();
                    mc.interactionManager.attackBlock(breakingObstacle, Direction.UP);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    placeCooldown = 1;
                    return;
                }
            }
            breakingObstacle = null;
        }

        double maxBlockReach = player.getBlockInteractionRange() + 0.3;
        double maxBlockReachSq = maxBlockReach * maxBlockReach;
        List<BlockHitResult> hits = new ArrayList<>();
        for (BlockPos placePos : placePositions) {
            BlockHitResult hit = findSupportHit(mc, player, placePos);
            if (hit == null) continue;
            if (player.getEyePos().squaredDistanceTo(hit.getPos()) > maxBlockReachSq) continue;
            hits.add(hit);
        }
        if (hits.isEmpty()) return;

        int oldSlot = inv.selectedSlot;
        inv.selectedSlot = webSlot;
        try {
            for (BlockHitResult hit : hits) {
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            }
        } finally {
            if (switchBack.get()) inv.selectedSlot = oldSlot;
        }
        placeCooldown = (int) delay.get();
    }

    private PlayerEntity findTarget(MinecraftClient mc, PlayerEntity self) {
        PlayerEntity best = null;
        double maxDistance = Math.min(range.get(), self.getBlockInteractionRange() + 0.3);
        double bestDistance = maxDistance;
        for (PlayerEntity candidate : mc.world.getPlayers()) {
            if (candidate == self || !candidate.isAlive() || candidate.isSpectator()) continue;
            if (ignoreFriends.get() && Friends.isFriend(candidate)) continue;
            if (!throughWalls.get() && !self.canSee(candidate)) continue;
            double distance = self.distanceTo(candidate);
            if (distance > bestDistance) continue;
            bestDistance = distance;
            best = candidate;
        }
        return best;
    }

    private int findCobwebSlot(PlayerInventory inv) {
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.COBWEB)) return i;
        }
        return -1;
    }

    private List<BlockPos> findPlacePositions(MinecraftClient mc, BlockPos targetPos) {
        // Player block position is the lower/feet block; the head space is one block above.
        BlockPos legs = targetPos;
        BlockPos head = targetPos.up();
        List<BlockPos> positions = new ArrayList<>(2);
        switch (placement.get()) {
            case "Legs" -> {
                if (canPlaceAt(mc, legs)) positions.add(legs);
            }
            case "Head" -> {
                if (canPlaceAt(mc, head)) positions.add(head);
            }
            case "Both" -> {
                if (canPlaceAt(mc, legs)) positions.add(legs);
                if (canPlaceAt(mc, head)) positions.add(head);
            }
        }
        return positions;
    }

    private boolean canPlaceAt(MinecraftClient mc, BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        return (state.isReplaceable() && !state.isOf(Blocks.COBWEB))
                || (noLever.get() && isRedstoneControl(mc, pos));
    }

    private boolean isRedstoneControl(MinecraftClient mc, BlockPos pos) {
        String path = Registries.BLOCK.getId(mc.world.getBlockState(pos).getBlock()).getPath();
        return path.equals("lever") || path.endsWith("_button") || path.equals("stone_button")
                || path.endsWith("_pressure_plate") || path.equals("heavy_weighted_pressure_plate")
                || path.equals("light_weighted_pressure_plate") || path.equals("tripwire_hook")
                || path.equals("redstone_torch") || path.equals("redstone_wall_torch");
    }

    /** Finds a neighboring solid block to click so vanilla placement fills placePos. */
    private BlockHitResult findSupportHit(MinecraftClient mc, PlayerEntity player, BlockPos placePos) {
        BlockHitResult best = null;
        double bestDistance = Double.MAX_VALUE;
        Vec3d eye = player.getEyePos();
        for (Direction face : Direction.values()) {
            BlockPos supportPos = placePos.offset(face.getOpposite());
            BlockState support = mc.world.getBlockState(supportPos);
            if (support.isReplaceable() || support.getCollisionShape(mc.world, supportPos).isEmpty()) continue;

            Vec3d hitPos = new Vec3d(
                    supportPos.getX() + 0.5 + face.getOffsetX() * 0.5,
                    supportPos.getY() + 0.5 + face.getOffsetY() * 0.5,
                    supportPos.getZ() + 0.5 + face.getOffsetZ() * 0.5);
            double distance = eye.squaredDistanceTo(hitPos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new BlockHitResult(hitPos, face, supportPos, false);
            }
        }
        return best;
    }
}
