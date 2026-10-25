import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.ARBDrawInstanced;
import org.lwjgl.opengl.ARBInstancedArrays;
import org.lwjgl.opengl.EXTGPUShader4;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;

import java.nio.ByteBuffer;

/**
 * Viewpoint's world pass, one command at a time, in the game's kind of context (legacy 2.1) and
 * in a 4.1 core one ({@code -Dcore}): the world's vertex layout (six attributes, 40 bytes), an
 * integer per draw, four half-float targets and 16 textures. What each command costs the driver
 * decides whether a core context is worth building for.
 * Usage: tools/contextprobe.sh, PROBE_OPTS=-Dcore=1 tools/contextprobe.sh
 */
public class ContextProbe {
    static final int COMMANDS = 1000, STRIDE = 40, DIVISOR = 1 << 30;

    public static void main(String[] args) {
        boolean core = System.getProperty("core") != null;
        GLFW.glfwInit();
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        if (core) {
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 1);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        }
        long w = GLFW.glfwCreateWindow(64, 64, "probe", 0, 0);
        GLFW.glfwMakeContextCurrent(w);
        GL.createCapabilities();
        System.out.println(GL11.glGetString(GL11.GL_VERSION) + " | " + GL11.glGetString(GL11.GL_RENDERER));
        if (core) GL30.glBindVertexArray(GL30.glGenVertexArrays());

        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        int[] targets = new int[4];
        for (int k = 0; k < 4; k++) {
            int t = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, 1920, 1200, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + k, GL11.GL_TEXTURE_2D, t, 0);
            targets[k] = GL30.GL_COLOR_ATTACHMENT0 + k;
        }
        GL20.glDrawBuffers(targets);
        int depth = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depth);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL14.GL_DEPTH_COMPONENT24, 1920, 1200);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, depth);
        GL11.glViewport(0, 0, 1920, 1200);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        for (int k = 0; k < 16; k++) {
            int t = GL11.glGenTextures();
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + k);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 256, 256, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        StringBuilder fs = new StringBuilder();
        String vs;
        if (core) {
            vs = "#version 410\nlayout(location = 0) in vec3 p; layout(location = 1) in vec2 uv; layout(location = 2) in vec4 tint;\n"
                + "layout(location = 3) in vec3 n; layout(location = 4) in float shade; layout(location = 5) in int flags;\n"
                + "layout(location = 6) in int draw; uniform mat4 view; out vec2 vUv; flat out int vDraw;\n"
                + "void main() { vUv = uv + tint.xy + n.xy + vec2(shade, float(flags)) * 1e-6; vDraw = draw;\n"
                + "  gl_Position = view * vec4(p * 0.002 + float(draw) * 1e-9, 1.0); }";
            fs.append("#version 410\nuniform sampler2D t[16]; uniform vec4 bank[40]; in vec2 vUv; flat in int vDraw;\n");
            for (int k = 0; k < 4; k++) fs.append("layout(location = ").append(k).append(") out vec4 o").append(k).append(";\n");
            fs.append("void main() { vec4 c = bank[7] + float(vDraw) * 1e-9;\n");
            for (int k = 0; k < 16; k++) fs.append("  c += texture(t[").append(k).append("], vUv);\n");
            fs.append("  o0 = c; o1 = c * 0.5; o2 = c * 0.25; o3 = c * 0.125; }");
        } else {
            vs = "#version 120\n#extension GL_EXT_gpu_shader4 : require\nattribute vec3 p; attribute vec2 uv; attribute vec4 tint;\n"
                + "attribute vec3 n; attribute float shade; attribute int flags; attribute int draw; uniform mat4 view;\n"
                + "varying vec2 vUv; flat varying int vDraw;\n"
                + "void main() { vUv = uv + tint.xy + n.xy + vec2(shade, float(flags)) * 1e-6; vDraw = draw;\n"
                + "  gl_Position = view * vec4(p * 0.002 + float(draw) * 1e-9, 1.0); }";
            fs.append("#version 120\n#extension GL_EXT_gpu_shader4 : require\nuniform sampler2D t[16]; uniform vec4 bank[40];\n"
                + "varying vec2 vUv; flat varying int vDraw;\nvoid main() { vec4 c = bank[7] + float(vDraw) * 1e-9;\n");
            for (int k = 0; k < 16; k++) fs.append("  c += texture2D(t[").append(k).append("], vUv);\n");
            fs.append("  gl_FragData[0] = c; gl_FragData[1] = c * 0.5; gl_FragData[2] = c * 0.25; gl_FragData[3] = c * 0.125; }");
        }
        int program = program(vs, fs.toString(), new String[] {"p", "uv", "tint", "n", "shade", "flags", "draw"});
        GL20.glUseProgram(program);
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "view"), false, new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1});
        for (int k = 0; k < 16; k++) GL20.glUniform1i(GL20.glGetUniformLocation(program, "t[" + k + "]"), k);

        // The world's commands: sizes from a few quads to a few thousand vertices, in no buffer order.
        java.util.Random r = new java.util.Random(1);
        int[] first = new int[COMMANDS], count = new int[COMMANDS], order = new int[COMMANDS];
        int total = 0;
        for (int i = 0; i < COMMANDS; i++) {
            count[i] = 6 * (4 + r.nextInt(100));
            first[i] = total;
            total += count[i];
            order[i] = i;
        }
        for (int i = COMMANDS - 1; i > 0; i--) { int j = r.nextInt(i + 1), t = order[i]; order[i] = order[j]; order[j] = t; }
        ByteBuffer v = BufferUtils.createByteBuffer(total * STRIDE);
        for (int i = 0; i < total / 3; i++) {
            float x = r.nextFloat() * 900 - 450, y = r.nextFloat() * 900 - 450;
            for (int c = 0; c < 3; c++) {
                int at = (i * 3 + c) * STRIDE;
                v.putFloat(at, x + (c == 1 ? 1 : 0)).putFloat(at + 4, y + (c == 2 ? 1 : 0)).putFloat(at + 8, 0);
                v.putFloat(at + 12, 0.5f).putFloat(at + 16, 0.5f);
                v.putShort(at + 20, (short) 1000).putShort(at + 22, (short) 1000).putShort(at + 24, (short) 1000).putShort(at + 26, (short) 1000);
                v.put(at + 28, (byte) 0).put(at + 29, (byte) 127).put(at + 30, (byte) 0);
                v.putFloat(at + 32, 1f).putInt(at + 36, 3);
            }
        }
        int vbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, v, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, STRIDE, 0);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, STRIDE, 12);
        GL20.glVertexAttribPointer(2, 4, GL11.GL_UNSIGNED_SHORT, true, STRIDE, 20);
        GL20.glVertexAttribPointer(3, 3, GL11.GL_BYTE, true, STRIDE, 28);
        GL20.glVertexAttribPointer(4, 1, GL11.GL_FLOAT, false, STRIDE, 32);
        if (core) GL30.glVertexAttribIPointer(5, 1, GL11.GL_INT, STRIDE, 36);
        else EXTGPUShader4.glVertexAttribIPointerEXT(5, 1, GL11.GL_INT, STRIDE, 36);
        for (int a = 0; a < 6; a++) GL20.glEnableVertexAttribArray(a);
        int ids = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, ids);
        int[] idValues = new int[COMMANDS];
        for (int i = 0; i < COMMANDS; i++) idValues[i] = i | (i % 3) << 24;
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, idValues, GL15.GL_STATIC_DRAW);

        String[] names = {
            "per-draw integer as a constant attribute, plain draw",
            "per-draw integer from an instanced array, pointer moved, instanced draw",
            "nothing changed between draws, plain draw",
        };
        System.out.println(core ? "4.1 core context" : "legacy 2.1 context (the game's)");
        for (int round = 0; round < 2; round++) {
            for (int mode = 0; mode < names.length; mode++) {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, ids);
                if (core) GL30.glVertexAttribIPointer(6, 1, GL11.GL_INT, 4, 0);
                else EXTGPUShader4.glVertexAttribIPointerEXT(6, 1, GL11.GL_INT, 4, 0);
                if (core) GL33.glVertexAttribDivisor(6, DIVISOR);
                else ARBInstancedArrays.glVertexAttribDivisorARB(6, DIVISOR);
                if (mode == 1) GL20.glEnableVertexAttribArray(6);
                else GL20.glDisableVertexAttribArray(6);
                double best = Double.MAX_VALUE, done = Double.MAX_VALUE;
                for (int rep = 0; rep < 6; rep++) {
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                    GL11.glFinish();
                    long t = System.nanoTime();
                    for (int i = 0; i < COMMANDS; i++) {
                        int c = order[i];
                        switch (mode) {
                            case 0 -> {
                                if (core) GL30.glVertexAttribI1i(6, idValues[c]);
                                else EXTGPUShader4.glVertexAttribI1iEXT(6, idValues[c]);
                                GL11.glDrawArrays(GL11.GL_TRIANGLES, first[c], count[c]);
                            }
                            case 1 -> {
                                if (core) GL30.glVertexAttribIPointer(6, 1, GL11.GL_INT, 4, (long) c * 4);
                                else EXTGPUShader4.glVertexAttribIPointerEXT(6, 1, GL11.GL_INT, 4, (long) c * 4);
                                if (core) GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES, first[c], count[c], 1);
                                else ARBDrawInstanced.glDrawArraysInstancedARB(GL11.GL_TRIANGLES, first[c], count[c], 1);
                            }
                            default -> GL11.glDrawArrays(GL11.GL_TRIANGLES, first[c], count[c]);
                        }
                    }
                    long s = System.nanoTime();
                    GL11.glFinish();
                    best = Math.min(best, (s - t) / 1e3 / COMMANDS);
                    done = Math.min(done, (System.nanoTime() - t) / 1e3 / COMMANDS);
                }
                int error = GL11.glGetError();
                if (round == 1) System.out.printf("%6.2f us to issue, %6.2f us with the GPU done, per command  %s%s%n", best, done, names[mode],
                    error != 0 ? " (GL error 0x" + Integer.toHexString(error) + ")" : "");
            }
        }
        GLFW.glfwTerminate();
    }

    static int program(String vs, String fs, String[] attributes) {
        int p = GL20.glCreateProgram();
        for (int k = 0; k < 2; k++) {
            int s = GL20.glCreateShader(k == 0 ? GL20.GL_VERTEX_SHADER : GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(s, k == 0 ? vs : fs);
            GL20.glCompileShader(s);
            if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) throw new IllegalStateException(GL20.glGetShaderInfoLog(s));
            GL20.glAttachShader(p, s);
        }
        for (int a = 0; a < attributes.length; a++) GL20.glBindAttribLocation(p, a, attributes[a]);
        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) throw new IllegalStateException(GL20.glGetProgramInfoLog(p));
        return p;
    }
}
