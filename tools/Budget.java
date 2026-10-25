import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.EXTGPUShader4;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import vpmac.Glsl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes a program Viewpoint really built (the .original sources ViewpointMac saves when a link
 * fails), changes some of its #defines, and reports how many texture units it then needs.
 *
 * <p>Usage: Budget &lt;vert.original&gt; &lt;frag.original&gt; [NAME=VALUE,...]...  (one line per set)
 */
public class Budget {
    public static void main(String[] args) throws Exception {
        GLFW.glfwInit();
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long w = GLFW.glfwCreateWindow(64, 64, "budget", 0, 0);
        GLFW.glfwMakeContextCurrent(w);
        GL.createCapabilities();
        String vert = Files.readString(Path.of(args[0])), frag = Files.readString(Path.of(args[1]));
        for (int i = 2; i < args.length; i++) {
            System.out.println(args[i] + " -> " + samplers(set(vert, args[i]), set(frag, args[i])));
        }
        if (args.length == 2) System.out.println("as built -> " + samplers(vert, frag));
    }

    static String set(String src, String overrides) {
        for (String kv : overrides.split(",")) {
            if (kv.isBlank()) continue;
            String[] p = kv.split("=");
            src = src.replaceAll("(?m)^#define " + p[0] + " .*$", "#define " + p[0] + " " + p[1]);
        }
        return src;
    }

    static String samplers(String v, String f) {
        Glsl.Result vr = Glsl.translate(v, true), fr = Glsl.translate(f, false);
        int prog = GL20.glCreateProgram();
        int vs = compile(GL20.GL_VERTEX_SHADER, vr.source), fs = compile(GL20.GL_FRAGMENT_SHADER, fr.source);
        if (vs == 0 || fs == 0) return "does not compile";
        GL20.glAttachShader(prog, vs);
        GL20.glAttachShader(prog, fs);
        vr.locations.forEach((n, l) -> GL20.glBindAttribLocation(prog, l, n));
        fr.locations.forEach((n, l) -> EXTGPUShader4.glBindFragDataLocationEXT(prog, l, n));
        GL20.glLinkProgram(prog);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_TRUE) {
            int n = GL20.glGetProgrami(prog, GL20.GL_ACTIVE_UNIFORMS), s = 0;
            try (org.lwjgl.system.MemoryStack st = org.lwjgl.system.MemoryStack.stackPush()) {
                java.nio.IntBuffer size = st.mallocInt(1), type = st.mallocInt(1);
                for (int i = 0; i < n; i++) {
                    GL20.glGetActiveUniform(prog, i, 256, size, type);
                    int t = type.get(0);
                    if ((t >= 0x8B5D && t <= 0x8B64) || (t >= 0x8DC0 && t <= 0x8DD8)) s += size.get(0);
                }
            }
            return "links, " + s + " samplers";
        }
        Matcher m = Pattern.compile("uses (\\d+) samplers").matcher(GL20.glGetProgramInfoLog(prog));
        String worst = "?";
        while (m.find()) worst = m.group(1);
        return worst + " samplers";
    }

    static int compile(int type, String src) {
        int sh = GL20.glCreateShader(type);
        GL20.glShaderSource(sh, src);
        GL20.glCompileShader(sh);
        if (GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
            System.out.println("   " + GL20.glGetShaderInfoLog(sh).lines().findFirst().orElse(""));
            return 0;
        }
        return sh;
    }
}
