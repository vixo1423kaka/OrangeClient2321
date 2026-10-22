package com.example.orangeclient.module.render;

import com.example.orangeclient.util.Acc;
import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.module.ModuleManager;
import com.example.orangeclient.module.combat.KillAura;
import com.example.orangeclient.setting.BoolSetting;
import com.example.orangeclient.setting.ModeSetting;
import com.example.orangeclient.setting.NumberSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Arm;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/**
 * HandAnimations: wlasne animacje machniecia reka / itemem w pierwszej osobie (port modulu Animations
 * z ThunderHack). Dziala dla zwyklego machniecia - uzywanie itemow (jedzenie, luk, kusza, tarcza...) zostaje
 * vanillowe. Rendering robi HeldItemRendererMixin, a SlowAnimation - LivingEntitySwingMixin.
 */
public class HandAnimations extends Module {
    private final BoolSetting onlyAura = add(new BoolSetting("OnlyAura", false));
    private final BoolSetting disableSwapMain = add(new BoolSetting("DisableSwapMain", true));
    private final BoolSetting disableSwapOff = add(new BoolSetting("DisableSwapOff", true));
    private final ModeSetting mode = add(new ModeSetting("Mode", "Default",
            "Normal", "Default", "Smooth", "Chop", "Slash", "Stab", "Spin", "Flip", "Pulse", "Swing", "Jab",
            "Whip", "Wave", "Sweep", "Drop", "Pop"));
    private final BoolSetting slowAnimation = add(new BoolSetting("SlowAnimation", true));
    private final NumberSetting slowValue = add(new NumberSetting("SlowValue", 12, 1, 50, 1));

    private boolean flip;
    private boolean wasSwinging;

    public HandAnimations() {
        super("HandAnimations", Category.RENDER);
    }

    private boolean auraOk() {
        if (!onlyAura.get()) return true;
        KillAura ka = ModuleManager.get(KillAura.class);
        return ka != null && ka.isEnabled() && ka.getTarget() != null;
    }

    public boolean shouldAnimate() {
        return isEnabled() && auraOk() && !mode.is("Normal");
    }

    /** Dlugosc machniecia w tickach albo -1, gdy modul jej nie zmienia. */
    public static int swingDuration() {
        HandAnimations h = ModuleManager.get(HandAnimations.class);
        if (h == null || !h.isEnabled() || !h.auraOk() || !h.slowAnimation.get()) return -1;
        return (int) h.slowValue.get();
    }

    @Override
    public void onTick(MinecraftClient mc) {
        if (mc.player == null) return;
        boolean sw = mc.player.handSwinging;
        if (sw && !wasSwinging) flip = !flip;           // co machniecie zmienia kierunek obrotu (tryb Six)
        wasSwinging = sw;

        var held = mc.getEntityRenderDispatcher().getHeldItemRenderer();
        if (disableSwapMain.get()) {                    // bez animacji wyciagania przy zmianie slotu
            Acc.setEquipProgressMainHand(held, 1f);
            Acc.setPrevEquipProgressMainHand(held, 1f);
            Acc.setMainHand(held, mc.player.getMainHandStack());
        }
        if (disableSwapOff.get()) {
            Acc.setEquipProgressOffHand(held, 1f);
            Acc.setPrevEquipProgressOffHand(held, 1f);
            Acc.setOffHand(held, mc.player.getOffHandStack());
        }
    }

    // ------------------------------------------------------------------ transformacje (jak w Animations)

    /** Nakleja transformacje wybranego trybu na macierz (zastepuje vanillowe machniecie). */
    public void apply(MatrixStack matrices, Arm arm, float swingProgress, float equipProgress) {
        String md = mode.get();
        // tryby "obrotowe" na lewej rece: zwykle vanillowe machniecie, zeby nie wygladaly krzywo
        if (arm == Arm.LEFT && (md.equals("Chop") || md.equals("Slash") || md.equals("Wave") || md.equals("Swing")
                || md.equals("Whip") || md.equals("Pop"))) {
            applyEquipOffset(matrices, arm, equipProgress);
            applySwingOffset(matrices, arm, swingProgress);
            return;
        }
        final float pi = (float) Math.PI;
        int i = arm == Arm.RIGHT ? 1 : -1;
        float g = MathHelper.sin(MathHelper.sqrt(swingProgress) * pi);
        switch (md) {
            case "Default" -> {
                applyEquipOffset(matrices, arm, equipProgress);
                applySwingOffset(matrices, arm, swingProgress);
            }
            case "Smooth" -> {                          // lagodne przechylenie na bok
                applyEquipOffset(matrices, arm, 0);
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(i * g * -28.0F));
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(g * -10.0F));
            }
            case "Chop" -> {                            // sciecie z gory na dol
                applyEquipOffset(matrices, arm, 0);
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(45f));
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-65f * g - 45f));
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(105f));
            }
            case "Slash" -> {                           // poziome ciecie
                applyEquipOffset(matrices, arm, 0);
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(45f));
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-35f * (1f - g) - 28f));
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(100f));
            }
            case "Stab" -> {                            // pchniecie do przodu
                applyEquipOffset(matrices, arm, 0);
                matrices.translate(0, 0, -g / 3f);
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-115f));
            }
            case "Spin" -> {                            // pelny obrot wokol osi poziomej
                applyEquipOffset(matrices, arm, equipProgress);
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(swingProgress * -340f));
            }
            case "Flip" -> {                            // obrot na przemian w obie strony
                applyEquipOffset(matrices, arm, equipProgress);
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(swingProgress * (flip ? 340.0F : -340F)));
            }
            case "Pulse" -> {                           // item "pulsuje" (zmniejsza sie i wraca)
                applyEquipOffset(matrices, arm, equipProgress);
                float a = 1f - MathHelper.sin(swingProgress * pi) * 0.45f;
                matrices.scale(a, a, a);
            }
            case "Swing" -> {                           // szeroki zamach z przechyleniem
                applyEquipOffset(matrices, arm, -0.35F * g);
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(i * 45.0F));
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(i * g * -65.0F));
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-60f));
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(i * -45.0F));
            }
            case "Jab" -> {                             // krotkie, szybkie dziabniecie
                applyEquipOffset(matrices, arm, 0);
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(swingProgress > 0 ? -MathHelper.sin(swingProgress * 12f) * 32f : 0));
            }
            case "Whip" -> {                            // bicz: X i Z naraz
                applyEquipOffset(matrices, arm, 0);
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-MathHelper.sin(swingProgress * 3f) * 55f));
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-65f * g));
            }
            case "Wave" -> {                            // item trzymany bokiem, falujacy
                applyEquipOffset(matrices, arm, 0);
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(45f));
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-55f));
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(105f + 25f * g));
            }
            case "Sweep" -> {                           // klasyczne zamaszyste ciecie
                applyEquipOffset(matrices, arm, -0.4F * MathHelper.sin(MathHelper.sqrt(swingProgress) * pi));
                float f1 = MathHelper.sin(swingProgress * swingProgress * pi);
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(i * (45.0F + f1 * -25.0F)));
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(i * g * -24.0F));
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(i * -45.0F));
            }
            case "Drop" -> applyEquipOffset(matrices, arm, 0.25F * MathHelper.sin(MathHelper.sqrt(swingProgress) * 6.2831855F));
            case "Pop" -> {                             // item wyskakuje w gore i do przodu
                if (swingProgress > 0) {
                    matrices.translate(0.56F, equipProgress * -0.2f - 0.5F, -0.7F);
                    matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(45));
                    matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(g * -80.0F));
                    matrices.translate(-0.1F, 0.3F, 0.2F);
                    matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-80.0F));
                } else {
                    applyEquipOffset(matrices, arm, equipProgress);
                    applySwingOffset(matrices, arm, swingProgress);
                }
            }
            default -> {
                applyEquipOffset(matrices, arm, equipProgress);
                applySwingOffset(matrices, arm, swingProgress);
            }
        }
    }

    private void applyEquipOffset(MatrixStack matrices, Arm arm, float equipProgress) {
        int i = arm == Arm.RIGHT ? 1 : -1;
        matrices.translate(i * 0.56F, -0.52F + equipProgress * -0.6F, -0.72F);
    }

    private void applySwingOffset(MatrixStack matrices, Arm arm, float swingProgress) {
        int i = arm == Arm.RIGHT ? 1 : -1;
        float f = MathHelper.sin(swingProgress * swingProgress * (float) Math.PI);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(i * (45.0F + f * -20.0F)));
        float g = MathHelper.sin(MathHelper.sqrt(swingProgress) * (float) Math.PI);
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(i * g * -20.0F));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(g * -80.0F));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(i * -45.0F));
    }
}
