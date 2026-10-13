package retrofit.host;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Isolated parent loader for the whole 1.7.10 world. Its parent is the platform
 * loader, so host classes (1.20.1 or harness) are invisible to legacy code, with one
 * deliberate exceptions: SLF4J and Retrofit's bridge API ({@code retrofit.api}) are shared with
 * the host, so both sides use the same classes. Legacy code logs through its
 * own log4j-api 2.19 (Forge's 2.0-beta9 is vulnerable to Log4Shell), bridged by
 * log4j-to-slf4j into the host's logging, so legacy logs land in the host's log files.
 *
 * Classes it defines itself (LaunchWrapper, FML's relauncher, tweakers) never pass
 * through the LaunchClassLoader's transformers, so it runs RetrofitTransformer on
 * them directly.
 */
public final class LegacyRootLoader extends URLClassLoader {
    static {
        registerAsParallelCapable();
    }

    private static final String TRANSFORMER = "retrofit.legacy.boot.RetrofitTransformer";
    private static final String[] SHARED_PREFIXES = {"org.slf4j.", "retrofit.api."};

    private final ClassLoader host;
    private volatile Object transformer;
    private volatile Method transform;

    LegacyRootLoader(URL[] urls, ClassLoader host) {
        super("retrofit-legacy", urls, ClassLoader.getPlatformClassLoader());
        this.host = host;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        for (String prefix : SHARED_PREFIXES) {
            if (name.startsWith(prefix)) {
                return host.loadClass(name);
            }
        }
        return super.loadClass(name, resolve);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (!needsTransform(name)) {
            return super.findClass(name);
        }
        String path = name.replace('.', '/') + ".class";
        URL resource = findResource(path);
        if (resource == null) {
            throw new ClassNotFoundException(name);
        }
        try (InputStream in = resource.openStream()) {
            byte[] bytes = transform(name, in.readAllBytes());
            URL source = sourceOf(resource, path);
            definePackageFor(name, source);
            // Legacy code locates its own jar through the CodeSource (FMLTweaker does), so keep it.
            return defineClass(name, bytes, 0, bytes.length, new CodeSource(source, (CodeSigner[]) null));
        } catch (IOException | ReflectiveOperationException e) {
            throw new ClassNotFoundException(name, e);
        }
    }

    /** Maps "jar:file:/x.jar!/a/B.class" (or a directory URL) back to the classpath entry it came from. */
    private static URL sourceOf(URL resource, String path) throws IOException {
        String text = resource.toString();
        if (text.startsWith("jar:")) {
            return new URL(text.substring(4, text.indexOf("!/")));
        }
        return new URL(text.substring(0, text.length() - path.length()));
    }

    private void definePackageFor(String className, URL source) throws IOException {
        int dot = className.lastIndexOf('.');
        if (dot < 0) {
            return;
        }
        String packageName = className.substring(0, dot);
        if (getDefinedPackage(packageName) != null) {
            return;
        }
        Manifest manifest = null;
        if (source.getPath().endsWith(".jar")) {
            try (JarFile jar = new JarFile(new File(URI.create(source.toString())))) {
                manifest = jar.getManifest();
            }
        }
        try {
            if (manifest != null) {
                definePackage(packageName, manifest, source);
            } else {
                definePackage(packageName, null, null, null, null, null, null, null);
            }
        } catch (IllegalArgumentException alreadyDefinedByAnotherThread) {
            // Benign race between parallel class loads.
        }
    }

    /**
     * The transformer and its dependencies are defined untransformed to avoid recursion.
     * Retrofit's own log4j is skipped too: it is a multi-release jar with Java 9+ classes
     * that Forge's ASM 5 cannot read, and it needs no fixes.
     */
    private static boolean needsTransform(String name) {
        return !name.startsWith("retrofit.")
                && !name.startsWith("org.objectweb.asm.")
                && !name.startsWith("net.minecraft.launchwrapper.")
                && !name.startsWith("org.apache.logging.");
    }

    private byte[] transform(String name, byte[] bytes) throws ReflectiveOperationException {
        if (transformer == null) {
            synchronized (this) {
                if (transformer == null) {
                    Class<?> type = loadClass(TRANSFORMER);
                    transform = type.getMethod("transform", String.class, String.class, byte[].class);
                    transformer = type.getConstructor().newInstance();
                }
            }
        }
        return (byte[]) transform.invoke(transformer, name, name, bytes);
    }
}
