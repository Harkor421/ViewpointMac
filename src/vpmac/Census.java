package vpmac;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the render thread's busiest GL calls look like, counted for a few seconds ({@code census:S}
 * in dump.txt): buffer uploads by buffer and size, texture uploads by texture and size, and the
 * indirect commands by shape (several instances, or mergeable with the next one: same mesh and
 * the next instances), to see which of them could be issued in fewer calls.
 */
final class Census {
    private Census() {}

    static volatile boolean on;
    private static long until, from;
    private static long subCalls, subBytes, texCalls, texBytes, commandLists, commands, multiInstance, sameMeshNext, contiguousNext;
    private static final Map<Integer, long[]> buffers = new HashMap<>(), textures = new HashMap<>();
    private static final Map<Integer, long[]> listSizes = new HashMap<>();

    static void start(int seconds) {
        subCalls = subBytes = texCalls = texBytes = commandLists = commands = multiInstance = sameMeshNext = contiguousNext = 0;
        buffers.clear();
        textures.clear();
        listSizes.clear();
        from = System.nanoTime();
        until = from + seconds * 1_000_000_000L;
        on = true;
        Log.info("census for " + seconds + " s");
    }

    static void subData(int buffer, long bytes) {
        subCalls++;
        subBytes += bytes;
        long[] b = buffers.computeIfAbsent(buffer, k -> new long[3]);
        b[0]++;
        b[1] += bytes;
        Buf.Info i = Buf.peek(buffer);
        b[2] = i == null ? -1 : i.size;
        tick();
    }

    static void texSubImage(int texture, int w, int h) {
        texCalls++;
        long bytes = (long) w * h * 4;
        texBytes += bytes;
        long[] t = textures.computeIfAbsent(texture, k -> new long[4]);
        t[0]++;
        t[1] += bytes;
        t[2] = w;
        t[3] = h;
        tick();
    }

    /** One indirect draw's commands, 16 bytes each (count, instances, first, base instance) at {@code stride}. */
    static void commands(Buf.Info cmds, long indirect, int drawCount, int stride) {
        commandLists++;
        listSizes.computeIfAbsent(Math.min(drawCount, 1000) / 50 * 50, k -> new long[1])[0]++;
        int pc = -1, pi = 0, pf = -1, pb = -1;
        for (int i = 0; i < drawCount; i++) {
            long at = indirect + (long) i * stride;
            if (at + 16 > cmds.size) break;
            int count = Buf.intAt(cmds, at), instances = Buf.intAt(cmds, at + 4), first = Buf.intAt(cmds, at + 8), base = Buf.intAt(cmds, at + 12);
            if (count <= 0 || instances <= 0) continue;
            commands++;
            if (instances > 1) multiInstance++;
            if (pc == count && pf == first && base == pb + pi) sameMeshNext++;
            if (first == pf + pc && base == pb + pi) contiguousNext++;
            pc = count;
            pi = instances;
            pf = first;
            pb = base;
        }
        tick();
    }

    private static void tick() {
        long now = System.nanoTime();
        if (now < until) return;
        on = false;
        double s = (now - from) / 1e9;
        StringBuilder b = new StringBuilder(String.format("census over %.1f s:", s));
        b.append(String.format("%n   buffer uploads %.0f/s, %.1f MB/s; by buffer (calls/s, KB/s, buffer size KB):", subCalls / s, subBytes / s / 1e6));
        top(buffers, b, s, true);
        b.append(String.format("%n   texture uploads %.0f/s, %.1f MB/s; by texture (calls/s, KB/s, w x h):", texCalls / s, texBytes / s / 1e6));
        top(textures, b, s, false);
        b.append(String.format("%n   indirect draws %.0f/s with %.0f commands/s; commands with several instances %.0f/s,"
            + " same mesh with the next instances %.0f/s, next vertices with the next instance %.0f/s",
            commandLists / s, commands / s, multiInstance / s, sameMeshNext / s, contiguousNext / s));
        b.append("\n   commands per indirect draw (bucket of 50: draws):");
        List<Integer> keys = new ArrayList<>(listSizes.keySet());
        java.util.Collections.sort(keys);
        for (int k : keys) b.append(' ').append(k).append(':').append(listSizes.get(k)[0]);
        Log.info(b.toString());
    }

    private static void top(Map<Integer, long[]> m, StringBuilder b, double s, boolean buffer) {
        List<Map.Entry<Integer, long[]>> rows = new ArrayList<>(m.entrySet());
        rows.sort((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1]));
        for (int i = 0; i < Math.min(6, rows.size()); i++) {
            long[] v = rows.get(i).getValue();
            b.append(String.format(" [%d: %.0f, %.0f, %s]", rows.get(i).getKey(), v[0] / s, v[1] / s / 1e3,
                buffer ? String.valueOf(v[2] / 1024) : v[2] + "x" + v[3]));
        }
    }
}
