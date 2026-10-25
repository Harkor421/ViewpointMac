import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.ARBSync;
import org.lwjgl.opengl.EXTTimerQuery;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLCapabilities;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * What the Mac's legacy context can do and what makes it wait: extensions and limits, then the
 * CPU time of readbacks and buffer writes issued right behind a long stretch of GPU work. A call
 * that takes about as long as that work waited for the GPU.
 * Usage: tools/probe.sh
 */
public class GlProbe {
    static int heavy, fbo, color, depth, small, smallFbo, quadVbo;
    static final int W = 1920, H = 1200;

    public static void main(String[] args) {
        GLFW.glfwInit();
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long w = GLFW.glfwCreateWindow(64, 64, "probe", 0, 0);
        GLFW.glfwMakeContextCurrent(w);
        GLCapabilities caps = GL.createCapabilities();
        System.out.println(GL11.glGetString(GL11.GL_VERSION) + " | " + GL11.glGetString(GL11.GL_RENDERER));
        String ext = GL11.glGetString(GL11.GL_EXTENSIONS);
        for (String e : new String[] {"GL_EXT_timer_query", "GL_ARB_timer_query", "GL_ARB_sync", "GL_APPLE_fence", "GL_APPLE_flush_buffer_range",
            "GL_ARB_map_buffer_range", "GL_ARB_copy_buffer", "GL_NV_texture_barrier", "GL_ARB_texture_barrier", "GL_APPLE_texture_range",
            "GL_ARB_draw_elements_base_vertex", "GL_ARB_instanced_arrays", "GL_EXT_framebuffer_blit", "GL_ARB_vertex_array_object",
            "GL_APPLE_vertex_array_object", "GL_EXT_texture_array", "GL_ARB_texture_float", "GL_ARB_texture_rg", "GL_EXT_gpu_shader4",
            "GL_ARB_texture_buffer_object", "GL_EXT_texture_buffer_object", "GL_ARB_sampler_objects", "GL_APPLE_client_storage"}) {
            System.out.println((ext.contains(e + " ") || ext.endsWith(e) ? "  yes " : "  no  ") + e);
        }
        System.out.println("  units: fragment " + GL11.glGetInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS) + ", vertex "
            + GL11.glGetInteger(GL20.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS) + ", combined " + GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS)
            + "; draw buffers " + GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS) + "; varyings " + GL11.glGetInteger(GL20.GL_MAX_VARYING_FLOATS)
            + "; vertex uniforms " + GL11.glGetInteger(GL20.GL_MAX_VERTEX_UNIFORM_COMPONENTS) + ", fragment " + GL11.glGetInteger(GL20.GL_MAX_FRAGMENT_UNIFORM_COMPONENTS));
        System.out.println("  LWJGL: timer query " + caps.GL_EXT_timer_query + ", ARB_sync " + caps.GL_ARB_sync + ", glQueryCounter " + (caps.glQueryCounter != 0));

        setup();
        gpuWork();
        double work = gpuWork();
        System.out.printf("GPU work behind each timed call: %.1f ms%n", work);

        int pbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, pbo);
        GL15.glBufferData(GL30.GL_PIXEL_PACK_BUFFER, 128 * 72 * 16, GL15.GL_STREAM_READ);
        GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, 0);

        time("readPixels 1x1 DEPTH/FLOAT -> PBO", () -> {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, pbo);
            GL11.glReadPixels(5, 5, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, 0L);
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, 0);
        });
        time("readPixels 128x72 RG/FLOAT -> PBO", () -> {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, pbo);
            GL11.glReadPixels(0, 0, 128, 72, GL30.GL_RG, GL11.GL_FLOAT, 0L);
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, 0);
        });
        time("readPixels 128x72 RGBA/FLOAT -> PBO", () -> {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, pbo);
            GL11.glReadPixels(0, 0, 128, 72, GL11.GL_RGBA, GL11.GL_FLOAT, 0L);
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, 0);
        });
        time("copyTexSubImage 1x1 depth -> depth texture", () -> {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, small);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 5, 5, 1, 1);
        });
        time("draw depth -> 128x72 RGBA32F (shader copy)", GlProbe::shaderCopy);
        time("readPixels 128x72 RGBA/FLOAT from RGBA32F -> PBO", () -> {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, smallFbo);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, pbo);
            GL11.glReadPixels(0, 0, 128, 72, GL11.GL_RGBA, GL11.GL_FLOAT, 0L);
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, 0);
        });
        time("readPixels 128x72 BGRA/8888_REV from RGBA32F -> PBO", () -> {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, smallFbo);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, pbo);
            GL11.glReadPixels(0, 0, 128, 72, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, 0L);
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, 0);
        });
        time("getTexImage of a 1x1 texture written last frame", () -> {
            FloatBuffer f = BufferUtils.createFloatBuffer(4);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, small);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, f);
        });
        time("depth pick, async: copy 1x1 depth, shader to R32F, readPixels RED/FLOAT -> PBO", () -> asyncDepth(pbo));
        // Same pixel both ways; the GPU idle so the plain read is quick.
        GL11.glFinish();
        FloatBuffer direct = BufferUtils.createFloatBuffer(1), viaPbo = BufferUtils.createFloatBuffer(1);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
        GL11.glReadPixels(5, 5, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, direct);
        asyncDepth(pbo);
        GL11.glFinish();
        GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, pbo);
        GL15.glGetBufferSubData(GL30.GL_PIXEL_PACK_BUFFER, 0, viaPbo);
        GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, 0);
        System.out.println("  depth at (5,5): direct " + direct.get(0) + ", async " + viaPbo.get(0)
            + (Float.floatToIntBits(direct.get(0)) == Float.floatToIntBits(viaPbo.get(0)) ? " (same bits)" : " (DIFFERENT)"));
        time("glGetError", () -> GL11.glGetError());
        time("glGetIntegerv(ARRAY_BUFFER_BINDING)", () -> GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING));
        time("glGetIntegerv x1000", () -> { for (int i = 0; i < 1000; i++) GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING); });
        time("glGetVertexAttribiv x1000", () -> { for (int i = 0; i < 1000; i++) GL20.glGetVertexAttribi(0, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED); });
        time("bufferSubData 64 KB into the VBO the work drew from", () -> {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, BufferUtils.createByteBuffer(64 << 10));
        });
        time("bufferData orphan + subData 64 KB", () -> {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 1 << 20, GL15.GL_DYNAMIC_DRAW);
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, BufferUtils.createByteBuffer(64 << 10));
        });
        time("texSubImage 1920x1200 from a PBO written by bufferSubData", () -> {
            int up = GL15.glGenBuffers();
            GL15.glBindBuffer(GL30.GL_PIXEL_UNPACK_BUFFER, up);
            GL15.glBufferData(GL30.GL_PIXEL_UNPACK_BUFFER, 64 * 64 * 16, GL15.GL_DYNAMIC_DRAW);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
            GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 64, 64, GL11.GL_RGBA, GL11.GL_FLOAT, 0L);
            GL15.glBindBuffer(GL30.GL_PIXEL_UNPACK_BUFFER, 0);
            GL15.glDeleteBuffers(up);
        });
        if (caps.GL_ARB_sync) {
            time("fenceSync + clientWait(0)", () -> {
                long s = ARBSync.glFenceSync(ARBSync.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                ARBSync.glClientWaitSync(s, 0, 0);
                ARBSync.glDeleteSync(s);
            });
        }
        if (caps.GL_EXT_timer_query) {
            int q = GL15.glGenQueries();
            GL15.glBeginQuery(EXTTimerQuery.GL_TIME_ELAPSED_EXT, q);
            draw(1);
            GL15.glEndQuery(EXTTimerQuery.GL_TIME_ELAPSED_EXT);
            GL11.glFinish();
            long ns = EXTTimerQuery.glGetQueryObjectui64EXT(q, GL15.GL_QUERY_RESULT);
            System.out.printf("TIME_ELAPSED of one pass: %.2f ms (%s)%n", ns / 1e6, ns > 0 ? "works" : "zero");
            time("100 begin/end TIME_ELAPSED pairs around tiny draws", () -> {
                int[] qs = new int[100];
                GL15.glGenQueries(qs);
                for (int x : qs) {
                    GL15.glBeginQuery(EXTTimerQuery.GL_TIME_ELAPSED_EXT, x);
                    tiny();
                    GL15.glEndQuery(EXTTimerQuery.GL_TIME_ELAPSED_EXT);
                }
            });
        }
        time("1000 tiny draws", () -> { for (int i = 0; i < 1000; i++) tiny(); });
        if (caps.GL_EXT_timer_query) loadVersusClear();
        System.out.println("probe done");
    }

    interface Op { void run(); }

    /**
     * CPU time of {@code op}, median of 5, issued right after a long stretch of queued GPU work
     * ("busy") and with the GPU idle ("idle"); a warm-up run first so compiles don't count.
     */
    static void time(String name, Op op) {
        op.run();
        GL11.glFinish();
        double[] busy = new double[5], idle = new double[5];
        for (int r = 0; r < 5; r++) {
            draw(6);
            GL11.glFlush();
            long t = System.nanoTime();
            op.run();
            busy[r] = (System.nanoTime() - t) / 1e6;
            GL11.glFinish();
            t = System.nanoTime();
            op.run();
            idle[r] = (System.nanoTime() - t) / 1e6;
            GL11.glFinish();
        }
        java.util.Arrays.sort(busy);
        java.util.Arrays.sort(idle);
        System.out.printf("%8.2f ms busy %8.2f ms idle  %s%n", busy[2], idle[2], name);
    }

    static double gpuWork() {
        GL11.glFinish();
        long t = System.nanoTime();
        draw(6);
        GL11.glFinish();
        return (System.nanoTime() - t) / 1e6;
    }

    static void setup() {
        String vs = "#version 120\nattribute vec2 p; void main() { gl_Position = vec4(p, 0.5, 1.0); }";
        String fs = "#version 120\nuniform float k; void main() { vec3 c = gl_FragCoord.xyz; float a = 0.0;"
            + " for (int i = 0; i < 400; i++) { a += sin(c.x * 0.01 + float(i) * k) * cos(c.y * 0.013 - a); }"
            + " gl_FragColor = vec4(a, a * 0.5, 1.0, 1.0); gl_FragDepth = fract(a); }";
        heavy = program(vs, fs);
        color = tex(GL30.GL_RGBA16F, GL11.GL_RGBA, GL11.GL_FLOAT, W, H);
        depth = tex(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, W, H);
        fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depth, 0);
        System.out.println("  fbo " + (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE ? "complete" : "INCOMPLETE"));
        small = tex(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, 1, 1);
        int smallColor = tex(GL30.GL_RGBA32F, GL11.GL_RGBA, GL11.GL_FLOAT, 128, 72);
        smallFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, smallFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, smallColor, 0);
        quadVbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
        FloatBuffer q = BufferUtils.createFloatBuffer(1 << 18);
        q.put(new float[] {-1, -1, 3, -1, -1, 3}).rewind();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, q, GL15.GL_DYNAMIC_DRAW);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
        GL20.glEnableVertexAttribArray(0);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_ALWAYS);
    }

    static void draw(int passes) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL11.glViewport(0, 0, W, H);
        GL20.glUseProgram(heavy);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
        for (int i = 0; i < passes; i++) {
            GL20.glUniform1f(GL20.glGetUniformLocation(heavy, "k"), 0.1f + i);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        }
    }

    static void tiny() {
        GL11.glViewport(0, 0, 1, 1);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    /**
     * GPU time of 40 full-screen passes over two 1920x1200 RGBA16F targets: drawn over (the Mac
     * loads the old contents into tile memory), or cleared first (nothing to load).
     */
    static void loadVersusClear() {
        int fill = program("#version 120\nattribute vec2 p; void main() { gl_Position = vec4(p, 0.5, 1.0); }",
            "#version 120\nuniform float k; void main() { gl_FragColor = vec4(gl_FragCoord.xy * 0.001, k, 1.0); }");
        int[] targets = {tex(GL30.GL_RGBA16F, GL11.GL_RGBA, GL11.GL_FLOAT, W, H), tex(GL30.GL_RGBA16F, GL11.GL_RGBA, GL11.GL_FLOAT, W, H)};
        int[] fbs = new int[2];
        for (int i = 0; i < 2; i++) {
            fbs[i] = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbs[i]);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, targets[i], 0);
        }
        GL20.glUseProgram(fill);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glViewport(0, 0, W, H);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
        for (int mode = 0; mode < 3; mode++) {
            double best = Double.MAX_VALUE;
            for (int r = 0; r < 6; r++) {
                int q = GL15.glGenQueries();
                GL11.glFinish();
                GL15.glBeginQuery(EXTTimerQuery.GL_TIME_ELAPSED_EXT, q);
                for (int i = 0; i < 40; i++) {
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbs[i & 1]);
                    if (mode == 1) GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                    GL20.glUniform1f(GL20.glGetUniformLocation(fill, "k"), i);
                    GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                    if (mode == 2) {
                        // a second draw into the same target: the pass stays open, nothing more to load
                        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                    }
                }
                GL15.glEndQuery(EXTTimerQuery.GL_TIME_ELAPSED_EXT);
                GL11.glFinish();
                best = Math.min(best, EXTTimerQuery.glGetQueryObjectui64EXT(q, GL15.GL_QUERY_RESULT) / 1e6);
            }
            System.out.printf("%8.2f ms GPU  40 full-screen passes, %s%n", best,
                mode == 0 ? "drawn over" : mode == 1 ? "cleared first" : "drawn over, two draws each");
        }
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    static int pickDepth, pickColor, pickFbo, pickProgram;

    /** What a 1x1 depth read into a pixel-pack buffer can become: GPU copies, then a float read the Mac does without waiting. */
    static void asyncDepth(int pbo) {
        if (pickProgram == 0) {
            pickDepth = tex(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, 1, 1);
            pickColor = tex(GL30.GL_R32F, GL30.GL_RED, GL11.GL_FLOAT, 1, 1);
            pickFbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, pickFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, pickColor, 0);
            pickProgram = program("#version 120\nattribute vec2 p; void main() { gl_Position = vec4(p, 0.5, 1.0); }",
                "#version 120\n#extension GL_EXT_gpu_shader4 : require\nuniform sampler2D d; void main() {"
                    + " gl_FragColor = vec4(texelFetch2D(d, ivec2(gl_FragCoord.xy), 0).r); }");
        }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, pickDepth);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 5, 5, 1, 1);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, pickFbo);
        GL11.glViewport(0, 0, 1, 1);
        GL20.glUseProgram(pickProgram);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, pbo);
        GL11.glReadPixels(0, 0, 1, 1, GL30.GL_RED, GL11.GL_FLOAT, 0L);
        GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, 0);
    }

    static int shaderCopyProgram;

    static void shaderCopy() {
        if (shaderCopyProgram == 0) {
            shaderCopyProgram = program("#version 120\nattribute vec2 p; void main() { gl_Position = vec4(p, 0.5, 1.0); }",
                "#version 120\n#extension GL_EXT_gpu_shader4 : require\nuniform sampler2D d; void main() {"
                    + " gl_FragColor = vec4(texelFetch2D(d, ivec2(gl_FragCoord.xy), 0).r); }");
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, smallFbo);
        GL11.glViewport(0, 0, 128, 72);
        GL20.glUseProgram(shaderCopyProgram);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    static int tex(int ifmt, int fmt, int type, int w, int h) {
        int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, ifmt, w, h, 0, fmt, type, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        return t;
    }

    static int program(String vs, String fs) {
        int v = GL20.glCreateShader(GL20.GL_VERTEX_SHADER), f = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(v, vs);
        GL20.glCompileShader(v);
        GL20.glShaderSource(f, fs);
        GL20.glCompileShader(f);
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, v);
        GL20.glAttachShader(p, f);
        GL20.glBindAttribLocation(p, 0, "p");
        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
            System.out.println("link failed: " + GL20.glGetProgramInfoLog(p) + GL20.glGetShaderInfoLog(f));
        }
        return p;
    }
}
