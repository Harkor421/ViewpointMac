package vpmac;

import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.Map;

/** Sampler objects (3.3), applied to the texture they sample as texture parameters. */
final class Smp {
    private Smp() {}

    static final class Obj {
        final Map<Integer, Number> params = new HashMap<>();
        int version;
    }

    private static final Map<Integer, Obj> objs = new HashMap<>();
    private static int nextId = 1;

    /** What a texture's parameters are when nothing set them. */
    private static final Map<Integer, Number> DEFAULTS = new HashMap<>();
    static {
        DEFAULTS.put(GL11.GL_TEXTURE_MIN_FILTER, 0x2702);
        DEFAULTS.put(GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        DEFAULTS.put(GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        DEFAULTS.put(GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        DEFAULTS.put(0x8072, GL11.GL_REPEAT);       // WRAP_R
        DEFAULTS.put(0x884C, 0);                    // COMPARE_MODE
        DEFAULTS.put(0x884D, GL11.GL_LEQUAL);       // COMPARE_FUNC
        DEFAULTS.put(0x84FE, 1.0f);                 // MAX_ANISOTROPY
        DEFAULTS.put(0x8501, 0.0f);                 // LOD_BIAS
        DEFAULTS.put(0x813A, -1000.0f);             // MIN_LOD
        DEFAULTS.put(0x813B, 1000.0f);              // MAX_LOD
    }

    static int create() {
        int id = nextId++;
        objs.put(id, new Obj());
        return id;
    }

    static void delete(int id) {
        objs.remove(id);
    }

    static Map<Integer, Number> params(int id) {
        Obj o = id == 0 ? null : objs.get(id);
        return o == null ? Map.of() : o.params;
    }

    static void set(int id, int pname, Number value) {
        Obj o = objs.computeIfAbsent(id, k -> new Obj());
        o.params.put(pname, value);
        o.version++;
    }

    /**
     * Makes {@code texture} (bound on the active unit as {@code target}) sample as sampler
     * {@code id} says, or as its own parameters say when {@code id} is 0.
     */
    static void apply(int texture, int target, int id) {
        Tex.Info ti = Tex.info(texture);
        Obj o = id == 0 ? null : objs.get(id);
        int version = o == null ? 0 : o.version;
        if (ti.appliedSampler == id && ti.appliedSamplerVersion == version) return;
        Map<Integer, Number> want = new HashMap<>(ti.own);
        if (o != null) want.putAll(o.params);
        for (Integer pname : new java.util.HashSet<>(ti.applied.keySet())) {
            if (!want.containsKey(pname) && DEFAULTS.containsKey(pname)) want.put(pname, DEFAULTS.get(pname));
        }
        for (Map.Entry<Integer, Number> e : want.entrySet()) {
            Number have = ti.applied.get(e.getKey());
            if (have != null && have.equals(e.getValue())) continue;
            if (have == null && e.getValue().equals(DEFAULTS.get(e.getKey()))) continue;
            if (e.getValue() instanceof Float) GL11.glTexParameterf(target, e.getKey(), e.getValue().floatValue());
            else GL11.glTexParameteri(target, e.getKey(), e.getValue().intValue());
            ti.applied.put(e.getKey(), e.getValue());
        }
        ti.appliedSampler = id;
        ti.appliedSamplerVersion = version;
    }
}
