package vpmac;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A sampling profiler for when nothing can attach to the game: every few milliseconds it reads
 * every busy thread's stack, then writes where each thread spent its time to profile.txt.
 * Asked for with {@code profile:<seconds>} in dump.txt.
 */
final class Prof implements Runnable {
    private static volatile boolean running;
    private final int seconds;

    private Prof(int seconds) {
        this.seconds = seconds;
    }

    static void start(int seconds) {
        if (running) return;
        running = true;
        Thread t = new Thread(new Prof(seconds), "vpmac-profiler");
        t.setDaemon(true);
        t.start();
        Log.info("profiling every thread for " + seconds + " s");
    }

    public void run() {
        Map<String, int[]> perThread = new HashMap<>();   // name -> {samples, runnable}
        Map<String, int[]> self = new HashMap<>();        // thread | top frame
        Map<String, int[]> inclusive = new HashMap<>();   // thread | method anywhere on the stack
        Map<String, int[]> path = new HashMap<>();        // thread | top frame <- first frame of ours or Viewpoint's
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        int rounds = 0;
        try {
            while (System.nanoTime() < end) {
                rounds++;
                for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                    Thread t = e.getKey();
                    StackTraceElement[] st = e.getValue();
                    if (t == Thread.currentThread() || st.length == 0) continue;
                    String name = t.getName();
                    int[] c = perThread.computeIfAbsent(name, k -> new int[2]);
                    c[0]++;
                    boolean runnable = t.getState() == Thread.State.RUNNABLE && !parked(st[0]);
                    if (!runnable) continue;
                    c[1]++;
                    String top = frame(st[0]);
                    bump(self, name + " | " + top);
                    java.util.HashSet<String> seen = new java.util.HashSet<>();
                    String ours = null;
                    for (StackTraceElement f : st) {
                        String cls = f.getClassName();
                        if (!(cls.startsWith("vpmac.") || cls.startsWith("viewpoint.") || cls.startsWith("zombie.") || cls.startsWith("org.lwjgl."))) continue;
                        String m = cls + "." + f.getMethodName();
                        if (seen.add(m)) bump(inclusive, name + " | " + m);
                        if (ours == null && (cls.startsWith("vpmac.") || cls.startsWith("viewpoint.")) && f != st[0]) ours = frame(f);
                    }
                    if (ours != null) bump(path, name + " | " + top + "  <-  " + ours);
                }
                Thread.sleep(3);
            }
            int total = rounds;
            StringBuilder out = new StringBuilder();
            out.append("rounds ").append(rounds).append(" over ").append(seconds).append(" s\n\n== threads (samples, runnable) ==\n");
            perThread.entrySet().stream().filter(e -> e.getValue()[1] > total / 50)
                .sorted((a, b) -> b.getValue()[1] - a.getValue()[1])
                .forEach(e -> out.append(String.format("%6d %6d  %s%n", e.getValue()[0], e.getValue()[1], e.getKey())));
            section(out, "self (top frame while runnable)", self, 60, rounds);
            section(out, "inclusive (game, Viewpoint, ours, LWJGL)", inclusive, 160, rounds);
            section(out, "top frame <- nearest Viewpoint/our frame", path, 80, rounds);
            Files.writeString(Log.dir().resolve("profile.txt"), out.toString());
            Log.info("profile written to profile.txt (" + rounds + " rounds)");
        } catch (Throwable t) {
            Log.warn("profiler stopped: " + t);
        } finally {
            running = false;
        }
    }

    private static boolean parked(StackTraceElement f) {
        String m = f.getClassName() + "." + f.getMethodName();
        return m.startsWith("jdk.internal.misc.Unsafe.park") || m.startsWith("java.lang.Thread.sleep") || m.startsWith("java.lang.Object.wait")
            || m.startsWith("sun.nio.ch.") && m.contains("poll") || m.contains("accept0") || m.contains("socketRead") || m.contains("KQueue");
    }

    private static String frame(StackTraceElement f) {
        return f.getClassName() + "." + f.getMethodName() + (f.getLineNumber() > 0 ? ":" + f.getLineNumber() : "");
    }

    private static void bump(Map<String, int[]> m, String k) {
        m.computeIfAbsent(k, x -> new int[1])[0]++;
    }

    private static void section(StringBuilder out, String title, Map<String, int[]> m, int limit, int rounds) {
        out.append("\n== ").append(title).append(" (samples, % of rounds) ==\n");
        List<Map.Entry<String, int[]>> list = new ArrayList<>(m.entrySet());
        list.sort((a, b) -> b.getValue()[0] - a.getValue()[0]);
        for (int i = 0; i < Math.min(limit, list.size()); i++) {
            Map.Entry<String, int[]> e = list.get(i);
            out.append(String.format("%6d %5.1f%%  %s%n", e.getValue()[0], 100.0 * e.getValue()[0] / Math.max(1, rounds), e.getKey()));
        }
    }
}
