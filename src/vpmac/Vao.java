package vpmac;

import org.lwjgl.opengl.EXTGPUShader4;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;

import java.util.HashMap;
import java.util.Map;

/**
 * Vertex array objects and separate attribute formats (4.3), kept in software: the Mac's legacy
 * context has no VAOs LWJGL can call, so binding one replays its attribute pointers.
 */
final class Vao {
    private Vao() {}

    static final int ATTRIBS = 16;

    static final class Attr {
        boolean enabled, normalized, integer;
        int size = 4, type = Formats.FLOAT, binding;
        long relOffset;
    }

    static final class Binding {
        int buffer, stride, divisor;
        long offset;
    }

    static final class Obj {
        final Attr[] attrs = new Attr[ATTRIBS];
        final Binding[] bindings = new Binding[ATTRIBS];
        int elementBuffer;

        Obj() {
            for (int i = 0; i < ATTRIBS; i++) {
                attrs[i] = new Attr();
                attrs[i].binding = i;
                bindings[i] = new Binding();
            }
        }
    }

    private static final Map<Integer, Obj> objs = new HashMap<>();
    private static int nextId = 1;
    static int currentId;
    static Obj current;
    /** Attributes this file turned on or gave a divisor, to turn off again when Viewpoint unbinds. */
    private static int touched;

    static int create() {
        int id = nextId++;
        objs.put(id, new Obj());
        return id;
    }

    static void delete(int id) {
        objs.remove(id);
        if (currentId == id) bind(0);
    }

    static void bind(int id) {
        Obj o = id == 0 ? null : objs.computeIfAbsent(id, k -> new Obj());
        currentId = id;
        current = o;
        if (o == null) {
            for (int i = 0; i < ATTRIBS; i++) {
                if ((touched & (1 << i)) == 0) continue;
                GL20.glDisableVertexAttribArray(i);
                G.divisor(i, 0);
            }
            touched = 0;
            return;
        }
        int prev = GL11.glGetInteger(G.ARRAY_BUFFER_BINDING);
        for (int i = 0; i < ATTRIBS; i++) set(o, i, 0, prev);
        GL15.glBindBuffer(G.ARRAY_BUFFER, prev);
        GL15.glBindBuffer(G.ELEMENT_ARRAY_BUFFER, o.elementBuffer);
        Stats.vaoBinds++;
    }

    static void elementBuffer(int buffer) {
        if (current != null) current.elementBuffer = buffer;
    }

    static void enable(int index, boolean on) {
        if (current == null) {
            if (on) GL20.glEnableVertexAttribArray(index);
            else GL20.glDisableVertexAttribArray(index);
            return;
        }
        current.attrs[index].enabled = on;
        apply(current, index, 0);
    }

    static void pointer(int index, int size, int type, boolean normalized, boolean integer, int stride, long pointer) {
        if (current == null) {
            if (integer) EXTGPUShader4.glVertexAttribIPointerEXT(index, size, type, stride, pointer);
            else GL20.glVertexAttribPointer(index, size, type, normalized, stride, pointer);
            return;
        }
        Attr a = current.attrs[index];
        a.size = size;
        a.type = type;
        a.normalized = normalized;
        a.integer = integer;
        a.relOffset = pointer;
        a.binding = index;
        Binding b = current.bindings[index];
        b.buffer = Buf.bound(G.ARRAY_BUFFER);
        b.offset = 0;
        b.stride = stride;
        apply(current, index, 0);
    }

    static void divisor(int index, int divisor) {
        if (current == null) {
            G.divisor(index, divisor);
            return;
        }
        current.attrs[index].binding = index;
        current.bindings[index].divisor = divisor;
        apply(current, index, 0);
    }

    static void format(int index, int size, int type, boolean normalized, boolean integer, int relOffset) {
        if (current == null) return;
        Attr a = current.attrs[index];
        a.size = size;
        a.type = type;
        a.normalized = normalized;
        a.integer = integer;
        a.relOffset = relOffset;
        apply(current, index, 0);
    }

    static void attribBinding(int index, int binding) {
        if (current == null) return;
        current.attrs[index].binding = binding;
        apply(current, index, 0);
    }

    static void vertexBuffer(int binding, int buffer, long offset, int stride) {
        if (current == null) return;
        Binding b = current.bindings[binding];
        b.buffer = buffer;
        b.offset = offset;
        b.stride = stride;
        int prev = GL11.glGetInteger(G.ARRAY_BUFFER_BINDING);
        for (int i = 0; i < ATTRIBS; i++) if (current.attrs[i].binding == binding) set(current, i, 0, prev);
        GL15.glBindBuffer(G.ARRAY_BUFFER, prev);
    }

    static void bindingDivisor(int binding, int divisor) {
        if (current == null) return;
        current.bindings[binding].divisor = divisor;
        for (int i = 0; i < ATTRIBS; i++) if (current.attrs[i].binding == binding) apply(current, i, 0);
    }

    /**
     * Moves the per-instance attributes {@code baseInstance} instances on (2.1 has no base
     * instance); 0 puts them back.
     */
    static void shiftInstances(int baseInstance) {
        if (current == null) return;
        boolean run = runPrev >= 0;
        int prev = run ? runPrev : -1, bound = run ? runBound : -1;
        for (int i = 0; i < ATTRIBS; i++) {
            Attr a = current.attrs[i];
            if (!a.enabled) continue;
            Binding b = current.bindings[a.binding];
            if (b.divisor <= 0) continue;
            if (prev < 0) bound = prev = GL11.glGetInteger(G.ARRAY_BUFFER_BINDING);
            if (bound != b.buffer) GL15.glBindBuffer(G.ARRAY_BUFFER, bound = b.buffer);
            pointer(i, a, b, baseInstance);
        }
        if (run) runBound = bound;
        else if (prev >= 0 && bound != prev) GL15.glBindBuffer(G.ARRAY_BUFFER, prev);
    }

    /** Inside one indirect draw: the array buffer bound before it, and the one bound now. */
    private static int runPrev = -1, runBound;

    /** Shifts until {@link #endRun} leave whatever array buffer they need bound, and put back the first one once. */
    static void beginRun() {
        runPrev = runBound = GL11.glGetInteger(G.ARRAY_BUFFER_BINDING);
    }

    static void endRun() {
        if (runPrev >= 0 && runBound != runPrev) GL15.glBindBuffer(G.ARRAY_BUFFER, runPrev);
        runPrev = -1;
    }

    private static int stride(Attr a, Binding b) {
        return b.stride != 0 ? b.stride : a.size * Formats.typeBytes(a.type);
    }

    private static void apply(Obj o, int index, int baseInstance) {
        int prev = GL11.glGetInteger(G.ARRAY_BUFFER_BINDING);
        set(o, index, baseInstance, prev);
        GL15.glBindBuffer(G.ARRAY_BUFFER, prev);
    }

    /**
     * Makes real attribute {@code index} what the object says. It may leave another buffer bound
     * as the array buffer: the caller puts {@code prev} back once, after all its attributes.
     */
    private static void set(Obj o, int index, int baseInstance, int prev) {
        Attr a = o.attrs[index];
        if (!a.enabled) {
            GL20.glDisableVertexAttribArray(index);
            if ((touched & (1 << index)) != 0) G.divisor(index, 0);
            return;
        }
        Binding b = o.bindings[a.binding];
        GL15.glBindBuffer(G.ARRAY_BUFFER, b.buffer);
        pointer(index, a, b, baseInstance);
        G.divisor(index, b.divisor);
        GL20.glEnableVertexAttribArray(index);
        touched |= 1 << index;
    }

    /** The attribute's pointer into the array buffer bound now, moved {@code baseInstance} instances on. */
    private static void pointer(int index, Attr a, Binding b, int baseInstance) {
        long offset = b.offset + a.relOffset;
        // GL reads element floor(instance / divisor) + baseInstance: the base is not divided.
        // (Viewpoint gives per-draw data a divisor of 2^30, so only the base ever moves it.)
        if (baseInstance > 0 && b.divisor > 0) offset += (long) baseInstance * stride(a, b);
        if (a.integer) EXTGPUShader4.glVertexAttribIPointerEXT(index, a.size, a.type, b.stride, offset);
        else GL20.glVertexAttribPointer(index, a.size, a.type, a.normalized, b.stride, offset);
        Stats.attribApplies++;
    }
}
