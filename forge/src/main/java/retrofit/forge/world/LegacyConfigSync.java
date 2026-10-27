package retrofit.forge.world;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * A dedicated server's 1.7.10 mod configs (retrofit/config) and dimension id choices (retrofit/dimension-ids.txt)
 * for its players, as a 1.7.10 pack had them the same on both sides: mods read their configs once, at startup, and
 * a client whose settings differ sees the server's content numbered and behaving otherwise (Witchery's potions).
 *
 * When a player joins, the server lists its files with a hash each. A client with different ones asks the player,
 * and if they agree, gets those files, keeps its own in retrofit/config-backup/(time), writes the server's and asks
 * for a restart (configs can't be read again while playing). Files whose names mention "client" are each player's
 * own (keys, HUD placement) and never sent. The client only writes paths it asked for, inside retrofit/config.
 */
public final class LegacyConfigSync {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Payload channels (not legacy ones): the server's list, a client's request, the files. */
    public static final String LIST = "retrofit:configs";
    public static final String REQUEST = "retrofit:config-request";
    public static final String FILES = "retrofit:config-files";
    private static final String DIMENSION_IDS = "dimension-ids.txt";
    private static final long MAX_FILE = 1 << 20;
    private static final long MAX_TOTAL = 16 << 20;
    private static final int CHUNK = 256 << 10;

    private LegacyConfigSync() {
    }

    private static Path legacyDir() {
        return FMLPaths.GAMEDIR.get().resolve("retrofit");
    }

    /** Whether a file (path relative to retrofit/, "/"-separated) is one servers share. */
    static boolean shared(String relative) {
        if (relative.equals(DIMENSION_IDS)) {
            return true;
        }
        if (!relative.startsWith("config/") || relative.contains("..")) {
            return false;
        }
        String name = relative.substring(relative.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return !name.contains("client") && !name.endsWith(".log") && !name.endsWith(".bak") && !name.endsWith(".lock") && !name.startsWith(".");
    }

    /** The shared files here: relative path -> SHA-1 (hex). */
    public static Map<String, String> fingerprints() {
        Map<String, String> result = new TreeMap<>();
        Path root = legacyDir();
        List<Path> files = new ArrayList<>();
        Path config = root.resolve("config");
        if (Files.isDirectory(config)) {
            try (Stream<Path> walk = Files.walk(config)) {
                walk.filter(Files::isRegularFile).forEach(files::add);
            } catch (IOException e) {
                LOGGER.warn("Could not list the 1.7.10 configs", e);
            }
        }
        if (Files.isRegularFile(root.resolve(DIMENSION_IDS))) {
            files.add(root.resolve(DIMENSION_IDS));
        }
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            try {
                if (shared(relative) && Files.size(file) <= MAX_FILE) {
                    result.put(relative, sha1(Files.readAllBytes(file)));
                }
            } catch (IOException e) {
                // left out
            }
        }
        return result;
    }

    private static String sha1(byte[] bytes) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-1").digest(bytes)) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- Server ----

    /** A player joined a dedicated server: the list of its shared files. */
    static void sendList(ServerPlayer player) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            Map<String, String> files = fingerprints();
            out.writeInt(files.size());
            for (Map.Entry<String, String> file : files.entrySet()) {
                out.writeUTF(file.getKey());
                out.writeUTF(file.getValue());
            }
            LegacyNetwork.sendTo(player, LIST, bytes.toByteArray());
        } catch (IOException e) {
            LOGGER.warn("Could not send the 1.7.10 config list", e);
        }
    }

    /** A player asked for files of the list: sent in pieces, the last one marked. */
    static void requested(ServerPlayer player, byte[] request) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(request));
            int count = in.readInt();
            Map<String, String> shared = fingerprints();
            List<Object[]> files = new ArrayList<>();
            long total = 0;
            for (int i = 0; i < count && i < 10000; i++) {
                String relative = in.readUTF();
                if (!shared.containsKey(relative)) {
                    continue; // only what was offered
                }
                byte[] content = Files.readAllBytes(legacyDir().resolve(relative));
                total += content.length;
                if (total > MAX_TOTAL) {
                    break;
                }
                files.add(new Object[]{relative, content});
            }
            LOGGER.info("Sending {} 1.7.10 config files to {}", files.size(), player.getGameProfile().getName());
            ByteArrayOutputStream piece = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(piece);
            for (int i = 0; i < files.size(); i++) {
                byte[] content = (byte[]) files.get(i)[1];
                out.writeBoolean(true); // a file follows
                out.writeUTF((String) files.get(i)[0]);
                out.writeInt(content.length);
                out.write(content);
                if (piece.size() >= CHUNK || i == files.size() - 1) {
                    out.writeBoolean(false);
                    out.writeBoolean(i == files.size() - 1); // the last piece
                    LegacyNetwork.sendTo(player, FILES, piece.toByteArray());
                    piece = new ByteArrayOutputStream();
                    out = new DataOutputStream(piece);
                }
            }
            if (files.isEmpty()) {
                out.writeBoolean(false);
                out.writeBoolean(true);
                LegacyNetwork.sendTo(player, FILES, piece.toByteArray());
            }
        } catch (IOException e) {
            LOGGER.warn("Could not send 1.7.10 configs to {}", player.getGameProfile().getName(), e);
        }
    }

    // ---- Client ----

    /** The files of a server's list this client lacks or has otherwise. */
    public static List<String> differing(byte[] list) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(list));
        int count = in.readInt();
        Map<String, String> mine = fingerprints();
        List<String> differing = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String relative = in.readUTF();
            String hash = in.readUTF();
            if (shared(relative) && !hash.equals(mine.get(relative))) {
                differing.add(relative);
            }
        }
        return differing;
    }

    public static byte[] request(List<String> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(files.size());
        for (String file : files) {
            out.writeUTF(file);
        }
        return bytes.toByteArray();
    }

    private static final Map<String, byte[]> RECEIVED = new LinkedHashMap<>();
    private static List<String> asked = List.of();

    public static synchronized void asking(List<String> files) {
        asked = List.copyOf(files);
        RECEIVED.clear();
    }

    /** A piece of files; returns where the client's own were kept once the last piece is written, else null. */
    public static synchronized Path received(byte[] piece) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(piece));
        while (in.readBoolean()) {
            String relative = in.readUTF();
            byte[] content = new byte[in.readInt()];
            in.readFully(content);
            if (asked.contains(relative) && shared(relative)) {
                RECEIVED.put(relative, content);
            }
        }
        if (!in.readBoolean()) {
            return null;
        }
        Path root = legacyDir().toAbsolutePath().normalize();
        Path backup = root.resolve("config-backup").resolve(java.time.LocalDateTime.now().toString().replace(':', '-'));
        for (Map.Entry<String, byte[]> file : RECEIVED.entrySet()) {
            Path target = root.resolve(file.getKey()).normalize();
            if (!target.startsWith(root)) {
                continue;
            }
            if (Files.isRegularFile(target)) {
                Path kept = backup.resolve(file.getKey());
                Files.createDirectories(kept.getParent());
                Files.copy(target, kept);
            }
            Files.createDirectories(target.getParent());
            Files.write(target, file.getValue());
        }
        LOGGER.info("Wrote {} 1.7.10 config files from the server; the earlier ones are in {}", RECEIVED.size(), backup);
        RECEIVED.clear();
        return backup;
    }
}
