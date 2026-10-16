package com.example.orangeclient.module.combat;

import com.example.orangeclient.friend.Friends;
import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.setting.NumberSetting;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Places blocks around a nearby player using blocks from the hotbar. */
public final class AutoTrap extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 4.0, 1.0, 6.0, 0.1));
    private final NumberSetting delay = add(new NumberSetting("Delay", 2, 0, 10, 1));
    private final com.example.orangeclient.setting.BoolSetting movingTargets = add(new com.example.orangeclient.setting.BoolSetting("MovingTargetsOnly", false));
    private int cooldown;
    public AutoTrap() { super("AutoTrap", Category.COMBAT); }
    @Override public void onTick(MinecraftClient mc) {
        if (cooldown > 0) cooldown--;
        if (cooldown > 0 || mc.player == null || mc.world == null || mc.interactionManager == null || mc.currentScreen != null) return;
        PlayerEntity self = mc.player, target = null; double best = range.get();
        for (PlayerEntity e : mc.world.getPlayers()) {
            if (e == self || !e.isAlive() || e.isSpectator() || Friends.isFriend(e)) continue;
            if (movingTargets.get() && e.getVelocity().horizontalLengthSquared() < 0.0025) continue;
            double d = self.distanceTo(e); if (d < best) { best = d; target = e; }
        }
        if (target == null) return;
        int slot = -1;
        for (int i = 0; i < 9; i++) if (self.getInventory().getStack(i).getItem() instanceof BlockItem) { slot = i; break; }
        if (slot < 0) return;
        BlockPos base = target.getBlockPos();
        BlockPos[] candidates = {base.up(), base.up(2), base.north(), base.south(), base.east(), base.west(), base.north().up(), base.south().up(), base.east().up(), base.west().up()};
        int old = self.getInventory().selectedSlot; self.getInventory().selectedSlot = slot;
        try {
            for (BlockPos pos : candidates) {
                if (!mc.world.getBlockState(pos).isReplaceable()) continue;
                for (Direction side : Direction.values()) {
                    BlockPos neighbor = pos.offset(side); BlockState state = mc.world.getBlockState(neighbor);
                    if (state.isAir() || !state.getFluidState().isEmpty() || !state.isSolidBlock(mc.world, neighbor)) continue;
                    Direction face = side.getOpposite();
                    Vec3d hit = Vec3d.ofCenter(neighbor).add(Vec3d.of(face.getVector()).multiply(0.5));
                    var result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(hit, face, neighbor, false));
                    if (result.isAccepted()) { self.swingHand(Hand.MAIN_HAND); cooldown = (int) delay.get(); return; }
                }
            }
        } finally { self.getInventory().selectedSlot = old; }
    }
}
