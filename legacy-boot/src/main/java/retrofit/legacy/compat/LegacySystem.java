package retrofit.legacy.compat;

import java.io.PrintStream;

/**
 * Redirect targets for {@code java.lang.System} calls that legacy code must not make
 * inside a shared modern JVM.
 */
public final class LegacySystem {
    private LegacySystem() {
    }

    /**
     * FML installs a SecurityManager to catch mods calling System.exit. A security
     * manager slows down the whole JVM and is gone in Java 18+, so we never install it.
     */
    public static void setSecurityManager(SecurityManager manager) {
        System.out.println("[Retrofit] Ignored legacy SecurityManager: " + (manager == null ? "null" : manager.getClass().getName()));
    }

    /** FML routes stdout into its own logger; in a shared JVM the host owns the console. */
    public static void setOut(PrintStream stream) {
        System.out.println("[Retrofit] Ignored legacy System.setOut");
    }

    public static void setErr(PrintStream stream) {
        System.out.println("[Retrofit] Ignored legacy System.setErr");
    }
}
