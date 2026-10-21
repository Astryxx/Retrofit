package retrofit.legacy.compat.log4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

/**
 * Replacement for log4j 2.0-beta9's {@code org.apache.logging.log4j.core.helpers.Loader}, which
 * mods used to find resources (CoFHCore copies its default world generation files with it).
 * See {@link Integers}.
 */
public final class Loader {
    private Loader() {
    }

    public static ClassLoader getClassLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : Loader.class.getClassLoader();
    }

    /** As log4j looked: the context class loader, then this class's, then the given one, then the system's. */
    public static URL getResource(String resource, ClassLoader defaultLoader) {
        for (ClassLoader loader : new ClassLoader[] {Thread.currentThread().getContextClassLoader(), Loader.class.getClassLoader(), defaultLoader}) {
            if (loader != null) {
                URL url = loader.getResource(resource);
                if (url != null) {
                    return url;
                }
            }
        }
        return ClassLoader.getSystemResource(resource);
    }

    public static InputStream getResourceAsStream(String resource, ClassLoader defaultLoader) {
        URL url = getResource(resource, defaultLoader);
        try {
            return url != null ? url.openStream() : null;
        } catch (IOException e) {
            return null;
        }
    }

    public static Class<?> loadClass(String className) throws ClassNotFoundException {
        return Class.forName(className, true, getClassLoader());
    }
}
