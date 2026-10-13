package retrofit.host;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Locates the player's own 1.7.10 + Forge installation and resolves its classpath
 * from the launcher's version files. Nothing from Mojang or Forge is bundled.
 */
public final class LegacyInstall {
    public static final String VANILLA_VERSION = "1.7.10";
    public static final String FORGE_VERSION = "1.7.10-Forge10.13.4.1614-1.7.10";

    /**
     * Legacy code logs through a patched log4j-api bridged to the host's SLF4J, replacing
     * Forge 1.7.10's Log4Shell-vulnerable log4j 2.0-beta9 (API-compatible for FML's use).
     */
    private static final List<Library> LOGGING_LIBRARIES = List.of(
            new Library("org.apache.logging.log4j", "log4j-api", "2.19.0", null, List.of()),
            new Library("org.apache.logging.log4j", "log4j-to-slf4j", "2.19.0", null, List.of()));

    private final Path minecraftHome;

    public LegacyInstall(Path minecraftHome) {
        this.minecraftHome = minecraftHome;
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

    public Path gameJar() {
        return minecraftHome.resolve("versions").resolve(VANILLA_VERSION).resolve(VANILLA_VERSION + ".jar");
    }

    public Path assetsDir() {
        return minecraftHome.resolve("assets");
    }

    /**
     * Forge libraries first so their newer versions (e.g. Guava 17) win, then vanilla's, then the game jar.
     * Open-source libraries the launcher never downloaded are fetched into {@code cacheDir}; the
     * Minecraft jar itself must come from the player's install.
     */
    public List<Path> classpath(Path cacheDir) throws IOException {
        Map<String, Library> libraries = new LinkedHashMap<>();
        for (Library library : LOGGING_LIBRARIES) {
            libraries.put(library.group() + ":" + library.artifact(), library);
        }
        addLibraries(readVersion(FORGE_VERSION), libraries);
        addLibraries(readVersion(VANILLA_VERSION), libraries);

        List<Path> classpath = new ArrayList<>();
        for (Library library : libraries.values()) {
            classpath.add(library.locate(minecraftHome.resolve("libraries"), cacheDir.resolve("libraries")));
        }

        Path gameJar = gameJar();
        if (!Files.isRegularFile(gameJar)) {
            throw new IOException("Minecraft " + VANILLA_VERSION + " is not installed. Launch it once from the Minecraft launcher. Expected " + gameJar);
        }
        classpath.add(gameJar);
        return classpath;
    }

    private JsonObject readVersion(String id) throws IOException {
        Path file = minecraftHome.resolve("versions").resolve(id).resolve(id + ".json");
        try (Reader reader = Files.newBufferedReader(file)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void addLibraries(JsonObject version, Map<String, Library> into) {
        for (JsonElement element : version.getAsJsonArray("libraries")) {
            JsonObject json = element.getAsJsonObject();
            if (json.has("natives") || !rulesAllow(json.getAsJsonArray("rules"))) {
                continue;
            }
            Library library = Library.parse(json);
            if (library.group().equals("org.apache.logging.log4j")) {
                continue; // Log4Shell-vulnerable 2.0-beta9; replaced by LOGGING_LIBRARIES
            }
            into.putIfAbsent(library.group() + ":" + library.artifact(), library);
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
