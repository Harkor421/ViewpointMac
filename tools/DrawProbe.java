import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.ARBDrawInstanced;
import org.lwjgl.opengl.ARBInstancedArrays;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * What one command of an indirect draw list costs the Mac's legacy driver, issued the ways the
 * layer could issue it: the world arrives as ~260,000 single-instance commands a second, each
 * with its own base instance, and the driver's work per call is what holds the frame rate.
 * Usage: tools/drawprobe.sh
 */
public class DrawProbe {
    static final int COMMANDS = 1000, COUNT = 300, DIVISOR = 1 << 30;
    static int program, vbo, instances, posAttr, instAttr;

    public static void main(String[] args) {
        GLFW.glfwInit();
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long w = GLFW.glfwCreateWindow(64, 64, "probe", 0, 0);
        GLFW.glfwMakeContextCurrent(w);
        GL.createCapabilities();
        System.out.println(GL11.glGetString(GL11.GL_VERSION) + " | " + GL11.glGetString(GL11.GL_RENDERER));

        int fbo = GL30.glGenFramebuffers(), color = GL11.glGenTextures(), depth = GL30.glGenRenderbuffers();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1920, 1200, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depth);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL14.GL_DEPTH_COMPONENT24, 1920, 1200);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, depth);
        GL11.glViewport(0, 0, 1920, 1200);
        GL11.glEnable(GL11.GL_DEPTH_TEST);

        // -Dheavy: the state Viewpoint's world pass draws with: four half-float targets, 16
        // textures sampled, a bank of uniforms, and more per-vertex attributes.
        boolean heavy = System.getProperty("heavy") != null;
        if (heavy) {
            for (int k = 1; k < 4; k++) {
                int t = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, 1920, 1200, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + k, GL11.GL_TEXTURE_2D, t, 0);
            }
            GL20.glDrawBuffers(new int[] {GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1, GL30.GL_COLOR_ATTACHMENT2, GL30.GL_COLOR_ATTACHMENT3});
            for (int k = 0; k < 16; k++) {
                int t = GL11.glGenTextures();
                org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + k);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 256, 256, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            }
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        }
        StringBuilder fs = new StringBuilder("#version 120\n");
        if (heavy) {
            fs.append("uniform sampler2D t[16]; uniform vec4 bank[40];\nvoid main() { vec4 c = bank[7];\n");
            for (int k = 0; k < 16; k++) fs.append("  c += texture2D(t[").append(k).append("], gl_FragCoord.xy / 256.0);\n");
            fs.append("  gl_FragData[0] = c; gl_FragData[1] = c * 0.5; gl_FragData[2] = c * 0.25; gl_FragData[3] = c * 0.125; }");
        } else {
            fs.append("void main() { gl_FragColor = vec4(0.5); }");
        }
        program = program(
            "#version 120\n#extension GL_EXT_gpu_shader4 : require\nattribute vec3 p; attribute vec4 inst; attribute int id; uniform vec4 perDraw; uniform mat4 view;\n"
                + "void main() { gl_Position = view * vec4(p * 0.002 + inst.xyz * 0.001 + perDraw.xyz * 0.001 + float(id) * 1e-9, 1.0); }",
            fs.toString());
        GL20.glUseProgram(program);
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "view"), false, new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1});
        if (heavy) for (int k = 0; k < 16; k++) GL20.glUniform1i(GL20.glGetUniformLocation(program, "t[" + k + "]"), k);
        System.out.println(heavy ? "heavy state: 4 targets, 16 textures, 40 uniforms" : "light state: 1 target, no textures");
        posAttr = GL20.glGetAttribLocation(program, "p");
        instAttr = GL20.glGetAttribLocation(program, "inst");
        int perDraw = GL20.glGetUniformLocation(program, "perDraw");

        // Small triangles spread over the screen: the GPU's share stays small, the driver's shows.
        FloatBuffer v = BufferUtils.createFloatBuffer(COMMANDS * COUNT * 3);
        java.util.Random r = new java.util.Random(1);
        for (int i = 0; i < COMMANDS * COUNT / 3; i++) {
            float x = r.nextFloat() * 900 - 450, y = r.nextFloat() * 900 - 450;
            v.put(x).put(y).put(0).put(x + 1).put(y).put(0).put(x).put(y + 1).put(0);
        }
        v.flip();
        vbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, v, GL15.GL_STATIC_DRAW);
        FloatBuffer in = BufferUtils.createFloatBuffer(COMMANDS * 4);
        for (int i = 0; i < COMMANDS * 4; i++) in.put(r.nextFloat());
        in.flip();
        instances = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instances);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, in, GL15.GL_STATIC_DRAW);
        float[] cpu = new float[COMMANDS * 4];
        in.get(cpu);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL20.glEnableVertexAttribArray(posAttr);
        GL20.glVertexAttribPointer(posAttr, 3, GL11.GL_FLOAT, false, 12, 0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instances);
        GL20.glEnableVertexAttribArray(instAttr);
        GL20.glVertexAttribPointer(instAttr, 4, GL11.GL_FLOAT, false, 16, 0);
        ARBInstancedArrays.glVertexAttribDivisorARB(instAttr, DIVISOR);

        IntBuffer firsts = BufferUtils.createIntBuffer(COMMANDS), counts = BufferUtils.createIntBuffer(COMMANDS);
        // The world's commands come in an order of their own, not the buffer's.
        int[] order = new int[COMMANDS];
        for (int i = 0; i < COMMANDS; i++) order[i] = i;
        for (int i = COMMANDS - 1; i > 0; i--) { int j = r.nextInt(i + 1), t = order[i]; order[i] = order[j]; order[j] = t; }
        for (int i = 0; i < COMMANDS; i++) { firsts.put(order[i] * COUNT); counts.put(COUNT); }
        firsts.flip();
        counts.flip();

        String[] names = {
            "instanced draw, instance pointer moved (today)",
            "plain glDrawArrays, instance pointer moved",
            "plain glDrawArrays, per-draw data as a constant attribute",
            "plain glDrawArrays, per-draw data as a uniform",
            "plain glDrawArrays, nothing changed between draws",
            "one glMultiDrawArrays for all commands",
            "instanced draw, nothing changed between draws",
            "plain glDrawArrays, no instanced attribute enabled, nothing changed",
            "one glMultiDrawArrays, no instanced attribute enabled",
            "integer per-draw data: instanced draw, pointer moved",
            "integer per-draw data: plain draw, constant set with glVertexAttribI4iEXT",
            "integer per-draw data: plain draw, constant set with glVertexAttribI1iEXT",
            "integer per-draw data: plain draw, same constant each time",
        };
        int ids = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, ids);
        int[] idValues = new int[COMMANDS];
        for (int i = 0; i < COMMANDS; i++) idValues[i] = i;
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, idValues, GL15.GL_STATIC_DRAW);
        int query = GL15.glGenQueries(), timer = System.getProperty("timer") != null ? GL15.glGenQueries() : 0;
        if (timer != 0) System.out.println("with a GPU timer running");
        for (int round = 0; round < 2; round++) {
            for (int mode = 0; mode < names.length; mode++) {
                double best = Double.MAX_VALUE, submit = Double.MAX_VALUE;
                int samples = 0;
                for (int rep = 0; rep < 6; rep++) {
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instances);
                    GL20.glEnableVertexAttribArray(instAttr);
                    GL20.glVertexAttribPointer(instAttr, 4, GL11.GL_FLOAT, false, 16, 0);
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                    if (mode >= 7) GL20.glDisableVertexAttribArray(instAttr);
                    // The integer modes: only the integer attribute is per instance.
                    if (mode >= 9) {
                        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, ids);
                        org.lwjgl.opengl.EXTGPUShader4.glVertexAttribIPointerEXT(2, 1, GL11.GL_INT, 4, 0);
                        ARBInstancedArrays.glVertexAttribDivisorARB(2, DIVISOR);
                        if (mode == 9) GL20.glEnableVertexAttribArray(2);
                        else GL20.glDisableVertexAttribArray(2);
                    } else {
                        GL20.glDisableVertexAttribArray(2);
                    }
                    GL11.glFinish();
                    GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, query);
                    // -Dtimer: a GPU timer running around the draws, as Viewpoint keeps one around each pass.
                    if (timer != 0) GL15.glBeginQuery(org.lwjgl.opengl.EXTTimerQuery.GL_TIME_ELAPSED_EXT, timer);
                    long t = System.nanoTime();
                    switch (mode) {
                        case 0 -> {
                            for (int i = 0; i < COMMANDS; i++) {
                                GL20.glVertexAttribPointer(instAttr, 4, GL11.GL_FLOAT, false, 16, (long) order[i] * 16);
                                ARBDrawInstanced.glDrawArraysInstancedARB(GL11.GL_TRIANGLES, order[i] * COUNT, COUNT, 1);
                            }
                        }
                        case 1 -> {
                            for (int i = 0; i < COMMANDS; i++) {
                                GL20.glVertexAttribPointer(instAttr, 4, GL11.GL_FLOAT, false, 16, (long) order[i] * 16);
                                GL11.glDrawArrays(GL11.GL_TRIANGLES, order[i] * COUNT, COUNT);
                            }
                        }
                        case 2 -> {
                            GL20.glDisableVertexAttribArray(instAttr);
                            for (int i = 0; i < COMMANDS; i++) {
                                int k = order[i] * 4;
                                GL20.glVertexAttrib4f(instAttr, cpu[k], cpu[k + 1], cpu[k + 2], cpu[k + 3]);
                                GL11.glDrawArrays(GL11.GL_TRIANGLES, order[i] * COUNT, COUNT);
                            }
                        }
                        case 3 -> {
                            for (int i = 0; i < COMMANDS; i++) {
                                int k = order[i] * 4;
                                GL20.glUniform4f(perDraw, cpu[k], cpu[k + 1], cpu[k + 2], cpu[k + 3]);
                                GL11.glDrawArrays(GL11.GL_TRIANGLES, order[i] * COUNT, COUNT);
                            }
                        }
                        case 4 -> {
                            for (int i = 0; i < COMMANDS; i++) GL11.glDrawArrays(GL11.GL_TRIANGLES, order[i] * COUNT, COUNT);
                        }
                        case 5 -> GL14.glMultiDrawArrays(GL11.GL_TRIANGLES, firsts, counts);
                        case 6 -> {
                            for (int i = 0; i < COMMANDS; i++) ARBDrawInstanced.glDrawArraysInstancedARB(GL11.GL_TRIANGLES, order[i] * COUNT, COUNT, 1);
                        }
                        case 7 -> {
                            for (int i = 0; i < COMMANDS; i++) GL11.glDrawArrays(GL11.GL_TRIANGLES, order[i] * COUNT, COUNT);
                        }
                        case 8 -> GL14.glMultiDrawArrays(GL11.GL_TRIANGLES, firsts, counts);
                        case 9 -> {
                            for (int i = 0; i < COMMANDS; i++) {
                                org.lwjgl.opengl.EXTGPUShader4.glVertexAttribIPointerEXT(2, 1, GL11.GL_INT, 4, (long) order[i] * 4);
                                GL11.glDrawArrays(GL11.GL_TRIANGLES, order[i] * COUNT, COUNT);
                            }
                        }
                        case 10 -> {
                            for (int i = 0; i < COMMANDS; i++) {
                                org.lwjgl.opengl.EXTGPUShader4.glVertexAttribI4iEXT(2, order[i], 0, 0, 1);
                                GL11.glDrawArrays(GL11.GL_TRIANGLES, order[i] * COUNT, COUNT);
                            }
                        }
                        case 11 -> {
                            for (int i = 0; i < COMMANDS; i++) {
                                org.lwjgl.opengl.EXTGPUShader4.glVertexAttribI1iEXT(2, order[i]);
                                GL11.glDrawArrays(GL11.GL_TRIANGLES, order[i] * COUNT, COUNT);
                            }
                        }
                        case 12 -> {
                            for (int i = 0; i < COMMANDS; i++) {
                                org.lwjgl.opengl.EXTGPUShader4.glVertexAttribI4iEXT(2, 7, 0, 0, 1);
                                GL11.glDrawArrays(GL11.GL_TRIANGLES, order[i] * COUNT, COUNT);
                            }
                        }
                        default -> { }
                    }
                    long s = System.nanoTime();
                    GL11.glFinish();
                    long e = System.nanoTime();
                    if (timer != 0) GL15.glEndQuery(org.lwjgl.opengl.EXTTimerQuery.GL_TIME_ELAPSED_EXT);
                    GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
                    samples = GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT);
                    int error = GL11.glGetError();
                    if (error != 0) System.out.println("  GL error 0x" + Integer.toHexString(error) + " in: " + names[mode]);
                    submit = Math.min(submit, (s - t) / 1e3 / COMMANDS);
                    best = Math.min(best, (e - t) / 1e3 / COMMANDS);
                }
                if (round == 1) System.out.printf("%6.2f us to issue, %6.2f us with the GPU done, per command, %8d samples  %s%n", submit, best, samples, names[mode]);
            }
        }
        GLFW.glfwTerminate();
    }

    static int program(String vs, String fs) {
        int p = GL20.glCreateProgram();
        for (int k = 0; k < 2; k++) {
            int s = GL20.glCreateShader(k == 0 ? GL20.GL_VERTEX_SHADER : GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(s, k == 0 ? vs : fs);
            GL20.glCompileShader(s);
            if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) throw new IllegalStateException(GL20.glGetShaderInfoLog(s));
            GL20.glAttachShader(p, s);
        }
        // Attribute 0 is the one that makes vertices on a legacy context: the position, never the per-draw data.
        GL20.glBindAttribLocation(p, 0, "p");
        GL20.glBindAttribLocation(p, 1, "inst");
        GL20.glBindAttribLocation(p, 2, "id");
        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) throw new IllegalStateException(GL20.glGetProgramInfoLog(p));
        return p;
    }
}
