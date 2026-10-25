package vpmac;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

/**
 * What the layer costs per frame, without the game: a frame shaped like Viewpoint's, drawn through
 * the stand-ins in a hidden Mac legacy context, timed on the CPU (what the render thread spends
 * issuing it) and to the end of the GPU's work. Small targets, so the CPU side is what shows.
 * Runs against any build of the layer on the classpath, to compare one with another.
 *
 * <p>The frame: 150 mesh draws with 12 textures on virtual units and a software vertex array,
 * 20 indirect draws of 50 commands each with base instances, 44 full-screen passes without vertex
 * arrays, and one light pass of 23 samplers that needs the atlas (two of its textures redrawn each
 * frame, so copied each frame).
 */
public class LayerBench {
    static final int SIZE = 256;

    public static void main(String[] args) {
        int frames = args.length > 0 ? Integer.parseInt(args[0]) : 300;
        GLFW.glfwInit();
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long w = GLFW.glfwCreateWindow(64, 64, "bench", 0, 0);
        GLFW.glfwMakeContextCurrent(w);
        GL.createCapabilities();
        GLFW.glfwSwapInterval(0);

        // Render targets: RT0 the meshes draw into, RT1..RT4 the full-screen passes, OUT the rest.
        int[] rt = new int[6];
        int[] fbo = new int[6];
        for (int i = 0; i < 6; i++) {
            rt[i] = G.glCreateTextures(G.T_2D);
            G.glTextureStorage2D(rt[i], 1, 0x881A, SIZE, SIZE); // RGBA16F
            fbo[i] = GL30.glGenFramebuffers();
            G.glBindFramebuffer(G.FRAMEBUFFER, fbo[i]);
            G.glFramebufferTexture2D(G.FRAMEBUFFER, G.COLOR_ATTACHMENT0, G.T_2D, rt[i], 0);
        }
        int[] small = new int[12];
        for (int i = 0; i < small.length; i++) {
            small[i] = G.glCreateTextures(G.T_2D);
            G.glTextureStorage2D(small[i], 1, 0x8058, 64, 64);
            G.glTextureSubImage2D(small[i], 0, 0, 0, 64, 64, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, BufferUtils.createByteBuffer(64 * 64 * 4));
        }
        int array = G.glCreateTextures(G.T_2D_ARRAY);
        G.glTextureStorage3D(array, 1, 0x8058, 16, 16, 4);
        int sampler = G.glGenSamplers();
        G.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);

        // Mesh program: 12 textures on units 64..75, an instanced attribute.
        StringBuilder fs = new StringBuilder("#version 330\nin vec2 vUv;\nout vec4 fragColor;\n");
        for (int i = 0; i < 12; i++) fs.append("uniform sampler2D uTex").append(i).append(";\n");
        fs.append("void main() { vec4 c = vec4(0.0);\n");
        for (int i = 0; i < 12; i++) fs.append("  c += texture(uTex").append(i).append(", vUv);\n");
        fs.append("  fragColor = c; }\n");
        int mesh = program("#version 330\nlayout(location = 0) in vec3 aPos;\nlayout(location = 1) in vec2 aUv;\nlayout(location = 2) in vec4 aInst;\n"
            + "out vec2 vUv;\nvoid main() { vUv = aUv; gl_Position = vec4(aPos.xy * 0.05 + aInst.xy, 0.0, 1.0); }\n", fs.toString());
        G.glUseProgram(mesh);
        for (int i = 0; i < 12; i++) G.glUniform1i(G.glGetUniformLocation(mesh, "uTex" + i), 64 + i);

        // Full-screen program: a triangle from gl_VertexID, 4 textures on real units.
        int screen = program("#version 330\nout vec2 vUv;\nvoid main() { vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);"
                + " vUv = p; gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }\n",
            "#version 330\nin vec2 vUv;\nuniform sampler2D uA;\nuniform sampler2D uB;\nuniform sampler2D uC;\nuniform sampler2D uD;\nout vec4 fragColor;\n"
                + "void main() { fragColor = texture(uA, vUv) + texture(uB, vUv) + texture(uC, vUv) * texture(uD, vUv); }\n");
        G.glUseProgram(screen);
        G.glUniform1i(G.glGetUniformLocation(screen, "uA"), 0);
        G.glUniform1i(G.glGetUniformLocation(screen, "uB"), 1);
        G.glUniform1i(G.glGetUniformLocation(screen, "uC"), 2);
        G.glUniform1i(G.glGetUniformLocation(screen, "uD"), 3);

        // Light program: 12 array samplers (can't go in the atlas), 6 small tables, 5 screen buffers.
        StringBuilder lfs = new StringBuilder("#version 330\nin vec2 vUv;\nout vec4 fragColor;\n");
        for (int i = 0; i < 12; i++) lfs.append("uniform sampler2DArray uShadow").append(i).append(";\n");
        for (int i = 0; i < 6; i++) lfs.append("uniform sampler2D uWeather").append(i).append(";\n");
        String[] screens = {"uAlbedo", "uNormal", "uEngineLight", "uOcclusion", "uBounce"};
        for (String s : screens) lfs.append("uniform sampler2D ").append(s).append(";\n");
        lfs.append("void main() { vec4 c = vec4(0.0);\n");
        for (int i = 0; i < 12; i++) lfs.append("  c += texture(uShadow").append(i).append(", vec3(vUv, 1.0));\n");
        for (int i = 0; i < 6; i++) lfs.append("  c += texture(uWeather").append(i).append(", vUv);\n");
        for (String s : screens) lfs.append("  c += texture(").append(s).append(", vUv);\n");
        lfs.append("  fragColor = c; }\n");
        int light = program("#version 330\nout vec2 vUv;\nvoid main() { vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);"
            + " vUv = p; gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }\n", lfs.toString());
        G.glUseProgram(light);
        int unit = 20;
        for (int i = 0; i < 12; i++) G.glUniform1i(G.glGetUniformLocation(light, "uShadow" + i), unit++);
        for (int i = 0; i < 6; i++) G.glUniform1i(G.glGetUniformLocation(light, "uWeather" + i), unit++);
        for (String s : screens) G.glUniform1i(G.glGetUniformLocation(light, s), unit++);

        // Mesh data: one triangle, 1000 instances, a separate-format vertex array (as Viewpoint's).
        int vbo = G.glCreateBuffers();
        G.glBindBuffer(G.ARRAY_BUFFER, vbo);
        G.glBufferData(G.ARRAY_BUFFER, BufferUtils.createFloatBuffer(15).put(new float[] {-1, -1, 0, 0, 0, 1, -1, 0, 1, 0, 0, 1, 0, 0.5f, 1}).flip(), 0x88E4);
        int inst = G.glCreateBuffers();
        G.glBindBuffer(G.ARRAY_BUFFER, inst);
        G.glBufferData(G.ARRAY_BUFFER, BufferUtils.createFloatBuffer(4000), 0x88E8);
        int vao = G.glGenVertexArrays();
        G.glBindVertexArray(vao);
        G.glVertexAttribFormat(0, 3, GL11.GL_FLOAT, false, 0);
        G.glVertexAttribBinding(0, 0);
        G.glVertexAttribFormat(1, 2, GL11.GL_FLOAT, false, 12);
        G.glVertexAttribBinding(1, 0);
        G.glBindVertexBuffer(0, vbo, 0, 20);
        G.glVertexAttribFormat(2, 4, GL11.GL_FLOAT, false, 0);
        G.glVertexAttribBinding(2, 1);
        G.glBindVertexBuffer(1, inst, 0, 16);
        G.glVertexBindingDivisor(1, 1);
        for (int i = 0; i < 3; i++) G.glEnableVertexAttribArray(i);
        G.glBindVertexArray(0);

        int cmds = G.glCreateBuffers();
        G.glBindBuffer(G.DRAW_INDIRECT_BUFFER, cmds);
        ByteBuffer c = BufferUtils.createByteBuffer(50 * 16);
        for (int i = 0; i < 50; i++) c.putInt(3).putInt(1).putInt(0).putInt(i * 2);
        c.flip();
        G.glBufferData(G.DRAW_INDIRECT_BUFFER, c.remaining(), 0x88E4);
        G.glBufferSubData(G.DRAW_INDIRECT_BUFFER, 0, c);

        if (System.getProperty("profile") != null) Prof.start(Integer.parseInt(System.getProperty("profile")));
        long cpu = 0, total = 0;
        int warm = Math.min(30, frames / 5);
        for (int f = 0; f < frames + warm; f++) {
            GL11.glFinish();
            long t0 = System.nanoTime();
            // Meshes into RT0.
            G.glBindFramebuffer(G.FRAMEBUFFER, fbo[0]);
            GL11.glViewport(0, 0, SIZE, SIZE);
            G.glUseProgram(mesh);
            for (int i = 0; i < 12; i++) {
                G.glActiveTexture(G.TEXTURE0 + 64 + i);
                G.glBindTexture(G.T_2D, small[i]);
                G.glBindSampler(64 + i, i % 3 == 0 ? sampler : 0);
            }
            for (int d = 0; d < 150; d++) {
                G.glBindVertexArray(vao);
                G.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, 3, 2);
            }
            for (int d = 0; d < 20; d++) {
                G.glBindVertexArray(vao);
                G.glMultiDrawArraysIndirect(GL11.GL_TRIANGLES, 0, 50, 0);
            }
            G.glBindVertexArray(0);
            // Full-screen passes: 4 into RT1..RT4, then 40 into OUT.
            G.glUseProgram(screen);
            for (int i = 0; i < 4; i++) {
                G.glActiveTexture(G.TEXTURE0 + i);
                G.glBindTexture(G.T_2D, small[i]);
            }
            for (int k = 1; k <= 4; k++) {
                G.glBindFramebuffer(G.FRAMEBUFFER, fbo[k]);
                G.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            }
            // The light pass: tables and arrays bound, screen buffers from this frame.
            G.glBindFramebuffer(G.FRAMEBUFFER, fbo[5]);
            G.glUseProgram(light);
            int u = 20;
            for (int i = 0; i < 12; i++) {
                G.glActiveTexture(G.TEXTURE0 + u++);
                G.glBindTexture(G.T_2D_ARRAY, array);
            }
            for (int i = 0; i < 6; i++) {
                G.glActiveTexture(G.TEXTURE0 + u++);
                G.glBindTexture(G.T_2D, small[6 + i]);
            }
            for (int i = 0; i < 5; i++) {
                G.glActiveTexture(G.TEXTURE0 + u++);
                G.glBindTexture(G.T_2D, rt[i]);
            }
            G.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            G.glUseProgram(screen);
            G.glActiveTexture(G.TEXTURE0);
            for (int d = 0; d < 40; d++) G.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            G.glUseProgram(0);
            G.glBindFramebuffer(G.FRAMEBUFFER, 0);
            long t1 = System.nanoTime();
            GL11.glFinish();
            long t2 = System.nanoTime();
            if (f >= warm) {
                cpu += t1 - t0;
                total += t2 - t0;
            }
        }
        int e = GL11.glGetError();
        System.out.printf("per frame: CPU %.3f ms, to GPU done %.3f ms (%d frames%s)%n", cpu / 1e6 / frames, total / 1e6 / frames, frames,
            e == 0 ? "" : ", GL error 0x" + Integer.toHexString(e));
    }

    static int program(String vs, String fs) {
        int v = G.glCreateShader(0x8B31), f = G.glCreateShader(0x8B30);
        G.glShaderSource(v, vs);
        G.glCompileShader(v);
        G.glShaderSource(f, fs);
        G.glCompileShader(f);
        int p = org.lwjgl.opengl.GL20.glCreateProgram();
        G.glAttachShader(p, v);
        G.glAttachShader(p, f);
        G.glLinkProgram(p);
        if (org.lwjgl.opengl.GL20.glGetProgrami(p, org.lwjgl.opengl.GL20.GL_LINK_STATUS) != 1) {
            System.out.println("link failed: " + org.lwjgl.opengl.GL20.glGetProgramInfoLog(p));
        }
        return p;
    }
}
