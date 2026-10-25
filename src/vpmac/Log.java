package vpmac;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Lines in the game's console, each kind of warning once. */
final class Log {
    private Log() {}

    private static final Set<String> said = new HashSet<>();

    static void info(String msg) {
        System.out.println("[ViewpointMac] " + msg);
    }

    static void warn(String msg) {
        System.out.println("[ViewpointMac] WARN " + msg);
    }

    static void once(String key, String msg) {
        if (said.add(key)) warn(msg);
    }

    static Path dir() {
        return Path.of(System.getProperty("user.home"), "Zomboid", "viewpointmac");
    }
}
