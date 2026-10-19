package com.example.orangeclient.mixin;

import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(HeldItemRenderer.class)
public interface HeldItemRendererAccessor {
    @Accessor("equipProgressMainHand") void orange$setEquipProgressMainHand(float v);
    @Accessor("prevEquipProgressMainHand") void orange$setPrevEquipProgressMainHand(float v);
    @Accessor("equipProgressOffHand") void orange$setEquipProgressOffHand(float v);
    @Accessor("prevEquipProgressOffHand") void orange$setPrevEquipProgressOffHand(float v);
    @Accessor("mainHand") void orange$setMainHand(ItemStack stack);
    @Accessor("offHand") void orange$setOffHand(ItemStack stack);
}
