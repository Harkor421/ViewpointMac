package vpmac;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * For development: lines written to {@code ~/Zomboid/viewpointmac/dump.txt} ask for a look inside
 * the next frames. PNGs land in {@code dumps/}, stretched to their range, with each channel's range
 * in the log.
 * <ul>
 * <li>{@code uAlbedo} or {@code N:uAlbedo}: the texture a program (N: that program) samples under the name
 * <li>{@code N:bindings}: what each of program N's samplers has bound
 * <li>{@code after:N}: what program N's next draw left in its framebuffer
 * <li>{@code uniforms:N}: the values of program N's uniforms at its next draw
 * <li>{@code set:N:uName=value}: sets a uniform before each of program N's draws ({@code unset:N} stops)
 * <li>{@code screen}: the frame Viewpoint drew
 * <li>{@code errors}: for the next 5000 draws, logs (once each) GL errors and incomplete framebuffers
 * <li>{@code stats:S} / {@code gpu:S} / {@code profile:S}: see {@link Stats} and {@link Prof}
 * <li>{@code clear}: drops every request still waiting
 * </ul>
 * Requests nothing answers within {@link #EXPIRE_MS} are dropped, so a name that never shows up
 * doesn't keep every draw checking for it.
 */
final class Dump {
    private Dump() {}

    private static long nextCheck;
    private static final Set<String> wanted = new HashSet<>();
    private static final Set<Integer> after = new HashSet<>(), uniforms = new HashSet<>();
    private static final Map<Integer, Map<String, String>> overrides = new HashMap<>();
    private static boolean screen;
    private static int checks;
    private static long askedAt;
    static final long EXPIRE_MS = 20_000;
    /** Anything asked for at all: draws skip every check here while this is false. */
    static boolean busy;

    static void poll() {
        long now = System.currentTimeMillis();
        if (now < nextCheck) return;
        nextCheck = now + 500;
        Stats.tick();
        if (busy && now - askedAt > EXPIRE_MS && (!wanted.isEmpty() || !after.isEmpty() || !uniforms.isEmpty() || screen)) {
            Log.info("dump requests never answered, dropped: " + wanted + (after.isEmpty() ? "" : " after " + after)
                + (uniforms.isEmpty() ? "" : " uniforms " + uniforms) + (screen ? " screen" : ""));
            wanted.clear();
            after.clear();
            uniforms.clear();
            screen = false;
        }
        busy = !wanted.isEmpty() || !after.isEmpty() || !uniforms.isEmpty() || !overrides.isEmpty() || screen || checks > 0;
        Path f = Log.dir().resolve("dump.txt");
        if (!Files.isRegularFile(f)) return;
        List<String> lines;
        try {
            lines = Files.readAllLines(f);
            Files.delete(f);
        } catch (Exception e) {
            Log.warn("dump request unreadable: " + e);
            return;
        }
        for (String line : lines) {
            String w = line.trim();
            try {
                if (w.equals("screen")) screen = true;
                else if (w.equals("errors")) checks = 5000;
                else if (w.equals("clear")) {
                    wanted.clear();
                    after.clear();
                    uniforms.clear();
                    overrides.clear();
                    screen = false;
                    checks = 0;
                }
                else if (w.startsWith("stats:")) Stats.startStats(Integer.parseInt(w.substring(6)));
                else if (w.startsWith("gpu:")) Stats.startGpu(Integer.parseInt(w.substring(4)));
                else if (w.equals("revive")) {
                    // Viewpoint stops drawing for good after a crash; let it try again.
                    java.lang.reflect.Field g = Class.forName("me.zed_0xff.zombie_buddy.Loader").getDeclaredField("g_instrumentation");
                    g.setAccessible(true);
                    Class<?> drawer = null;
                    for (Class<?> c : ((java.lang.instrument.Instrumentation) g.get(null)).getAllLoadedClasses()) {
                        if (c.getName().equals("viewpoint.SceneDrawer")) drawer = c;
                    }
                    java.lang.reflect.Field failed = drawer.getDeclaredField("failed");
                    failed.setAccessible(true);
                    failed.setBoolean(null, false);
                    Log.info("Viewpoint may draw again");
                }
                else if (w.startsWith("profile:")) Prof.start(Integer.parseInt(w.substring(8)));
                else if (w.startsWith("after:")) after.add(Integer.parseInt(w.substring(6)));
                else if (w.startsWith("uniforms:")) uniforms.add(Integer.parseInt(w.substring(9)));
                else if (w.startsWith("unset:")) overrides.remove(Integer.parseInt(w.substring(6)));
                else if (w.startsWith("set:")) {
                    String[] parts = w.substring(4).split(":", 2);
                    String[] kv = parts[1].split("=", 2);
                    overrides.computeIfAbsent(Integer.parseInt(parts[0]), k -> new HashMap<>()).put(kv[0].trim(), kv[1].trim());
                } else if (!w.isEmpty()) wanted.add(w);
            } catch (Exception e) {
                Log.warn("dump request line not understood: " + w + " (" + e + ")");
            }
        }
        askedAt = now;
        busy = !wanted.isEmpty() || !after.isEmpty() || !uniforms.isEmpty() || !overrides.isEmpty() || screen || checks > 0;
        if (busy) Log.info("dump asked: " + wanted + (screen ? " + screen" : "") + (after.isEmpty() ? "" : " after " + after)
            + (uniforms.isEmpty() ? "" : " uniforms " + uniforms) + (overrides.isEmpty() ? "" : " set " + overrides));
    }

    /** Before a draw: saves the textures this program samples under a wanted name. */
    static void samplers(Prog.Program p) {
        int id = Prog.currentId;
        Map<String, String> set = overrides.get(id);
        if (set != null) {
            for (Map.Entry<String, String> e : set.entrySet()) {
                int loc = GL20.glGetUniformLocation(id, e.getKey());
                if (loc < 0) continue;
                if (e.getValue().contains(".")) GL20.glUniform1f(loc, Float.parseFloat(e.getValue()));
                else GL20.glUniform1i(loc, Integer.parseInt(e.getValue()));
            }
        }
        if (uniforms.remove(id)) logUniforms(id, p);
        if (wanted.remove("source:" + id)) {
            int[] count = new int[1], shaders = new int[8];
            GL20.glGetAttachedShaders(id, count, shaders);
            StringBuilder where = new StringBuilder();
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            for (int i = 2; i < Math.min(st.length, 9); i++) where.append(" < ").append(st[i].getClassName()).append('.').append(st[i].getMethodName());
            try {
                Path dir = Log.dir().resolve("sources");
                Files.createDirectories(dir);
                for (int i = 0; i < count[0]; i++) {
                    int type = GL20.glGetShaderi(shaders[i], GL20.GL_SHADER_TYPE);
                    Files.writeString(dir.resolve("program-" + id + "-" + shaders[i] + (type == GL20.GL_VERTEX_SHADER ? ".vert" : ".frag")),
                        GL20.glGetShaderSource(shaders[i]));
                }
            } catch (Exception e) {
                Log.warn("sources not saved: " + e);
            }
            Log.info("program " + id + " drawn from" + where + "; " + count[0] + " shaders saved");
        }
        if (wanted.isEmpty()) return;
        String label = p.label.replace("program ", "");
        if (wanted.remove(label + ":bindings")) {
            StringBuilder b = new StringBuilder("bindings of " + p.label + " (draw fbo " + Fbo.drawFbo + "):");
            for (Prog.Sampler s : p.samplers) {
                int u = Math.max(0, Math.min(G.VIRTUAL_UNITS - 1, s.unit));
                b.append(' ').append(s.name).append('@').append(s.unit).append('=').append(G.vTex[u][G.targetIndex(s.virtualTarget)]);
            }
            for (String n : p.packed) {
                Integer u = p.mergedUnit.get(n);
                int t = u == null ? 0 : G.vTex[u][G.targetIndex(G.T_2D)];
                Tex.Info ti = Tex.peek(t);
                b.append(" [atlas] ").append(n).append('@').append(u).append('=').append(t).append(" v").append(ti == null ? "-" : String.valueOf(ti.version));
            }
            Log.info(b.toString());
        }
        for (Prog.Sampler s : p.samplers) {
            int bracket = s.name.endsWith("]") ? s.name.lastIndexOf('[') : -1;
            String base = bracket < 0 ? s.name : s.name.substring(0, bracket);
            if (!wanted.remove(label + ":" + base) && !wanted.remove(s.name) && !wanted.remove(base)) continue;
            int unit = Math.max(0, Math.min(G.VIRTUAL_UNITS - 1, s.unit));
            int texture = G.vTex[unit][G.targetIndex(s.virtualTarget)];
            if (texture == 0 || s.kind == 1 || s.kind == 2 || s.realTarget != G.T_2D) {
                Log.info("dump " + s.name + ": texture " + texture + " is not a float 2D texture; skipped");
                continue;
            }
            texture(s.name + "-" + texture, texture, "before " + p.label);
        }
        // Packed names have no sampler of their own; look them up where the atlas found them.
        for (String n : p.packed) {
            if (!wanted.remove(label + ":" + n) && !wanted.remove(n)) continue;
            Integer u = p.mergedUnit.get(n);
            int texture = u == null ? 0 : G.vTex[u][G.targetIndex(G.T_2D)];
            if (texture != 0) texture(n + "-" + texture, texture, "before " + p.label + ", in the atlas");
        }
    }

    private static void texture(String name, int texture, String note) {
        Tex.with(G.T_2D, texture, () -> {
            int w = GL11.glGetTexLevelParameteri(G.T_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int h = GL11.glGetTexLevelParameteri(G.T_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            int ifmt = GL11.glGetTexLevelParameteri(G.T_2D, 0, 0x1003);
            boolean depth = Formats.isDepth(ifmt);
            FloatBuffer px = MemoryUtil.memAllocFloat(Math.max(1, w * h * (depth ? 1 : 4)));
            try {
                intoMemory(() -> GL11.glGetTexImage(G.T_2D, 0, depth ? GL11.GL_DEPTH_COMPONENT : GL11.GL_RGBA, GL11.GL_FLOAT, px));
                write(name, px, w, h, depth ? 1 : 4, note + ", ifmt 0x" + Integer.toHexString(ifmt));
            } finally {
                MemoryUtil.memFree(px);
            }
        });
    }

    private static void logUniforms(int program, Prog.Program p) {
        StringBuilder b = new StringBuilder("uniforms of " + p.label + ":");
        int n = GL20.glGetProgrami(program, GL20.GL_ACTIVE_UNIFORMS);
        try (MemoryStack st = MemoryStack.stackPush()) {
            IntBuffer size = st.mallocInt(1), type = st.mallocInt(1);
            FloatBuffer f = st.mallocFloat(16);
            IntBuffer iv = st.mallocInt(16);
            for (int i = 0; i < n; i++) {
                String name = GL20.glGetActiveUniform(program, i, 256, size, type);
                int t = type.get(0), loc = GL20.glGetUniformLocation(program, name);
                b.append("\n   ").append(name).append(size.get(0) > 1 ? "[" + size.get(0) + "]" : "").append(" = ");
                int count = switch (t) {
                    case 0x1406, 0x1404, 0x8B56 -> 1;               // float, int, bool
                    case 0x8B50, 0x8B53, 0x8B57 -> 2;
                    case 0x8B51, 0x8B54, 0x8B58 -> 3;
                    case 0x8B52, 0x8B55, 0x8B59, 0x8B5A -> 4;       // vec4, ivec4, bvec4, mat2
                    case 0x8B5B -> 9;
                    case 0x8B5C -> 16;
                    default -> 1;                                   // samplers and the rest: an int
                };
                boolean floats = t == 0x1406 || (t >= 0x8B50 && t <= 0x8B52) || (t >= 0x8B5A && t <= 0x8B5C);
                if (loc < 0) {
                    b.append("(no location)");
                } else if (floats) {
                    f.clear();
                    GL20.glGetUniformfv(program, loc, f);
                    for (int k = 0; k < count; k++) b.append(k == 0 ? "" : ", ").append(f.get(k));
                } else {
                    iv.clear();
                    GL20.glGetUniformiv(program, loc, iv);
                    for (int k = 0; k < count; k++) b.append(k == 0 ? "" : ", ").append(iv.get(k));
                }
            }
        }
        Log.info(b.toString());
    }

    /** Right before a draw, while "errors" lasts: what was already wrong, and whether the target is drawable. */
    static void checkBefore() {
        if (checks <= 0) return;
        if (wanted.remove("state:" + Prog.currentId)) {
            java.nio.ByteBuffer mask = MemoryUtil.memAlloc(16);
            GL11.glGetBooleanv(0x0C23, mask); // COLOR_WRITEMASK
            StringBuilder b = new StringBuilder("state at program " + Prog.currentId + "'s draw: color mask "
                + mask.get(0) + mask.get(1) + mask.get(2) + mask.get(3));
            MemoryUtil.memFree(mask);
            int[] caps = {GL11.GL_BLEND, GL11.GL_DEPTH_TEST, GL11.GL_STENCIL_TEST, GL11.GL_SCISSOR_TEST, GL11.GL_CULL_FACE,
                GL11.GL_ALPHA_TEST, 0x8C89, 0x809D, 0x8642, 0x8DB9, GL11.GL_POLYGON_OFFSET_FILL, 0x809E, GL11.GL_COLOR_LOGIC_OP};
            String[] names = {"blend", "depth", "stencil", "scissor", "cull", "alpha", "rasterizerDiscard", "multisample",
                "pointSize", "framebufferSRGB", "polygonOffset", "alphaToCoverage", "logicOp"};
            for (int i = 0; i < caps.length; i++) {
                if (GL11.glIsEnabled(caps[i])) b.append(' ').append(names[i]);
            }
            for (int i = 0; i < 4; i++) {
                if (org.lwjgl.opengl.EXTDrawBuffers2.glIsEnabledIndexedEXT(GL11.GL_BLEND, i)) b.append(" blend").append(i);
            }
            b.append("; blend src 0x").append(Integer.toHexString(GL11.glGetInteger(0x80C9))).append(" dst 0x")
                .append(Integer.toHexString(GL11.glGetInteger(0x80C8))).append(" srcA 0x").append(Integer.toHexString(GL11.glGetInteger(0x80CB)))
                .append(" dstA 0x").append(Integer.toHexString(GL11.glGetInteger(0x80CA))).append(" eq 0x")
                .append(Integer.toHexString(GL11.glGetInteger(0x8009)));
            b.append("; depth func 0x").append(Integer.toHexString(GL11.glGetInteger(GL11.GL_DEPTH_FUNC)));
            int[] sc = new int[4];
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, sc);
            b.append("; scissor ").append(sc[0]).append(',').append(sc[1]).append(' ').append(sc[2]).append('x').append(sc[3]);
            b.append("; polygon mode 0x").append(Integer.toHexString(GL11.glGetInteger(GL11.GL_POLYGON_MODE)));
            b.append("; program bound ").append(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
            b.append("; read fbo ").append(GL11.glGetInteger(G.READ_FRAMEBUFFER_BINDING)).append(" draw fbo ")
                .append(GL11.glGetInteger(G.DRAW_FRAMEBUFFER_BINDING));
            int fb = GL11.glGetInteger(G.DRAW_FRAMEBUFFER_BINDING);
            if (fb != 0) {
                b.append("; color0 object ").append(GL30.glGetFramebufferAttachmentParameteri(G.DRAW_FRAMEBUFFER, G.COLOR_ATTACHMENT0, 0x8CD1))
                    .append(" level ").append(GL30.glGetFramebufferAttachmentParameteri(G.DRAW_FRAMEBUFFER, G.COLOR_ATTACHMENT0, 0x8CD2));
            }
            Log.info(b.toString());
        }
        int e = GL11.glGetError();
        if (e != 0) Log.once("errBefore:" + Prog.currentId + ":" + e, "GL error 0x" + Integer.toHexString(e)
            + " pending before program " + Prog.currentId + " draws");
        int status = GL30.glCheckFramebufferStatus(G.DRAW_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            Map<Integer, Integer> a = Fbo.attachments(Fbo.drawFbo);
            Log.once("fboStatus:" + Prog.currentId + ":" + Fbo.drawFbo + ":" + status, "program " + Prog.currentId
                + " draws into framebuffer " + Fbo.drawFbo + ", incomplete (0x" + Integer.toHexString(status) + "), attachments " + a
                + ", draw buffer 0 0x" + Integer.toHexString(GL11.glGetInteger(Fbo.DRAW_BUFFER0)));
        }
    }

    static void checkAfter() {
        if (checks <= 0) return;
        checks--;
        int[] vp = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
        Log.once("drawsInto:" + Prog.currentId + ":" + Fbo.drawFbo, "program " + Prog.currentId + " draws into framebuffer "
            + Fbo.drawFbo + " " + Fbo.attachments(Fbo.drawFbo) + ", draw buffers 0x" + Integer.toHexString(GL11.glGetInteger(Fbo.DRAW_BUFFER0))
            + "/0x" + Integer.toHexString(GL11.glGetInteger(Fbo.DRAW_BUFFER0 + 1)) + ", viewport " + vp[0] + "," + vp[1] + " " + vp[2] + "x" + vp[3]);
        int e = GL11.glGetError();
        if (e != 0) Log.once("errDraw:" + Prog.currentId + ":" + e, "program " + Prog.currentId + "'s draw raised GL error 0x"
            + Integer.toHexString(e) + " (framebuffer " + Fbo.drawFbo + ")");
        if (checks == 0) Log.info("error checks done");
    }

    /** After a draw: if it went to the window, or was program after:N's, saves what is there. */
    static void afterDraw() {
        if (after.remove(Prog.currentId)) {
            Map<Integer, Integer> a = Fbo.attachments(Fbo.drawFbo);
            if (Fbo.drawFbo == 0 || a == null) {
                // Not one of Viewpoint's (the game's own, or the window): read its first color buffer.
                int[] vp = new int[4];
                GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
                int w = Math.max(1, vp[2]), h = Math.max(1, vp[3]);
                int prevRead = GL11.glGetInteger(G.READ_FRAMEBUFFER_BINDING);
                GL30.glBindFramebuffer(G.READ_FRAMEBUFFER, Fbo.drawFbo);
                FloatBuffer px = MemoryUtil.memAllocFloat(w * h * 4);
                int prevBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
                try {
                    GL11.glReadBuffer(Fbo.drawFbo == 0 ? GL11.GL_BACK : G.COLOR_ATTACHMENT0);
                    intoMemory(() -> GL11.glReadPixels(vp[0], vp[1], w, h, GL11.GL_RGBA, GL11.GL_FLOAT, px));
                    GL11.glReadBuffer(prevBuffer);
                    write("after-" + Prog.currentId + "-fbo" + Fbo.drawFbo, px, w, h, 4, "after program " + Prog.currentId
                        + " into framebuffer " + Fbo.drawFbo);
                } finally {
                    MemoryUtil.memFree(px);
                    GL30.glBindFramebuffer(G.READ_FRAMEBUFFER, prevRead);
                }
            } else {
                for (Map.Entry<Integer, Integer> e : a.entrySet()) {
                    int point = e.getKey();
                    if (point < G.COLOR_ATTACHMENT0 || point > G.COLOR_ATTACHMENT0 + 7) continue;
                    Tex.Info ti = Tex.peek(e.getValue());
                    if (ti != null && ti.target != G.T_2D) {
                        Log.info("after program " + Prog.currentId + ": attachment " + (point - G.COLOR_ATTACHMENT0)
                            + " is texture " + e.getValue() + ", target 0x" + Integer.toHexString(ti.target) + "; skipped");
                        continue;
                    }
                    texture("after-" + Prog.currentId + "-color" + (point - G.COLOR_ATTACHMENT0) + "-" + e.getValue(), e.getValue(),
                        "after program " + Prog.currentId + " into framebuffer " + Fbo.drawFbo);
                }
            }
        }
        if (!screen || Fbo.drawFbo != 0) return;
        screen = false;
        int[] vp = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
        int w = Math.max(1, vp[2]), h = Math.max(1, vp[3]);
        int prevRead = GL11.glGetInteger(G.READ_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(G.READ_FRAMEBUFFER, 0);
        FloatBuffer px = MemoryUtil.memAllocFloat(w * h * 4);
        int prevBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        try {
            GL11.glReadBuffer(GL11.GL_BACK);
            intoMemory(() -> GL11.glReadPixels(vp[0], vp[1], w, h, GL11.GL_RGBA, GL11.GL_FLOAT, px));
            GL11.glReadBuffer(prevBuffer);
            write("screen", px, w, h, 4, "program " + Prog.currentId);
        } finally {
            MemoryUtil.memFree(px);
            GL30.glBindFramebuffer(G.READ_FRAMEBUFFER, prevRead);
        }
    }

    /** A read into client memory: no pack buffer, tight rows, and the game's pack state put back after. */
    private static void intoMemory(Runnable read) {
        int pack = GL11.glGetInteger(0x88ED); // PIXEL_PACK_BUFFER_BINDING
        GL11.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT);
        if (pack != 0) org.lwjgl.opengl.GL15.glBindBuffer(G.PIXEL_PACK_BUFFER, 0);
        try {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            read.run();
        } finally {
            GL11.glPopClientAttrib();
            if (pack != 0) org.lwjgl.opengl.GL15.glBindBuffer(G.PIXEL_PACK_BUFFER, pack);
        }
    }

    private static void write(String name, FloatBuffer px, int w, int h, int channels, String note) {
        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        int nans = 0;
        for (int i = 0; i < w * h; i++) {
            for (int c = 0; c < channels; c++) {
                float v = px.get(i * channels + c);
                if (Float.isNaN(v) || Float.isInfinite(v)) { nans++; continue; }
                lo[c] = Math.min(lo[c], v);
                hi[c] = Math.max(hi[c], v);
            }
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * channels;
                int rgb = 0;
                for (int c = 0; c < 3; c++) {
                    int ch = channels == 1 ? 0 : c;
                    float v = px.get(i + ch);
                    float span = hi[ch] - lo[ch];
                    int b = span > 0 && !Float.isNaN(v) ? (int) Math.max(0, Math.min(255, (v - lo[ch]) / span * 255)) : 0;
                    rgb = rgb << 8 | b;
                }
                img.setRGB(x, h - 1 - y, rgb);
            }
        }
        try {
            Path dir = Log.dir().resolve("dumps");
            Files.createDirectories(dir);
            javax.imageio.ImageIO.write(img, "png", dir.resolve(name + ".png").toFile());
        } catch (Exception e) {
            Log.warn("dump " + name + " not written: " + e);
        }
        StringBuilder r = new StringBuilder();
        for (int c = 0; c < channels; c++) r.append(" ").append("rgba".charAt(c)).append(' ').append(lo[c]).append("..").append(hi[c]);
        Log.info("dump " + name + " " + w + "x" + h + " (" + note + "):" + r + (nans > 0 ? " NaN/inf " + nans : ""));
    }

    static List<String> pending() {
        return List.copyOf(wanted);
    }
}
