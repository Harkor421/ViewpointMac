package vpmac;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites Viewpoint's GLSL 3.30 shaders into GLSL 1.20 plus the extensions a Mac's legacy
 * OpenGL 2.1 context offers (EXT_gpu_shader4, EXT_texture_array, ARB_draw_instanced,
 * ARB_shader_texture_lod).
 *
 * <p>The 3.30 names that 1.20 lacks (texture, texelFetch, textureLod...) become overloaded
 * helper functions, so call sites stay untouched. Buffer textures don't exist on the Mac, so a
 * {@code samplerBuffer} becomes a {@code sampler2D} that {@link G} fills row by row,
 * {@link #BUFFER_WIDTH} texels a row.
 */
public final class Glsl {
    private Glsl() {}

    /** Texels a row of the 2D texture that stands in for a buffer texture. */
    public static final int BUFFER_WIDTH = 4096;
    static final int BUFFER_SHIFT = 12;

    public static final class Result {
        public final String source;
        /** Vertex inputs and fragment outputs with their {@code layout(location)}, to bind before linking. */
        public final Map<String, Integer> locations;
        public final Set<String> bufferSamplers;
        /**
         * Shadow samplers whose size the shader asks for: 1.20 has no textureSize for them, so the
         * size arrives in a uniform named {@link #SIZE_PREFIX} + sampler, which {@link G} keeps set.
         */
        public final Set<String> sizedShadowSamplers;
        /**
         * Float buffer samplers folded into the one {@link #MERGED} texture, in order: each reads
         * its range from the texel {@link #ROW_PREFIX} + name gives. Saves a unit per extra buffer.
         */
        public final java.util.List<String> mergedBuffers;
        /** Plain sampler2D uniforms that can move into the {@link #ATLAS}, cheapest to move first. */
        public final java.util.List<String> packable;
        /** Those that did: each reads its region of the atlas, given by {@link #RECT_PREFIX} + name. */
        public final java.util.List<String> packed;
        /** How many model textures the shader keeps of its texture array ({@link ModelSlots}); 0 when it has none. */
        public final int modelTextures;

        Result(String source, Map<String, Integer> locations, Set<String> bufferSamplers, Set<String> sizedShadowSamplers,
               java.util.List<String> mergedBuffers, java.util.List<String> packable, java.util.List<String> packed, int modelTextures) {
            this.modelTextures = modelTextures;
            this.source = source;
            this.locations = locations;
            this.bufferSamplers = bufferSamplers;
            this.sizedShadowSamplers = sizedShadowSamplers;
            this.mergedBuffers = mergedBuffers;
            this.packable = packable;
            this.packed = packed;
        }
    }

    public static final String ATLAS = "vpmac_atlas";
    public static final String RECT_PREFIX = "vpmac_rect_";
    public static final String MODE_PREFIX = "vpmac_mode_";

    public static final String SIZE_PREFIX = "vpmac_size_";
    public static final String MERGED = "vpmac_buffers";
    public static final String ROW_PREFIX = "vpmac_row_";

    /**
     * Sampler arrays cut down so a program fits the Mac's 16 texture units: Viewpoint's model
     * batches bind 16 textures and its atlas draws 8. Indexes past the cut are clamped.
     */
    static final String[][] CUT_ARRAYS = {
        {"MODEL_TEXTURES", "uTextures", "8"},
        {"ATLAS_SLOTS", "uAtlases", "3"},
    };

    private static final Pattern VERSION = Pattern.compile("(?m)^[ \\t]*#[ \\t]*version[^\\n]*$");
    private static final Pattern EXTENSION = Pattern.compile("(?m)^[ \\t]*#[ \\t]*extension[^\\n]*$");
    private static final Pattern PRECISION = Pattern.compile("(?m)^[ \\t]*precision[ \\t]+\\w+[ \\t]+\\w+[ \\t]*;");
    private static final Pattern PRECISION_WORD = Pattern.compile("\\b(highp|mediump|lowp)\\s+");
    private static final Pattern GLOBAL_DECL = Pattern.compile(
        "^(\\s*)(?:layout\\s*\\(([^)]*)\\)\\s*)?((?:(?:flat|smooth|noperspective|centroid)\\s+)*)(in|out)\\s+(\\w+)\\s+([^;{(]+);(.*)$");
    private static final Pattern LOCATION = Pattern.compile("location\\s*=\\s*(\\d+)");
    private static final Pattern BUFFER_UNIFORM = Pattern.compile("\\buniform\\s+([iu]?)samplerBuffer\\s+(\\w+)");
    private static final Pattern SHADOW_UNIFORM = Pattern.compile("\\buniform\\s+sampler2D(Array)?Shadow\\s+(\\w+)\\s*;");

    public static Result translate(String src, boolean vertex) {
        return translate(src, vertex, java.util.List.of());
    }

    /** As {@link #translate(String, boolean)}, moving the sampler2D uniforms named in {@code pack} into the atlas. */
    public static Result translate(String src, boolean vertex, java.util.List<String> pack) {
        return translate(src, vertex, pack, MODEL_TEXTURES);
    }

    /** The model textures a model shader keeps unless its program can't link with that many. */
    public static final int MODEL_TEXTURES = Integer.parseInt(CUT_ARRAYS[0][2]);
    /** Fewer than this and a batch of models would be mostly bookkeeping. */
    public static final int MIN_MODEL_TEXTURES = 4;

    /** As above, keeping {@code modelTextures} of the model texture array ({@link #CUT_ARRAYS}' first). */
    public static Result translate(String src, boolean vertex, java.util.List<String> pack, int modelTextures) {
        String s = src.replace("\r\n", "\n");
        s = VERSION.matcher(s).replaceAll("");
        s = EXTENSION.matcher(s).replaceAll("");
        s = PRECISION.matcher(s).replaceAll("");
        s = PRECISION_WORD.matcher(s).replaceAll("");
        s = Preproc.resolve(s);
        s = oneDeclarationEach(s);

        Set<String> bufferSamplers = new LinkedHashSet<>();
        java.util.List<String> floats = new java.util.ArrayList<>();
        Matcher bm = BUFFER_UNIFORM.matcher(s);
        while (bm.find()) {
            bufferSamplers.add(bm.group(2));
            if (bm.group(1).isEmpty() && !floats.contains(bm.group(2))) floats.add(bm.group(2));
        }
        // Every float buffer texture reads from the shared stand-in at a base texel, so a new
        // range of the same buffer is just a new base, not a new texture.
        java.util.List<String> merged = floats;
        if (!merged.isEmpty()) {
            StringBuilder decl = new StringBuilder("uniform sampler2D " + MERGED + ";\n");
            boolean first = true;
            for (String name : merged) {
                bufferSamplers.remove(name);
                String with = "uniform int " + ROW_PREFIX + name + ";\n#define " + name + " " + MERGED + ", " + ROW_PREFIX + name;
                s = s.replaceFirst("\\buniform\\s+samplerBuffer\\s+" + name + "\\s*;", Matcher.quoteReplacement((first ? decl : "") + with));
                first = false;
            }
        }
        s = s.replaceAll("\\bisamplerBuffer\\b", "isampler2D")
             .replaceAll("\\busamplerBuffer\\b", "usampler2D")
             .replaceAll("\\bsamplerBuffer\\b", "sampler2D");

        int keptModel = s.contains(CUT_ARRAYS[0][1] + "[") ? modelTextures : 0;
        for (String[] cut : CUT_ARRAYS) s = cutArray(s, cut[0], cut[1], cut == CUT_ARRAYS[0] ? modelTextures : Integer.parseInt(cut[2]));

        java.util.List<String> packable = packable(s, bufferSamplers, merged);
        java.util.List<String> packed = new java.util.ArrayList<>();
        for (String name : pack) if (packable.contains(name)) packed.add(name);
        if (!packed.isEmpty()) s = pack(s, packed);

        Map<String, Integer> locations = new LinkedHashMap<>();
        s = rewriteGlobals(s, vertex, locations);

        if (vertex) s = s.replaceAll("\\bgl_InstanceID\\b", "gl_InstanceIDARB");
        // EXT_gpu_shader4 spells the unsigned scalar "unsigned int".
        s = s.replaceAll("\\buint\\b", "unsigned int");

        Set<String> sized = new LinkedHashSet<>();
        StringBuilder sizeUniforms = new StringBuilder();
        Matcher sm = SHADOW_UNIFORM.matcher(s);
        while (sm.find()) {
            String name = sm.group(2);
            Pattern call = Pattern.compile("\\btextureSize\\s*\\(\\s*" + name + "\\s*,[^)]*\\)");
            if (!call.matcher(s).find()) continue;
            sized.add(name);
            String type = sm.group(1) != null ? "ivec3" : "ivec2";
            sizeUniforms.append("uniform ").append(type).append(' ').append(SIZE_PREFIX).append(name).append(";\n");
        }
        for (String name : sized) {
            s = s.replaceAll("\\btextureSize\\s*\\(\\s*" + name + "\\s*,[^)]*\\)", SIZE_PREFIX + name);
        }

        return new Result(prelude(s, vertex) + (packed.isEmpty() ? "" : atlasHelpers()) + sizeUniforms + s,
            locations, bufferSamplers, sized, merged, packable, packed, keptModel);
    }

    private static final Pattern PLAIN_SAMPLER = Pattern.compile("\\buniform\\s+sampler2D\\s+(\\w+)\\s*;");
    private static final Pattern TEXTURE_CALL = Pattern.compile(
        "\\b(texture|texelFetch|textureSize|textureLod|textureGrad|textureGather|textureOffset|textureLodOffset)\\s*\\(\\s*$");
    /** Small maps and tables: cheap to copy each frame. */
    private static final Pattern CHEAP = Pattern.compile("(?i)sky|transmittance|scattering|weather|bedcolou?r|spot|lamp|water|cloud|rain|mask|plan|table|palette|grid|noise|tone|lut|area|search|wave|star|moon|flash");
    /** Screen-sized buffers, copied every frame. */
    private static final Pattern COSTLY = Pattern.compile("(?i)albedo|normal|depth|engine|occlusion|bounce|reflection|velocity|history|colou?r|scene|current|light");
    /** Depth needs 32-bit floats, which doubles the atlas it lands in. */
    private static final Pattern DEPTH = Pattern.compile("(?i)depth");
    /** The terrain's relief and the tree atlas, thousands of texels a side: a gigabyte in an atlas. */
    private static final Pattern HUGE = Pattern.compile("(?i)relief|tree");

    /** The order textures move into the atlas, cheapest first. */
    static int cost(String name) {
        if (HUGE.matcher(name).find()) return 4;
        if (DEPTH.matcher(name).find()) return 3;
        if (CHEAP.matcher(name).find()) return 0;
        return COSTLY.matcher(name).find() ? 2 : 1;
    }

    /** sampler2D uniforms only ever used as the first argument of a texture function. */
    private static java.util.List<String> packable(String s, Set<String> buffers, java.util.List<String> merged) {
        java.util.List<String> names = new java.util.ArrayList<>();
        Matcher m = PLAIN_SAMPLER.matcher(s);
        while (m.find()) {
            String name = m.group(1);
            if (buffers.contains(name) || merged.contains(name) || name.equals(MERGED) || names.contains(name)) continue;
            boolean ok = true;
            Matcher use = Pattern.compile("\\b" + name + "\\b").matcher(s);
            while (use.find() && ok) {
                if (use.start() >= m.start() && use.end() <= m.end()) continue;
                String before = s.substring(Math.max(0, use.start() - 40), use.start());
                ok = TEXTURE_CALL.matcher(before).find();
            }
            if (ok) names.add(name);
        }
        names.sort(java.util.Comparator.comparingInt(Glsl::cost));
        return names;
    }

    private static String pack(String s, java.util.List<String> packed) {
        for (String name : packed) {
            String with = "uniform vec4 " + RECT_PREFIX + name + ";\nuniform vec4 " + MODE_PREFIX + name + ";\n"
                + "#define " + name + " " + ATLAS + ", " + RECT_PREFIX + name + ", " + MODE_PREFIX + name;
            s = s.replaceFirst("\\buniform\\s+sampler2D\\s+" + name + "\\s*;", Matcher.quoteReplacement(with));
        }
        return s;
    }

    /**
     * Texture functions for a region of the atlas. rect: texel origin xy, size zw as stored; mode:
     * x = 1 repeats, y = 1 samples the nearest texel, zw = the texture's own size when it is
     * stored smaller (else 0). No mipmaps: every level reads level 0.
     */
    private static String atlasHelpers() {
        return "uniform sampler2D " + ATLAS + ";\n"
            + "vec2 vpmac_extent(vec4 r, vec4 m) { return m.z > 0.0 ? m.zw : r.zw; }\n"
            + "vec2 vpmac_uv(sampler2D a, vec4 r, vec4 m, vec2 uv) { vec2 t = uv * r.zw;"
            + " if (m.x > 0.5) t = mod(t, r.zw);"
            + " t = m.y > 0.5 ? floor(clamp(t, vec2(0.0), r.zw - 1.0)) + 0.5 : clamp(t, vec2(0.5), r.zw - 0.5);"
            + " return (r.xy + t) / vec2(textureSize2D(a, 0)); }\n"
            + "vec4 texture(sampler2D a, vec4 r, vec4 m, vec2 uv) { return texture2D(a, vpmac_uv(a, r, m, uv)); }\n"
            + "vec4 texture(sampler2D a, vec4 r, vec4 m, vec2 uv, float b) { return texture2D(a, vpmac_uv(a, r, m, uv)); }\n"
            + "vec4 textureLod(sampler2D a, vec4 r, vec4 m, vec2 uv, float l) { return texture2D(a, vpmac_uv(a, r, m, uv)); }\n"
            + "vec4 textureGrad(sampler2D a, vec4 r, vec4 m, vec2 uv, vec2 dx, vec2 dy) { return texture2D(a, vpmac_uv(a, r, m, uv)); }\n"
            + "vec4 textureOffset(sampler2D a, vec4 r, vec4 m, vec2 uv, ivec2 o) {"
            + " return texture2D(a, vpmac_uv(a, r, m, uv + vec2(o) / vpmac_extent(r, m))); }\n"
            + "vec4 textureLodOffset(sampler2D a, vec4 r, vec4 m, vec2 uv, float l, ivec2 o) {"
            + " return texture2D(a, vpmac_uv(a, r, m, uv + vec2(o) / vpmac_extent(r, m))); }\n"
            + "vec4 texelFetch(sampler2D a, vec4 r, vec4 m, ivec2 p, int l) {"
            + " ivec2 q = m.z > 0.0 ? ivec2((vec2(p) + 0.5) * r.zw / m.zw) : p;"
            + " return texelFetch2D(a, ivec2(r.xy) + clamp(q, ivec2(0), ivec2(r.zw) - 1), 0); }\n"
            + "ivec2 textureSize(sampler2D a, vec4 r, vec4 m, int l) { return ivec2(vpmac_extent(r, m)); }\n"
            + "vec4 textureGather(sampler2D a, vec4 r, vec4 m, vec2 uv) { vec2 t = uv * vpmac_extent(r, m) - 0.5; ivec2 i = ivec2(floor(t));"
            + " return vec4(texelFetch(a, r, m, i + ivec2(0, 1), 0).r, texelFetch(a, r, m, i + ivec2(1, 1), 0).r,"
            + " texelFetch(a, r, m, i + ivec2(1, 0), 0).r, texelFetch(a, r, m, i, 0).r); }\n";
    }

    private static String cutArray(String s, String define, String array, int size) {
        if (!s.contains(array + "[")) return s;
        s = s.replaceAll("(?m)^([ \\t]*#[ \\t]*define[ \\t]+" + define + "[ \\t]+)\\d+", "$1" + size);
        StringBuilder out = new StringBuilder(s.length() + 64);
        String open = array + "[";
        int i = 0;
        while (true) {
            int at = s.indexOf(open, i);
            if (at < 0) break;
            int start = at + open.length();
            int depth = 1, j = start;
            while (j < s.length() && depth > 0) {
                char c = s.charAt(j);
                if (c == '[') depth++;
                else if (c == ']') depth--;
                j++;
            }
            String index = s.substring(start, j - 1).trim();
            out.append(s, i, start);
            boolean declaration = index.equals(define) || index.matches("\\d+");
            out.append(declaration ? index : "min(int(" + index + "), " + (size - 1) + ")");
            out.append(']');
            i = j;
        }
        out.append(s.substring(i));
        return out.toString();
    }

    private static final Pattern SAMPLER_LIST = Pattern.compile("^(\\s*)uniform\\s+([iu]?sampler\\w+)\\s+([^;=(]*,[^;=(]*)$");

    /**
     * At file scope: one statement a line, and one sampler a uniform declaration. Add-ons write
     * {@code out vec2 a; out vec4 b;} on one line and {@code uniform sampler2D a, b;}, and the
     * rewrites here (globals, buffer samplers, the atlas) read a declaration a line.
     */
    static String oneDeclarationEach(String s) {
        StringBuilder out = new StringBuilder(s.length() + 256);
        int depth = 0;
        boolean inBlockComment = false, continued = false;
        for (String line : s.split("\n", -1)) {
            String code = line;
            // A preprocessor line, and every line a trailing backslash continues, stays whole.
            boolean macro = continued || line.trim().startsWith("#");
            continued = macro && line.endsWith("\\");
            if (depth == 0 && !inBlockComment && !macro && !line.contains("/*")) {
                int comment = line.indexOf("//");
                code = comment < 0 ? line : line.substring(0, comment);
                String tail = comment < 0 ? "" : line.substring(comment);
                // Declarations only: a one-line function stays as it is.
                if (code.indexOf('{') >= 0 || code.indexOf('}') >= 0) code = "";
                StringBuilder split = new StringBuilder();
                int parens = 0, from = 0;
                for (int i = 0; i < code.length(); i++) {
                    char c = code.charAt(i);
                    if (c == '(') parens++;
                    else if (c == ')') parens--;
                    else if (c == ';' && parens == 0) {
                        split.append(samplerList(code.substring(from, i + 1))).append('\n');
                        from = i + 1;
                    }
                }
                String rest = code.substring(from);
                if (split.length() > 0) {
                    String joined = split + (rest.isBlank() ? "" : rest) + tail;
                    out.append(joined.endsWith("\n") ? joined.substring(0, joined.length() - 1) : joined);
                } else {
                    out.append(line);
                }
            } else {
                out.append(line);
            }
            out.append('\n');
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (inBlockComment) {
                    if (c == '*' && i + 1 < line.length() && line.charAt(i + 1) == '/') { inBlockComment = false; i++; }
                    continue;
                }
                if (c == '/' && i + 1 < line.length()) {
                    char n = line.charAt(i + 1);
                    if (n == '/') break;
                    if (n == '*') { inBlockComment = true; i++; continue; }
                }
                if (c == '{') depth++;
                else if (c == '}') depth--;
            }
        }
        return out.substring(0, out.length() - 1);
    }

    /** {@code uniform sampler2D a, b;} as one declaration each. */
    private static String samplerList(String statement) {
        String body = statement.substring(0, statement.length() - 1);
        Matcher m = SAMPLER_LIST.matcher(body);
        if (!m.matches()) return statement;
        StringBuilder b = new StringBuilder();
        for (String name : m.group(3).split(",")) {
            if (b.length() > 0) b.append('\n');
            b.append(m.group(1)).append("uniform ").append(m.group(2)).append(' ').append(name.trim()).append(';');
        }
        return b.toString();
    }

    /** Turns the global in/out declarations into attribute/varying ones; leaves function parameters alone. */
    private static String rewriteGlobals(String s, boolean vertex, Map<String, Integer> locations) {
        StringBuilder out = new StringBuilder(s.length() + 256);
        int depth = 0;
        boolean inBlockComment = false;
        for (String line : s.split("\n", -1)) {
            if (depth == 0 && !inBlockComment) {
                Matcher m = GLOBAL_DECL.matcher(line);
                if (m.matches()) {
                    line = rewriteDecl(m, vertex, locations);
                }
            }
            // Track brace depth outside comments.
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (inBlockComment) {
                    if (c == '*' && i + 1 < line.length() && line.charAt(i + 1) == '/') { inBlockComment = false; i++; }
                    continue;
                }
                if (c == '/' && i + 1 < line.length()) {
                    char n = line.charAt(i + 1);
                    if (n == '/') break;
                    if (n == '*') { inBlockComment = true; i++; continue; }
                }
                if (c == '{') depth++;
                else if (c == '}') depth--;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String rewriteDecl(Matcher m, boolean vertex, Map<String, Integer> locations) {
        String indent = m.group(1);
        String layout = m.group(2);
        String interp = m.group(3).replaceAll("\\bsmooth\\s+", "");
        boolean in = m.group(4).equals("in");
        String type = m.group(5);
        String names = m.group(6).trim();
        String rest = m.group(7);

        if (layout != null) {
            Matcher lm = LOCATION.matcher(layout);
            if (lm.find()) {
                int loc = Integer.parseInt(lm.group(1));
                for (String n : names.split(",")) {
                    locations.put(n.replaceAll("\\[.*", "").trim(), loc++);
                }
            }
        }

        String storage;
        if (vertex && in) {
            storage = "attribute";
            interp = "";
        } else if (!vertex && !in) {
            storage = "varying out";
            interp = "";
        } else {
            storage = "varying";
            if (isIntegerType(type) && !interp.contains("flat")) interp = "flat " + interp;
        }
        return indent + interp + storage + " " + type + " " + names + ";" + rest;
    }

    private static boolean isIntegerType(String t) {
        return t.equals("int") || t.equals("uint") || t.matches("[iu]vec[234]");
    }

    private static boolean uses(String s, String name) {
        return Pattern.compile("\\b" + name + "\\s*\\(").matcher(s).find();
    }

    private static String prelude(String body, boolean vertex) {
        StringBuilder p = new StringBuilder(4096);
        p.append("#version 120\n");
        p.append("#extension GL_EXT_gpu_shader4 : require\n");
        p.append("#extension GL_EXT_texture_array : require\n");
        if (vertex) p.append("#extension GL_ARB_draw_instanced : require\n");
        else p.append("#extension GL_ARB_shader_texture_lod : require\n");

        if (uses(body, "texture")) {
            p.append("vec4 texture(sampler2D s, vec2 p) { return texture2D(s, p); }\n");
            p.append("vec4 texture(sampler3D s, vec3 p) { return texture3D(s, p); }\n");
            p.append("vec4 texture(samplerCube s, vec3 p) { return textureCube(s, p); }\n");
            p.append("vec4 texture(sampler2DArray s, vec3 p) { return texture2DArray(s, p); }\n");
            p.append("float texture(sampler2DShadow s, vec3 p) { return shadow2D(s, p).r; }\n");
            p.append("float texture(sampler2DArrayShadow s, vec4 p) { return shadow2DArray(s, p).r; }\n");
            p.append("ivec4 texture(isampler2D s, vec2 p) { return texture2D(s, p); }\n");
            p.append("ivec4 texture(isampler3D s, vec3 p) { return texture3D(s, p); }\n");
            p.append("uvec4 texture(usampler2DArray s, vec3 p) { return texture2DArray(s, p); }\n");
            if (!vertex) {
                p.append("vec4 texture(sampler2D s, vec2 p, float b) { return texture2D(s, p, b); }\n");
                p.append("vec4 texture(sampler3D s, vec3 p, float b) { return texture3D(s, p, b); }\n");
                p.append("vec4 texture(sampler2DArray s, vec3 p, float b) { return texture2DArray(s, p, b); }\n");
            }
        }
        if (uses(body, "textureLod") || uses(body, "textureLodOffset")) {
            p.append("vec4 textureLod(sampler2D s, vec2 p, float l) { return texture2DLod(s, p, l); }\n");
            p.append("vec4 textureLod(sampler3D s, vec3 p, float l) { return texture3DLod(s, p, l); }\n");
            p.append("vec4 textureLod(samplerCube s, vec3 p, float l) { return textureCubeLod(s, p, l); }\n");
            p.append("vec4 textureLod(sampler2DArray s, vec3 p, float l) { return texture2DArrayLod(s, p, l); }\n");
        }
        if (uses(body, "textureLodOffset")) {
            p.append("vec4 textureLodOffset(sampler2D s, vec2 p, float l, ivec2 o) { return texture2DLod(s, p + vec2(o) / vec2(textureSize2D(s, 0)), l); }\n");
        }
        if (uses(body, "textureOffset")) {
            p.append("vec4 textureOffset(sampler2D s, vec2 p, ivec2 o) { return texture2DOffset(s, p, o); }\n");
        }
        if (uses(body, "textureGrad")) {
            p.append("vec4 textureGrad(sampler2D s, vec2 p, vec2 dx, vec2 dy) { return texture2DGradARB(s, p, dx, dy); }\n");
            p.append("vec4 textureGrad(sampler2DArray s, vec3 p, vec2 dx, vec2 dy) { return texture2DArray(s, p); }\n");
        }
        if (uses(body, "texelFetch")) {
            p.append("vec4 texelFetch(sampler2D s, ivec2 p, int l) { return texelFetch2D(s, p, l); }\n");
            p.append("vec4 texelFetch(sampler3D s, ivec3 p, int l) { return texelFetch3D(s, p, l); }\n");
            p.append("vec4 texelFetch(sampler2DArray s, ivec3 p, int l) { return texelFetch2DArray(s, p, l); }\n");
            p.append("ivec4 texelFetch(isampler2D s, ivec2 p, int l) { return texelFetch2D(s, p, l); }\n");
            p.append("ivec4 texelFetch(isampler3D s, ivec3 p, int l) { return texelFetch3D(s, p, l); }\n");
            p.append("uvec4 texelFetch(usampler2D s, ivec2 p, int l) { return texelFetch2D(s, p, l); }\n");
            p.append("uvec4 texelFetch(usampler2DArray s, ivec3 p, int l) { return texelFetch2DArray(s, p, l); }\n");
            // Buffer textures, laid out BUFFER_WIDTH texels a row.
            String at = "ivec2(i & " + (BUFFER_WIDTH - 1) + ", i >> " + BUFFER_SHIFT + ")";
            p.append("vec4 texelFetch(sampler2D s, int i) { return texelFetch2D(s, ").append(at).append(", 0); }\n");
            p.append("ivec4 texelFetch(isampler2D s, int i) { return texelFetch2D(s, ").append(at).append(", 0); }\n");
            p.append("uvec4 texelFetch(usampler2D s, int i) { return texelFetch2D(s, ").append(at).append(", 0); }\n");
            // Buffers folded into one texture: texel i of the range starts at texel base.
            p.append("vec4 texelFetch(sampler2D s, int base, int i) { int k = base + i; return texelFetch2D(s, ivec2(k & ")
                .append(BUFFER_WIDTH - 1).append(", k >> ").append(BUFFER_SHIFT).append("), 0); }\n");
        }
        if (uses(body, "textureSize")) {
            p.append("ivec2 textureSize(sampler2D s, int l) { return textureSize2D(s, l); }\n");
            p.append("ivec3 textureSize(sampler3D s, int l) { return textureSize3D(s, l); }\n");
            p.append("ivec3 textureSize(sampler2DArray s, int l) { return textureSize2DArray(s, l); }\n");
            p.append("ivec2 textureSize(isampler2D s, int l) { return textureSize2D(s, l); }\n");
            p.append("ivec3 textureSize(isampler3D s, int l) { return textureSize3D(s, l); }\n");
        }
        if (uses(body, "textureGather")) {
            p.append("vec4 textureGather(sampler2D s, vec2 p) { vec2 t = p * vec2(textureSize2D(s, 0)) - 0.5; ivec2 i = ivec2(floor(t));"
                + " return vec4(texelFetch2D(s, i + ivec2(0, 1), 0).r, texelFetch2D(s, i + ivec2(1, 1), 0).r,"
                + " texelFetch2D(s, i + ivec2(1, 0), 0).r, texelFetch2D(s, i, 0).r); }\n");
        }
        if (uses(body, "mix")) {
            p.append("float mix(float a, float b, bool c) { return c ? b : a; }\n");
            p.append("vec2 mix(vec2 a, vec2 b, bvec2 c) { return vec2(c.x ? b.x : a.x, c.y ? b.y : a.y); }\n");
            p.append("vec3 mix(vec3 a, vec3 b, bvec3 c) { return vec3(c.x ? b.x : a.x, c.y ? b.y : a.y, c.z ? b.z : a.z); }\n");
            p.append("vec4 mix(vec4 a, vec4 b, bvec4 c) { return vec4(c.x ? b.x : a.x, c.y ? b.y : a.y, c.z ? b.z : a.z, c.w ? b.w : a.w); }\n");
        }
        if (uses(body, "isnan")) {
            p.append("bool isnan(float x) { return !(x < 0.0 || x > 0.0 || x == 0.0); }\n");
            p.append("bvec2 isnan(vec2 x) { return bvec2(isnan(x.x), isnan(x.y)); }\n");
            p.append("bvec3 isnan(vec3 x) { return bvec3(isnan(x.x), isnan(x.y), isnan(x.z)); }\n");
            p.append("bvec4 isnan(vec4 x) { return bvec4(isnan(x.x), isnan(x.y), isnan(x.z), isnan(x.w)); }\n");
        }
        if (uses(body, "isinf")) {
            p.append("bool isinf(float x) { return abs(x) > 3.4e38; }\n");
            p.append("bvec2 isinf(vec2 x) { return greaterThan(abs(x), vec2(3.4e38)); }\n");
            p.append("bvec3 isinf(vec3 x) { return greaterThan(abs(x), vec3(3.4e38)); }\n");
            p.append("bvec4 isinf(vec4 x) { return greaterThan(abs(x), vec4(3.4e38)); }\n");
        }
        if (uses(body, "tanh")) {
            p.append("float tanh(float x) { float e = exp(2.0 * clamp(x, -15.0, 15.0)); return (e - 1.0) / (e + 1.0); }\n");
            p.append("vec3 tanh(vec3 x) { vec3 e = exp(2.0 * clamp(x, -15.0, 15.0)); return (e - 1.0) / (e + 1.0); }\n");
        }
        if (uses(body, "inverse")) {
            // GLSL 1.40; add-ons use it on model matrices.
            p.append("mat2 inverse(mat2 m) { return mat2(m[1][1], -m[0][1], -m[1][0], m[0][0]) / (m[0][0] * m[1][1] - m[0][1] * m[1][0]); }\n");
            p.append("mat3 inverse(mat3 m) { vec3 r0 = cross(m[1], m[2]), r1 = cross(m[2], m[0]), r2 = cross(m[0], m[1]);"
                + " return mat3(r0.x, r1.x, r2.x, r0.y, r1.y, r2.y, r0.z, r1.z, r2.z) / dot(m[0], r0); }\n");
            p.append("mat4 inverse(mat4 m) {"
                + " float a00 = m[0][0], a01 = m[0][1], a02 = m[0][2], a03 = m[0][3], a10 = m[1][0], a11 = m[1][1], a12 = m[1][2], a13 = m[1][3],"
                + " a20 = m[2][0], a21 = m[2][1], a22 = m[2][2], a23 = m[2][3], a30 = m[3][0], a31 = m[3][1], a32 = m[3][2], a33 = m[3][3],"
                + " b00 = a00 * a11 - a01 * a10, b01 = a00 * a12 - a02 * a10, b02 = a00 * a13 - a03 * a10, b03 = a01 * a12 - a02 * a11,"
                + " b04 = a01 * a13 - a03 * a11, b05 = a02 * a13 - a03 * a12, b06 = a20 * a31 - a21 * a30, b07 = a20 * a32 - a22 * a30,"
                + " b08 = a20 * a33 - a23 * a30, b09 = a21 * a32 - a22 * a31, b10 = a21 * a33 - a23 * a31, b11 = a22 * a33 - a23 * a32,"
                + " det = b00 * b11 - b01 * b10 + b02 * b09 + b03 * b08 - b04 * b07 + b05 * b06;"
                + " return mat4(a11 * b11 - a12 * b10 + a13 * b09, a02 * b10 - a01 * b11 - a03 * b09, a31 * b05 - a32 * b04 + a33 * b03, a22 * b04 - a21 * b05 - a23 * b03,"
                + " a12 * b08 - a10 * b11 - a13 * b07, a00 * b11 - a02 * b08 + a03 * b07, a32 * b02 - a30 * b05 - a33 * b01, a20 * b05 - a22 * b02 + a23 * b01,"
                + " a10 * b10 - a11 * b08 + a13 * b06, a01 * b08 - a00 * b10 - a03 * b06, a30 * b04 - a31 * b02 + a33 * b00, a21 * b02 - a20 * b04 - a23 * b00,"
                + " a11 * b07 - a10 * b09 - a12 * b06, a00 * b09 - a01 * b07 + a02 * b06, a31 * b01 - a30 * b03 - a32 * b00, a20 * b03 - a21 * b01 + a22 * b00) / det; }\n");
        }
        if (uses(body, "determinant")) {
            p.append("float determinant(mat2 m) { return m[0][0] * m[1][1] - m[0][1] * m[1][0]; }\n");
            p.append("float determinant(mat3 m) { return dot(m[0], cross(m[1], m[2])); }\n");
        }
        if (uses(body, "floatBitsToInt") || uses(body, "floatBitsToUint")) {
            // No bit casts before 3.30: rebuild the IEEE bits arithmetically (finite values).
            p.append("int floatBitsToInt(float f) { if (f == 0.0) return 0; float a = abs(f); float e = floor(log2(a));"
                + " float m = a / exp2(e) - 1.0; int b = (int(e) + 127) << 23 | int(m * 8388608.0);"
                + " return f < 0.0 ? (b | int(0x80000000u)) : b; }\n");
            p.append("ivec2 floatBitsToInt(vec2 f) { return ivec2(floatBitsToInt(f.x), floatBitsToInt(f.y)); }\n");
            p.append("ivec3 floatBitsToInt(vec3 f) { return ivec3(floatBitsToInt(f.x), floatBitsToInt(f.y), floatBitsToInt(f.z)); }\n");
            p.append("unsigned int floatBitsToUint(float f) { return unsigned int(floatBitsToInt(f)); }\n");
            p.append("uvec3 floatBitsToUint(vec3 f) { return uvec3(floatBitsToInt(f)); }\n");
        }
        p.append("#line 1\n");
        return p.toString();
    }
}
