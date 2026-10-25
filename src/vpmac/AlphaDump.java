package vpmac;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/** A texture's alpha channel as a grey PNG in dumps/ ({@code texa:<id>} in dump.txt): masks and fonts show up white on black. */
final class AlphaDump {
    private AlphaDump() {}

    static void dump(int texture) {
        Tex.with(G.T_2D, texture, () -> {
            int w = GL11.glGetTexLevelParameteri(G.T_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int h = GL11.glGetTexLevelParameteri(G.T_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            ByteBuffer px = MemoryUtil.memAlloc(Math.max(1, w * h));
            int pack = GL11.glGetInteger(0x88ED);
            GL11.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT);
            if (pack != 0) org.lwjgl.opengl.GL15.glBindBuffer(G.PIXEL_PACK_BUFFER, 0);
            try {
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
                GL11.glGetTexImage(G.T_2D, 0, GL11.GL_ALPHA, GL11.GL_UNSIGNED_BYTE, px);
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    int v = px.get(y * w + x) & 255;
                    img.setRGB(x, y, v << 16 | v << 8 | v);
                }
                java.nio.file.Path dir = Log.dir().resolve("dumps");
                java.nio.file.Files.createDirectories(dir);
                javax.imageio.ImageIO.write(img, "png", dir.resolve("alpha-" + texture + ".png").toFile());
                Log.info("alpha of texture " + texture + " (" + w + "x" + h + ") in dumps/alpha-" + texture + ".png");
            } catch (Exception e) {
                Log.warn("alpha dump failed: " + e);
            } finally {
                GL11.glPopClientAttrib();
                if (pack != 0) org.lwjgl.opengl.GL15.glBindBuffer(G.PIXEL_PACK_BUFFER, pack);
                MemoryUtil.memFree(px);
            }
        });
    }
}
