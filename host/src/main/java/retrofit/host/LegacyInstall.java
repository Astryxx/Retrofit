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
 * hosts do, but only once the player or the server's admin has agreed ({@link #allowDownloads});
 * {@link #missing()} tells beforehand, without going online, whether anything is needed.
 */
public final class LegacyInstall {
    public static final String VANILLA_VERSION = "1.7.10";
    public static final String FORGE_COORDINATES = "net.minecraftforge:forge:1.7.10-10.13.4.1614-1.7.10";
    private static final String VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String FORGE_MAVEN = "https://maven.minecraftforge.net/";

    /**
     * Libraries used instead of Forge 1.7.10's own. Legacy code logs through log4j-api bridged to
     * the host's SLF4J, replacing Forge 1.7.10's Log4Shell-vulnerable log4j 2.0-beta9
     * (API-compatible for FML's use).
     */
    private static final List<Library> REPLACED_LIBRARIES = List.of(
            new Library("org.apache.logging.log4j", "log4j-api", "2.19.0", null, List.of()),
            new Library("org.apache.logging.log4j", "log4j-to-slf4j", "2.19.0", null, List.of()),
            // Replaces Forge's ASM 5.0.3 (same API). 5.0.3 drops the interface flag of method handles
            // when it rewrites a class, so Java 8 interfaces with lambdas fail Java 17's verification
            // after any transformer touched them (Java 8 did not check).
            new Library("org.ow2.asm", "asm-all", "5.2", null, List.of("2ea49e08b876bbd33e0a7ce75c8f371d29e1f10a")));

    /**
     * Unpacks Forge 1.7.10's Pack200-compressed patches. Not every 1.20.1 distribution lets mods
     * see Minecraft's own copy (dedicated servers don't), so it is fetched like the legacy libraries.
     */
    private static final Library COMMONS_COMPRESS = new Library("org.apache.commons", "commons-compress", "1.21", null,
            List.of("4ec95b60d4e86b5c95a0e919cb172a0af98011ef"));

    /**
     * Java 8's JavaScript engine (Nashorn, removed in Java 15), as its OpenJDK project publishes it,
     * with the ASM it needs (kept apart from legacy code's ASM 5 in a class loader of its own).
     * Only for mods that run JavaScript (FiskHeroes' heroes); see {@link #needsJavaScript}.
     */
    private static final List<Library> JAVASCRIPT = List.of(
            new Library("org.openjdk.nashorn", "nashorn-core", "15.4", null, List.of()),
            new Library("org.ow2.asm", "asm", "7.3.1", null, List.of()),
            new Library("org.ow2.asm", "asm-commons", "7.3.1", null, List.of()),
            new Library("org.ow2.asm", "asm-tree", "7.3.1", null, List.of()),
            new Library("org.ow2.asm", "asm-util", "7.3.1", null, List.of()),
            new Library("org.ow2.asm", "asm-analysis", "7.3.1", null, List.of()));

    private final Path minecraftHome;
    private final Path cacheDir;
    private final boolean server;
    private boolean javaScript;

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

    /** Includes the JavaScript engine (some mods need it: {@link #needsJavaScript}). */
    public LegacyInstall withJavaScript(boolean wanted) {
        javaScript = wanted;
        return this;
    }

    /**
     * Whether a 1.7.10 mod in the folder runs JavaScript through Nashorn, Java 8's engine: one that
     * carries .js files and names jdk.nashorn in its classes. Only such mods' classes are read.
     */
    public static boolean needsJavaScript(Path legacyMods) {
        for (Path folder : new Path[]{legacyMods, legacyMods.resolve(VANILLA_VERSION)}) {
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (java.util.stream.Stream<Path> files = Files.list(folder)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    if (Files.isRegularFile(file) && (name.endsWith(".jar") || name.endsWith(".zip")) && usesNashorn(file)) {
                        return true;
                    }
                }
            } catch (IOException e) {
                // unreadable folder
            }
        }
        return false;
    }

    private static boolean usesNashorn(Path jar) {
        byte[] marker = "jdk/nashorn".getBytes(StandardCharsets.UTF_8);
        byte[] dotted = "jdk.nashorn".getBytes(StandardCharsets.UTF_8);
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            if (zip.stream().noneMatch(entry -> entry.getName().endsWith(".js"))) {
                return false;
            }
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                if (entry.getName().endsWith(".class")) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        byte[] bytes = in.readAllBytes();
                        if (indexOf(bytes, marker) >= 0 || indexOf(bytes, dotted) >= 0) {
                            return true;
                        }
                    }
                }
            }
        } catch (IOException e) {
            // not a readable jar
        }
        return false;
    }

    private static int indexOf(byte[] data, byte[] marker) {
        outer:
        for (int i = 0; i + marker.length <= data.length; i++) {
            for (int j = 0; j < marker.length; j++) {
                if (data[i + j] != marker[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** The JavaScript engine's jars (see {@link #JAVASCRIPT}), downloading them if needed; empty unless {@link #withJavaScript}. */
    public List<Path> javaScriptLibraries() throws IOException {
        List<Path> jars = new ArrayList<>();
        if (javaScript) {
            for (Library library : JAVASCRIPT) {
                jars.add(library.locate(minecraftHome.resolve("libraries"), cacheDir.resolve("libraries")));
            }
        }
        return jars;
    }

    /** One thing that would be downloaded: what it is, and the address it comes from. */
    public record Missing(String what, String from) {
    }

    /** Allows downloads (the player agreed, or a server's admin did in retrofit.properties). Off until then. */
    public static void allowDownloads(boolean allowed) {
        Library.downloadsAllowed = allowed;
    }

    /** Is told the address of each file just before it is downloaded. */
    public static void onDownload(java.util.function.Consumer<String> listener) {
        Library.progress = listener == null ? url -> { } : listener;
    }

    /**
     * What resolving the classpath would download, found without going online: empty when the
     * launcher's installation and Retrofit's cache already have everything. Files listed by
     * version files not on this computer yet are summed up as "and their libraries".
     */
    public List<Missing> missing() throws IOException {
        List<Missing> missing = new ArrayList<>();
        Path launcherLibraries = minecraftHome.resolve("libraries");
        Path cacheLibraries = cacheDir.resolve("libraries");
        // the same libraries, in the same order, as classpath(): replacements win over Forge's, Forge's over vanilla's
        Map<String, Library> libraries = new LinkedHashMap<>();
        for (Library library : REPLACED_LIBRARIES) {
            libraries.put(library.group() + ":" + library.artifact(), library);
        }
        String[] coords = FORGE_COORDINATES.split(":");
        Library forge = new Library(coords[0], coords[1], coords[2], FORGE_MAVEN, List.of());
        Path forgeJar = Files.isRegularFile(launcherLibraries.resolve(forge.relativePath())) ? launcherLibraries.resolve(forge.relativePath())
                : cacheLibraries.resolve(forge.relativePath());
        if (Files.isRegularFile(forgeJar)) {
            addLibraries(forgeVersion(forgeJar), libraries);
        } else {
            missing.add(new Missing("Minecraft Forge " + coords[2] + ", and its libraries", "Forge (maven.minecraftforge.net), Maven Central"));
        }
        Path vanillaJson = Files.isRegularFile(minecraftHome.resolve("versions").resolve(VANILLA_VERSION).resolve(VANILLA_VERSION + ".json"))
                ? minecraftHome.resolve("versions").resolve(VANILLA_VERSION).resolve(VANILLA_VERSION + ".json")
                : cacheDir.resolve("versions").resolve(VANILLA_VERSION + ".json");
        String game = server ? "Minecraft " + VANILLA_VERSION + " server" : "Minecraft " + VANILLA_VERSION;
        boolean vanillaKnown = Files.isRegularFile(vanillaJson);
        if (!vanillaKnown) {
            missing.add(new Missing(game + (server ? "" : ", and its libraries"), "Mojang (piston-meta.mojang.com, piston-data.mojang.com, libraries.minecraft.net)"));
        } else if (!server) {
            addLibraries(readJson(vanillaJson), libraries);
        }
        for (Library library : libraries.values()) {
            if (!library.isLocal(launcherLibraries, cacheLibraries)) {
                missing.add(new Missing("library " + library.group() + ":" + library.artifact() + " " + library.version(),
                        "Mojang (libraries.minecraft.net), Forge (maven.minecraftforge.net) or Maven Central (repo1.maven.org)"));
            }
        }
        if (!COMMONS_COMPRESS.isLocal(launcherLibraries, cacheLibraries)) {
            missing.add(new Missing("Apache Commons Compress " + COMMONS_COMPRESS.version(), "Maven Central (repo1.maven.org)"));
        }
        if (javaScript && JAVASCRIPT.stream().anyMatch(library -> !library.isLocal(launcherLibraries, cacheLibraries))) {
            missing.add(new Missing("the Nashorn JavaScript engine 15.4 (a mod runs JavaScript; Java 8 had it built in)", "Maven Central (repo1.maven.org)"));
        }
        if (vanillaKnown) {
            Path gameJar = server ? cacheDir.resolve("versions").resolve("minecraft_server." + VANILLA_VERSION + ".jar")
                    : Files.isRegularFile(minecraftHome.resolve("versions").resolve(VANILLA_VERSION).resolve(VANILLA_VERSION + ".jar"))
                    ? minecraftHome.resolve("versions").resolve(VANILLA_VERSION).resolve(VANILLA_VERSION + ".jar")
                    : cacheDir.resolve("versions").resolve(VANILLA_VERSION + ".jar");
            if (!Files.isRegularFile(gameJar)) {
                missing.add(new Missing(game, "Mojang (piston-data.mojang.com)"));
            }
        }
        return missing;
    }

    /** Downloads everything {@link #missing()} listed (needs {@link #allowDownloads}). */
    public void fetchAll() throws IOException {
        classpath();
        commonsCompress();
        javaScriptLibraries();
    }

    /** The launcher's .minecraft directory (-Dretrofit.minecraftHome=... for launchers that keep it elsewhere). */
    public static Path defaultMinecraftHome() {
        String configured = System.getProperty("retrofit.minecraftHome");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
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
    /** commons-compress, from the launcher's libraries or downloaded (see {@link #COMMONS_COMPRESS}). */
    public Path commonsCompress() throws IOException {
        return COMMONS_COMPRESS.locate(minecraftHome.resolve("libraries"), cacheDir.resolve("libraries"));
    }

    public List<Path> classpath() throws IOException {
        Map<String, Library> libraries = new LinkedHashMap<>();
        for (Library library : REPLACED_LIBRARIES) {
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
                continue; // Log4Shell-vulnerable 2.0-beta9; replaced by REPLACED_LIBRARIES
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
