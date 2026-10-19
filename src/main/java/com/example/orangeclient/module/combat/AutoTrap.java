package com.example.orangeclient.module.combat;

import com.example.orangeclient.friend.Friends;
import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.module.movement.Freecam;
import com.example.orangeclient.setting.BoolSetting;
import com.example.orangeclient.setting.ModeSetting;
import com.example.orangeclient.setting.NumberSetting;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Otacza gracza blokami (surround). Dziala jak AutoWeb: sam szuka klikalnej podpory,
 * pilnuje zasiegu (blockInteractionRange) i stawia wszystkie mozliwe pozycje w jednym cyklu.
 */
public class AutoTrap extends Module {
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    private final NumberSetting range = add(new NumberSetting("Range", 4.5, 1.0, 6.0, 0.1));
    private final NumberSetting delay = add(new NumberSetting("Delay", 1, 0, 10, 1));
    private final ModeSetting mode = add(new ModeSetting("Mode", "Both", "Feet", "Head", "Both"));
    private final BoolSetting aboveHead = add(new BoolSetting("AboveHead", false));
    private final BoolSetting movingOnly = add(new BoolSetting("MovingTargetsOnly", false));
    private final BoolSetting ignoreFriends = add(new BoolSetting("IgnoreFriends", true));
    private final BoolSetting throughWalls = add(new BoolSetting("ThroughWalls", false));
    private final BoolSetting switchBack = add(new BoolSetting("SwitchBack", true));

    private int cooldown;

    public AutoTrap() { super("AutoTrap", Category.COMBAT); }

    @Override
    protected void onEnable() { cooldown = 0; }

    @Override
    public void onTick(MinecraftClient mc) {
        if (cooldown > 0) cooldown--;
        if (cooldown > 0 || mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (mc.currentScreen != null || Freecam.isActive() || mc.player.isSpectator()
                || !mc.player.isAlive() || mc.player.isUsingItem()) return;

        PlayerEntity self = mc.player;
        PlayerEntity target = findTarget(mc, self);
        if (target == null) return;

        PlayerInventory inv = self.getInventory();
        int blockSlot = findBlockSlot(mc, inv);
        if (blockSlot < 0) return;

        double reach = self.getBlockInteractionRange() + 0.3;
        double reachSq = reach * reach;

        List<BlockHitResult> hits = new ArrayList<>();
        for (BlockPos pos : findPlacePositions(mc, target.getBlockPos())) {
            BlockHitResult hit = findSupportHit(mc, self, pos);
            if (hit == null) continue;
            if (self.getEyePos().squaredDistanceTo(hit.getPos()) > reachSq) continue;
            hits.add(hit);
        }
        if (hits.isEmpty()) return;

        int oldSlot = inv.selectedSlot;
        inv.selectedSlot = blockSlot;
        try {
            for (BlockHitResult hit : hits) {
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            }
        } finally {
            if (switchBack.get()) inv.selectedSlot = oldSlot;
        }
        cooldown = (int) delay.get();
    }

    private PlayerEntity findTarget(MinecraftClient mc, PlayerEntity self) {
        PlayerEntity best = null;
        double bestDistance = Math.min(range.get(), self.getBlockInteractionRange() + 0.3);
        for (PlayerEntity candidate : mc.world.getPlayers()) {
            if (candidate == self || !candidate.isAlive() || candidate.isSpectator()) continue;
            if (ignoreFriends.get() && Friends.isFriend(candidate)) continue;
            if (!throughWalls.get() && !self.canSee(candidate)) continue;
            if (movingOnly.get() && candidate.getVelocity().horizontalLengthSquared() < 0.0025) continue;
            double distance = self.distanceTo(candidate);
            if (distance > bestDistance) continue;
            bestDistance = distance;
            best = candidate;
        }
        return best;
    }

    private List<BlockPos> findPlacePositions(MinecraftClient mc, BlockPos targetPos) {
        List<BlockPos> out = new ArrayList<>();
        String m = mode.get();
        if (!m.equals("Head")) addSurround(mc, targetPos, out);
        if (!m.equals("Feet")) addSurround(mc, targetPos.up(), out);
        if (aboveHead.get()) {
            BlockPos p = targetPos.up(2);
            if (canPlaceAt(mc, p)) out.add(p);
        }
        return out;
    }

    private void addSurround(MinecraftClient mc, BlockPos center, List<BlockPos> out) {
        for (Direction d : HORIZONTAL) {
            BlockPos pos = center.offset(d);
            if (canPlaceAt(mc, pos)) out.add(pos);
        }
    }

    private boolean canPlaceAt(MinecraftClient mc, BlockPos pos) {
        return mc.world.getBlockState(pos).isReplaceable();
    }

    /** Podpiera sie na najblizszym bloku z kolizja (slabs/stairs tez dzialaja). */
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

    /** Najpierw pelny blok (np. cobble), a jak nie ma - cokolwiek stawialnego. */
    private int findBlockSlot(MinecraftClient mc, PlayerInventory inv) {
        int fallback = -1;
        BlockPos anchor = mc.player.getBlockPos();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (!(s.getItem() instanceof BlockItem item)) continue;
            if (fallback < 0) fallback = i;
            if (item.getBlock().getDefaultState().isFullCube(mc.world, anchor)) return i;
        }
        return fallback;
    }
}
