package retrofit.host;

import org.apache.commons.compress.harmony.pack200.Pack200Exception;
import org.apache.commons.compress.harmony.unpack200.Archive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
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
     * @return a handle for calling into the booted legacy world
     */
    public static LegacyRuntime boot(Path minecraftHome, Path gameDir, List<Path> retrofitJars, boolean headless) throws Exception {
        // Logging must be initialised before the context class loader switches to the legacy
        // world: SLF4J and log4j both discover providers and config files through it.
        Logger log = LoggerFactory.getLogger("Retrofit");
        long start = System.nanoTime();
        LegacyInstall install = new LegacyInstall(minecraftHome);
        prepareGameDir(gameDir);

        List<URL> urls = new ArrayList<>();
        for (Path jar : retrofitJars) {
            urls.add(jar.toUri().toURL());
        }
        for (Path entry : install.classpath(gameDir.resolve(".retrofit"))) {
            urls.add(entry.toUri().toURL());
        }
        log.info("Legacy classpath: {} entries from {}", urls.size(), minecraftHome);

        LegacyRootLoader root = new LegacyRootLoader(urls.toArray(URL[]::new), LegacyHost.class.getClassLoader());
        root.loadClass("retrofit.legacy.compat.Pack200").getField("impl").set(null, pack200(gameDir.resolve(".retrofit").resolve("pack200.log")));
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
                    .getMethod("boot", File.class, File.class, boolean.class)
                    .invoke(null, gameDir.toFile(), install.assetsDir().toFile(), headless);
        } catch (InvocationTargetException e) {
            throw unwrap(e);
        } finally {
            thread.setContextClassLoader(previous);
        }
        log.info("Total legacy boot time: {} ms", (System.nanoTime() - start) / 1_000_000);
        return new LegacyRuntime(root, install.gameJar());
    }

    /** Forge's loading splash needs its own OpenGL window, so it is always off. */
    private static void prepareGameDir(Path gameDir) throws IOException {
        Path config = gameDir.resolve("config");
        Files.createDirectories(config);
        Files.createDirectories(gameDir.resolve("mods"));
        Files.writeString(config.resolve("splash.properties"), "enabled=false\n");
    }

    /**
     * Unpacks Forge's binary patches with commons-compress, using its Archive class directly:
     * the Pack200 adapter logs to System.out by default and then closes it, which would cut
     * off the host's console. Its log goes to {@code logFile} instead.
     */
    private static BiConsumer<InputStream, JarOutputStream> pack200(Path logFile) {
        return (in, out) -> {
            try {
                Files.createDirectories(logFile.getParent());
                // Buffer first: some commons-compress versions reflect into FilterInputStream wrappers, which Java 17 forbids.
                Archive archive = new Archive(new ByteArrayInputStream(in.readAllBytes()), out);
                archive.setLogFile(logFile.toString());
                archive.unpack();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (Pack200Exception e) {
                throw new UncheckedIOException(new IOException(e));
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
