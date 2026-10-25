package vpmac;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Who hands the game's sprite renderer its sprites: for a few seconds, every millisecond, the
 * game's main thread is sampled, and whenever it is inside {@code zombie.core.SpriteRenderer}
 * the frames that called it are counted. Asked for with {@code hunt:<seconds>} in dump.txt.
 */
final class StackHunt implements Runnable {
    private final int seconds;

    private StackHunt(int seconds) {
        this.seconds = seconds;
    }

    static void start(int seconds) {
        Thread t = new Thread(new StackHunt(seconds), "vpmac-stack-hunt");
        t.setDaemon(true);
        t.start();
        Log.info("looking for who draws sprites, " + seconds + " s");
    }

    public void run() {
        Thread main = null;
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.getName().equals("MainThread")) main = t;
        if (main == null) {
            Log.warn("no MainThread to sample");
            return;
        }
        Map<String, int[]> callers = new HashMap<>();
        int samples = 0, inside = 0;
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        try {
            while (System.nanoTime() < end) {
                StackTraceElement[] st = main.getStackTrace();
                samples++;
                int first = -1;
                for (int i = 0; i < st.length; i++) {
                    if (st[i].getClassName().startsWith("zombie.core.SpriteRenderer")) first = i;
                    else if (first >= 0) break;
                }
                if (first >= 0) {
                    inside++;
                    StringBuilder key = new StringBuilder();
                    // Past the font and UI plumbing, to whoever asked for the text.
                    int from = first + 1;
                    while (from < st.length - 1 && (st[from].getClassName().startsWith("zombie.core.fonts") || st[from].getClassName().startsWith("zombie.ui.TextManager")
                        || st[from].getClassName().startsWith("se.krka") || st[from].getClassName().startsWith("jdk.internal") || st[from].getClassName().startsWith("java.lang.reflect"))) from++;
                    for (int i = from; i < Math.min(st.length, from + 7); i++) {
                        key.append(i == from ? "" : " < ").append(st[i].getClassName()).append('.').append(st[i].getMethodName());
                    }
                    callers.computeIfAbsent(key.toString(), k -> new int[1])[0]++;
                }
                Thread.sleep(1);
            }
        } catch (InterruptedException ignored) {
            return;
        }
        List<Map.Entry<String, int[]>> rows = new ArrayList<>(callers.entrySet());
        rows.sort((a, b) -> b.getValue()[0] - a.getValue()[0]);
        StringBuilder b = new StringBuilder("sprite senders on the main thread: inside SpriteRenderer " + inside + " of " + samples + " samples");
        for (int i = 0; i < Math.min(12, rows.size()); i++) b.append("\n   ").append(rows.get(i).getValue()[0]).append("  ").append(rows.get(i).getKey());
        Log.info(b.toString());
    }
}
