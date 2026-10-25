package vpmac;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * Drives the stand-ins the way Viewpoint does, in a hidden Mac legacy context, and checks the
 * pixels: a texture on virtual unit 100, a buffer texture, a software VAO with separate formats,
 * indirect draws with base instances, the atlas, views, big buffer textures, queued depth reads,
 * timers, add-on style shaders and lookups, buffer mapping, texture reads, and the bookkeeping
 * behind them (atlas copies only on change, framebuffer textures after a delete).
 */
public class SmokeTest {
    static int failures;

    public static void main(String[] args) {
        GLFW.glfwInit();
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long w = GLFW.glfwCreateWindow(64, 64, "smoke", 0, 0);
        GLFW.glfwMakeContextCurrent(w);
        GL.createCapabilities();

        // Target: an 8x8 RGBA8 framebuffer.
        int target = G.glCreateTextures(G.T_2D);
        G.glTextureStorage2D(target, 1, 0x8058, 8, 8);
        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(G.FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(G.FRAMEBUFFER, G.COLOR_ATTACHMENT0, G.T_2D, target, 0);
        check("framebuffer complete", GL30.glCheckFramebufferStatus(G.FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE);
        GL11.glViewport(0, 0, 8, 8);

        // A 1x1 green texture with immutable storage, bound on virtual unit 100.
        int green = G.glCreateTextures(G.T_2D);
        G.glTextureStorage2D(green, 1, 0x8058, 1, 1);
        ByteBuffer px = BufferUtils.createByteBuffer(4).put(new byte[] {0, (byte) 255, 0, (byte) 255}).flip();
        G.glTextureSubImage2D(green, 0, 0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
        // Bound the way the game's own Texture.bind() does it for Viewpoint's models: straight to GL.
        G.glActiveTexture(G.TEXTURE0 + 100);
        GL11.glBindTexture(G.T_2D, green);
        int sampler = G.glGenSamplers();
        G.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        G.glBindSampler(100, sampler);

        // A buffer texture on virtual unit 120 holding blue in texel 5000 (second row of the stand-in).
        int tbo = G.glCreateBuffers();
        G.glNamedBufferStorage(tbo, 8192 * 16, 0x100);
        G.glBindBuffer(G.T_BUFFER, tbo);
        FloatBuffer blue = BufferUtils.createFloatBuffer(4).put(new float[] {0, 0, 1, 1}).flip();
        G.glBufferSubData(G.T_BUFFER, 5000L * 16, blue);
        int btex = G.glCreateTextures(G.T_BUFFER);
        G.glActiveTexture(G.TEXTURE0 + 120);
        G.glBindTexture(G.T_BUFFER, btex);
        G.glTexBuffer(G.T_BUFFER, 0x8814, tbo);

        // A second float buffer on virtual unit 121: red in texel 3. Two float buffers in one
        // shader get folded into one texture, so this also checks the folding.
        int tbo2 = G.glCreateBuffers();
        G.glNamedBufferStorage(tbo2, 16 * 16, 0x100);
        G.glBindBuffer(G.T_BUFFER, tbo2);
        G.glBufferSubData(G.T_BUFFER, 3L * 16, BufferUtils.createFloatBuffer(4).put(new float[] {1, 0, 0, 1}).flip());
        int btex2 = G.glCreateTextures(G.T_BUFFER);
        G.glActiveTexture(G.TEXTURE0 + 121);
        G.glBindTexture(G.T_BUFFER, btex2);
        G.glTexBuffer(G.T_BUFFER, 0x8814, tbo2);

        String vs = "#version 330\nlayout(location = 0) in vec2 aPos;\nlayout(location = 1) in float aPick;\n"
            + "flat out int vPick;\nvoid main() { vPick = int(aPick); gl_Position = vec4(aPos, 0.0, 1.0); }\n";
        String fs = "#version 330\nuniform sampler2D uGreen;\nuniform samplerBuffer uRecords;\nuniform samplerBuffer uMore;\nuniform int uWhich;\n"
            + "flat in int vPick;\nout vec4 fragColor;\n"
            + "void main() { fragColor = vPick == 0 ? texture(uGreen, vec2(0.5)) : uWhich == 0 ? texelFetch(uRecords, 5000) : texelFetch(uMore, 3); }\n";
        int prog = program(vs, fs);
        G.glUseProgram(prog);
        G.glUniform1i(org.lwjgl.opengl.GL20.glGetUniformLocation(prog, "uGreen"), 100);
        G.glUniform1i(G.glGetUniformLocation(prog, "uRecords"), 120);
        G.glUniform1i(G.glGetUniformLocation(prog, "uMore"), 121);
        check("folded buffer reads back its unit", G.glGetUniformi(prog, G.glGetUniformLocation(prog, "uMore")) == 121);

        // Full-screen triangle strip in one buffer; per-instance "pick" values {0, 1} in another.
        int vbo = G.glCreateBuffers();
        G.glBindBuffer(G.ARRAY_BUFFER, vbo);
        G.glBufferData(G.ARRAY_BUFFER, BufferUtils.createFloatBuffer(8).put(new float[] {-1, -1, 1, -1, -1, 1, 1, 1}).flip(), 0x88E4);
        int picks = G.glCreateBuffers();
        G.glBindBuffer(G.ARRAY_BUFFER, picks);
        G.glBufferData(G.ARRAY_BUFFER, BufferUtils.createFloatBuffer(2).put(new float[] {0, 1}).flip(), 0x88E4);
        int vao = G.glGenVertexArrays();
        G.glBindVertexArray(vao);
        G.glVertexAttribFormat(0, 2, GL11.GL_FLOAT, false, 0);
        G.glVertexAttribBinding(0, 0);
        G.glBindVertexBuffer(0, vbo, 0, 8);
        G.glEnableVertexAttribArray(0);
        G.glVertexAttribFormat(1, 1, GL11.GL_FLOAT, false, 0);
        G.glVertexAttribBinding(1, 1);
        G.glBindVertexBuffer(1, picks, 0, 4);
        G.glVertexBindingDivisor(1, 1);
        G.glEnableVertexAttribArray(1);

        G.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        check("virtual unit 100 bound outside the stand-ins + sampler object -> green", pixel(0, 255, 0));

        // Indirect draw with base instance 1: picks the buffer texture -> blue.
        int cmds = G.glCreateBuffers();
        G.glBindBuffer(G.DRAW_INDIRECT_BUFFER, cmds);
        G.glBufferData(G.DRAW_INDIRECT_BUFFER, 16, 0x88E4);
        ByteBuffer cmd = BufferUtils.createByteBuffer(16);
        cmd.putInt(4).putInt(1).putInt(0).putInt(1).flip();
        G.glBufferSubData(G.DRAW_INDIRECT_BUFFER, 0, cmd);
        G.glMultiDrawArraysIndirect(GL11.GL_TRIANGLE_STRIP, 0, 1, 0);
        check("indirect draw, base instance 1, buffer texture -> blue", pixel(0, 0, 255));
        org.lwjgl.opengl.GL20.glUniform1i(org.lwjgl.opengl.GL20.glGetUniformLocation(prog, "uWhich"), 1);
        G.glMultiDrawArraysIndirect(GL11.GL_TRIANGLE_STRIP, 0, 1, 0);
        check("second buffer, folded under the first -> red", pixel(255, 0, 0));

        atlasCheck(vao);
        hugeCheck(vao);
        viewCheck(vao, fbo);
        bigBufferCheck(vao);
        pickCheck(vao, fbo);
        timerCheck(vao);
        addonShaderCheck(vao);
        reflectCheck();
        indirectRunCheck(1);
        indirectRunCheck(1 << 30);
        mapCheck(vao);
        textureReadCheck();
        stateQueryCheck();
        fboTouchCheck();
        glslChecks();
        addonsChecks();
        modelSlotsCheck();
        gpuWriteCheck();
        reusedNameCheck(vao);
        queryErrorCheck();
        mapRangeCheck();

        G.glBindVertexArray(0);
        check("no GL error", GL11.glGetError() == 0);
        System.out.println(failures == 0 ? "SMOKE OK" : "SMOKE FAILED: " + failures);
        GLFW.glfwTerminate();
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * 17 sampler2D textures in one fragment shader: one more than the Mac has units, so linking
     * moves some into the atlas. uT0..uT15 are 1x1 with red 16*i; uT16 is 4x2 with blue 200 at (3,1).
     */
    static void atlasCheck(int vao) {
        StringBuilder fs = new StringBuilder("#version 330\n");
        for (int i = 0; i < 17; i++) fs.append("uniform sampler2D ").append(i == 16 ? "uSkyLut" : "uT" + i).append(";\n");
        fs.append("out vec4 fragColor;\nvoid main() {\n  float r = 0.0;\n");
        for (int i = 0; i < 16; i++) fs.append("  r += texture(uT").append(i).append(", vec2(0.5)).r;\n");
        fs.append("  fragColor = vec4(r / 16.0, float(textureSize(uSkyLut, 0).x) * 50.0 / 255.0, texelFetch(uSkyLut, ivec2(3, 1), 0).b, 1.0);\n}\n");
        String vs = "#version 330\nlayout(location = 0) in vec2 aPos;\nvoid main() { gl_Position = vec4(aPos, 0.0, 1.0); }\n";
        int prog = program(vs, fs.toString());
        atlasProgram = prog;
        G.glUseProgram(prog);
        for (int i = 0; i < 17; i++) {
            int t = G.glCreateTextures(G.T_2D);
            boolean big = i == 16;
            G.glTextureStorage2D(t, 1, 0x8058, big ? 4 : 1, big ? 2 : 1);
            G.glTextureParameteri(t, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            ByteBuffer data = BufferUtils.createByteBuffer(big ? 32 : 4);
            if (big) data.put(7 * 4 + 2, (byte) 200);
            else data.put(0, (byte) (16 * i));
            G.glTextureSubImage2D(t, 0, 0, 0, big ? 4 : 1, big ? 2 : 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);
            G.glActiveTexture(G.TEXTURE0 + 20 + i);
            G.glBindTexture(G.T_2D, t);
            G.glUniform1i(G.glGetUniformLocation(prog, i == 16 ? "uSkyLut" : "uT" + i), 20 + i);
            lastAtlasTextures[i] = t;
        }
        G.glBindVertexArray(vao);
        G.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        check("17 textures, some in the atlas: filtered, size and texelFetch reads", pixel(120, 200, 200));
        long copies = Stats.atlasCopies;
        G.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        check("atlas: a second draw with nothing changed copies nothing", Stats.atlasCopies == copies);
        // uSkyLut is in the atlas (cheapest first): write a texel, and only it is copied again.
        G.glTextureSubImage2D(lastAtlasTextures[16], 0, 3, 1, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
            BufferUtils.createByteBuffer(4).put(new byte[] {0, 0, 100, 0}).flip());
        G.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        check("atlas: one texture written, one copy, and the new texel is read (" + (Stats.atlasCopies - copies) + " copies)",
            Stats.atlasCopies == copies + 1 && pixel(120, 200, 100));
    }

    static int[] lastAtlasTextures = new int[17];
    static int atlasProgram;

    /**
     * 17 textures that are all "relief", so one too big for an atlas gets packed: uRelief0 is 4100x8
     * R16 with 40000 at (4000, 5). Stored smaller, it must still read back that texel and its real size.
     */
    static void hugeCheck(int vao) {
        StringBuilder fs = new StringBuilder("#version 330\n");
        for (int i = 0; i < 17; i++) fs.append("uniform sampler2D uRelief").append(i).append(";\n");
        fs.append("out vec4 fragColor;\nvoid main() {\n  float r = 0.0;\n");
        for (int i = 1; i < 17; i++) fs.append("  r += texture(uRelief").append(i).append(", vec2(0.5)).r;\n");
        fs.append("  fragColor = vec4(texelFetch(uRelief0, ivec2(4000, 5), 0).r, textureSize(uRelief0, 0) == ivec2(4100, 8) ? 1.0 : 0.0, r, 1.0);\n}\n");
        String vs = "#version 330\nlayout(location = 0) in vec2 aPos;\nvoid main() { gl_Position = vec4(aPos, 0.0, 1.0); }\n";
        int prog = program(vs, fs.toString());
        G.glUseProgram(prog);
        for (int i = 0; i < 17; i++) {
            int t = G.glCreateTextures(G.T_2D);
            boolean big = i == 0;
            G.glTextureStorage2D(t, 1, big ? 0x822A : 0x8058, big ? 4100 : 1, big ? 8 : 1);
            G.glTextureParameteri(t, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            if (big) {
                ByteBuffer data = BufferUtils.createByteBuffer(4100 * 8 * 2);
                data.putShort((5 * 4100 + 4000) * 2, (short) 40000);
                G.glTextureSubImage2D(t, 0, 0, 0, 4100, 8, 0x1903, GL11.GL_UNSIGNED_SHORT, data);
            } else {
                G.glTextureSubImage2D(t, 0, 0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, BufferUtils.createByteBuffer(4));
            }
            G.glActiveTexture(G.TEXTURE0 + 40 + i);
            G.glBindTexture(G.T_2D, t);
            G.glUniform1i(G.glGetUniformLocation(prog, "uRelief" + i), 40 + i);
        }
        G.glBindVertexArray(vao);
        G.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        check("a relief too big for the atlas, stored smaller: texel and size", pixel(156, 255, 0));
    }

    /** A depth array with 3 layers, a 2D view of layer 1 drawn into through a framebuffer, then sampled. */
    static void viewCheck(int vao, int colorFbo) {
        int array = G.glCreateTextures(G.T_2D_ARRAY);
        G.glTextureStorage3D(array, 1, 0x81A6, 8, 8, 3);
        int[] views = new int[3];
        GL11.glGenTextures(views);
        for (int i = 0; i < 3; i++) G.glTextureView(views[i], G.T_2D, array, 0x81A6, 0, 1, i, 1);
        int shadowFbo = GL30.glGenFramebuffers();
        G.glBindFramebuffer(G.FRAMEBUFFER, shadowFbo);
        G.glFramebufferTexture2D(G.FRAMEBUFFER, 0x8D00, G.T_2D, views[1], 0);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);
        check("shadow framebuffer on a view is complete", GL30.glCheckFramebufferStatus(G.FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE);
        GL11.glViewport(0, 0, 8, 8);
        GL11.glClearDepth(0.25);
        G.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glClearDepth(1.0);
        G.glBindFramebuffer(G.FRAMEBUFFER, colorFbo);
        String vs = "#version 330\nlayout(location = 0) in vec2 aPos;\nvoid main() { gl_Position = vec4(aPos, 0.0, 1.0); }\n";
        String fs = "#version 330\nuniform sampler2D uDepthView;\nout vec4 fragColor;\n"
            + "void main() { fragColor = vec4(texelFetch(uDepthView, ivec2(2, 2), 0).r, 0.0, 0.0, 1.0); }\n";
        int prog = program(vs, fs);
        G.glUseProgram(prog);
        G.glActiveTexture(G.TEXTURE0 + 70);
        G.glBindTexture(G.T_2D, views[1]);
        G.glUniform1i(G.glGetUniformLocation(prog, "uDepthView"), 70);
        G.glBindVertexArray(vao);
        G.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        check("depth drawn through the view reads back as 0.25", pixel(64, 0, 0));
    }

    /** An 80 MB buffer texture (no CPU copy kept): written, read, written again in one spot, read again. */
    static void bigBufferCheck(int vao) {
        int texels = 5 * 1024 * 1024, at = 5_000_000;
        int buf = G.glCreateBuffers();
        G.glNamedBufferStorage(buf, (long) texels * 16, 0x100);
        G.glBindBuffer(G.T_BUFFER, buf);
        G.glBufferSubData(G.T_BUFFER, (long) at * 16, BufferUtils.createFloatBuffer(4).put(new float[] {1, 0, 0, 1}).flip());
        int tex = G.glCreateTextures(G.T_BUFFER);
        G.glActiveTexture(G.TEXTURE0 + 130);
        G.glBindTexture(G.T_BUFFER, tex);
        G.glTexBuffer(G.T_BUFFER, 0x8814, buf);
        String vs = "#version 330\nlayout(location = 0) in vec2 aPos;\nvoid main() { gl_Position = vec4(aPos, 0.0, 1.0); }\n";
        String fs = "#version 330\nuniform samplerBuffer uBig;\nout vec4 fragColor;\nvoid main() { fragColor = texelFetch(uBig, " + at + "); }\n";
        int prog = program(vs, fs);
        G.glUseProgram(prog);
        G.glUniform1i(G.glGetUniformLocation(prog, "uBig"), 130);
        G.glBindVertexArray(vao);
        G.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        check("80 MB buffer texture, copied on the GPU -> red", pixel(255, 0, 0));
        G.glBindBuffer(G.T_BUFFER, buf);
        G.glBufferSubData(G.T_BUFFER, (long) at * 16, BufferUtils.createFloatBuffer(4).put(new float[] {0, 1, 0, 1}).flip());
        G.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        check("same texel rewritten, only its row copied again -> green", pixel(0, 255, 0));
    }

    /** A depth read into a pack buffer, the way Viewpoint reads the cursor's depth: queued, same value, state put back. */
    static void pickCheck(int vao, int colorFbo) {
        int depth = G.glCreateTextures(G.T_2D);
        G.glTextureStorage2D(depth, 1, 0x8CAC, 8, 8); // DEPTH_COMPONENT32F
        int depthFbo = GL30.glGenFramebuffers();
        G.glBindFramebuffer(G.FRAMEBUFFER, depthFbo);
        G.glFramebufferTexture2D(G.FRAMEBUFFER, G.DEPTH_ATTACHMENT, G.T_2D, depth, 0);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);
        GL11.glViewport(0, 0, 8, 8);
        GL11.glClearDepth(0.375);
        G.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glClearDepth(1.0);
        G.glBindFramebuffer(G.FRAMEBUFFER, colorFbo);
        int pbo = G.glCreateBuffers();
        G.glBindBuffer(G.PIXEL_PACK_BUFFER, pbo);
        G.glBufferData(G.PIXEL_PACK_BUFFER, 4, 0x88E1); // STREAM_READ
        G.glBindFramebuffer(G.READ_FRAMEBUFFER, depthFbo);
        GL11.glViewport(1, 2, 7, 5);
        int program = program("#version 330\nlayout(location = 0) in vec2 aPos;\nvoid main() { gl_Position = vec4(aPos, 0.0, 1.0); }\n",
            "#version 330\nout vec4 fragColor;\nvoid main() { fragColor = vec4(1.0); }\n");
        G.glUseProgram(program);
        G.glReadPixels(3, 4, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, 0L);
        boolean restored = GL11.glGetInteger(G.READ_FRAMEBUFFER_BINDING) == depthFbo && GL11.glGetInteger(G.DRAW_FRAMEBUFFER_BINDING) == colorFbo
            && GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM) == program;
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        restored &= viewport[0] == 1 && viewport[1] == 2 && viewport[2] == 7 && viewport[3] == 5;
        if (!restored) System.out.println("   read fb " + GL11.glGetInteger(G.READ_FRAMEBUFFER_BINDING) + "/" + depthFbo + ", draw fb "
            + GL11.glGetInteger(G.DRAW_FRAMEBUFFER_BINDING) + "/" + colorFbo + ", program " + GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM)
            + "/" + program + ", viewport " + java.util.Arrays.toString(viewport));
        GL11.glFinish();
        FloatBuffer got = BufferUtils.createFloatBuffer(1);
        G.glGetBufferSubData(G.PIXEL_PACK_BUFFER, 0, got);
        G.glBindBuffer(G.PIXEL_PACK_BUFFER, 0);
        G.glBindFramebuffer(G.FRAMEBUFFER, colorFbo);
        GL11.glViewport(0, 0, 8, 8);
        G.glUseProgram(0);
        check("depth read into a pack buffer, queued -> 0.375 (got " + got.get(0) + ")", got.get(0) == 0.375f);
        check("depth read leaves framebuffers, program and viewport as they were", restored);
    }

    /** Timestamps from chained elapsed-time queries, and occlusion queries still answered by GL. */
    static void timerCheck(int vao) {
        int[] q = new int[3];
        org.lwjgl.opengl.GL15.glGenQueries(q);
        G.glQueryCounter(q[0], 0x8E28);
        check("timers off: a counter is ready at once", G.glGetQueryObjecti(q[0], G.QUERY_RESULT_AVAILABLE) == 1);
        Timers.on = true;
        G.glQueryCounter(q[0], 0x8E28);
        G.glBindVertexArray(vao);
        for (int i = 0; i < 50; i++) G.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        G.glQueryCounter(q[1], 0x8E28);
        GL11.glFinish();
        boolean ready = G.glGetQueryObjecti(q[1], G.QUERY_RESULT_AVAILABLE) == 1;
        long span = G.glGetQueryObjectui64(q[1], 0x8866) - G.glGetQueryObjectui64(q[0], 0x8866);
        Timers.stop();
        check("timers on: the span between two counters is measured (" + span + " ns)", ready && span > 0);
        org.lwjgl.opengl.GL15.glBeginQuery(0x8914, q[2]); // SAMPLES_PASSED
        G.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        org.lwjgl.opengl.GL15.glEndQuery(0x8914);
        GL11.glFinish();
        check("an occlusion query still answers through the 64-bit read", G.glGetQueryObjecti64(q[2], 0x8866) > 0);
    }

    /** The way add-ons write shaders: several declarations a line, samplers in a list, inverse() on matrices. */
    static void addonShaderCheck(int vao) {
        String vs = "#version 330 core\nlayout(location = 0) in vec2 aPos; out vec2 vUv; out vec4 vCol; flat out int vMode;\n"
            + "void main() { vUv = aPos * 0.5 + 0.5; vCol = vec4(1.0); vMode = 1; gl_Position = vec4(aPos, 0.0, 1.0); }\n";
        String fs = "#version 330 core\nin vec2 vUv; in vec4 vCol; flat in int vMode;\nuniform sampler2D uA,uB;\nuniform mat4 uM;\nout vec4 fragColor;\n"
            + "void main() { mat4 i4 = inverse(uM) * uM; mat3 i3 = inverse(mat3(uM)) * mat3(uM); mat2 i2 = inverse(mat2(uM)) * mat2(uM); float e = 0.0;\n"
            + "  for (int c = 0; c < 4; c++) for (int r = 0; r < 4; r++) e += abs(i4[c][r] - (c == r ? 1.0 : 0.0));\n"
            + "  for (int c = 0; c < 3; c++) for (int r = 0; r < 3; r++) e += abs(i3[c][r] - (c == r ? 1.0 : 0.0));\n"
            + "  for (int c = 0; c < 2; c++) for (int r = 0; r < 2; r++) e += abs(i2[c][r] - (c == r ? 1.0 : 0.0));\n"
            + "  vec4 t = texture(uA, vUv) + texture(uB, vUv);\n"
            + "  fragColor = (e < 0.001 && vMode == 1 && vCol.a == 1.0) ? vec4(0.0, 1.0, 0.0, 1.0) + t * 0.0 : vec4(1.0, 0.0, 0.0, 1.0); }\n";
        int prog = program(vs, fs);
        G.glUseProgram(prog);
        float[] m = {2, 0.5f, 0, 0, -0.25f, 1.5f, 0.3f, 0, 0.1f, 0, 3, 0, 4, -2, 1, 1}; // scale, shear, translation
        org.lwjgl.opengl.GL20.glUniformMatrix4fv(org.lwjgl.opengl.GL20.glGetUniformLocation(prog, "uM"), false, m);
        G.glUniform1i(G.glGetUniformLocation(prog, "uA"), 0);
        G.glUniform1i(G.glGetUniformLocation(prog, "uB"), 1);
        G.glBindVertexArray(vao);
        G.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        check("add-on style shader: one-line declarations, sampler list, inverse() of mat2/3/4 -> green", pixel(0, 255, 0));
        G.glUseProgram(0);
    }

    /** Reflective GL lookups, the way some add-ons make their calls, land on the stand-ins. */
    static void reflectCheck() {
        try {
            java.lang.reflect.Method m = Reflect.getDeclaredMethod(Class.forName("org.lwjgl.opengl.GL33"), "glBindSampler", int.class, int.class);
            java.lang.reflect.Method plain = Reflect.getMethod(Class.forName("org.lwjgl.opengl.GL11"), "glFlush");
            check("reflective GL33.glBindSampler resolves to the stand-in, glFlush stays real",
                m.getDeclaringClass() == G.class && plain.getDeclaringClass() != G.class);
        } catch (Exception e) {
            check("reflective lookup: " + e, false);
        }
    }

    /**
     * One indirect draw of three commands, each a third of the target with its own base instance
     * (red, green, blue from an instanced attribute); the array buffer bound before is bound after.
     * With a divisor of 2^30 too, Viewpoint's way of giving each draw its own data: GL never
     * divides the base instance, only the instance.
     */
    static void indirectRunCheck(int divisor) {
        float[] quads = new float[3 * 8];
        for (int k = 0; k < 3; k++) {
            float x0 = -1 + k * 2 / 3f, x1 = -1 + (k + 1) * 2 / 3f;
            System.arraycopy(new float[] {x0, -1, x1, -1, x0, 1, x1, 1}, 0, quads, k * 8, 8);
        }
        int vbo = G.glCreateBuffers();
        G.glBindBuffer(G.ARRAY_BUFFER, vbo);
        G.glBufferData(G.ARRAY_BUFFER, BufferUtils.createFloatBuffer(24).put(quads).flip(), 0x88E4);
        int colors = G.glCreateBuffers();
        G.glBindBuffer(G.ARRAY_BUFFER, colors);
        G.glBufferData(G.ARRAY_BUFFER, BufferUtils.createFloatBuffer(3).put(new float[] {0, 1, 2}).flip(), 0x88E4);
        int vao = G.glGenVertexArrays();
        G.glBindVertexArray(vao);
        G.glVertexAttribFormat(0, 2, GL11.GL_FLOAT, false, 0);
        G.glVertexAttribBinding(0, 0);
        G.glBindVertexBuffer(0, vbo, 0, 8);
        G.glEnableVertexAttribArray(0);
        G.glVertexAttribFormat(1, 1, GL11.GL_FLOAT, false, 0);
        G.glVertexAttribBinding(1, 1);
        G.glBindVertexBuffer(1, colors, 0, 4);
        G.glVertexBindingDivisor(1, divisor);
        G.glEnableVertexAttribArray(1);
        int prog = program("#version 330\nlayout(location = 0) in vec2 aPos;\nlayout(location = 1) in float aColor;\nflat out int vColor;\n"
                + "void main() { vColor = int(aColor); gl_Position = vec4(aPos, 0.0, 1.0); }\n",
            "#version 330\nflat in int vColor;\nout vec4 fragColor;\n"
                + "void main() { fragColor = vec4(vColor == 0 ? 1.0 : 0.0, vColor == 1 ? 1.0 : 0.0, vColor == 2 ? 1.0 : 0.0, 1.0); }\n");
        G.glUseProgram(prog);
        int cmds = G.glCreateBuffers();
        G.glBindBuffer(G.DRAW_INDIRECT_BUFFER, cmds);
        ByteBuffer c = BufferUtils.createByteBuffer(48);
        for (int k = 0; k < 3; k++) c.putInt(4).putInt(1).putInt(k * 4).putInt(k);
        c.flip();
        G.glBufferData(G.DRAW_INDIRECT_BUFFER, 48, 0x88E4);
        G.glBufferSubData(G.DRAW_INDIRECT_BUFFER, 0, c);
        int marker = G.glCreateBuffers();
        G.glBindBuffer(G.ARRAY_BUFFER, marker);
        G.glMultiDrawArraysIndirect(GL11.GL_TRIANGLE_STRIP, 0, 3, 0);
        check("indirect draw of 3 commands, divisor " + divisor + ", base instances 0/1/2 -> red, green, blue thirds",
            pixelAt(1, 4, 255, 0, 0) && pixelAt(4, 4, 0, 255, 0) && pixelAt(6, 4, 0, 0, 255));
        check("indirect draw puts the array buffer back", GL11.glGetInteger(G.ARRAY_BUFFER_BINDING) == marker);
        G.glBindVertexArray(0);
        G.glUseProgram(0);
    }

    /** A range of a buffer mapped and written: the buffer texture made from it shows the write. */
    static void mapCheck(int vao) {
        int buf = G.glCreateBuffers();
        G.glNamedBufferStorage(buf, 64 * 16, 0x100);
        int tex = G.glCreateTextures(G.T_BUFFER);
        G.glActiveTexture(G.TEXTURE0 + 140);
        G.glBindTexture(G.T_BUFFER, tex);
        G.glTexBuffer(G.T_BUFFER, 0x8814, buf);
        int prog = program("#version 330\nlayout(location = 0) in vec2 aPos;\nvoid main() { gl_Position = vec4(aPos, 0.0, 1.0); }\n",
            "#version 330\nuniform samplerBuffer uMapped;\nout vec4 fragColor;\nvoid main() { fragColor = texelFetch(uMapped, 7); }\n");
        G.glUseProgram(prog);
        G.glUniform1i(G.glGetUniformLocation(prog, "uMapped"), 140);
        G.glBindVertexArray(vao);
        G.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        G.glBindBuffer(G.T_BUFFER, buf);
        ByteBuffer range = G.glMapBufferRange(G.T_BUFFER, 7 * 16, 16, 2); // MAP_WRITE_BIT
        boolean mapped = range != null && range.capacity() == 16;
        if (mapped) range.asFloatBuffer().put(new float[] {0, 1, 0, 1});
        boolean unmapped = G.glUnmapBuffer(G.T_BUFFER);
        G.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        check("a mapped range written, unmapped, read through the buffer texture -> green", mapped && unmapped && pixel(0, 255, 0));
        G.glUseProgram(0);
    }

    /** glGetTextureSubImage: a color texel into memory, a depth texel into a pack buffer. */
    static void textureReadCheck() {
        int tex = G.glCreateTextures(G.T_2D);
        G.glTextureStorage2D(tex, 1, 0x8058, 4, 4);
        ByteBuffer texels = BufferUtils.createByteBuffer(64);
        texels.put((2 * 4 + 1) * 4, (byte) 10).put((2 * 4 + 1) * 4 + 1, (byte) 20).put((2 * 4 + 1) * 4 + 2, (byte) 30);
        G.glTextureSubImage2D(tex, 0, 0, 0, 4, 4, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, texels);
        ByteBuffer out = BufferUtils.createByteBuffer(4);
        G.glGetTextureSubImage(tex, 0, 1, 2, 0, 1, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
        check("texture sub-image read: texel (1, 2) is 10,20,30", out.get(0) == 10 && out.get(1) == 20 && out.get(2) == 30);

        int depth = G.glCreateTextures(G.T_2D);
        G.glTextureStorage2D(depth, 1, 0x8CAC, 4, 4);
        int fb = GL30.glGenFramebuffers();
        int drawFb = GL11.glGetInteger(G.DRAW_FRAMEBUFFER_BINDING);
        G.glBindFramebuffer(G.FRAMEBUFFER, fb);
        G.glFramebufferTexture2D(G.FRAMEBUFFER, G.DEPTH_ATTACHMENT, G.T_2D, depth, 0);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);
        GL11.glViewport(0, 0, 4, 4);
        GL11.glClearDepth(0.625);
        G.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glClearDepth(1.0);
        G.glBindFramebuffer(G.FRAMEBUFFER, drawFb);
        GL11.glViewport(0, 0, 8, 8);
        int pbo = G.glCreateBuffers();
        G.glBindBuffer(G.PIXEL_PACK_BUFFER, pbo);
        G.glBufferData(G.PIXEL_PACK_BUFFER, 4, 0x88E1);
        G.glGetTextureSubImage(depth, 0, 2, 2, 0, 1, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, 4, 0L);
        GL11.glFinish();
        FloatBuffer got = BufferUtils.createFloatBuffer(1);
        G.glGetBufferSubData(G.PIXEL_PACK_BUFFER, 0, got);
        G.glBindBuffer(G.PIXEL_PACK_BUFFER, 0);
        check("texture sub-image depth read into a pack buffer -> 0.625 (got " + got.get(0) + ")", got.get(0) == 0.625f);
    }

    /** The state queries add-ons make: the sampler on the active unit, how many units there are. */
    static void stateQueryCheck() {
        int pending = GL11.glGetError();
        if (pending != 0) check("no GL error left by the checks before (0x" + Integer.toHexString(pending) + ")", false);
        int s = G.glGenSamplers();
        G.glActiveTexture(G.TEXTURE0 + 100);
        G.glBindSampler(100, s);
        boolean sampler = G.glGetInteger(0x8919) == s;
        G.glBindSampler(100, 0);
        G.glActiveTexture(G.TEXTURE0);
        check("GL_SAMPLER_BINDING answers the active unit's sampler, MAX_COMBINED the virtual units",
            sampler && G.glGetInteger(0x8B4D) == G.VIRTUAL_UNITS - 4 && GL11.glGetError() == 0);
    }

    /** Drawing into a framebuffer marks its textures changed, also after one was deleted and replaced. */
    static void fboTouchCheck() {
        int fb = GL30.glGenFramebuffers();
        int drawFb = GL11.glGetInteger(G.DRAW_FRAMEBUFFER_BINDING);
        int a = G.glCreateTextures(G.T_2D);
        G.glTextureStorage2D(a, 1, 0x8058, 4, 4);
        G.glBindFramebuffer(G.FRAMEBUFFER, fb);
        G.glFramebufferTexture2D(G.FRAMEBUFFER, G.COLOR_ATTACHMENT0, G.T_2D, a, 0);
        int before = Tex.info(a).version;
        G.glClear(GL11.GL_COLOR_BUFFER_BIT);
        boolean first = Tex.info(a).version == before + 1;
        G.glFramebufferTexture2D(G.FRAMEBUFFER, G.COLOR_ATTACHMENT0, G.T_2D, 0, 0);
        G.glDeleteTextures(a);
        int b = G.glCreateTextures(G.T_2D);
        G.glTextureStorage2D(b, 1, 0x8058, 4, 4);
        G.glFramebufferTexture2D(G.FRAMEBUFFER, G.COLOR_ATTACHMENT0, G.T_2D, b, 0);
        int beforeB = Tex.info(b).version;
        G.glClear(GL11.GL_COLOR_BUFFER_BIT);
        check("a framebuffer's texture marked changed by a clear, and its replacement after a delete",
            first && Tex.info(b).version == beforeB + 1);
        G.glBindFramebuffer(G.FRAMEBUFFER, drawFb);
    }

    /** The translator's declaration splitting, on its own text. */
    static void glslChecks() {
        String in = "out vec2 vUv; out vec4 vCol; flat out int vMode; // three\n"
            + "uniform sampler2D uA, uB[2];\n"
            + "float f(float x) { x += 1.0; return x; }\n"
            + "#define PAIR 1; 2\n"
            + "/* a; b; */ uniform float uK;\n"
            + "void main() {\n  float a = 1.0; float b = 2.0;\n}\n";
        String out = Glsl.oneDeclarationEach(in);
        java.util.List<String> lines = java.util.Arrays.stream(out.split("\n")).map(String::trim).toList();
        check("translator: one declaration a line", lines.contains("out vec2 vUv;") && lines.contains("out vec4 vCol;")
            && lines.contains("flat out int vMode;"));
        check("translator: a sampler list split", lines.contains("uniform sampler2D uA;") && lines.contains("uniform sampler2D uB[2];"));
        String macro = Glsl.oneDeclarationEach("#define SHADE(c) \\\n  c.rgb *= 2.0; \\\n  c.a = 1.0;\nout vec2 a; out vec2 b;\n");
        check("translator: a macro over several lines stays whole, the line after it is split",
            macro.contains("#define SHADE(c) \\\n  c.rgb *= 2.0; \\\n  c.a = 1.0;\n") && macro.contains("out vec2 a;\n out vec2 b;"));
        check("translator: functions, defines, comments and bodies left alone",
            out.contains("float f(float x) { x += 1.0; return x; }") && out.contains("#define PAIR 1; 2")
                && out.contains("/* a; b; */ uniform float uK;") && out.contains("  float a = 1.0; float b = 2.0;"));
    }

    /** Which packages count as Viewpoint add-ons, from mod.info files and by name. */
    static void addonsChecks() {
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("vpmac-addons");
            java.nio.file.Path yes = dir.resolve("yes.info"), mac = dir.resolve("mac.info"), none = dir.resolve("none.info");
            java.nio.file.Files.writeString(yes, "name=An add-on\nid=SomeAddon\nrequire=\\ZombieBuddy,\\Viewpoint\njavaPkgName=some.addon\n");
            java.nio.file.Files.writeString(mac, "id=Other\nrequire=\\ViewpointMac\njavaPkgName=other.thing\n");
            java.nio.file.Files.writeString(none, "id=NoJava\nrequire=\\Viewpoint\n");
            java.util.List<String> found = new java.util.ArrayList<>();
            Addons.read(yes, found);
            Addons.read(mac, found);
            Addons.read(none, found);
            check("add-ons: a mod requiring Viewpoint is rewritten, one requiring only ViewpointMac is not",
                Addons.covers("some/addon/Draw") && !Addons.covers("other/thing/Draw") && found.size() == 1);
            for (String bad : new String[] {"com", "vpmac", "org.lwjgl.opengl", "zombie.core", "java.lang"}) Addons.add(bad, "test", found);
            check("add-ons: the game's, Java's, LWJGL's, this mod's and bare top-level packages are never rewritten",
                !Addons.covers("com/x/Y") && !Addons.covers("vpmac/G") && !Addons.covers("org/lwjgl/opengl/GL11")
                    && !Addons.covers("zombie/core/Core") && !Addons.covers("java/lang/String") && found.size() == 1);
            for (java.nio.file.Path p : new java.nio.file.Path[] {yes, mac, none}) java.nio.file.Files.delete(p);
            // A folder that can't be read, ahead of an add-on: the add-on is still found.
            java.nio.file.Path locked = dir.resolve("a-locked"), later = dir.resolve("b-later");
            java.nio.file.Files.createDirectories(locked);
            java.nio.file.Files.createDirectories(later);
            java.nio.file.Files.writeString(later.resolve("mod.info"), "id=Later\nrequire=\\Viewpoint\njavaPkgName=later.addon\n");
            locked.toFile().setReadable(false);
            Addons.scan(dir, found);
            locked.toFile().setReadable(true);
            check("add-ons: an unreadable folder doesn't hide the add-ons after it", Addons.covers("later/addon/X"));
            java.nio.file.Files.delete(later.resolve("mod.info"));
            java.nio.file.Files.delete(later);
            java.nio.file.Files.delete(locked);
            java.nio.file.Files.delete(dir);
        } catch (Exception e) {
            check("add-ons: " + e, false);
        }
    }

    /**
     * A model program over 16 units with nothing left for the atlas (8 model textures and 9 array
     * samplers) links by keeping 7 model textures, and model batches shrink to match.
     */
    static void modelSlotsCheck() {
        StringBuilder fs = new StringBuilder("#version 330\n#define MODEL_TEXTURES 16\nuniform sampler2D uTextures[MODEL_TEXTURES];\nuniform int uSlot;\n");
        for (int i = 0; i < 9; i++) fs.append("uniform sampler2DArray uLayers").append(i).append(";\n");
        fs.append("out vec4 fragColor;\nvoid main() { vec4 c = texture(uTextures[uSlot], vec2(0.5));\n");
        for (int i = 0; i < 9; i++) fs.append("  c += texture(uLayers").append(i).append(", vec3(0.5, 0.5, 0.0));\n");
        fs.append("  fragColor = c; }\n");
        int before = ModelSlots.count;
        int prog = program("#version 330\nlayout(location = 0) in vec2 aPos;\nvoid main() { gl_Position = vec4(aPos, 0.0, 1.0); }\n", fs.toString());
        Prog.Program p = Prog.programs.get(prog);
        check("a model program with nothing left for the atlas links by keeping " + ModelSlots.count + " model textures",
            p != null && p.linked && ModelSlots.count == before - 1);
        ModelSlots.count = before;
    }

    /**
     * What the GPU writes into a buffer (a texture read into it as the pack buffer) is what reading
     * the buffer by name returns, next to what the CPU wrote, and what copying it carries on.
     */
    static void gpuWriteCheck() {
        int tex = G.glCreateTextures(G.T_2D);
        G.glTextureStorage2D(tex, 1, 0x8058, 2, 2);
        ByteBuffer texels = BufferUtils.createByteBuffer(16);
        for (int i = 0; i < 16; i++) texels.put(i, (byte) (i + 1));
        G.glTextureSubImage2D(tex, 0, 0, 0, 2, 2, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, texels);
        int buf = G.glCreateBuffers();
        G.glNamedBufferStorage(buf, 32, 0x100);
        G.glBindBuffer(G.ARRAY_BUFFER, buf);
        G.glBufferSubData(G.ARRAY_BUFFER, 0, BufferUtils.createByteBuffer(16).put(0, (byte) 99).put(15, (byte) 98));
        G.glBindBuffer(G.PIXEL_PACK_BUFFER, buf);
        G.glGetTextureImage(tex, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 16, 16L);
        G.glBindBuffer(G.PIXEL_PACK_BUFFER, 0);
        ByteBuffer out = BufferUtils.createByteBuffer(32);
        G.glGetNamedBufferSubData(buf, 0, out);
        boolean cpu = out.get(0) == 99 && out.get(15) == 98, gpu = true;
        for (int i = 0; i < 16; i++) gpu &= out.get(16 + i) == i + 1;
        int copy = G.glCreateBuffers();
        G.glNamedBufferStorage(copy, 16, 0x100);
        G.glCopyNamedBufferSubData(buf, copy, 16, 0, 16);
        ByteBuffer copied = BufferUtils.createByteBuffer(16);
        G.glGetNamedBufferSubData(copy, 0, copied);
        check("a buffer the GPU wrote into reads back what it wrote, and what the CPU wrote, and copies on",
            cpu && gpu && copied.get(0) == 1 && copied.get(15) == 16);
    }

    /**
     * A texture in the atlas and a buffer behind a buffer texture, deleted and made again under the
     * same name at the same size: the new contents are what draws read, not the old ones.
     */
    static void reusedNameCheck(int vao) {
        int old = lastAtlasTextures[16];
        int prog = Prog.currentId;
        G.glDeleteTextures(old);
        int again = G.glCreateTextures(G.T_2D);
        if (again != old) {
            System.out.println("   (GL gave a new name, " + again + " for " + old + "; the reused-name case can't be set up here)");
        } else {
            G.glTextureStorage2D(again, 1, 0x8058, 4, 2);
            ByteBuffer data = BufferUtils.createByteBuffer(32);
            data.put(7 * 4 + 2, (byte) 40);
            G.glTextureSubImage2D(again, 0, 0, 0, 4, 2, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);
            G.glUseProgram(atlasProgram);
            G.glActiveTexture(G.TEXTURE0 + 36);
            G.glBindTexture(G.T_2D, again);
            G.glBindVertexArray(vao);
            G.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
            check("atlas: a texture made again under the same name is copied again -> new texel", pixel(120, 200, 40));
        }
        int buf = G.glCreateBuffers();
        G.glNamedBufferStorage(buf, 16 * 16, 0x100);
        G.glBindBuffer(G.T_BUFFER, buf);
        G.glBufferSubData(G.T_BUFFER, 3L * 16, BufferUtils.createFloatBuffer(4).put(new float[] {1, 0, 0, 1}).flip());
        int btex = G.glCreateTextures(G.T_BUFFER);
        G.glActiveTexture(G.TEXTURE0 + 150);
        G.glBindTexture(G.T_BUFFER, btex);
        G.glTexBuffer(G.T_BUFFER, 0x8814, buf);
        int bprog = program("#version 330\nlayout(location = 0) in vec2 aPos;\nvoid main() { gl_Position = vec4(aPos, 0.0, 1.0); }\n",
            "#version 330\nuniform samplerBuffer uReused;\nout vec4 fragColor;\nvoid main() { fragColor = texelFetch(uReused, 3); }\n");
        G.glUseProgram(bprog);
        G.glUniform1i(G.glGetUniformLocation(bprog, "uReused"), 150);
        G.glBindVertexArray(vao);
        G.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        boolean first = pixel(255, 0, 0);
        G.glDeleteBuffers(buf);
        int bufAgain = G.glCreateBuffers();
        if (bufAgain != buf) {
            System.out.println("   (GL gave a new buffer name; the reused-buffer case can't be set up here)");
        } else {
            G.glNamedBufferStorage(bufAgain, 16 * 16, 0x100);
            G.glBindBuffer(G.T_BUFFER, bufAgain);
            G.glBufferSubData(G.T_BUFFER, 3L * 16, BufferUtils.createFloatBuffer(4).put(new float[] {0, 0, 1, 1}).flip());
            G.glActiveTexture(G.TEXTURE0 + 150);
            G.glTexBuffer(G.T_BUFFER, 0x8814, bufAgain);
            G.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
            check("buffer texture: a buffer made again under the same name is copied again -> blue", first && pixel(0, 0, 255));
        }
        G.glUseProgram(prog);
    }

    /** Names only the layer knows, asked through glGetIntegerv, leave no GL error behind. */
    static void queryErrorCheck() {
        GL11.glGetError();
        int[] v = new int[4];
        for (int pname : new int[] {0x8919, 0x8F43, 0x919F, 0x8C2B, 0x8C2C}) G.glGetIntegerv(pname, v);
        check("glGetIntegerv of the layer's own names leaves no GL error", GL11.glGetError() == 0);
    }

    /** A range past the buffer's end isn't mapped, and leaves the buffer usable. */
    static void mapRangeCheck() {
        int buf = G.glCreateBuffers();
        G.glNamedBufferStorage(buf, 64, 0x100);
        G.glBindBuffer(G.T_BUFFER, buf);
        ByteBuffer none = G.glMapBufferRange(G.T_BUFFER, 48, 32, 2);
        GL11.glGetError();
        G.glBufferSubData(G.T_BUFFER, 0, BufferUtils.createFloatBuffer(4).put(new float[] {1, 2, 3, 4}).flip());
        check("a range past the end maps nothing and the buffer can still be written", none == null && GL11.glGetError() == 0);
    }

    static boolean pixelAt(int x, int y, int r, int g, int b) {
        ByteBuffer out = BufferUtils.createByteBuffer(4);
        GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
        int pr = out.get(0) & 255, pg = out.get(1) & 255, pb = out.get(2) & 255;
        boolean ok = Math.abs(pr - r) < 3 && Math.abs(pg - g) < 3 && Math.abs(pb - b) < 3;
        if (!ok) System.out.println("   got " + pr + "," + pg + "," + pb + " at " + x + "," + y);
        return ok;
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
        check("program links", org.lwjgl.opengl.GL20.glGetProgrami(p, org.lwjgl.opengl.GL20.GL_LINK_STATUS) == 1);
        return p;
    }

    static boolean pixel(int r, int g, int b) {
        ByteBuffer out = BufferUtils.createByteBuffer(4);
        GL11.glReadPixels(4, 4, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
        int pr = out.get(0) & 255, pg = out.get(1) & 255, pb = out.get(2) & 255;
        boolean ok = Math.abs(pr - r) < 3 && Math.abs(pg - g) < 3 && Math.abs(pb - b) < 3;
        if (!ok) System.out.println("   got " + pr + "," + pg + "," + pb);
        return ok;
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }
}
