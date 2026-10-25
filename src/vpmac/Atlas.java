package vpmac;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the sampler2D textures a program had to give up to fit the Mac's 16 units live (see
 * {@link Glsl.Result#packed}): two sheets shared by every program, one of half floats and one of
 * full floats. Each texture has one place, in the sheet its format needs, and is copied there
 * once each time its contents change, whichever programs read it; the shader gets the place,
 * the sheet and how to sample it through two uniforms ({@link Glsl#RECT_PREFIX},
 * {@link Glsl#MODE_PREFIX}).
 *
 * <p>Sheets are {@link #WIDTH} wide and grow downwards as textures arrive. The relief and the tree
 * atlas, thousands of texels a side, are stored at most {@link #HUGE_SIDE} a side (the shader still
 * sees their real size). Both sheets together stay under {@link #BUDGET}: a sheet that is full
 * starts again with the textures read in the last few seconds, and copies them afresh.
 */
final class Atlas {
    private Atlas() {}

    /** The virtual units the sheets sit on: past all of Viewpoint's, next to {@link Merged#UNIT}. */
    static final int UNIT = Merged.UNIT - 1, UNIT_FULL = Merged.UNIT - 2;
    static final int RGBA32F = 0x8814, RGBA16F = 0x881A;
    static final int MAX_SIZE = 16384;
    static final int WIDTH = 4096;
    /** Longest side the textures {@link Glsl#cost} calls huge keep in a sheet. */
    static final int HUGE_SIDE = 2048;
    /** Longest side any other texture keeps; 4K screen buffers fit. */
    static final int MAX_SIDE = WIDTH;
    /** What both sheets together may take. */
    static final long BUDGET = 768L << 20;
    /** A texture not read for this long loses its place when its sheet is packed again. */
    static final long RECENT_NS = 5_000_000_000L;

    private static final class Sheet {
        final boolean full;
        int texture, fbo, height;
        /** The shelf being filled: its top, its height, and how far along it is. */
        int shelfY, shelfHeight, shelfX;
        /** Bumped each time the sheet is packed again: places of an older generation are gone. */
        int generation;

        Sheet(boolean full) {
            this.full = full;
        }

        long bytes() {
            return (long) WIDTH * height * (full ? 16 : 8);
        }
    }

    /** One texture's place: where in which sheet, how big as stored and as itself, and what was copied there. */
    private static final class Home {
        Sheet sheet;
        int generation, x, y, w, h, sourceW, sourceH, serial;
        String name;
        long copied = -3, lastRead;
    }

    private static final Sheet half = new Sheet(false), full = new Sheet(true);
    /** By {@link #key}: which texture, and which sheet it lives in (one or the other, never both). */
    private static final Map<Long, Home> homes = new HashMap<>();

    private static long key(int texture, boolean full) {
        return ((long) texture << 1) | (full ? 1 : 0);
    }
    private static int blitProgram, blitSource, blitOrigin, blitScale;

    static void sync(Prog.Program p) {
        List<String> names = p.packed;
        int n = names.size();
        if (p.atlasTextures == null || p.atlasTextures.length != n) {
            p.atlasTextures = new int[n];
            p.atlasSent = new float[n][];
        }
        long now = System.nanoTime();
        Home[] placed = new Home[n];
        for (int i = 0; i < n; i++) {
            Integer unit = p.mergedUnit.get(names.get(i));
            int t = unit == null ? 0 : G.vTex[unit][G.targetIndex(G.T_2D)];
            p.atlasTextures[i] = t;
            if (t == 0) continue;
            Home h = home(t, names.get(i), p.atlasFullOnly);
            if (h == null) continue;
            h.lastRead = now;
            placed[i] = h;
        }
        // Copies after every place is settled: packing a sheet again moves places.
        for (int i = 0; i < n; i++) {
            Home h = placed[i];
            if (h == null || h.generation != h.sheet.generation) {
                placed[i] = h == null ? null : homes.get(key(p.atlasTextures[i], h.sheet.full));
                h = placed[i];
            }
            if (h == null) continue;
            Tex.Info ti = Tex.peek(p.atlasTextures[i]);
            // Serial too: a texture deleted and made again under the same name restarts its versions.
            long state = ti == null || !ti.known ? -2 : ((long) ti.serial << 32) | (ti.version & 0xffffffffL);
            if (state != -2 && h.copied == state) continue;
            Timers.begin(Timers.ATLAS);
            copy(h, p.atlasTextures[i]);
            h.copied = state;
        }
        Timers.end(Timers.ATLAS);

        G.vTex[UNIT][G.targetIndex(G.T_2D)] = half.texture;
        G.vTex[UNIT_FULL][G.targetIndex(G.T_2D)] = full.texture;
        G.vSampler[UNIT] = G.vSampler[UNIT_FULL] = 0;
        for (int i = 0; i < n; i++) {
            String name = names.get(i);
            Home h = placed[i];
            float x = h == null ? 0 : h.x, y = h == null ? 0 : h.y, w = h == null ? 1 : h.w, hh = h == null ? 1 : h.h;
            boolean smaller = h != null && (h.sourceW != h.w || h.sourceH != h.h);
            Integer unit = p.mergedUnit.get(name);
            // mode.x: 1 when the texture repeats, plus 2 when it lives in the full-float sheet.
            float mode = (repeats(p.atlasTextures[i], unit) ? 1 : 0) + (h != null && h.sheet.full ? 2 : 0);
            float nearest = nearest(p.atlasTextures[i], unit) ? 1 : 0;
            float sw = smaller ? h.sourceW : 0, sh = smaller ? h.sourceH : 0;
            float[] sent = p.atlasSent[i];
            if (sent != null && sent[0] == x && sent[1] == y && sent[2] == w && sent[3] == hh
                && sent[4] == mode && sent[5] == nearest && sent[6] == sw && sent[7] == sh) continue;
            Integer rl = p.rectLocation.get(name), ml = p.modeLocation.get(name);
            if (rl != null) GL20.glUniform4f(rl, x, y, w, hh);
            if (ml != null) GL20.glUniform4f(ml, mode, nearest, sw, sh);
            p.atlasSent[i] = new float[] {x, y, w, hh, mode, nearest, sw, sh};
            Stats.uniforms++;
        }
    }

    /**
     * Where {@code texture} lives, given a place if it has none (or its old one no longer fits it):
     * in the sheet its format needs, or the full-float one when {@code fullOnly}. A texture lives
     * in one sheet only, so it is copied once a change whichever programs read it: one that a
     * program reading only the full-float sheet needs moves there for good, and every program
     * reads it from there.
     */
    private static Home home(int texture, String name, boolean fullOnly) {
        int[] size = size(texture);
        Tex.Info ti = Tex.peek(texture);
        Home inFull = homes.get(key(texture, true));
        boolean needsFull = fullOnly || ti != null && needsFull(ti.ifmt) || inFull != null && inFull.generation == inFull.sheet.generation;
        if (needsFull) homes.remove(key(texture, false));
        long key = key(texture, needsFull);
        Home h = homes.get(key);
        if (h != null && h.generation == h.sheet.generation && h.sourceW == size[0] && h.sourceH == size[1]
            && (ti == null || h.serial == ti.serial)) {
            return h;
        }
        int side = Glsl.cost(name) >= 4 ? HUGE_SIDE : MAX_SIDE;
        double scale = Math.min(1.0, (double) side / Math.max(size[0], size[1]));
        int w = Math.max(1, (int) Math.round(size[0] * scale)), hh = Math.max(1, (int) Math.round(size[1] * scale));
        Sheet sheet = needsFull ? full : half;
        h = new Home();
        h.sheet = sheet;
        h.name = name;
        h.w = w;
        h.h = hh;
        h.sourceW = size[0];
        h.sourceH = size[1];
        h.serial = ti == null ? 0 : ti.serial;
        h.lastRead = System.nanoTime();
        homes.put(key, h);
        if (!place(sheet, h)) repack(sheet);
        Home placed = homes.get(key);
        if (placed == null || placed.generation != sheet.generation) {
            homes.remove(key);
            Log.once("atlasFull:" + texture, "texture " + texture + " (" + name + ", " + size[0] + "x" + size[1]
                + ") doesn't fit the " + (sheet.full ? "full" : "half") + "-float atlas sheet; it reads as black");
            return null;
        }
        if (w != size[0]) Log.info("atlas: " + name + " " + size[0] + "x" + size[1] + " stored at " + w + "x" + hh);
        return placed;
    }

    /** Puts {@code h} on the sheet's current shelf or a new one, growing the sheet if the budget allows. */
    private static boolean place(Sheet s, Home h) {
        if (h.w > WIDTH) return false;
        if (s.shelfX + h.w > WIDTH) {
            s.shelfY += s.shelfHeight;
            s.shelfX = 0;
            s.shelfHeight = 0;
        }
        int bottom = s.shelfY + Math.max(s.shelfHeight, h.h);
        if (bottom > s.height && !grow(s, bottom)) return false;
        h.x = s.shelfX;
        h.y = s.shelfY;
        h.generation = s.generation;
        h.copied = -3;
        s.shelfX += h.w;
        s.shelfHeight = Math.max(s.shelfHeight, h.h);
        return true;
    }

    /** Makes the sheet at least {@code rows} tall: a new, bigger texture, so everything on it is copied again. */
    private static boolean grow(Sheet s, int rows) {
        int height = Math.min(MAX_SIZE, Math.max(rows, Math.max(1024, s.height * 2)));
        long after = (long) WIDTH * height * (s.full ? 16 : 8) + (s.full ? half : full).bytes();
        if (rows > MAX_SIZE || after > BUDGET) {
            height = rows;
            after = (long) WIDTH * height * (s.full ? 16 : 8) + (s.full ? half : full).bytes();
            if (rows > MAX_SIZE || after > BUDGET) return false;
        }
        allocate(s, height);
        for (Home h : homes.values()) if (h.sheet == s) h.copied = -3;
        Log.info("atlas: the " + (s.full ? "full" : "half") + "-float sheet is now " + WIDTH + "x" + height
            + "; both take " + ((half.bytes() + full.bytes()) >> 20) + " MB");
        return true;
    }

    /**
     * The sheet is full: packs it again with only the textures read lately, tallest first, and
     * copies them afresh. Those that still don't fit lose their place.
     */
    private static void repack(Sheet s) {
        long now = System.nanoTime();
        List<Map.Entry<Long, Home>> keep = new ArrayList<>();
        List<Long> drop = new ArrayList<>();
        for (Map.Entry<Long, Home> e : homes.entrySet()) {
            if (e.getValue().sheet != s) continue;
            if (now - e.getValue().lastRead <= RECENT_NS) keep.add(e);
            else drop.add(e.getKey());
        }
        for (long k : drop) homes.remove(k);
        keep.sort((a, b) -> b.getValue().h - a.getValue().h);
        s.generation++;
        s.shelfX = s.shelfY = s.shelfHeight = 0;
        for (Map.Entry<Long, Home> e : keep) {
            if (!place(s, e.getValue())) {
                Log.once("atlasThrash:" + s.full, "the " + (s.full ? "full" : "half")
                    + "-float atlas sheet can't hold what the last few seconds read; some textures read as black");
                homes.remove(e.getKey());
            }
        }
        Log.info("atlas: the " + (s.full ? "full" : "half") + "-float sheet packed again with " + keep.size()
            + " textures (" + drop.size() + " not read lately left out)");
    }

    /** Width and height of level 0, asking GL for textures this file never saw allocated. */
    private static int[] size(int texture) {
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
    static boolean needsFull(int f) {
        return f == 0x822E || f == 0x8230 || f == 0x8814 || f == 0x8815 || Formats.isDepth(f)
            || f == 0x822A || f == 0x822C || f == 0x805B || f == 0x8054;
    }

    /** "half", "full" or "both": which sheets a texture has a place in now; null when none. For tests. */
    static String sheetOf(int texture) {
        Home h = homes.get(key(texture, false)), f = homes.get(key(texture, true));
        boolean inHalf = h != null && h.generation == h.sheet.generation, inFull = f != null && f.generation == f.sheet.generation;
        return inHalf && inFull ? "both" : inHalf ? "half" : inFull ? "full" : null;
    }

    /** A texture GL deleted: its places go back on the next packing. */
    static void forget(int texture) {
        homes.remove(key(texture, false));
        homes.remove(key(texture, true));
    }

    private static void allocate(Sheet s, int height) {
        if (s.texture != 0) {
            GL11.glDeleteTextures(s.texture);
            GL30.glDeleteFramebuffers(s.fbo);
        }
        s.height = height;
        s.texture = GL11.glGenTextures();
        int format = s.full ? RGBA32F : RGBA16F;
        Tex.with(G.T_2D, s.texture, () -> {
            Tex.unpacked(() -> GL11.glTexImage2D(G.T_2D, 0, format, WIDTH, height, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null), true);
            GL11.glTexParameteri(G.T_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(G.T_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(G.T_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(G.T_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(G.T_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        });
        int prevDraw = GL11.glGetInteger(G.DRAW_FRAMEBUFFER_BINDING), prevRead = GL11.glGetInteger(G.READ_FRAMEBUFFER_BINDING);
        s.fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(G.FRAMEBUFFER, s.fbo);
        GL30.glFramebufferTexture2D(G.FRAMEBUFFER, G.COLOR_ATTACHMENT0, G.T_2D, s.texture, 0);
        int status = GL30.glCheckFramebufferStatus(G.FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            Log.warn("atlas framebuffer incomplete (0x" + Integer.toHexString(status) + ", error " + GL11.glGetError() + ")");
        }
        GL30.glBindFramebuffer(G.DRAW_FRAMEBUFFER, prevDraw);
        GL30.glBindFramebuffer(G.READ_FRAMEBUFFER, prevRead);
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

    private static void copy(Home h, int texture) {
        Stats.atlasCopies++;
        Stats.atlasTexels += (long) h.w * h.h;
        if (AtlasCensus.on) {
            Tex.Info ci = Tex.peek(texture);
            AtlasCensus.copy(h.sheet.texture, texture, h.w, h.h, ci == null ? 0 : ci.ifmt);
        }
        if (blitProgram == 0) makeBlit();
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevDraw = GL11.glGetInteger(G.DRAW_FRAMEBUFFER_BINDING), prevRead = GL11.glGetInteger(G.READ_FRAMEBUFFER_BINDING);
        int active = GL11.glGetInteger(G.ACTIVE_TEXTURE);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL13.glActiveTexture(G.TEXTURE0);
        int prevTexture = GL11.glGetInteger(G.BINDINGS[G.targetIndex(G.T_2D)]);
        try {
            GL30.glBindFramebuffer(G.FRAMEBUFFER, h.sheet.fbo);
            GL11.glDrawBuffer(G.COLOR_ATTACHMENT0);
            GL11.glViewport(h.x, h.y, h.w, h.h);
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
            GL20.glUniform2i(blitOrigin, h.x, h.y);
            GL20.glUniform2f(blitScale, (float) h.sourceW / h.w, (float) h.sourceH / h.h);
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
