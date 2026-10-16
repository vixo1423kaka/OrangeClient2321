package com.example.orangeclient.module.render;

import com.example.orangeclient.module.Category;
import com.example.orangeclient.module.Module;
import com.example.orangeclient.util.RenderUtil;
import com.example.orangeclient.util.UiFont;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import com.example.orangeclient.util.HudStyle;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.render.Camera;
import org.joml.Quaternionf;
import org.joml.Vector4f;
import org.joml.Matrix4f;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import com.example.orangeclient.OrangeClient;

/** Waypoint data and world rendering. Configuration is opened from ClickGUI. */
public class WaypointManager extends Module {
    public static final class Waypoint {
        public String name;
        public Vec3d pos;
        public String dimension;
        public int color;
        public String icon;
        public float screenX = -1, screenY = -1;
        public Waypoint(String name, Vec3d pos, String dimension, int color) {
            this.name = name; this.pos = pos; this.dimension = dimension; this.color = color; this.icon = "◆";
        }
    }
    private final List<Waypoint> waypoints = new ArrayList<>();
    private String activeServerKey = "";
    private static final Gson WAYPOINT_GSON = new GsonBuilder().setPrettyPrinting().create();
    private Path waypointFile() { return FabricLoader.getInstance().getConfigDir().resolve("orangeclient-waypoints.json"); }
    private String serverKey(MinecraftClient mc) {
        if (mc.getCurrentServerEntry() != null && mc.getCurrentServerEntry().address != null)
            return "server:" + mc.getCurrentServerEntry().address.trim().toLowerCase(java.util.Locale.ROOT);
        if (mc.isInSingleplayer() && mc.world != null)
            return "singleplayer:" + mc.world.getRegistryKey().getValue();
        return "unknown";
    }
    private void switchServerIfNeeded(MinecraftClient mc) {
        String key = serverKey(mc);
        if (key.equals(activeServerKey)) return;
        saveActiveWaypoints();
        activeServerKey = key;
        waypoints.clear(); selected = -1;
        try {
            Path f = waypointFile();
            if (!Files.exists(f)) return;
            JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            if (!root.has(key) || !root.get(key).isJsonArray()) return;
            for (var el : root.getAsJsonArray(key)) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                Waypoint w = new Waypoint(o.has("name") ? o.get("name").getAsString() : "Waypoint",
                    new Vec3d(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble()),
                    o.has("dimension") ? o.get("dimension").getAsString() : "minecraft:overworld",
                    o.has("color") ? o.get("color").getAsInt() : 0xFFFF9900);
                if (o.has("icon")) w.icon = o.get("icon").getAsString();
                waypoints.add(w);
            }
        } catch (Exception e) { OrangeClient.LOGGER.error("Nie udalo sie wczytac waypointow", e); }
    }
    private void saveActiveWaypoints() {
        if (activeServerKey.isEmpty()) return;
        try {
            Path f = waypointFile(); JsonObject root = new JsonObject();
            if (Files.exists(f)) try { root = JsonParser.parseString(Files.readString(f)).getAsJsonObject(); } catch (Exception ignored) { }
            JsonArray arr = new JsonArray();
            for (Waypoint w : waypoints) { JsonObject o = new JsonObject(); o.addProperty("name", w.name); o.addProperty("x", w.pos.x); o.addProperty("y", w.pos.y); o.addProperty("z", w.pos.z); o.addProperty("dimension", w.dimension); o.addProperty("color", w.color); o.addProperty("icon", w.icon); arr.add(o); }
            root.add(activeServerKey, arr); Files.writeString(f, WAYPOINT_GSON.toJson(root));
        } catch (Exception e) { OrangeClient.LOGGER.error("Nie udalo sie zapisac waypointow", e); }
    }
    private int selected = -1;
    private boolean beam = true;
    private boolean labels = true;

    public WaypointManager() { super("Waypoint Manager", Category.RENDER); }
    public List<Waypoint> getWaypoints() { return waypoints; }
    public int getSelected() { return selected; }
    public void setSelected(int i) { selected = i >= 0 && i < waypoints.size() ? i : -1; }
    public boolean isBeam() { return beam; }
    public void setBeam(boolean v) { beam = v; }
    public boolean isLabels() { return labels; }
    public void setLabels(boolean v) { labels = v; }
    public void createAtPlayer(MinecraftClient mc, String name) {
        if (mc.player == null || mc.world == null) return;
        switchServerIfNeeded(mc);
        String n = name == null || name.trim().isEmpty() ? "Waypoint " + (waypoints.size() + 1) : name.trim();
        waypoints.add(new Waypoint(n, mc.player.getPos(), mc.world.getRegistryKey().getValue().toString(), 0xFFFF9900));
        selected = waypoints.size() - 1; saveActiveWaypoints();
    }
    public void removeSelected() { if (selected >= 0 && selected < waypoints.size()) { waypoints.remove(selected); selected = waypoints.isEmpty() ? -1 : Math.min(selected, waypoints.size()-1); saveActiveWaypoints(); } }
    public void renameSelected(String name) { if (selected >= 0 && selected < waypoints.size() && name != null && !name.trim().isEmpty()) waypoints.get(selected).name = name.trim(); saveActiveWaypoints(); }
    public void cycleColorSelected() {
        if (selected < 0 || selected >= waypoints.size()) return;
        Waypoint w = waypoints.get(selected);
        int[] colors = {0xFFFF8617, 0xFF55D6FF, 0xFF66E58C, 0xFFFF5D78, 0xFFB68CFF, 0xFFFFD45C, 0xFFFFFFFF};
        int next = 0; for (int i=0;i<colors.length;i++) if (colors[i] == w.color) { next=(i+1)%colors.length; break; }
        w.color = colors[next]; saveActiveWaypoints();
    }
    public void cycleIconSelected() {
        if (selected < 0 || selected >= waypoints.size()) return;
        String[] icons = {"◆", "✚", "⚑", "☠", "●", "✦", "★", "♥", "✿"};
        Waypoint w = waypoints.get(selected); int i=0;
        for (int j=0;j<icons.length;j++) if (icons[j].equals(w.icon)) { i=(j+1)%icons.length; break; }
        w.icon=icons[i]; saveActiveWaypoints();
    }
    public void remove(int i) { setSelected(i); removeSelected(); }
    public void rename(int i, String name) { if (i >= 0 && i < waypoints.size()) { waypoints.get(i).name = name == null ? "" : name; saveActiveWaypoints(); } }
    public void cycleColor(int i, int dir) {
        if (i < 0 || i >= waypoints.size()) return;
        Waypoint w = waypoints.get(i);
        int[] colors = {0xFFFF8617, 0xFF55D6FF, 0xFF66E58C, 0xFFFF5D78, 0xFFB68CFF, 0xFFFFD45C, 0xFFFFFFFF};
        int cur = 0; for (int k = 0; k < colors.length; k++) if (colors[k] == w.color) { cur = k; break; }
        w.color = colors[(cur + dir + colors.length) % colors.length]; saveActiveWaypoints();
    }
    public void cycleIcon(int i, int dir) {
        if (i < 0 || i >= waypoints.size()) return;
        String[] icons = {"◆", "✚", "⚑", "☠", "●", "✦", "★", "♥", "✿"};
        Waypoint w = waypoints.get(i); int cur = 0;
        for (int k = 0; k < icons.length; k++) if (icons[k].equals(w.icon)) { cur = k; break; }
        w.icon = icons[(cur + dir + icons.length) % icons.length]; saveActiveWaypoints();
    }
    public void moveToPlayer(MinecraftClient mc, int i) {
        if (mc.player == null || mc.world == null || i < 0 || i >= waypoints.size()) return;
        Waypoint w = waypoints.get(i);
        w.pos = mc.player.getPos();
        w.dimension = mc.world.getRegistryKey().getValue().toString(); saveActiveWaypoints();
    }
    @Override public void onWorldRender(WorldRenderContext ctx, MinecraftClient mc) {
        if (mc.world != null && mc.player != null) switchServerIfNeeded(mc);
        if (!isEnabled() || mc.world == null || mc.player == null) {
            for (Waypoint w : waypoints) w.screenX = w.screenY = -1;
            return;
        }
        Camera camera = ctx.camera();
        Matrix4f view = new Matrix4f().rotation(camera.getRotation().conjugate(new Quaternionf()));
        Matrix4f mvp = new Matrix4f(ctx.projectionMatrix()).mul(view);
        Vec3d cam = camera.getPos();
        float td = ctx.tickCounter().getTickDelta(false);
        int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        for (Waypoint w : waypoints) {
            w.screenX = w.screenY = -1;
            if (!w.dimension.equals(mc.world.getRegistryKey().getValue().toString())) continue;
            // Marker is projected directly above the saved block position, not fixed to a HUD corner.
            Vec3d rel = w.pos.add(0.5, 1.03, 0.5).subtract(cam);
            Vector4f v = new Vector4f((float)rel.x, (float)rel.y, (float)rel.z, 1f);
            mvp.transform(v);
            if (v.w <= 0.01f) continue;
            float nx = v.x / v.w, ny = v.y / v.w;
            if (nx < -1.15f || nx > 1.15f || ny < -1.15f || ny > 1.15f) continue;
            w.screenX = (nx * 0.5f + 0.5f) * sw;
            w.screenY = (0.5f - ny * 0.5f) * sh;
        }
    }
    /** Czaszka i flaga w foncie sa malutkie wzgledem reszty ikon - rysujemy je wieksze. */
    public static boolean isBigIcon(String icon) { return "⚑".equals(icon) || "☠".equals(icon); }

    /** Rysuje ikone waypointa wysrodkowana w (cx, cy); czaszka i flaga sa powiekszone, zeby byly proporcjonalne. */
    public static void drawIcon(DrawContext ctx, TextRenderer tr, String icon, int cx, int cy, int color) {
        float s = isBigIcon(icon) ? 1.9f : 1f;
        var m = ctx.getMatrices();
        m.push();
        m.translate(cx, cy, 0f);
        m.scale(s, s, 1f);
        UiFont.drawCentered(ctx, tr, UiFont.of(icon), 0, -4, color);
        m.pop();
    }

    @Override public void onHudRender(DrawContext ctx, MinecraftClient mc) {
        if (!isEnabled() || !labels || mc.player == null || mc.world == null) return;
        String curDim = mc.world.getRegistryKey().getValue().toString();
        for (Waypoint w : waypoints) {
            if (!w.dimension.equals(curDim) || w.screenX < 0 || w.screenY < 0) continue;
            int x = Math.round(w.screenX), y = Math.round(w.screenY);
            boolean hasName = w.name != null && !w.name.isBlank();
            String dist = String.format(java.util.Locale.ROOT, "%.1fm", mc.player.getPos().distanceTo(w.pos));
            int textW = Math.max(hasName ? UiFont.width(mc.textRenderer, w.name) : 0, UiFont.width(mc.textRenderer, dist));

            // karta w stylu TargetHUD: ikona w ramce po lewej, nazwa (biala) + dystans (szary), pasek w kolorze waypointa
            int bar = 3, padX = 9, fs = 22;
            int pw = padX + fs + 8 + textW + padX, ph = bar + 6 + fs + 7;
            int x1 = x - pw / 2, y1 = y + 4 - ph;
            HudStyle.panel(ctx, x1, y1, x1 + pw, y1 + ph, 9, bar, w.color & 0xFFFFFF, 255);
            int fx = x1 + padX, fy = y1 + bar + 6;
            HudStyle.frame(ctx, fx, fy, fx + fs, fy + fs, 6, 255);
            drawIcon(ctx, mc.textRenderer, w.icon, fx + fs / 2, fy + fs / 2, w.color | 0xFF000000);
            int tx = fx + fs + 8;
            if (hasName) {
                UiFont.draw(ctx, mc.textRenderer, w.name, tx, fy + 1, HudStyle.argb(255, HudStyle.WHITE));
                UiFont.draw(ctx, mc.textRenderer, dist, tx, fy + 12, HudStyle.argb(255, HudStyle.MUTED));
            } else {
                UiFont.draw(ctx, mc.textRenderer, dist, tx, fy + 6, HudStyle.argb(255, HudStyle.WHITE));
            }
        }
    }
}
