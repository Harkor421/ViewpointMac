package vpmac;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;

/**
 * The game's own sprite and UI draws ({@code SpriteRenderer$RingBuffer.drawElements}), which
 * {@link GameDrawsPatch} points here: counted, and, on request ({@code gamestate} in dump.txt),
 * the GL state the first of them meets after a Viewpoint frame is logged. With Viewpoint on, those
 * draws cost the Mac's driver far more than in the isometric view; this is how to see why.
 */
public final class GameDraws {
    private GameDraws() {}

    /** Set by {@link Draw#begin}: a Viewpoint draw happened since the game's last one. */
    public static volatile boolean viewpointDrew;
    static volatile boolean dumpNext;
    private static long draws, afterViewpoint, windowStart;

    public static void glDrawRangeElements(int mode, int start, int end, int count, int type, long indices) {
        if (viewpointDrew) {
            viewpointDrew = false;
            afterViewpoint++;
            if (dumpNext) {
                dumpNext = false;
                dumpState();
            }
            DrawHistogram.frame();
        }
        if (DrawHistogram.recording) DrawHistogram.record(count);
        draws++;
        long now = System.nanoTime();
        if (windowStart == 0) windowStart = now;
        if (now - windowStart >= 5_000_000_000L) {
            double s = (now - windowStart) / 1e9;
            Log.info(String.format("game draws: %.0f per second, %.0f per Viewpoint frame", draws / s, afterViewpoint == 0 ? 0 : (double) draws / afterViewpoint));
            draws = afterViewpoint = 0;
            windowStart = now;
        }
        GL12.glDrawRangeElements(mode, start, end, count, type, indices);
    }

    /** Everything a draw's cost on the Mac depends on: program, targets, caps, arrays, textures. */
    private static void dumpState() {
        StringBuilder b = new StringBuilder("GL state at the game's first draw after a Viewpoint frame:");
        b.append("\n   program ").append(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM))
            .append(", draw framebuffer ").append(GL11.glGetInteger(G.DRAW_FRAMEBUFFER_BINDING))
            .append(", array buffer ").append(GL11.glGetInteger(G.ARRAY_BUFFER_BINDING))
            .append(", element buffer ").append(GL11.glGetInteger(0x8895))
            .append(", pack/unpack ").append(GL11.glGetInteger(0x88ED)).append('/').append(GL11.glGetInteger(0x88EF));
        int[] vp = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
        b.append(", viewport ").append(vp[2]).append('x').append(vp[3]);
        b.append("\n   draw buffers");
        for (int i = 0; i < 8; i++) b.append(' ').append(Integer.toHexString(GL11.glGetInteger(Fbo.DRAW_BUFFER0 + i)));
        b.append("\n   enabled:");
        int[] caps = {GL11.GL_BLEND, GL11.GL_DEPTH_TEST, GL11.GL_CULL_FACE, GL11.GL_SCISSOR_TEST, GL11.GL_STENCIL_TEST, GL11.GL_ALPHA_TEST,
            GL11.GL_VERTEX_ARRAY, GL11.GL_COLOR_ARRAY, GL11.GL_TEXTURE_COORD_ARRAY, GL11.GL_TEXTURE_2D};
        String[] names = {"blend", "depth", "cull", "scissor", "stencil", "alpha", "vertexArray", "colorArray", "texcoordArray", "texture2D"};
        for (int i = 0; i < caps.length; i++) if (GL11.glIsEnabled(caps[i])) b.append(' ').append(names[i]);
        b.append("\n   vertex attributes:");
        for (int i = 0; i < 16; i++) {
            if (GL20.glGetVertexAttribi(i, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED) == 0) continue;
            b.append(" [").append(i).append(" buffer ").append(GL20.glGetVertexAttribi(i, GL15.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING))
                .append(" size ").append(GL20.glGetVertexAttribi(i, GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE))
                .append(" divisor ").append(GL20.glGetVertexAttribi(i, 0x88FE)).append(']');
        }
        int active = GL11.glGetInteger(G.ACTIVE_TEXTURE);
        b.append("\n   textures (unit: 2D/array/3D/cube):");
        int bound = 0;
        for (int u = 0; u < G.REAL_UNITS; u++) {
            GL13.glActiveTexture(G.TEXTURE0 + u);
            int t2 = GL11.glGetInteger(0x8069), ta = GL11.glGetInteger(0x8C1D), t3 = GL11.glGetInteger(0x806A), tc = GL11.glGetInteger(0x8514);
            if (t2 == 0 && ta == 0 && t3 == 0 && tc == 0) continue;
            bound++;
            b.append(' ').append(u).append(':').append(t2).append('/').append(ta).append('/').append(t3).append('/').append(tc);
        }
        GL13.glActiveTexture(active);
        b.append(" (").append(bound).append(" units hold something)");
        Log.info(b.toString());
    }
}
