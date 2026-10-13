package retrofit.legacy.bridge;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S02PacketChat;
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
        if (packet instanceof S02PacketChat) {
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
