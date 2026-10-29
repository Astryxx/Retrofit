package retrofit.forge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import retrofit.forge.world.LegacyBridge;
import retrofit.forge.world.LegacyMenu;
import retrofit.forge.world.LegacyNetwork;
import retrofit.forge.world.LegacyPlayers;

/** Client-only calls made from common code; only invoked on the client, so this class only loads there. */
public final class ClientHooks {
    private ClientHooks() {
    }

    /**
     * The server's 1.7.10 config list: when this client's differ, the player is asked whether to use the server's
     * (they take effect after a restart; see LegacyConfigSync).
     */
    private static void serverConfigs(byte[] list) {
        java.util.List<String> differing;
        try {
            differing = retrofit.forge.world.LegacyConfigSync.differing(list);
        } catch (java.io.IOException e) {
            return;
        }
        if (differing.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        java.util.Set<String> mods = new java.util.TreeSet<>();
        for (String file : differing) {
            String name = file.substring(file.lastIndexOf('/') + 1);
            mods.add(name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name);
        }
        String shown = String.join(", ", mods.stream().limit(8).toList()) + (mods.size() > 8 ? ", ..." : "");
        Screen previous = minecraft.screen;
        minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(useThem -> {
            if (!useThem) {
                disconnect(Component.literal("You kept your own 1.7.10 mod configs. This server's differ (" + shown
                        + "), so its 1.7.10 content may not match yours: join again to use the server's."));
                return;
            }
            try {
                retrofit.forge.world.LegacyConfigSync.asking(differing);
                LegacyNetwork.sendToServer(retrofit.forge.world.LegacyConfigSync.REQUEST, retrofit.forge.world.LegacyConfigSync.request(differing));
                minecraft.setScreen(new net.minecraft.client.gui.screens.GenericDirtMessageScreen(Component.literal("Getting the server's 1.7.10 mod configs...")));
            } catch (java.io.IOException e) {
                minecraft.setScreen(previous);
            }
        }, Component.literal("This server's 1.7.10 mod configs differ from yours"),
                Component.literal(differing.size() + " config files differ (" + shown + "). 1.7.10 mods read their configs when the game"
                        + " starts, so play here with the server's: Retrofit saves them (yours are kept in retrofit/config-backup),"
                        + " then you restart the game and join again."),
                Component.literal("Use the server's"), Component.literal("Keep mine and leave")));
    }

    private static void serverConfigFiles(byte[] piece) {
        try {
            java.nio.file.Path backup = retrofit.forge.world.LegacyConfigSync.received(piece);
            if (backup != null) {
                disconnect(Component.literal("The server's 1.7.10 mod configs are saved. Restart the game, then join again."
                        + " Your earlier configs are in " + relative(backup) + "."));
            }
        } catch (java.io.IOException e) {
            disconnect(Component.literal("The server's 1.7.10 mod configs could not be saved: " + e.getMessage()));
        }
    }

    private static void disconnect(Component reason) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            connection.getConnection().disconnect(reason);
        }
    }

    /** A path as shown to the player: relative to the game folder. */
    private static String relative(java.nio.file.Path path) {
        java.nio.file.Path game = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        return path.startsWith(game) ? game.relativize(path).toString() : path.toString();
    }

    /** The signed-in player's name and UUID (no dashes), for the legacy client's Session. */
    public static String[] user() {
        var user = Minecraft.getInstance().getUser();
        return new String[]{user.getName(), user.getProfileId() == null ? "" : user.getProfileId().toString().replace("-", "")};
    }

    public static void setBlocksDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.levelRenderer != null) {
            minecraft.levelRenderer.setBlocksDirty(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    public static net.minecraft.world.entity.player.Player localPlayer() {
        return Minecraft.getInstance().player;
    }

    /** A legacy mod packet from the server, for the local player. */
    public static void legacyPayload(String channel, byte[] data) {
        if (channel.equals(retrofit.forge.world.LegacyEvents.IDS_CHANNEL)) {
            LegacyBridge.call("applyIds", (Object) data);
            return;
        }
        if (channel.equals(retrofit.forge.world.LegacyModCheck.SERVER_MODS)) {
            retrofit.forge.world.LegacyModCheck.serverMods(data);
            return;
        }
        if (channel.equals(retrofit.forge.world.LegacyConfigSync.LIST)) {
            serverConfigs(data);
            return;
        }
        if (channel.equals(retrofit.forge.world.LegacyConfigSync.FILES)) {
            serverConfigFiles(data);
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            LegacyNetwork.deliver(new LegacyNetwork.Payload(channel, data), LegacyPlayers.mirror(player, true));
        }
    }

    public static void entityData(int entityId, byte[] data) {
        if (Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.getEntity(entityId) instanceof retrofit.forge.world.LegacyEntityCarrier carrier) {
            carrier.core().watcher(data);
        }
    }

    public static void entityEvent(int entityId, int event) {
        if (Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.getEntity(entityId) instanceof retrofit.forge.world.LegacyEntityCarrier carrier) {
            carrier.core().entityEvent(event);
        }
    }

    /** A legacy container property (progress bar) from the server. */
    public static void windowProperty(int windowId, int property, int value) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.containerMenu instanceof LegacyMenu menu && menu.containerId == windowId) {
            menu.legacyProperty(property, value);
        }
    }

    /** Legacy client code opened a screen (or closed the current one with null). */
    public static void displayLegacyScreen(Object legacyScreen) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (legacyScreen == null) {
            closeLegacyScreen();
            return;
        }
        if (player == null) {
            return;
        }
        Screen screen;
        if (LegacyBridge.call("guiContainer", legacyScreen) != null) {
            LegacyMenu menu = LegacyMenu.clientOnly(player, legacyScreen);
            player.containerMenu = menu;
            screen = new LegacyScreen(menu, player.getInventory(), Component.empty());
        } else {
            screen = new LegacyPlainScreen(legacyScreen);
        }
        minecraft.setScreen(screen);
    }

    /**
     * The legacy GUI on screen in a client-only menu, handed over to the server menu the host just
     * opened for the same container (see LegacyMenu.DIRECT); null if there is none.
     */
    public static Object takeClientOnlyScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof LegacyScreen screen && screen.getMenu().containerId == LegacyMenu.CLIENT_ONLY) {
            screen.handOver();
            return screen.getMenu().legacyScreen();
        }
        return null;
    }

    /** Legacy code closed its screen (closeScreen, or displayGuiScreen(null)). */
    public static void closeLegacyScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof LegacyScreen && minecraft.player != null) {
            if (minecraft.player.containerMenu.containerId == LegacyMenu.CLIENT_ONLY) {
                minecraft.player.clientSideCloseContainer();
            } else {
                minecraft.player.closeContainer();
            }
        } else if (minecraft.screen instanceof LegacyPlainScreen) {
            minecraft.setScreen(null);
        }
    }

    /** A slot click the open legacy container screen made. */
    public static void clickLegacySlot(int windowId, int slotId, int button, int mode) {
        if (Minecraft.getInstance().screen instanceof LegacyScreen screen && screen.getMenu().containerId == windowId) {
            screen.legacyClick(slotId, button, mode);
        }
    }
}
