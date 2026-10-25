package vpmac;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Links the programs Viewpoint really built (the .original sources ViewpointMac keeps under
 * ~/Zomboid/viewpointmac/failed-shaders) through the stand-ins, as the game would, and reports
 * whether each now links.
 *
 * <p>Usage: LinkReal &lt;failed-shaders dir&gt;
 */
public class LinkReal {
    public static void main(String[] args) throws Exception {
        GLFW.glfwInit();
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long w = GLFW.glfwCreateWindow(64, 64, "linkreal", 0, 0);
        GLFW.glfwMakeContextCurrent(w);
        GL.createCapabilities();
        Path dir = Path.of(args[0]);
        List<String> stems = new ArrayList<>();
        try (Stream<Path> st = Files.list(dir)) {
            st.map(p -> p.getFileName().toString()).filter(n -> n.matches("program-\\d+\\.log"))
                .map(n -> n.substring(0, n.length() - 4)).sorted().forEach(stems::add);
        }
        int ok = 0;
        for (String stem : stems) {
            int prog = GL20.glCreateProgram();
            try (Stream<Path> st = Files.list(dir)) {
                for (Path f : (Iterable<Path>) st.filter(p -> p.getFileName().toString().startsWith(stem + "-")
                        && p.getFileName().toString().endsWith(".original"))::iterator) {
                    boolean vertex = f.toString().endsWith(".vert.original");
                    int sh = G.glCreateShader(vertex ? GL20.GL_VERTEX_SHADER : GL20.GL_FRAGMENT_SHADER);
                    G.glShaderSource(sh, Files.readString(f));
                    G.glCompileShader(sh);
                    G.glAttachShader(prog, sh);
                }
            }
            G.glLinkProgram(prog);
            boolean linked = GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_TRUE;
            if (linked) ok++;
            System.out.println((linked ? "links   " : "FAILS   ") + stem
                + (linked ? "" : "  " + GL20.glGetProgramInfoLog(prog).lines().filter(l -> l.contains("ERROR")).findFirst().orElse("")));
        }
        System.out.println(ok + " of " + stems.size() + " link");
    }
}
