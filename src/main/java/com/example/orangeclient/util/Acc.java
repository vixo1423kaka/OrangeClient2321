package com.example.orangeclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection-based replacement for the Mixin {@code @Accessor}/{@code @Invoker} interfaces.
 * Works both when the client is loaded as a regular Fabric mod and when it is injected into an
 * already running game (live attach), where the Accessor mixins are never applied and casting
 * to them throws ClassCastException.
 *
 * Each member is looked up by its runtime (intermediary) name first and by its yarn name second
 * (dev environment).
 */
public final class Acc {
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();

    private Acc() { }

    private static Field field(Class<?> owner, String intermediary, String named) {
        return FIELDS.computeIfAbsent(owner.getName() + "#" + intermediary, k -> {
            for (String n : new String[] { intermediary, named }) {
                try {
                    Field f = owner.getDeclaredField(n);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) { }
            }
            throw new IllegalStateException("Field not found: " + owner.getName() + "." + named + " / " + intermediary);
        });
    }

    private static Method method(Class<?> owner, String intermediary, String named) {
        return METHODS.computeIfAbsent(owner.getName() + "#" + intermediary, k -> {
            for (String n : new String[] { intermediary, named }) {
                try {
                    Method m = owner.getDeclaredMethod(n);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) { }
            }
            throw new IllegalStateException("Method not found: " + owner.getName() + "." + named + " / " + intermediary);
        });
    }

    private static int getInt(Field f, Object o) {
        try { return f.getInt(o); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
    private static void setInt(Field f, Object o, int v) {
        try { f.setInt(o, v); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
    private static float getFloat(Field f, Object o) {
        try { return f.getFloat(o); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
    private static void setFloat(Field f, Object o, float v) {
        try { f.setFloat(o, v); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
    private static boolean getBool(Field f, Object o) {
        try { return f.getBoolean(o); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
    private static void setObj(Field f, Object o, Object v) {
        try { f.set(o, v); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }

    // ---- LivingEntity ----
    private static Field jumpingCooldown() { return field(LivingEntity.class, "field_6228", "jumpingCooldown"); }

    public static int getJumpingCooldown(LivingEntity e) { return getInt(jumpingCooldown(), e); }
    public static void setJumpingCooldown(LivingEntity e, int v) { setInt(jumpingCooldown(), e, v); }
    public static void invokeJump(LivingEntity e) {
        try { method(LivingEntity.class, "method_6043", "jump").invoke(e); }
        catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
    }

    // ---- MinecraftClient ----
    private static Field itemUseCooldown() { return field(MinecraftClient.class, "field_1752", "itemUseCooldown"); }

    public static int getItemUseCooldown(MinecraftClient mc) { return getInt(itemUseCooldown(), mc); }
    public static void setItemUseCooldown(MinecraftClient mc, int v) { setInt(itemUseCooldown(), mc, v); }

    // ---- ClientPlayerInteractionManager ----
    private static Field blockBreakingCooldown() { return field(ClientPlayerInteractionManager.class, "field_3716", "blockBreakingCooldown"); }
    private static Field currentBreakingProgress() { return field(ClientPlayerInteractionManager.class, "field_3715", "currentBreakingProgress"); }
    private static Field breakingBlock() { return field(ClientPlayerInteractionManager.class, "field_3717", "breakingBlock"); }

    public static int getBlockBreakingCooldown(ClientPlayerInteractionManager im) { return getInt(blockBreakingCooldown(), im); }
    public static void setBlockBreakingCooldown(ClientPlayerInteractionManager im, int v) { setInt(blockBreakingCooldown(), im, v); }
    public static float getCurrentBreakingProgress(ClientPlayerInteractionManager im) { return getFloat(currentBreakingProgress(), im); }
    public static void setCurrentBreakingProgress(ClientPlayerInteractionManager im, float v) { setFloat(currentBreakingProgress(), im, v); }
    public static boolean isBreakingBlock(ClientPlayerInteractionManager im) { return getBool(breakingBlock(), im); }

    // ---- HeldItemRenderer ----
    private static Field hr(String intermediary, String named) { return field(HeldItemRenderer.class, intermediary, named); }

    public static void setEquipProgressMainHand(HeldItemRenderer r, float v) { setFloat(hr("field_4043", "equipProgressMainHand"), r, v); }
    public static void setPrevEquipProgressMainHand(HeldItemRenderer r, float v) { setFloat(hr("field_4053", "prevEquipProgressMainHand"), r, v); }
    public static void setEquipProgressOffHand(HeldItemRenderer r, float v) { setFloat(hr("field_4052", "equipProgressOffHand"), r, v); }
    public static void setPrevEquipProgressOffHand(HeldItemRenderer r, float v) { setFloat(hr("field_4051", "prevEquipProgressOffHand"), r, v); }
    public static void setMainHand(HeldItemRenderer r, ItemStack s) { setObj(hr("field_4047", "mainHand"), r, s); }
    public static void setOffHand(HeldItemRenderer r, ItemStack s) { setObj(hr("field_4048", "offHand"), r, s); }
}
