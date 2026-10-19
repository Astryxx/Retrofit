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
        List<Dependency> missing = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < wanted.size(); i++) {
            Dependency dep = wanted.get(i);
            String file = dep.file().toLowerCase(Locale.ROOT);
            String prefix = file.contains("-") ? file.substring(0, file.indexOf('-')) : file; // "baubles" of Baubles-1.7.10-1.0.1.10.jar
            boolean present = wantedClasses.get(i) != null && classes.contains(wantedClasses.get(i))
                    || names.contains(file) || names.stream().anyMatch(name -> name.startsWith(prefix));
            if (!present && seen.add(file)) {
                missing.add(dep);
            }
        }
        return missing;
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
