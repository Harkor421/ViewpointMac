package vpmac;

import org.lwjgl.opengl.EXTGPUShader4;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;

/**
 * A per-instance attribute turned into a constant for a run of single-instance draws.
 *
 * <p>Viewpoint's world comes as indirect lists of single-instance commands (about 280,000 a
 * second), each reading its per-draw data at its base instance. Each command costs the Mac's
 * driver more for the state that changes before it than for the draw itself: measured in the
 * game, moving an instanced array and drawing costs 0.53 to 0.83 µs a command, while setting the
 * attribute's current value and drawing plainly costs 0.36 to 0.66 µs (0.15 to 0.2 µs for a draw
 * with nothing changed). With three per-draw attributes (a matrix) the constants cost more than
 * the array (1.6 against 1.4 µs), so only lists with one per-instance attribute take this path.
 *
 * <p>For such a list the instanced array is switched off, and each command's value is read from
 * the buffer's CPU copy ({@link Buf.Info#data}) and set as a constant: what GL would read for
 * instance 0 at that base instance, whatever the divisor.
 */
final class PerDraw {
    private PerDraw() {}

    /** The run's per-instance attributes: real index, buffer copy, where the first element is, and its format. */
    private static final int[] index = new int[Vao.ATTRIBS], size = new int[Vao.ATTRIBS], type = new int[Vao.ATTRIBS],
        stride = new int[Vao.ATTRIBS];
    private static final long[] offset = new long[Vao.ATTRIBS];
    private static final boolean[] normalized = new boolean[Vao.ATTRIBS], integer = new boolean[Vao.ATTRIBS];
    private static final Buf.Info[] source = new Buf.Info[Vao.ATTRIBS];
    private static int count;

    /**
     * Whether the bound vertex array's per-instance data can be a constant: at most one attribute
     * (see above), not attribute 0 (on a legacy context it is what makes vertices), with a CPU copy
     * the GPU hasn't written past. If so its array is off until {@link #end}.
     */
    static boolean begin() {
        count = 0;
        Vao.Obj o = Vao.current;
        if (o == null) return false;
        int n = 0;
        for (int i = 0; i < Vao.ATTRIBS; i++) {
            Vao.Attr a = o.attrs[i];
            if (!a.enabled) continue;
            Vao.Binding b = o.bindings[a.binding];
            if (b.divisor <= 0) continue;
            Buf.Info info = Buf.peek(b.buffer);
            if (n == 1 || i == 0 || info == null || info.data == null || info.gpuWritten || !readable(a.type) || a.size < 1 || a.size > 4) {
                return false;
            }
            index[n] = i;
            size[n] = a.size;
            type[n] = a.type;
            normalized[n] = a.normalized;
            integer[n] = a.integer;
            stride[n] = b.stride != 0 ? b.stride : a.size * Formats.typeBytes(a.type);
            offset[n] = b.offset + a.relOffset;
            source[n] = info;
            n++;
        }
        count = n;
        for (int k = 0; k < count; k++) GL20.glDisableVertexAttribArray(index[k]);
        return true;
    }

    /** Sets each attribute to its element at {@code baseInstance}. */
    static void set(int baseInstance) {
        for (int k = 0; k < count; k++) {
            ByteBuffer d = source[k].data;
            int bytes = Formats.typeBytes(type[k]);
            long at = offset[k] + (long) baseInstance * stride[k];
            // Past the end GL reads zeros (robust access) or garbage; zeros it is.
            boolean inside = at >= 0 && at + (long) size[k] * bytes <= d.capacity();
            if (integer[k]) {
                int[] v = {0, 0, 0, 1};
                for (int c = 0; c < size[k] && inside; c++) v[c] = intAt(d, (int) at + c * bytes, type[k]);
                if (unsigned(type[k])) EXTGPUShader4.glVertexAttribI4uiEXT(index[k], v[0], v[1], v[2], v[3]);
                else EXTGPUShader4.glVertexAttribI4iEXT(index[k], v[0], v[1], v[2], v[3]);
            } else {
                float[] v = {0, 0, 0, 1};
                for (int c = 0; c < size[k] && inside; c++) v[c] = floatAt(d, (int) at + c * bytes, type[k], normalized[k]);
                GL20.glVertexAttrib4f(index[k], v[0], v[1], v[2], v[3]);
            }
            Stats.attribApplies++;
        }
    }

    /** Turns the arrays back on, and their current values back to what GL starts with. */
    static void end() {
        for (int k = 0; k < count; k++) {
            GL20.glEnableVertexAttribArray(index[k]);
            GL20.glVertexAttrib4f(index[k], 0, 0, 0, 1);
            source[k] = null;
        }
        count = 0;
    }

    private static boolean readable(int t) {
        return t == Formats.FLOAT || t == Formats.HALF || t == Formats.BYTE || t == Formats.UBYTE || t == Formats.SHORT
            || t == Formats.USHORT || t == Formats.INT || t == Formats.UINT;
    }

    private static boolean unsigned(int t) {
        return t == Formats.UBYTE || t == Formats.USHORT || t == Formats.UINT;
    }

    static int intAt(ByteBuffer d, int at, int t) {
        switch (t) {
            case Formats.BYTE: return d.get(at);
            case Formats.UBYTE: return d.get(at) & 0xff;
            case Formats.SHORT: return d.getShort(at);
            case Formats.USHORT: return d.getShort(at) & 0xffff;
            case Formats.FLOAT: return (int) d.getFloat(at);
            case Formats.HALF: return (int) Float.float16ToFloat(d.getShort(at));
            default: return d.getInt(at);
        }
    }

    /** One component as a float attribute reads it: normalized integers to [0, 1] or [-1, 1] (the 4.2 rule). */
    static float floatAt(ByteBuffer d, int at, int t, boolean normalized) {
        switch (t) {
            case Formats.FLOAT: return d.getFloat(at);
            case Formats.HALF: return Float.float16ToFloat(d.getShort(at));
            case Formats.BYTE: return normalized ? Math.max(d.get(at) / 127f, -1f) : d.get(at);
            case Formats.UBYTE: return normalized ? (d.get(at) & 0xff) / 255f : d.get(at) & 0xff;
            case Formats.SHORT: return normalized ? Math.max(d.getShort(at) / 32767f, -1f) : d.getShort(at);
            case Formats.USHORT: return normalized ? (d.getShort(at) & 0xffff) / 65535f : d.getShort(at) & 0xffff;
            case Formats.INT: return normalized ? (float) Math.max(d.getInt(at) / 2147483647.0, -1.0) : d.getInt(at);
            default: return normalized ? (float) ((d.getInt(at) & 0xffffffffL) / 4294967295.0) : d.getInt(at) & 0xffffffffL;
        }
    }
}
