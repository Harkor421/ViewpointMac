package vpmac;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * ZombieBuddy runs this when the mod loads. On a Mac it hooks {@link Remap} into class loading
 * and rewrites any Viewpoint class already loaded; anywhere else it does nothing.
 */
public class Main {
    public static void main(String[] args) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            Log.info("not a Mac; leaving Viewpoint alone");
            return;
        }
        Instrumentation inst = instrumentation();
        if (inst == null) {
            Log.warn("ZombieBuddy gave no instrumentation; Viewpoint stays as it is");
            return;
        }
        Addons.find();
        Remap remap = new Remap();
        inst.addTransformer(remap, true);
        List<Class<?>> loaded = new ArrayList<>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (Remap.applies(c.getName().replace('.', '/')) && inst.isModifiableClass(c)) loaded.add(c);
        }
        if (!loaded.isEmpty()) {
            try {
                inst.retransformClasses(loaded.toArray(new Class<?>[0]));
            } catch (Throwable t) {
                Log.warn("could not rewrite the Viewpoint and add-on classes already loaded: " + t);
            }
        }
        SlotPatch.install(inst);
        HotReload.start(inst);
        Log.info("ready: Viewpoint's OpenGL 3-4.5 calls go through the Mac stand-ins ("
            + loaded.size() + " Viewpoint and add-on classes were already loaded, " + remap.changedClasses + " rewritten so far)");
    }

    private static Instrumentation instrumentation() {
        try {
            Class<?> loader = Class.forName("me.zed_0xff.zombie_buddy.Loader");
            Field f = loader.getDeclaredField("g_instrumentation");
            f.setAccessible(true);
            return (Instrumentation) f.get(null);
        } catch (Throwable t) {
            Log.warn("could not reach ZombieBuddy's instrumentation: " + t);
            return null;
        }
    }
}
