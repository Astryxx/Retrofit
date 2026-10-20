package retrofit.host;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 1.7.10 mods put in the game's own {@code mods} folder, where launchers (CurseForge, the
 * Minecraft Launcher, Prism) install mods: Forge 1.20.1 skips them (they have no mods.toml), and
 * 1.7.10's FML only looks in {@code retrofit/mods}. Each one gets a hard link there (the same file,
 * no extra space; a copy where links are not possible), listed in {@code .from-mods-folder.txt} so
 * links whose mod left the mods folder are removed again, and files players put there are never
 * touched.
 */
public final class LegacyModFiles {
    private static final String LIST = ".from-mods-folder.txt";
    /** How many classes are looked at for FML 1.7 references when a file has no mcmod.info or coremod entry. */
    private static final int CLASSES_CHECKED = 200;

    private LegacyModFiles() {
    }

    /** A mod file for another old Minecraft version (1.12.2, 1.8.9...): Retrofit runs 1.7.10 mods only. */
    public record OtherVersion(String file, String minecraftVersion) {
    }

    /**
     * Old Forge mods (no mods.toml) for other Minecraft versions than 1.7.10, in the mods folder or
     * retrofit/mods: neither Forge 1.20.1 nor 1.7.10 can load them, so players are told.
     */
    public static List<OtherVersion> otherVersions(Path... folders) {
        List<OtherVersion> found = new ArrayList<>();
        for (Path folder : folders) {
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                    String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (Files.isRegularFile(file) && (lower.endsWith(".jar") || lower.endsWith(".zip"))) {
                        String version = declaredVersion(file);
                        if (version != null && !version.startsWith("1.7")) {
                            found.add(new OtherVersion(file.getFileName().toString(), version));
                        }
                    }
                }
            } catch (IOException e) {
                // unreadable folder: nothing to tell
            }
        }
        return found;
    }

    /**
     * Links the 1.7.10 mods of {@code modsFolder} into {@code legacyMods}; returns their file names.
     * Links made before whose mod is gone are removed.
     */
    public static List<String> linkFromModsFolder(Path modsFolder, Path legacyMods) throws IOException {
        Files.createDirectories(legacyMods);
        Path list = legacyMods.resolve(LIST);
        Set<String> previous = new LinkedHashSet<>();
        if (Files.isRegularFile(list)) {
            for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    previous.add(line.trim());
                }
            }
        }
        List<String> linked = new ArrayList<>();
        if (Files.isDirectory(modsFolder)) {
            try (Stream<Path> files = Files.list(modsFolder)) {
                for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                    String name = file.getFileName().toString();
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (!Files.isRegularFile(file) || !(lower.endsWith(".jar") || lower.endsWith(".zip")) || !isLegacyMod(file)) {
                        continue;
                    }
                    Path target = legacyMods.resolve(name);
                    if (Files.exists(target) && !previous.contains(name)) {
                        continue; // the player has it in retrofit/mods too: that one counts
                    }
                    if (!Files.exists(target) || Files.size(target) != Files.size(file) || Files.getLastModifiedTime(target).compareTo(Files.getLastModifiedTime(file)) != 0) {
                        Files.deleteIfExists(target);
                        try {
                            Files.createLink(target, file);
                        } catch (IOException | UnsupportedOperationException e) {
                            Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                        }
                    }
                    linked.add(name);
                }
            }
        }
        for (String name : previous) {
            if (!linked.contains(name)) {
                Files.deleteIfExists(legacyMods.resolve(name)); // its mod left the mods folder
            }
        }
        Files.write(list, linked, StandardCharsets.UTF_8);
        return linked;
    }

    /** A 1.7.10 Forge mod: no mods.toml (1.13+), but mcmod.info, a coremod entry or classes using FML 1.7's packages. */
    static boolean isLegacyMod(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (zip.getEntry("META-INF/mods.toml") != null || zip.getEntry("fabric.mod.json") != null || zip.getEntry("quilt.mod.json") != null) {
                return false;
            }
            String version = declaredVersion(zip);
            if (version != null) {
                return version.startsWith("1.7"); // mcmod.info's "mcversion": 1.12.2 mods have one too
            }
            if (zip.getEntry("mcmod.info") != null) {
                return true;
            }
            ZipEntry manifestEntry = zip.getEntry(JarFile.MANIFEST_NAME);
            if (manifestEntry != null) {
                try (InputStream in = zip.getInputStream(manifestEntry)) {
                    Attributes attributes = new Manifest(in).getMainAttributes();
                    if (attributes.getValue("FMLCorePlugin") != null || attributes.getValue("TweakClass") != null && attributes.getValue("FMLModType") == null) {
                        return true;
                    }
                }
            }
            int checked = 0;
            byte[] marker = "cpw/mods/fml/".getBytes(StandardCharsets.UTF_8);
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements() && checked < CLASSES_CHECKED; ) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    checked++;
                    try (InputStream in = zip.getInputStream(entry)) {
                        if (contains(in.readAllBytes(), marker)) {
                            return true;
                        }
                    }
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    /** The Minecraft version an old Forge mod's mcmod.info names ("mcversion"), or null. */
    private static String declaredVersion(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (zip.getEntry("META-INF/mods.toml") != null) {
                return null;
            }
            return declaredVersion(zip);
        } catch (IOException e) {
            return null;
        }
    }

    private static String declaredVersion(ZipFile zip) {
        ZipEntry info = zip.getEntry("mcmod.info");
        if (info == null) {
            return null;
        }
        try (java.io.Reader reader = new java.io.InputStreamReader(zip.getInputStream(info), StandardCharsets.UTF_8)) {
            com.google.gson.JsonElement json = com.google.gson.JsonParser.parseReader(reader);
            com.google.gson.JsonArray mods = json.isJsonArray() ? json.getAsJsonArray()
                    : json.isJsonObject() && json.getAsJsonObject().has("modList") ? json.getAsJsonObject().getAsJsonArray("modList") : null;
            if (mods != null) {
                for (com.google.gson.JsonElement mod : mods) {
                    if (mod.isJsonObject() && mod.getAsJsonObject().has("mcversion")) {
                        String version = mod.getAsJsonObject().get("mcversion").getAsString().trim();
                        if (!version.isEmpty() && !version.contains("${")) {
                            return version;
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // many 1.7.10 mcmod.info files are not quite JSON: no version known
        }
        return null;
    }

    private static boolean contains(byte[] data, byte[] marker) {
        outer:
        for (int i = 0; i + marker.length <= data.length; i++) {
            for (int j = 0; j < marker.length; j++) {
                if (data[i + j] != marker[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
