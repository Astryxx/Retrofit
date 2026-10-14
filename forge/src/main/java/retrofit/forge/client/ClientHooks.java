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
