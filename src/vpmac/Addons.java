package vpmac;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Java mods built on Viewpoint: every installed mod whose mod.info requires Viewpoint has its Java
 * package ({@code javaPkgName}) rewritten like Viewpoint's own, so an add-on's GL calls and shaders
 * reach the Mac stand-ins too. Nothing is listed by hand: a new add-on is picked up the next time
 * the game starts. {@code ~/Zomboid/viewpointmac/packages.txt} can add packages (one a line) for a
 * mod that draws for Viewpoint without declaring it.
 */
final class Addons {
    private Addons() {}

    /** Internal-name prefixes ("vptw/"), Viewpoint's own included. */
    static final Set<String> packages = new LinkedHashSet<>(List.of("viewpoint/"));
    /** Never rewritten: this mod (its calls are the real ones) and anything the game or Java owns. */
    private static final String[] NEVER = {"vpmac/", "zombie/", "java/", "javax/", "jdk/", "sun/", "org/lwjgl/", "org/joml/",
        "net/bytebuddy/", "me/zed_0xff/", "se/krka/", "fmod/", "com/sun/"};
    /** Bare top-level names: a package this short would take in every library under it. */
    private static final Set<String> TOO_WIDE = Set.of("com/", "org/", "net/", "io/", "de/", "ru/", "fr/", "uk/", "me/", "dev/");

    static boolean covers(String internalName) {
        if (internalName == null) return false;
        for (String p : packages) if (internalName.startsWith(p)) return true;
        return false;
    }

    static void find() {
        List<String> found = new ArrayList<>();
        for (Path root : roots()) scan(root, found);
        Path extra = Log.dir().resolve("packages.txt");
        try {
            if (Files.isRegularFile(extra)) {
                for (String line : Files.readAllLines(extra)) add(line.trim(), "packages.txt", found);
            }
        } catch (Exception e) {
            Log.warn("packages.txt unreadable: " + e);
        }
        Log.info("Viewpoint add-ons whose GL goes through the Mac stand-ins: " + (found.isEmpty() ? "none" : String.join(", ", found)));
    }

    /** Every mod.info under {@code root}, six folders deep; a folder that can't be read is skipped, not the rest. */
    static void scan(Path root, List<String> found) {
        if (!Files.isDirectory(root)) return;
        try {
            Files.walkFileTree(root, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 6,
                new java.nio.file.SimpleFileVisitor<Path>() {
                    @Override
                    public java.nio.file.FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a) {
                        if (f.getFileName().toString().equals("mod.info")) read(f, found);
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }

                    @Override
                    public java.nio.file.FileVisitResult visitFileFailed(Path f, java.io.IOException e) {
                        return java.nio.file.FileVisitResult.CONTINUE; // an unreadable folder: skip it, keep looking
                    }
                });
        } catch (Exception e) {
            Log.warn("could not look for Viewpoint add-ons under " + root + ": " + e);
        }
    }

    /** Where mods live: the Workshop folder of this game, and the local mod folders. */
    private static List<Path> roots() {
        List<Path> roots = new ArrayList<>();
        Path home = Paths.get(System.getProperty("user.home"));
        roots.add(home.resolve("Zomboid/mods"));
        roots.add(home.resolve("Zomboid/Workshop"));
        Path dir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (p.getFileName() != null && p.getFileName().toString().equals("steamapps")) {
                roots.add(p.resolve("workshop/content/108600"));
                break;
            }
        }
        Path steam = home.resolve("Library/Application Support/Steam/steamapps/workshop/content/108600");
        if (!roots.contains(steam)) roots.add(steam);
        return roots;
    }

    static void read(Path info, List<String> found) {
        String id = "", pkg = "";
        boolean viewpoint = false;
        try {
            for (String line : Files.readAllLines(info)) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim().toLowerCase(), value = line.substring(eq + 1).trim();
                if (key.equals("id")) id = value;
                else if (key.equals("javapkgname")) pkg = value;
                else if (key.equals("require")) {
                    for (String r : value.split(",")) viewpoint |= r.trim().replace("\\", "").equals("Viewpoint");
                }
            }
        } catch (Exception e) {
            return;
        }
        if (viewpoint && !pkg.isEmpty()) add(pkg, id, found);
    }

    static void add(String pkg, String from, List<String> found) {
        if (pkg.isEmpty() || pkg.startsWith("#")) return;
        String internal = pkg.replace('.', '/');
        if (!internal.endsWith("/")) internal += "/";
        if (TOO_WIDE.contains(internal)) {
            Log.warn("not rewriting package " + pkg + " (from " + from + "): too wide");
            return;
        }
        for (String n : NEVER) {
            if (internal.startsWith(n) || n.startsWith(internal)) {
                Log.warn("not rewriting package " + pkg + " (from " + from + "): it belongs to the game, Java or this mod");
                return;
            }
        }
        if (packages.add(internal)) found.add(from + " (" + pkg + ")");
    }
}
