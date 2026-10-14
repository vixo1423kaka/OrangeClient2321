package com.example.orangeclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Jedno miejsce wyboru czcionki calego clienta (menu, HUD, powiadomienia, nametagi, ekrany).
 * Kolejnosc: Inter (font/inter.ttf + font/inter_bold.ttf) -> stary Instrument Sans (font/ui.ttf) -> zwykla czcionka MC.
 *
 * Tekst Intera jest rysowany w ROZDZIELCZOSCI FIZYCZNYCH PIKSELI: dla skali GUI = N uzywamy definicji
 * czcionki o rozmiarze 11*N (inter_sN.json), a ekran rysujemy z macierza 1/N. Wynik: ostre litery
 * przy kazdej skali GUI (zamiast zmniejszanego/powiekszanego atlasu glifow). Dlatego szerokosci tekstu
 * trzeba mierzyc przez {@link #width}, a nie przez TextRenderer.getWidth.
 */
public final class UiFont {
    private static final Identifier INTER_FILE = Identifier.of("orangeclient", "font/inter.ttf");
    private static final Identifier LEGACY_FILE = Identifier.of("orangeclient", "font/ui.ttf");
    private static final Identifier LEGACY = Identifier.of("orangeclient", "ui");
    private static final Identifier LEGACY_BOLD = Identifier.of("orangeclient", "ui_bold");
    private static final int MAX_SCALE = 6;
    private static final float UI_SIZE = 0.94f;
    private static final Identifier[] INTER = new Identifier[MAX_SCALE + 1];
    private static final Identifier[] INTER_BOLD = new Identifier[MAX_SCALE + 1];
    static {
        for (int i = 1; i <= MAX_SCALE; i++) {
            INTER[i] = Identifier.of("orangeclient", "inter_s" + i);
            INTER_BOLD[i] = Identifier.of("orangeclient", "inter_bold_s" + i);
        }
    }

    /**
     * Minecraft zawsze stawia linie bazowa tekstu 7 jednostek ponizej punktu rysowania - niezaleznie od rozmiaru czcionki.
     * Przy czcionce powiekszonej xN (rozmiar BASE*N) i rysowanej z macierza 1/N trzeba wiec dosunac tekst w dol
     * o 7*(N-1) pikseli fizycznych, zeby linia bazowa wypadla znowu 7 jednostek GUI pod y.
     */
    private static final int BASELINE = 7;
    /** Dodatkowe, reczne przesuniecie tekstu w dol w jednostkach GUI (np. 0.5f; ujemne = w gore) - do dostrojenia pionu. */
    private static final float NUDGE = 0.5f;

    private static int yOffset(int s) {
        return BASELINE * (s - 1) + (int) (NUDGE * s);
    }

    private static boolean useInter, useLegacy, resolved;

    private UiFont() { }

    /** Wymusza ponowne sprawdzenie plikow czcionek (np. przy otwarciu menu). */
    public static void refresh() { resolved = false; }

    private static void resolve() {
        if (resolved) return;
        var rm = MinecraftClient.getInstance().getResourceManager();
        useInter = rm.getResource(INTER_FILE).isPresent();
        useLegacy = !useInter && rm.getResource(LEGACY_FILE).isPresent();
        resolved = true;
    }

    /** Skala GUI (1..6) dla Intera; 1 dla pozostalych czcionek. */
    public static int scale() {
        resolve();
        if (!useInter) return 1;
        long s = Math.round(MinecraftClient.getInstance().getWindow().getScaleFactor());
        return (int) Math.max(1, Math.min(MAX_SCALE, s));
    }

    /** Nakleja czcionke clienta na tekst; pogrubienie (Formatting.BOLD) zamienia na prawdziwy font Bold. */
    public static MutableText apply(Text t) {
        resolve();
        MutableText m = t.copy();
        if (useInter) {
            final int s = scale();
            return m.styled(st -> {
                Identifier f = st.getFont();
                if (f != null && f.getNamespace().equals("orangeclient")) return st;     // juz ostylowane
                return st.isBold() ? st.withBold(false).withFont(INTER_BOLD[s]) : st.withFont(INTER[s]);
            });
        }
        if (useLegacy) {
            return m.styled(st -> {
                Identifier f = st.getFont();
                if (f != null && f.getNamespace().equals("orangeclient")) return st;
                return st.isBold() ? st.withBold(false).withFont(LEGACY_BOLD) : st.withFont(LEGACY);
            });
        }
        return m;
    }

    public static MutableText of(String s) { return apply(Text.literal(s)); }

    // ------------------------------------------------------------------ pomiar i rysowanie (bez cienia)

    /** Szerokosc tekstu w jednostkach GUI (niezalezna od skali). */
    public static int width(TextRenderer tr, Text t) {
        int s = scale();
        int w = tr.getWidth(t);
        return Math.round((s == 1 ? w : w / (float) s) * UI_SIZE);
    }

    public static int width(TextRenderer tr, String t) { return width(tr, of(t)); }

    public static void draw(DrawContext ctx, TextRenderer tr, Text t, int x, int y, int color) {
        int s = scale();
        var m = ctx.getMatrices();
        m.push();
        float factor = UI_SIZE / s;
        m.scale(factor, factor, 1f);
        ctx.drawText(tr, t, Math.round(x / factor), Math.round(y / factor) + yOffset(s), color, false);
        m.pop();
    }

    public static void draw(DrawContext ctx, TextRenderer tr, String t, int x, int y, int color) {
        draw(ctx, tr, of(t), x, y, color);
    }

    public static void drawCentered(DrawContext ctx, TextRenderer tr, Text t, int cx, int y, int color) {
        int s = scale();
        var m = ctx.getMatrices();
        m.push();
        float factor = UI_SIZE / s;
        m.scale(factor, factor, 1f);
        ctx.drawText(tr, t, Math.round(cx / factor - tr.getWidth(t) / 2f), Math.round(y / factor) + yOffset(s), color, false);
        m.pop();
    }

    public static void drawCentered(DrawContext ctx, TextRenderer tr, String t, int cx, int y, int color) {
        drawCentered(ctx, tr, of(t), cx, y, color);
    }
}
