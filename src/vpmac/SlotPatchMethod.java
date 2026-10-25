package vpmac;

import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/** In {@code ModelBatches.slot}: the 16 textures a batch may hold become {@link ModelSlots#count}, read each call. */
final class SlotPatchMethod extends MethodVisitor {
    private final SlotPatch patch;

    SlotPatchMethod(MethodVisitor next, SlotPatch patch) {
        super(Opcodes.ASM9, next);
        this.patch = patch;
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        if (opcode == Opcodes.BIPUSH && operand == 16) {
            super.visitFieldInsn(Opcodes.GETSTATIC, "vpmac/ModelSlots", "count", "I");
            patch.changed = true;
            return;
        }
        super.visitIntInsn(opcode, operand);
    }
}
