package com.example.orangeclient.live;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Runtime hooks for Minecraft 1.21.4 intermediary classes. */
public final class LiveMixins {
    private static final String HOOKS = "com/example/orangeclient/live/Hooks";
    private static final AtomicInteger PATCHED = new AtomicInteger();

    private LiveMixins() {}

    public static void log(String message) {
        try {
            Path p = Path.of(System.getProperty("java.io.tmpdir"), "orange-jvm-agent.log");
            Files.writeString(p, Instant.now() + " " + message + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) { }
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
                if (className == null) return null;
                boolean client = className.equals("net/minecraft/class_310");
                boolean player = className.equals("net/minecraft/class_746");
                if (!client && !player) return null;

                try {
                    ClassReader reader = new ClassReader(classfileBuffer);
                    // Do not ask ASM to resolve Minecraft classes to recompute stack frames.
                    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                    int[] inserted = {0};
                    ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                         String signature, String[] exceptions) {
                            MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
                            if (base == null) return null;

                            if (client && name.equals("method_1583") && descriptor.equals("()V")) {
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

                            if (client && name.equals("method_1574") && descriptor.equals("()V")) {
                                inserted[0]++;
                                return new MethodVisitor(Opcodes.ASM9, base) {
                                    @Override public void visitCode() {
                                        super.visitCode();
                                        super.visitVarInsn(Opcodes.ALOAD, 0);
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "killAuraTick",
                                                "(Ljava/lang/Object;)V", false);
                                    }
                                };
                            }

                            if (player && name.equals("method_3136") && descriptor.equals("()V")) {
                                inserted[0]++;
                                return new MethodVisitor(Opcodes.ASM9, base) {
                                    @Override public void visitCode() {
                                        super.visitCode();
                                        super.visitVarInsn(Opcodes.ALOAD, 0);
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "killAuraPre",
                                                "(Ljava/lang/Object;)V", false);
                                    }
                                    @Override public void visitInsn(int opcode) {
                                        if (opcode == Opcodes.RETURN) {
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
                    PATCHED.incrementAndGet();
                    log("prepared hooks for " + className + " (" + inserted[0] + " methods)");
                    return writer.toByteArray();
                } catch (Throwable error) {
                    log("failed to patch " + className + ": " + error);
                    return null;
                }
            }
        };

        try {
            instrumentation.addTransformer(transformer, true);
            int targets = 0;
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                String name = loaded.getName();
                if (name.equals("net.minecraft.class_310") || name.equals("net.minecraft.class_746")) {
                    targets++;
                    if (!instrumentation.isModifiableClass(loaded)) {
                        log("skip " + name + ": class is not modifiable");
                        continue;
                    }
                    try {
                        instrumentation.retransformClasses(loaded);
                        log("live mixins: retransformed " + name);
                    } catch (Throwable error) {
                        log("failed to retransform " + name + ": " + error);
                    }
                }
            }
            if (targets == 0) log("target not found: Minecraft intermediary classes were not loaded");
            log("live mixins: " + PATCHED.get() + "/" + targets + " target classes patched");
        } catch (Throwable error) {
            log("install failed: " + error);
        }
    }
}
