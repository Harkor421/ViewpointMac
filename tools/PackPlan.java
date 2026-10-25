package vpmac;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Links Viewpoint's shaders through the stand-ins, as the game would, and prints which textures
 * each program moves into the atlas.
 *
 * <p>Usage: PackPlan &lt;viewpoint/shaders dir&gt; &lt;constants.glsl&gt; &lt;defines file&gt; [vert+frag,...]
 * (-Dpack=&lt;shaderpack shaders dir&gt; overlays a shader pack, as Viewpoint does)
 */
public class PackPlan {
    static final Pattern INCLUDE = Pattern.compile("^\\s*#include\\s+\"([^\"]+)\"\\s*$");
    static Path root, pack;
    static String constants, defines;

    public static void main(String[] args) throws Exception {
        root = Path.of(args[0]);
        String overlay = System.getProperty("pack");
        if (overlay != null) pack = Path.of(overlay);
        constants = Files.readString(Path.of(args[1]));
        defines = Files.readString(Path.of(args[2]));
        GLFW.glfwInit();
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long w = GLFW.glfwCreateWindow(64, 64, "packplan", 0, 0);
        GLFW.glfwMakeContextCurrent(w);
        GL.createCapabilities();
        String pairs = args.length > 3 ? args[3] : null;
        if (pairs == null) {
            StringBuilder all = new StringBuilder();
            try (var st = Files.list(root)) {
                for (Path f : (Iterable<Path>) st.sorted()::iterator) {
                    String n = f.getFileName().toString();
                    if (!n.endsWith(".frag")) continue;
                    String base = n.substring(0, n.length() - 5);
                    String vert = Files.exists(root.resolve(base + ".vert")) ? base + ".vert" : "screen.vert";
                    all.append(all.length() == 0 ? "" : ",").append(vert).append('+').append(n);
                }
            }
            pairs = all.toString();
        }
        for (String pair : pairs.split(",")) {
            String[] vf = pair.split("\\+");
            int prog = GL20.glCreateProgram();
            for (String file : vf) {
                boolean vertex = file.endsWith(".vert");
                int sh = G.glCreateShader(vertex ? GL20.GL_VERTEX_SHADER : GL20.GL_FRAGMENT_SHADER);
                G.glShaderSource(sh, withDefines(expand(file)));
                G.glCompileShader(sh);
                G.glAttachShader(prog, sh);
            }
            G.glLinkProgram(prog);
            boolean linked = GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_TRUE;
            Prog.Program p = Prog.programs.get(prog);
            if (p == null || p.packed.isEmpty() && linked) continue;
            System.out.println((linked ? "" : "FAILS ") + pair + "  packs " + p.packed.size() + ": " + p.packed);
            StringBuilder real = new StringBuilder();
            for (Prog.Sampler s : p.samplers) real.append(' ').append(s.name);
            System.out.println("    real:" + real);
        }
    }

    static String withDefines(String src) {
        int nl = src.indexOf('\n');
        return src.substring(0, nl + 1) + defines + src.substring(nl + 1);
    }

    static String expand(String name) throws Exception {
        StringBuilder sb = new StringBuilder();
        expandInto(name, sb, new HashSet<>());
        return sb.toString();
    }

    static void expandInto(String name, StringBuilder sb, Set<String> stack) throws Exception {
        if (!stack.add(name)) return;
        Path f = pack != null && Files.exists(pack.resolve(name)) ? pack.resolve(name) : root.resolve(name);
        if (!name.equals("constants.glsl") && !Files.exists(f)) return; // only under an #if this pack turns off
        String text = name.equals("constants.glsl") ? constants : Files.readString(f);
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            Matcher m = INCLUDE.matcher(line);
            if (m.matches()) expandInto(m.group(1), sb, stack);
            else sb.append(line).append('\n');
        }
        stack.remove(name);
    }
}
