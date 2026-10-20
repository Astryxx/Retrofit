package retrofit.host;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.jar.JarOutputStream;

/**
 * Boots the player's 1.7.10 + Forge into an isolated class loader. Shared by the
 * standalone harness and the 1.20.1 mod.
 */
public final class LegacyHost {
    private LegacyHost() {
    }

    /**
     * @param minecraftHome the launcher's .minecraft directory (versions + libraries)
     * @param gameDir       the legacy game directory (mods, config, logs)
     * @param retrofitJars  Retrofit's legacy-side jars (boot, and optionally the bridge)
     * @param headless      stub out the legacy client's window and resource systems
     * @param server        boot the dedicated server side of 1.7.10 instead of the client
     * @return a handle for calling into the booted legacy world
     */
    public static LegacyRuntime boot(Path minecraftHome, Path gameDir, List<Path> retrofitJars, boolean headless, boolean server)
            throws Exception {
        // Logging must be initialised before the context class loader switches to the legacy
        // world: SLF4J and log4j both discover providers and config files through it.
        Logger log = LoggerFactory.getLogger("Retrofit");
        long start = System.nanoTime();
        LegacyInstall install = new LegacyInstall(minecraftHome, gameDir.resolve(".retrofit"), server)
                .withJavaScript(LegacyInstall.needsJavaScript(gameDir.resolve("mods")));
        prepareGameDir(gameDir);

        List<URL> urls = new ArrayList<>();
        for (Path jar : retrofitJars) {
            urls.add(jar.toUri().toURL());
        }
        for (Path entry : install.classpath()) {
            urls.add(entry.toUri().toURL());
        }
        log.info("Legacy classpath: {} entries from {}", urls.size(), minecraftHome);

        List<Path> javaScript = install.javaScriptLibraries();
        ClassLoader nashorn = javaScript.isEmpty() ? null : new URLClassLoader("retrofit-nashorn",
                javaScript.stream().map(LegacyHost::url).toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        if (nashorn != null) {
            log.info("Nashorn JavaScript engine for 1.7.10 mods that run JavaScript: {}", javaScript.get(0).getFileName());
        }
        LegacyRootLoader root = new LegacyRootLoader(urls.toArray(URL[]::new), LegacyHost.class.getClassLoader(), nashorn);
        root.loadClass("retrofit.legacy.compat.Pack200").getField("impl").set(null,
                pack200(install.commonsCompress(), gameDir.resolve(".retrofit").resolve("pack200.log")));
        // A lambda, not a method reference: log4j picks the logging context from the first
        // visible caller frame, and method references run in hidden frames it skips.
        Function<String, Logger> hostLoggers = name -> LoggerFactory.getLogger(name);
        root.loadClass("retrofit.legacy.compat.log4j.RetrofitLoggerContextFactory").getField("hostLoggers")
                .set(null, hostLoggers);

        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(root);
        try {
            root.loadClass("retrofit.legacy.boot.LegacyBootstrap")
                    .getMethod("boot", File.class, File.class, boolean.class, boolean.class)
                    .invoke(null, gameDir.toFile(), install.assetsDir().toFile(), headless, server);
        } catch (InvocationTargetException e) {
            throw unwrap(e);
        } finally {
            thread.setContextClassLoader(previous);
        }
        log.info("Total legacy boot time: {} ms", (System.nanoTime() - start) / 1_000_000);
        return new LegacyRuntime(root, server ? null : install.gameJar());
    }

    private static URL url(Path path) {
        try {
            return path.toUri().toURL();
        } catch (java.net.MalformedURLException e) {
            throw new IllegalArgumentException(path.toString(), e);
        }
    }

    /** Forge's loading splash needs its own OpenGL window, so it is always off. */
    private static void prepareGameDir(Path gameDir) throws IOException {
        Path config = gameDir.resolve("config");
        Files.createDirectories(config);
        Files.createDirectories(gameDir.resolve("mods"));
        Files.writeString(config.resolve("splash.properties"), "enabled=false\n");
    }

    /**
     * Unpacks Forge's binary patches with commons-compress (in a class loader of its own), using
     * its Archive class directly: the Pack200 adapter logs to System.out by default and then
     * closes it, which would cut off the host's console. Its log goes to {@code logFile} instead.
     */
    private static BiConsumer<InputStream, JarOutputStream> pack200(Path commonsCompress, Path logFile) throws IOException {
        URLClassLoader loader = new URLClassLoader(new URL[]{commonsCompress.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        return (in, out) -> {
            try {
                Files.createDirectories(logFile.getParent());
                Class<?> archiveClass = loader.loadClass("org.apache.commons.compress.harmony.unpack200.Archive");
                // Buffer first: some commons-compress versions reflect into FilterInputStream wrappers, which Java 17 forbids.
                Object archive = archiveClass.getConstructor(InputStream.class, JarOutputStream.class)
                        .newInstance(new ByteArrayInputStream(in.readAllBytes()), out);
                archiveClass.getMethod("setLogFile", String.class).invoke(archive, logFile.toString());
                archiveClass.getMethod("unpack").invoke(archive);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InvocationTargetException e) {
                throw new UncheckedIOException(new IOException("Could not unpack Forge 1.7.10's patches", e.getCause()));
            } catch (ReflectiveOperationException e) {
                throw new UncheckedIOException(new IOException("commons-compress is unusable", e));
            }
        };
    }

    private static Exception unwrap(InvocationTargetException e) {
        Throwable cause = e.getCause();
        if (cause instanceof Exception ex) {
            return ex;
        }
        if (cause instanceof Error err) {
            throw err;
        }
        return e;
    }
}
