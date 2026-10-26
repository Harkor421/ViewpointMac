package vpmac;

import org.lwjgl.opengl.ARBDrawInstanced;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import java.util.Arrays;
import java.util.Map;

/**
 * Right before each draw, turns the current program's virtual sampler units into real ones:
 * the textures land on the 16 units the Mac has, the sampler uniforms point at them, sampler
 * objects and buffer textures are brought in. After the draw, borrowed units get their textures back.
 */
final class Draw {
    private Draw() {}

    private static final int[] borrowedTarget = new int[G.REAL_UNITS];
    private static final int[] borrowedPrev = new int[G.REAL_UNITS];
    private static int borrowed; // bit per real unit
    private static int activeBefore;
    private static boolean warnedUnits;

    static boolean begin() {
        Prog.Program p = Prog.current;
        borrowed = 0;
        OutsideBinds.settle();
        Dump.poll();
        if (Spin.nanos > 0) Spin.now();
        Stats.draws++;
        GameDraws.viewpointDrew = true;
        if (p == null) return true;
        if (!p.linked) return false;
        if (Dump.busy) Dump.samplers(p);
        activeBefore = GL11.glGetInteger(G.ACTIVE_TEXTURE);
        if (!p.merged.isEmpty()) Merged.sync(p);
        if (!p.packed.isEmpty()) Atlas.sync(p);
        if (p.planVersion != p.unitsVersion) plan(p);

        for (int k = 0; k < p.planKeys; k++) {
            int pu = p.planPhysical[k];
            if (pu < 0) continue;
            int v = p.planUnit[k], real = p.planTarget[k], first = p.planFirst[k];
            Prog.Sampler s = p.samplers.get(first);
            int vt = s.virtualTarget;
            int texture = G.vTex[v][G.targetIndex(vt)];
            if (texture != 0 && vt == G.T_2D) Tex.refreshView(texture);
            GL13.glActiveTexture(G.TEXTURE0 + pu);
            if (pu != v && (borrowed & (1 << pu)) == 0) {
                borrowed |= 1 << pu;
                borrowedTarget[pu] = real;
                borrowedPrev[pu] = GL11.glGetInteger(G.BINDINGS[G.targetIndex(real)]);
            }
            boolean empty = texture == 0;
            GL11.glBindTexture(real, texture);
            Stats.binds++;
            if (!empty) {
                if (vt == G.T_BUFFER) empty = !Tex.syncBuffer(texture);
                else Smp.apply(texture, real, G.vSampler[v]);
            }
            // The Mac's driver crashes drawing with a sampler on a unit that holds no texture
            // (Windows reads black): give it a 1x1 one of the right kind.
            if (empty) GL11.glBindTexture(real, dummy(real, s.kind));
            if (!p.dumped) describe(p, k, first, v, pu, real, empty ? -1 : texture);
        }
        p.dumped = true;
        for (int i = 0; i < p.samplers.size(); i++) {
            Prog.Sampler s = p.samplers.get(i);
            int pu = p.planSamplerUnit[i];
            if (s.realUnit != pu) {
                GL20.glUniform1i(s.location, pu);
                s.realUnit = pu;
                Stats.uniforms++;
            }
        }

        if (!p.sizeUniforms.isEmpty()) {
            for (Map.Entry<Prog.Sampler, Integer> e : p.sizeUniforms.entrySet()) {
                Prog.Sampler s = e.getKey();
                Tex.Info ti = Tex.peek(G.vTex[clampUnit(s.unit)][G.targetIndex(s.virtualTarget)]);
                int[] size = ti == null ? new int[] {1, 1, 1} : new int[] {Math.max(1, ti.width), Math.max(1, ti.height), Math.max(1, ti.depth)};
                int[] sent = p.sizeSent.get(s);
                if (sent != null && Arrays.equals(sent, size)) continue;
                if (s.realTarget == G.T_2D_ARRAY) GL20.glUniform3i(e.getValue(), size[0], size[1], size[2]);
                else GL20.glUniform2i(e.getValue(), size[0], size[1]);
                p.sizeSent.put(s, size);
            }
        }
        GL13.glActiveTexture(activeBefore);
        if (Dump.busy) Dump.checkBefore();
        return true;
    }

    /**
     * Works out, once per set of virtual units, which real unit each sampler gets: one real unit
     * per (virtual unit, target, kind) the program samples, so no unit ever holds two kinds of
     * texture for one draw. Units under 16 stay where they are when they can.
     */
    static void plan(Prog.Program p) {
        int n = p.samplers.size();
        int[] keyUnit = new int[n], keyTarget = new int[n], keyPhysical = new int[n], keyFirst = new int[n];
        int keys = 0;
        int[] samplerKey = new int[n];
        for (int i = 0; i < n; i++) {
            Prog.Sampler s = p.samplers.get(i);
            int v = clampUnit(s.unit), t = s.realTarget;
            int k = 0;
            while (k < keys && !(keyUnit[k] == v && keyTarget[k] == t && sameKind(p, i, k, samplerKey))) k++;
            if (k == keys) {
                keyUnit[k] = v;
                keyTarget[k] = t;
                keyFirst[k] = i;
                keys++;
            }
            samplerKey[i] = k;
        }
        int[] claimedBy = new int[G.REAL_UNITS];
        Arrays.fill(claimedBy, -1);
        for (int k = 0; k < keys; k++) {
            keyPhysical[k] = -1;
            int v = keyUnit[k];
            if (v < G.REAL_UNITS && claimedBy[v] < 0) {
                claimedBy[v] = k;
                keyPhysical[k] = v;
            }
        }
        int next = 0;
        for (int k = 0; k < keys; k++) {
            if (keyPhysical[k] >= 0) continue;
            while (next < G.REAL_UNITS && claimedBy[next] >= 0) next++;
            if (next >= G.REAL_UNITS) {
                if (!warnedUnits) {
                    warnedUnits = true;
                    Log.warn("a program needs more than 16 texture units; some of its textures are left out");
                }
                keyPhysical[k] = -1;
                continue;
            }
            claimedBy[next] = k;
            keyPhysical[k] = next++;
        }
        int[] samplerUnit = new int[n];
        for (int i = 0; i < n; i++) {
            int pu = keyPhysical[samplerKey[i]];
            samplerUnit[i] = pu >= 0 ? pu : spareUnit(claimedBy);
        }
        p.planUnit = keyUnit;
        p.planTarget = keyTarget;
        p.planPhysical = keyPhysical;
        p.planFirst = keyFirst;
        p.planSamplerUnit = samplerUnit;
        p.planKeys = keys;
        p.planVersion = p.unitsVersion;
    }

    static void end() {
        if (FinalProgram.on) FinalProgram.saw(Prog.currentId, Fbo.drawFbo);
        if (Dump.busy) Dump.checkAfter();
        Fbo.touch();
        if (Dump.busy) Dump.afterDraw();
        if (borrowed == 0) return;
        for (int u = 0; u < G.REAL_UNITS; u++) {
            if ((borrowed & (1 << u)) == 0) continue;
            GL13.glActiveTexture(G.TEXTURE0 + u);
            GL11.glBindTexture(borrowedTarget[u], borrowedPrev[u]);
        }
        GL13.glActiveTexture(activeBefore);
        borrowed = 0;
    }

    private static final java.util.Map<Integer, Integer> dummies = new java.util.HashMap<>();

    /** A 1x1 texture (1x1x1, 6 faces) of {@code target} for samplers of {@code kind} (0 float, 1 int, 2 uint, 3 shadow). */
    static int dummy(int target, int kind) {
        return dummies.computeIfAbsent(target * 4 + kind, k -> {
            int t = GL11.glGenTextures();
            int ifmt = kind == 1 ? 0x8D8E : kind == 2 ? 0x8D7C : kind == 3 ? 0x81A6 : 0x8058; // RGBA8I, RGBA8UI, DEPTH24, RGBA8
            Tex.with(target, t, () -> {
                int[] ft = Formats.formatType(ifmt);
                switch (target) {
                    case G.T_3D: case G.T_2D_ARRAY:
                        org.lwjgl.opengl.GL12.glTexImage3D(target, 0, ifmt, 1, 1, 1, 0, ft[0], ft[1], (java.nio.ByteBuffer) null);
                        break;
                    case G.T_CUBE:
                        for (int f = 0; f < 6; f++) GL11.glTexImage2D(0x8515 + f, 0, ifmt, 1, 1, 0, ft[0], ft[1], (java.nio.ByteBuffer) null);
                        break;
                    case G.T_1D:
                        GL11.glTexImage1D(target, 0, ifmt, 1, 0, ft[0], ft[1], (java.nio.ByteBuffer) null);
                        break;
                    default:
                        GL11.glTexImage2D(target, 0, ifmt, 1, 1, 0, ft[0], ft[1], (java.nio.ByteBuffer) null);
                }
                GL11.glTexParameteri(target, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(target, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(target, org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LEVEL, 0);
                if (kind == 3) GL11.glTexParameteri(target, 0x884C, 0x884E); // COMPARE_REF_TO_TEXTURE
            });
            return t;
        });
    }

    /** One line per sampler the first time a program draws: what it gets, for reading crash logs. */
    private static void describe(Prog.Program p, int key, int first, int v, int pu, int target, int texture) {
        Prog.Sampler s = p.samplers.get(first);
        Tex.Info ti = texture > 0 ? Tex.peek(texture) : null;
        Log.info("draw " + p.label + ": " + s.name + " unit " + v + "->" + pu + " target 0x" + Integer.toHexString(target)
            + " kind " + s.kind + " texture " + (texture < 0 ? "none (1x1 stand-in)" : texture
            + (ti == null ? " (not seen)" : " " + ti.width + "x" + ti.height + "x" + ti.depth + " ifmt 0x" + Integer.toHexString(ti.ifmt)
               + (ti.buffer != 0 ? " buffer " + ti.buffer + " rows " + ti.rows : ""))));
    }

    /** Integer and float samplers of one target still need units of their own. */
    private static boolean sameKind(Prog.Program p, int i, int k, int[] samplerKey) {
        for (int j = 0; j < i; j++) {
            if (samplerKey[j] == k) return p.samplers.get(j).kind == p.samplers.get(i).kind;
        }
        return true;
    }

    /** A unit for a sampler left without one: the last unit nothing claimed, else 15. */
    private static int spareUnit(int[] claimedBy) {
        for (int u = G.REAL_UNITS - 1; u >= 0; u--) if (claimedBy[u] < 0) return u;
        return G.REAL_UNITS - 1;
    }

    private static int clampUnit(int u) {
        return Math.max(0, Math.min(G.VIRTUAL_UNITS - 1, u));
    }

    // ---- indirect draws (4.3) from the indirect buffer's copy --------------------------------

    /** The base instance the per-instance pointers are moved to, inside an indirect draw. */
    private static int shifted;

    static void multiElements(int mode, int type, long indirect, int drawCount, int stride) {
        Buf.Info cmds = Buf.peek(Buf.bound(G.DRAW_INDIRECT_BUFFER));
        if (cmds == null || cmds.data == null) {
            Log.once("indirectMissing", "an indirect draw had no command buffer copy; skipped");
            return;
        }
        int indexBytes = type == GL11.GL_UNSIGNED_INT ? 4 : type == GL11.GL_UNSIGNED_SHORT ? 2 : 1;
        // Indices are unknown here; a stand-in attribute 0, if one is needed, covers every
        // 16-bit index past the furthest base vertex.
        int most = 0;
        boolean single = true;
        for (int i = 0; i < drawCount; i++) {
            long at = indirect + (long) i * stride;
            if (at + 20 > cmds.size) break;
            most = Math.max(most, Buf.intAt(cmds, at + 12));
            single &= Buf.intAt(cmds, at + 4) <= 1;
        }
        // Before the units are set up: a list reading its per-draw data from the table needs it on its unit.
        boolean table = single && MeshRows.prepare();
        if (!begin()) return;
        boolean constant = false;
        try {
            NoAttribs.before(most + (1 << 16));
            Vao.beginRun();
            shifted = 0;
            // Single-instance commands draw plainly, their per-draw data from the table (MeshRows)
            // or set as constants (PerDraw).
            constant = !table && single && PerDraw.begin();
            if (table) MeshRows.begin();
            else MeshRows.plain();
            // Per-instance data from the table or set as constants comes from the CPU copy, not from GL.
            StreamWrites.beforeDraw(!constant && !table);
            int set = -1;
            for (int i = 0; i < drawCount; i++) {
                long at = indirect + (long) i * stride;
                if (at + 20 > cmds.size) break;
                int count = Buf.intAt(cmds, at), instances = Buf.intAt(cmds, at + 4), first = Buf.intAt(cmds, at + 8),
                    baseVertex = Buf.intAt(cmds, at + 12), baseInstance = Buf.intAt(cmds, at + 16);
                if (count <= 0 || instances <= 0) continue;
                if (table) {
                    if (baseInstance != set) MeshRows.set(set = baseInstance);
                } else if (constant) {
                    if (baseInstance != set) PerDraw.set(set = baseInstance);
                } else if (baseInstance != shifted) {
                    Vao.shiftInstances(shifted = baseInstance);
                }
                G.elements(mode, count, type, (long) first * indexBytes, instances, baseVertex);
                Stats.commands++;
            }
        } finally {
            // Even when a command throws: put the instance arrays, the array buffer and attribute 0 back.
            if (table) MeshRows.end();
            if (constant) PerDraw.end();
            if (shifted != 0) Vao.shiftInstances(0);
            shifted = 0;
            Vao.endRun();
            NoAttribs.after();
            end();
        }
    }

    static void multiArrays(int mode, long indirect, int drawCount, int stride) {
        Buf.Info cmds = Buf.peek(Buf.bound(G.DRAW_INDIRECT_BUFFER));
        if (cmds == null || cmds.data == null) {
            Log.once("indirectMissing", "an indirect draw had no command buffer copy; skipped");
            return;
        }
        int most = 0;
        boolean single = true;
        for (int i = 0; i < drawCount; i++) {
            long at = indirect + (long) i * stride;
            if (at + 16 > cmds.size) break;
            most = Math.max(most, Buf.intAt(cmds, at + 8) + Buf.intAt(cmds, at));
            single &= Buf.intAt(cmds, at + 4) <= 1;
        }
        // Before the units are set up: a list reading its per-draw data from the table needs it on its unit.
        boolean table = single && MeshRows.prepare();
        if (!begin()) return;
        if (Census.on) Census.commands(cmds, indirect, drawCount, stride);
        boolean constant = false;
        try {
            NoAttribs.before(most);
            Vao.beginRun();
            shifted = 0;
            // Single-instance commands draw plainly, their per-draw data from the table (MeshRows)
            // or set as constants (PerDraw).
            constant = !table && single && PerDraw.begin();
            if (table) MeshRows.begin();
            else MeshRows.plain();
            // Per-instance data from the table or set as constants comes from the CPU copy, not from GL.
            StreamWrites.beforeDraw(!constant && !table);
            int set = -1;
            for (int i = 0; i < drawCount; i++) {
                long at = indirect + (long) i * stride;
                if (at + 16 > cmds.size) break;
                int count = Buf.intAt(cmds, at), instances = Buf.intAt(cmds, at + 4), first = Buf.intAt(cmds, at + 8),
                    baseInstance = Buf.intAt(cmds, at + 12);
                if (count <= 0 || instances <= 0) continue;
                // Consecutive commands often share a base instance: only move the data when it changes.
                if (table) {
                    if (baseInstance != set) MeshRows.set(set = baseInstance);
                    GL11.glDrawArrays(mode, first, count);
                } else if (constant) {
                    if (baseInstance != set) PerDraw.set(set = baseInstance);
                    GL11.glDrawArrays(mode, first, count);
                } else {
                    if (baseInstance != shifted) Vao.shiftInstances(shifted = baseInstance);
                    ARBDrawInstanced.glDrawArraysInstancedARB(mode, first, count, instances);
                }
                Stats.commands++;
            }
        } finally {
            // Even when a command throws: put the instance arrays, the array buffer and attribute 0 back.
            if (table) MeshRows.end();
            if (constant) PerDraw.end();
            if (shifted != 0) Vao.shiftInstances(0);
            shifted = 0;
            Vao.endRun();
            NoAttribs.after();
            end();
        }
    }
}
