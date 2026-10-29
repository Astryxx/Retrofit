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
    /** Downloads the player has not been asked about yet (the title screen asks), or null. */
    private static volatile LegacyDownloads pendingDownloads;

    public RetrofitMod() {
        retrofit.forge.world.LegacyErrors.ready(); // before any legacy code can fail (see LegacyErrors.LOGGER)
        // Both sides boot the matching side of 1.7.10: a dedicated server downloads the 1.7.10 server jar.
        if (Boolean.getBoolean("retrofit.disabled")) {
            LOGGER.warn("Retrofit is disabled (-Dretrofit.disabled=true); 1.7.10 mods are not loaded.");
            return;
        }
        // Chunks legacy tickets forced last time are released at load: the mods force them again from their own
        // saved tickets (1.7.10's ForgeChunkManager, see the legacy bridge's ChunkLoading).
        net.minecraftforge.common.world.ForgeChunkManager.setForcedChunkLoadingCallback(MOD_ID,
                (level, tickets) -> tickets.getEntityTickets().keySet().forEach(tickets::removeAllTickets));
        boolean server = FMLEnvironment.dist == Dist.DEDICATED_SERVER;
        Path legacyDir = FMLPaths.GAMEDIR.get().resolve("retrofit");
        try {
            Files.createDirectories(legacyDir.resolve("mods")); // where players put their 1.7.10 mods, even before 1.7.10 can run
            // launchers install mods into mods/: 1.7.10 ones there are linked into retrofit/mods (Forge 1.20.1 skips them)
            List<String> fromModsFolder = retrofit.host.LegacyModFiles.linkFromModsFolder(FMLPaths.MODSDIR.get(), legacyDir.resolve("mods"));
            if (!fromModsFolder.isEmpty()) {
                LOGGER.info("1.7.10 mods in the mods folder, loaded by Retrofit: {}", fromModsFolder);
            }
            legacyModFiles = countLegacyModFiles(legacyDir.resolve("mods"));
            otherVersions = retrofit.host.LegacyModFiles.otherVersions(FMLPaths.MODSDIR.get(), legacyDir.resolve("mods"));
            otherVersions.forEach(other -> LOGGER.warn("{} is a mod for Minecraft {}: Retrofit runs 1.7.10 mods only, so it is not loaded",
                    other.file(), other.minecraftVersion()));
            retrofit.host.LegacyDependencies.bringBack(legacyDir); // set aside at an earlier start: checked again
            if (!downloadsSettled(legacyDir, server)) {
                return; // 1.7.10 mods load once the files are there (after the next start)
            }
            List<Path> jars = new ArrayList<>();
            for (String jar : LEGACY_JARS) {
                jars.add(extractJar(jar, legacyDir.resolve(".retrofit")));
            }
            System.setProperty("retrofit.language", legacyLanguage());
            if (!server) {
                try {
                    String[] user = retrofit.forge.client.ClientHooks.user();
                    System.setProperty("retrofit.username", user[0]);
                    System.setProperty("retrofit.uuid", user[1]);
                } catch (RuntimeException e) {
                    LOGGER.debug("The player's name is not known yet", e);
                }
            }
            String ipv4Before = System.getProperty("java.net.preferIPv4Stack");
            runtime = LegacyHost.boot(LegacyInstall.defaultMinecraftHome(), legacyDir, jars, true, server);
            List<String> notLoaded = new ArrayList<>(waitingForLibraries);
            notLoaded.addAll(leftOutMods(runtime));
            leftOut = notLoaded;
            leftOut.forEach(line -> LOGGER.warn("1.7.10 mod not loaded, the others are: {}", line));
            // Legacy code must not change JVM-wide settings (see LegacySystem); check the one FML 1.7.10 sets.
            LOGGER.debug("java.net.preferIPv4Stack before legacy boot: {}, after: {}", ipv4Before,
                    System.getProperty("java.net.preferIPv4Stack"));
        } catch (Exception e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause.getMessage() != null && cause.getMessage().startsWith("Retrofit does not let 1.7.10 mods download")) {
                    throw new IllegalStateException(cause.getMessage(), e); // a mod's own download of a mod it needs, refused (LegacyNet)
                }
            }
            throw new IllegalStateException("Retrofit could not start Minecraft 1.7.10. See the log above for details.", e);
        }
    }

    /**
     * Whether everything 1.7.10 needs is on this computer, after downloading it if the player or
     * the server's admin already allowed that. Otherwise clients ask on the title screen (this
     * session runs without 1.7.10), and servers stop with instructions, like eula.txt.
     */
    private static boolean downloadsSettled(Path legacyDir, boolean server) throws IOException {
        LegacyDownloads downloads = new LegacyDownloads(legacyDir, server);
        if (!downloads.needed()) {
            return true;
        }
        LegacyDownloads.Permission permission = LegacyDownloads.permission(legacyDir);
        if (permission == LegacyDownloads.Permission.NEVER && downloads.files().isEmpty()) {
            // only mods' dependencies are missing: the mods wanting them wait aside, the others load
            waitingForLibraries = retrofit.host.LegacyDependencies.setAside(legacyDir, downloads.dependencies());
            return true;
        }
        if (permission == LegacyDownloads.Permission.YES) {
            LOGGER.info("Downloading what 1.7.10 needs ({} set to yes in retrofit/retrofit.properties)", LegacyDownloads.SETTING);
            // a dependency that can't be downloaded: its mod waits aside, the others load
            waitingForLibraries = retrofit.host.LegacyDependencies.setAside(legacyDir, downloads.fetch(url -> LOGGER.info("Downloading {}", url)));
            return true;
        }
        StringBuilder list = new StringBuilder();
        downloads.files().forEach(file -> list.append("\n  ").append(file.what()).append(" from ").append(file.from()));
        downloads.dependencies().forEach(dep -> list.append("\n  ").append(dep.file()).append(" (wanted by ").append(dep.wantedBy())
                .append(") from ").append(dep.url()));
        if (server) {
            if (!Files.isRegularFile(legacyDir.resolve("retrofit.properties"))) {
                LegacyDownloads.setPermission(legacyDir, LegacyDownloads.Permission.ASK);
            }
            throw new IllegalStateException("Retrofit needs to download files to run 1.7.10 mods (" + downloads.size() + ") into "
                    + downloads.cacheDir() + ":" + list + "\nTo allow it, set " + LegacyDownloads.SETTING + "=yes in "
                    + legacyDir.resolve("retrofit.properties") + " and start the server again.");
        }
        if (permission == LegacyDownloads.Permission.ASK) {
            pendingDownloads = downloads;
            LOGGER.warn("Retrofit needs to download files to run 1.7.10 mods; the title screen asks first. Needed:{}", list);
        } else {
            LOGGER.warn("Retrofit needs to download files to run 1.7.10 mods, and {}=never in retrofit/retrofit.properties: "
                    + "1.7.10 mods are not loaded. Needed:{}", LegacyDownloads.SETTING, list);
        }
        return false;
    }

    private static int legacyModFiles = -1;
    private static List<String> leftOut = List.of();
    /** The mods set aside this start for a dependency they couldn't get (see LegacyDependencies.setAside). */
    private static List<String> waitingForLibraries = List.of();

    /** 1.7.10 mods left out because they lack a mod they need or failed to load, with why (told on the title screen). */
    public static List<String> leftOut() {
        return leftOut;
    }

    @SuppressWarnings("unchecked")
    private static List<String> leftOutMods(LegacyRuntime runtime) {
        try {
            return List.copyOf((List<String>) Class.forName("retrofit.legacy.boot.ModFailures", true, runtime.gameLoader())
                    .getMethod("report").invoke(null));
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.debug("Could not ask which 1.7.10 mods were left out", e);
            return List.of();
        }
    }
    private static List<retrofit.host.LegacyModFiles.OtherVersion> otherVersions = List.of();

    /** Old Forge mods for other Minecraft versions than 1.7.10 that players put in (told on the title screen). */
    public static List<retrofit.host.LegacyModFiles.OtherVersion> otherVersions() {
        return otherVersions;
    }

    /** Mod files (jars and zips) 1.7.10 loads from retrofit/mods (including those linked from mods/), or -1 if not looked at. */
    public static int legacyModFiles() {
        return legacyModFiles;
    }

    private static int countLegacyModFiles(Path legacyMods) {
        int count = 0;
        for (Path dir : new Path[]{legacyMods, legacyMods.resolve("1.7.10")}) {
            try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                count += (int) files.filter(file -> {
                    String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    return Files.isRegularFile(file) && (name.endsWith(".jar") || name.endsWith(".zip"));
                }).count();
            } catch (IOException e) {
                // no such folder
            }
        }
        return count;
    }

    /** Downloads waiting for the player's answer (client.LegacyDownloadScreen), or null. */
    public static LegacyDownloads pendingDownloads() {
        return pendingDownloads;
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
