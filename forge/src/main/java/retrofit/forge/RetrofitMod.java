package retrofit.forge;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import retrofit.host.LegacyHost;
import retrofit.host.LegacyInstall;
import retrofit.host.LegacyRuntime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Forge 1.20.1 entry point. Boots the player's 1.7.10 + Forge during mod construction, so
 * legacy content exists before 1.20.1's registry events.
 */
@Mod(RetrofitMod.MOD_ID)
public final class RetrofitMod {
    public static final String MOD_ID = "retrofit";
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Legacy-side jars shipped inside the mod; the boot jar must come first. */
    private static final List<String> LEGACY_JARS = List.of("legacy-boot.jar", "legacy-bridge.jar");

    private static volatile LegacyRuntime runtime;

    public RetrofitMod() {
        // Both sides boot the matching side of 1.7.10: a dedicated server downloads the 1.7.10 server jar.
        if (Boolean.getBoolean("retrofit.disabled")) {
            LOGGER.warn("Retrofit is disabled (-Dretrofit.disabled=true); 1.7.10 mods are not loaded.");
            return;
        }
        boolean server = FMLEnvironment.dist == Dist.DEDICATED_SERVER;
        Path legacyDir = FMLPaths.GAMEDIR.get().resolve("retrofit");
        try {
            List<Path> jars = new ArrayList<>();
            for (String jar : LEGACY_JARS) {
                jars.add(extractJar(jar, legacyDir.resolve(".retrofit")));
            }
            System.setProperty("retrofit.language", legacyLanguage());
            String ipv4Before = System.getProperty("java.net.preferIPv4Stack");
            runtime = LegacyHost.boot(LegacyInstall.defaultMinecraftHome(), legacyDir, jars, true, server);
            // Legacy code must not change JVM-wide settings (see LegacySystem); check the one FML 1.7.10 sets.
            LOGGER.debug("java.net.preferIPv4Stack before legacy boot: {}, after: {}", ipv4Before,
                    System.getProperty("java.net.preferIPv4Stack"));
        } catch (Exception e) {
            throw new IllegalStateException("Retrofit could not start Minecraft 1.7.10. See the log above for details.", e);
        }
    }

    /** The player's language from options.txt ("de_de"), in 1.7.10's form ("de_DE"); English if unknown. */
    private static String legacyLanguage() {
        Path options = FMLPaths.GAMEDIR.get().resolve("options.txt");
        try {
            if (Files.isRegularFile(options)) {
                for (String line : Files.readAllLines(options)) {
                    if (line.startsWith("lang:")) {
                        String[] parts = line.substring(5).trim().split("_");
                        if (parts.length == 2) {
                            return parts[0].toLowerCase(java.util.Locale.ROOT) + "_" + parts[1].toUpperCase(java.util.Locale.ROOT);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Could not read the language from {}", options, e);
        }
        return "en_US";
    }

    /** The booted legacy world, or null if it is not running (e.g. on a dedicated server). */
    public static LegacyRuntime runtime() {
        return runtime;
    }

    /**
     * The legacy-side code must not be on 1.20.1's classpath, so it ships as nested jars
     * and is copied out for the legacy class loader. Rewritten only when it changes.
     */
    private static Path extractJar(String name, Path cacheDir) throws IOException {
        String resource = "/retrofit/" + name;
        byte[] bytes;
        try (InputStream in = RetrofitMod.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Retrofit is missing " + resource + "; the mod jar is incomplete");
            }
            bytes = in.readAllBytes();
        }
        Path target = cacheDir.resolve(name);
        if (!Files.isRegularFile(target) || !Arrays.equals(Files.readAllBytes(target), bytes)) {
            Files.createDirectories(cacheDir);
            Files.write(target, bytes);
        }
        return target;
    }
}
