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

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        if (startsWithAny(name, transformerExceptions) && !startsWithAny(name, classLoaderExceptions)) {
            Class<?> patched = definePatchedIfNeeded(name);
            if (patched != null) {
                return patched;
            }
        }
        return super.findClass(name);
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
