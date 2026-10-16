package com.example.orangeclient.module.combat;

import com.example.orangeclient.friend.Friends;
import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.setting.BoolSetting;
import com.example.orangeclient.setting.NumberSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

/** Tracks incoming pearls and gives a lightweight trajectory/landing hint; does not auto-throw. */
public final class PearlChaser extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 24, 4, 64, 1));
    private final BoolSetting ignoreFriends = add(new BoolSetting("IgnoreFriends", true));
    private final BoolSetting autoThrow = add(new BoolSetting("AutoThrow", false));
    private int cooldown;
    public PearlChaser() { super("PearlChaser", Category.COMBAT); }
    @Override public void onTick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || mc.currentScreen != null) return;
        if (cooldown > 0) cooldown--;
        EnderPearlEntity nearest = null; double best = range.get()*range.get();
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EnderPearlEntity pearl) || pearl.getOwner() == mc.player) continue;
            double d = mc.player.squaredDistanceTo(pearl); if (d < best) { best=d; nearest=pearl; }
        }
        if (nearest == null) return;
        // Only act when a pearl is plausibly travelling toward the player.
        Vec3d toPlayer = mc.player.getPos().subtract(nearest.getPos());
        if (nearest.getVelocity().dotProduct(toPlayer) <= 0) return;
        if (autoThrow.get() && cooldown == 0 && mc.player.getHealth() > 6) {
            int slot=-1; for(int i=0;i<9;i++) if(mc.player.getInventory().getStack(i).getItem()==Items.ENDER_PEARL){slot=i;break;}
            if(slot>=0 && mc.player.isOnGround()) { int old=mc.player.getInventory().selectedSlot; mc.player.getInventory().selectedSlot=slot; mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND); mc.player.getInventory().selectedSlot=old; cooldown=20; }
        }
    }
}
