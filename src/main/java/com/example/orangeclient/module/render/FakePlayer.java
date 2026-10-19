package com.example.orangeclient.module.render;

import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.setting.StringSetting;
import com.example.orangeclient.setting.BoolSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import com.mojang.authlib.GameProfile;

import java.util.UUID;

/** Local-only player preview. It is never sent to the server. */
public class FakePlayer extends Module {
    private final StringSetting cloneName = add(new StringSetting("Name", "OrangeDummy", 16));
    private final BoolSetting copyInventory = add(new BoolSetting("CopyInventory", true));
    private final BoolSetting copyEffects = add(new BoolSetting("CopyEffects", true));
    private OtherClientPlayerEntity clone;
    private double anchorY;

    public FakePlayer() { super("FakePlayer", Category.RENDER); }

    @Override protected void onEnable() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        removeClone();
        PlayerEntity source = mc.player;
        String name = cloneName.get().isBlank() ? "OrangeDummy" : cloneName.get();
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        // Preserve the active player's texture properties so the clone uses the same skin.
        profile.getProperties().putAll(source.getGameProfile().getProperties());
        clone = new OtherClientPlayerEntity((ClientWorld) mc.world, profile);
        Vec3d look = source.getRotationVec(1.0f);
        double x = source.getX() + look.x * 1.4;
        double z = source.getZ() + look.z * 1.4;
        // Keep the clone at the source's feet height, not eye/lerped height.
        // Place feet exactly on the surface at the clone's X/Z, even when it stands one block ahead.
        int groundY = mc.world.getTopY(Heightmap.Type.MOTION_BLOCKING, net.minecraft.util.math.MathHelper.floor(x), net.minecraft.util.math.MathHelper.floor(z));
        double y = Math.max(source.getY(), groundY);
        anchorY = y;
        clone.setId(-1000);
        clone.refreshPositionAndAngles(x, y, z, source.getYaw(), source.getPitch());
        clone.setBodyYaw(source.getBodyYaw());
        clone.setHeadYaw(source.getHeadYaw());
        clone.setOnGround(true);
        clone.setNoGravity(true);
        clone.noClip = true;
        for (EquipmentSlot slot : EquipmentSlot.values()) clone.equipStack(slot, source.getEquippedStack(slot).copy());
        if (copyInventory.get()) {
            for (int i = 0; i < source.getInventory().size(); i++) {
                clone.getInventory().setStack(i, source.getInventory().getStack(i).copy());
            }
        }
        if (copyEffects.get()) {
            source.getStatusEffects().forEach(effect -> clone.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(effect)));
        }
        mc.world.addEntity(clone);
    }

    @Override public void onTick(MinecraftClient mc) {
        if (clone == null || mc.player == null || mc.world == null) return;
        if (clone.isRemoved()) { setEnabled(false); return; }
        // Lock feet to the ground level chosen at creation and keep this client-only dummy stationary.
        clone.setNoGravity(true);
        clone.noClip = true;
        // Keep the original feet height. Recomputing the heightmap every tick makes the dummy
        // jump to the top of any newly placed block above it.
        clone.setPosition(clone.getX(), anchorY, clone.getZ());
        clone.setOnGround(true);
        clone.setVelocity(Vec3d.ZERO);
    }

    /** True only for the exact local clone owned by this module. */
    public static boolean isLocalFake(Entity entity) {
        FakePlayer module = com.example.orangeclient.module.ModuleManager.get(FakePlayer.class);
        return module != null && module.isEnabled() && module.clone != null && entity == module.clone;
    }

    /** Applies a local-only hit reaction; the interaction mixin cancels the network attack. */
    public static void hitLocally(PlayerEntity attacker) {
        FakePlayer module = com.example.orangeclient.module.ModuleManager.get(FakePlayer.class);
        if (module == null || module.clone == null || attacker == null) return;
        module.clone.handleStatus((byte) 2);
        Vec3d push = module.clone.getPos().subtract(attacker.getPos());
        if (push.lengthSquared() > 1.0E-5) {
            push = push.normalize().multiply(0.32);
            module.clone.addVelocity(push.x, 0.12, push.z);
        }
    }

    @Override protected void onDisable() { removeClone(); }

    private void removeClone() {
        if (clone != null) { clone.discard(); clone = null; }
        anchorY = 0.0;
    }
}
