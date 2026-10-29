package retrofit.host;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Mods that 1.7.10 mods download for themselves: CodeChicken's DepLoader (in CodeChickenCore, and
 * copied into Thaumcraft and NotEnoughItems) reads a {@code dependancies.info} from each mod and
 * downloads what is missing into {@code mods/1.7.10} while the game starts, without asking. Here
 * they are found beforehand so the player can be asked first; legacy code itself may not download
 * mods (see the legacy side's LegacyNet).
 */
public final class LegacyDependencies {
    private LegacyDependencies() {
    }

    /** A mod's dependency: its file name, where its mod downloads it from, and which mod wants it. */
    public record Dependency(String file, String url, String wantedBy) {
    }

    /** The dependencies the mods in {@code modsDir} name that no jar there provides. */
    public static List<Dependency> missing(Path modsDir) {
        List<Path> jars = jars(modsDir);
        Set<String> names = new HashSet<>();
        Set<String> classes = new HashSet<>();
        List<Dependency> wanted = new ArrayList<>();
        List<String> wantedClasses = new ArrayList<>();
        for (Path jar : jars) {
            names.add(jar.getFileName().toString().toLowerCase(Locale.ROOT));
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                    String name = entries.nextElement().getName();
                    if (name.endsWith(".class")) {
                        classes.add(name);
                    }
                }
                for (String info : new String[]{"dependancies.info", "dependencies.info"}) { // DepLoader's spelling first
                    ZipEntry entry = zip.getEntry(info);
                    if (entry != null) {
                        try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                            JsonElement json = JsonParser.parseReader(reader);
                            for (JsonElement element : json.isJsonArray() ? json.getAsJsonArray() : List.of(json)) {
                                JsonObject dep = element.getAsJsonObject();
                                if (dep.has("repo") && dep.has("file")) {
                                    String repo = dep.get("repo").getAsString();
                                    wanted.add(new Dependency(dep.get("file").getAsString(),
                                            (repo.endsWith("/") ? repo : repo + "/") + dep.get("file").getAsString(), jar.getFileName().toString()));
                                    wantedClasses.add(dep.has("class") ? dep.get("class").getAsString().replace('.', '/') + ".class" : null);
                                }
                            }
                        } catch (RuntimeException e) {
                            System.out.println("[Retrofit] Could not read " + info + " in " + jar + ": " + e);
                        }
                    }
                }
            } catch (IOException e) {
                System.out.println("[Retrofit] Could not read " + jar + ": " + e);
            }
        }
        // Mods asking for one library at different versions get the newest, as DepLoader merges them (ForgeMultipart's
        // CodeChickenLib 1.1.3.127, whose address is gone, and CodeChickenCore's 1.1.3.141)
        java.util.Map<String, Integer> newest = new java.util.LinkedHashMap<>();
        for (int i = 0; i < wanted.size(); i++) {
            Integer before = newest.get(library(wanted.get(i).file()));
            if (before == null || compareVersions(wanted.get(i).file(), wanted.get(before).file()) > 0) {
                newest.put(library(wanted.get(i).file()), i);
            }
        }
        List<Dependency> missing = new ArrayList<>();
        for (int i : newest.values()) {
            Dependency dep = wanted.get(i);
            String file = dep.file().toLowerCase(Locale.ROOT);
            String prefix = library(file); // "baubles" of Baubles-1.7.10-1.0.1.10.jar
            boolean present = wantedClasses.get(i) != null && classes.contains(wantedClasses.get(i))
                    || names.contains(file) || names.stream().anyMatch(name -> name.startsWith(prefix));
            if (!present) {
                missing.add(dep);
            }
        }
        return missing;
    }

    /** A library's name in its file name: what comes before the first '-', in lower case. */
    private static String library(String file) {
        String lower = file.toLowerCase(Locale.ROOT);
        return lower.contains("-") ? lower.substring(0, lower.indexOf('-')) : lower;
    }

    /** Compares two file names of one library by the numbers in them, in order (1.7.10-1.1.3.141 after 1.7.10-1.1.3.127). */
    static int compareVersions(String a, String b) {
        String[] left = a.replaceAll("[^0-9]+", " ").trim().split(" ");
        String[] right = b.replaceAll("[^0-9]+", " ").trim().split(" ");
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            int compared = new java.math.BigInteger(left[i].isEmpty() ? "0" : left[i]).compareTo(new java.math.BigInteger(right[i].isEmpty() ? "0" : right[i]));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    /** Where mods wait for a dependency they couldn't get (see {@link #setAside}), in the legacy game directory. */
    private static final String WAITING = "waiting-for-libraries";

    /**
     * Moves the mods set aside at an earlier start back into the mods folder: their dependency may be there now,
     * and they are checked again with the others.
     */
    public static void bringBack(Path legacyDir) {
        Path waiting = legacyDir.resolve(WAITING);
        if (!Files.isDirectory(waiting)) {
            return;
        }
        try (Stream<Path> files = Files.list(waiting)) {
            for (Path jar : files.toList()) {
                Path back = legacyDir.resolve("mods").resolve(jar.getFileName());
                if (!Files.exists(back)) {
                    Files.move(jar, back);
                }
            }
        } catch (IOException e) {
            System.out.println("[Retrofit] Could not bring back the mods in " + waiting + ": " + e);
        }
    }

    /**
     * The mods wanting these dependencies move out of the mods folder into retrofit/waiting-for-libraries (moved,
     * not deleted; they come back at every start, see bringBack): their DepLoader fails without the dependency,
     * and its failure stopped every 1.7.10 mod. One line per mod, for the player.
     */
    public static List<String> setAside(Path legacyDir, List<Dependency> missing) {
        List<String> lines = new ArrayList<>();
        Path waiting = legacyDir.resolve(WAITING);
        Set<String> moved = new HashSet<>();
        for (Dependency dependency : missing) {
            for (Path dir : new Path[]{legacyDir.resolve("mods"), legacyDir.resolve("mods").resolve("1.7.10")}) {
                Path jar = dir.resolve(dependency.wantedBy());
                if (!moved.contains(dependency.wantedBy()) && Files.isRegularFile(jar)) {
                    try {
                        Files.createDirectories(waiting);
                        Files.move(jar, waiting.resolve(jar.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        moved.add(dependency.wantedBy());
                    } catch (IOException e) {
                        System.out.println("[Retrofit] Could not set aside " + jar + ": " + e);
                    }
                }
            }
            lines.add(dependency.wantedBy() + ": needs " + dependency.file() + ", which isn't there (put it in retrofit/mods; from "
                    + dependency.url() + ")");
        }
        return lines;
    }

    /** Downloads a dependency where its mod's DepLoader looks for it (needs {@link LegacyInstall#allowDownloads}). */
    public static void fetch(Dependency dependency, Path modsDir) throws IOException {
        Library.downloadUnverified(dependency.url(), modsDir.resolve("1.7.10").resolve(dependency.file()));
    }

    private static List<Path> jars(Path modsDir) {
        List<Path> jars = new ArrayList<>();
        for (Path dir : new Path[]{modsDir, modsDir.resolve("1.7.10")}) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(file -> {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    return Files.isRegularFile(file) && (name.endsWith(".jar") || name.endsWith(".zip"));
                }).forEach(jars::add);
            } catch (IOException e) {
                System.out.println("[Retrofit] Could not list " + dir + ": " + e);
            }
        }
        return jars;
    }
}
