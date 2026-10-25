package vpmac;

/**
 * A known amount of CPU work added to every draw the layer starts ({@code spin:NANOS} in
 * dump.txt, {@code spin:0} to stop): if the frame rate falls by what that work costs, the render
 * thread's CPU is what holds the frame; if it doesn't move, the GPU is.
 */
final class Spin {
    private Spin() {}

    static volatile long nanos;

    static void now() {
        long until = System.nanoTime() + nanos;
        while (System.nanoTime() < until) Thread.onSpinWait();
    }
}
