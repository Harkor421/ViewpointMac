package vpmac;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Some add-ons reach LWJGL through reflection (Class.forName("org.lwjgl.opengl.GL33") and
 * getDeclaredMethod), which no bytecode rewrite can see. {@link Remap} points their lookups here:
 * a static GL method that {@link G} stands in for comes back as G's, so invoking it lands in the
 * stand-in exactly as a direct call would.
 */
public final class Reflect {
    private Reflect() {}

    public static Method getDeclaredMethod(Class<?> owner, String name, Class<?>... params) throws NoSuchMethodException {
        return standIn(owner, owner.getDeclaredMethod(name, params));
    }

    public static Method getMethod(Class<?> owner, String name, Class<?>... params) throws NoSuchMethodException {
        return standIn(owner, owner.getMethod(name, params));
    }

    private static Method standIn(Class<?> owner, Method found) {
        if (!owner.getName().startsWith("org.lwjgl.opengl.") || !Modifier.isStatic(found.getModifiers())) return found;
        try {
            Method g = G.class.getMethod(found.getName(), found.getParameterTypes());
            if (Modifier.isStatic(g.getModifiers()) && g.getReturnType() == found.getReturnType()) return g;
        } catch (NoSuchMethodException none) {
            // no stand-in: the real call is right for this one
        }
        return found;
    }
}
