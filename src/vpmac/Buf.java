package vpmac;

import org.lwjgl.opengl.GL15;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Buffer bindings for targets the Mac lacks (texture, indirect, copy, uniform...), and a copy
 * of each buffer's contents: buffer textures and indirect draws are read from it on the CPU.
 */
final class Buf {
    private Buf() {}

    /**
     * Buffers past this size keep no CPU copy. Only indirect commands are read on the CPU; buffer
     * textures copy GPU-side, straight from the buffer.
     */
    static final long COPY_LIMIT = 64L << 20;
    static final int LOG = 64;

    private static int serials;

    static final class Info {
        ByteBuffer data;
        long size;
        int version;
        /** Unique to this record: a buffer name GL hands out again gets a new one. */
        final int serial = ++serials;
        /**
         * The GPU wrote into it (pixels or a texture read into it as the pack buffer), so the CPU
         * copy is behind: reads of it go to GL until its storage is given again.
         */
        boolean gpuWritten;
        /** The last {@link #LOG} writes: version, from, to. */
        final long[][] log = new long[LOG][];
    }

    private static void wrote(Info i, long from, long to) {
        i.version++;
        i.log[i.version % LOG] = new long[] {i.version, from, to};
    }

    /** Byte range written since {@code version}: null when nothing, the whole buffer when the log is too short. */
    static long[] dirtySince(Info i, int version) {
        if (version == i.version) return null;
        long from = Long.MAX_VALUE, to = Long.MIN_VALUE;
        for (int v = version + 1; v <= i.version; v++) {
            long[] e = i.log[v % LOG];
            if (e == null || e[0] != v) return new long[] {0, i.size};
            from = Math.min(from, e[1]);
            to = Math.max(to, e[2]);
        }
        return new long[] {from, to};
    }

    private static final Map<Integer, Info> infos = new HashMap<>();
    private static final Map<Integer, Integer> bound = new HashMap<>();

    static boolean real(int target) {
        return target == G.ARRAY_BUFFER || target == G.ELEMENT_ARRAY_BUFFER
            || target == G.PIXEL_PACK_BUFFER || target == G.PIXEL_UNPACK_BUFFER;
    }

    static int bound(int target) {
        if (target == G.PIXEL_PACK_BUFFER) return packBound;
        Integer b = bound.get(target);
        return b == null ? 0 : b;
    }

    /** Asked for on every read of pixels: kept apart from the map. */
    private static int packBound;

    static Info peek(int buffer) {
        return infos.get(buffer);
    }

    static void bind(int target, int buffer) {
        // A pixel transfer through it reads or writes it on the GPU: what is held goes first.
        if (target == G.PIXEL_PACK_BUFFER || target == G.PIXEL_UNPACK_BUFFER) StreamWrites.flush(buffer);
        bound.put(target, buffer);
        if (target == G.PIXEL_PACK_BUFFER) packBound = buffer;
        if (target == G.ELEMENT_ARRAY_BUFFER) Vao.elementBuffer(buffer);
        if (real(target)) GL15.glBindBuffer(target, buffer);
    }

    static void forget(int buffer) {
        Streams.forget(buffer);
        StreamWrites.drop(buffer);
        Info i = infos.remove(buffer);
        if (i != null && i.data != null) MemoryUtil.memFree(i.data);
        bound.replaceAll((t, b) -> b == buffer ? 0 : b);
        if (packBound == buffer) packBound = 0;
    }

    /** Runs {@code op} with the buffer bound to {@code target} reachable through a real target. */
    static void on(int target, IntConsumer op) {
        if (real(target)) {
            op.accept(target);
        } else {
            onBuffer(bound(target), op);
        }
    }

    /** Runs {@code op} with {@code buffer} bound as the array buffer, then restores that binding. */
    static void onBuffer(int buffer, IntConsumer op) {
        int prev = org.lwjgl.opengl.GL11.glGetInteger(G.ARRAY_BUFFER_BINDING);
        GL15.glBindBuffer(G.ARRAY_BUFFER, buffer);
        try {
            op.accept(G.ARRAY_BUFFER);
        } finally {
            GL15.glBindBuffer(G.ARRAY_BUFFER, prev);
        }
    }

    private static Info resize(int buffer, long size) {
        StreamWrites.drop(buffer);
        Info i = infos.computeIfAbsent(buffer, b -> new Info());
        if (i.data != null && i.size != size) {
            MemoryUtil.memFree(i.data);
            i.data = null;
        }
        if (i.data == null && size > 0 && size <= COPY_LIMIT) i.data = MemoryUtil.memCalloc((int) size);
        i.size = size;
        i.gpuWritten = false;
        wrote(i, 0, size);
        return i;
    }

    static void data(int target, long size, long address, int usage) {
        int buffer = bound(target);
        Info i = resize(buffer, size);
        if (address != 0 && i.data != null) MemoryUtil.memCopy(address, MemoryUtil.memAddress(i.data), size);
        on(target, real -> GL15.nglBufferData(real, size, address, usage));
    }

    static void subData(int target, long offset, long size, long address) {
        int buffer = bound(target);
        if (Census.on) Census.subData(buffer, size);
        Streams.prepare(target, buffer);
        Info i = infos.get(buffer);
        if (i != null) {
            if (i.data != null && offset + size <= i.size) MemoryUtil.memCopy(address, MemoryUtil.memAddress(i.data) + offset, size);
            wrote(i, offset, offset + size);
        }
        if (StreamWrites.hold(target, buffer, i, offset, size)) return;
        on(target, real -> GL15.nglBufferSubData(real, offset, size, address));
    }

    /** glNamedBufferData without contents (4.5): the buffer by name instead of by target. */
    static void namedData(int buffer, long size, int usage) {
        resize(buffer, size);
        onBuffer(buffer, real -> GL15.nglBufferData(real, size, 0L, usage));
    }

    // ---- mapping a range (3.0) --------------------------------------------------------------

    private static final class Mapping {
        long offset, length;
        ByteBuffer range;
        boolean write;
    }

    private static final Map<Integer, Mapping> mapped = new HashMap<>();

    /** The whole buffer mapped (the Mac has no range mapping), and the range handed out. */
    static ByteBuffer mapRange(int target, long offset, long length, int access) {
        int buffer = bound(target);
        StreamWrites.flush(buffer);
        int mode = (access & 3) == 3 ? GL15.GL_READ_WRITE : (access & 2) != 0 ? GL15.GL_WRITE_ONLY : GL15.GL_READ_ONLY;
        ByteBuffer[] whole = new ByteBuffer[1];
        on(target, real -> whole[0] = GL15.glMapBuffer(real, mode));
        if (whole[0] == null) return null;
        if (offset < 0 || offset + length > whole[0].capacity()) {
            on(target, GL15::glUnmapBuffer);
            return null;
        }
        Mapping m = new Mapping();
        m.offset = offset;
        m.length = length;
        m.write = (access & 2) != 0;
        m.range = MemoryUtil.memSlice(whole[0], (int) offset, (int) length);
        mapped.put(buffer, m);
        return m.range;
    }

    /** Unmaps; what was written through a mapping reaches the CPU copy too. */
    static boolean unmap(int target) {
        int buffer = bound(target);
        Mapping m = mapped.remove(buffer);
        Info i = infos.get(buffer);
        if (m != null && m.write && i != null) {
            if (i.data != null && m.offset + m.length <= i.size) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(m.range), MemoryUtil.memAddress(i.data) + m.offset, m.length);
            }
            wrote(i, m.offset, m.offset + m.length);
        }
        boolean[] ok = {true};
        on(target, real -> ok[0] = GL15.glUnmapBuffer(real));
        return ok[0];
    }

    /** Immutable storage (4.4) as a plain dynamic buffer. */
    static void storage(int buffer, long size) {
        resize(buffer, size);
        onBuffer(buffer, real -> GL15.nglBufferData(real, size, 0L, GL15.GL_DYNAMIC_DRAW));
    }

    /** A read of pixels or of a texture just went into the pack buffer, on the GPU. */
    static void packWritten() {
        Info i = packBound == 0 ? null : infos.get(packBound);
        if (i != null) i.gpuWritten = true;
    }

    static void read(int buffer, long offset, ByteBuffer out) {
        Info i = infos.get(buffer);
        if (i != null && i.data != null && !i.gpuWritten && offset + out.remaining() <= i.size) {
            MemoryUtil.memCopy(MemoryUtil.memAddress(i.data) + offset, MemoryUtil.memAddress(out), out.remaining());
        } else {
            StreamWrites.flush(buffer);
            onBuffer(buffer, real -> GL15.glGetBufferSubData(real, offset, out));
        }
    }

    static void copy(int read, int write, long readOffset, long writeOffset, long size) {
        Info src = infos.get(read);
        ByteBuffer tmp;
        boolean owned = false;
        if (src != null && src.data != null && !src.gpuWritten && readOffset + size <= src.size) {
            tmp = MemoryUtil.memSlice(src.data, (int) readOffset, (int) size);
        } else {
            tmp = MemoryUtil.memAlloc((int) size);
            owned = true;
            StreamWrites.flush(read);
            final ByteBuffer t = tmp;
            onBuffer(read, real -> GL15.glGetBufferSubData(real, readOffset, t));
        }
        try {
            Info dst = infos.get(write);
            if (dst != null) {
                if (dst.data != null && writeOffset + size <= dst.size) {
                    MemoryUtil.memCopy(MemoryUtil.memAddress(tmp), MemoryUtil.memAddress(dst.data) + writeOffset, size);
                }
                wrote(dst, writeOffset, writeOffset + size);
            }
            final ByteBuffer t = tmp;
            onBuffer(write, real -> GL15.glBufferSubData(real, writeOffset, t));
        } finally {
            if (owned) MemoryUtil.memFree(tmp);
        }
    }

    static void clear(int buffer, ByteBuffer pattern) {
        Info i = infos.get(buffer);
        if (i == null || i.size <= 0 || i.size > COPY_LIMIT) return;
        ByteBuffer fill = MemoryUtil.memCalloc((int) i.size);
        try {
            if (pattern != null && pattern.remaining() > 0) {
                int p = pattern.remaining();
                for (int k = 0; k + p <= i.size; k += p) {
                    for (int j = 0; j < p; j++) fill.put(k + j, pattern.get(pattern.position() + j));
                }
            }
            if (i.data != null) MemoryUtil.memCopy(MemoryUtil.memAddress(fill), MemoryUtil.memAddress(i.data), i.size);
            wrote(i, 0, i.size);
            StreamWrites.drop(buffer);
            onBuffer(buffer, real -> GL15.glBufferSubData(real, 0, fill));
        } finally {
            MemoryUtil.memFree(fill);
        }
    }

    /** Reads an int from the copy of the buffer bound to {@code target} (indirect commands). */
    static int intAt(Info i, long offset) {
        return i.data.getInt((int) offset);
    }
}
