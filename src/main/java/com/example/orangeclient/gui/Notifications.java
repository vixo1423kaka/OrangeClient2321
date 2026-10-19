package com.example.orangeclient.gui;

import com.example.orangeclient.module.render.Hud;
import com.example.orangeclient.util.UiFont;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * Powiadomienia "Modul wlaczony / wylaczony" (pozycja z modulu HUD, domyslnie prawy dol ekranu).
 * Animacje: wjazd na sprezynie z przeskokiem, galka przelacznika przeskakuje do nowego stanu,
 * karta plynnie zmienia kolor, blysk wokol karty, pasek pozostalego czasu, a stos powiadomien
 * "odbija sie" na sprezynach gdy ktores znika lub dochodzi nowe.
 */
public final class Notifications {
    public static final int H = 22;
    private static final int GAP = 4, MAX = 5;
    private static final long IN_MS = 380L, HOLD_MS = 1700L, OUT_MS = 300L;
    private static final int TEXT_GREY = 0xFFCBD2DC, TEXT_DIM = 0xFF8994A3;

    private static final class Notif {
        String name;
        boolean on;
        long born;
        float y = -1f;
        float vy;
    }

    private static final List<Notif> LIST = new ArrayList<>();
    private static long lastFrame;

    private Notifications() { }

    public static void push(String name, boolean on) {
        LIST.removeIf(n -> n.name.equals(name));      // ten sam modul: nowe powiadomienie zastepuje stare
        Notif n = new Notif();
        n.name = name;
        n.on = on;
        n.born = Util.getMeasuringTimeMs();
        LIST.add(n);
        while (LIST.size() > MAX) LIST.remove(0);
    }

    private static MutableText font(Text t) { return UiFont.apply(t); }

    /** Nazwa modulu + status; kolory dobrane do tla karty (pomaranczowa = wlaczony, ciemna = wylaczony). */
    private static MutableText label(String name, boolean on) {
        int nameCol = on ? 0xFFFFFF : TEXT_GREY & 0xFFFFFF;
        int statusCol = on ? 0xFFE0C0 : TEXT_DIM & 0xFFFFFF;
        return font(Text.literal(name)).styled(st -> st.withColor(nameCol))
                .append(font(Text.literal(on ? "  enabled" : "  disabled")).styled(st -> st.withColor(statusCol)));
    }

    /** Szerokosc: padding + tekst + odstep + przelacznik 14 px + padding. */
    public static int cardWidth(TextRenderer tr, String name, boolean on) {
        return 10 + UiFont.width(tr, label(name, on)) + 10 + 14 + 9;
    }

    /** Statyczna karta (uzywana w edytorze HUD): pelny stan, bez paska czasu. */
    public static void drawCard(DrawContext ctx, TextRenderer tr, int x, int y, int w, String name, boolean on) {
        drawCard(ctx, tr, x, y, w, name, on, on ? 1f : 0f, on ? 1f : 0f, 0f, -1f);
    }

    /**
     * @param en    stan koloru karty 0..1 (0 = wylaczony/szary, 1 = wlaczony/pomaranczowy)
     * @param kp    polozenie galki przelacznika 0..1 (moze lekko wyjsc poza zakres - przeskok)
     * @param vel   predkosc galki (rozciaga galke w ruchu)
     * @param timer pozostaly czas 0..1; ujemny = bez paska
     */
    public static void drawCard(DrawContext ctx, TextRenderer tr, int x, int y, int w, String name, boolean on,
                                float en, float kp, float vel, float timer) {
        // ten sam styl co karty modulow: polprzezroczysty gradient + obwodka, wlaczony = pomaranczowy
        ClickGui.drawCardBodyBlend(ctx, x, y, w, H, 9, true, en, 0f);
        UiFont.draw(ctx, tr, label(name, on), x + 10, y + (H - 8) / 2, 0xFFFFFFFF);
        ClickGui.drawSwitchSpring(ctx, x + w - 23, y + (H - 7) / 2, kp, vel, en, 0x99000000);
        if (timer >= 0f) {
            int tw = Math.round((w - 20) * Math.max(0f, Math.min(1f, timer)));
            if (tw > 1) ctx.fill(x + 10, y + H - 4, x + 10 + tw, y + H - 3, 0x55FFFFFF);
        }
    }

    /** Easing z lekkim przestrzeleniem. */
    private static float easeOutBack(float t, float k) {
        if (t >= 1f) return 1f;
        if (t <= 0f) return 0f;
        float u = t - 1f;
        return 1f + (k + 1f) * u * u * u + k * u * u;
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

    public static void render(DrawContext ctx) {
        if (LIST.isEmpty() || !Hud.notifVisible()) {
            lastFrame = 0;
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        final long now = Util.getMeasuringTimeMs();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        LIST.removeIf(n -> now - n.born > IN_MS + HOLD_MS + OUT_MS);

        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        int count = LIST.size();
        boolean right = Hud.notifRight(), bottom = Hud.notifBottom(), center = Hud.notifCenter();
        // po zmianie rozmiaru okna powiadomienie nie moze wyjechac poza ekran
        int mx = Math.min(Hud.notifX(), Math.max(0, sw - 40));
        int my = Math.min(Hud.notifY(), Math.max(0, sh - H));
        float step = Math.min(dt, 0.04f);
        int sub = Math.max(1, (int) Math.ceil(step / 0.008f));
        float h8 = step / sub;
        for (int i = 0; i < count; i++) {
            Notif n = LIST.get(i);
            // najnowsze powiadomienie przy krawedzi; reszta ukladana w kierunku srodka ekranu
            float targetY = bottom ? sh - my - H - (count - 1 - i) * (H + GAP)
                                   : my + (count - 1 - i) * (H + GAP);
            if (n.y < 0) {
                n.y = targetY;
                n.vy = 0f;
            } else {
                // stos na sprezynie: przesuniecia lekko "odbijaja"
                for (int k = 0; k < sub; k++) {
                    float a = (targetY - n.y) * 220f - n.vy * 20f;
                    n.vy += a * h8;
                    n.y += n.vy * h8;
                }
            }

            int w = cardWidth(tr, n.name, n.on);
            long age = now - n.born;

            // wjazd: sprezyna z przeskokiem (off < 0 = karta wjezdza lekko za daleko i wraca); wyjazd: przyspieszenie
            float off = 0f;
            if (age < IN_MS) {
                off = 1f - easeOutBack(age / (float) IN_MS, 1.4f);
            } else if (age > IN_MS + HOLD_MS) {
                float p = (age - IN_MS - HOLD_MS) / (float) OUT_MS;
                off = p * p * p;
            }
            int slide = center ? 0 : Math.round(off * (w + mx + 6));
            int x = center ? (sw - w) / 2 : right ? sw - mx - w + slide : mx - slide;
            int y = Math.round(n.y);

            // na srodku ekranu nie ma wjazdu z boku: karta "wyskakuje" w miejscu (skalowanie od srodka karty)
            var mat = ctx.getMatrices();
            if (center) {
                float sc = Math.max(0.02f, 1f - off);
                float cx = x + w / 2f, cy = y + H / 2f;
                mat.push();
                mat.translate(cx, cy, 0f);
                mat.scale(sc, sc, 1f);
                mat.translate(-cx, -cy, 0f);
            }

            // blysk wokol karty zaraz po pojawieniu sie
            if (age < 520L) {
                float t = age / 520f;
                int e = Math.round(t * 7f);
                int a = Math.round(0x58 * (1f - t) * (1f - t));
                int col = (a << 24) | (n.on ? 0xFF8617 : 0x9AA5B5);
                ClickGui.roundRing(ctx, x - 1 - e, y - 1 - e, x + w + 1 + e, y + H + 1 + e, 10 + e, col);
            }

            // galka przeskakuje ze starego stanu do nowego, karta w tym czasie zmienia kolor
            float from = n.on ? 0f : 1f, to = n.on ? 1f : 0f;
            float tk = clamp01((age - 90L) / 320f);
            float kp = from + (to - from) * easeOutBack(tk, 1.6f);
            float dk = (easeOutBack(Math.min(1f, tk + 0.04f), 1.6f) - easeOutBack(tk, 1.6f)) / (0.04f * 0.32f);
            float vel = (to - from) * dk;
            float en = clamp01(kp);
            float timer = 1f - clamp01((age - IN_MS) / (float) HOLD_MS);

            drawCard(ctx, tr, x, y, w, n.name, n.on, en, kp, vel, timer);
            if (center) mat.pop();
        }
    }
}
