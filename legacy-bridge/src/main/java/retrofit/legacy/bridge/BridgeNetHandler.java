package retrofit.legacy.bridge;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.network.play.server.S3FPacketCustomPayload;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.IChatComponent;
import retrofit.api.HostPlayer;

import java.lang.reflect.Field;

/**
 * The network handler of a bridged server player. There is no legacy connection: packets that
 * mean something to the host player (chat, teleports) are forwarded to it, the rest dropped
 * (blocks, tile entities and inventories reach clients through the host).
 */
final class BridgeNetHandler extends NetHandlerPlayServer {
    private static Field chatComponent;

    private final HostPlayer host;

    BridgeNetHandler(MinecraftServer server, EntityPlayerMP player, HostPlayer host) {
        super(server, new NetworkManager(false), player);
        this.host = host;
    }

    /** sendPacket */
    @Override
    public void func_147359_a(Packet packet) {
        if (packet instanceof FMLProxyPacket) {
            FMLProxyPacket proxy = (FMLProxyPacket) packet;
            byte[] data = new byte[proxy.payload().readableBytes()];
            proxy.payload().getBytes(proxy.payload().readerIndex(), data);
            Network.customPayload(field_147369_b, proxy.channel(), data);
        } else if (packet instanceof S3FPacketCustomPayload) {
            Object[] payload = fields(packet, "field_149172_a", "field_149171_b"); // channel, data
            if (payload != null) {
                Network.customPayload(field_147369_b, (String) payload[0], (byte[]) payload[1]);
            }
        } else if (packet instanceof S35PacketUpdateTileEntity) {
            Object[] position = fields(packet, "field_148863_a", "field_148861_b", "field_148862_c"); // x, y, z
            if (position != null) { // a mod sent a tile entity update itself: resend the block entity's data
                field_147369_b.field_70170_p.func_147471_g((Integer) position[0], (Integer) position[1], (Integer) position[2]); // markBlockForUpdate
            }
        } else if (packet instanceof S02PacketChat) {
            IChatComponent component = chatOf((S02PacketChat) packet);
            if (component != null) {
                host.sendMessage(IChatComponent.Serializer.func_150696_a(component));
            }
        }
    }

    /** setPlayerLocation */
    @Override
    public void func_147364_a(double x, double y, double z, float yaw, float pitch) {
        host.teleport(x, y, z, yaw, pitch);
        field_147369_b.func_70080_a(x, y, z, yaw, pitch); // playerEntity.setPositionAndRotation
    }

    /** kickPlayerFromServer: the host decides who stays connected. */
    @Override
    public void func_147360_c(String reason) {
        System.out.println("[Retrofit] A legacy mod asked to kick a player (" + reason + "); ignored");
    }

    private static Object[] fields(Object packet, String... names) {
        try {
            Object[] values = new Object[names.length];
            for (int i = 0; i < names.length; i++) {
                Field field = packet.getClass().getDeclaredField(names[i]);
                field.setAccessible(true);
                values[i] = field.get(packet);
            }
            return values;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static IChatComponent chatOf(S02PacketChat packet) {
        try {
            if (chatComponent == null) {
                Field field = S02PacketChat.class.getDeclaredField("field_148919_a");
                field.setAccessible(true);
                chatComponent = field;
            }
            return (IChatComponent) chatComponent.get(packet);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
