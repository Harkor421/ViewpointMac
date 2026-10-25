package vpmac;

/**
 * Counts of what the layer does, and timed reports of them, for tuning without a profiler:
 * {@code stats:<seconds>} in dump.txt logs the rates per second, {@code gpu:<seconds>} turns on
 * {@link Timers} and logs Viewpoint's own per-pass GPU times.
 *
 * <p>Once a game, a minute after Viewpoint starts drawing, both run by themselves (10 s each,
 * frames per second included), so a session someone else plays leaves its numbers in the log.
 * A file {@code no-auto-measure} next to dump.txt turns that off.
 */
final class Stats {
    private Stats() {}

    static long draws, commands, binds, samplerChanges, vaoBinds, attribApplies, atlasCopies, atlasTexels,
        bufferRows, readbacks, uniforms;

    private static long[] start;
    private static long statsUntil, statsFrom, gpuUntil, gpuFrom, gpuSettled;

    private static long[] snapshot() {
        return new long[] {draws, commands, binds, samplerChanges, vaoBinds, attribApplies, atlasCopies, atlasTexels,
            bufferRows, readbacks, uniforms};
    }

    private static final String[] NAMES = {"draw calls", "draw commands", "texture binds", "sampler changes", "vertex array binds",
        "attribute updates", "atlas copies", "atlas texels", "buffer texture rows", "depth reads", "uniform sets"};

    static void startStats(int seconds) {
        start = snapshot();
        statsFrames = frames();
        statsFrom = System.nanoTime();
        statsUntil = statsFrom + seconds * 1_000_000_000L;
        Log.info("counting for " + seconds + " s");
    }

    static void startGpu(int seconds) {
        gpuReport(); // throws away what Viewpoint averaged before
        Timers.on = true;
        Timers.ownReport(1);
        // Passes that began before the timers came on read as running since the last session:
        // a second in, what Viewpoint averaged so far is thrown away, and the window starts there.
        gpuSettled = System.nanoTime() + 1_000_000_000L;
        gpuFrom = gpuSettled;
        gpuUntil = gpuFrom + seconds * 1_000_000_000L;
        Log.info("GPU timers on for " + seconds + " s");
    }

    private static long autoFrom, statsFrames;
    private static int autoStep;

    /** Frames Viewpoint has presented, from its own counter; -1 when it can't be read. */
    private static long frames() {
        try {
            java.lang.reflect.Field f = Vp.find("viewpoint.platform.FrameTimes").getDeclaredField("count");
            f.setAccessible(true);
            return f.getLong(null);
        } catch (Exception e) {
            return -1;
        }
    }

    private static void auto(long now) {
        if (autoStep == 0) {
            // Real frames, not the few draws Viewpoint makes while warming up in the menu.
            if (draws < 20_000) return;
            autoStep = java.nio.file.Files.exists(Log.dir().resolve("no-auto-measure")) ? 9 : 1;
            autoFrom = now + 60_000_000_000L;
        } else if (autoStep == 1 && now >= autoFrom) {
            Log.info("measuring on its own for 20 s (frame rate, counts, then GPU passes)");
            startStats(10);
            autoStep = 2;
        } else if (autoStep == 2 && statsUntil == 0) {
            startGpu(10);
            autoStep = 3;
        }
    }

    /** From {@link Dump#poll}, twice a second. */
    static void tick() {
        long now = System.nanoTime();
        if (autoStep < 3) auto(now);
        if (statsUntil != 0 && now >= statsUntil) {
            long[] end = snapshot();
            double s = (now - statsFrom) / 1e9;
            StringBuilder b = new StringBuilder("per second:");
            for (int i = 0; i < NAMES.length; i++) b.append(' ').append(NAMES[i]).append(' ').append(Math.round((end[i] - start[i]) / s)).append(',');
            long f = frames();
            Log.info(b.substring(0, b.length() - 1) + (f >= 0 && statsFrames >= 0 ? String.format("; %.1f frames per second", (f - statsFrames) / s) : ""));
            statsUntil = 0;
        }
        if (gpuSettled != 0 && now >= gpuSettled) {
            gpuReport();
            Timers.ownReport(1);
            gpuSettled = 0;
        }
        if (gpuUntil != 0 && now >= gpuUntil) {
            String r = gpuReport();
            Timers.stop();
            Log.info("Viewpoint's GPU passes" + (r == null ? ": no report" : r));
            Log.info("the layer's own GPU work: " + Timers.ownReport((now - gpuFrom) / 1e9));
            gpuUntil = 0;
        }
    }

    private static String gpuReport() {
        try {
            Class<?> parts = Vp.find("viewpoint.platform.GpuParts");
            return parts == null ? null : (String) parts.getMethod("report").invoke(null);
        } catch (Exception e) {
            Log.warn("no GPU report: " + e);
            return null;
        }
    }
}
