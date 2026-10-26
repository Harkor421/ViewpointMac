package vpmac;

import org.lwjgl.opengl.ARBDrawElementsBaseVertex;
import org.lwjgl.opengl.ARBDrawInstanced;
import org.lwjgl.opengl.ARBInstancedArrays;
import org.lwjgl.opengl.EXTDrawBuffers2;
import org.lwjgl.opengl.EXTGPUShader4;
import org.lwjgl.opengl.EXTTimerQuery;
import org.lwjgl.opengl.EXTTextureInteger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * Stand-ins for the OpenGL 3.0–4.5 calls Viewpoint makes. {@link Remap} points Viewpoint's call
 * sites here, method for method (same name, same descriptor); everything else still goes straight
 * to LWJGL.
 *
 * <p>The Mac gives the game a legacy 2.1 context with 16 texture units, so this keeps a virtual
 * copy of the state that context can't hold — texture units past 15, sampler objects, vertex array
 * objects, buffer targets it doesn't know — and turns it into real 2.1 state right before each
 * draw. All of it runs on the render thread, like the GL calls it replaces.
 */
public final class G {
    private G() {}

    // ---- constants -------------------------------------------------------------------------

    static final int TEXTURE0 = 0x84C0, ACTIVE_TEXTURE = 0x84E0;
    static final int T_1D = 0x0DE0, T_2D = 0x0DE1, T_3D = 0x806F, T_CUBE = 0x8513, T_2D_ARRAY = 0x8C1A,
        T_BUFFER = 0x8C2A, T_1D_ARRAY = 0x8C18, T_RECT = 0x84F5;
    static final int[] TARGETS = {T_1D, T_2D, T_3D, T_CUBE, T_2D_ARRAY, T_BUFFER, T_1D_ARRAY, T_RECT};
    static final int[] BINDINGS = {0x8068, 0x8069, 0x806A, 0x8514, 0x8C1D, 0x8C2C, 0x8C1C, 0x84F6};
    static final int REAL_UNITS = 16, VIRTUAL_UNITS = 192;

    static final int ARRAY_BUFFER = 0x8892, ELEMENT_ARRAY_BUFFER = 0x8893, PIXEL_PACK_BUFFER = 0x88EB,
        PIXEL_UNPACK_BUFFER = 0x88EC, DRAW_INDIRECT_BUFFER = 0x8F3F;
    static final int ARRAY_BUFFER_BINDING = 0x8894, PIXEL_UNPACK_BUFFER_BINDING = 0x88EF;

    static final int QUERY_RESULT_AVAILABLE = 0x8867;

    static final int FRAMEBUFFER = 0x8D40, READ_FRAMEBUFFER = 0x8CA8, DRAW_FRAMEBUFFER = 0x8CA9,
        READ_FRAMEBUFFER_BINDING = 0x8CAA, DRAW_FRAMEBUFFER_BINDING = 0x8CA6;
    static final int COLOR_ATTACHMENT0 = 0x8CE0, DEPTH_ATTACHMENT = 0x8D00, DEPTH_STENCIL_ATTACHMENT = 0x821A;

    static int targetIndex(int target) {
        for (int i = 0; i < TARGETS.length; i++) if (TARGETS[i] == target) return i;
        return 1;
    }

    // ---- capabilities ----------------------------------------------------------------------

    /** Viewpoint only reads this to decide whether to measure frame times; the stand-ins cover 3.3. */
    public static boolean cap_OpenGL33(GLCapabilities caps) { return true; }

    // ---- texture units ---------------------------------------------------------------------

    static int vActive;
    static final int[][] vTex = new int[VIRTUAL_UNITS][TARGETS.length];
    static final int[] vSampler = new int[VIRTUAL_UNITS];

    public static void glActiveTexture(int texture) {
        OutsideBinds.settle();
        vActive = Math.max(0, Math.min(VIRTUAL_UNITS - 1, texture - TEXTURE0));
        if (vActive < REAL_UNITS) GL13.glActiveTexture(texture);
        else OutsideBinds.arm(vActive);
    }

    public static void glBindTexture(int target, int texture) {
        vTex[vActive][targetIndex(target)] = texture;
        if (target == T_BUFFER) {
            Tex.info(texture).target = T_BUFFER;
            return;
        }
        if (texture != 0) Tex.info(texture).target = target;
        if (vActive < REAL_UNITS) GL11.glBindTexture(target, texture);
    }

    /** The texture Viewpoint has bound on its active (virtual) unit for this target. */
    static int boundTexture(int target) {
        return vTex[vActive][targetIndex(target)];
    }

    /** Runs a texture edit against the texture Viewpoint means, even when its unit is virtual. */
    static void onBound(int target, Runnable edit) {
        if (vActive < REAL_UNITS && target != T_BUFFER) {
            edit.run();
        } else {
            Tex.with(target == T_BUFFER ? T_2D : target, boundTexture(target), edit);
        }
    }

    public static int glGetInteger(int pname) {
        switch (pname) {
            case ACTIVE_TEXTURE: return TEXTURE0 + vActive;
            case 0x85B5: return Vao.currentId; // VERTEX_ARRAY_BINDING
            case 0x8C2B: return Glsl.BUFFER_WIDTH * 16384; // MAX_TEXTURE_BUFFER_SIZE
            case 0x919F: return 16; // TEXTURE_BUFFER_OFFSET_ALIGNMENT
            case 0x8F43: return Buf.bound(DRAW_INDIRECT_BUFFER);
            case 0x8C2C: return vTex[vActive][targetIndex(T_BUFFER)];
            case 0x8919: return vSampler[vActive]; // SAMPLER_BINDING
            // MAX_COMBINED_TEXTURE_IMAGE_UNITS: the virtual units, less the last few the layer keeps
            // for itself (the atlas, the folded buffers); add-ons count down from here.
            case 0x8B4D: return VIRTUAL_UNITS - 4;
            default:
        }
        for (int i = 0; i < BINDINGS.length; i++) {
            if (BINDINGS[i] == pname && (vActive >= REAL_UNITS || TARGETS[i] == T_BUFFER)) return vTex[vActive][i];
        }
        return GL11.glGetInteger(pname);
    }

    public static void glGetIntegerv(int pname, int[] params) {
        int v = glGetInteger(pname);
        // Values only this file knows are single; everything else may have several (GL_VIEWPORT has 4).
        if (!onlyOurs(pname)) GL11.glGetIntegerv(pname, params);
        params[0] = v;
    }

    public static void glGetIntegerv(int pname, IntBuffer params) {
        int v = glGetInteger(pname);
        if (!onlyOurs(pname)) GL11.glGetIntegerv(pname, params);
        params.put(params.position(), v);
    }

    /** Names the 2.1 context doesn't know: asking it would leave GL_INVALID_ENUM behind. */
    private static boolean onlyOurs(int pname) {
        return pname == 0x8C2B || pname == 0x919F || pname == 0x8F43 || pname == 0x8C2C || pname == 0x8919;
    }

    public static void glDeleteTextures(int texture) {
        Tex.forget(texture);
        GL11.glDeleteTextures(texture);
    }

    public static void glDeleteTextures(int[] textures) {
        for (int t : textures) Tex.forget(t);
        GL11.glDeleteTextures(textures);
    }

    public static void glTexParameteri(int target, int pname, int param) {
        Tex.ownParam(boundTexture(target), pname, param);
        if (target == T_BUFFER) return;
        onBound(target, () -> GL11.glTexParameteri(target, pname, param));
    }

    public static void glTexParameterf(int target, int pname, float param) {
        Tex.ownParam(boundTexture(target), pname, param);
        if (target == T_BUFFER) return;
        onBound(target, () -> GL11.glTexParameterf(target, pname, param));
    }

    public static int glGetTexParameteri(int target, int pname) {
        int[] r = new int[1];
        onBound(target, () -> r[0] = GL11.glGetTexParameteri(target, pname));
        return r[0];
    }

    public static int glGetTexLevelParameteri(int target, int level, int pname) {
        int[] r = new int[1];
        onBound(target, () -> r[0] = GL11.glGetTexLevelParameteri(target, level, pname));
        return r[0];
    }

    public static void glTexImage1D(int target, int level, int ifmt, int w, int border, int fmt, int type, ByteBuffer px) {
        Tex.info(boundTexture(target)).allocated(target, level, ifmt, w, 1, 1);
        onBound(target, () -> Tex.unpacked(() -> GL11.glTexImage1D(target, level, ifmt, w, border, fmt, type, px), px != null));
    }

    public static void glTexImage2D(int target, int level, int ifmt, int w, int h, int border, int fmt, int type, ByteBuffer px) {
        Tex.info(boundTexture(target)).allocated(target, level, ifmt, w, h, 1);
        onBound(target, () -> GL11.glTexImage2D(target, level, ifmt, w, h, border, fmt, type, px));
    }

    public static void glTexImage2D(int target, int level, int ifmt, int w, int h, int border, int fmt, int type, IntBuffer px) {
        Tex.info(boundTexture(target)).allocated(target, level, ifmt, w, h, 1);
        onBound(target, () -> GL11.glTexImage2D(target, level, ifmt, w, h, border, fmt, type, px));
    }

    public static void glTexImage2D(int target, int level, int ifmt, int w, int h, int border, int fmt, int type, FloatBuffer px) {
        Tex.info(boundTexture(target)).allocated(target, level, ifmt, w, h, 1);
        onBound(target, () -> GL11.glTexImage2D(target, level, ifmt, w, h, border, fmt, type, px));
    }

    public static void glTexImage3D(int target, int level, int ifmt, int w, int h, int d, int border, int fmt, int type, ByteBuffer px) {
        Tex.info(boundTexture(target)).allocated(target, level, ifmt, w, h, d);
        onBound(target, () -> GL12.glTexImage3D(target, level, ifmt, w, h, d, border, fmt, type, px));
    }

    public static void glTexImage3D(int target, int level, int ifmt, int w, int h, int d, int border, int fmt, int type, IntBuffer px) {
        Tex.info(boundTexture(target)).allocated(target, level, ifmt, w, h, d);
        onBound(target, () -> GL12.glTexImage3D(target, level, ifmt, w, h, d, border, fmt, type, px));
    }

    public static void glTexSubImage2D(int target, int level, int x, int y, int w, int h, int fmt, int type, ByteBuffer px) {
        if (Census.on) Census.texSubImage(boundTexture(target), w, h);
        Tex.touch(boundTexture(target));
        onBound(target, () -> GL11.glTexSubImage2D(target, level, x, y, w, h, fmt, type, px));
    }

    public static void glTexSubImage2D(int target, int level, int x, int y, int w, int h, int fmt, int type, FloatBuffer px) {
        if (Census.on) Census.texSubImage(boundTexture(target), w, h);
        Tex.touch(boundTexture(target));
        onBound(target, () -> GL11.glTexSubImage2D(target, level, x, y, w, h, fmt, type, px));
    }

    public static void glTexSubImage2D(int target, int level, int x, int y, int w, int h, int fmt, int type, ShortBuffer px) {
        Tex.touch(boundTexture(target));
        onBound(target, () -> GL11.glTexSubImage2D(target, level, x, y, w, h, fmt, type, px));
    }

    public static void glTexSubImage3D(int target, int level, int x, int y, int z, int w, int h, int d, int fmt, int type, ByteBuffer px) {
        Tex.touch(boundTexture(target));
        onBound(target, () -> GL12.glTexSubImage3D(target, level, x, y, z, w, h, d, fmt, type, px));
    }

    public static void glTexSubImage3D(int target, int level, int x, int y, int z, int w, int h, int d, int fmt, int type, IntBuffer px) {
        Tex.touch(boundTexture(target));
        onBound(target, () -> GL12.glTexSubImage3D(target, level, x, y, z, w, h, d, fmt, type, px));
    }

    public static void glGetTexImage(int target, int level, int fmt, int type, FloatBuffer px) {
        onBound(target, () -> GL11.glGetTexImage(target, level, fmt, type, px));
    }

    public static void glGenerateMipmap(int target) {
        Tex.touch(boundTexture(target));
        onBound(target, () -> GL30.glGenerateMipmap(target));
    }

    public static void glTexStorage1D(int target, int levels, int ifmt, int w) {
        Tex.storage(target, boundTexture(target), levels, ifmt, w, 1, 1);
    }

    public static void glTexStorage2D(int target, int levels, int ifmt, int w, int h) {
        Tex.storage(target, boundTexture(target), levels, ifmt, w, h, 1);
    }

    public static void glTexStorage3D(int target, int levels, int ifmt, int w, int h, int d) {
        Tex.storage(target, boundTexture(target), levels, ifmt, w, h, d);
    }

    public static void glTexBuffer(int target, int ifmt, int buffer) {
        Tex.attachBuffer(boundTexture(target), ifmt, buffer, 0, -1);
    }

    public static void glTexBufferRange(int target, int ifmt, int buffer, long offset, long size) {
        Tex.attachBuffer(boundTexture(target), ifmt, buffer, offset, size);
    }

    // ---- direct state access (4.5) ---------------------------------------------------------

    public static int glCreateTextures(int target) {
        int t = GL11.glGenTextures();
        Tex.info(t).target = target;
        if (target != T_BUFFER) Tex.with(target, t, () -> {});
        return t;
    }

    public static void glTextureParameteri(int texture, int pname, int param) {
        Tex.Info ti = Tex.ownParam(texture, pname, param);
        if (ti.target == T_BUFFER) return;
        Tex.with(ti.target, texture, () -> GL11.glTexParameteri(ti.target, pname, param));
    }

    public static void glTextureStorage2D(int texture, int levels, int ifmt, int w, int h) {
        Tex.storage(Tex.info(texture).target, texture, levels, ifmt, w, h, 1);
    }

    public static void glTextureStorage3D(int texture, int levels, int ifmt, int w, int h, int d) {
        Tex.storage(Tex.info(texture).target, texture, levels, ifmt, w, h, d);
    }

    public static void glTextureSubImage2D(int texture, int level, int x, int y, int w, int h, int fmt, int type, ByteBuffer px) {
        Tex.touch(texture);
        int target = Tex.info(texture).target;
        Tex.with(target, texture, () -> GL11.glTexSubImage2D(target, level, x, y, w, h, fmt, type, px));
    }

    public static void glTextureSubImage2D(int texture, int level, int x, int y, int w, int h, int fmt, int type, FloatBuffer px) {
        Tex.touch(texture);
        int target = Tex.info(texture).target;
        Tex.with(target, texture, () -> GL11.glTexSubImage2D(target, level, x, y, w, h, fmt, type, px));
    }

    public static void glTextureSubImage2D(int texture, int level, int x, int y, int w, int h, int fmt, int type, IntBuffer px) {
        Tex.touch(texture);
        int target = Tex.info(texture).target;
        Tex.with(target, texture, () -> GL11.glTexSubImage2D(target, level, x, y, w, h, fmt, type, px));
    }

    public static void glTextureSubImage3D(int texture, int level, int x, int y, int z, int w, int h, int d, int fmt, int type, ByteBuffer px) {
        Tex.touch(texture);
        int target = Tex.info(texture).target;
        Tex.with(target, texture, () -> GL12.glTexSubImage3D(target, level, x, y, z, w, h, d, fmt, type, px));
    }

    public static void glTextureSubImage3D(int texture, int level, int x, int y, int z, int w, int h, int d, int fmt, int type, IntBuffer px) {
        Tex.touch(texture);
        int target = Tex.info(texture).target;
        Tex.with(target, texture, () -> GL12.glTexSubImage3D(target, level, x, y, z, w, h, d, fmt, type, px));
    }

    public static void glGetTextureImage(int texture, int level, int fmt, int type, int bufSize, long pixels) {
        if (Buf.bound(PIXEL_PACK_BUFFER) != 0) Buf.packWritten();
        int target = Tex.info(texture).target;
        Tex.with(target, texture, () -> GL11.nglGetTexImage(target, level, fmt, type, pixels));
    }

    public static int glGetTextureLevelParameteri(int texture, int level, int pname) {
        int target = Tex.info(texture).target;
        int[] r = new int[1];
        Tex.with(target, texture, () -> r[0] = GL11.glGetTexLevelParameteri(target, level, pname));
        return r[0];
    }

    public static void glClearTexImage(int texture, int level, int fmt, int type, ByteBuffer data) {
        Tex.clear(texture, level, fmt, type, data);
    }

    public static void glCopyImageSubData(int src, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ,
                                          int dst, int dstTarget, int dstLevel, int dstX, int dstY, int dstZ,
                                          int w, int h, int d) {
        Tex.copy(src, srcTarget, srcLevel, srcX, srcY, srcZ, dst, dstTarget, dstLevel, dstX, dstY, dstZ, w, h, d);
    }

    public static void glTextureView(int texture, int target, int orig, int ifmt, int minLevel, int numLevels, int minLayer, int numLayers) {
        if (numLayers != 1 || target != T_2D) {
            Log.once("textureView", "a texture view of " + numLayers + " layers as " + target + " is not emulated; it stays empty");
            return;
        }
        Tex.view(texture, target, orig, ifmt, minLevel, minLayer);
    }

    public static void glBindFramebuffer(int target, int fb) {
        Fbo.bound(target, fb);
        GL30.glBindFramebuffer(target, fb);
    }

    public static void glFramebufferTexture2D(int target, int attachment, int texTarget, int texture, int level) {
        Fbo.attach(target, attachment, texture);
        Tex.Info ti = texture == 0 ? null : Tex.peek(texture);
        if (ti != null && ti.viewOf != 0) {
            GL30.glFramebufferTextureLayer(target, attachment, ti.viewOf, level + ti.viewLevel, ti.viewLayer);
        } else {
            GL30.glFramebufferTexture2D(target, attachment, texTarget, texture, level);
        }
        Fbo.explainIfIncomplete(target, attachment, texTarget, texture, level);
    }

    public static void glFramebufferTextureLayer(int target, int attachment, int texture, int level, int layer) {
        Fbo.attach(target, attachment, texture);
        GL30.glFramebufferTextureLayer(target, attachment, texture, level, layer);
        Fbo.explainIfIncomplete(target, attachment, Tex.info(texture).target, texture, level);
    }

    public static void glDeleteFramebuffers(int fb) {
        Fbo.forget(fb);
        GL30.glDeleteFramebuffers(fb);
    }

    public static void glDeleteFramebuffers(int[] fbs) {
        for (int fb : fbs) Fbo.forget(fb);
        GL30.glDeleteFramebuffers(fbs);
    }

    public static void glClear(int mask) {
        GL11.glClear(mask);
        Fbo.touch();
    }

    public static void glBlitFramebuffer(int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1, int mask, int filter) {
        GL30.glBlitFramebuffer(sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, mask, filter);
        Fbo.touch();
    }

    public static int glCreateFramebuffers() {
        return GL30.glGenFramebuffers();
    }

    public static void glNamedFramebufferTextureLayer(int fb, int attachment, int texture, int level, int layer) {
        Fbo.attachTo(fb, attachment, texture);
        Fbo.with(fb, () -> GL30.glFramebufferTextureLayer(FRAMEBUFFER, attachment, texture, level, layer));
    }

    public static void glNamedFramebufferDrawBuffer(int fb, int buf) {
        Fbo.with(fb, () -> GL11.glDrawBuffer(buf));
    }

    public static void glNamedFramebufferReadBuffer(int fb, int buf) {
        Fbo.with(fb, () -> GL11.glReadBuffer(buf));
    }

    public static int glCreateBuffers() {
        return GL15.glGenBuffers();
    }

    public static void glNamedBufferStorage(int buffer, long size, int flags) {
        Buf.storage(buffer, size);
    }

    public static void glGetNamedBufferSubData(int buffer, long offset, ByteBuffer data) {
        Buf.read(buffer, offset, data);
    }

    public static void glNamedBufferData(int buffer, long size, int usage) {
        Buf.namedData(buffer, size, usage);
    }

    public static int glGetNamedBufferParameteri(int buffer, int pname) {
        int[] r = new int[1];
        Buf.onBuffer(buffer, real -> r[0] = GL15.glGetBufferParameteri(real, pname));
        return r[0];
    }

    public static void glGetTextureSubImage(int texture, int level, int x, int y, int z, int w, int h, int d, int fmt, int type, int bufSize, long pixels) {
        if (Buf.bound(PIXEL_PACK_BUFFER) != 0) Buf.packWritten();
        Readback.texture(texture, level, x, y, z, w, h, d, fmt, type, pixels);
    }

    public static void glGetTextureSubImage(int texture, int level, int x, int y, int z, int w, int h, int d, int fmt, int type, ByteBuffer pixels) {
        Readback.texture(texture, level, x, y, z, w, h, d, fmt, type, MemoryUtil.memAddress(pixels));
    }

    public static void glCopyNamedBufferSubData(int read, int write, long readOffset, long writeOffset, long size) {
        Buf.copy(read, write, readOffset, writeOffset, size);
    }

    // ---- samplers (3.3) --------------------------------------------------------------------

    public static int glGenSamplers() {
        return Smp.create();
    }

    public static void glDeleteSamplers(int sampler) {
        Smp.delete(sampler);
    }

    public static void glBindSampler(int unit, int sampler) {
        OutsideBinds.settle();
        if (unit >= 0 && unit < VIRTUAL_UNITS) vSampler[unit] = sampler;
    }

    public static void glSamplerParameteri(int sampler, int pname, int param) {
        Smp.set(sampler, pname, param);
    }

    public static void glSamplerParameterf(int sampler, int pname, float param) {
        Smp.set(sampler, pname, param);
    }

    // ---- buffers ---------------------------------------------------------------------------

    public static void glBindBuffer(int target, int buffer) {
        Buf.bind(target, buffer);
    }

    public static void glDeleteBuffers(int buffer) {
        Buf.forget(buffer);
        GL15.glDeleteBuffers(buffer);
    }

    public static void glBufferData(int target, long size, int usage) {
        Buf.data(target, size, 0L, usage);
    }

    public static void glBufferData(int target, FloatBuffer data, int usage) {
        Buf.data(target, data.remaining() * 4L, MemoryUtil.memAddress(data), usage);
    }

    public static void glBufferData(int target, ShortBuffer data, int usage) {
        Buf.data(target, data.remaining() * 2L, MemoryUtil.memAddress(data), usage);
    }

    public static void glBufferSubData(int target, long offset, ByteBuffer data) {
        Buf.subData(target, offset, data.remaining(), MemoryUtil.memAddress(data));
    }

    public static void glBufferSubData(int target, long offset, FloatBuffer data) {
        Buf.subData(target, offset, data.remaining() * 4L, MemoryUtil.memAddress(data));
    }

    public static void glBufferSubData(int target, long offset, IntBuffer data) {
        Buf.subData(target, offset, data.remaining() * 4L, MemoryUtil.memAddress(data));
    }

    public static void glGetBufferSubData(int target, long offset, FloatBuffer data) {
        StreamWrites.flush(Buf.bound(target));
        Buf.on(target, real -> GL15.glGetBufferSubData(real, offset, data));
    }

    public static void glGetBufferSubData(int target, long offset, ByteBuffer data) {
        StreamWrites.flush(Buf.bound(target));
        Buf.on(target, real -> GL15.glGetBufferSubData(real, offset, data));
    }

    /** No ARB_map_buffer_range on the Mac: the whole buffer is mapped and the range handed out. */
    public static ByteBuffer glMapBufferRange(int target, long offset, long length, int access) {
        return Buf.mapRange(target, offset, length, access);
    }

    public static boolean glUnmapBuffer(int target) {
        return Buf.unmap(target);
    }

    public static int glGetBufferParameteri(int target, int pname) {
        int[] r = new int[1];
        Buf.on(target, real -> r[0] = GL15.glGetBufferParameteri(real, pname));
        return r[0];
    }

    public static void glBufferStorage(int target, long size, int flags) {
        Buf.storage(Buf.bound(target), size);
    }

    public static void glCopyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size) {
        Buf.copy(Buf.bound(readTarget), Buf.bound(writeTarget), readOffset, writeOffset, size);
    }

    public static void glClearBufferData(int target, int ifmt, int fmt, int type, ByteBuffer data) {
        Buf.clear(Buf.bound(target), data);
    }

    public static void glBindBufferBase(int target, int index, int buffer) {
        Log.once("bindBufferBase", "uniform/storage buffer blocks are not available on the Mac (shader packs need them)");
    }

    // ---- vertex arrays ---------------------------------------------------------------------

    public static int glGenVertexArrays() { return Vao.create(); }

    public static void glBindVertexArray(int array) { Vao.bind(array); }

    public static void glDeleteVertexArrays(int array) { Vao.delete(array); }

    public static void glEnableVertexAttribArray(int index) { Vao.enable(index, true); }

    public static void glDisableVertexAttribArray(int index) { Vao.enable(index, false); }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) {
        Vao.pointer(index, size, type, normalized, false, stride, pointer);
    }

    public static void glVertexAttribIPointer(int index, int size, int type, int stride, long pointer) {
        Vao.pointer(index, size, type, false, true, stride, pointer);
    }

    public static void glVertexAttribDivisor(int index, int divisor) { Vao.divisor(index, divisor); }

    public static void glVertexAttribFormat(int index, int size, int type, boolean normalized, int relOffset) {
        Vao.format(index, size, type, normalized, false, relOffset);
    }

    public static void glVertexAttribIFormat(int index, int size, int type, int relOffset) {
        Vao.format(index, size, type, false, true, relOffset);
    }

    public static void glVertexAttribBinding(int index, int binding) { Vao.attribBinding(index, binding); }

    public static void glBindVertexBuffer(int binding, int buffer, long offset, int stride) {
        Vao.vertexBuffer(binding, buffer, offset, stride);
    }

    public static void glVertexBindingDivisor(int binding, int divisor) { Vao.bindingDivisor(binding, divisor); }

    // ---- programs --------------------------------------------------------------------------

    public static int glCreateShader(int type) {
        int s = GL20.glCreateShader(type);
        Prog.shaderCreated(s, type);
        return s;
    }

    public static void glShaderSource(int shader, CharSequence source) {
        GL20.glShaderSource(shader, Prog.translate(shader, source));
    }

    public static void glCompileShader(int shader) {
        GL20.glCompileShader(shader);
        Prog.compiled(shader);
    }

    public static void glAttachShader(int program, int shader) {
        Prog.attach(program, shader);
        GL20.glAttachShader(program, shader);
    }

    public static void glDetachShader(int program, int shader) {
        Prog.detach(program, shader);
        GL20.glDetachShader(program, shader);
    }

    public static void glLinkProgram(int program) {
        Prog.link(program);
    }

    public static void glUseProgram(int program) {
        Prog.use(program);
        GL20.glUseProgram(program);
    }

    public static void glDeleteProgram(int program) {
        Prog.forget(program);
        GL20.glDeleteProgram(program);
    }

    public static void glUniform1i(int location, int v) {
        if (!Prog.samplerUniform(location, new int[] {v})) GL20.glUniform1i(location, v);
    }

    public static void glUniform1iv(int location, int[] v) {
        if (!Prog.samplerUniform(location, v)) GL20.glUniform1iv(location, v);
    }

    public static int glGetUniformLocation(int program, CharSequence name) {
        int fake = Prog.fakeLocation(program, name.toString());
        return fake != -2 ? fake : GL20.glGetUniformLocation(program, name);
    }

    public static int glGetUniformi(int program, int location) {
        int v = Prog.samplerValue(program, location);
        return v >= 0 ? v : GL20.glGetUniformi(program, location);
    }

    public static void glBindFragDataLocation(int program, int color, CharSequence name) {
        EXTGPUShader4.glBindFragDataLocationEXT(program, color, name);
    }

    public static int glGetFragDataLocation(int program, CharSequence name) {
        return EXTGPUShader4.glGetFragDataLocationEXT(program, name);
    }

    public static void glUniform1ui(int location, int v) {
        EXTGPUShader4.glUniform1uiEXT(location, v);
    }

    public static int glGetProgramInterfacei(int program, int iface, int pname) { return 0; }

    public static void glGetProgramResourceiv(int program, int iface, int index, int[] props, int[] length, int[] params) {
        if (length != null && length.length > 0) length[0] = 0;
    }

    // ---- draws -----------------------------------------------------------------------------

    public static void glDrawArrays(int mode, int first, int count) {
        if (!Draw.begin()) return;
        MeshRows.plain();
        StreamWrites.beforeDraw(true);
        NoAttribs.before(first + count);
        GL11.glDrawArrays(mode, first, count);
        NoAttribs.after();
        Draw.end();
    }

    public static void glDrawArraysInstanced(int mode, int first, int count, int instances) {
        if (!Draw.begin()) return;
        MeshRows.plain();
        StreamWrites.beforeDraw(true);
        NoAttribs.before(first + count);
        ARBDrawInstanced.glDrawArraysInstancedARB(mode, first, count, instances);
        NoAttribs.after();
        Draw.end();
    }

    public static void glDrawArraysInstancedBaseInstance(int mode, int first, int count, int instances, int baseInstance) {
        if (!Draw.begin()) return;
        MeshRows.plain();
        StreamWrites.beforeDraw(true);
        Vao.shiftInstances(baseInstance);
        NoAttribs.before(first + count);
        ARBDrawInstanced.glDrawArraysInstancedARB(mode, first, count, instances);
        NoAttribs.after();
        Vao.shiftInstances(0);
        Draw.end();
    }

    public static void glMultiDrawArraysIndirect(int mode, long indirect, int drawCount, int stride) {
        Draw.multiArrays(mode, indirect, drawCount, stride == 0 ? 16 : stride);
    }

    public static void glMultiDrawElementsIndirect(int mode, int type, long indirect, int drawCount, int stride) {
        Draw.multiElements(mode, type, indirect, drawCount, stride == 0 ? 20 : stride);
    }

    // ---- per-buffer state (4.0) ------------------------------------------------------------

    public static void glEnablei(int cap, int index) { EXTDrawBuffers2.glEnableIndexedEXT(cap, index); }

    public static void glDisablei(int cap, int index) { EXTDrawBuffers2.glDisableIndexedEXT(cap, index); }

    public static void glColorMaski(int index, boolean r, boolean g, boolean b, boolean a) {
        EXTDrawBuffers2.glColorMaskIndexedEXT(index, r, g, b, a);
    }

    /** The Mac has one blend function for all draw buffers; the last one set wins. */
    public static void glBlendFunci(int buf, int src, int dst) { GL11.glBlendFunc(src, dst); }

    public static void glBlendFuncSeparatei(int buf, int srcRGB, int dstRGB, int srcA, int dstA) {
        GL14.glBlendFuncSeparate(srcRGB, dstRGB, srcA, dstA);
    }

    public static void glClearBufferfv(int buffer, int drawBuffer, float[] value) {
        Fbo.touch();
        if (buffer == 0x1801) { // DEPTH
            float[] old = new float[1];
            GL11.glGetFloatv(GL11.GL_DEPTH_CLEAR_VALUE, old);
            GL11.glClearDepth(value[0]);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glClearDepth(old[0]);
            return;
        }
        Fbo.onlyDrawBuffer(drawBuffer, () -> {
            float[] old = new float[4];
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, old);
            GL11.glClearColor(value[0], value[1], value[2], value[3]);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL11.glClearColor(old[0], old[1], old[2], old[3]);
        });
    }

    public static void glClearBufferuiv(int buffer, int drawBuffer, int[] value) {
        Fbo.touch();
        Fbo.onlyDrawBuffer(drawBuffer, () -> {
            EXTTextureInteger.glClearColorIuiEXT(value[0], value[1], value[2], value[3]);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        });
    }

    // ---- queries, debug, and what the Mac can't do at all ----------------------------------

    public static void glQueryCounter(int id, int target) { Timers.counter(id); }

    public static int glGetQueryObjecti(int id, int pname) {
        if (!Timers.isCounter(id)) return GL15.glGetQueryObjecti(id, pname);
        return pname == QUERY_RESULT_AVAILABLE ? (Timers.available(id) ? 1 : 0) : (int) Timers.time(id);
    }

    public static long glGetQueryObjecti64(int id, int pname) { return query64(id, pname); }

    public static long glGetQueryObjectui64(int id, int pname) { return query64(id, pname); }

    /** Timestamps from {@link Timers}; any other query (occlusion) is real and answers as itself. */
    private static long query64(int id, int pname) {
        if (!Timers.isCounter(id)) return EXTTimerQuery.glGetQueryObjecti64EXT(id, pname);
        return pname == QUERY_RESULT_AVAILABLE ? (Timers.available(id) ? 1 : 0) : Timers.time(id);
    }

    // ---- reads -----------------------------------------------------------------------------

    /** Depth reads into a pack buffer would stop the Mac until the GPU is idle; {@link Readback} queues them. */
    public static void glReadPixels(int x, int y, int w, int h, int format, int type, long pixels) {
        boolean packed = Buf.bound(PIXEL_PACK_BUFFER) != 0;
        if (packed) Buf.packWritten();
        if (format == GL11.GL_DEPTH_COMPONENT && type == GL11.GL_FLOAT && packed && Readback.depth(x, y, w, h, pixels)) return;
        GL11.glReadPixels(x, y, w, h, format, type, pixels);
    }

    public static void glPushDebugGroup(int source, int id, CharSequence message) {}

    public static void glPopDebugGroup() {}

    public static void glDispatchCompute(int x, int y, int z) {
        Log.once("compute", "compute shaders are not available on the Mac (shader packs need them)");
    }

    public static void glMemoryBarrier(int barriers) {}

    public static void glBindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access, int format) {}

    public static void glPatchParameteri(int pname, int value) {}

    // ---- helpers shared by the stand-ins ---------------------------------------------------

    /** Real (2.1) target for a texture Viewpoint made: buffer textures live as 2D textures. */
    static int realTarget(int target) {
        return target == T_BUFFER ? T_2D : target;
    }

    /** One indexed draw; the caller has lent attribute 0 if the program needs one (see {@link NoAttribs}). */
    static void elements(int mode, int count, int type, long offset, int instances, int baseVertex) {
        // One instance draws plainly: instance 0 reads the same elements, and the Mac's instanced path costs more.
        if (instances == 1) {
            if (baseVertex != 0) ARBDrawElementsBaseVertex.glDrawElementsBaseVertex(mode, count, type, offset, baseVertex);
            else GL11.glDrawElements(mode, count, type, offset);
        } else if (baseVertex != 0) {
            ARBDrawElementsBaseVertex.glDrawElementsInstancedBaseVertex(mode, count, type, offset, instances, baseVertex);
        } else {
            ARBDrawInstanced.glDrawElementsInstancedARB(mode, count, type, offset, instances);
        }
    }

    static void divisor(int index, int divisor) {
        ARBInstancedArrays.glVertexAttribDivisorARB(index, divisor);
    }
}
