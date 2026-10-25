package vpmac;

import org.lwjgl.opengl.ARBInstancedArrays;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Legacy GL draws nothing unless attribute 0 (or the vertex array) is enabled: it is what makes a
 * vertex. Viewpoint's full-screen passes build their triangle from gl_VertexID alone, so such draws
 * get a stand-in attribute 0 — every vertex (0, 0, 0, 1), what a disabled attribute reads anyway —
 * and attribute 0 goes back to how it was right after.
 */
final class NoAttribs {
    private NoAttribs() {}

    private static int buffer, capacity;
    private static boolean lent;
    private static int savedBuffer, savedSize, savedType, savedStride, savedDivisor;
    private static boolean savedNormalized;
    private static long savedPointer;

    /** Before a draw that reads vertices 0 to {@code vertices} - 1. */
    static void before(int vertices) {
        lent = false;
        // Viewpoint's own vertex array says so without asking GL.
        if (Vao.current != null && Vao.current.attrs[0].enabled) return;
        if (GL20.glGetVertexAttribi(0, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED) != 0 || GL11.glIsEnabled(GL11.GL_VERTEX_ARRAY)) return;
        savedBuffer = GL20.glGetVertexAttribi(0, GL15.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING);
        savedSize = GL20.glGetVertexAttribi(0, GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE);
        savedType = GL20.glGetVertexAttribi(0, GL20.GL_VERTEX_ATTRIB_ARRAY_TYPE);
        savedStride = GL20.glGetVertexAttribi(0, GL20.GL_VERTEX_ATTRIB_ARRAY_STRIDE);
        savedNormalized = GL20.glGetVertexAttribi(0, GL20.GL_VERTEX_ATTRIB_ARRAY_NORMALIZED) != 0;
        savedDivisor = GL20.glGetVertexAttribi(0, ARBInstancedArrays.GL_VERTEX_ATTRIB_ARRAY_DIVISOR_ARB);
        savedPointer = GL20.glGetVertexAttribPointer(0, GL20.GL_VERTEX_ATTRIB_ARRAY_POINTER);

        int prev = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        if (buffer == 0) buffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        if (vertices > capacity) {
            capacity = Math.max(vertices, 1 << 16);
            ByteBuffer data = MemoryUtil.memCalloc(capacity * 4);
            for (int i = 0; i < capacity; i++) data.put(i * 4 + 3, (byte) 255);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
            MemoryUtil.memFree(data);
        }
        GL20.glVertexAttribPointer(0, 4, GL11.GL_UNSIGNED_BYTE, true, 4, 0L);
        if (savedDivisor != 0) ARBInstancedArrays.glVertexAttribDivisorARB(0, 0);
        GL20.glEnableVertexAttribArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prev);
        lent = true;
        Log.once("noAttribs:" + Prog.currentId, "program " + Prog.currentId + " draws without vertex arrays; it gets a stand-in attribute 0");
    }

    static void after() {
        if (!lent) return;
        lent = false;
        GL20.glDisableVertexAttribArray(0);
        int prev = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedBuffer);
        GL20.glVertexAttribPointer(0, Math.max(1, savedSize), savedType == 0 ? GL11.GL_FLOAT : savedType, savedNormalized, savedStride, savedPointer);
        if (savedDivisor != 0) ARBInstancedArrays.glVertexAttribDivisorARB(0, savedDivisor);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prev);
    }
}
