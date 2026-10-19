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

    private static final int MIN_P = 5, MAX_P = 96;
    private static final Identifier[] PX = new Identifier[MAX_P + 1];
    private static final Identifier[] PX_BOLD = new Identifier[MAX_P + 1];

    /** Czcionka o rozmiarze P fizycznych pikseli (font/inter_pP.json) - rysowana 1:1, bez skalowania atlasu. */
    private static Identifier pxFont(int p, boolean bold) {
        Identifier[] arr = bold ? PX_BOLD : PX;
        if (arr[p] == null) arr[p] = Identifier.of("orangeclient", (bold ? "inter_bold_p" : "inter_p") + p);
        return arr[p];
    }

    /** Podmienia czcionki Intera w tekscie na wersje o rozmiarze P px (reszta stylu bez zmian). */
    private static Text remap(Text t, int p) {
        MutableText out = Text.empty();
        t.visit((style, str) -> {
            Identifier f = style.getFont();
            if (f != null && f.getNamespace().equals("orangeclient")) {
                String path = f.getPath();
                if (path.startsWith("inter_bold_s")) style = style.withFont(pxFont(p, true));
                else if (path.startsWith("inter_s")) style = style.withFont(pxFont(p, false));
            }
            out.append(Text.literal(str).setStyle(style));
            return java.util.Optional.empty();
        }, net.minecraft.text.Style.EMPTY);
        return out;
    }

    /**
     * Ostry tekst: rozmiar czcionki dobierany tak, zeby jeden piksel glifu = dokladnie jeden piksel ekranu
     * (z uwzglednieniem skali GUI i skali z macierzy, np. malych napisow), a punkt rysowania jest przyciagany
     * do calych pikseli ekranu. Dzieki temu kreski liter maja zawsze te sama grubosc.
     */
    private static void drawSharp(DrawContext ctx, TextRenderer tr, Text t, float x, float y, int color) {
        var m = ctx.getMatrices();
        var pm = m.peek().getPositionMatrix();
        float kx = pm.m00(), ky = pm.m11();
        if (!(kx > 0.05f) || !(ky > 0.05f)) { kx = 1f; ky = 1f; }
        double sf = MinecraftClient.getInstance().getWindow().getScaleFactor();
        float sx = pm.m00() * x + pm.m30(), sy = pm.m11() * y + pm.m31();
        double fk = sf * kx;
        int p = (int) Math.max(MIN_P, Math.min(MAX_P, Math.round(11.0 * UI_SIZE * fk)));
        float dx = (float) ((Math.round(sx * sf) / sf - sx) / kx);
        float dy = (float) ((Math.round(sy * sf) / sf - sy) / ky);
        int extra = Math.round(7f * p / 11f) - 7 + Math.round((float) (NUDGE * fk));
        float inv = (float) (1.0 / fk);
        m.push();
        m.translate(x + dx, y + dy, 0f);
        m.scale(inv, inv, 1f);
        ctx.drawText(tr, remap(t, p), 0, extra, color, false);
        m.pop();
    }

    public static void draw(DrawContext ctx, TextRenderer tr, Text t, int x, int y, int color) {
        resolve();
        if (useInter) { drawSharp(ctx, tr, t, x, y, color); return; }
        var m = ctx.getMatrices();
        m.push();
        m.scale(UI_SIZE, UI_SIZE, 1f);
        ctx.drawText(tr, t, Math.round(x / UI_SIZE), Math.round(y / UI_SIZE), color, false);
        m.pop();
    }

    public static void draw(DrawContext ctx, TextRenderer tr, String t, int x, int y, int color) {
        draw(ctx, tr, of(t), x, y, color);
    }

    public static void drawCentered(DrawContext ctx, TextRenderer tr, Text t, int cx, int y, int color) {
        resolve();
        if (useInter) {
            int sc = scale();
            drawSharp(ctx, tr, t, cx - tr.getWidth(t) / (float) sc * UI_SIZE / 2f, y, color);
            return;
        }
        var m = ctx.getMatrices();
        m.push();
        m.scale(UI_SIZE, UI_SIZE, 1f);
        ctx.drawText(tr, t, Math.round(cx / UI_SIZE - tr.getWidth(t) / 2f), Math.round(y / UI_SIZE), color, false);
        m.pop();
    }

    public static void drawCentered(DrawContext ctx, TextRenderer tr, String t, int cx, int y, int color) {
        drawCentered(ctx, tr, of(t), cx, y, color);
    }
}
