package com.example.attach;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Live agent for Minecraft 1.21.4 running under Fabric Loader (Knot).
 * Adds the bundled client jar to the game's class path and invokes its client
 * initializer on the Minecraft thread. Mixins cannot be retroactively applied.
 */
public final class OrangeAgent {
    private static final String[] MC_CLIENT = {"net.minecraft.class_310", "net.minecraft.client.MinecraftClient"};
    private static final String CLIENT_MAIN = "com.example.orangeclient.OrangeClient";
    private static final String LICENSE = "com.example.orangeclient.license.LicenseManager";
    private static final String PAYLOAD_PREFIX = "payload/";
    private static final Object LOCK = new Object();
    private static volatile boolean done;

    private OrangeAgent() {}

    public static void premain(String args, Instrumentation inst) { start("premain", args, inst); }
    public static void agentmain(String args, Instrumentation inst) { start("agentmain", args, inst); }

    private static void start(String phase, String args, Instrumentation inst) {
        Thread t = new Thread(() -> run(phase, args, inst), "OrangeClient-Attach");
        t.setDaemon(true);
        t.start();
    }

    private static void run(String phase, String args, Instrumentation inst) {
        try {
            synchronized (LOCK) {
                if (done) { result("OK already injected"); return; }
                Class<?> mc = findClass(inst);
                if (mc == null) { result("FAILED Minecraft client class not found (is this the game process, 1.21.4?)"); return; }
                ClassLoader game = mc.getClassLoader();
                log("phase=" + phase + " java=" + System.getProperty("java.version")
                        + " mc=" + mc.getName() + " loader=" + game);

                Class<?> launcherBase;
                try {
                    launcherBase = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase", false, game);
                } catch (ClassNotFoundException e) {
                    result("FAILED Fabric Loader not found in target; launch the instance with Fabric 1.21.4");
                    return;
                }

                Path jar = extractPayload();
                addToKnot(launcherBase, game, jar);

                Class<?> main = Class.forName(CLIENT_MAIN, true, game);
                Object instance = main.getDeclaredConstructor().newInstance();
                Method init = main.getMethod("onInitializeClient");
                Executor gameThread = (Executor) findMinecraftInstance(mc);

                CompletableFuture<Void> initDone = new CompletableFuture<>();
                gameThread.execute(() -> {
                    try { init.invoke(instance); initDone.complete(null); }
                    catch (Throwable t) { initDone.completeExceptionally(t.getCause() != null ? t.getCause() : t); }
                });
                initDone.get(30, java.util.concurrent.TimeUnit.SECONDS);
                done = true;

                String key = parseKey(args);
                if (key.isEmpty()) { result("OK injected; no license key given"); return; }
                Class<?> lic = Class.forName(LICENSE, true, game);
                Object future = lic.getMethod("validate", String.class).invoke(null, key);
                Object err = ((CompletableFuture<?>) future).get(20, java.util.concurrent.TimeUnit.SECONDS);
                result(err == null ? "OK injected; license valid" : "OK injected; license rejected: " + err);
            }
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            log("error: " + c);
            result("FAILED " + c.getClass().getSimpleName() + ": " + c.getMessage());
        }
    }

    private static Class<?> findClass(Instrumentation inst) {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            for (String n : MC_CLIENT) if (c.getName().equals(n)) return c;
        }
        return null;
    }

    private static Object findMinecraftInstance(Class<?> mc) throws Exception {
        for (Field f : mc.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == mc) {
                f.setAccessible(true);
                Object o = f.get(null);
                if (o != null) return o;
            }
        }
        throw new IllegalStateException("MinecraftClient singleton not found");
    }

    private static Path extractPayload() throws Exception {
        Path agentJar = Path.of(OrangeAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (JarFile jf = new JarFile(agentJar.toFile())) {
            ZipEntry entry = null;
            var en = jf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.getName().startsWith(PAYLOAD_PREFIX) && e.getName().endsWith(".jar")) { entry = e; break; }
            }
            if (entry == null) throw new IllegalStateException("payload jar missing in launcher; build the client first (gradlew build)");
            Path dir = Files.createTempDirectory("orangeclient-");
            Path out = dir.resolve("orange-client-inner.jar");
            try (InputStream in = jf.getInputStream(entry)) { Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING); }
            out.toFile().deleteOnExit();
            return out;
        }
    }

    private static void addToKnot(Class<?> launcherBase, ClassLoader game, Path jar) throws Exception {
        try {
            Object launcher = launcherBase.getMethod("getLauncher").invoke(null);
            Method add = launcher.getClass().getMethod("addToClassPath", Path.class, String[].class);
            add.invoke(launcher, jar, new String[0]);
            log("added via FabricLauncher.addToClassPath");
            return;
        } catch (ReflectiveOperationException first) {
            log("addToClassPath failed: " + first + "; trying addUrlFwd");
        }
        for (Class<?> c = game.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("addUrlFwd", URL.class);
                m.setAccessible(true);
                m.invoke(game, jar.toUri().toURL());
                log("added via addUrlFwd");
                return;
            } catch (NoSuchMethodException ignored) { }
        }
        throw new IllegalStateException("Could not add Orange Client to the Knot class path");
    }

    private static String parseKey(String args) {
        if (args == null) return "";
        return args.startsWith("key=") ? args.substring(4).trim() : "";
    }

    private static void result(String line) {
        try {
            Files.writeString(tmp("orange-agent-result.txt"), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (Exception ignored) { }
        log("result: " + line);
    }

    private static void log(String line) {
        try {
            Files.writeString(tmp("orange-jvm-agent.log"), Instant.now() + " " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
    }

    private static Path tmp(String name) { return Path.of(System.getProperty("java.io.tmpdir"), name); }
}
