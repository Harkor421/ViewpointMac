package vpmac;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * For development: when {@code ~/Zomboid/viewpointmac/dev.txt} names a folder of compiled vpmac
 * classes, any class rebuilt there replaces the running one, so a fix reaches the game without
 * restarting it. Only method bodies can change this way, but a new top-level class is added as it
 * is, so new state and helpers can live there; a new field or method in a loaded class needs a restart.
 */
final class HotReload {
    private HotReload() {}

    static void start(Instrumentation inst) {
        Path config = Log.dir().resolve("dev.txt");
        if (!Files.isRegularFile(config)) return;
        Path dir;
        try {
            dir = Path.of(Files.readString(config).trim());
        } catch (Exception e) {
            return;
        }
        Thread t = new Thread(() -> watch(inst, dir.resolve("vpmac")), "ViewpointMac hot reload");
        t.setDaemon(true);
        t.start();
        Log.info("hot reload: watching " + dir);
    }

    private static void watch(Instrumentation inst, Path dir) {
        Map<Path, Long> seen = new HashMap<>();
        scan(dir, seen);
        while (true) {
            try {
                Thread.sleep(1000);
                Map<Path, Long> now = new HashMap<>();
                scan(dir, now);
                List<ClassDefinition> changed = new ArrayList<>();
                List<Path> added = new ArrayList<>();
                for (Map.Entry<Path, Long> e : now.entrySet()) {
                    Long before = seen.get(e.getKey());
                    if (before != null && before.equals(e.getValue())) continue;
                    String name = "vpmac." + e.getKey().getFileName().toString().replace(".class", "");
                    Class<?> c = loaded(inst, name);
                    if (c != null) changed.add(new ClassDefinition(c, Files.readAllBytes(e.getKey())));
                    else if (before == null && !name.contains("$")) added.add(e.getKey());
                }
                seen = now;
                if (changed.isEmpty() && added.isEmpty()) continue;
                // Let the build finish writing every file before reading them.
                Thread.sleep(500);
                // A class the game has never loaded is defined next to the others, so the changed
                // ones can use it (and keep new state in it).
                java.lang.invoke.MethodHandles.Lookup here = java.lang.invoke.MethodHandles.lookup();
                for (Path f : added) {
                    try {
                        here.defineClass(Files.readAllBytes(f));
                        Log.info("hot reload: added " + f.getFileName());
                    } catch (LinkageError ignored) {
                        // already in the mod's jar; it loads from there
                    }
                }
                if (changed.isEmpty()) continue;
                List<ClassDefinition> fresh = new ArrayList<>();
                for (ClassDefinition d : changed) {
                    Path f = dir.resolve(d.getDefinitionClass().getName().substring("vpmac.".length()) + ".class");
                    fresh.add(new ClassDefinition(d.getDefinitionClass(), Files.readAllBytes(f)));
                }
                inst.redefineClasses(fresh.toArray(new ClassDefinition[0]));
                Log.info("hot reload: " + fresh.size() + " classes replaced");
            } catch (UnsupportedOperationException e) {
                Log.warn("hot reload: a class changed its fields or methods; restart the game for it (" + e.getMessage() + ")");
            } catch (Throwable e) {
                Log.warn("hot reload failed: " + e);
            }
        }
    }

    private static void scan(Path dir, Map<Path, Long> out) {
        // For test runs: "skip-loading" in the ViewpointMac folder passes the "click to start"
        // screen the way a click would, so nobody has to sit at the game.
        Path skip = Log.dir().resolve("skip-loading");
        if (Files.exists(skip)) {
            try {
                Files.delete(skip);
                Object machine = Class.forName("zombie.GameWindow").getField("states").get(null);
                Object state = machine.getClass().getField("current").get(machine);
                if (state != null && state.getClass().getSimpleName().equals("GameLoadingState")) {
                    java.lang.reflect.Field f = state.getClass().getDeclaredField("forceDone");
                    f.setAccessible(true);
                    f.setBoolean(state, true);
                    Log.info("loading screen passed");
                } else {
                    Log.info("no loading screen to pass (" + (state == null ? "none" : state.getClass().getSimpleName()) + ")");
                }
            } catch (Throwable e) {
                Log.warn("could not pass the loading screen: " + e);
            }
        }
        // "revive": after a crash Viewpoint never draws again; this lets it try again.
        Path revive = Log.dir().resolve("revive");
        if (Files.exists(revive)) {
            try {
                Files.delete(revive);
                java.lang.reflect.Field g = Class.forName("me.zed_0xff.zombie_buddy.Loader").getDeclaredField("g_instrumentation");
                g.setAccessible(true);
                for (Class<?> c : ((Instrumentation) g.get(null)).getAllLoadedClasses()) {
                    if (!c.getName().equals("viewpoint.SceneDrawer")) continue;
                    java.lang.reflect.Field failed = c.getDeclaredField("failed");
                    failed.setAccessible(true);
                    failed.setBoolean(null, false);
                    Log.info("Viewpoint may draw again");
                }
            } catch (Throwable e) {
                Log.warn("could not revive Viewpoint: " + e);
            }
        }
        // "patch-game-draws": the running game's sprite draws go through GameDraws (counted, state on request).
        Path gameDraws = Log.dir().resolve("patch-game-draws");
        if (Files.exists(gameDraws)) {
            try {
                Files.delete(gameDraws);
                java.lang.reflect.Field g = Class.forName("me.zed_0xff.zombie_buddy.Loader").getDeclaredField("g_instrumentation");
                g.setAccessible(true);
                GameDrawsPatch.install((Instrumentation) g.get(null));
            } catch (Throwable e) {
                Log.warn("could not watch the game's draws: " + e);
            }
        }
        // "patch-slots": the running game gets SlotPatch, which a fresh start installs in Main.
        Path slots = Log.dir().resolve("patch-slots");
        if (Files.exists(slots)) {
            try {
                Files.delete(slots);
                java.lang.reflect.Field g = Class.forName("me.zed_0xff.zombie_buddy.Loader").getDeclaredField("g_instrumentation");
                g.setAccessible(true);
                SlotPatch.install((Instrumentation) g.get(null));
            } catch (Throwable e) {
                Log.warn("could not patch the model batches: " + e);
            }
        }
        try (Stream<Path> st = Files.list(dir)) {
            st.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                try {
                    out.put(p, Files.getLastModifiedTime(p).toMillis());
                } catch (Exception ignored) {
                    // being rewritten; next pass
                }
            });
        } catch (Exception ignored) {
            // folder missing between builds
        }
    }

    private static Class<?> loaded(Instrumentation inst, String name) {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.getName().equals(name)) return c;
        }
        return null;
    }
}
