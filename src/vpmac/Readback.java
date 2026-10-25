package vpmac;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

/**
 * Depth reads into a pixel-pack buffer without the wait. The Mac turns a DEPTH_COMPONENT read
 * into a full stop until the GPU has finished everything queued (tools/GlProbe: 34 ms behind 35 ms
 * of work), and Viewpoint reads the depth under the cursor and the crosshair every frame. So the
 * depth is copied on the GPU into a small R32F target, and that is read instead: a float read the
 * Mac queues like any other command. Same bits, no stall.
 */
final class Readback {
    private Readback() {}

    /** Bigger reads are rare (screenshots, debugging) and go the ordinary way. */
    private static final int MAX_SIDE = 64;

    private static int depthCopy, depthCopyFormat, color, fbo, program, originLoc, side;
    /** The copy program didn't link: depth reads go the ordinary way from then on. */
    private static boolean unavailable;

    /** Reads {@code w}x{@code h} depth floats at x, y of the read framebuffer into the bound pack buffer at {@code offset}. */
    static boolean depth(int x, int y, int w, int h, long offset) {
        int readFb = GL11.glGetInteger(G.READ_FRAMEBUFFER_BINDING);
        return readFb != 0 && depth(x, y, w, h, offset, readFb, sourceFormat(readFb));
    }

    private static boolean depth(int x, int y, int w, int h, long offset, int readFb, int source) {
        if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE || unavailable) return false;
        if (program == 0 && !setup()) {
            unavailable = true;
            return false;
        }
        if (depthCopy == 0 || depthCopyFormat != source || side < Math.max(w, h)) allocate(source, Math.max(w, h));

        int drawFb = GL11.glGetInteger(G.DRAW_FRAMEBUFFER_BINDING);
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int active = GL11.glGetInteger(G.ACTIVE_TEXTURE);
        int readBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL13.glActiveTexture(G.TEXTURE0);
        int prevTexture = GL11.glGetInteger(G.BINDINGS[G.targetIndex(G.T_2D)]);
        Timers.begin(Timers.READS);
        try {
            // 1. The depth region, GPU to GPU (queued, like a draw).
            GL11.glBindTexture(G.T_2D, depthCopy);
            GL11.glCopyTexSubImage2D(G.T_2D, 0, 0, 0, x, y, w, h);
            // 2. Into a float color target, texel for texel.
            GL30.glBindFramebuffer(G.DRAW_FRAMEBUFFER, fbo);
            GL11.glDrawBuffer(G.COLOR_ATTACHMENT0);
            GL11.glViewport(0, 0, w, h);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glColorMask(true, true, true, true);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            GL20.glUseProgram(program);
            GL20.glUniform2i(originLoc, 0, 0);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(-1, -1);
            GL11.glVertex2f(1, -1);
            GL11.glVertex2f(1, 1);
            GL11.glVertex2f(-1, 1);
            GL11.glEnd();
            // 3. The float read, into the pack buffer Viewpoint bound.
            GL30.glBindFramebuffer(G.READ_FRAMEBUFFER, fbo);
            GL11.glReadBuffer(G.COLOR_ATTACHMENT0);
            GL11.glReadPixels(0, 0, w, h, GL30.GL_RED, GL11.GL_FLOAT, offset);
        } finally {
            GL11.glBindTexture(G.T_2D, prevTexture);
            GL11.glPopAttrib();
            GL13.glActiveTexture(active);
            GL20.glUseProgram(prevProgram);
            GL30.glBindFramebuffer(G.READ_FRAMEBUFFER, readFb);
            GL11.glReadBuffer(readBuffer);
            GL30.glBindFramebuffer(G.DRAW_FRAMEBUFFER, drawFb);
            Timers.end(Timers.READS);
        }
        if (Stats.readbacks++ == 0) Log.info("depth reads go through a float copy now, so they don't stop the GPU");
        return true;
    }

    private static int textureFbo;

    /**
     * glGetTextureSubImage (4.5): each layer attached to a framebuffer of this file's and read
     * from there, into client memory or the bound pack buffer. Depth into a pack buffer goes the
     * queued way of {@link #depth}.
     */
    static void texture(int texture, int level, int x, int y, int z, int w, int h, int d, int fmt, int type, long pixels) {
        Tex.Info ti = Tex.info(texture);
        if (textureFbo == 0) {
            // Only ever read from: no draw buffer, or the Mac calls it incomplete with depth alone.
            textureFbo = GL30.glGenFramebuffers();
            Fbo.with(textureFbo, () -> GL11.glDrawBuffer(GL11.GL_NONE));
        }
        boolean depth = fmt == GL11.GL_DEPTH_COMPONENT || fmt == 0x84F9; // DEPTH_STENCIL
        int attachment = depth ? (fmt == 0x84F9 ? G.DEPTH_STENCIL_ATTACHMENT : G.DEPTH_ATTACHMENT) : G.COLOR_ATTACHMENT0;
        int prevRead = GL11.glGetInteger(G.READ_FRAMEBUFFER_BINDING), prevBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        long layerBytes = (long) w * h * Formats.pixelBytes(fmt, type);
        boolean packed = Buf.bound(G.PIXEL_PACK_BUFFER) != 0;
        GL30.glBindFramebuffer(G.READ_FRAMEBUFFER, textureFbo);
        try {
            for (int k = 0; k < Math.max(1, d); k++) {
                if (ti.target == G.T_2D_ARRAY || ti.target == G.T_3D) {
                    GL30.glFramebufferTextureLayer(G.READ_FRAMEBUFFER, attachment, texture, level, z + k);
                } else if (ti.target == G.T_CUBE) {
                    GL30.glFramebufferTexture2D(G.READ_FRAMEBUFFER, attachment, 0x8515 + z + k, texture, level);
                } else {
                    GL30.glFramebufferTexture2D(G.READ_FRAMEBUFFER, attachment, G.T_2D, texture, level);
                }
                GL11.glReadBuffer(depth ? GL11.GL_NONE : G.COLOR_ATTACHMENT0);
                long at = pixels + k * layerBytes;
                boolean queued = depth && fmt == GL11.GL_DEPTH_COMPONENT && type == GL11.GL_FLOAT && packed
                    && depth(x, y, w, h, at, textureFbo, Formats.isDepth(ti.ifmt) ? ti.ifmt : 0x8CAC);
                if (!queued) GL11.nglReadPixels(x, y, w, h, fmt, type, at);
            }
            GL30.glFramebufferTexture2D(G.READ_FRAMEBUFFER, attachment, G.T_2D, 0, 0);
        } finally {
            GL30.glBindFramebuffer(G.READ_FRAMEBUFFER, prevRead);
            GL11.glReadBuffer(prevBuffer);
        }
    }

    /** The read framebuffer's depth format, so the copy needs no conversion; 32-bit float when unknown. */
    private static int sourceFormat(int readFb) {
        java.util.Map<Integer, Integer> a = Fbo.attachments(readFb);
        Integer t = a == null ? null : a.get(G.DEPTH_ATTACHMENT);
        if (t == null && a != null) t = a.get(G.DEPTH_STENCIL_ATTACHMENT);
        Tex.Info ti = t == null ? null : Tex.peek(t);
        return ti != null && Formats.isDepth(ti.ifmt) ? ti.ifmt : 0x8CAC; // DEPTH_COMPONENT32F
    }

    private static void allocate(int format, int need) {
        side = Math.max(side, need);
        if (depthCopy != 0) GL11.glDeleteTextures(depthCopy);
        depthCopyFormat = format;
        depthCopy = GL11.glGenTextures();
        int[] ft = Formats.formatType(format);
        Tex.with(G.T_2D, depthCopy, () -> {
            Tex.unpacked(() -> GL11.glTexImage2D(G.T_2D, 0, format, side, side, 0, ft[0], ft[1], (ByteBuffer) null), true);
            params();
            GL11.glTexParameteri(G.T_2D, 0x884C, 0); // no compare: the shader reads raw depth
        });
        Tex.with(G.T_2D, color, () -> Tex.unpacked(() ->
            GL11.glTexImage2D(G.T_2D, 0, GL30.GL_R32F, side, side, 0, GL30.GL_RED, GL11.GL_FLOAT, (ByteBuffer) null), true));
    }

    private static void params() {
        GL11.glTexParameteri(G.T_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(G.T_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(G.T_2D, org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LEVEL, 0);
    }

    private static boolean setup() {
        color = GL11.glGenTextures();
        Tex.with(G.T_2D, color, () -> {
            Tex.unpacked(() -> GL11.glTexImage2D(G.T_2D, 0, GL30.GL_R32F, 1, 1, 0, GL30.GL_RED, GL11.GL_FLOAT, (ByteBuffer) null), true);
            params();
        });
        int drawFb = GL11.glGetInteger(G.DRAW_FRAMEBUFFER_BINDING);
        fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(G.DRAW_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(G.DRAW_FRAMEBUFFER, G.COLOR_ATTACHMENT0, G.T_2D, color, 0);
        GL30.glBindFramebuffer(G.DRAW_FRAMEBUFFER, drawFb);
        String vs = "#version 120\nvoid main() { gl_Position = gl_Vertex; }\n";
        String fs = "#version 120\n#extension GL_EXT_gpu_shader4 : require\nuniform sampler2D depth;\nuniform ivec2 origin;\n"
            + "void main() { gl_FragColor = vec4(texelFetch2D(depth, origin + ivec2(gl_FragCoord.xy), 0).r); }\n";
        int v = GL20.glCreateShader(GL20.GL_VERTEX_SHADER), f = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(v, vs);
        GL20.glCompileShader(v);
        GL20.glShaderSource(f, fs);
        GL20.glCompileShader(f);
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, v);
        GL20.glAttachShader(p, f);
        GL20.glLinkProgram(p);
        GL20.glDeleteShader(v);
        GL20.glDeleteShader(f);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
            Log.warn("the depth read program did not link: " + GL20.glGetProgramInfoLog(p) + "; depth reads wait for the GPU");
            GL20.glDeleteProgram(p);
            GL30.glDeleteFramebuffers(fbo);
            GL11.glDeleteTextures(color);
            fbo = color = 0;
            return false;
        }
        int prev = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(p);
        GL20.glUniform1i(GL20.glGetUniformLocation(p, "depth"), 0);
        GL20.glUseProgram(prev);
        originLoc = GL20.glGetUniformLocation(p, "origin");
        program = p;
        return true;
    }
}
