package vpmac;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.system.JNI;

import java.util.HashSet;
import java.util.Set;

/**
 * Buffers Viewpoint streams per-frame data into, written without the Mac's implicit sync.
 *
 * <p>A glBufferSubData into a buffer the GPU still reads makes the Mac's driver stop and hand its
 * queued work to the GPU first: 67 µs a write in tools/GlProbe, against 4.6 µs once the buffer is
 * marked not serialized ({@code APPLE_flush_buffer_range}). Viewpoint writes about 23 times a
 * frame into {@code FrameStream}, a ring that only ever writes past what the GPU may still read
 * (it holds at least four frames), which is exactly what that mark is for. FrameStream is the
 * only writer through {@code GL_COPY_WRITE_BUFFER}, so that target is what marks a stream.
 */
final class Streams {
    private Streams() {}

    static final int COPY_WRITE_BUFFER = 0x8F37;
    private static final int SERIALIZED_MODIFY = 0x8A12;
    private static final Set<Integer> marked = new HashSet<>();
    private static long parameteri = -1;

    /** Before a write through {@code target} into {@code buffer}: a stream gets its mark once. */
    static void prepare(int target, int buffer) {
        if (target != COPY_WRITE_BUFFER || buffer == 0 || marked.contains(buffer)) return;
        marked.add(buffer);
        if (parameteri == -1) parameteri = GL.getFunctionProvider().getFunctionAddress("glBufferParameteriAPPLE");
        if (parameteri == 0) return;
        int prev = GL11.glGetInteger(G.ARRAY_BUFFER_BINDING);
        GL15.glBindBuffer(G.ARRAY_BUFFER, buffer);
        JNI.invokeV(G.ARRAY_BUFFER, SERIALIZED_MODIFY, GL11.GL_FALSE, parameteri);
        GL15.glBindBuffer(G.ARRAY_BUFFER, prev);
        Log.info("buffer " + buffer + " is a stream: written without waiting for the GPU");
    }

    static void forget(int buffer) {
        marked.remove(buffer);
    }
}
