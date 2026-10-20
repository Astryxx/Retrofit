package retrofit.legacy.compat;

import java.io.PrintStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redirect targets for {@code java.lang.System} calls that legacy code must not make
 * inside a shared modern JVM.
 */
public final class LegacySystem {
    /**
     * System properties set or cleared by legacy code. They stay local to the legacy world:
     * FML 1.7.10, for one, sets java.net.preferIPv4Stack=true, which would switch the host game's
     * networking to IPv4-only mid-session. Legacy code still reads its own settings and every real
     * property it hasn't overridden.
     */
    private static final Map<String, String> PROPERTIES = new ConcurrentHashMap<String, String>();
    private static final Set<String> CLEARED = ConcurrentHashMap.newKeySet();

    private LegacySystem() {
    }

    public static String getProperty(String key) {
        String value = PROPERTIES.get(key);
        if (value != null) {
            return value;
        }
        return CLEARED.contains(key) ? null : System.getProperty(key);
    }

    public static String getProperty(String key, String defaultValue) {
        String value = getProperty(key);
        return value != null ? value : defaultValue;
    }

    public static String setProperty(String key, String value) {
        String previous = getProperty(key);
        CLEARED.remove(key);
        PROPERTIES.put(key, value);
        return previous;
    }

    public static String clearProperty(String key) {
        String previous = getProperty(key);
        PROPERTIES.remove(key);
        CLEARED.add(key);
        return previous;
    }

    public static boolean getBoolean(String key) {
        return Boolean.parseBoolean(getProperty(key));
    }

    public static Integer getInteger(String key) {
        return getInteger(key, null);
    }

    public static Integer getInteger(String key, int defaultValue) {
        return getInteger(key, Integer.valueOf(defaultValue));
    }

    public static Integer getInteger(String key, Integer defaultValue) {
        try {
            String value = getProperty(key);
            return value != null ? Integer.decode(value) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static Long getLong(String key) {
        return getLong(key, null);
    }

    public static Long getLong(String key, long defaultValue) {
        return getLong(key, Long.valueOf(defaultValue));
    }

    public static Long getLong(String key, Long defaultValue) {
        try {
            String value = getProperty(key);
            return value != null ? Long.decode(value) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
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
