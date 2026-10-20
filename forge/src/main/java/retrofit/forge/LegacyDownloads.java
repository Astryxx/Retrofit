package retrofit.forge;

import retrofit.host.LegacyDependencies;
import retrofit.host.LegacyInstall;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * What Retrofit needs to download before 1.7.10 can run, and whether it may. Nothing is downloaded
 * without permission: players are asked on the title screen (client.LegacyDownloadScreen), and a
 * dedicated server's admin agrees in {@code retrofit/retrofit.properties}, as with eula.txt.
 * When the launcher already has everything (and the mods' dependencies are installed), nobody is
 * asked and nothing is downloaded.
 */
public final class LegacyDownloads {
    static final String SETTING = "download-files";
    /** About how much the downloads weigh, from a computer without any 1.7.10 files. */
    private static final String CLIENT_SIZE = "about 50 MB", SERVER_SIZE = "about 45 MB";

    private final Path legacyDir;
    private final LegacyInstall install;
    private final boolean server;
    private final List<LegacyInstall.Missing> files;
    private final List<LegacyDependencies.Dependency> dependencies;

    /** Players' answer, or a server admin's: ask (the default), yes, or never. */
    enum Permission {
        ASK, YES, NEVER
    }

    LegacyDownloads(Path legacyDir, boolean server) throws IOException {
        this.legacyDir = legacyDir;
        this.server = server;
        this.install = new LegacyInstall(LegacyInstall.defaultMinecraftHome(), legacyDir.resolve(".retrofit"), server);
        this.files = install.missing();
        this.dependencies = LegacyDependencies.missing(legacyDir.resolve("mods"));
    }

    boolean needed() {
        return !files.isEmpty() || !dependencies.isEmpty();
    }

    boolean server() {
        return server;
    }

    public List<LegacyInstall.Missing> files() {
        return files;
    }

    public List<LegacyDependencies.Dependency> dependencies() {
        return dependencies;
    }

    public String size() {
        return files.isEmpty() ? "a few MB" : server ? SERVER_SIZE : CLIENT_SIZE;
    }

    public Path cacheDir() {
        return legacyDir.resolve(".retrofit");
    }

    /** Where mods' dependencies go: where their DepLoader looks. */
    public Path dependencyDir() {
        return legacyDir.resolve("mods").resolve("1.7.10");
    }

    /** Downloads everything listed, telling {@code progress} each address. */
    public void fetch(java.util.function.Consumer<String> progress) throws IOException {
        LegacyInstall.onDownload(progress);
        LegacyInstall.allowDownloads(true);
        try {
            if (!files.isEmpty()) {
                install.fetchAll();
            }
            List<String> failed = new ArrayList<>();
            for (LegacyDependencies.Dependency dependency : dependencies) {
                try {
                    LegacyDependencies.fetch(dependency, legacyDir.resolve("mods"));
                } catch (IOException e) {
                    failed.add(dependency.file() + " (wanted by " + dependency.wantedBy() + ", from " + dependency.url() + ")");
                }
            }
            if (!failed.isEmpty()) {
                throw new IOException("Could not download " + String.join(", ", failed)
                        + ". Download them from their mods' pages and put them in retrofit/mods.");
            }
        } finally {
            LegacyInstall.allowDownloads(false);
            LegacyInstall.onDownload(null);
        }
    }

    /** The player's "don't ask again". */
    public void neverAsk() throws IOException {
        setPermission(legacyDir, Permission.NEVER);
    }

    // ---- retrofit/retrofit.properties ----

    private static Path settingsFile(Path legacyDir) {
        return legacyDir.resolve("retrofit.properties");
    }

    static Permission permission(Path legacyDir) {
        Properties properties = new Properties();
        Path file = settingsFile(legacyDir);
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException | IllegalArgumentException e) {
                return Permission.ASK;
            }
        }
        String value = properties.getProperty(SETTING, "ask").trim().toLowerCase(Locale.ROOT);
        return value.equals("yes") || value.equals("true") ? Permission.YES : value.equals("never") || value.equals("no") ? Permission.NEVER : Permission.ASK;
    }

    /** Writes the setting with an explanation (the file has no other settings yet). */
    static void setPermission(Path legacyDir, Permission permission) throws IOException {
        Files.createDirectories(legacyDir);
        try (Writer writer = Files.newBufferedWriter(settingsFile(legacyDir), StandardCharsets.UTF_8)) {
            writer.write("""
                    # Retrofit runs 1.7.10 mods on Minecraft 1.7.10 and Forge 10.13.4.1614, which it does not include.
                    # When the Minecraft launcher does not have them, they are downloaded from Mojang, Forge's Maven and
                    # Maven Central (and mods' dependencies from where the mods say), but only with permission:
                    #   ask   - players are asked on the title screen (the default)
                    #   yes   - download what is missing when the game starts (dedicated servers need this)
                    #   never - never download; 1.7.10 mods load only when the files are already there
                    """);
            writer.write(SETTING + "=" + permission.name().toLowerCase(Locale.ROOT) + "\n");
        }
    }
}
