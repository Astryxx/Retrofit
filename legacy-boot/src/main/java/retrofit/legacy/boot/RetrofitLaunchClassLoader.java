package retrofit.legacy.boot;

import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.Map;
import java.util.Set;

/**
 * LaunchClassLoader that also applies {@link RetrofitTransformer}'s Java 17 fixes to
 * classes on its transformer-exclusion list (FML excludes its own patcher, which uses
 * the removed Pack200 API). All other behaviour is LaunchWrapper's own.
 *
 * <p>The additions are made in {@link #loadClass(String, boolean)}, in place of ClassLoader's
 * own, and {@link #findClass} is LaunchClassLoader's: the call stack while transformers run is
 * then frame for frame what it was under LaunchWrapper. Coremods count on it (CoFHCore's access
 * transformer finds the class loader at fixed depths of the stack, and took ours for the
 * loader above it, which has no Minecraft classes).
 */
public class RetrofitLaunchClassLoader extends LaunchClassLoader {
    private static final RetrofitTransformer COMPAT = new RetrofitTransformer();

    private final Set<String> classLoaderExceptions;
    private final Set<String> transformerExceptions;
    private final Map<String, Class<?>> cachedClasses;

    public RetrofitLaunchClassLoader(URL[] sources) {
        super(sources);
        classLoaderExceptions = privateField("classLoaderExceptions");
        transformerExceptions = privateField("transformerExceptions");
        cachedClasses = privateField("cachedClasses");
    }

    /** ClassLoader.loadClass for a loader with no parent (as LaunchClassLoader is), with the additions. */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> type = findLoadedClass(name);
            if (type == null) {
                type = bootstrapClass(name);
            }
            if (type == null && startsWithAny(name, transformerExceptions) && !startsWithAny(name, classLoaderExceptions)) {
                type = definePatchedIfNeeded(name);
            }
            if (type == null) {
                try {
                    type = findClass(name);
                } catch (ClassNotFoundException e) {
                    type = platformClass(name);
                    if (type == null) {
                        throw e;
                    }
                }
            }
            if (resolve) {
                resolveClass(type);
            }
            return type;
        }
    }

    private static Class<?> bootstrapClass(String name) {
        try {
            return Class.forName(name, false, null);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * LaunchClassLoader asks only the bootstrap loader before its own classpath. On Java 8 that had
     * the whole JDK; since Java 9 some of it is in the platform loader's modules (javax.script,
     * java.sql, javax.annotation.processing...), which legacy code would never see. JDK packages it
     * can't find are looked for there, after mods' own copies.
     */
    private static Class<?> platformClass(String name) {
        if (!(name.startsWith("java") || name.startsWith("jdk.") || name.startsWith("sun.") || name.startsWith("com.sun.")
                || name.startsWith("org.w3c.") || name.startsWith("org.xml.") || name.startsWith("org.ietf."))) {
            return null;
        }
        try {
            return ClassLoader.getSystemClassLoader().getParent().loadClass(name); // the platform loader (Java 8 API)
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private synchronized Class<?> definePatchedIfNeeded(String name) throws ClassNotFoundException {
        Class<?> cached = cachedClasses.get(name);
        if (cached != null) {
            return cached;
        }
        try {
            byte[] original = getClassBytes(name);
            if (original == null) {
                return null;
            }
            byte[] patched = COMPAT.transform(name, name, original);
            if (patched == original) {
                return null;
            }
            String path = name.replace('.', '/') + ".class";
            CodeSource codeSource = new CodeSource(sourceOf(findResource(path), path), (CodeSigner[]) null);
            Class<?> type = defineClass(name, patched, 0, patched.length, codeSource);
            cachedClasses.put(name, type);
            return type;
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
    }

    private static URL sourceOf(URL resource, String path) throws MalformedURLException {
        if (resource == null) {
            return null;
        }
        String text = resource.toString();
        if (text.startsWith("jar:")) {
            return new URL(text.substring(4, text.indexOf("!/")));
        }
        return new URL(text.substring(0, text.length() - path.length()));
    }

    private static boolean startsWithAny(String name, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private <T> T privateField(String fieldName) {
        try {
            Field field = LaunchClassLoader.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (T) field.get(this);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported LaunchWrapper version: missing " + fieldName, e);
        }
    }
}
