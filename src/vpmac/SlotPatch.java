package vpmac;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Viewpoint gives each batch of characters and items up to 16 textures ({@code ModelBatches.slot});
 * on the Mac the model shaders keep fewer of them ({@link Glsl#CUT_ARRAYS}, {@link ModelSlots}), so a
 * batch stops at {@link ModelSlots#count}, read each time, and no model reads another's texture.
 */
final class SlotPatch extends ClassVisitor implements ClassFileTransformer {
    static final String TARGET = "viewpoint/render/ModelBatches";
    boolean changed;

    SlotPatch(ClassVisitor next) {
        super(Opcodes.ASM9, next);
    }

    /** Patches ModelBatches from now on, and now if it is already loaded. */
    static void install(Instrumentation inst) {
        inst.addTransformer(new SlotPatch(null), true);
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (!c.getName().equals(TARGET.replace('/', '.'))) continue;
            try {
                inst.retransformClasses(c);
            } catch (Throwable t) {
                Log.warn("could not limit Viewpoint's model batches: " + t);
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
            SlotPatch patch = new SlotPatch(writer);
            reader.accept(patch, 0);
            if (!patch.changed) {
                Log.warn("Viewpoint's model batches look different; their 16 textures stay");
                return null;
            }
            Log.info("Viewpoint's model batches now hold " + ModelSlots.count + " textures");
            return writer.toByteArray();
        } catch (Throwable t) {
            Log.warn("could not limit Viewpoint's model batches: " + t);
            return null;
        }
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, desc, sig, exceptions);
        return name.equals("slot") && desc.equals("(I)I") ? new SlotPatchMethod(mv, this) : mv;
    }
}
