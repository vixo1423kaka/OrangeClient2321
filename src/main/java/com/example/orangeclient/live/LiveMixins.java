package com.example.orangeclient.live;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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

public final class LiveMixins {
    private enum Kind { HEAD_CANCEL, HEAD, TAIL }
    private record Patch(String owner, String name, String desc, Kind kind, String hook) { }

    private static final Patch[] PATCHES = {
        new Patch("net/minecraft/class_310", "method_1583", "()V", Kind.HEAD_CANCEL, "errorKiller"),
        new Patch("net/minecraft/class_746", "method_5773", "()V", Kind.HEAD, "killAuraTick"),
        new Patch("net/minecraft/class_746", "method_3136", "()V", Kind.HEAD, "killAuraPre"),
        new Patch("net/minecraft/class_746", "method_3136", "()V", Kind.TAIL, "killAuraPost")
    };

    private static boolean installed;
    private LiveMixins() { }

    public static synchronized void install(Instrumentation inst) {
        if (installed) { log("live mixins already installed"); return; }
        if (!inst.isRetransformClassesSupported()) {
            log("live mixins: retransform not supported (agent manifest needs Can-Retransform-Classes: true)");
            return;
        }
        Map<String, Method> hooks = new HashMap<>();
        for (Method m : Hooks.class.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && Modifier.isPublic(m.getModifiers())) hooks.put(m.getName(), m);
        }
        Map<String, List<Patch>> byClass = new HashMap<>();
        for (Patch p : PATCHES) {
            if (!hooks.containsKey(p.hook())) { log("live mixins: missing hook " + p.hook()); continue; }
            byClass.computeIfAbsent(p.owner(), k -> new ArrayList<>()).add(p);
        }
        installed = true;

        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> redefined,
                                    ProtectionDomain pd, byte[] bytes) {
                List<Patch> ps = byClass.get(className);
                if (ps == null) return null;
                try {
                    if (loader == null || Class.forName(Hooks.class.getName(), false, loader) != Hooks.class) {
                        log("live mixins: skip " + className + " (hooks not visible from its class loader)");
                        return null;
                    }
                    return patch(bytes, ps, hooks);
                } catch (Throwable t) {
                    log("live mixins: failed to patch " + className + ": " + t);
                    return null;
                }
            }
        }, true);

        int ok = 0;
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String internal = c.getName().replace('.', '/');
            if (!byClass.containsKey(internal) || !inst.isModifiableClass(c)) continue;
            try {
                inst.retransformClasses(c);
                ok++;
                log("live mixins: retransformed " + c.getName());
            } catch (Throwable t) {
                log("live mixins: retransform failed for " + c.getName() + ": " + t);
            }
        }
        log("live mixins: " + ok + "/" + byClass.size() + " target classes patched");
    }

    private static byte[] patch(byte[] bytes, List<Patch> patches, Map<String, Method> hooks) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        Set<Patch> applied = new HashSet<>();
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            private String owner;
            @Override
            public void visit(int version, int access, String name, String sig, String superName, String[] itfs) {
                owner = name;
                super.visit(version, access, name, sig, superName, itfs);
            }
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                List<Patch> mine = new ArrayList<>();
                for (Patch p : patches) if (p.name().equals(name) && p.desc().equals(desc)) mine.add(p);
                if (mine.isEmpty()) return mv;
                applied.addAll(mine);
                return new MethodPatcher(mv, owner, access, desc, mine, hooks);
            }
        }, ClassReader.EXPAND_FRAMES);
        for (Patch p : patches) if (!applied.contains(p)) log("live mixins: target not found: " + p.owner() + "." + p.name() + p.desc());
        return cw.toByteArray();
    }

    private static final class MethodPatcher extends MethodVisitor {
        private final String owner;
        private final boolean isStatic;
        private final String desc;
        private final List<Patch> patches;
        private final Map<String, Method> hooks;

        MethodPatcher(MethodVisitor mv, String owner, int access, String desc, List<Patch> patches, Map<String, Method> hooks) {
            super(Opcodes.ASM9, mv);
            this.owner = owner;
            this.isStatic = (access & Opcodes.ACC_STATIC) != 0;
            this.desc = desc;
            this.patches = patches;
            this.hooks = hooks;
            if (Type.getReturnType(desc).getSort() != Type.VOID) throw new IllegalStateException("only void targets are supported: " + owner + desc);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            for (Patch p : patches) {
                if (p.kind() == Kind.HEAD) callHook(p);
                else if (p.kind() == Kind.HEAD_CANCEL) {
                    callHook(p);
                    Label proceed = new Label();
                    mv.visitJumpInsn(Opcodes.IFEQ, proceed);
                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitLabel(proceed);
                    Object[] locals = initialLocals();
                    mv.visitFrame(Opcodes.F_NEW, locals.length, locals, 0, new Object[0]);
                }
            }
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.RETURN) for (Patch p : patches) if (p.kind() == Kind.TAIL) callHook(p);
            super.visitInsn(opcode);
        }

        private void callHook(Patch p) {
            Method hook = hooks.get(p.hook());
            int hookArgs = hook.getParameterCount();
            Type[] args = Type.getArgumentTypes(desc);
            int slot = 0;
            int loaded = 0;
            if (!isStatic) {
                if (hookArgs > 0) { mv.visitVarInsn(Opcodes.ALOAD, 0); loaded++; }
                slot = 1;
            }
            for (int i = 0; loaded < hookArgs && i < args.length; i++, loaded++) {
                mv.visitVarInsn(args[i].getOpcode(Opcodes.ILOAD), slot);
                slot += args[i].getSize();
            }
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(Hooks.class), hook.getName(), Type.getMethodDescriptor(hook), false);
        }

        private Object[] initialLocals() {
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

    static void log(String line) {
        try {
            Path p = Path.of(System.getProperty("java.io.tmpdir"), "orange-jvm-agent.log");
            Files.writeString(p, Instant.now() + " " + line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
    }
}
