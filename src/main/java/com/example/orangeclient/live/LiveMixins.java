package com.example.orangeclient.live;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;
import java.time.Instant;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Runtime hooks for Minecraft 1.21.4 intermediary classes. All hooks live in MinecraftClient (class_310). */
public final class LiveMixins {
    private static final String HOOKS = "com/example/orangeclient/live/Hooks";
    private static final String CLIENT = "net/minecraft/class_310";
    /** KillAura attack/restore hook at the end of MinecraftClient.tick(); dropped automatically if the JVM rejects it. */
    private static volatile boolean withTail = true;

    private LiveMixins() {}

    public static void log(String message) {
        try {
            Path p = Path.of(System.getProperty("java.io.tmpdir"), "orange-jvm-agent.log");
            Files.writeString(p, Instant.now() + " " + message + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) { }
    }

    private static String describe(Throwable t) {
        return t + (t.getCause() != null ? " (cause: " + t.getCause() + ")" : "");
    }

    public static void install(Instrumentation instrumentation) {
        if (!instrumentation.isRetransformClassesSupported()) {
            log("live mixins: retransformation unsupported by this JVM");
            return;
        }

        ClassFileTransformer transformer = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (!CLIENT.equals(className)) return null;
                try {
                    ClassReader reader = new ClassReader(classfileBuffer);
                    // Do not ask ASM to resolve Minecraft classes to recompute stack frames.
                    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                    int[] inserted = {0};
                    boolean tail = withTail;
                    ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                         String signature, String[] exceptions) {
                            MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
                            if (base == null) return null;

                            // MinecraftClient.doItemUse(): ErrorKiller, cancellable
                            if (name.equals("method_1583") && descriptor.equals("()V")) {
                                inserted[0]++;
                                return new MethodVisitor(Opcodes.ASM9, base) {
                                    @Override public void visitCode() {
                                        super.visitCode();
                                        super.visitVarInsn(Opcodes.ALOAD, 0);
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "errorKiller",
                                                "(Ljava/lang/Object;)Z", false);
                                        Label proceed = new Label();
                                        super.visitJumpInsn(Opcodes.IFEQ, proceed);
                                        super.visitInsn(Opcodes.RETURN);
                                        super.visitLabel(proceed);
                                        super.visitFrame(Opcodes.F_NEW, 1,
                                                new Object[]{"net/minecraft/class_310"}, 0, new Object[0]);
                                    }
                                };
                            }

                            // MinecraftClient.tick(): KillAura rotation at the start, attack + restore at the end
                            if (name.equals("method_1574") && descriptor.equals("()V")) {
                                inserted[0]++;
                                return new MethodVisitor(Opcodes.ASM9, base) {
                                    @Override public void visitCode() {
                                        super.visitCode();
                                        super.visitVarInsn(Opcodes.ALOAD, 0);
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "killAuraTick",
                                                "(Ljava/lang/Object;)V", false);
                                    }
                                    @Override public void visitInsn(int opcode) {
                                        if (tail && opcode == Opcodes.RETURN) {
                                            super.visitVarInsn(Opcodes.ALOAD, 0);
                                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "killAuraPost",
                                                    "(Ljava/lang/Object;)V", false);
                                        }
                                        super.visitInsn(opcode);
                                    }
                                };
                            }
                            return base;
                        }
                    };
                    reader.accept(visitor, ClassReader.EXPAND_FRAMES);
                    if (inserted[0] == 0) {
                        log("skip " + className + ": expected methods not found");
                        return null;
                    }
                    log("prepared hooks for " + className + " (" + inserted[0] + " methods, killAuraPost tail hook="
                            + tail + ")");
                    return writer.toByteArray();
                } catch (Throwable error) {
                    log("failed to patch " + className + ": " + describe(error));
                    return null;
                }
            }
        };

        try {
            instrumentation.addTransformer(transformer, true);
            int targets = 0;
            int patched = 0;
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (!loaded.getName().equals("net.minecraft.class_310")) continue;
                targets++;
                if (!instrumentation.isModifiableClass(loaded)) {
                    log("skip " + loaded.getName() + ": class is not modifiable");
                    continue;
                }
                try {
                    instrumentation.retransformClasses(loaded);
                    patched++;
                    log("live mixins: retransformed " + loaded.getName() + " (killAuraPost tail hook=" + withTail + ")");
                } catch (Throwable error) {
                    log("failed to retransform " + loaded.getName() + ": " + describe(error));
                    if (withTail) {
                        // The JVM rejected the class with the tail hook. Retry without it so ErrorKiller still works.
                        withTail = false;
                        try {
                            instrumentation.retransformClasses(loaded);
                            patched++;
                            log("live mixins: retransformed " + loaded.getName() + " WITHOUT killAuraPost tail hook");
                        } catch (Throwable retry) {
                            log("retry without tail hook failed: " + describe(retry));
                        }
                    }
                }
            }
            if (targets == 0) log("target not found: Minecraft intermediary classes were not loaded");
            log("live mixins: " + patched + "/" + targets + " target classes patched");
        } catch (Throwable error) {
            log("install failed: " + describe(error));
        }
    }
}
