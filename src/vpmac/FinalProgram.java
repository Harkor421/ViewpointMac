package vpmac;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which programs draw into framebuffers that aren't Viewpoint's, over one second ({@code
 * finalprog} in dump.txt). The one that does so once a frame composites Viewpoint's final image
 * into the game's framebuffer, and {@code after:N} on it saves that image without the game's
 * interface (tools/screenshot.sh does both).
 */
final class FinalProgram {
    private FinalProgram() {}

    static volatile boolean on;
    private static final Map<String, Integer> seen = new LinkedHashMap<>();
    private static long until;

    /** After each draw while {@link #on}. */
    static void saw(int program, int fbo) {
        if (until == 0) until = System.nanoTime() + 1_000_000_000L;
        if (Fbo.attachments(fbo) == null) seen.merge("program " + program + " -> framebuffer " + fbo, 1, Integer::sum);
        if (System.nanoTime() < until) return;
        Log.info("draws into the game's framebuffers over 1 s: " + seen);
        seen.clear();
        until = 0;
        on = false;
    }
}
