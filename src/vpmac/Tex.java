package vpmac;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** What the Mac context can't tell us about Viewpoint's textures, and the edits that need it. */
final class Tex {
    private Tex() {}

    private static int serials;

    static final class Info {
        /** Unique to this record: a texture name GL hands out again gets a new one. */
        final int serial = ++serials;
        int target = G.T_2D;
        int ifmt, width, height, depth;
        /** Bumped whenever the contents may have changed; {@code known} once this file saw it allocated. */
        int version;
        boolean known;
        /** A texture view (4.3): which texture, level and layer it shows; its own storage holds a copy for sampling. */
        int viewOf, viewLevel, viewLayer, viewCopied = -1;
        /** Parameters Viewpoint set on the texture itself, and those last applied (sampler objects change them). */
        final Map<Integer, Number> own = new HashMap<>();
        final Map<Integer, Number> applied = new HashMap<>();
        int appliedSampler, appliedSamplerVersion = -1;

        // Buffer texture stand-in: a 2D texture filled from the buffer's copy.
        int buffer, bufferFormat;
        long bufferOffset, bufferSize = -1;
        int syncedVersion = -1, syncedBuffer, syncedSerial, rows;
        long syncedOffset = -1;

        void allocated(int target, int level, int ifmt, int w, int h, int d) {
            version++;
            if (level != 0) return;
            known = true;
            this.target = target;
            this.ifmt = ifmt;
            this.width = w;
            this.height = h;
            this.depth = d;
        }
    }

    private static final Map<Integer, Info> infos = new HashMap<>();

    static Info info(int texture) {
        return infos.computeIfAbsent(texture, t -> new Info());
    }

    /** A parameter Viewpoint set on the texture itself; the next sampler apply re-checks everything. */
    static Info ownParam(int texture, int pname, Number value) {
        Info ti = info(texture);
        ti.own.put(pname, value);
        ti.applied.put(pname, value);
        ti.appliedSamplerVersion = -1;
        return ti;
    }

    static void touch(int texture) {
        Info ti = infos.get(texture);
        if (ti != null) touch(ti);
    }

    static void touch(Info ti) {
        ti.version++;
        if (ti.viewOf != 0) touch(ti.viewOf);
    }

    /**
     * A view of one layer of another texture: the Mac has no views, so the view gets storage of
     * its own, framebuffers attach the original's layer instead, and {@link #refreshView} copies
     * that layer over before the view is sampled.
     */
    static void view(int view, int target, int orig, int ifmt, int minLevel, int minLayer) {
        Info o = info(orig);
        Info v = info(view);
        v.viewOf = orig;
        v.viewLevel = minLevel;
        v.viewLayer = minLayer;
        int w = Math.max(1, o.width >> minLevel), h = Math.max(1, o.height >> minLevel);
        storage(target, view, 1, ifmt, w, h, 1);
        v.viewCopied = -1;
    }

    static void refreshView(int view) {
        Info v = infos.get(view);
        if (v == null || v.viewOf == 0) return;
        int version = info(v.viewOf).version;
        if (v.viewCopied == version) return;
        Info o = info(v.viewOf);
        Timers.begin(Timers.VIEWS);
        copy(v.viewOf, o.target, v.viewLevel, 0, 0, v.viewLayer, view, v.target, 0, 0, 0, 0, v.width, v.height, 1);
        v.viewCopied = info(v.viewOf).version;
        Timers.end(Timers.VIEWS);
    }

    static Info peek(int texture) {
        return infos.get(texture);
    }

    static void forget(int texture) {
        infos.remove(texture);
        Fbo.infosChanged();
        Atlas.forget(texture);
    }

    /** Binds {@code texture} on unit 0 just for {@code edit}, then puts back what was there. */
    static void with(int target, int texture, Runnable edit) {
        int real = G.realTarget(target);
        int active = GL11.glGetInteger(G.ACTIVE_TEXTURE);
        GL13.glActiveTexture(G.TEXTURE0);
        int prev = GL11.glGetInteger(G.BINDINGS[G.targetIndex(real)]);
        GL11.glBindTexture(real, texture);
        try {
            edit.run();
        } finally {
            GL11.glBindTexture(real, prev);
            GL13.glActiveTexture(active);
        }
    }

    /** Runs an upload from client memory with no pixel-unpack buffer bound and tight rows. */
    static void unpacked(Runnable upload, boolean fromClient) {
        if (!fromClient) {
            upload.run();
            return;
        }
        int pbo = GL11.glGetInteger(G.PIXEL_UNPACK_BUFFER_BINDING);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT);
        if (pbo != 0) GL15.glBindBuffer(G.PIXEL_UNPACK_BUFFER, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        try {
            upload.run();
        } finally {
            GL11.glPopClientAttrib();
            if (pbo != 0) GL15.glBindBuffer(G.PIXEL_UNPACK_BUFFER, pbo);
        }
    }

    // ---- immutable storage (4.2) as plain levels ------------------------------------------

    static void storage(int target, int texture, int levels, int ifmt, int w, int h, int d) {
        Info ti = info(texture);
        ti.target = target;
        ti.allocated(target, 0, ifmt, w, h, d);
        int[] ft = Formats.formatType(ifmt);
        with(target, texture, () -> unpacked(() -> {
            for (int l = 0; l < levels; l++) {
                int lw = Math.max(1, w >> l), lh = Math.max(1, h >> l);
                switch (target) {
                    case G.T_1D:
                        GL11.glTexImage1D(target, l, ifmt, lw, 0, ft[0], ft[1], (ByteBuffer) null);
                        break;
                    case G.T_3D:
                        GL12.glTexImage3D(target, l, ifmt, lw, lh, Math.max(1, d >> l), 0, ft[0], ft[1], (ByteBuffer) null);
                        break;
                    case G.T_2D_ARRAY:
                        GL12.glTexImage3D(target, l, ifmt, lw, lh, d, 0, ft[0], ft[1], (ByteBuffer) null);
                        break;
                    case G.T_CUBE:
                        for (int f = 0; f < 6; f++) {
                            GL11.glTexImage2D(0x8515 + f, l, ifmt, lw, lh, 0, ft[0], ft[1], (ByteBuffer) null);
                        }
                        break;
                    default:
                        GL11.glTexImage2D(target, l, ifmt, lw, lh, 0, ft[0], ft[1], (ByteBuffer) null);
                }
            }
            GL11.glTexParameteri(target, GL12.GL_TEXTURE_BASE_LEVEL, 0);
            GL11.glTexParameteri(target, GL12.GL_TEXTURE_MAX_LEVEL, levels - 1);
        }, true));
        ti.own.put(GL12.GL_TEXTURE_MAX_LEVEL, levels - 1);
        ti.applied.put(GL12.GL_TEXTURE_MAX_LEVEL, levels - 1);
        ti.known = true;
        ti.version++;
    }

    // ---- buffer textures --------------------------------------------------------------------

    static void attachBuffer(int texture, int ifmt, int buffer, long offset, long size) {
        Info ti = info(texture);
        ti.target = G.T_BUFFER;
        ti.buffer = buffer;
        ti.bufferFormat = ifmt;
        ti.bufferOffset = offset;
        ti.bufferSize = size;
        ti.syncedVersion = -1;
    }

    /**
     * Brings the 2D stand-in of a buffer texture up to date with its buffer; the texture must be
     * bound as GL_TEXTURE_2D on the active unit. Returns false when it has no contents to show.
     */
    static boolean syncBuffer(int texture) {
        Info ti = peek(texture);
        if (ti == null || ti.buffer == 0) return false;
        Buf.Info b = Buf.peek(ti.buffer);
        if (b == null || b.size <= ti.bufferOffset) return ti.rows > 0;
        int texel = Formats.texelBytes(ti.bufferFormat);
        long bytes = ti.bufferSize >= 0 ? ti.bufferSize : b.size - ti.bufferOffset;
        bytes = Math.max(0, Math.min(bytes, b.size - ti.bufferOffset));
        int texels = (int) Math.min(Integer.MAX_VALUE, bytes / texel);
        int rows = Math.max(1, (texels + Glsl.BUFFER_WIDTH - 1) / Glsl.BUFFER_WIDTH);
        if (rows > Atlas.MAX_SIZE) {
            Log.once("bufferTooBig", "a buffer texture of " + texels + " texels is too big for the Mac; it is cut short");
            rows = Atlas.MAX_SIZE;
            texels = rows * Glsl.BUFFER_WIDTH;
        }
        // The stand-in only grows; a new range of the buffer is copied whole, later writes by range.
        boolean grow = rows > ti.rows || ti.ifmt != ti.bufferFormat;
        boolean moved = grow || ti.syncedBuffer != ti.buffer || ti.syncedSerial != b.serial || ti.syncedOffset != ti.bufferOffset;
        long[] range = moved ? new long[] {0, b.size} : Buf.dirtySince(b, ti.syncedVersion);
        if (grow) {
            int[] ft = Formats.formatType(ti.bufferFormat);
            final int r = Math.min(Atlas.MAX_SIZE, Math.max(rows, ti.rows + ti.rows / 2));
            unpacked(() -> {
                GL11.glTexImage2D(G.T_2D, 0, ti.bufferFormat, Glsl.BUFFER_WIDTH, r, 0, ft[0], ft[1], (ByteBuffer) null);
                GL11.glTexParameteri(G.T_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(G.T_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(G.T_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            }, true);
            ti.rows = r;
            ti.ifmt = ti.bufferFormat;
        }
        if (range != null) {
            Timers.begin(Timers.BUFFERS);
            uploadRows(ti.buffer, ti.bufferOffset, texels, ti.bufferFormat, 0, range);
            Timers.end(Timers.BUFFERS);
        }
        ti.syncedBuffer = ti.buffer;
        ti.syncedSerial = b.serial;
        ti.syncedOffset = ti.bufferOffset;
        ti.syncedVersion = b.version;
        return true;
    }

    /**
     * Copies a buffer's texels into rows of the bound 2D texture, {@link Glsl#BUFFER_WIDTH} a row
     * starting at {@code firstRow}: only the rows that {@code range} (bytes of the buffer) touches.
     * They come from the buffer's CPU copy when it is current: an upload from memory doesn't wait
     * the way one from a pixel-unpack buffer the GPU may still read does (4.8 against 73 µs in
     * tools/GlProbe), and what {@link StreamWrites} holds for the buffer can keep waiting. Without
     * a current copy the buffer itself is the source, GPU-side.
     */
    static void uploadRows(int buffer, long offset, int texels, int ifmt, int firstRow, long[] range) {
        int texel = Formats.texelBytes(ifmt);
        long rowBytes = (long) Glsl.BUFFER_WIDTH * texel;
        int full = texels / Glsl.BUFFER_WIDTH, rest = texels - full * Glsl.BUFFER_WIDTH;
        int lastRow = full + (rest > 0 ? 1 : 0);
        int from = (int) Math.max(0, (range[0] - offset) / rowBytes);
        int to = (int) Math.min(lastRow, (range[1] - offset + rowBytes - 1) / rowBytes);
        if (to <= from) return;
        Stats.bufferRows += to - from;
        int[] ft = Formats.formatType(ifmt);
        unpacked(() -> {
            Buf.Info b = Buf.peek(buffer);
            boolean fromCopy = b != null && b.data != null && !b.gpuWritten
                && offset + full * rowBytes + rest * (rowBytes / Glsl.BUFFER_WIDTH) <= b.size;
            long base = fromCopy ? MemoryUtil.memAddress(b.data) : 0;
            if (!fromCopy) {
                StreamWrites.flush(buffer);
                GL15.glBindBuffer(G.PIXEL_UNPACK_BUFFER, buffer);
            }
            int fullTo = Math.min(to, full);
            if (fullTo > from) {
                GL11.nglTexSubImage2D(G.T_2D, 0, 0, firstRow + from, Glsl.BUFFER_WIDTH, fullTo - from, ft[0], ft[1], base + offset + from * rowBytes);
            }
            if (to > full && rest > 0) {
                GL11.nglTexSubImage2D(G.T_2D, 0, 0, firstRow + full, rest, 1, ft[0], ft[1], base + offset + full * rowBytes);
            }
            if (!fromCopy) GL15.glBindBuffer(G.PIXEL_UNPACK_BUFFER, 0);
        }, true);
    }

    // ---- clear and copy (4.4, 4.3) ----------------------------------------------------------

    static void clear(int texture, int level, int fmt, int type, ByteBuffer data) {
        Info ti = info(texture);
        int w = Math.max(1, ti.width >> level), h = Math.max(1, ti.height >> level);
        int d = ti.target == G.T_3D ? Math.max(1, ti.depth >> level) : Math.max(1, ti.depth);
        int px = Formats.pixelBytes(fmt, type);
        long total = (long) w * h * d * px;
        if (total <= 0 || total > Integer.MAX_VALUE) return;
        ByteBuffer fill = MemoryUtil.memCalloc((int) total);
        try {
            if (data != null && data.remaining() > 0) {
                int pat = Math.min(px, data.remaining());
                for (int i = 0; i + pat <= total; i += px) {
                    for (int k = 0; k < pat; k++) fill.put(i + k, data.get(data.position() + k));
                }
            }
            int target = ti.target;
            with(target, texture, () -> unpacked(() -> {
                if (target == G.T_3D || target == G.T_2D_ARRAY) {
                    GL12.glTexSubImage3D(target, level, 0, 0, 0, w, h, d, fmt, type, fill);
                } else {
                    GL11.glTexSubImage2D(target, level, 0, 0, w, h, fmt, type, fill);
                }
            }, true));
            ti.version++;
        } finally {
            MemoryUtil.memFree(fill);
        }
    }

    private static int copyFbo;

    static void copy(int src, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ,
                     int dst, int dstTarget, int dstLevel, int dstX, int dstY, int dstZ, int w, int h, int d) {
        if (copyFbo == 0) {
            // Only ever read from: no draw buffer, or the Mac calls it incomplete.
            copyFbo = GL30.glGenFramebuffers();
            Fbo.with(copyFbo, () -> GL11.glDrawBuffer(GL11.GL_NONE));
        }
        Info si = info(src);
        boolean depth = Formats.isDepth(si.ifmt);
        int attachment = depth ? (Formats.hasStencil(si.ifmt) ? G.DEPTH_STENCIL_ATTACHMENT : G.DEPTH_ATTACHMENT) : G.COLOR_ATTACHMENT0;
        int prevRead = GL11.glGetInteger(G.READ_FRAMEBUFFER_BINDING);
        int prevReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        GL30.glBindFramebuffer(G.READ_FRAMEBUFFER, copyFbo);
        try {
            for (int z = 0; z < d; z++) {
                if (srcTarget == G.T_2D_ARRAY || srcTarget == G.T_3D) {
                    GL30.glFramebufferTextureLayer(G.READ_FRAMEBUFFER, attachment, src, srcLevel, srcZ + z);
                } else {
                    GL30.glFramebufferTexture2D(G.READ_FRAMEBUFFER, attachment, srcTarget, src, srcLevel);
                }
                GL11.glReadBuffer(depth ? GL11.GL_NONE : G.COLOR_ATTACHMENT0);
                final int layer = dstZ + z;
                with(dstTarget, dst, () -> {
                    if (dstTarget == G.T_2D_ARRAY || dstTarget == G.T_3D) {
                        GL12.glCopyTexSubImage3D(dstTarget, dstLevel, dstX, dstY, layer, srcX, srcY, w, h);
                    } else {
                        GL11.glCopyTexSubImage2D(dstTarget, dstLevel, dstX, dstY, srcX, srcY, w, h);
                    }
                });
            }
            GL30.glFramebufferTexture2D(G.READ_FRAMEBUFFER, attachment, G.T_2D, 0, 0);
            Info di = infos.get(dst);
            if (di != null) di.version++;
        } finally {
            GL30.glBindFramebuffer(G.READ_FRAMEBUFFER, prevRead);
            GL11.glReadBuffer(prevReadBuffer);
        }
    }
}
