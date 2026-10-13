package retrofit.host;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** One library entry from a launcher version file. */
public record Library(String group, String artifact, String version, String repository, List<String> sha1s) {
    private static final String MOJANG_LIBRARIES = "https://libraries.minecraft.net/";
    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    static Library parse(JsonObject json) {
        String[] coords = json.get("name").getAsString().split(":");
        String repository = json.has("url") ? json.get("url").getAsString() : null;
        List<String> sha1s = new ArrayList<>();
        if (json.has("checksums")) {
            for (JsonElement checksum : json.getAsJsonArray("checksums")) {
                sha1s.add(checksum.getAsString());
            }
        }
        return new Library(coords[0], coords[1], coords[2], repository, sha1s);
    }

    String relativePath() {
        return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".jar";
    }

    /** Prefers the launcher's copy; otherwise downloads into the cache and verifies it. */
    Path locate(Path launcherLibraries, Path cacheLibraries) throws IOException {
        Path installed = launcherLibraries.resolve(relativePath());
        if (Files.isRegularFile(installed)) {
            return installed;
        }
        Path cached = cacheLibraries.resolve(relativePath());
        if (Files.isRegularFile(cached)) {
            return cached;
        }
        IOException failure = new IOException("Could not download library " + group + ":" + artifact + ":" + version);
        for (String base : repositories()) {
            try {
                download(base, cached);
                return cached;
            } catch (IOException e) {
                failure.addSuppressed(e);
            }
        }
        throw failure;
    }

    private List<String> repositories() {
        List<String> repositories = new ArrayList<>();
        if (repository != null) {
            repositories.add(repository.endsWith("/") ? repository : repository + "/");
        }
        repositories.add(MOJANG_LIBRARIES);
        repositories.add(MAVEN_CENTRAL);
        return repositories;
    }

    private void download(String base, Path target) throws IOException {
        String url = base + relativePath();
        byte[] jar = fetch(url);
        List<String> expected = new ArrayList<>(sha1s);
        if (expected.isEmpty()) {
            expected.add(new String(fetch(url + ".sha1")).trim().split("\\s+")[0]);
        }
        String actual = sha1(jar);
        if (!expected.contains(actual)) {
            throw new IOException("Checksum mismatch for " + url + ": got " + actual + ", expected " + expected);
        }
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), artifact, ".part");
        Files.write(temp, jar);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        System.out.println("[Retrofit] Downloaded " + url);
    }

    private static byte[] fetch(String url) throws IOException {
        try {
            HttpResponse<InputStream> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode() + " for " + url);
                }
                return body.readAllBytes();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted downloading " + url, e);
        }
    }

    private static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
