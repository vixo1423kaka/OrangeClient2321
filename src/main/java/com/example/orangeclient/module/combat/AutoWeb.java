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
import java.util.Comparator;
import java.util.List;

/**
 * Stawia pajeczyny w gracza (stopy / glowa).
 *
 * Przez sciany: swiat nie raycastuje, tylko sprawdza odleglosc do srodka kliknietego bloku
 * z tolerancja blockInteractionRange + 1.0. Dlatego:
 *   - zasieg liczymy do SRODKA podpory (a nie do powierzchni), z ta sama tolerancja,
 *   - gdy proba sie nie uda (serwer odrzucil), kolejna proba bierze INNA podpore,
 *   - po kazdej probie sprawdzamy czy web faktycznie wstal (Verify).
 */
public class AutoWeb extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 4.5, 1.0, 6.0, 0.1));
    private final ModeSetting placement = add(new ModeSetting("Placement", "Legs", "Legs", "Head", "Both"));
    private final NumberSetting delay = add(new NumberSetting("Delay", 1, 0, 10, 1));
    private final BoolSetting ignoreFriends = add(new BoolSetting("IgnoreFriends", true));
    private final BoolSetting throughWalls = add(new BoolSetting("ThroughWalls", true));
    private final BoolSetting switchBack = add(new BoolSetting("SwitchBack", true));
    private final BoolSetting noLever = add(new BoolSetting("NoLever", false));
    private final BoolSetting verify = add(new BoolSetting("Verify", true));
    private final BoolSetting wide = add(new BoolSetting("Wide", false));

    private final List<BlockPos> pending = new ArrayList<>();
    private int pendingTicks;
    private int supportIndex;
    private int placeCooldown;
    private BlockPos breakingObstacle;

    public AutoWeb() {
        super("AutoWeb", Category.COMBAT);
    }

    @Override
    protected void onEnable() {
        placeCooldown = 0;
        breakingObstacle = null;
        pending.clear();
        supportIndex = 0;
    }

    @Override
    public void onTick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        // Weryfikacja poprzedniej proby: jesli web nie wstal, nastepnym razem inna podpora.
        if (!pending.isEmpty() && --pendingTicks <= 0) {
            boolean failed = false;
            for (BlockPos p : pending) {
                if (!mc.world.getBlockState(p).isOf(Blocks.COBWEB)) {
                    failed = true;
                    break;
                }
            }
            if (failed && verify.get()) supportIndex++;
            pending.clear();
        }

        if (placeCooldown > 0) placeCooldown--;
        if (placeCooldown > 0 || !pending.isEmpty()) return;
        if (mc.currentScreen != null || Freecam.isActive() || mc.player.isSpectator()
                || !mc.player.isAlive() || mc.player.isUsingItem()) return;

        PlayerEntity player = mc.player;
        List<PlayerEntity> targets = findTargets(mc, player);
        if (targets.isEmpty()) return;

        PlayerInventory inv = player.getInventory();
        int webSlot = findCobwebSlot(inv);
        if (webSlot == -1) return;

        double extra = throughWalls.get() ? 1.0 : 0.3;
        double reach = player.getBlockInteractionRange() + extra;
        double reachSq = reach * reach;

        for (PlayerEntity target : targets) {
            List<BlockPos> positions = findPlacePositions(mc, target.getBlockPos());
            if (positions.isEmpty()) continue;

            if (noLever.get()) {
                BlockPos obstacle = findObstacle(mc, positions);
                if (obstacle != null) {
                    breakingObstacle = obstacle;
                    mc.interactionManager.attackBlock(obstacle, Direction.UP);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    placeCooldown = 1;
                    return;
                }
            }
            breakingObstacle = null;

            List<BlockHitResult> hits = new ArrayList<>();
            for (BlockPos pos : positions) {
                BlockHitResult hit = pickSupport(mc, player, pos, reachSq);
                if (hit != null) hits.add(hit);
            }
            if (hits.isEmpty()) continue;

            int oldSlot = inv.selectedSlot;
            inv.selectedSlot = webSlot;
            try {
                for (BlockHitResult hit : hits) {
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                }
            } finally {
                if (switchBack.get()) inv.selectedSlot = oldSlot;
            }

            if (verify.get()) {
                pending.clear();
                for (BlockHitResult hit : hits) {
                    pending.add(hit.getBlockPos().offset(hit.getSide()).toImmutable());
                }
                pendingTicks = 3;
            }
            placeCooldown = (int) delay.get();
            return;
        }
    }

    /** Cele po odleglosci - jeden nieosiagalny gracz nie blokuje reszty. */
    private List<PlayerEntity> findTargets(MinecraftClient mc, PlayerEntity self) {
        double extra = throughWalls.get() ? 1.0 : 0.3;
        double maxDistance = Math.min(range.get(), self.getBlockInteractionRange() + extra);
        List<PlayerEntity> out = new ArrayList<>();
        for (PlayerEntity candidate : mc.world.getPlayers()) {
            if (candidate == self || !candidate.isAlive() || candidate.isSpectator()) continue;
            if (ignoreFriends.get() && Friends.isFriend(candidate)) continue;
            if (!throughWalls.get() && !self.canSee(candidate)) continue;
            if (self.distanceTo(candidate) > maxDistance) continue;
            out.add(candidate);
        }
        out.sort(Comparator.comparingDouble((PlayerEntity p) -> self.squaredDistanceTo(p)));
        return out;
    }

    private int findCobwebSlot(PlayerInventory inv) {
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.COBWEB)) return i;
        }
        return -1;
    }

    private List<BlockPos> findPlacePositions(MinecraftClient mc, BlockPos targetPos) {
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
            default -> {
                if (canPlaceAt(mc, legs)) positions.add(legs);
                if (canPlaceAt(mc, head)) positions.add(head);
            }
        }
        if (wide.get()) {
            for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
                BlockPos p1 = legs.offset(d);
                BlockPos p2 = head.offset(d);
                if (canPlaceAt(mc, p1)) positions.add(p1);
                if (canPlaceAt(mc, p2)) positions.add(p2);
            }
        }
        return positions;
    }

    private boolean canPlaceAt(MinecraftClient mc, BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        return (state.isReplaceable() && !state.isOf(Blocks.COBWEB))
                || (noLever.get() && isRedstoneControl(mc, pos));
    }

    private BlockPos findObstacle(MinecraftClient mc, List<BlockPos> positions) {
        for (BlockPos pos : positions) {
            BlockState state = mc.world.getBlockState(pos);
            if (!state.isReplaceable() && isRedstoneControl(mc, pos)) return pos.toImmutable();
        }
        return null;
    }

    private boolean isRedstoneControl(MinecraftClient mc, BlockPos pos) {
        String path = Registries.BLOCK.getId(mc.world.getBlockState(pos).getBlock()).getPath();
        return path.equals("lever") || path.endsWith("_button") || path.equals("stone_button")
                || path.endsWith("_pressure_plate") || path.equals("heavy_weighted_pressure_plate")
                || path.equals("light_weighted_pressure_plate") || path.equals("tripwire_hook")
                || path.equals("redstone_torch") || path.equals("redstone_wall_torch");
    }

    /** Podpora rotowana przy kolejnych probach, zeby zla podpora nie zapetlala modulu. */
    private BlockHitResult pickSupport(MinecraftClient mc, PlayerEntity player, BlockPos placePos, double reachSq) {
        List<BlockHitResult> supports = findSupportHits(mc, player, placePos, reachSq);
        if (supports.isEmpty()) return null;
        return supports.get(supportIndex % supports.size());
    }

    /**
     * Podpory dla placePos: najpierw pelne boki (isSideSolidFullSquare), potem reszta, po odleglosci.
     * Zasieg liczony do SRODKA podpory - dokladnie tak jak waliduje to swiat.
     */
    private List<BlockHitResult> findSupportHits(MinecraftClient mc, PlayerEntity player, BlockPos placePos, double reachSq) {
        List<BlockHitResult> full = new ArrayList<>();
        List<BlockHitResult> rest = new ArrayList<>();
        Vec3d eye = player.getEyePos();
        for (Direction face : Direction.values()) {
            BlockPos supportPos = placePos.offset(face.getOpposite());
            BlockState support = mc.world.getBlockState(supportPos);
            if (support.isReplaceable() || support.getCollisionShape(mc.world, supportPos).isEmpty()) continue;
            if (eye.squaredDistanceTo(Vec3d.ofCenter(supportPos)) > reachSq) continue;

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