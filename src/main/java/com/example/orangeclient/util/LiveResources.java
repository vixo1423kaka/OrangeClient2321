package com.example.orangeclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.io.InputStream;

/**
 * Used only when the client is injected into a running game (live attach).
 * Fabric registers mod resources at startup, so an injected client has no textures.
 * This registers the GUI textures straight from the client jar. Must run on the render thread.
 * Does nothing when the textures are already available (regular mod install).
 */
public final class LiveResources {
    private static final String NS = "orangeclient";
    private static final String DIR = "textures/gui/";
    private static final String[] TEXTURES = {
            "orange", "orange_small", "glow",
            "category_combat", "category_combat_white",
            "category_player", "category_player_white",
            "category_render", "category_render_white",
            "category_movement", "category_movement_white",
            "category_fun", "category_fun_white"
    };

    private LiveResources() { }

    public static void install() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ResourceManager rm = mc.getResourceManager();
        TextureManager tm = mc.getTextureManager();
        ClassLoader cl = LiveResources.class.getClassLoader();
        int ok = 0;
        for (String name : TEXTURES) {
            Identifier id = Identifier.of(NS, DIR + name + ".png");
            if (rm.getResource(id).isPresent()) continue;
            try (InputStream in = cl.getResourceAsStream("assets/" + NS + "/" + DIR + name + ".png")) {
                if (in == null) continue;
                NativeImageBackedTexture tex = new NativeImageBackedTexture(NativeImage.read(in));
                tex.setFilter(true, false); // the .mcmeta files ask for blur=true
                tm.registerTexture(id, tex);
                ok++;
            } catch (Exception e) {
                System.err.println("[OrangeClient] could not register texture " + id + ": " + e);
            }
        }
        System.out.println("[OrangeClient] live texture registration: " + ok + "/" + TEXTURES.length);
    }
}
