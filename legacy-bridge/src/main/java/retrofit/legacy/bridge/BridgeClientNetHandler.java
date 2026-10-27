package retrofit.legacy.bridge;

import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C17PacketCustomPayload;

import java.lang.reflect.Field;

/**
 * Client only. The client network handler legacy code sees (player.sendQueue and FML's client
 * message context). It has no connection: mod packets sent through it go to the host's channel,
 * everything else is dropped (the host's own connection carries the game). Created without a
 * constructor (see LegacyWorlds.clientNetHandler).
 */
final class BridgeClientNetHandler extends NetHandlerPlayClient {
    private BridgeClientNetHandler() {
        super(null, null, null);
    }

    private net.minecraft.network.NetworkManager manager;

    /** The server the host is connected to, as 1.7.10's connection named it (see setServerAddress). */
    private static volatile java.net.SocketAddress serverAddress = new ServerAddress(null, null, 0);

    /**
     * The host joined a server ({@code host} null: the integrated one). Mods read the connection's address
     * (getSocketAddress().toString()): NEI names its per-server settings by it.
     */
    static void setServerAddress(String host, String ip, int port) {
        serverAddress = new ServerAddress(host, ip, port);
    }

    /** A connection address printed as 1.7.10's were: "host/ip:port" for a server, "local:server" for the integrated one. */
    private static final class ServerAddress extends java.net.SocketAddress {
        private final String text;

        ServerAddress(String host, String ip, int port) {
            text = host == null ? "local:server" : host + "/" + (ip != null ? ip : host) + ":" + port;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /**
     * getNetworkManager: FML builds client packet events from it (FMLNetworkEvent.ClientCustomPacketEvent
     * asks it for this handler). A client-side manager with no channel, pointing back here.
     */
    @Override
    public synchronized net.minecraft.network.NetworkManager func_147298_b() {
        if (manager == null) {
            manager = new net.minecraft.network.NetworkManager(true);
            manager.func_150719_a(this); // setNetHandler
        }
        if (manager.func_74430_c() != serverAddress) { // getSocketAddress: the server joined last
            try {
                Field address = net.minecraft.network.NetworkManager.class.getDeclaredField("field_150743_l"); // socketAddress
                address.setAccessible(true);
                address.set(manager, serverAddress);
            } catch (ReflectiveOperationException e) {
                // left without one
            }
        }
        return manager;
    }

    /** addToSendQueue */
    @Override
    public void func_147297_a(Packet packet) {
        Object player = Minecraft.func_71410_x().field_71439_g; // thePlayer
        if (!(player instanceof BridgeClientPlayer)) {
            return;
        }
        if (packet instanceof FMLProxyPacket) {
            FMLProxyPacket proxy = (FMLProxyPacket) packet;
            byte[] data = new byte[proxy.payload().readableBytes()];
            proxy.payload().getBytes(proxy.payload().readerIndex(), data);
            Network.customPayload((BridgeClientPlayer) player, proxy.channel(), data);
        } else if (packet instanceof C17PacketCustomPayload) {
            try {
                Field channel = C17PacketCustomPayload.class.getDeclaredField("field_149562_a");
                Field data = C17PacketCustomPayload.class.getDeclaredField("field_149561_c");
                channel.setAccessible(true);
                data.setAccessible(true);
                Network.customPayload((BridgeClientPlayer) player, (String) channel.get(packet), (byte[]) data.get(packet));
            } catch (ReflectiveOperationException ignored) {
                // Not a packet anyone can read; drop it.
            }
        }
    }
}
