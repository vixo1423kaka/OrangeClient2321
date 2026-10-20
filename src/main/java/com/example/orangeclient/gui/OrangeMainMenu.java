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

/** Rounded, macOS-inspired Orange Client title screen. */
public final class OrangeMainMenu extends Screen {
    private static final int ORANGE = 0xFFFF8617;
    private static final int BLACK = 0xFF090A0E;
    private static final int PANEL = 0xF0181B22;
    private static final int BORDER = 0xFF343945;
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
    private int panelX, panelY, panelW, panelH;
    private int buttonX, buttonY, buttonW, buttonH, buttonGap;

    public OrangeMainMenu() {
        super(Text.literal("Orange Client"));
    }

    @Override
    protected void init() {
        panelW = Math.min(980, width - 28);
        panelH = Math.min(650, height - 28);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        buttonX = panelX + 38;
        buttonW = Math.min(360, Math.max(220, panelW / 2 - 68));
        buttonH = 50;
        buttonGap = 12;
        buttonY = panelY + 154;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        long time = Util.getMeasuringTimeMs();

        // Dark, full-screen backdrop with a soft orange animated atmosphere.
        ctx.fill(0, 0, width, height, BLACK);
        int band = (int) ((time / 30L) % Math.max(1, height + 120));
        ctx.fill(0, band - 100, width, band - 98, 0x16FF7A12);
        ctx.fill(0, band + 30, width, band + 32, 0x0DFF7A12);
        for (int i = 0; i < 38; i++) {
            float phase = (time * (0.010f + (i % 4) * 0.0025f) + i * 61f) % (height + 45);
            int px = (int) ((i * 137L + (long) (Math.sin(time / 1500.0 + i) * 50)) % Math.max(1, width));
            int py = (int) phase - 18;
            int alpha = 24 + (i % 5) * 8;
            ctx.fill(px, py, px + (i % 3 == 0 ? 3 : 2), py + 2, (alpha << 24) | 0x00FF8617);
        }

        // Main floating glass panel: rounded corners, shadow and a thin outline.
        rounded(ctx, panelX + 5, panelY + 8, panelX + panelW + 5, panelY + panelH + 8, 24, 0x50000000);
        rounded(ctx, panelX, panelY, panelX + panelW, panelY + panelH, 24, PANEL);
        roundedOutline(ctx, panelX, panelY, panelX + panelW, panelY + panelH, 24, BORDER);
        ctx.fill(panelX + 1, panelY + 63, panelX + panelW - 1, panelY + 64, 0x30FFFFFF);

        // macOS-style traffic lights, drawn as actual circles rather than square buttons.
        int lightY = panelY + 25;
        drawDot(ctx, panelX + 25, lightY, 6, 0xFFFF6058, 0xFFB83A35);
        drawDot(ctx, panelX + 45, lightY, 6, 0xFFFFBD2E, 0xFFB88719);
        drawDot(ctx, panelX + 65, lightY, 6, 0xFF28C840, 0xFF16872B);
        ctx.drawText(textRenderer, Text.literal("ORANGE CLIENT"), panelX + 92, panelY + 21, 0xFFB8BEC9, false);
        ctx.drawText(textRenderer, Text.literal("MINECRAFT  /  FABRIC"), panelX + panelW - 172, panelY + 21, 0xFF737B89, false);

        // Rotating orange hero on the right, contained inside the rounded app panel.
        float heroX = panelX + panelW * 0.755f;
        float heroY = panelY + panelH * 0.54f;
        drawGlow(ctx, heroX, heroY, Math.min(panelH * 0.80f, panelW * 0.42f), time);
        drawOrange(ctx, heroX, heroY, Math.min(panelH * 0.47f, panelW * 0.25f), time);

        int titleX = buttonX + 2;
        ctx.fill(titleX, panelY + 91, titleX + 4, panelY + 132, ORANGE);
        ctx.drawTextWithShadow(textRenderer, Text.literal("ORANGE"), titleX + 15, panelY + 91, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("CLIENT"), titleX + 15, panelY + 109, ORANGE);
        ctx.drawText(textRenderer, Text.literal("TWOJA GRA. TWOJE ZASADY."), titleX, panelY + 139, 0xFF969EAC, false);

        for (int i = 0; i < LABELS.length; i++) {
            int y = buttonY + i * (buttonH + buttonGap);
            boolean over = mouseX >= buttonX && mouseX <= buttonX + buttonW
                    && mouseY >= y && mouseY <= y + buttonH;
            hover[i] += ((over ? 1f : 0f) - hover[i]) * Math.min(1f, delta * 0.30f + 0.06f);
            drawButton(ctx, i, y, hover[i]);
        }

        ctx.drawText(textRenderer, Text.literal("PLAY DIFFERENT."), panelX + 38, panelY + panelH - 28, 0xFF737B89, false);
        ctx.drawText(textRenderer, Text.literal("v0.1.0  •  1.21.4"), panelX + panelW - 122, panelY + panelH - 28, 0xFF626A78, false);
    }

    private void drawButton(DrawContext ctx, int i, int y, float amount) {
        int fill = mix(0xE51D212A, 0xF53A2115, amount);
        int edge = mix(0xFF343945, ORANGE, amount);
        rounded(ctx, buttonX, y, buttonX + buttonW, y + buttonH, 12, fill);
        roundedOutline(ctx, buttonX, y, buttonX + buttonW, y + buttonH, 12, edge);
        rounded(ctx, buttonX + 1, y + 1, buttonX + 5, y + buttonH - 1, 2, mix(0xFF4A505D, ORANGE, amount));
        int tx = buttonX + 17;
        ctx.drawText(textRenderer, Text.literal(LABELS[i]), tx, y + 9, mix(0xFFE2E6ED, 0xFFFFFFFF, amount), false);
        ctx.drawText(textRenderer, Text.literal(HINTS[i]), tx, y + 27, mix(0xFF858E9D, 0xFFFFB16F, amount), false);
        if (amount > 0.02f) {
            int cx = buttonX + buttonW - 19;
            drawDot(ctx, cx, y + buttonH / 2, 3, (Math.round(amount * 255) << 24) | 0x00FF8617, 0x00000000);
        }
    }

    private void drawDot(DrawContext ctx, int cx, int cy, int radius, int color, int edge) {
        for (int dy = -radius; dy <= radius; dy++) {
            int span = (int) Math.floor(Math.sqrt(radius * radius - dy * dy));
            ctx.fill(cx - span, cy + dy, cx + span + 1, cy + dy + 1, color);
        }
        if ((edge >>> 24) != 0) {
            for (int dy = -radius; dy <= radius; dy++) {
                int outer = (int) Math.floor(Math.sqrt(radius * radius - dy * dy));
                int inner = (int) Math.floor(Math.sqrt(Math.max(0, (radius - 1) * (radius - 1) - dy * dy)));
                if (Math.abs(dy) < radius) {
                    ctx.fill(cx - outer, cy + dy, cx - inner, cy + dy + 1, edge);
                    ctx.fill(cx + inner + 1, cy + dy, cx + outer + 1, cy + dy + 1, edge);
                }
            }
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

    /** Fills a rectangle with rounded corners using horizontal scan lines. */
    private void rounded(DrawContext ctx, int x1, int y1, int x2, int y2, int radius, int color) {
        int r = Math.max(1, Math.min(radius, Math.min((x2 - x1) / 2, (y2 - y1) / 2)));
        for (int y = y1; y < y2; y++) {
            int dy = y < y1 + r ? y - (y1 + r) + 1 : (y >= y2 - r ? y - (y2 - r) : 0);
            int inset = 0;
            if (dy != 0) inset = r - (int) Math.floor(Math.sqrt(Math.max(0, r * r - dy * dy)));
            ctx.fill(x1 + inset, y, x2 - inset, y + 1, color);
        }
    }

    private void roundedOutline(DrawContext ctx, int x1, int y1, int x2, int y2, int radius, int color) {
        rounded(ctx, x1, y1, x2, y1 + 1, 1, color);
        rounded(ctx, x1, y2 - 1, x2, y2, 1, color);
        for (int y = y1 + radius; y < y2 - radius; y++) {
            ctx.fill(x1, y, x1 + 1, y + 1, color);
            ctx.fill(x2 - 1, y, x2, y + 1, color);
        }
        for (int y = y1; y < y1 + radius; y++) {
            int inset = radius - (int) Math.floor(Math.sqrt(Math.max(0, radius * radius - (radius - y + y1) * (radius - y + y1))));
            ctx.fill(x1 + inset, y, x1 + inset + 1, y + 1, color);
            ctx.fill(x2 - inset - 1, y, x2 - inset, y + 1, color);
        }
        for (int y = y2 - radius; y < y2; y++) {
            int d = y - (y2 - radius);
            int inset = radius - (int) Math.floor(Math.sqrt(Math.max(0, radius * radius - d * d)));
            ctx.fill(x1 + inset, y, x1 + inset + 1, y + 1, color);
            ctx.fill(x2 - inset - 1, y, x2 - inset, y + 1, color);
        }
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
