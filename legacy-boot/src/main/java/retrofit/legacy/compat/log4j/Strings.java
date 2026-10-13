package retrofit.legacy.compat.log4j;

/** Replacement for log4j 2.0-beta9's {@code org.apache.logging.log4j.core.helpers.Strings}. See {@link Integers}. */
public final class Strings {
    private Strings() {
    }

    public static boolean isEmpty(CharSequence cs) {
        return cs == null || cs.length() == 0;
    }

    public static boolean isNotEmpty(CharSequence cs) {
        return !isEmpty(cs);
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static boolean isNotBlank(String s) {
        return !isBlank(s);
    }

    public static String dquote(String s) {
        return '"' + s + '"';
    }
}
