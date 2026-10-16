package com.example.orangeclient.gui;

import com.example.orangeclient.module.render.Hud;
import com.example.orangeclient.util.UiFont;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

/** Powiadomienia "Modul wlaczony / wylaczony" (pozycja z modulu HUD, domyslnie prawy dol ekranu). */
public final class Notifications {
    public static final int H = 22;
    private static final int GAP = 4, MAX = 5;
    private static final long IN_MS = 220L, HOLD_MS = 1700L, OUT_MS = 280L;
    private static final int TEXT_GREY = 0xFFCBD2DC, TEXT_DIM = 0xFF8994A3;

    private static final class Notif {
        String name;
        boolean on;
        long born;
        float y = -1f;
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

    /** Rysuje jedna karte powiadomienia w (x, y) o szerokosci w (z cardWidth) - uzywane tez w edytorze HUD. */
    public static void drawCard(DrawContext ctx, TextRenderer tr, int x, int y, int w, String name, boolean on) {
        // dokladnie ten sam styl co karty modulow: polprzezroczysty gradient + obwodka, wlaczony = pomaranczowy
        ClickGui.drawCardBody(ctx, x, y, w, H, on, 0f);
        UiFont.draw(ctx, tr, label(name, on), x + 10, y + (H - 8) / 2 + 0, 0xFFFFFFFF);
        ClickGui.drawSwitch(ctx, x + w - 23, y + (H - 7) / 2, on, 0x99000000);
    }

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
        boolean right = Hud.notifRight(), bottom = Hud.notifBottom();
        // po zmianie rozmiaru okna powiadomienie nie moze wyjechac poza ekran
        int mx = Math.min(Hud.notifX(), Math.max(0, sw - 40));
        int my = Math.min(Hud.notifY(), Math.max(0, sh - H));
        for (int i = 0; i < count; i++) {
            Notif n = LIST.get(i);
            // najnowsze powiadomienie przy krawedzi; reszta ukladana w kierunku srodka ekranu
            float targetY = bottom ? sh - my - H - (count - 1 - i) * (H + GAP)
                                   : my + (count - 1 - i) * (H + GAP);
            if (n.y < 0) n.y = targetY;
            else n.y += (targetY - n.y) * (1f - (float) Math.exp(-dt * 14f));

            int w = cardWidth(tr, n.name, n.on);

            // wsuwanie z prawej krawedzi, na koncu wysuwanie
            long age = now - n.born;
            float off = 0f;
            if (age < IN_MS) {
                float p = 1f - age / (float) IN_MS;
                off = p * p * p;
            } else if (age > IN_MS + HOLD_MS) {
                float p = (age - IN_MS - HOLD_MS) / (float) OUT_MS;
                off = p * p * p;
            }
            int slide = Math.round(off * (w + mx + 6));
            int x = right ? sw - mx - w + slide : mx - slide;
            int y = Math.round(n.y);

            drawCard(ctx, tr, x, y, w, n.name, n.on);
        }
    }
}
