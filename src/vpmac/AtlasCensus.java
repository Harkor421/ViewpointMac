package vpmac;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Atlas copies by texture for a few seconds ({@code atlas:S} in dump.txt): how often each texture
 * is copied, into how many different sheets, and how big it is, to see which copies repeat.
 */
final class AtlasCensus {
    private AtlasCensus() {}

    static volatile boolean on;
    private static long until, from;
    private static final Map<Integer, long[]> byTexture = new HashMap<>();   // copies, texels, w, h
    private static final Map<Integer, java.util.Set<Integer>> sheets = new HashMap<>();

    static void start(int seconds) {
        byTexture.clear();
        sheets.clear();
        from = System.nanoTime();
        until = from + seconds * 1_000_000_000L;
        on = true;
    }

    static void copy(int sheetTexture, int texture, int w, int h, int ifmt) {
        long[] t = byTexture.computeIfAbsent(texture, k -> new long[5]);
        t[0]++;
        t[1] += (long) w * h;
        t[2] = w;
        t[3] = h;
        t[4] = ifmt;
        sheets.computeIfAbsent(texture, k -> new java.util.HashSet<>()).add(sheetTexture);
        long now = System.nanoTime();
        if (now < until) return;
        on = false;
        double s = (now - from) / 1e9;
        List<Map.Entry<Integer, long[]>> rows = new ArrayList<>(byTexture.entrySet());
        rows.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
        StringBuilder b = new StringBuilder(String.format("atlas copies over %.1f s, by texture (copies/s, Mtexels/s, size, format, sheets):", s));
        for (int i = 0; i < Math.min(14, rows.size()); i++) {
            long[] v = rows.get(i).getValue();
            b.append(String.format("%n   %d: %.0f/s, %.1f, %dx%d, 0x%x, %d", rows.get(i).getKey(), v[0] / s, v[1] / s / 1e6, v[2], v[3], v[4],
                sheets.get(rows.get(i).getKey()).size()));
        }
        Log.info(b.toString());
    }
}
