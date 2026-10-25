package vpmac;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/** Points the game's sprite draw ({@code SpriteRenderer$RingBuffer}'s glDrawRangeElements) at {@link GameDraws}. */
final class GameDrawsPatch extends ClassVisitor implements ClassFileTransformer {
    static final String TARGET = "zombie/core/SpriteRenderer$RingBuffer";
    boolean changed;

    GameDrawsPatch(ClassVisitor next) {
        super(Opcodes.ASM9, next);
    }

    static void install(Instrumentation inst) {
        inst.addTransformer(new GameDrawsPatch(null), true);
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (!c.getName().equals(TARGET.replace('/', '.'))) continue;
            try {
                inst.retransformClasses(c);
            } catch (Throwable t) {
                Log.warn("could not watch the game's draws: " + t);
            }
        }
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> redefined,
                            ProtectionDomain domain, byte[] bytes) {
        if (!TARGET.equals(className)) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, 0);
            GameDrawsPatch patch = new GameDrawsPatch(writer);
            reader.accept(patch, 0);
            if (!patch.changed) return null;
            Log.info("the game's sprite draws now go through GameDraws");
            return writer.toByteArray();
        } catch (Throwable t) {
            Log.warn("could not watch the game's draws: " + t);
            return null;
        }
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
        return new GameDrawsPatchMethod(super.visitMethod(access, name, desc, sig, exceptions), this);
    }
}
