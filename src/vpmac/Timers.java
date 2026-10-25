package vpmac;

import org.lwjgl.opengl.EXTTimerQuery;
import org.lwjgl.opengl.GL15;

import java.util.HashMap;
import java.util.Map;

/**
 * GPU timestamps (3.3) from the Mac's elapsed-time queries. A timestamp only means something next
 * to another, so one elapsed query is always running and each glQueryCounter closes it and opens
 * the next: a counter's time is the sum of every closed span before it. Each switch costs ~35 µs
 * of CPU on the Mac (tools/GlProbe), so this runs only when asked for ({@code gpu:<seconds>} in
 * dump.txt); otherwise counters answer "ready, zero" at once, as they always did.
 */
final class Timers {
    private Timers() {}

    private static final int RING = 1 << 13;

    static boolean on;
    /** Query names Viewpoint used as timestamps: the others (occlusion queries) are real ones. */
    private static final Map<Integer, Long> counters = new HashMap<>();
    /** Span i: its elapsed query and, once read, the time at its end. */
    private static final int[] spanQuery = new int[RING];
    private static final long[] endsAt = new long[RING];
    private static long spans, read, clock;
    private static int open;
    private static final java.util.ArrayDeque<Integer> pool = new java.util.ArrayDeque<>();

    static boolean isCounter(int id) {
        return counters.containsKey(id);
    }

    static void counter(int id) {
        if (!on) {
            counters.put(id, -1L);
            return;
        }
        if (spans - read >= RING - 1) collect(true);
        if (open != 0) {
            GL15.glEndQuery(EXTTimerQuery.GL_TIME_ELAPSED_EXT);
            spanQuery[(int) (spans % RING)] = open;
            spans++;
        }
        open = pool.isEmpty() ? GL15.glGenQueries() : pool.pop();
        GL15.glBeginQuery(EXTTimerQuery.GL_TIME_ELAPSED_EXT, open);
        counters.put(id, spans);
    }

    /** Reads finished spans in order; {@code all} waits for every closed one. */
    private static void collect(boolean all) {
        while (read < spans) {
            int q = spanQuery[(int) (read % RING)];
            if (!all && GL15.glGetQueryObjecti(q, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) return;
            clock += EXTTimerQuery.glGetQueryObjectui64EXT(q, GL15.GL_QUERY_RESULT);
            endsAt[(int) (read % RING)] = clock;
            pool.push(q);
            read++;
        }
    }

    /** True once every span before the counter has been measured. */
    static boolean available(int id) {
        Long at = counters.get(id);
        if (at == null || at < 0) return true;
        if (at > read) collect(false);
        return at <= read || spans - at >= RING;
    }

    static long time(int id) {
        Long at = counters.get(id);
        if (at == null || at <= 0 || !available(id) || read - at >= RING) return 0;
        return endsAt[(int) ((at - 1) % RING)];
    }

    // ---- the layer's own GPU work, timed on the same chain -------------------------------------

    /** Names of the layer's own sections; their counters use ids below zero, which GL never hands out. */
    static final String[] SECTIONS = {"atlas copies", "buffer textures", "texture views", "depth reads"};
    static final int ATLAS = 0, BUFFERS = 1, VIEWS = 2, READS = 3;
    private static int nextOwn = -1;
    private static final java.util.ArrayDeque<int[]> pairs = new java.util.ArrayDeque<>();
    private static final long[] ownNs = new long[SECTIONS.length];
    private static int openSection = -1, openStart;

    /** Starts timing the layer's own work of {@code kind} (no-op unless on, or already timing). */
    static void begin(int kind) {
        if (!on || openSection >= 0) return;
        openSection = kind;
        openStart = nextOwn--;
        counter(openStart);
    }

    static void end(int kind) {
        if (openSection != kind) return;
        int stop = nextOwn--;
        counter(stop);
        pairs.add(new int[] {kind, openStart, stop});
        openSection = -1;
        if (nextOwn < -(1 << 30)) nextOwn = -1;
        settleOwn();
    }

    private static void settleOwn() {
        while (!pairs.isEmpty()) {
            int[] q = pairs.peek();
            if (!available(q[2])) return;
            ownNs[q[0]] += time(q[2]) - time(q[1]);
            counters.remove(q[1]);
            counters.remove(q[2]);
            pairs.poll();
        }
    }

    /** The layer's own GPU time per section since the last call, in ms per second of wall time. */
    static String ownReport(double seconds) {
        settleOwn();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < SECTIONS.length; i++) {
            b.append(i == 0 ? "" : ", ").append(SECTIONS[i]).append(String.format(" %.2f ms/s", ownNs[i] / 1e6 / seconds));
            ownNs[i] = 0;
        }
        return b.toString();
    }

    static void stop() {
        if (!on) return;
        on = false;
        if (open != 0) {
            GL15.glEndQuery(EXTTimerQuery.GL_TIME_ELAPSED_EXT);
            spanQuery[(int) (spans % RING)] = open;
            spans++;
            open = 0;
        }
        collect(true);
    }
}
