package retrofit.forge.world;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * FML 1.7.10's check of a server's and a client's 1.7.10 mods, once a player joins a dedicated server
 * (Forge's handshake can only ask both sides for the same channel version, but a client may have mods
 * the server hasn't: Mouse Tweaks, minimaps). Each mod checks the other side's list by its own rule
 * (its acceptableRemoteVersions, or its own handler):
 *
 * - the server sends its mods; the client checks them, and answers with its own mods and the server
 *   mods its mods refused;
 * - the server checks the client's mods, and turns the player away naming every mod that differs.
 */
public final class LegacyModCheck {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Payload channels (not legacy ones): the server's mods, a client's answer. */
    public static final String SERVER_MODS = "retrofit:mods";
    public static final String CLIENT_MODS = "retrofit:mods-reply";
    /** Mods named on the screen of a player turned away, at most. */
    private static final int SHOWN = 12;

    private LegacyModCheck() {
    }

    // ---- Server ----

    static void sendServerMods(ServerPlayer player) {
        try {
            LegacyNetwork.sendTo(player, SERVER_MODS, LegacyBridge.<String>call("modVersions").getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            LegacyErrors.report("1.7.10 mod list", "being sent to a player", e);
        }
    }

    /** A client's answer: "its mods" NUL "the server mods it refused, one per line". */
    static void clientMods(ServerPlayer player, byte[] data) {
        String[] parts = new String(data, StandardCharsets.UTF_8).split("\0", -1);
        List<String> refused = new ArrayList<>();
        if (parts.length > 1 && !parts[1].isEmpty()) {
            refused.addAll(Arrays.asList(parts[1].split("\n")));
        }
        try {
            for (String mod : LegacyBridge.<String[]>call("checkRemoteMods", parts[0], false)) {
                if (!refused.contains(mod)) {
                    refused.add(mod);
                }
            }
        } catch (RuntimeException e) {
            LegacyErrors.report("1.7.10 mod list", "being checked", e);
            return;
        }
        if (refused.isEmpty()) {
            return;
        }
        LOGGER.warn("{}'s 1.7.10 mods differ from the server's, so they can't join: {}", player.getGameProfile().getName(), refused);
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < refused.size() && i < SHOWN; i++) {
            list.append(i > 0 ? "\n" : "").append(refused.get(i));
        }
        if (refused.size() > SHOWN) {
            list.append('\n').append(Component.translatable("retrofit.mismatch.more", refused.size() - SHOWN).getString());
        }
        player.connection.disconnect(Component.translatable("retrofit.mismatch.mods", list.toString()));
    }

    // ---- Client ----

    /** The server's mods: checked here, and answered with this client's. */
    public static void serverMods(byte[] data) {
        String[] refused;
        try {
            refused = LegacyBridge.call("checkRemoteMods", new String(data, StandardCharsets.UTF_8), true);
        } catch (RuntimeException e) {
            LegacyErrors.report("the server's 1.7.10 mod list", "being checked", e);
            refused = new String[0];
        }
        String answer = LegacyBridge.<String>call("modVersions") + "\0" + String.join("\n", refused);
        LegacyNetwork.sendToServer(CLIENT_MODS, answer.getBytes(StandardCharsets.UTF_8));
    }
}
