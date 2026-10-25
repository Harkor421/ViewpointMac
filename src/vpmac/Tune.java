package vpmac;

/**
 * Viewpoint's live settings from dump.txt, for experiments: {@code vp:key=value} sets one the way
 * its settings window does, without saving it; {@code vp?key} logs its value. Nothing here writes
 * the settings file, so a restart brings back what the player chose.
 */
final class Tune {
    private Tune() {}

    static void set(String assignment) {
        int eq = assignment.indexOf('=');
        if (eq < 0) return;
        String key = assignment.substring(0, eq).trim(), value = assignment.substring(eq + 1).trim();
        try {
            String before = get(key);
            java.lang.reflect.Method put = Vp.find("viewpoint.platform.LiveSettings").getDeclaredMethod("put", String.class, String.class);
            put.setAccessible(true);
            put.invoke(null, key, value);
            Log.info("setting " + key + ": " + before + " -> " + get(key) + " (not saved)");
        } catch (Exception e) {
            Log.warn("could not set " + key + ": " + e);
        }
    }

    static String get(String key) {
        try {
            java.lang.reflect.Method value = Vp.find("viewpoint.platform.LiveSettings").getDeclaredMethod("value", String.class);
            value.setAccessible(true);
            return String.valueOf(value.invoke(null, key));
        } catch (Exception e) {
            return "? (" + e + ")";
        }
    }
}
