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
