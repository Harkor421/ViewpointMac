package vpmac;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;

/**
 * Points Viewpoint's calls into LWJGL's OpenGL classes at {@link G} wherever G has a method of
 * the same name and descriptor, and its reads of {@code GLCapabilities} fields at G's
 * {@code cap_} methods. Only Viewpoint's classes and its add-ons' ({@link Addons}) change; the
 * game's own GL calls don't. In add-ons, reflective lookups of GL methods go through {@link Reflect}.
 */
final class Remap implements ClassFileTransformer {
    static final String SHIM = "vpmac/G";
    static final String REFLECT = "vpmac/Reflect";
    static final String LOOKUP = "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;";
    static final String CAPS = "org/lwjgl/opengl/GLCapabilities";

    private final Set<String> shims = new HashSet<>();
    int changedClasses;

    Remap() {
        for (Method m : G.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || !Modifier.isStatic(m.getModifiers())) continue;
            shims.add(m.getName() + descriptor(m));
        }
    }

    static boolean applies(String className) {
        return Addons.covers(className);
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> redefined,
                            ProtectionDomain domain, byte[] bytes) {
        if (!applies(className)) return null;
        try {
            return rewrite(bytes);
        } catch (Throwable t) {
            Log.warn("could not rewrite " + className + ": " + t);
            return null;
        }
    }

    byte[] rewrite(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        boolean[] changed = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, sig, exceptions)) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean itf) {
                        if (opcode == Opcodes.INVOKESTATIC && owner.startsWith("org/lwjgl/opengl/") && shims.contains(mName + mDesc)) {
                            changed[0] = true;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, SHIM, mName, mDesc, false);
                            return;
                        }
                        if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals("java/lang/Class") && mDesc.equals(LOOKUP)
                            && (mName.equals("getDeclaredMethod") || mName.equals("getMethod"))) {
                            changed[0] = true;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, REFLECT, mName, "(Ljava/lang/Class;" + LOOKUP.substring(1), false);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, mName, mDesc, itf);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fName, String fDesc) {
                        String cap = "cap_" + fName + "(L" + CAPS + ";)Z";
                        if (opcode == Opcodes.GETFIELD && owner.equals(CAPS) && shims.contains(cap)) {
                            changed[0] = true;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, SHIM, "cap_" + fName, "(L" + CAPS + ";)Z", false);
                            return;
                        }
                        super.visitFieldInsn(opcode, owner, fName, fDesc);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String iName, String iDesc, Handle bsm, Object... args) {
                        for (int i = 0; i < args.length; i++) {
                            if (args[i] instanceof Handle h && h.getTag() == Opcodes.H_INVOKESTATIC
                                && h.getOwner().startsWith("org/lwjgl/opengl/") && shims.contains(h.getName() + h.getDesc())) {
                                args[i] = new Handle(Opcodes.H_INVOKESTATIC, SHIM, h.getName(), h.getDesc(), false);
                                changed[0] = true;
                            }
                        }
                        super.visitInvokeDynamicInsn(iName, iDesc, bsm, args);
                    }
                };
            }
        }, 0);
        if (!changed[0]) return null;
        changedClasses++;
        return writer.toByteArray();
    }

    private static String descriptor(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) sb.append(typeDesc(p));
        return sb.append(')').append(typeDesc(m.getReturnType())).toString();
    }

    private static String typeDesc(Class<?> c) {
        if (c == void.class) return "V";
        if (c == int.class) return "I";
        if (c == long.class) return "J";
        if (c == float.class) return "F";
        if (c == double.class) return "D";
        if (c == boolean.class) return "Z";
        if (c == byte.class) return "B";
        if (c == short.class) return "S";
        if (c == char.class) return "C";
        if (c.isArray()) return "[" + typeDesc(c.getComponentType());
        return "L" + c.getName().replace('.', '/') + ";";
    }
}
