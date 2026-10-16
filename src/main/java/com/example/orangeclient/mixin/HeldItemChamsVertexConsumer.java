package com.example.orangeclient.mixin;

import com.example.orangeclient.module.ModuleManager;
import com.example.orangeclient.module.render.Chams;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;

/**
 * Changes only item vertex colors. It deliberately avoids RenderSystem global shader state,
 * which can leak into the crosshair, HUD, and GUI while buffers are flushed later.
 */
final class HeldItemChamsVertexConsumer implements VertexConsumer {
    private final VertexConsumer delegate;
    private final Chams chams;

    HeldItemChamsVertexConsumer(VertexConsumer delegate, Chams chams) {
        this.delegate = delegate;
        this.chams = chams;
    }

    static VertexConsumerProvider wrap(VertexConsumerProvider original, Chams chams) {
        return (RenderLayer layer) -> new HeldItemChamsVertexConsumer(original.getBuffer(layer), chams);
    }

    @Override
    public VertexConsumer vertex(float x, float y, float z) {
        delegate.vertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {
        int rgb = chams.heldItemRgb();
        int r = clamp(red), g = clamp(green), b = clamp(blue), a = clamp(alpha);
        int tr = (rgb >> 16) & 0xFF;
        int tg = (rgb >> 8) & 0xFF;
        int tb = rgb & 0xFF;

        if (chams.heldItemStyleIs("Solid")) {
            r = tr;
            g = tg;
            b = tb;
        } else if (chams.heldItemStyleIs("Tint") || chams.heldItemStyleIs("Glass")) {
            r = r * tr / 255;
            g = g * tg / 255;
            b = b * tb / 255;
        } else if (chams.heldItemStyleIs("Invert")) {
            r = 255 - r;
            g = 255 - g;
            b = 255 - b;
        } else if (chams.heldItemStyleIs("Grayscale")) {
            int gray = clamp((int) (0.2126 * r + 0.7152 * g + 0.0722 * b));
            r = gray;
            g = gray;
            b = gray;
        } else if (chams.heldItemStyleIs("Sepia")) {
            int oldR = r, oldG = g, oldB = b;
            r = clamp((int) (0.393 * oldR + 0.769 * oldG + 0.189 * oldB));
            g = clamp((int) (0.349 * oldR + 0.686 * oldG + 0.168 * oldB));
            b = clamp((int) (0.272 * oldR + 0.534 * oldG + 0.131 * oldB));
        } else if (chams.heldItemStyleIs("Rainbow")) {
            int rainbow = java.awt.Color.HSBtoRGB((System.currentTimeMillis() % 4000L) / 4000.0f, 0.9f, 1.0f);
            r = r * ((rainbow >> 16) & 0xFF) / 255;
            g = g * ((rainbow >> 8) & 0xFF) / 255;
            b = b * (rainbow & 0xFF) / 255;
        }

        if (chams.heldItemStyleIs("Glass")) {
            a = a * Math.min(chams.heldItemAlphaPercent(), 55) / 100;
        }
        delegate.color(r, g, b, a);
        return this;
    }

    @Override
    public VertexConsumer texture(float u, float v) {
        delegate.texture(u, v);
        return this;
    }

    @Override
    public VertexConsumer overlay(int u, int v) {
        delegate.overlay(u, v);
        return this;
    }

    @Override
    public VertexConsumer light(int u, int v) {
        delegate.light(u, v);
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        delegate.normal(x, y, z);
        return this;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
