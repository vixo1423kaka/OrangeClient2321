package com.example.orangeclient.util;

import com.example.orangeclient.gui.ClickGui;
import net.minecraft.client.gui.DrawContext;

/**
 * Wspolny wyglad kart HUD - wzorowany na TargetHUD: ciemny, zaokraglony panel z pomaranczowym
 * paskiem u gory, ramka na ikone/glowe i cienki zaokraglony pasek HP.
 * Uzywany przez TargetHUD-like elementy: Nametags, Waypoint Manager, Projectile trajectory.
 */
public final class HudStyle {
    public static final int BG = 0x171A20;
    public static final int ACCENT = 0xFF8617;
    public static final int WHITE = 0xF3F5F7;
    public static final int MUTED = 0xAAB3C0;
    public static final int FRAME = 0x3B424D;
    public static final int TRACK = 0x3A3F48;
    public static final int HP_GOOD = 0x62D98B;
    public static final int HP_MID = 0xFFC15A;
    public static final int HP_LOW = 0xFF5968;

    private HudStyle() { }

    /** Kolor RGB z kanalem alfa 0..255 -> ARGB. */
    public static int argb(int alpha, int rgb) { return (alpha << 24) | (rgb & 0xFFFFFF); }

    /** Kolor paska HP wg ulamka zdrowia (te same progi co w TargetHUD). */
    public static int hpColor(float frac) { return frac > 0.6f ? HP_GOOD : frac > 0.3f ? HP_MID : HP_LOW; }

    /**
     * Panel jak w TargetHUD: pomaranczowy ksztalt (tylko gora) + ciemny panel o tym samym promieniu przesuniety
     * w dol - zaokraglone rogi ciemnego panelu odslaniaja kolor, wiec pasek "wplywa" w boki panelu.
     *
     * @param accentRgb kolor paska (RGB bez alfa), zwykle {@link #ACCENT}
     */
    public static void panel(DrawContext ctx, int x1, int y1, int x2, int y2, int r, int bar, int accentRgb, int alpha) {
        int accent = argb(alpha, accentRgb);
        ClickGui.roundRectGradient(ctx, x1, y1, x2, y1 + r + bar + 2, r, accent, accent, false);
        ClickGui.roundRect(ctx, x1, y1 + bar, x2, y2, r, argb(alpha, BG));
    }

    /** Ramka na ikone / glowe (ciemnoszary zaokraglony kwadrat). */
    public static void frame(DrawContext ctx, int x1, int y1, int x2, int y2, int r, int alpha) {
        ClickGui.roundRect(ctx, x1, y1, x2, y2, r, argb(alpha, FRAME));
    }

    /** Pasek HP: tlo + wypelnienie (zaokraglone), jak w TargetHUD. */
    public static void bar(DrawContext ctx, int x, int y, int w, int h, float frac, int fillRgb, int alpha) {
        float r = h / 2f;
        ClickGui.roundRect(ctx, x, y, x + w, y + h, r, argb(alpha, TRACK));
        int fill = Math.round(w * Math.max(0f, Math.min(1f, frac)));
        if (fill > 0) ClickGui.roundRect(ctx, x, y, x + Math.max(fill, h), y + h, r, argb(alpha, fillRgb));
    }
}
