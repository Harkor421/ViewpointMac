package vpmac;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Framebuffer edits by name (4.5) and clears of a single draw buffer (3.0). */
final class Fbo {
    private Fbo() {}

    static final int DRAW_BUFFER0 = 0x8825, MAX_DRAW_BUFFERS = 8;

    /** Textures attached to each framebuffer Viewpoint made, so drawing into one marks them changed. */
    private static final java.util.Map<Integer, java.util.Map<Integer, Integer>> attached = new java.util.HashMap<>();
    static int drawFbo, readFbo;

    static void bound(int target, int fb) {
        if (target == G.FRAMEBUFFER || target == G.DRAW_FRAMEBUFFER) drawFbo = fb;
        if (target == G.FRAMEBUFFER || target == G.READ_FRAMEBUFFER) readFbo = fb;
    }

    static void attach(int target, int attachment, int texture) {
        int fb = target == G.READ_FRAMEBUFFER ? readFbo : drawFbo;
        attachTo(fb, attachment, texture);
    }

    static void attachTo(int fb, int attachment, int texture) {
        if (fb == 0) return;
        java.util.Map<Integer, Integer> a = attached.computeIfAbsent(fb, k -> new java.util.HashMap<>());
        if (texture == 0) a.remove(attachment);
        else a.put(attachment, texture);
        touchedFbo = -1;
    }

    /** Attachment point -> texture, of a framebuffer Viewpoint made; null when none is known. */
    static java.util.Map<Integer, Integer> attachments(int fb) {
        return attached.get(fb);
    }

    static void forget(int fb) {
        attached.remove(fb);
        touchedFbo = -1;
    }

    /** A texture record went away: {@link #touch} must look its framebuffer up again. */
    static void infosChanged() {
        touchedFbo = -1;
    }

    /** The draw framebuffer {@link #touch} last looked up, and its textures' records. */
    private static int touchedFbo = -1;
    private static Tex.Info[] touchedInfos = new Tex.Info[0];

    /** Marks what the current draw framebuffer holds as changed (after a draw, clear or blit into it). */
    static void touch() {
        if (drawFbo != touchedFbo) {
            java.util.Map<Integer, Integer> a = attached.get(drawFbo);
            java.util.List<Tex.Info> infos = new java.util.ArrayList<>();
            if (a != null) for (int t : a.values()) infos.add(Tex.info(t));
            touchedInfos = infos.toArray(new Tex.Info[0]);
            touchedFbo = drawFbo;
        }
        for (Tex.Info ti : touchedInfos) Tex.touch(ti);
    }

    private static final java.util.Set<String> explained = new java.util.HashSet<>();

    /** Logs, once per texture and level, why an attachment leaves the framebuffer incomplete. */
    static void explainIfIncomplete(int target, int attachment, int texTarget, int texture, int level) {
        if (texture == 0) return;
        int status = GL30.glCheckFramebufferStatus(target);
        // Missing attachments and draw buffers come and go while a framebuffer is being built.
        if (status == GL30.GL_FRAMEBUFFER_COMPLETE || status == 0x8CD7 || status == 0x8CDB || status == 0x8CDC) return;
        if (!explained.add(texture + ":" + level + ":" + attachment)) return;
        Tex.Info ti = Tex.peek(texture);
        int real = texTarget == G.T_BUFFER ? G.T_2D : (texTarget == G.T_2D_ARRAY || texTarget == G.T_3D ? texTarget : G.T_2D);
        int[] lv = new int[4];
        Tex.with(real, texture, () -> {
            lv[0] = GL11.glGetTexLevelParameteri(real, level, GL11.GL_TEXTURE_WIDTH);
            lv[1] = GL11.glGetTexLevelParameteri(real, level, GL11.GL_TEXTURE_HEIGHT);
            lv[2] = GL11.glGetTexLevelParameteri(real, level, 0x1003); // TEXTURE_INTERNAL_FORMAT
            lv[3] = GL11.glGetTexParameteri(real, 0x813C) * 1000 + GL11.glGetTexParameteri(real, 0x813D); // BASE, MAX
        });
        int[] bufs = new int[MAX_DRAW_BUFFERS];
        StringBuilder db = new StringBuilder();
        for (int i = 0; i < MAX_DRAW_BUFFERS; i++) {
            bufs[i] = GL11.glGetInteger(DRAW_BUFFER0 + i);
            if (bufs[i] != 0) db.append(' ').append(Integer.toHexString(bufs[i]));
        }
        Log.warn("framebuffer " + drawFbo + " incomplete (0x" + Integer.toHexString(status) + ") after attaching texture " + texture
            + " level " + level + " at 0x" + Integer.toHexString(attachment) + ": real level " + lv[0] + "x" + lv[1]
            + " ifmt 0x" + Integer.toHexString(lv[2]) + " base/max " + (lv[3] / 1000) + "/" + (lv[3] % 1000)
            + (ti == null ? " (texture not seen)" : " seen as target 0x" + Integer.toHexString(ti.target) + " ifmt 0x"
               + Integer.toHexString(ti.ifmt) + " " + ti.width + "x" + ti.height + " view of " + ti.viewOf)
            + "; draw buffers" + db + "; error " + GL11.glGetError());
    }

    static void with(int fb, Runnable edit) {
        int prevDraw = GL11.glGetInteger(G.DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL11.glGetInteger(G.READ_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(G.FRAMEBUFFER, fb);
        try {
            edit.run();
        } finally {
            GL30.glBindFramebuffer(G.DRAW_FRAMEBUFFER, prevDraw);
            GL30.glBindFramebuffer(G.READ_FRAMEBUFFER, prevRead);
        }
    }

    /** Runs a clear that should touch only draw buffer {@code index}. */
    static void onlyDrawBuffer(int index, Runnable clear) {
        int[] bufs = new int[MAX_DRAW_BUFFERS];
        int n = 0;
        for (int i = 0; i < MAX_DRAW_BUFFERS; i++) {
            bufs[i] = GL11.glGetInteger(DRAW_BUFFER0 + i);
            if (bufs[i] != GL11.GL_NONE) n = i + 1;
        }
        if (index >= n) return;
        GL11.glDrawBuffer(bufs[index]);
        try {
            clear.run();
        } finally {
            if (n <= 1) {
                GL11.glDrawBuffer(bufs[0]);
            } else {
                int[] restore = new int[n];
                System.arraycopy(bufs, 0, restore, 0, n);
                GL20.glDrawBuffers(restore);
            }
        }
    }
}
