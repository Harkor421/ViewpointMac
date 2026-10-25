package vpmac;

/** Viewpoint's own classes, by name: they live in the game's mod class loader, not ours. */
final class Vp {
    private Vp() {}

    private static final java.util.Map<String, Class<?>> found = new java.util.HashMap<>();

    static Class<?> find(String name) throws Exception {
        Class<?> c = found.get(name);
        if (c != null) return c;
        java.lang.reflect.Field g = Class.forName("me.zed_0xff.zombie_buddy.Loader").getDeclaredField("g_instrumentation");
        g.setAccessible(true);
        for (Class<?> k : ((java.lang.instrument.Instrumentation) g.get(null)).getAllLoadedClasses()) {
            if (k.getName().equals(name)) {
                found.put(name, k);
                return k;
            }
        }
        return null;
    }
}
