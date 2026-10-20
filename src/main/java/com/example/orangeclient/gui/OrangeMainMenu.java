package com.example.orangeclient.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.RotationAxis;
import org.lwjgl.glfw.GLFW;

/** Custom orange-and-black title screen with a rotating orange and animated controls. */
public final class OrangeMainMenu extends Screen {
    private static final int ORANGE = 0xFFFF8617;
    private static final int BLACK = 0xFF090B10;
    private static final int PANEL = 0xD9161A22;
    private static final int BORDER = 0xFF303744;
    private static final Identifier ORANGE_TEX = Identifier.of("orangeclient", "textures/gui/orange.png");
    private static final Identifier GLOW_TEX = Identifier.of("orangeclient", "textures/gui/glow.png");
    private static final int TEX_SIZE = 512;
    private static final int GLOW_SIZE = 512;
    private static final float ORANGE_SHARE = 0.46f;
    private static final String[] LABELS = {"JEDNOOSOBOWA", "WIELOOSOBOWA", "USTAWIENIA", "WYJDŹ"};
    private static final String[] HINTS = {
            "Stwórz świat lub kontynuuj przygodę",
            "Dołącz do serwera ze znajomymi",
            "Grafika, sterowanie i dźwięk",
            "Zamknij Orange Client"
    };
    private final float[] hover = new float[LABELS.length];
    private int buttonX, buttonY, buttonW, buttonH, buttonGap;

    public OrangeMainMenu() {
        super(Text.literal("Orange Client"));
    }

    @Override
    protected void init() {
        buttonW = Math.min(270, Math.max(210, width / 3));
        buttonH = 42;
        buttonGap = 10;
        buttonX = Math.max(28, width / 14);
        buttonY = Math.min(height - LABELS.length * (buttonH + buttonGap) - 12,
                Math.max(130, height / 2 - 4));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        long time = Util.getMeasuringTimeMs();
        ctx.fill(0, 0, width, height, BLACK);

        // Animated orange bands and floating embers.
        int band = (int) ((time / 32L) % Math.max(1, height + 100));
        ctx.fill(0, band - 100, width, band - 98, 0x18FF7A12);
        ctx.fill(0, band + 35, width, band + 36, 0x10FF7A12);
        for (int i = 0; i < 28; i++) {
            float phase = (time * (0.012f + (i % 4) * 0.003f) + i * 73f) % (height + 50);
            int px = (int) ((i * 137L + (long) (Math.sin(time / 1700.0 + i) * 45)) % Math.max(1, width));
            int py = (int) phase - 20;
            int alpha = 24 + (i % 5) * 8;
            ctx.fill(px, py, px + (i % 3 == 0 ? 3 : 2), py + 2, (alpha << 24) | 0x00FF8617);
        }

        // Pulsing halo and a full rotation every 16 seconds.
        drawGlow(ctx, width * 0.73f, height * 0.49f, Math.min(height * 1.05f, width * 0.78f), time);
        drawOrange(ctx, width * 0.73f, height * 0.49f, Math.min(height * 0.62f, width * 0.43f), time);

        int left = Math.max(28, width / 14);
        ctx.fill(left, 38, left + 4, 91, ORANGE);
        ctx.drawTextWithShadow(textRenderer, Text.literal("ORANGE"), left + 17, 39, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("CLIENT"), left + 17, 57, ORANGE);
        ctx.drawText(textRenderer, Text.literal("MINECRAFT • FABRIC EDITION"), left + 17, 78, 0xFF9299A7);
        ctx.drawText(textRenderer, Text.literal("PLAY DIFFERENT."), left, height - 31, 0xFF737B89);
        ctx.drawText(textRenderer, Text.literal("v0.1.0  /  1.21.4"), left + 120, height - 31, 0xFF4C5563);

        for (int i = 0; i < LABELS.length; i++) {
            int y = buttonY + i * (buttonH + buttonGap);
            boolean over = mouseX >= buttonX && mouseX <= buttonX + buttonW
                    && mouseY >= y && mouseY <= y + buttonH;
            hover[i] += ((over ? 1f : 0f) - hover[i]) * Math.min(1f, delta * 0.28f + 0.08f);
            drawButton(ctx, i, y, hover[i]);
        }
    }

    private void drawButton(DrawContext ctx, int i, int y, float amount) {
        int fill = mix(PANEL, 0xF53A2115, amount);
        int edge = mix(BORDER, ORANGE, amount);
        ctx.fill(buttonX, y, buttonX + buttonW, y + buttonH, fill);
        ctx.fill(buttonX, y, buttonX + 3, y + buttonH, edge);
        ctx.fill(buttonX + 3, y, buttonX + buttonW, y + 1, mix(0xFF242A34, 0xFFFFA04D, amount));
        int tx = buttonX + 17;
        ctx.drawText(textRenderer, Text.literal(LABELS[i]), tx, y + 8, mix(0xFFE2E6ED, 0xFFFFFFFF, amount), false);
        ctx.drawText(textRenderer, Text.literal(HINTS[i]), tx, y + 23, mix(0xFF858E9D, 0xFFFFB16F, amount), false);
        if (amount > 0.02f) {
            int markerX = buttonX + buttonW - 15;
            ctx.fill(markerX, y + 18, markerX + 4, y + 22, (Math.round(amount * 255) << 24) | 0x00FF8617);
        }
    }

    private void drawGlow(DrawContext ctx, float cx, float cy, float size, long time) {
        float pulse = 1f + 0.055f * (float) Math.sin(time / 720.0);
        float scale = size * pulse / GLOW_SIZE;
        var matrices = ctx.getMatrices();
        matrices.push();
        matrices.translate(cx, cy, 0);
        matrices.scale(scale, scale, 1f);
        ctx.drawTexture(RenderLayer::getGuiTextured, GLOW_TEX,
                -GLOW_SIZE / 2, -GLOW_SIZE / 2, 0f, 0f, GLOW_SIZE, GLOW_SIZE, GLOW_SIZE, GLOW_SIZE);
        matrices.pop();
    }

    private void drawOrange(DrawContext ctx, float cx, float cy, float size, long time) {
        float pulse = 1f + 0.025f * (float) Math.sin(time / 850.0);
        float scale = (size * pulse / ORANGE_SHARE) / TEX_SIZE;
        float angle = (time % 16000L) / 16000f * 360f;
        var matrices = ctx.getMatrices();
        matrices.push();
        matrices.translate(cx, cy + (float) Math.sin(time / 1000.0) * 5f, 0);
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(angle));
        matrices.scale(scale, scale, 1f);
        ctx.drawTexture(RenderLayer::getGuiTextured, ORANGE_TEX,
                -TEX_SIZE / 2, -TEX_SIZE / 2, 0f, 0f, TEX_SIZE, TEX_SIZE, TEX_SIZE, TEX_SIZE);
        matrices.pop();
    }

    private static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >>> 24) & 255, ar = (a >>> 16) & 255, ag = (a >>> 8) & 255, ab = a & 255;
        int ba = (b >>> 24) & 255, br = (b >>> 16) & 255, bg = (b >>> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * t) << 24)
                | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8)
                | Math.round(ab + (bb - ab) * t);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (int i = 0; i < LABELS.length; i++) {
                int y = buttonY + i * (buttonH + buttonGap);
                if (mouseX >= buttonX && mouseX <= buttonX + buttonW && mouseY >= y && mouseY <= y + buttonH) {
                    activate(i);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void activate(int index) {
        MinecraftClient client = MinecraftClient.getInstance();
        switch (index) {
            case 0 -> client.setScreen(new SelectWorldScreen(this));
            case 1 -> client.setScreen(new MultiplayerScreen(this));
            case 2 -> client.setScreen(new OptionsScreen(this, client.options));
            case 3 -> client.scheduleStop();
            default -> { }
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
