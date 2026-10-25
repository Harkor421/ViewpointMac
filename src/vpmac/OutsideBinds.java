package vpmac;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * Viewpoint binds character and item textures with the game's own {@code Texture.bind()}, which is
 * not Viewpoint's code, so it reaches GL as it is and binds on the real active unit. While Viewpoint
 * has a virtual unit past the real ones active, a scratch real unit stands in for it: whatever the
 * game binds there is moved onto the virtual unit, and the scratch unit gets its texture back.
 */
final class OutsideBinds {
    private OutsideBinds() {}

    static final int SCRATCH = G.REAL_UNITS - 1;
    private static boolean armed;
    private static int unit, scratchWas;

    /** Viewpoint made virtual unit {@code virtual} (past the real ones) active. */
    static void arm(int virtual) {
        settle();
        GL13.glActiveTexture(G.TEXTURE0 + SCRATCH);
        scratchWas = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        unit = virtual;
        armed = true;
    }

    /** Takes what the game bound since {@link #arm} onto the virtual unit. */
    static void settle() {
        if (!armed) return;
        armed = false;
        if (GL11.glGetInteger(G.ACTIVE_TEXTURE) != G.TEXTURE0 + SCRATCH) return;
        int now = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (now == scratchWas) return;
        G.vTex[unit][G.targetIndex(G.T_2D)] = now;
        if (now != 0) Tex.info(now).target = G.T_2D;
        GL11.glBindTexture(G.T_2D, scratchWas);
    }
}
