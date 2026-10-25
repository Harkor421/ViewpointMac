package vpmac;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The texture that holds, side by side, the sampler2D textures a program had to give up to fit
 * the Mac's 16 units (see {@link Glsl.Result#packed}). Each texture is copied into its region
 * whenever it has changed since the last copy — on the GPU, with a tiny program — and the shader
 * gets the region and how to sample it through two uniforms.
 *
 * <p>Memory is what limits this: an atlas is 8 or 16 bytes a texel whatever its members were. So
 * the relief and the tree atlas, thousands of texels a side, are stored smaller (the shader still
 * sees their real size), and all atlases together stay under {@link #BUDGET}, the least recently
 * used going first.
 */
final class Atlas {
    private Atlas() {}

    /** The virtual unit the atlas sits on; next to {@link Merged#UNIT}, past all of Viewpoint's. */
    static final int UNIT = Merged.UNIT - 1;
    static final int RGBA32F = 0x8814, RGBA16F = 0x881A;
    static final int MAX_SIZE = 16384;
    /** Longest side the textures {@link Glsl#cost} calls huge keep in an atlas. */
    static final int HUGE_SIDE = 2048;
    /** Longest side any other texture keeps; 4K screen buffers fit. */
    static final int MAX_SIDE = 4096;
    /** What all atlases together may take. */
    static final long BUDGET = 768L << 20;

    private static final class Sheet {
        int texture, fbo, width, height, format;
        long bytes;
        String key;
        /** Evicted: programs that remember it must look again. */
        boolean dead;
        final Map<Integer, int[]> rects = new HashMap<>();    // texture -> {x, y, w, h} as stored
        final Map<Integer, int[]> sources = new HashMap<>();  // texture -> its own {w, h}
        final Map<Integer, Long> copied = new HashMap<>();    // texture -> its record's serial and the version copied
    }

    /** By member textures and sizes, least recently used first. */
    private static final Map<String, Sheet> sheets = new LinkedHashMap<>(16, 0.75f, true);
    private static long total;
    private static int blitProgram, blitSource, blitOrigin, blitScale;

    static void sync(Prog.Program p) {
        List<String> names = p.packed;
        int n = names.size();
        if (p.atlasTextures == null || p.atlasTextures.length != n) {
            p.atlasTextures = new int[n];
            p.atlasSizes = new int[n * 2];
            p.atlasSent = new float[n][];
            p.atlasSheet = null;
        }
        // The same members at the same sizes as at the last draw: the same sheet, found without a key.
        int[] textures = p.atlasTextures;
        boolean same = p.atlasSheet != null && !((Sheet) p.atlasSheet).dead;
        for (int i = 0; i < n; i++) {
            Integer unit = p.mergedUnit.get(names.get(i));
            int t = unit == null ? 0 : G.vTex[unit][G.targetIndex(G.T_2D)];
            Tex.Info ti = t == 0 ? null : Tex.peek(t);
            int w = ti == null ? 1 : ti.width, h = ti == null ? 1 : ti.height;
            if (t != textures[i] || w != p.atlasSizes[2 * i] || h != p.atlasSizes[2 * i + 1]) same = false;
            textures[i] = t;
        }
        Sheet sheet;
        if (same) {
            sheet = (Sheet) p.atlasSheet;
            sheets.get(sheet.key); // still in use: last to be evicted
        } else {
            int[][] sizes = new int[n][];
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < n; i++) {
                sizes[i] = size(textures[i]);
                p.atlasSizes[2 * i] = sizes[i][0];
                p.atlasSizes[2 * i + 1] = sizes[i][1];
                key.append(textures[i]).append(':').append(sizes[i][0]).append('x').append(sizes[i][1]).append('|');
            }
            sheet = sheets.get(key.toString());
            if (sheet == null) {
                sheet = layout(p, names, textures, sizes);
                if (sheet == null) {
                    p.atlasSheet = null;
                    G.vTex[UNIT][G.targetIndex(G.T_2D)] = 0;
                    return;
                }
                sheet.key = key.toString();
                evict(sheet.bytes);
                allocate(sheet);
                sheets.put(sheet.key, sheet);
                total += sheet.bytes;
            }
            p.atlasSheet = sheet;
            java.util.Arrays.fill(p.atlasSent, null);
        }

        for (int i = 0; i < n; i++) {
            int t = textures[i];
            if (t == 0) continue;
            Tex.Info ti = Tex.peek(t);
            // Serial too: a texture deleted and made again under the same name restarts its versions.
            long state = ti == null || !ti.known ? -2 : ((long) ti.serial << 32) | (ti.version & 0xffffffffL);
            Long done = sheet.copied.get(t);
            if (state != -2 && done != null && done == state) continue;
            Timers.begin(Timers.ATLAS);
            copy(sheet, t);
            sheet.copied.put(t, state);
        }

        Timers.end(Timers.ATLAS);
        G.vTex[UNIT][G.targetIndex(G.T_2D)] = sheet.texture;
        G.vSampler[UNIT] = 0;
        for (int i = 0; i < n; i++) {
            String name = names.get(i);
            int[] r = sheet.rects.get(textures[i]);
            if (r == null) r = NO_RECT;
            int[] src = sheet.sources.get(textures[i]);
            boolean smaller = src != null && (src[0] != r[2] || src[1] != r[3]);
            Integer unit = p.mergedUnit.get(name);
            float repeat = repeats(textures[i], unit) ? 1 : 0, nearest = nearest(textures[i], unit) ? 1 : 0;
            float sw = smaller ? src[0] : 0, sh = smaller ? src[1] : 0;
            float[] sent = p.atlasSent[i];
            if (sent != null && sent[0] == r[0] && sent[1] == r[1] && sent[2] == r[2] && sent[3] == r[3]
                && sent[4] == repeat && sent[5] == nearest && sent[6] == sw && sent[7] == sh) continue;
            Integer rl = p.rectLocation.get(name), ml = p.modeLocation.get(name);
            if (rl != null) GL20.glUniform4f(rl, r[0], r[1], r[2], r[3]);
            if (ml != null) GL20.glUniform4f(ml, repeat, nearest, sw, sh);
            p.atlasSent[i] = new float[] {r[0], r[1], r[2], r[3], repeat, nearest, sw, sh};
            Stats.uniforms++;
        }
    }

    private static final int[] NO_RECT = {0, 0, 1, 1};

    /** Width and height of level 0, asking GL for textures this file never saw allocated. */
    private static int[] size(int texture) {
        if (texture == 0) return new int[] {1, 1};
        Tex.Info ti = Tex.info(texture);
        if (ti.width <= 0 || ti.height <= 0 || ti.ifmt == 0) {
            int[] wh = new int[3];
            Tex.with(G.T_2D, texture, () -> {
                wh[0] = GL11.glGetTexLevelParameteri(G.T_2D, 0, GL11.GL_TEXTURE_WIDTH);
                wh[1] = GL11.glGetTexLevelParameteri(G.T_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                wh[2] = GL11.glGetTexLevelParameteri(G.T_2D, 0, 0x1003); // TEXTURE_INTERNAL_FORMAT
            });
            if (ti.width <= 0 || ti.height <= 0) {
                ti.width = Math.max(1, wh[0]);
                ti.height = Math.max(1, wh[1]);
            }
            if (ti.ifmt == 0) ti.ifmt = wh[2];
        }
        return new int[] {ti.width, ti.height};
    }

    /** Formats a half float would round: 32-bit floats, depth, and 16-bit normalized ones. */
    private static boolean needsFull(int f) {
        return f == 0x822E || f == 0x8230 || f == 0x8814 || f == 0x8815 || Formats.isDepth(f)
            || f == 0x822A || f == 0x822C || f == 0x805B || f == 0x8054;
    }

    /** Shelf packing, tallest first, each texture at the size it is stored. */
    private static Sheet layout(Prog.Program p, List<String> names, int[] textures, int[][] sizes) {
        Sheet sheet = new Sheet();
        List<Integer> order = new ArrayList<>();
        int[][] stored = new int[textures.length][];
        int widest = 1;
        boolean full = false;
        for (int i = 0; i < textures.length; i++) {
            if (textures[i] == 0 || sheet.sources.containsKey(textures[i])) continue;
            int w = sizes[i][0], h = sizes[i][1];
            int side = Glsl.cost(names.get(i)) >= 4 ? HUGE_SIDE : MAX_SIDE;
            double scale = Math.min(1.0, (double) side / Math.max(w, h));
            stored[i] = new int[] {Math.max(1, (int) Math.round(w * scale)), Math.max(1, (int) Math.round(h * scale))};
            sheet.sources.put(textures[i], new int[] {w, h});
            order.add(i);
            widest = Math.max(widest, stored[i][0]);
            Tex.Info ti = Tex.peek(textures[i]);
            if (ti != null) full |= needsFull(ti.ifmt);
        }
        order.sort((a, b) -> stored[b][1] - stored[a][1]);
        int width = Math.min(MAX_SIZE, Math.max(widest, 2048));
        int x = 0, y = 0, shelf = 0;
        for (int i : order) {
            int w = stored[i][0], h = stored[i][1];
            if (x + w > width) {
                x = 0;
                y += shelf;
                shelf = 0;
            }
            sheet.rects.put(textures[i], new int[] {x, y, w, h});
            x += w;
            shelf = Math.max(shelf, h);
        }
        int height = Math.max(1, y + shelf);
        if (height > MAX_SIZE) {
            Log.once("atlasTooBig:" + p.label, "the textures " + p.label + " shares don't fit one atlas ("
                + width + "x" + height + "); it draws without them");
            return null;
        }
        sheet.width = width;
        sheet.height = height;
        sheet.format = full ? RGBA32F : RGBA16F;
        sheet.bytes = (long) width * height * (full ? 16 : 8);

        StringBuilder members = new StringBuilder();
        for (int i : order) {
            int[] s = sheet.sources.get(textures[i]);
            members.append(' ').append(names.get(i)).append(' ').append(s[0]).append('x').append(s[1]);
            if (stored[i][0] != s[0]) members.append("->").append(stored[i][0]).append('x').append(stored[i][1]);
        }
        Log.info("atlas " + width + "x" + height + (full ? " (32-bit, " : " (16-bit, ") + (sheet.bytes >> 20) + " MB) for "
            + p.label + ":" + members);
        return sheet;
    }

    /** Frees the least recently used atlases until one of {@code incoming} bytes fits the budget. */
    private static void evict(long incoming) {
        Iterator<Sheet> it = sheets.values().iterator();
        while (total + incoming > BUDGET && it.hasNext()) {
            Sheet old = it.next();
            old.dead = true;
            GL11.glDeleteTextures(old.texture);
            GL30.glDeleteFramebuffers(old.fbo);
            total -= old.bytes;
            it.remove();
        }
        if (total + incoming > BUDGET) Log.once("atlasOverBudget", "one atlas alone is over the budget (" + (incoming >> 20) + " MB)");
    }

    private static void allocate(Sheet sheet) {
        sheet.texture = GL11.glGenTextures();
        Tex.with(G.T_2D, sheet.texture, () -> {
            Tex.unpacked(() -> GL11.glTexImage2D(G.T_2D, 0, sheet.format, sheet.width, sheet.height, 0, GL11.GL_RGBA,
                GL11.GL_FLOAT, (ByteBuffer) null), true);
            GL11.glTexParameteri(G.T_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(G.T_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(G.T_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(G.T_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(G.T_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        });
        int prevDraw = GL11.glGetInteger(G.DRAW_FRAMEBUFFER_BINDING), prevRead = GL11.glGetInteger(G.READ_FRAMEBUFFER_BINDING);
        sheet.fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(G.FRAMEBUFFER, sheet.fbo);
        GL30.glFramebufferTexture2D(G.FRAMEBUFFER, G.COLOR_ATTACHMENT0, G.T_2D, sheet.texture, 0);
        int status = GL30.glCheckFramebufferStatus(G.FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            Log.warn("atlas framebuffer incomplete (0x" + Integer.toHexString(status) + ", error " + GL11.glGetError() + ")");
        }
        GL30.glBindFramebuffer(G.DRAW_FRAMEBUFFER, prevDraw);
        GL30.glBindFramebuffer(G.READ_FRAMEBUFFER, prevRead);
        Log.info("atlases now take " + ((total + sheet.bytes) >> 20) + " MB in " + (sheets.size() + 1));
    }

    private static final Integer WRAP_S = GL11.GL_TEXTURE_WRAP_S, MAG_FILTER = GL11.GL_TEXTURE_MAG_FILTER;

    /** A parameter as the texture is sampled: its sampler object's, else the texture's own. */
    private static Number param(int texture, Integer unit, Integer pname) {
        Number v = Smp.params(unit == null ? 0 : G.vSampler[unit]).get(pname);
        if (v != null) return v;
        Tex.Info ti = Tex.peek(texture);
        return ti == null ? null : ti.own.get(pname);
    }

    private static boolean repeats(int texture, Integer unit) {
        Number wrap = param(texture, unit, WRAP_S);
        return wrap == null || wrap.intValue() == GL11.GL_REPEAT || wrap.intValue() == 0x8370; // MIRRORED_REPEAT
    }

    private static boolean nearest(int texture, Integer unit) {
        Number mag = param(texture, unit, MAG_FILTER);
        return mag != null && mag.intValue() == GL11.GL_NEAREST;
    }

    // ---- the copy: a full-region quad that fetches texel for texel -------------------------------

    private static void copy(Sheet sheet, int texture) {
        int[] r = sheet.rects.get(texture), src = sheet.sources.get(texture);
        if (r == null || src == null) return;
        Stats.atlasCopies++;
        Stats.atlasTexels += (long) r[2] * r[3];
        if (blitProgram == 0) makeBlit();
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevDraw = GL11.glGetInteger(G.DRAW_FRAMEBUFFER_BINDING), prevRead = GL11.glGetInteger(G.READ_FRAMEBUFFER_BINDING);
        int active = GL11.glGetInteger(G.ACTIVE_TEXTURE);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL13.glActiveTexture(G.TEXTURE0);
        int prevTexture = GL11.glGetInteger(G.BINDINGS[G.targetIndex(G.T_2D)]);
        try {
            GL30.glBindFramebuffer(G.FRAMEBUFFER, sheet.fbo);
            GL11.glDrawBuffer(G.COLOR_ATTACHMENT0);
            GL11.glViewport(r[0], r[1], r[2], r[3]);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(false);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            GL20.glUseProgram(blitProgram);
            GL20.glUniform1i(blitSource, 0);
            GL20.glUniform2i(blitOrigin, r[0], r[1]);
            GL20.glUniform2f(blitScale, (float) src[0] / r[2], (float) src[1] / r[3]);
            GL11.glBindTexture(G.T_2D, texture);
            int compare = GL11.glGetTexParameteri(G.T_2D, 0x884C);
            if (compare != 0) GL11.glTexParameteri(G.T_2D, 0x884C, 0);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(-1, -1);
            GL11.glVertex2f(1, -1);
            GL11.glVertex2f(1, 1);
            GL11.glVertex2f(-1, 1);
            GL11.glEnd();
            if (compare != 0) GL11.glTexParameteri(G.T_2D, 0x884C, compare);
        } finally {
            GL11.glBindTexture(G.T_2D, prevTexture);
            GL11.glPopAttrib();
            GL13.glActiveTexture(active);
            GL20.glUseProgram(prevProgram);
            GL30.glBindFramebuffer(G.DRAW_FRAMEBUFFER, prevDraw);
            GL30.glBindFramebuffer(G.READ_FRAMEBUFFER, prevRead);
        }
    }

    /** Texel for texel; a texture stored smaller takes every scale-th texel. */
    private static void makeBlit() {
        String vs = "#version 120\nvoid main() { gl_Position = gl_Vertex; }\n";
        String fs = "#version 120\n#extension GL_EXT_gpu_shader4 : require\n"
            + "uniform sampler2D src;\nuniform ivec2 origin;\nuniform vec2 scale;\n"
            + "void main() { gl_FragColor = texelFetch2D(src, ivec2((gl_FragCoord.xy - vec2(origin)) * scale), 0); }\n";
        int v = GL20.glCreateShader(GL20.GL_VERTEX_SHADER), f = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(v, vs);
        GL20.glCompileShader(v);
        GL20.glShaderSource(f, fs);
        GL20.glCompileShader(f);
        blitProgram = GL20.glCreateProgram();
        GL20.glAttachShader(blitProgram, v);
        GL20.glAttachShader(blitProgram, f);
        GL20.glLinkProgram(blitProgram);
        if (GL20.glGetProgrami(blitProgram, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
            Log.warn("the atlas copy program did not link: " + GL20.glGetProgramInfoLog(blitProgram));
        }
        blitSource = GL20.glGetUniformLocation(blitProgram, "src");
        blitOrigin = GL20.glGetUniformLocation(blitProgram, "origin");
        blitScale = GL20.glGetUniformLocation(blitProgram, "scale");
    }
}
