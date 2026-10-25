package vpmac;

/**
 * How many textures one batch of Viewpoint's characters and items holds: Viewpoint's own
 * {@code ModelBatches.slot} reads it ({@link SlotPatch}). It starts at what model shaders keep of
 * their texture array ({@link Glsl#MODEL_TEXTURES}), and only goes down: when a model program
 * can't fit the Mac's 16 units even with everything else in the atlas (a shader pack, or an
 * add-on's extra samplers), it keeps fewer, and from then on every batch is that small.
 */
public final class ModelSlots {
    private ModelSlots() {}

    public static volatile int count = Glsl.MODEL_TEXTURES;

    static void atMost(int n) {
        if (n >= count) return;
        count = n;
        Log.info("model batches now hold " + n + " textures, so every model program fits the Mac's units");
    }
}
