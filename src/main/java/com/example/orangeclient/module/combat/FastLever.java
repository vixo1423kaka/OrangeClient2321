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
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Wklada dzwignie w zrodla wody wokol gracza. Dzwignia nie da sie "waterlogowac",
 * wiec zrodlo wody ginie - dobra kontra na wode / AutoWeb.
 * Wklada wszystkie znalezione zrodla w jednym cyklu i probuje kolejnych scian podpory.
 */
public class FastLever extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 4.5, 1.0, 6.0, 0.1));
    private final NumberSetting radius = add(new NumberSetting("Radius", 1, 0, 2, 1));
    private final NumberSetting delay = add(new NumberSetting("Delay", 0, 0, 20, 1));
    private final BoolSetting ignoreFriends = add(new BoolSetting("IgnoreFriends", true));
    private final BoolSetting throughWalls = add(new BoolSetting("ThroughWalls", false));
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

        PlayerEntity self = mc.player;
        PlayerEntity target = findTarget(mc, self);
        if (target == null) return;

        PlayerInventory inv = self.getInventory();
        boolean offhand = self.getOffHandStack().isOf(Items.LEVER);
        int leverSlot = offhand ? -1 : findLeverSlot(inv);
        if (!offhand && leverSlot < 0) return;

        double reach = self.getBlockInteractionRange() + 0.3;
        double reachSq = reach * reach;

        List<BlockPos> waters = findWater(mc, self, target.getBlockPos());
        if (waters.isEmpty()) return;

        int oldSlot = inv.selectedSlot;
        if (!offhand) inv.selectedSlot = leverSlot;
        try {
            Hand hand = offhand ? Hand.OFF_HAND : Hand.MAIN_HAND;
            for (BlockPos water : waters) {
                for (BlockHitResult hit : findSupportHits(mc, self, water)) {
                    if (self.getEyePos().squaredDistanceTo(hit.getPos()) > reachSq) continue;
                    if (mc.interactionManager.interactBlock(self, hand, hit).isAccepted()) break;
                }
            }
        } finally {
            if (!offhand && switchBack.get()) inv.selectedSlot = oldSlot;
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
            double distance = self.distanceTo(candidate);
            if (distance > bestDistance) continue;
            bestDistance = distance;
            best = candidate;
        }
        return best;
    }

    private int findLeverSlot(PlayerInventory inv) {
        for (int i = 0; i < 9; i++) if (inv.getStack(i).isOf(Items.LEVER)) return i;
        return -1;
    }

    /** Zrodla wody wokol gracza, od najblizszego, tylko takie do ktorych da sie przyczepic dzwignie. */
    private List<BlockPos> findWater(MinecraftClient mc, PlayerEntity self, BlockPos center) {
        int r = (int) radius.get();
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -1; dy <= r + 1; dy++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    if (!isWaterSource(mc, pos)) continue;
                    if (findSupportHits(mc, self, pos).isEmpty()) continue;
                    out.add(pos.toImmutable());
                }
            }
        }
        Vec3d eye = self.getEyePos();
        out.sort(Comparator.comparingDouble((BlockPos p) -> eye.squaredDistanceTo(Vec3d.ofCenter(p))));
        return out;
    }

    private boolean isWaterSource(MinecraftClient mc, BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        return state.isOf(Blocks.WATER) && state.getFluidState().isStill() && state.isReplaceable();
    }

    /** Wszystkie mozliwe sciany podpory; najpierw pelne boki (dzwignia wymaga pelnego boku). */
    private List<BlockHitResult> findSupportHits(MinecraftClient mc, PlayerEntity player, BlockPos placePos) {
        List<BlockHitResult> full = new ArrayList<>();
        List<BlockHitResult> rest = new ArrayList<>();
        Vec3d eye = player.getEyePos();
        for (Direction face : Direction.values()) {
            BlockPos supportPos = placePos.offset(face.getOpposite());
            BlockState support = mc.world.getBlockState(supportPos);
            if (support.isReplaceable() || support.getCollisionShape(mc.world, supportPos).isEmpty()) continue;

            Vec3d hitPos = new Vec3d(
                    supportPos.getX() + 0.5 + face.getOffsetX() * 0.5,
                    supportPos.getY() + 0.5 + face.getOffsetY() * 0.5,
                    supportPos.getZ() + 0.5 + face.getOffsetZ() * 0.5);
            BlockHitResult hit = new BlockHitResult(hitPos, face, supportPos, false);
            if (support.isSideSolidFullSquare(mc.world, supportPos, face)) full.add(hit);
            else rest.add(hit);
        }
        full.sort(Comparator.comparingDouble((BlockHitResult h) -> eye.squaredDistanceTo(h.getPos())));
        rest.sort(Comparator.comparingDouble((BlockHitResult h) -> eye.squaredDistanceTo(h.getPos())));
        full.addAll(rest);
        return full;
    }
}
