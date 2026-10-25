package vpmac;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The texture that mirrors a program's float buffer textures (see {@link Glsl.Result#mergedBuffers}),
 * one buffer under the other, each buffer whole. Viewpoint points its buffer textures at new
 * ranges of the same buffers batch after batch; a range is only a base texel handed to the
 * shader, so nothing is reallocated or copied for it. Only the bytes written since the last
 * draw are copied, GPU-side.
 */
final class Merged {
    private Merged() {}

    /** The virtual unit the texture sits on; Viewpoint's own units stop well before it. */
    static final int UNIT = G.VIRTUAL_UNITS - 1;

    private static final class Stack {
        int texture, format, capacity;
        /** Per member: rows, the version copied, and which record of the buffer name it was. */
        int[] rows, versions, serials;
    }

    /** By the buffers mirrored, least recently used first. */
    private static final Map<String, Stack> stacks = new LinkedHashMap<>(16, 0.75f, true);

    static void sync(Prog.Program p) {
        List<String> names = p.merged;
        int n = names.size();
        Tex.Info[] members = new Tex.Info[n];
        int[] buffers = new int[n];
        int format = 0;
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < n; i++) {
            Integer unit = p.mergedUnit.get(names.get(i));
            int texture = unit == null ? 0 : G.vTex[unit][G.targetIndex(G.T_BUFFER)];
            members[i] = Tex.peek(texture);
            buffers[i] = members[i] == null ? 0 : members[i].buffer;
            if (format == 0 && members[i] != null && buffers[i] != 0) format = members[i].bufferFormat;
            key.append(buffers[i]).append('|');
        }
        if (format == 0) {
            G.vTex[UNIT][G.targetIndex(G.T_2D)] = 0;
            return;
        }
        key.append(format);
        Stack st = stacks.get(key.toString());
        if (st == null) {
            if (stacks.size() >= 16) {
                Map.Entry<String, Stack> oldest = stacks.entrySet().iterator().next();
                GL11.glDeleteTextures(oldest.getValue().texture);
                stacks.remove(oldest.getKey());
            }
            st = new Stack();
            st.texture = GL11.glGenTextures();
            st.rows = new int[n];
            st.versions = new int[n];
            st.serials = new int[n];
            Arrays.fill(st.versions, -1);
            st.format = format;
            stacks.put(key.toString(), st);
        }

        // Rows for each whole buffer; the texture only ever grows.
        int texel = Formats.texelBytes(format);
        int[] rows = new int[n];
        int total = 0;
        for (int i = 0; i < n; i++) {
            Buf.Info b = buffers[i] == 0 ? null : Buf.peek(buffers[i]);
            if (b == null || b.size <= 0) continue;
            long texels = b.size / texel;
            rows[i] = (int) Math.max(1, (texels + Glsl.BUFFER_WIDTH - 1) / Glsl.BUFFER_WIDTH);
            total += rows[i];
        }
        if (total > Atlas.MAX_SIZE) {
            Log.once("mergedTooBig", "a program's buffer textures need " + total + " rows, more than the Mac's "
                + Atlas.MAX_SIZE + "; it draws without them");
            G.vTex[UNIT][G.targetIndex(G.T_2D)] = 0;
            return;
        }
        final int need = total;
        boolean grow = total > st.capacity;
        boolean moved = !Arrays.equals(rows, st.rows);
        final Stack s = st;
        final int fmt = format;
        int[] ft = Formats.formatType(fmt);
        // Most draws find nothing written since the last one: no texture edit at all then.
        boolean dirty = grow || moved;
        for (int i = 0; i < n; i++) {
            Buf.Info b = buffers[i] == 0 ? null : Buf.peek(buffers[i]);
            if (b == null || rows[i] == 0) continue;
            // A buffer deleted and made again under the same name is a new buffer: copy it whole.
            if (s.serials[i] != b.serial) {
                s.serials[i] = b.serial;
                s.versions[i] = -1;
            }
            dirty |= s.versions[i] != b.version;
        }
        if (dirty) Timers.begin(Timers.BUFFERS);
        if (dirty) Tex.with(G.T_2D, st.texture, () -> {
            if (grow) {
                s.capacity = Math.min(Atlas.MAX_SIZE, Math.max(need, s.capacity + s.capacity / 2));
                Tex.unpacked(() -> {
                    GL11.glTexImage2D(G.T_2D, 0, fmt, Glsl.BUFFER_WIDTH, s.capacity, 0, ft[0], ft[1], (ByteBuffer) null);
                    GL11.glTexParameteri(G.T_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                    GL11.glTexParameteri(G.T_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                    GL11.glTexParameteri(G.T_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
                }, true);
            }
            if (grow || moved) Arrays.fill(s.versions, -1);
            int row = 0;
            for (int i = 0; i < n; i++) {
                Buf.Info b = buffers[i] == 0 ? null : Buf.peek(buffers[i]);
                if (b == null || rows[i] == 0) continue;
                if (members[i].bufferFormat != fmt) Log.once("mergedFormat:" + buffers[i], "buffer " + buffers[i] + " is 0x"
                    + Integer.toHexString(members[i].bufferFormat) + " but shares a 0x" + Integer.toHexString(fmt) + " texture with buffers "
                    + Arrays.toString(buffers));
                long[] range = s.versions[i] < 0 ? new long[] {0, b.size} : Buf.dirtySince(b, s.versions[i]);
                int texels = (int) Math.min((long) rows[i] * Glsl.BUFFER_WIDTH, b.size / texel);
                if (range != null) Tex.uploadRows(buffers[i], 0, texels, fmt, row, range);
                s.versions[i] = b.version;
                row += rows[i];
            }
        });
        Timers.end(Timers.BUFFERS);
        st.rows = rows;

        G.vTex[UNIT][G.targetIndex(G.T_2D)] = st.texture;
        G.vSampler[UNIT] = 0;
        int row = 0;
        for (int i = 0; i < n; i++) {
            Integer loc = p.rowLocation.get(names.get(i));
            int base = row * Glsl.BUFFER_WIDTH + (members[i] == null ? 0 : (int) (members[i].bufferOffset / texel));
            if (loc != null && !Integer.valueOf(base).equals(p.rowSent.get(names.get(i)))) {
                GL20.glUniform1i(loc, base);
                p.rowSent.put(names.get(i), base);
            }
            row += rows[i];
        }
    }
}
