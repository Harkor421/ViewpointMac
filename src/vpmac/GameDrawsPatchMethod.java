package vpmac;

import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/** The one call {@link GameDrawsPatch} moves: GL12.glDrawRangeElements(IIIIIJ)V to {@link GameDraws}. */
final class GameDrawsPatchMethod extends MethodVisitor {
    private final GameDrawsPatch patch;

    GameDrawsPatchMethod(MethodVisitor next, GameDrawsPatch patch) {
        super(Opcodes.ASM9, next);
        this.patch = patch;
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
        if (opcode == Opcodes.INVOKESTATIC && owner.equals("org/lwjgl/opengl/GL12") && name.equals("glDrawRangeElements")
            && desc.equals("(IIIIIJ)V")) {
            patch.changed = true;
            super.visitMethodInsn(Opcodes.INVOKESTATIC, "vpmac/GameDraws", name, desc, false);
            return;
        }
        super.visitMethodInsn(opcode, owner, name, desc, itf);
    }
}
