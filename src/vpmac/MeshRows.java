package vpmac;

import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-draw vec4 data read from a table instead of instanced arrays, so a command changes one
 * value instead of several.
 *
 * <p>Viewpoint's world meshes draw as indirect lists in which every command reads three vec4 of
 * per-draw data ({@code aMesh}, {@code aMeshFade}, {@code aMeshBelow}), a 48-byte record per draw.
 * On the Mac each command then costs the driver three attribute changes, about 1.4 µs, and on a
 * street these lists come to about 155,000 commands a second. {@link Glsl} lets a vertex shader
 * read such inputs from a buffer texture over the record buffer ({@link Glsl#TABLE}, folded into
 * {@link Merged}'s texture like Viewpoint's own buffer textures), at the record one attribute gives
 * ({@link Glsl#RECORD}). A single-instance list whose per-instance attributes all come from one
 * record aligned to 16 bytes draws that way: the arrays off, and before each command the record's
 * first texel as one constant. Other draws with the same program read the attributes as before
 * ({@link Glsl#TABLE_ON} 0); that uniform changes only when the way of reading does.
 *
 * <p>{@code mesh-table:0} in dump.txt keeps every list on the attributes; {@code mesh-table:1}
 * goes back.
 */
final class MeshRows {
    private MeshRows() {}

    /** The virtual unit the table sits on: next to the atlas sheets, past every unit Viewpoint and add-ons use. */
    static final int UNIT = G.VIRTUAL_UNITS - 4;
    static final int RGBA32F = 0x8814;
    static volatile boolean on = true;

    /** A program that can read from the table: its uniforms, which locations it reads at all, and what it was last sent. */
    static final class Plan {
        int onLocation, sentOn;
        final int[] atLocation = new int[Vao.ATTRIBS], sentAt = new int[Vao.ATTRIBS];
        final boolean[] active = new boolean[Vao.ATTRIBS];
    }

    /** The table for each record buffer: a buffer texture over all of it. */
    private static final Map<Integer, Integer> tables = new HashMap<>();
    /** The list being drawn from the table: its per-instance attributes, each one's texel in the record, and the records' place. */
    private static final int[] index = new int[Vao.ATTRIBS], texel = new int[Vao.ATTRIBS];
    private static int count, recordBase, recordStep;

    /**
     * After linking: the plan for a program whose vertex shader {@link Glsl} gave table reads,
     * {@code inputs} being their locations and original names; null when it reads none of them.
     */
    static Plan plan(int program, Map<Integer, String> inputs) {
        if (inputs.isEmpty()) return null;
        int onLocation = GL20.glGetUniformLocation(program, Glsl.TABLE_ON);
        if (onLocation < 0) return null;
        Plan r = new Plan();
        r.onLocation = onLocation;
        Arrays.fill(r.atLocation, -1);
        Arrays.fill(r.sentAt, -1);
        for (Map.Entry<Integer, String> e : inputs.entrySet()) {
            if (e.getKey() < Vao.ATTRIBS) r.atLocation[e.getKey()] = GL20.glGetUniformLocation(program, Glsl.TABLE_AT + e.getValue());
        }
        try (MemoryStack st = MemoryStack.stackPush()) {
            IntBuffer size = st.mallocInt(1), type = st.mallocInt(1);
            int n = GL20.glGetProgrami(program, GL20.GL_ACTIVE_ATTRIBUTES);
            for (int i = 0; i < n; i++) {
                String name = GL20.glGetActiveAttrib(program, i, size, type);
                int at = GL20.glGetAttribLocation(program, name);
                int slots = columns(type.get(0)) * Math.max(1, size.get(0));
                for (int k = 0; k < slots && at >= 0 && at + k < Vao.ATTRIBS; k++) r.active[at + k] = true;
            }
        }
        return r;
    }

    private static int columns(int type) {
        switch (type) {
            case 0x8B5A: case 0x8B65: case 0x8B66: return 2;  // mat2, mat2x3, mat2x4
            case 0x8B5B: case 0x8B67: case 0x8B68: return 3;  // mat3, mat3x2, mat3x4
            case 0x8B5C: case 0x8B69: case 0x8B6A: return 4;  // mat4, mat4x2, mat4x3
            default: return 1;
        }
    }

    /**
     * Before an indirect list sets up its draw: whether its per-draw data can come from the
     * table. Every per-instance attribute must sit in one record of the bound vertex array, at a
     * 16-byte step, and the program must read it through the table or not at all; then the
     * record buffer's table goes on {@link #UNIT}.
     */
    static boolean prepare() {
        count = 0;
        Prog.Program p = Prog.current;
        Vao.Obj o = Vao.current;
        if (!on || p == null || p.table == null || o == null || o.attrs[Glsl.RECORD_LOCATION].enabled) return false;
        Vao.Binding record = null;
        int n = 0;
        boolean reads = false;
        for (int i = 0; i < Vao.ATTRIBS; i++) {
            Vao.Attr a = o.attrs[i];
            if (!a.enabled) continue;
            Vao.Binding b = o.bindings[a.binding];
            if (b.divisor <= 0) continue;
            if (record == null) record = b;
            else if (b != record) return false;
            boolean viaTable = p.table.atLocation[i] >= 0;
            if (!viaTable && p.table.active[i]) return false;
            if (viaTable && (a.integer || a.type != Formats.FLOAT || a.size != 4 || a.relOffset % 16 != 0)) return false;
            index[n] = i;
            texel[n] = (int) (a.relOffset / 16);
            n++;
            reads |= viaTable;
        }
        if (!reads || record.stride <= 0 || record.stride % 16 != 0 || record.offset % 16 != 0) return false;
        count = n;
        recordBase = (int) (record.offset / 16);
        recordStep = record.stride / 16;
        G.vTex[UNIT][G.targetIndex(G.T_BUFFER)] = table(record.buffer);
        return true;
    }

    /** The list draws from the table: the program told so, and the per-instance arrays off. */
    static void begin() {
        Plan r = Prog.current.table;
        if (r.sentOn != 1) {
            GL20.glUniform1i(r.onLocation, 1);
            r.sentOn = 1;
            Stats.uniforms++;
        }
        for (int k = 0; k < count; k++) {
            int l = index[k], at = r.atLocation[l];
            if (at >= 0 && r.sentAt[l] != texel[k]) {
                GL20.glUniform1i(at, texel[k]);
                r.sentAt[l] = texel[k];
                Stats.uniforms++;
            }
            GL20.glDisableVertexAttribArray(l);
        }
    }

    /** Before a command: its record's first texel (exact as a float up to 2^24 texels, 256 MB). */
    static void set(int baseInstance) {
        GL20.glVertexAttrib1f(Glsl.RECORD_LOCATION, (float) (recordBase + (long) baseInstance * recordStep));
        Stats.tableRecords++;
    }

    /** After the list: the arrays back on, and the record attribute back to what GL starts with. */
    static void end() {
        for (int k = 0; k < count; k++) GL20.glEnableVertexAttribArray(index[k]);
        GL20.glVertexAttrib4f(Glsl.RECORD_LOCATION, 0, 0, 0, 1);
        count = 0;
    }

    /** Before any other draw: a program left reading the table goes back to its attributes. */
    static void plain() {
        Prog.Program p = Prog.current;
        if (p == null || p.table == null || p.table.sentOn == 0) return;
        GL20.glUniform1i(p.table.onLocation, 0);
        p.table.sentOn = 0;
        Stats.uniforms++;
    }

    private static int table(int buffer) {
        Integer t = tables.get(buffer);
        if (t == null) {
            t = G.glCreateTextures(G.T_BUFFER);
            Tex.attachBuffer(t, RGBA32F, buffer, 0, -1);
            tables.put(buffer, t);
        }
        return t;
    }

    /** The buffer is gone: so is its table. */
    static void forget(int buffer) {
        Integer t = tables.remove(buffer);
        if (t != null) G.glDeleteTextures(t);
    }
}
