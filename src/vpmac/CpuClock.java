package vpmac;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CPU time each thread really used over a few seconds ({@code cpu:S} in dump.txt). The stack
 * profile counts a thread blocked inside the driver as running; this tells working from waiting:
 * a render thread busy all the time but on the CPU half of it is waiting for the GPU.
 */
final class CpuClock {
    private CpuClock() {}

    static void start(int seconds) {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        if (!mx.isThreadCpuTimeSupported()) {
            Log.info("cpu: this JVM can't tell a thread's CPU time");
            return;
        }
        Map<Thread, Long> before = new HashMap<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) before.put(t, mx.getThreadCpuTime(t.threadId()));
        long from = System.nanoTime();
        Thread waiter = new Thread(() -> {
            try {
                Thread.sleep(seconds * 1000L);
            } catch (InterruptedException e) {
                return;
            }
            double wall = (System.nanoTime() - from) / 1e9;
            List<Object[]> rows = new ArrayList<>();
            for (Map.Entry<Thread, Long> e : before.entrySet()) {
                long now = mx.getThreadCpuTime(e.getKey().threadId());
                if (now < 0 || e.getValue() < 0) continue;
                rows.add(new Object[] {e.getKey().getName(), (now - e.getValue()) / 1e9 / wall});
            }
            rows.sort((a, b) -> Double.compare((double) b[1], (double) a[1]));
            StringBuilder b = new StringBuilder(String.format("CPU seconds per second over %.1f s, by thread:", wall));
            for (int i = 0; i < Math.min(8, rows.size()); i++) b.append(String.format("%n   %.2f  %s", rows.get(i)[1], rows.get(i)[0]));
            Log.info(b.toString());
        }, "ViewpointMac cpu clock");
        waiter.setDaemon(true);
        waiter.start();
    }
}
