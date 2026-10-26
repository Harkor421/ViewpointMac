package vpmac;

import org.lwjgl.opengl.EXTGPUShader4;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shaders through {@link Glsl}, and each program's samplers, whose units are virtual. */
final class Prog {
    private Prog() {}

    static final int VERTEX_SHADER = 0x8B31;

    static final class Shader {
        int type;
        Glsl.Result result;
        String original;
    }

    /** One element of a sampler uniform: where it is, what it samples, and the unit Viewpoint gave it. */
    static final class Sampler {
        String name;
        int location, realTarget, virtualTarget;
        int unit;
        int realUnit;
        /** 0 float, 1 signed integer, 2 unsigned integer, 3 shadow. */
        int kind;
    }

    static final class Program {
        final List<Integer> shaders = new ArrayList<>();
        final List<Sampler> samplers = new ArrayList<>();
        /**
         * Every sampler element's location, to its array and its index there: glUniform1i sets one
         * element, glUniform1iv a run of them starting at any element.
         */
        final Map<Integer, List<Sampler>> byLocation = new HashMap<>();
        final Map<Integer, Integer> indexAt = new HashMap<>();
        /** Shadow samplers whose size the shader reads from a uniform, with that uniform's location. */
        final Map<Sampler, Integer> sizeUniforms = new HashMap<>();
        final Map<Sampler, int[]> sizeSent = new HashMap<>();
        /** Buffer samplers folded into one texture: their names, the units Viewpoint gave them, row uniforms. */
        final List<String> merged = new ArrayList<>();
        final Map<String, Integer> mergedUnit = new HashMap<>();
        final Map<String, Integer> rowLocation = new HashMap<>();
        final Map<String, Integer> rowSent = new HashMap<>();
        /** sampler2D uniforms moved into the atlas, with their rect and mode uniforms. */
        final List<String> packed = new ArrayList<>();
        final Map<String, Integer> rectLocation = new HashMap<>();
        final Map<String, Integer> modeLocation = new HashMap<>();
        /** What {@link Atlas#sync} last saw and sent, so a draw like the one before costs no lookups. */
        int[] atlasTextures;
        /** All its packed textures read from the full-float sheet (it couldn't spare a unit for two sheets). */
        boolean atlasFullOnly;
        /** Per-draw inputs it can read from {@link MeshRows}' table; null when it has none. */
        MeshRows.Plan table;
        float[][] atlasSent;
        /** Uniforms that no longer exist in the program but Viewpoint still sets: merged, then packed. */
        final List<String> fake = new ArrayList<>();
        int fakeBase = 1 << 20;
        /** Set once its first draw has been described in the log. */
        boolean dumped;
        /**
         * Which real unit each sampler gets, worked out by {@link Draw#plan} and kept until a
         * sampler's virtual unit changes ({@code unitsVersion}).
         */
        int unitsVersion, planVersion = -1, planKeys;
        int[] planUnit, planTarget, planPhysical, planFirst, planSamplerUnit;
        String label = "";
        boolean linked;
    }

    /**
     * Locations handed out for samplers that no longer exist in the program start right after its
     * highest real location: Viewpoint indexes arrays by location, so they must stay small.
     */

    private static final Map<Integer, Shader> shaders = new HashMap<>();
    static final Map<Integer, Program> programs = new HashMap<>();
    static int currentId;
    static Program current;

    static void shaderCreated(int shader, int type) {
        Shader s = new Shader();
        s.type = type;
        shaders.put(shader, s);
    }

    static String translate(int shader, CharSequence source) {
        Shader s = shaders.computeIfAbsent(shader, k -> new Shader());
        s.original = source.toString();
        try {
            s.result = Glsl.translate(s.original, s.type == VERTEX_SHADER, java.util.List.of(), ModelSlots.count);
            return s.result.source;
        } catch (RuntimeException e) {
            Log.warn("could not translate a shader: " + e);
            s.result = null;
            return s.original;
        }
    }

    private static void saveFailed(String stem, String log, List<Integer> attached) {
        save("failed-shaders", stem, log, attached);
    }

    /** Keeps the sources of a program, as Viewpoint wrote them and as translated, under {@code folder}. */
    private static void save(String folder, String stem, String log, List<Integer> attached) {
        try {
            Path dir = Log.dir().resolve(folder);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(stem + ".log"), log);
            for (int sh : attached) {
                Shader s = shaders.get(sh);
                if (s == null) continue;
                String ext = s.type == VERTEX_SHADER ? ".vert" : ".frag";
                if (s.result != null) Files.writeString(dir.resolve(stem + "-" + sh + ext), s.result.source);
                if (s.original != null) Files.writeString(dir.resolve(stem + "-" + sh + ext + ".original"), s.original);
            }
        } catch (Exception e) {
            Log.warn("could not save " + stem + ": " + e);
        }
    }

    /** Keeps a copy of any shader the Mac refuses, with its log, for debugging. */
    static void compiled(int shader) {
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_TRUE) return;
        Shader s = shaders.get(shader);
        String log = GL20.glGetShaderInfoLog(shader);
        Log.warn("a shader failed to compile on the Mac: " + log.lines().findFirst().orElse(""));
        try {
            Path dir = Log.dir().resolve("failed-shaders");
            Files.createDirectories(dir);
            String stem = "shader-" + shader + (s != null && s.type == VERTEX_SHADER ? ".vert" : ".frag");
            if (s != null && s.result != null) Files.writeString(dir.resolve(stem), s.result.source);
            if (s != null && s.original != null) Files.writeString(dir.resolve(stem + ".original"), s.original);
            Files.writeString(dir.resolve(stem + ".log"), log);
        } catch (Exception e) {
            Log.warn("could not save the failed shader: " + e);
        }
    }

    static void attach(int program, int shader) {
        programs.computeIfAbsent(program, k -> new Program()).shaders.add(shader);
    }

    static void detach(int program, int shader) {
        Program p = programs.get(program);
        if (p != null) p.shaders.remove((Integer) shader);
    }

    static void forget(int program) {
        programs.remove(program);
        if (currentId == program) {
            currentId = 0;
            current = null;
        }
    }

    static void use(int program) {
        currentId = program;
        current = programs.get(program);
    }

    private static final java.util.regex.Pattern OVER_LIMIT =
        java.util.regex.Pattern.compile("(vertex shader|fragment shader|linked shaders) uses? (\\d+) samplers");

    private static boolean linkOnce(int program, Program p) {
        for (int sh : p.shaders) {
            Shader s = shaders.get(sh);
            if (s == null || s.result == null) continue;
            if (s.type == VERTEX_SHADER) {
                s.result.locations.forEach((name, loc) -> GL20.glBindAttribLocation(program, loc, name));
            } else {
                s.result.locations.forEach((name, loc) -> EXTGPUShader4.glBindFragDataLocationEXT(program, loc, name));
            }
        }
        GL20.glLinkProgram(program);
        return GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_TRUE;
    }

    /**
     * Links; when the program needs more than the Mac's 16 texture units, moves sampler2D uniforms
     * into the atlas (recompiling the shader that has too many) and links again. When nothing is
     * left to move and the program has the model texture array, it keeps fewer of those
     * ({@link ModelSlots}), down to {@link Glsl#MIN_MODEL_TEXTURES}.
     */
    static void link(int program) {
        Program p = programs.computeIfAbsent(program, k -> new Program());
        p.label = "program " + program;
        p.dumped = false;
        p.planVersion = -1;
        boolean ok = linkOnce(program, p);
        int kept = ModelSlots.count;
        boolean fullOnly = false, hasModelTextures = false;
        for (int sh : p.shaders) {
            Shader s = shaders.get(sh);
            if (s != null && s.result != null && s.result.modelTextures > 0) hasModelTextures = true;
        }
        for (int attempt = 0; !ok && attempt < 16; attempt++) {
            java.util.regex.Matcher m = OVER_LIMIT.matcher(GL20.glGetProgramInfoLog(program));
            if (!m.find()) break;
            int needed = Integer.parseInt(m.group(2));
            String stage = m.group(1);
            // What the program has packed so far, and what each stage concerned could still pack.
            List<String> packed = new ArrayList<>();
            Map<String, Integer> shared = new java.util.LinkedHashMap<>();
            for (int sh : p.shaders) {
                Shader s = shaders.get(sh);
                if (s == null || s.result == null) continue;
                for (String n : s.result.packed) if (!packed.contains(n)) packed.add(n);
                boolean vertex = s.type == VERTEX_SHADER;
                boolean concerned = stage.equals("linked shaders") || vertex == stage.equals("vertex shader");
                if (!concerned) continue;
                for (String n : s.result.packable) shared.merge(n, 1, Integer::sum);
            }
            // Cheapest first; among equals, names both stages use, which free a unit in each.
            List<String> candidates = new ArrayList<>(shared.keySet());
            candidates.removeAll(packed);
            candidates.sort(java.util.Comparator.comparingInt(Glsl::cost).thenComparing(n -> -shared.get(n)));
            List<String> pack = new ArrayList<>(packed);
            if (candidates.isEmpty()) {
                // Everything that can move into the atlas has. First one sheet instead of two (all
                // at full precision), then one model texture fewer.
                if (!fullOnly && !packed.isEmpty()) fullOnly = true;
                else if (!hasModelTextures || kept <= Glsl.MIN_MODEL_TEXTURES) break;
                else kept--;
            } else {
                // Packing brings in the two atlas sheets: two units more the first time.
                int more = needed - 16 + (packed.isEmpty() ? 2 : 0);
                for (int i = 0; i < candidates.size() && more > 0; i++, more--) pack.add(candidates.get(i));
            }
            for (int sh : p.shaders) {
                Shader s = shaders.get(sh);
                if (s == null || s.result == null) continue;
                List<String> mine = new ArrayList<>();
                for (String n : pack) if (s.result.packable.contains(n)) mine.add(n);
                if (mine.equals(s.result.packed) && (s.result.modelTextures == 0 || s.result.modelTextures == kept)
                    && (mine.isEmpty() || s.result.atlasFullOnly == fullOnly)) continue;
                s.result = Glsl.translate(s.original, s.type == VERTEX_SHADER, mine, kept, fullOnly);
                GL20.glShaderSource(sh, s.result.source);
                GL20.glCompileShader(sh);
                compiled(sh);
            }
            Log.info("program " + program + " needs " + needed + " texture units; " + pack.size() + " textures now share the atlas"
                + (fullOnly ? ", all in the full-float sheet" : "") + (kept < ModelSlots.count ? ", model textures cut to " + kept : ""));
            ok = linkOnce(program, p);
        }
        p.linked = ok;
        p.atlasFullOnly = fullOnly;
        if (ok && kept < ModelSlots.count) ModelSlots.atMost(kept);
        p.samplers.clear();
        p.byLocation.clear();
        p.indexAt.clear();
        p.sizeUniforms.clear();
        p.sizeSent.clear();
        p.rowLocation.clear();
        p.rowSent.clear();
        p.merged.clear();
        p.packed.clear();
        p.rectLocation.clear();
        p.modeLocation.clear();
        p.atlasTextures = null;
        p.fake.clear();
        for (int sh : p.shaders) {
            Shader s = shaders.get(sh);
            if (s == null || s.result == null) continue;
            for (String name : s.result.mergedBuffers) if (!p.merged.contains(name)) p.merged.add(name);
            for (String name : s.result.packed) if (!p.packed.contains(name)) p.packed.add(name);
        }
        p.fake.addAll(p.merged);
        p.fake.addAll(p.packed);
        if (!p.linked) {
            String log = GL20.glGetProgramInfoLog(program);
            Log.warn("a program failed to link on the Mac: " + log.lines().filter(l -> l.contains("ERROR")).findFirst().orElse(log));
            saveFailed("program-" + program, log, p.shaders);
            return;
        }
        if (!p.packed.isEmpty()) {
            Log.info(p.label + " shares the atlas: " + p.packed);
            // For tools/LinkReal and offline packing checks: the sources as the game built them.
            save("packed-shaders", "program-" + program, "packs " + p.packed, p.shaders);
        }
        inspect(program, p);
        inspectRows(program, p);
        java.util.Map<Integer, String> tableInputs = new java.util.TreeMap<>();
        for (int sh : p.shaders) {
            Shader s = shaders.get(sh);
            if (s == null || s.result == null || s.type != VERTEX_SHADER) continue;
            for (java.util.Map.Entry<String, Integer> e : s.result.locations.entrySet()) {
                if (e.getKey().startsWith(Glsl.TABLE_INPUT)) tableInputs.put(e.getValue(), e.getKey().substring(Glsl.TABLE_INPUT.length()));
            }
        }
        p.table = MeshRows.plan(program, tableInputs);
        if (p.merged.contains(Glsl.TABLE)) p.mergedUnit.put(Glsl.TABLE, MeshRows.UNIT);
        if (currentId == program) current = p;
    }

    private static void inspect(int program, Program p) {
        java.util.Set<String> buffers = new java.util.HashSet<>();
        java.util.Set<String> sized = new java.util.HashSet<>();
        for (int sh : p.shaders) {
            Shader s = shaders.get(sh);
            if (s == null || s.result == null) continue;
            buffers.addAll(s.result.bufferSamplers);
            sized.addAll(s.result.sizedShadowSamplers);
        }
        int count = GL20.glGetProgrami(program, GL20.GL_ACTIVE_UNIFORMS);
        int highest = -1;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer size = stack.mallocInt(1), type = stack.mallocInt(1);
            for (int i = 0; i < count; i++) {
                String name = GL20.glGetActiveUniform(program, i, 256, size, type);
                String stem = name.endsWith("[0]") ? name.substring(0, name.length() - 3) : name;
                int n = size.get(0);
                highest = Math.max(highest, GL20.glGetUniformLocation(program, n > 1 ? stem + "[" + (n - 1) + "]" : name));
                highest = Math.max(highest, GL20.glGetUniformLocation(program, name));
                int target = samplerTarget(type.get(0));
                if (target == 0) continue;
                String base = name.endsWith("[0]") ? name.substring(0, name.length() - 3) : name;
                boolean buffer = buffers.contains(base);
                List<Sampler> run = new ArrayList<>();
                for (int k = 0; k < size.get(0); k++) {
                    String elem = size.get(0) > 1 || name.endsWith("[0]") ? base + "[" + k + "]" : base;
                    int loc = GL20.glGetUniformLocation(program, elem);
                    if (loc < 0) continue;
                    Sampler smp = new Sampler();
                    smp.name = elem;
                    smp.location = loc;
                    smp.realTarget = target;
                    smp.virtualTarget = buffer ? G.T_BUFFER : target;
                    smp.kind = samplerKind(type.get(0));
                    smp.unit = 0;
                    smp.realUnit = 0;
                    run.add(smp);
                    p.samplers.add(smp);
                }
                for (int k = 0; k < run.size(); k++) {
                    p.byLocation.put(run.get(k).location, run);
                    p.indexAt.put(run.get(k).location, k);
                }
                if (base.equals(Glsl.MERGED) && !run.isEmpty()) {
                    run.get(0).unit = Merged.UNIT;
                    run.get(0).virtualTarget = G.T_2D;
                }
                if (base.equals(Glsl.ATLAS_FULL) && !run.isEmpty()) {
                    run.get(0).unit = Atlas.UNIT_FULL;
                    run.get(0).virtualTarget = G.T_2D;
                }
                if (base.equals(Glsl.ATLAS) && !run.isEmpty()) {
                    run.get(0).unit = Atlas.UNIT;
                    run.get(0).virtualTarget = G.T_2D;
                }
                if (sized.contains(base) && !run.isEmpty()) {
                    int loc = GL20.glGetUniformLocation(program, Glsl.SIZE_PREFIX + base);
                    if (loc >= 0) p.sizeUniforms.put(run.get(0), loc);
                }
            }
        }
        p.fakeBase = highest + 1;
    }

    static void inspectRows(int program, Program p) {
        for (String name : p.merged) {
            int loc = GL20.glGetUniformLocation(program, Glsl.ROW_PREFIX + name);
            if (loc >= 0) p.rowLocation.put(name, loc);
        }
        for (String name : p.packed) {
            int r = GL20.glGetUniformLocation(program, Glsl.RECT_PREFIX + name);
            int m = GL20.glGetUniformLocation(program, Glsl.MODE_PREFIX + name);
            if (r >= 0) p.rectLocation.put(name, r);
            if (m >= 0) p.modeLocation.put(name, m);
        }
    }

    /** A made-up location for a folded buffer sampler, or -2 when {@code name} is an ordinary uniform. */
    static int fakeLocation(int program, String name) {
        Program p = programs.get(program);
        if (p == null) return -2;
        int i = p.fake.indexOf(name);
        return i < 0 ? -2 : p.fakeBase + i;
    }

    private static int samplerKind(int type) {
        switch (type) {
            case 0x8DC9: case 0x8DCA: case 0x8DCB: case 0x8DCC: case 0x8DCE: case 0x8DCF: return 1;
            case 0x8DD1: case 0x8DD2: case 0x8DD3: case 0x8DD4: case 0x8DD6: case 0x8DD7: return 2;
            case 0x8B61: case 0x8B62: case 0x8DC3: case 0x8DC4: case 0x8DC5: return 3;
            default: return 0;
        }
    }

    private static int samplerTarget(int type) {
        switch (type) {
            case 0x8B5D: return G.T_1D;
            case 0x8B5E: case 0x8B62: case 0x8DCA: case 0x8DD2: return G.T_2D;        // sampler2D, Shadow, isampler2D, usampler2D
            case 0x8B5F: case 0x8DCB: case 0x8DD3: return G.T_3D;
            case 0x8B60: case 0x8DC5: case 0x8DCC: case 0x8DD4: return G.T_CUBE;
            case 0x8DC1: case 0x8DC4: case 0x8DCF: case 0x8DD7: return G.T_2D_ARRAY;   // 2DArray, 2DArrayShadow, i/u
            case 0x8B63: case 0x8B64: return G.T_RECT;
            default: return 0;
        }
    }

    /** Records sampler units instead of setting them; true when {@code location} was a sampler. */
    static boolean samplerUniform(int location, int[] values) {
        if (current == null) return false;
        if (location >= current.fakeBase && location < current.fakeBase + current.fake.size()) {
            current.mergedUnit.put(current.fake.get(location - current.fakeBase), values[0]);
            return true;
        }
        List<Sampler> run = current.byLocation.get(location);
        if (run == null) return false;
        int from = current.indexAt.get(location);
        for (int i = 0; from + i < run.size() && i < values.length; i++) {
            Sampler s = run.get(from + i);
            if (s.unit == values[i]) continue;
            s.unit = values[i];
            current.unitsVersion++;
        }
        return true;
    }

    static int samplerValue(int program, int location) {
        Program p = programs.get(program);
        if (p == null) return -1;
        if (location >= p.fakeBase && location < p.fakeBase + p.fake.size()) {
            Integer u = p.mergedUnit.get(p.fake.get(location - p.fakeBase));
            return u == null ? 0 : u;
        }
        List<Sampler> run = p.byLocation.get(location);
        return run == null ? -1 : run.get(p.indexAt.get(location)).unit;
    }
}
