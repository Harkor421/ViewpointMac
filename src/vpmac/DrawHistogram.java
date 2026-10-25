package vpmac;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One frame of the game's own draws ({@link GameDraws}), grouped by program, texture on unit 0 and
 * index count, then logged: what makes up the thousands of them. Asked for with {@code gamedraws}.
 */
final class DrawHistogram {
    private DrawHistogram() {}

    static volatile boolean wanted, recording;
    private static Map<String, int[]> rows;

    /** A Viewpoint frame went by: start recording if asked, stop and log after one whole frame. */
    static void frame() {
        if (recording) {
            recording = false;
            log();
        } else if (wanted) {
            wanted = false;
            rows = new HashMap<>();
            recording = true;
        }
    }

    static void record(int indices) {
        String key = "program " + GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) + " texture " + GL11.glGetInteger(0x8069) + " indices " + indices;
        rows.computeIfAbsent(key, k -> new int[1])[0]++;
    }

    private static void log() {
        List<Map.Entry<String, int[]>> list = new ArrayList<>(rows.entrySet());
        list.sort((a, b) -> b.getValue()[0] - a.getValue()[0]);
        int total = 0;
        for (Map.Entry<String, int[]> e : list) total += e.getValue()[0];
        StringBuilder b = new StringBuilder("one frame of game draws: " + total + " in " + list.size() + " kinds");
        for (int i = 0; i < Math.min(15, list.size()); i++) b.append("\n   ").append(list.get(i).getValue()[0]).append("  ").append(list.get(i).getKey());
        Log.info(b.toString());
        rows = null;
    }
}
