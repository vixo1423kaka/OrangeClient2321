package com.example.orangeclient.gui;

import com.example.orangeclient.util.UiFont;
import com.example.orangeclient.module.ModuleManager;
import com.example.orangeclient.module.render.Hud;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.List;

/**
 * Edytor pozycji HUD: pokazuje przykladowe powiadomienie (i liste modulow), ktore lapiesz myszka
 * i przesuwasz gdzie chcesz. ESC lub przycisk Done zapisuje i wraca do menu.
 * Ulepszenia: plynne wejscie, siatka, przyciaganie do srodka ekranu i marginesow (z liniami pomocniczymi),
 * animowane ramki z poswiata i uchwytami, etykiety i wspolrzedne podczas przeciagania.
 */
public class HudEditorScreen extends Screen {
    private enum Target { NONE, NOTIF, LIST }

    private static final int SNAP_CENTER = 6, SNAP_MARGIN = 4, MARGIN = 8;
    private static final int ORANGE = 0xFF8617;

    private final Screen parent;
    private Target dragging = Target.NONE;
    private int grabX, grabY;

    // pudelka elementow z ostatniej klatki (do klikania)
    private boolean hasNotif, hasList;
    private int nx1, ny1, nx2, ny2;
    private int lx1, ly1, lx2, ly2;
    private int listTextW, listRows;

    // animacje
    private final long openMs = Util.getMeasuringTimeMs();
    private long lastMs;
    private float dt = 0.016f;
    private float hoverN, hoverL, dragN, dragL;     // 0..1, wygladzone
    private float guideV, guideH;                   // widocznosc linii pomocniczych
    private boolean snapV, snapH;
    private float btnHover;
    private int btnX1, btnY1, btnX2, btnY2;

    public HudEditorScreen(Screen parent) {
        super(Text.literal("Edit HUD"));
        this.parent = parent;
    }

    @Override
    public boolean shouldPause() { return false; }

    private static boolean in(double mx, double my, int x1, int y1, int x2, int y2) {
        return mx >= x1 && mx < x2 && my >= y1 && my < y2;
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

    private float follow(float cur, float target, float speed) {
        return cur + (target - cur) * (1f - (float) Math.exp(-dt * speed));
    }

    private static float easeOutBack(float t, float k) {
        if (t >= 1f) return 1f;
        if (t <= 0f) return 0f;
        float u = t - 1f;
        return 1f + (k + 1f) * u * u * u + k * u * u;
    }

    private static int mix(int a, int b, float t) {
        int aa = Math.round(((a >>> 24) & 255) * (1f - t) + ((b >>> 24) & 255) * t);
        int rr = Math.round(((a >> 16) & 255) * (1f - t) + ((b >> 16) & 255) * t);
        int gg = Math.round(((a >> 8) & 255) * (1f - t) + ((b >> 8) & 255) * t);
        int bb = Math.round((a & 255) * (1f - t) + (b & 255) * t);
        return (aa << 24) | (rr << 16) | (gg << 8) | bb;
    }

    /** Kolor z alfa; zwraca 0 gdy alfa za male, zeby tekst nie byl rysowany "pelny" przez MC (alfa < 4). */
    private static int withAlpha(int rgb, float a) {
        int al = Math.round(255f * clamp01(a));
        return al < 8 ? 0 : (al << 24) | (rgb & 0xFFFFFF);
    }

    private void text(DrawContext ctx, TextRenderer tr, String s, int cx, int y, int rgb, float alpha) {
        int col = withAlpha(rgb, alpha);
        if (col != 0) UiFont.drawCentered(ctx, tr, UiFont.of(s), cx, y, col);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        long nowMs = Util.getMeasuringTimeMs();
        dt = lastMs == 0 ? 0.016f : Math.min(0.1f, (nowMs - lastMs) / 1000f);
        lastMs = nowMs;
        float open = clamp01((nowMs - openMs) / 380f);
        float openE = open * open * (3f - 2f * open);
        float pop = easeOutBack(open, 1.3f);

        // tlo: gradientowe przyciemnienie + siatka
        ctx.fillGradient(0, 0, width, height, Math.round(0xB4 * openE) << 24, Math.round(0x62 * openE) << 24);
        drawGrid(ctx, openE);

        TextRenderer tr = textRenderer;
        Hud hud = ModuleManager.get(Hud.class);
        hasNotif = false;
        hasList = false;

        // pudelka elementow
        List<String> names = null;
        if (hud != null) {
            if (hud.listEnabled()) {
                names = hud.listPreviewNames(tr);
                listTextW = hud.listWidth(tr, names);
                listRows = names.size();
                int[] b = hud.listBox(width, height, listTextW, listRows);
                lx1 = b[0]; ly1 = b[1]; lx2 = b[2]; ly2 = b[3];
                hasList = true;
            }
            if (hud.notifsEnabled()) {
                int w = Notifications.cardWidth(tr, "Example", true);
                int[] p = hud.notifPos(width, height, w, Notifications.H);
                nx1 = p[0]; ny1 = p[1]; nx2 = nx1 + w; ny2 = ny1 + Notifications.H;
                hasNotif = true;
            }
        }

        // wygladzone stany hover / drag
        boolean hovN = hasNotif && (dragging == Target.NOTIF || (dragging == Target.NONE && in(mouseX, mouseY, nx1, ny1, nx2, ny2)));
        boolean hovL = hasList && (dragging == Target.LIST || (dragging == Target.NONE && in(mouseX, mouseY, lx1, ly1, lx2, ly2)));
        hoverN = follow(hoverN, hovN ? 1f : 0f, 14f);
        hoverL = follow(hoverL, hovL ? 1f : 0f, 14f);
        dragN = follow(dragN, dragging == Target.NOTIF ? 1f : 0f, 16f);
        dragL = follow(dragL, dragging == Target.LIST ? 1f : 0f, 16f);
        guideV = follow(guideV, snapV ? 1f : 0f, 18f);
        guideH = follow(guideH, snapH ? 1f : 0f, 18f);

        drawGuides(ctx, nowMs);

        // elementy HUD (wskakuja na miejsce przy otwarciu)
        var m = ctx.getMatrices();
        if (hasList) {
            m.push();
            float cx = (lx1 + lx2) / 2f, cy = (ly1 + ly2) / 2f, s = 0.94f + 0.06f * pop;
            m.translate(cx, cy, 0f);
            m.scale(s, s, 1f);
            m.translate(-cx, -cy, 0f);
            hud.drawList(ctx, tr, names);
            m.pop();
        }
        if (hasNotif) {
            m.push();
            float cx = (nx1 + nx2) / 2f, cy = (ny1 + ny2) / 2f, s = 0.94f + 0.06f * pop;
            m.translate(cx, cy, 0f);
            m.scale(s, s, 1f);
            m.translate(-cx, -cy, 0f);
            Notifications.drawCard(ctx, tr, nx1, ny1, nx2 - nx1, "Example", true);
            m.pop();
        }
        if (hasList) drawFrame(ctx, tr, lx1, ly1, lx2, ly2, hoverL, dragL, "Module list", nowMs);
        if (hasNotif) drawFrame(ctx, tr, nx1, ny1, nx2, ny2, hoverN, dragN, "Notifications", nowMs);

        // naglowek: wjezdza z gory, tytul "oddycha"
        float pulse = 0.5f + 0.5f * (float) Math.sin(nowMs / 700.0);
        int titleRgb = mix(0xFFFF7A00, 0xFFFFA64D, pulse) & 0xFFFFFF;
        text(ctx, tr, "Edit HUD", width / 2, Math.round(14 - (1f - openE) * 12f), titleRgb, openE);
        float hintT = clamp01((nowMs - openMs - 120L) / 380f);
        text(ctx, tr, "Drag the elements with the mouse, ESC to finish", width / 2, 28, 0xBDBDBD, hintT * hintT * (3f - 2f * hintT));
        if (hud != null && !hud.isEnabled()) {
            text(ctx, tr, "HUD module is off - turn it on to use these positions", width / 2, 42, 0xFF5F5F, openE);
        }
        if (hud != null && !hasNotif && !hasList) {
            text(ctx, tr, "Notifications and ModuleList are both off in HUD settings", width / 2, height / 2, 0xBDBDBD, openE);
        }

        drawDoneButton(ctx, tr, mouseX, mouseY, openE);
    }

    /** Delikatna siatka co 24 px + wyrazniejszy krzyz na srodku ekranu. */
    private void drawGrid(DrawContext ctx, float openE) {
        int a = Math.round(0x0E * openE);
        if (a > 0) {
            int col = (a << 24) | 0xFFFFFF;
            for (int x = width / 2 % 24; x < width; x += 24) ctx.fill(x, 0, x + 1, height, col);
            for (int y = height / 2 % 24; y < height; y += 24) ctx.fill(0, y, width, y + 1, col);
        }
        int ca = Math.round(0x22 * openE);
        if (ca > 0) {
            int col = (ca << 24) | 0xFFFFFF;
            ctx.fill(width / 2, 0, width / 2 + 1, height, col);
            ctx.fill(0, height / 2, width, height / 2 + 1, col);
        }
    }

    /** Pomaranczowe, przerywane linie srodka ekranu - widoczne gdy przeciagany element sie do niego przyciaga. */
    private void drawGuides(DrawContext ctx, long nowMs) {
        int off = (int) ((nowMs / 40L) % 10L);   // lekko "plynace" kreski
        if (guideV > 0.02f) {
            int col = (Math.round(0xD0 * guideV) << 24) | ORANGE;
            for (int y = -off; y < height; y += 10) ctx.fill(width / 2, Math.max(0, y), width / 2 + 1, Math.min(height, y + 6), col);
        }
        if (guideH > 0.02f) {
            int col = (Math.round(0xD0 * guideH) << 24) | ORANGE;
            for (int x = -off; x < width; x += 10) ctx.fill(Math.max(0, x), height / 2, Math.min(width, x + 6), height / 2 + 1, col);
        }
    }

    private void drawFrame(DrawContext ctx, TextRenderer tr, int x1, int y1, int x2, int y2,
                           float hov, float drag, String label, long nowMs) {
        float act = Math.max(hov, drag);
        int ringCol = mix(0x66FFFFFF, 0xFFFF8617, act);
        // poswiata (oddycha)
        if (act > 0.02f) {
            float pulse = 0.75f + 0.25f * (float) Math.sin(nowMs / 420.0);
            for (int i = 4; i >= 1; i--) {
                int a = Math.round(0x34 * (1f - i / 5f) * act * pulse);
                ClickGui.roundRing(ctx, x1 - 3 - i, y1 - 3 - i, x2 + 3 + i, y2 + 3 + i, 9 + i, (a << 24) | ORANGE);
            }
        }
        ClickGui.roundRing(ctx, x1 - 3, y1 - 3, x2 + 3, y2 + 3, 9, ringCol);

        // uchwyty w rogach - pojawiaja sie przy najechaniu
        if (act > 0.3f) {
            int hc = withAlpha(0xFFB066, (act - 0.3f) / 0.7f);
            if (hc != 0) {
                int[][] cs = {{x1 - 3, y1 - 3}, {x2 + 3, y1 - 3}, {x1 - 3, y2 + 3}, {x2 + 3, y2 + 3}};
                for (int[] c : cs) ClickGui.roundRect(ctx, c[0] - 2, c[1] - 2, c[0] + 2, c[1] + 2, 2f, hc);
            }
        }

        // etykieta nad elementem (pod nim, gdy brak miejsca u gory) + wspolrzedne podczas przeciagania
        boolean above = y1 > 40;
        if (hov > 0.05f) {
            drawChip(ctx, tr, label, (x1 + x2) / 2, above ? y1 - 24 : y2 + 10, hov);
        }
        if (drag > 0.05f) {
            drawChip(ctx, tr, "x " + x1 + "   y " + y1, (x1 + x2) / 2, above ? y2 + 10 : y1 - 24, drag);
        }
    }

    /** Pigulka z tekstem wysuwajaca sie lekko z dolu, z fade. */
    private void drawChip(DrawContext ctx, TextRenderer tr, String s, int cx, int y, float a) {
        int tw = UiFont.width(tr, s);
        int w = tw + 16, h = 14;
        int yy = y + Math.round((1f - a) * 4f);
        int x1 = Math.max(2, Math.min(width - w - 2, cx - w / 2));
        int bg = (Math.round(0xE0 * a) << 24) | 0x14171C;
        int ring = (Math.round(0x90 * a) << 24) | ORANGE;
        ClickGui.roundRect(ctx, x1, yy, x1 + w, yy + h, 7, bg);
        ClickGui.roundRing(ctx, x1, yy, x1 + w, yy + h, 7, ring);
        int tc = withAlpha(0xFFFFFF, a);
        if (tc != 0) UiFont.draw(ctx, tr, s, x1 + 8, yy + (h - 8) / 2, tc);
    }

    private void drawDoneButton(DrawContext ctx, TextRenderer tr, int mouseX, int mouseY, float openE) {
        int tw = UiFont.width(tr, "Done");
        int w = tw + 30, h = 18;
        btnX1 = width / 2 - w / 2;
        btnX2 = btnX1 + w;
        btnY2 = height - 14;
        btnY1 = btnY2 - h;
        btnHover = follow(btnHover, in(mouseX, mouseY, btnX1, btnY1, btnX2, btnY2) ? 1f : 0f, 16f);
        int lift = Math.round((1f - openE) * 14f) - Math.round(btnHover * 1.5f);
        int y1 = btnY1 + lift;
        if (btnHover > 0.02f) {
            for (int i = 3; i >= 1; i--) {
                int a = Math.round(0x30 * (1f - i / 4f) * btnHover);
                ClickGui.roundRing(ctx, btnX1 - i, y1 - i, btnX2 + i, y1 + h + i, 9 + i, (a << 24) | ORANGE);
            }
        }
        int body = mix(0xB0222830, 0xE0E8780C, btnHover);
        ClickGui.roundRect(ctx, btnX1, y1, btnX2, y1 + h, 9, mix((int) (Math.round(0xB0 * openE) << 24) | 0x222830, body, btnHover));
        ClickGui.roundRing(ctx, btnX1, y1, btnX2, y1 + h, 9, mix(0xB0343B45, 0xFFFF8617, btnHover));
        int tc = withAlpha(0xFFFFFF, openE);
        if (tc != 0) UiFont.drawCentered(ctx, tr, "Done", width / 2, y1 + (h - 8) / 2, tc);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (in(mouseX, mouseY, btnX1, btnY1, btnX2, btnY2)) {
                close();
                return true;
            }
            if (hasNotif && in(mouseX, mouseY, nx1, ny1, nx2, ny2)) {          // powiadomienie jest na wierzchu
                dragging = Target.NOTIF;
                grabX = (int) mouseX - nx1;
                grabY = (int) mouseY - ny1;
                return true;
            }
            if (hasList && in(mouseX, mouseY, lx1, ly1, lx2, ly2)) {
                dragging = Target.LIST;
                grabX = (int) mouseX - lx1;
                grabY = (int) mouseY - ly1;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Przyciaganie: do srodka ekranu (z linia pomocnicza) i do marginesu 8 px od krawedzi. */
    private int[] snap(int x, int y, int w, int h) {
        snapV = false;
        snapH = false;
        if (Math.abs(x + w / 2 - width / 2) <= SNAP_CENTER) {
            x = width / 2 - w / 2;
            snapV = true;
        } else if (Math.abs(x - MARGIN) <= SNAP_MARGIN) {
            x = MARGIN;
        } else if (Math.abs(x + w - (width - MARGIN)) <= SNAP_MARGIN) {
            x = width - MARGIN - w;
        }
        if (Math.abs(y + h / 2 - height / 2) <= SNAP_CENTER) {
            y = height / 2 - h / 2;
            snapH = true;
        } else if (Math.abs(y - MARGIN) <= SNAP_MARGIN) {
            y = MARGIN;
        } else if (Math.abs(y + h - (height - MARGIN)) <= SNAP_MARGIN) {
            y = height - MARGIN - h;
        }
        return new int[]{x, y};
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (dragging != Target.NONE && button == 0) {
            Hud hud = ModuleManager.get(Hud.class);
            if (hud == null) return true;
            if (dragging == Target.NOTIF) {
                int w = nx2 - nx1, h = ny2 - ny1;
                int x = Math.max(0, Math.min(width - w, (int) mouseX - grabX));
                int y = Math.max(0, Math.min(height - h, (int) mouseY - grabY));
                int[] s = snap(x, y, w, h);
                hud.setNotifPos(width, height, s[0], s[1], w, h);
            } else {
                int w = lx2 - lx1, h = ly2 - ly1;
                int x = Math.max(0, Math.min(width - w, (int) mouseX - grabX));
                int y = Math.max(0, Math.min(height - h, (int) mouseY - grabY));
                int[] s = snap(x, y, w, h);
                hud.setListPos(width, height, s[0], s[1], listTextW, listRows);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragging != Target.NONE) {
            dragging = Target.NONE;
            snapV = false;
            snapH = false;
            ModuleManager.save();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void close() {
        ModuleManager.save();
        if (client != null) client.setScreen(parent);
    }
}
