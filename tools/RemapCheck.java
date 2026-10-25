package vpmac;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Offline check: runs {@link Remap} over an extracted Viewpoint (or add-on) jar and writes the
 * rewritten classes next to it, then counts the calls that now reach the stand-ins and the GL
 * calls still left for LWJGL.
 *
 * With {@code -Dcontext}, it also opens a hidden Mac legacy context and asks LWJGL whether each
 * call left for LWJGL exists there: one that doesn't would fail in the game, and fails the check.
 * {@code -Dallow=Owner.name,...} lists calls known to be guarded (Windows-only paths, debug output
 * behind a capability check), by prefix.
 *
 * <p>Usage: RemapCheck &lt;extracted jar dir&gt; &lt;out dir&gt;
 */
public class RemapCheck {
    public static void main(String[] args) throws Exception {
        Path in = Path.of(args[0]), out = Path.of(args[1]);
        Remap remap = new Remap();
        int seen = 0;
        int[] counts = new int[3]; // to G, to Reflect, still to LWJGL's GL classes
        java.util.Map<String, Integer> left = new java.util.TreeMap<>();
        try (Stream<Path> st = Files.walk(in)) {
            for (Path p : (Iterable<Path>) st.filter(f -> f.toString().endsWith(".class"))::iterator) {
                byte[] bytes = Files.readAllBytes(p);
                byte[] rewritten = remap.rewrite(bytes);
                Path dest = out.resolve(in.relativize(p));
                Files.createDirectories(dest.getParent());
                Files.write(dest, rewritten != null ? rewritten : bytes);
                count(rewritten != null ? rewritten : bytes, counts, left);
                seen++;
            }
        }
        Path batches = in.resolve(SlotPatch.TARGET + ".class");
        if (Files.isRegularFile(batches)) {
            byte[] patched = new SlotPatch(null).transform(null, null, SlotPatch.TARGET, null, null, Files.readAllBytes(batches));
            int[] reads = new int[1];
            if (patched != null) {
                new net.bytebuddy.jar.asm.ClassReader(patched).accept(new net.bytebuddy.jar.asm.ClassVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9) {
                    @Override
                    public net.bytebuddy.jar.asm.MethodVisitor visitMethod(int a, String n, String d, String sig, String[] ex) {
                        return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9) {
                            @Override
                            public void visitFieldInsn(int op, String owner, String name, String desc) {
                                if (owner.equals("vpmac/ModelSlots")) reads[0]++;
                            }
                        };
                    }
                }, 0);
            }
            System.out.println(reads[0] > 0 ? "ModelBatches.slot reads ModelSlots.count" : "MODEL BATCHES NOT PATCHED");
            if (reads[0] == 0) System.exit(3);
        }
        System.out.println(seen + " classes, " + remap.changedClasses + " rewritten; calls to the stand-ins " + counts[0]
            + ", reflective lookups through Reflect " + counts[1] + ", GL calls left to LWJGL " + counts[2]);
        if (System.getProperty("context") == null) {
            left.forEach((k, v) -> System.out.println("   left: " + k + " x" + v));
            return;
        }
        org.lwjgl.glfw.GLFW.glfwInit();
        org.lwjgl.glfw.GLFW.glfwDefaultWindowHints();
        org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_VISIBLE, org.lwjgl.glfw.GLFW.GLFW_FALSE);
        long w = org.lwjgl.glfw.GLFW.glfwCreateWindow(16, 16, "remap", 0, 0);
        org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(w);
        org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.createCapabilities();
        int missing = 0;
        java.util.List<String> allowed = java.util.Arrays.stream(System.getProperty("allow", "").split(",")).filter(a -> !a.isBlank()).toList();
        for (String call : left.keySet()) {
            if (allowed.stream().anyMatch(call::startsWith)) continue;
            String name = call.substring(call.indexOf('.') + 1, call.indexOf('('));
            long address;
            try {
                address = caps.getClass().getField(name).getLong(caps);
            } catch (ReflectiveOperationException e) {
                continue; // not a GL entry point LWJGL tracks (a helper overload)
            }
            if (address == 0) {
                missing++;
                System.out.println("   MISSING on the Mac: " + call + " x" + left.get(call));
            }
        }
        System.out.println(missing == 0 ? "every GL call left exists on the Mac" : missing + " GL calls left don't exist on the Mac");
        org.lwjgl.glfw.GLFW.glfwTerminate();
        if (missing > 0) System.exit(2);
    }

    static void count(byte[] bytes, int[] counts, java.util.Map<String, Integer> left) {
        new net.bytebuddy.jar.asm.ClassReader(bytes).accept(new net.bytebuddy.jar.asm.ClassVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9) {
            @Override
            public net.bytebuddy.jar.asm.MethodVisitor visitMethod(int a, String n, String d, String sig, String[] ex) {
                return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                        if (owner.equals(Remap.SHIM)) counts[0]++;
                        else if (owner.equals(Remap.REFLECT)) counts[1]++;
                        else if (owner.startsWith("org/lwjgl/opengl/")) {
                            counts[2]++;
                            left.merge(owner.substring(17) + "." + name + desc, 1, Integer::sum);
                        }
                    }
                };
            }
        }, 0);
    }
}
