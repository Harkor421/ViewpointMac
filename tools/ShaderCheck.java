import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import vpmac.Glsl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Offline check: expands Viewpoint's shaders the way its Shaders class does, runs them through
 * {@link Glsl} and compiles them in a Mac legacy (2.1) context, without starting the game.
 *
 * <p>Usage: ShaderCheck &lt;viewpoint/shaders dir&gt; &lt;constants.glsl&gt; [out dir] [name filter]
 */
public class ShaderCheck {
    static final Pattern INCLUDE = Pattern.compile("^\\s*#include\\s+\"([^\"]+)\"\\s*$");
    static Path root;
    static String constants;

    public static void main(String[] args) throws Exception {
        root = Path.of(args[0]);
        constants = Files.readString(Path.of(args[1]));
        Path outDir = args.length > 2 ? Path.of(args[2]) : null;
        String filter = args.length > 3 ? args[3] : null;

        if (!GLFW.glfwInit()) throw new IllegalStateException("glfwInit");
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long w = GLFW.glfwCreateWindow(64, 64, "check", 0, 0);
        GLFW.glfwMakeContextCurrent(w);
        GL.createCapabilities();
        System.out.println("GL " + GL11.glGetString(GL11.GL_VERSION));

        List<Path> files = new ArrayList<>();
        try (Stream<Path> st = Files.list(root)) {
            st.filter(p -> p.toString().endsWith(".vert") || p.toString().endsWith(".frag")).sorted().forEach(files::add);
        }
        int ok = 0, bad = 0;
        for (Path f : files) {
            String name = f.getFileName().toString();
            if (filter != null && !name.contains(filter)) continue;
            boolean vertex = name.endsWith(".vert");
            String expanded = withPipelineDefines(expand(name));
            Glsl.Result r = Glsl.translate(expanded, vertex);
            if (outDir != null) {
                Files.createDirectories(outDir);
                Files.writeString(outDir.resolve(name), r.source);
            }
            int sh = GL20.glCreateShader(vertex ? GL20.GL_VERTEX_SHADER : GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(sh, r.source);
            GL20.glCompileShader(sh);
            if (GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) == GL11.GL_TRUE) {
                ok++;
            } else {
                bad++;
                String log = GL20.glGetShaderInfoLog(sh);
                System.out.println("=== FAIL " + name);
                String[] lines = log.split("\n");
                for (int i = 0; i < Math.min(lines.length, 6); i++) System.out.println("   " + lines[i]);
            }
            GL20.glDeleteShader(sh);
        }
        System.out.println("compiled " + ok + " ok, " + bad + " failed");
        if (filter == null) linkPairs(files);
        if (System.getProperty("samplers") != null) samplerBudget(files, System.getProperty("samplers"));
        GLFW.glfwTerminate();
    }

    /**
     * Links each fragment shader with the first vertex shader it links with (Viewpoint pairs them in
     * Java); a fragment shader that links with none is reported with the errors of its likeliest pair.
     */
    static void linkPairs(List<Path> files) throws Exception {
        List<String> verts = new ArrayList<>();
        java.util.Map<String, Glsl.Result> translated = new java.util.HashMap<>();
        java.util.Map<String, String> sources = new java.util.HashMap<>();
        for (Path f : files) {
            String name = f.getFileName().toString();
            sources.put(name, withPipelineDefines(expand(name)));
            translated.put(name, Glsl.translate(sources.get(name), name.endsWith(".vert")));
            if (name.endsWith(".vert")) verts.add(name);
        }
        int ok = 0, bad = 0, viaAtlas = 0;
        for (Path f : files) {
            String name = f.getFileName().toString();
            if (!name.endsWith(".frag")) continue;
            String base = name.substring(0, name.length() - 5);
            List<String> order = new ArrayList<>();
            if (verts.contains(base + ".vert")) order.add(base + ".vert");
            order.add("screen.vert");
            for (String v : verts) if (!order.contains(v)) order.add(v);
            String firstLog = null, linkedWith = null;
            int packed = 0;
            for (String vert : order) {
                String log = link(translated.get(vert), translated.get(name));
                if (log == null) { linkedWith = vert; break; }
                if (firstLog == null) firstLog = vert + ": " + log;
                if (log.contains("Implementation limit")) {
                    // Over 16 units: pack into the atlas the way the game does (Prog.link) and try again.
                    List<String> pack = new ArrayList<>();
                    Glsl.Result v = translated.get(vert), fr = translated.get(name);
                    int kept = Glsl.MODEL_TEXTURES;
                    for (int attempt = 0; attempt < 16 && log != null; attempt++) {
                        Matcher m = OVER_LIMIT.matcher(log);
                        if (!m.find()) break;
                        int more = Integer.parseInt(m.group(1)) - 16 + (pack.isEmpty() ? 1 : 0);
                        List<String> candidates = new ArrayList<>(fr.packable);
                        for (String n : v.packable) if (!candidates.contains(n)) candidates.add(n);
                        candidates.removeAll(pack);
                        if (candidates.isEmpty()) {
                            // As Prog.link: nothing left for the atlas, so fewer model textures.
                            if (v.modelTextures == 0 && fr.modelTextures == 0 || kept <= Glsl.MIN_MODEL_TEXTURES) break;
                            kept--;
                        }
                        for (int i = 0; i < candidates.size() && more > 0; i++, more--) pack.add(candidates.get(i));
                        v = Glsl.translate(sources.get(vert), true, pack, kept);
                        fr = Glsl.translate(sources.get(name), false, pack, kept);
                        log = link(v, fr);
                        if (System.getProperty("verbose") != null) {
                            System.out.println("   " + name + " packs " + pack + " (vertex " + v.packed + ", fragment " + fr.packed + "): "
                                + (log == null ? "links" : log.lines().filter(l -> l.contains("ERROR")).findFirst().orElse(log)));
                        }
                    }
                    if (log == null) {
                        linkedWith = vert;
                        packed = pack.size();
                        if (kept < Glsl.MODEL_TEXTURES) System.out.println("    " + name + " keeps " + kept + " model textures");
                        break;
                    }
                    firstLog = vert + ": " + log;
                    break;
                }
            }
            if (linkedWith != null) {
                ok++;
                if (packed > 0) {
                    viaAtlas++;
                    System.out.println("    " + name + " links with " + linkedWith + " with " + packed + " textures in the atlas");
                }
            } else {
                bad++;
                System.out.println("=== LINK FAIL " + name);
                // The errors, not the warnings about unused varyings that come first.
                List<String> lines = new ArrayList<>(List.of(firstLog.split("\n")));
                String vert = firstLog.substring(0, Math.max(0, firstLog.indexOf(':')));
                List<String> errors = lines.stream().filter(l -> l.contains("ERROR") || l.contains("limit")).toList();
                List<String> shown = errors.isEmpty() ? lines : errors;
                System.out.println("   with " + vert);
                for (int i = 0; i < Math.min(shown.size(), 3); i++) System.out.println("   " + shown.get(i).replace(vert + ": ", ""));
            }
        }
        System.out.println("linked " + ok + " ok (" + viaAtlas + " through the atlas), " + bad + " failed");
    }

    /** For the named vert+frag pairs, shows the sampler count with every define on, then with each one off. */
    static void samplerBudget(List<Path> files, String pairs) throws Exception {
        for (String pair : pairs.split(",")) {
            String[] vf = pair.split("\\+");
            String v = expand(vf[0]), f = expand(vf[1]);
            Set<String> names = new java.util.TreeSet<>();
            Matcher m = Pattern.compile("\\b((?:PIPELINE|OPTION)_\\w+)").matcher(v + f);
            while (m.find()) names.add(m.group(1));
            System.out.println("== " + pair + " all on: " + samplers(v, f, Set.of()));
            for (String off : names) System.out.println("   off " + off + ": " + samplers(v, f, Set.of(off)));
        }
    }

    static String samplers(String v, String f, Set<String> off) {
        String log = link(Glsl.translate(defines(v, off), true), Glsl.translate(defines(f, off), false));
        if (log == null) return "links";
        Matcher m = Pattern.compile("use[s]? (\\d+) samplers").matcher(log);
        String worst = "?";
        while (m.find()) worst = m.group(1);
        return worst;
    }

    static String defines(String src, Set<String> off) {
        String withAll = withPipelineDefines(src);
        for (String o : off) withAll = withAll.replace("#define " + o + " 1\n", "#define " + o + " 0\n");
        return withAll;
    }

    /** Returns null when the pair links, else the info log. */
    private static final Pattern OVER_LIMIT = Pattern.compile("(?:vertex shader|fragment shader|linked shaders) uses? (\\d+) samplers");

    static String link(Glsl.Result vr, Glsl.Result fr) {
        int prog = GL20.glCreateProgram();
        int vs = compile(GL20.GL_VERTEX_SHADER, vr.source), fs = compile(GL20.GL_FRAGMENT_SHADER, fr.source);
        GL20.glAttachShader(prog, vs);
        GL20.glAttachShader(prog, fs);
        vr.locations.forEach((n, l) -> GL20.glBindAttribLocation(prog, l, n));
        fr.locations.forEach((n, l) -> org.lwjgl.opengl.EXTGPUShader4.glBindFragDataLocationEXT(prog, l, n));
        GL20.glLinkProgram(prog);
        String log = GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_TRUE ? null : GL20.glGetProgramInfoLog(prog);
        GL20.glDeleteProgram(prog);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        return log;
    }

    static int compile(int type, String src) {
        int sh = GL20.glCreateShader(type);
        GL20.glShaderSource(sh, src);
        GL20.glCompileShader(sh);
        return sh;
    }

    /**
     * The OPTION_ defines Viewpoint makes from a pack's settings, at their defaults, for the packs
     * named by {@code -Dpacks=dir:dir...} (the base first): true/false as 1/0, a list of words as the
     * default's index plus one constant per word, a list of numbers as the number, a range with a
     * fractional step as a float.
     */
    static java.util.Map<String, String> packDefines() throws Exception {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        String packs = System.getProperty("packs");
        if (packs == null) return out;
        for (String dir : packs.split(":")) {
            Path props = Path.of(dir, "pack.properties");
            if (!Files.isRegularFile(props)) continue;
            java.util.Properties p = new java.util.Properties();
            try (var in = Files.newBufferedReader(props)) { p.load(in); }
            for (String key : p.stringPropertyNames()) {
                if (!key.startsWith("option.") || !key.endsWith(".default")) continue;
                String name = key.substring(7, key.length() - 8), def = p.getProperty(key).trim();
                String macro = "OPTION_" + snake(name);
                String values = p.getProperty("option." + name + ".values"), range = p.getProperty("option." + name + ".range");
                if (def.equals("true") || def.equals("false")) {
                    out.put(macro, def.equals("true") ? "1" : "0");
                } else if (values != null) {
                    String[] v = values.trim().split("\\s+");
                    boolean numbers = java.util.Arrays.stream(v).allMatch(x -> x.matches("-?\\d+(\\.\\d+)?"));
                    if (numbers) {
                        out.put(macro, def);
                    } else {
                        for (int i = 0; i < v.length; i++) {
                            out.put(macro + "_" + snake(v[i]), String.valueOf(i));
                            if (v[i].equals(def)) out.put(macro, String.valueOf(i));
                        }
                    }
                } else {
                    boolean fraction = def.contains(".") || (range != null && range.trim().split("\\s+").length > 2 && range.trim().split("\\s+")[2].contains("."));
                    out.put(macro, fraction ? Float.toString(Float.parseFloat(def)) : def);
                }
            }
        }
        return out;
    }

    /** faceShading -> FACE_SHADING, taaPlus -> TAA_PLUS. */
    static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase();
    }

    /** Viewpoint defines PIPELINE_* and OPTION_* from its settings at runtime; turn them all on here. */
    static String withPipelineDefines(String src) {
        String file = System.getProperty("defines");
        if (file != null) {
            try {
                String defs = Files.readString(Path.of(file));
                // This pack's own options over the captured ones, and any name still missing turned on.
                for (var e : packDefines().entrySet()) {
                    String line = "#define " + e.getKey() + " " + e.getValue();
                    defs = defs.contains("#define " + e.getKey() + " ")
                        ? defs.replaceAll("(?m)^#define " + e.getKey() + " .*$", Matcher.quoteReplacement(line)) : defs + line + "\n";
                }
                Matcher used = Pattern.compile("\\b((?:PIPELINE|OPTION)_\\w+)").matcher(src);
                Set<String> missing = new java.util.TreeSet<>();
                while (used.find()) if (!defs.contains("#define " + used.group(1) + " ")) missing.add(used.group(1));
                for (String n : missing) defs += "#define " + n + " 1\n";
                for (String o : System.getProperty("off", "").split(",")) {
                    if (o.isEmpty()) continue;
                    defs = defs.replaceAll("(?m)^#define " + o + " .*$", "#define " + o + " 0");
                }
                int nl = src.indexOf('\n');
                return src.substring(0, nl + 1) + defs + src.substring(nl + 1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        Set<String> names = new java.util.TreeSet<>();
        Matcher m = Pattern.compile("\\b((?:PIPELINE|OPTION)_\\w+)").matcher(src);
        while (m.find()) names.add(m.group(1));
        StringBuilder d = new StringBuilder();
        String off = System.getProperty("off", "");
        for (String n : names) d.append("#define ").append(n).append(off.contains(n) ? " 0\n" : " 1\n");
        int nl = src.indexOf('\n');
        return src.substring(0, nl + 1) + d + src.substring(nl + 1);
    }

    static String expand(String name) throws Exception {
        StringBuilder sb = new StringBuilder();
        expandInto(name, sb, new HashSet<>(), true);
        return sb.toString();
    }

    /** Every #include is expanded where it stands (the files carry their own guards), as Viewpoint does. */
    static void expandInto(String name, StringBuilder sb, Set<String> stack, boolean top) throws Exception {
        if (!stack.add(name)) return;
        String text = name.equals("constants.glsl") ? constants : Files.readString(root.resolve(name));
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            Matcher m = INCLUDE.matcher(line);
            if (m.matches()) {
                expandInto(m.group(1), sb, stack, false);
            } else {
                sb.append(line).append('\n');
            }
        }
        stack.remove(name);
    }
}
