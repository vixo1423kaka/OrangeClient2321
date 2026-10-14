package com.example.orangeclient.module.combat;

import com.example.orangeclient.friend.Friends;
import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.module.movement.Freecam;
import com.example.orangeclient.setting.BoolSetting;
import com.example.orangeclient.setting.NumberSetting;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Places a lever into a nearby water source around another player's position. */
public final class FastLever extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 4.5, 1.0, 6.0, 0.1));
    private final NumberSetting delay = add(new NumberSetting("Delay", 2, 0, 20, 1));
    private final BoolSetting throughWalls = add(new BoolSetting("ThroughWalls", false));
    private final BoolSetting ignoreFriends = add(new BoolSetting("IgnoreFriends", true));
    private final BoolSetting switchBack = add(new BoolSetting("SwitchBack", true));
    private int cooldown;

    public FastLever() { super("FastLever", Category.COMBAT); }

    @Override
    protected void onEnable() { cooldown = 0; }

    @Override
    public void onTick(MinecraftClient mc) {
        if (cooldown > 0) cooldown--;
        if (cooldown > 0 || mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (mc.currentScreen != null || Freecam.isActive() || mc.player.isSpectator()
                || !mc.player.isAlive() || mc.player.isUsingItem()) return;

        ClientPlayerEntity self = mc.player;
        PlayerEntity target = findTarget(mc, self);
        if (target == null) return;

        PlayerInventory inv = self.getInventory();
        // Prefer a lever in the offhand; do not change the selected hotbar slot in that case.
        boolean offhandLever = self.getOffHandStack().isOf(Items.LEVER);
        int leverSlot = offhandLever ? -1 : findLeverSlot(inv);
        if (!offhandLever && leverSlot < 0) return;

        BlockPos waterPos = findNearbyWater(mc, target.getBlockPos(), self);
        if (waterPos == null) return;
        BlockHitResult hit = findSupportHit(mc, self, waterPos);
        if (hit == null) return;
        double reach = self.getBlockInteractionRange() + 0.3;
        if (self.getEyePos().squaredDistanceTo(hit.getPos()) > reach * reach) return;

        int oldSlot = inv.selectedSlot;
        if (!offhandLever) inv.selectedSlot = leverSlot;
        try {
            mc.interactionManager.interactBlock(self, offhandLever ? Hand.OFF_HAND : Hand.MAIN_HAND, hit);
        } finally {
            if (!offhandLever && switchBack.get()) inv.selectedSlot = oldSlot;
        }
        cooldown = (int) delay.get();
    }

    private PlayerEntity findTarget(MinecraftClient mc, PlayerEntity self) {
        PlayerEntity best = null;
        double bestDistance = Math.min(range.get(), self.getBlockInteractionRange() + 0.3);
        for (PlayerEntity candidate : mc.world.getPlayers()) {
            if (candidate.getUuid().equals(self.getUuid()) || !candidate.isAlive() || candidate.isSpectator()) continue;
            if (ignoreFriends.get() && Friends.isFriend(candidate)) continue;
            if (!throughWalls.get() && !self.canSee(candidate)) continue;
            double distance = self.distanceTo(candidate);
            if (distance <= bestDistance) { bestDistance = distance; best = candidate; }
        }
        return best;
    }

    private int findLeverSlot(PlayerInventory inv) {
        for (int i = 0; i < 9; i++) if (inv.getStack(i).isOf(Items.LEVER)) return i;
        return -1;
    }

    /** Finds a source-water block within a small area around the target's feet/head. */
    private BlockPos findNearbyWater(MinecraftClient mc, BlockPos center, PlayerEntity self) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            BlockPos pos = center.add(dx, dy, dz);
            if (!mc.world.getBlockState(pos).isOf(Blocks.WATER)) continue;
            if (!mc.world.getBlockState(pos).getFluidState().isStill()) continue;
            if (!mc.world.getBlockState(pos).isReplaceable()) continue;
            BlockHitResult support = findSupportHit(mc, self, pos);
            if (support == null) continue;
            double d = self.getEyePos().squaredDistanceTo(support.getPos());
            double reach = self.getBlockInteractionRange() + 0.3;
            if (d > reach * reach || d >= bestDistance) continue;
            bestDistance = d;
            best = pos.toImmutable();
        }
        return best;
    }

    /** Mirrors AutoWeb's through-wall placement strategy: synthesize a hit on a valid adjacent support block. */
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
