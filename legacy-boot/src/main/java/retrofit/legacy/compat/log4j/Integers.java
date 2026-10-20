package retrofit.legacy.compat.log4j;

/**
 * Replacement for log4j 2.0-beta9's {@code org.apache.logging.log4j.core.helpers.Integers}.
 * Legacy code keeps calling it, but Retrofit doesn't ship the vulnerable log4j-core it lived in.
 */
public final class Integers {
    private Integers() {
    }

    public static int parseInt(String s, int defaultValue) {
        if (s == null || s.trim().isEmpty()) {
            return defaultValue;
        }
        return Integer.parseInt(s.trim());
    }

    public static int parseInt(String s) {
        return parseInt(s, 0);
    }
}
