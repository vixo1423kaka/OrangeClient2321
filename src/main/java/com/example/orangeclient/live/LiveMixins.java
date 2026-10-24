package com.example.orangeclient.live;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Runtime hooks for Minecraft 1.21.4 (intermediary names). Replaces the Mixin injections after a live attach.
 * Each class is patched independently: if the JVM rejects one, the others still work.
 */
public final class LiveMixins {
    private static final String HOOKS = "com/example/orangeclient/live/Hooks";
    private static final String CLIENT = "net/minecraft/class_310";
    private static final String OBJ = "Ljava/lang/Object;";
    /** KillAura attack/restore hook at the end of MinecraftClient.tick(); dropped automatically if the JVM rejects it. */
    private static volatile boolean withTail = true;

    private interface Head { void emit(MethodVisitor mv, Ctx c); }
    private interface Ret { void emit(MethodVisitor mv, int opcode, Ctx c); }

    private record Ctx(String owner, boolean isStatic, String desc) {
        /** Local variable types at method entry, for the stack map frame after an injected early return. */
        Object[] locals() {
            List<Object> l = new ArrayList<>();
            if (!isStatic) l.add(owner);
            for (Type t : Type.getArgumentTypes(desc)) {
                switch (t.getSort()) {
                    case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> l.add(Opcodes.INTEGER);
                    case Type.FLOAT -> l.add(Opcodes.FLOAT);
                    case Type.LONG -> l.add(Opcodes.LONG);
                    case Type.DOUBLE -> l.add(Opcodes.DOUBLE);
                    default -> l.add(t.getInternalName());
                }
            }
            return l.toArray();
        }
    }

    private record MP(String owner, String name, String desc, Head head, Ret ret) { }

    // ---- bytecode helpers -------------------------------------------------

    private static void call(MethodVisitor mv, String hook, String desc, int... refSlots) {
        for (int s : refSlots) mv.visitVarInsn(Opcodes.ALOAD, s);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, hook, desc, false);
    }

    private static String boolDesc(int n) { return "(" + OBJ.repeat(n) + ")Z"; }

    private static void frame(MethodVisitor mv, Ctx c, Label l, Object... stack) {
        mv.visitLabel(l);
        Object[] loc = c.locals();
        mv.visitFrame(Opcodes.F_NEW, loc.length, loc, stack.length, stack);
    }

    /** HEAD: if hook(args) returns true, return immediately (void method). */
    private static Head cancelVoid(String hook, int... slots) {
        return (mv, c) -> {
            call(mv, hook, boolDesc(slots.length), slots);
            Label go = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, go);
            mv.visitInsn(Opcodes.RETURN);
            frame(mv, c, go);
        };
    }

    /** HEAD: if hook(args) returns true, return false (boolean method). */
    private static Head cancelFalse(String hook, int... slots) {
        return (mv, c) -> {
            call(mv, hook, boolDesc(slots.length), slots);
            Label go = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, go);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.IRETURN);
            frame(mv, c, go);
        };
    }

    /** HEAD: hook(args) returns an Object; if not null it is returned from the (object-returning) method. */
    private static Head cancelObject(String hook, String castTo, int... slots) {
        return (mv, c) -> {
            call(mv, hook, "(" + OBJ.repeat(slots.length) + ")" + OBJ, slots);
            mv.visitInsn(Opcodes.DUP);
            Label keep = new Label();
            mv.visitJumpInsn(Opcodes.IFNULL, keep);
            mv.visitTypeInsn(Opcodes.CHECKCAST, castTo);
            mv.visitInsn(Opcodes.ARETURN);
            frame(mv, c, keep, "java/lang/Object");
            mv.visitInsn(Opcodes.POP);
        };
    }

    // ---- patch table ------------------------------------------------------

    private static final List<MP> PATCHES = List.of(
            // MinecraftClient.doItemUse(): ErrorKiller (cancellable)
            new MP(CLIENT, "method_1583", "()V", cancelVoid("errorKiller", 0), null),

            // MinecraftClient.tick(): KillAura rotation at the start, attack + camera restore at the end
            new MP(CLIENT, "method_1574", "()V",
                    (mv, c) -> call(mv, "killAuraTick", "(" + OBJ + ")V", 0),
                    (mv, op, c) -> {
                        if (withTail && op == Opcodes.RETURN) call(mv, "killAuraPost", "(" + OBJ + ")V", 0);
                    }),

            // GameRenderer.findCrosshairTarget(Entity,double,double,float): Hitbox + AntiTrap, modifies the return value
            new MP("net/minecraft/class_757", "method_56153",
                    "(Lnet/minecraft/class_1297;DDF)Lnet/minecraft/class_239;", null,
                    (mv, op, c) -> {
                        if (op != Opcodes.ARETURN) return;
                        mv.visitVarInsn(Opcodes.ALOAD, 0);   // stack: ret, this
                        mv.visitInsn(Opcodes.SWAP);          // stack: this, ret
                        mv.visitVarInsn(Opcodes.ALOAD, 1);   // camera
                        mv.visitVarInsn(Opcodes.DLOAD, 2);   // blockRange
                        mv.visitVarInsn(Opcodes.DLOAD, 4);   // entityRange
                        mv.visitVarInsn(Opcodes.FLOAD, 6);   // tickDelta
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "crosshair",
                                "(" + OBJ + OBJ + OBJ + "DDF)" + OBJ, false);
                        mv.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/class_239");
                    }),

            // EntityRenderDispatcher.shouldRender(Entity,Frustum,double,double,double): AntiTrap hides frames/paintings/minecarts
            new MP("net/minecraft/class_898", "method_3950",
                    "(Lnet/minecraft/class_1297;Lnet/minecraft/class_4604;DDD)Z",
                    cancelFalse("antiTrapHides", 1), null),

            // EntityRenderDispatcher.render(Entity,double,double,double,float,MatrixStack,VertexConsumerProvider,int)
            new MP("net/minecraft/class_898", "method_62424",
                    "(Lnet/minecraft/class_1297;DDDFLnet/minecraft/class_4587;Lnet/minecraft/class_4597;I)V",
                    cancelVoid("antiTrapHides", 1), null),

            // ClientPlayerInteractionManager.interactBlock(player,hand,hit): AntiTrap NoBlockGui
            new MP("net/minecraft/class_636", "method_2896",
                    "(Lnet/minecraft/class_746;Lnet/minecraft/class_1268;Lnet/minecraft/class_3965;)Lnet/minecraft/class_1269;",
                    cancelObject("antiTrapNoGui", "net/minecraft/class_1269", 0, 1, 2, 3), null),

            // ClientPlayerEntity.pushOutOfBlocks(double,double): NoPush > Blocks (cancellable)
            new MP("net/minecraft/class_746", "method_30673", "(DD)V", cancelVoid("noBlockPush", 0), null),

            // Entity.pushAwayFrom(Entity): NoPush > Entities (remember the local player's velocity, restore it afterwards)
            new MP("net/minecraft/class_1297", "method_5697", "(Lnet/minecraft/class_1297;)V",
                    (mv, c) -> call(mv, "pushHead", "(" + OBJ + OBJ + ")V", 0, 1),
                    (mv, op, c) -> {
                        if (op == Opcodes.RETURN) call(mv, "pushReturn", "()V");
                    }),

            // Entity.updateMovementInFluid(TagKey,double): NoPush > Liquids
            new MP("net/minecraft/class_1297", "method_5692", "(Lnet/minecraft/class_6862;D)Z",
                    (mv, c) -> call(mv, "fluidHead", "(" + OBJ + ")V", 0),
                    (mv, op, c) -> {
                        if (op == Opcodes.IRETURN) call(mv, "fluidReturn", "()V");
                    })
    );

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
        Map<String, List<MP>> byClass = new HashMap<>();
        for (MP p : PATCHES) byClass.computeIfAbsent(p.owner(), k -> new ArrayList<>()).add(p);

        ClassFileTransformer transformer = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (className == null) return null;
                List<MP> mine = byClass.get(className);
                if (mine == null) return null;
                try {
                    ClassReader reader = new ClassReader(classfileBuffer);
                    // Do not ask ASM to resolve Minecraft classes to recompute stack frames.
                    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                    Set<MP> applied = new HashSet<>();
                    ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                         String signature, String[] exceptions) {
                            MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
                            if (base == null) return null;
                            for (MP p : mine) {
                                if (!p.name().equals(name) || !p.desc().equals(descriptor)) continue;
                                applied.add(p);
                                Ctx ctx = new Ctx(className, (access & Opcodes.ACC_STATIC) != 0, descriptor);
                                return new MethodVisitor(Opcodes.ASM9, base) {
                                    @Override public void visitCode() {
                                        super.visitCode();
                                        if (p.head() != null) p.head().emit(mv, ctx);
                                    }
                                    @Override public void visitInsn(int opcode) {
                                        if (p.ret() != null && opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                                            p.ret().emit(mv, opcode, ctx);
                                        }
                                        super.visitInsn(opcode);
                                    }
                                };
                            }
                            return base;
                        }
                    };
                    reader.accept(visitor, ClassReader.EXPAND_FRAMES);
                    for (MP p : mine) {
                        if (!applied.contains(p)) log("target not found: " + p.owner() + "." + p.name() + p.desc());
                    }
                    if (applied.isEmpty()) return null;
                    log("prepared hooks for " + className + " (" + applied.size() + " methods, killAuraPost tail hook="
                            + withTail + ")");
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
                String internal = loaded.getName().replace('.', '/');
                if (!byClass.containsKey(internal)) continue;
                targets++;
                if (!instrumentation.isModifiableClass(loaded)) {
                    log("skip " + loaded.getName() + ": class is not modifiable");
                    continue;
                }
                try {
                    instrumentation.retransformClasses(loaded);
                    patched++;
                    log("live mixins: retransformed " + loaded.getName());
                } catch (Throwable error) {
                    log("failed to retransform " + loaded.getName() + ": " + describe(error));
                    if (internal.equals(CLIENT) && withTail) {
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
            if (targets < byClass.size()) {
                log("note: " + (byClass.size() - targets) + " target class(es) are not loaded yet and were not patched");
            }
            log("live mixins: " + patched + "/" + byClass.size() + " target classes patched");
        } catch (Throwable error) {
            log("install failed: " + describe(error));
        }
    }
}