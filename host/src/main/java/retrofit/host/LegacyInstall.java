package retrofit.host;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Resolves the 1.7.10 + Forge classpath for the client or server side. Nothing from Mojang or
 * Forge is bundled with Retrofit: files come from the player's launcher installation when it has
 * them, and are otherwise downloaded from their official sources (Mojang's version manifest,
 * Forge's Maven, Maven Central) into Retrofit's cache, checksum-verified, as launchers and server
 * hosts do. A dedicated server therefore needs nothing pre-installed.
 */
public final class LegacyInstall {
    public static final String VANILLA_VERSION = "1.7.10";
    public static final String FORGE_COORDINATES = "net.minecraftforge:forge:1.7.10-10.13.4.1614-1.7.10";
    private static final String VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String FORGE_MAVEN = "https://maven.minecraftforge.net/";

    /**
     * Legacy code logs through a patched log4j-api bridged to the host's SLF4J, replacing
     * Forge 1.7.10's Log4Shell-vulnerable log4j 2.0-beta9 (API-compatible for FML's use).
     */
    private static final List<Library> LOGGING_LIBRARIES = List.of(
            new Library("org.apache.logging.log4j", "log4j-api", "2.19.0", null, List.of()),
            new Library("org.apache.logging.log4j", "log4j-to-slf4j", "2.19.0", null, List.of()));

    private final Path minecraftHome;
    private final Path cacheDir;
    private final boolean server;

    /**
     * @param minecraftHome the launcher's .minecraft directory, used when it has the files (may not exist)
     * @param cacheDir      where missing files are downloaded
     * @param server        resolve the dedicated server side instead of the client
     */
    public LegacyInstall(Path minecraftHome, Path cacheDir, boolean server) {
        this.minecraftHome = minecraftHome;
        this.cacheDir = cacheDir;
        this.server = server;
    }

    public static Path defaultMinecraftHome() {
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            return Path.of(appData, ".minecraft");
        }
        String home = System.getProperty("user.home");
        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            return Path.of(home, "Library", "Application Support", "minecraft");
        }
        return Path.of(home, ".minecraft");
    }

    public Path assetsDir() {
        return minecraftHome.resolve("assets");
    }

    /** The 1.7.10 client jar (also the source of vanilla textures), downloading it if needed. */
    public Path gameJar() throws IOException {
        Path installed = minecraftHome.resolve("versions").resolve(VANILLA_VERSION).resolve(VANILLA_VERSION + ".jar");
        if (Files.isRegularFile(installed)) {
            return installed;
        }
        return download(vanillaVersion().getAsJsonObject("downloads").getAsJsonObject("client"),
                cacheDir.resolve("versions").resolve(VANILLA_VERSION + ".jar"));
    }

    /**
     * Logging replacement first, then Forge's libraries (so its newer versions, e.g. Guava 17,
     * win), then on the client vanilla's libraries, then Forge and the game jar. The server jar
     * bundles its own libraries.
     */
    public List<Path> classpath() throws IOException {
        Map<String, Library> libraries = new LinkedHashMap<>();
        for (Library library : LOGGING_LIBRARIES) {
            libraries.put(library.group() + ":" + library.artifact(), library);
        }
        Path forgeJar = forgeJar();
        addLibraries(forgeVersion(forgeJar), libraries);
        if (!server) {
            addLibraries(vanillaVersion(), libraries);
        }

        List<Path> classpath = new ArrayList<>();
        for (Library library : libraries.values()) {
            classpath.add(library.locate(minecraftHome.resolve("libraries"), cacheDir.resolve("libraries")));
        }
        classpath.add(forgeJar);
        classpath.add(server ? serverJar() : gameJar());
        return classpath;
    }

    private Path serverJar() throws IOException {
        return download(vanillaVersion().getAsJsonObject("downloads").getAsJsonObject("server"),
                cacheDir.resolve("versions").resolve("minecraft_server." + VANILLA_VERSION + ".jar"));
    }

    /** The launcher's Forge 1.7.10 jar, or the same jar from Forge's Maven (published as "universal"). */
    private Path forgeJar() throws IOException {
        String[] coords = FORGE_COORDINATES.split(":");
        Library forge = new Library(coords[0], coords[1], coords[2], FORGE_MAVEN, List.of());
        Path installed = minecraftHome.resolve("libraries").resolve(forge.relativePath());
        if (Files.isRegularFile(installed)) {
            return installed;
        }
        Path target = cacheDir.resolve("libraries").resolve(forge.relativePath());
        if (!Files.isRegularFile(target)) {
            String url = FORGE_MAVEN + coords[0].replace('.', '/') + "/" + coords[1] + "/" + coords[2] + "/"
                    + coords[1] + "-" + coords[2] + "-universal.jar";
            Library.downloadVerified(url, null, target);
        }
        return target;
    }

    /** Forge's version.json, read from inside its own jar (the same file the installer uses). */
    private static JsonObject forgeVersion(Path forgeJar) throws IOException {
        try (ZipFile jar = new ZipFile(forgeJar.toFile())) {
            ZipEntry entry = jar.getEntry("version.json");
            if (entry == null) {
                throw new IOException(forgeJar + " has no version.json");
            }
            try (Reader reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    /** The launcher's 1.7.10 version file, or Mojang's (via the version manifest), cached. */
    private JsonObject vanillaVersion() throws IOException {
        Path installed = minecraftHome.resolve("versions").resolve(VANILLA_VERSION).resolve(VANILLA_VERSION + ".json");
        if (Files.isRegularFile(installed)) {
            return readJson(installed);
        }
        Path cached = cacheDir.resolve("versions").resolve(VANILLA_VERSION + ".json");
        if (!Files.isRegularFile(cached)) {
            JsonObject manifest;
            try (InputStream in = Library.open(VERSION_MANIFEST);
                 Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                manifest = JsonParser.parseReader(reader).getAsJsonObject();
            }
            for (JsonElement element : manifest.getAsJsonArray("versions")) {
                JsonObject version = element.getAsJsonObject();
                if (version.get("id").getAsString().equals(VANILLA_VERSION)) {
                    Library.downloadVerified(version.get("url").getAsString(), version.get("sha1").getAsString(), cached);
                    break;
                }
            }
            if (!Files.isRegularFile(cached)) {
                throw new IOException("Mojang's version manifest has no " + VANILLA_VERSION);
            }
        }
        return readJson(cached);
    }

    private static Path download(JsonObject download, Path target) throws IOException {
        if (!Files.isRegularFile(target)) {
            Library.downloadVerified(download.get("url").getAsString(), download.get("sha1").getAsString(), target);
        }
        return target;
    }

    private static JsonObject readJson(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private void addLibraries(JsonObject version, Map<String, Library> into) {
        for (JsonElement element : version.getAsJsonArray("libraries")) {
            JsonObject json = element.getAsJsonObject();
            if (json.has("natives") || !rulesAllow(json.getAsJsonArray("rules"))) {
                continue;
            }
            if (server && json.has("serverreq") && !json.get("serverreq").getAsBoolean()) {
                continue;
            }
            Library library = Library.parse(json);
            String key = library.group() + ":" + library.artifact();
            if (library.group().equals("org.apache.logging.log4j")) {
                continue; // Log4Shell-vulnerable 2.0-beta9; replaced by LOGGING_LIBRARIES
            }
            if (key.equals("net.minecraftforge:forge")) {
                continue; // added separately (see forgeJar)
            }
            into.putIfAbsent(key, library);
        }
    }

    private static boolean rulesAllow(JsonArray rules) {
        if (rules == null) {
            return true;
        }
        boolean allowed = false;
        for (JsonElement element : rules) {
            JsonObject rule = element.getAsJsonObject();
            boolean matches = !rule.has("os") || currentOs().equals(rule.getAsJsonObject("os").get("name").getAsString());
            if (matches) {
                allowed = rule.get("action").getAsString().equals("allow");
            }
        }
        return allowed;
    }

    private static String currentOs() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return "windows";
        }
        return os.contains("mac") ? "osx" : "linux";
    }
}
