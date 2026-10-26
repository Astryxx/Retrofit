package retrofit.legacy.bridge;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Whether a mod's class overrides a method. Asked through a method handle, which resolves just that
 * one method: Class.getMethod resolves every signature of the class, and mods' methods name classes
 * that aren't there (other mods' optional APIs, client-only classes on a dedicated server), which
 * fails the whole lookup.
 */
final class Overrides {
    private Overrides() {
    }

    /** Whether {@code type}'s public method {@code name} is declared below {@code base}; false if it can't be told. */
    static boolean overrides(Class<?> type, Class<?> base, String name, MethodType signature) {
        Class<?> declarer = declarer(type, name, signature);
        return declarer != null && declarer != base;
    }

    /** The class declaring {@code type}'s public method {@code name}, or null if there is none or it can't be told. */
    static Class<?> declarer(Class<?> type, String name, MethodType signature) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            return lookup.revealDirect(lookup.findVirtual(type, name, signature)).getDeclaringClass();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }
}
