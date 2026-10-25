package vpmac;

import org.lwjgl.opengl.GL15;
import org.lwjgl.system.MemoryUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * Writes into the buffers Viewpoint streams per-frame data through ({@link Streams}), held back
 * until something reads that buffer on the GPU.
 *
 * <p>Every write lands in the buffer's CPU copy at once, and most readers use that copy: indirect
 * commands, per-draw constants ({@link PerDraw}), buffer textures ({@link Tex#uploadRows}), copies
 * and reads of buffers. In the game the GL write itself costs about 27 µs whatever its size, about
 * 1,700 of them a second, 45 ms of the render thread's every second. Held, they go to GL about 390
 * times a second (41 KB, 21 µs each), 8 ms a second: what still reads the stream on the GPU is the
 * model batches, whose three per-draw attributes stay instanced arrays.
 *
 * <p>So a write only marks its range here, and the ranges go to GL, one call per run of them,
 * before anything reads the buffer on the GPU: a draw whose vertex arrays or indices come from it
 * ({@link #beforeDraw}), a buffer texture made from it when there is no CPU copy to use, a pixel
 * transfer through it, a mapping of it, or a read of it from GL.
 *
 * <p>{@code hold-writes:0} in dump.txt sends every write to GL as it comes (and what is held,
 * at once); {@code hold-writes:1} holds them again.
 */
final class StreamWrites {
    private StreamWrites() {}

    static volatile boolean on = true;
    /** Ranges kept per buffer; past that the two closest become one, if they are close. */
    static final int MOST_RANGES = 16;
    /** The widest gap two ranges are joined across: the bytes between go to GL as well. */
    static final long MERGE_GAP = 64 << 10;

    /** One buffer's ranges waiting for GL: from and to, sorted, none touching another. */
    static final class Ranges {
        final long[] from = new long[MOST_RANGES + 1], to = new long[MOST_RANGES + 1];
        int n;
    }

    private static final Map<Integer, Ranges> waiting = new HashMap<>();

    /**
     * Whether a write of {@code size} bytes at {@code offset} into {@code buffer}, already in its
     * CPU copy {@code i}, can wait. Only writes through {@code GL_COPY_WRITE_BUFFER} (Viewpoint's
     * stream) wait, and only into a buffer whose CPU copy is whole and not behind the GPU, and that
     * isn't bound where a pixel transfer would read it.
     */
    static boolean hold(int target, int buffer, Buf.Info i, long offset, long size) {
        if (!on || target != Streams.COPY_WRITE_BUFFER || i == null || i.data == null || i.gpuWritten) return false;
        if (offset < 0 || size <= 0 || offset + size > i.size) return false;
        if (Buf.bound(G.PIXEL_UNPACK_BUFFER) == buffer || Buf.bound(G.PIXEL_PACK_BUFFER) == buffer) return false;
        Ranges r = waiting.computeIfAbsent(buffer, b -> new Ranges());
        if (!add(r, offset, offset + size)) flush(buffer);
        Stats.writesHeld++;
        return true;
    }

    /** Before a draw: what its vertex arrays and indices read goes to GL; per-instance arrays only if {@code instances}. */
    static void beforeDraw(boolean instances) {
        if (waiting.isEmpty()) return;
        Vao.Obj o = Vao.current;
        if (o == null) {
            flushAll();
            return;
        }
        for (int a = 0; a < Vao.ATTRIBS; a++) {
            Vao.Attr attr = o.attrs[a];
            if (!attr.enabled) continue;
            Vao.Binding b = o.bindings[attr.binding];
            if (b.divisor > 0 && !instances) continue;
            flush(b.buffer);
        }
        flush(o.elementBuffer);
    }

    /** What is held for {@code buffer}, to GL now. */
    static void flush(int buffer) {
        if (waiting.isEmpty()) return;
        Ranges r = waiting.remove(buffer);
        Buf.Info i = Buf.peek(buffer);
        if (r == null || i == null || i.data == null) return;
        long base = MemoryUtil.memAddress(i.data);
        Buf.onBuffer(buffer, real -> {
            for (int k = 0; k < r.n; k++) {
                GL15.nglBufferSubData(real, r.from[k], r.to[k] - r.from[k], base + r.from[k]);
                Stats.writesSent++;
            }
        });
    }

    static void flushAll() {
        while (!waiting.isEmpty()) flush(waiting.keySet().iterator().next());
    }

    /** The buffer's storage was given again, or all of it written to GL: nothing held is wanted. */
    static void drop(int buffer) {
        if (!waiting.isEmpty()) waiting.remove(buffer);
    }

    /**
     * Adds a range, merging it with those it touches. Past {@link #MOST_RANGES} the two closest
     * become one if no more than {@link #MERGE_GAP} apart; otherwise it returns false, and the
     * caller sends them all.
     */
    static boolean add(Ranges r, long from, long to) {
        int k = 0;
        while (k < r.n && r.to[k] < from) k++;
        // Every range from k on that this one touches folds into it.
        int end = k;
        while (end < r.n && r.from[end] <= to) {
            from = Math.min(from, r.from[end]);
            to = Math.max(to, r.to[end]);
            end++;
        }
        int removed = end - k;
        if (removed != 1) {
            System.arraycopy(r.from, end, r.from, k + 1, r.n - end);
            System.arraycopy(r.to, end, r.to, k + 1, r.n - end);
            r.n += 1 - removed;
        }
        r.from[k] = from;
        r.to[k] = to;
        if (r.n <= MOST_RANGES) return true;
        int closest = 0;
        for (int j = 1; j + 1 < r.n; j++) {
            if (r.from[j + 1] - r.to[j] < r.from[closest + 1] - r.to[closest]) closest = j;
        }
        if (r.from[closest + 1] - r.to[closest] > MERGE_GAP) return false;
        r.to[closest] = r.to[closest + 1];
        System.arraycopy(r.from, closest + 2, r.from, closest + 1, r.n - closest - 2);
        System.arraycopy(r.to, closest + 2, r.to, closest + 1, r.n - closest - 2);
        r.n--;
        return true;
    }

    /** The ranges held for {@code buffer} as from, to pairs (for the smoke test). */
    static long[] held(int buffer) {
        Ranges r = waiting.get(buffer);
        if (r == null) return new long[0];
        long[] out = new long[r.n * 2];
        for (int k = 0; k < r.n; k++) {
            out[2 * k] = r.from[k];
            out[2 * k + 1] = r.to[k];
        }
        return out;
    }
}
