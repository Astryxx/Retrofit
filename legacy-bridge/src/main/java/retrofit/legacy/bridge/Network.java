package retrofit.legacy.bridge;

import cpw.mods.fml.common.network.FMLEmbeddedChannel;
import cpw.mods.fml.common.network.FMLOutboundHandler;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.INetHandler;
import retrofit.api.HostNetwork;
import retrofit.api.HostPlayer;

import java.lang.reflect.Field;

/**
 * Mod packets. 1.7.10 mods talk over FML channels (SimpleNetworkWrapper, FMLEventChannel, raw
 * custom payloads); there is no legacy connection here, so:
 *
 * - Outbound: FML's outbound handler is redirected to {@link #outbound} (see HeadlessTransformer).
 *   Each encoded packet goes to the host with its channel name and target (a player, everyone,
 *   a dimension, an area, or the server), and the host carries it on its own channel.
 * - Inbound: the host hands packets to {@link #inbound}, which feeds them to the same legacy
 *   channel with the receiving side's network handler set, so mods' handlers see the player as
 *   usual (ctx.getServerHandler().playerEntity is the player's mirror).
 *
 * The host runs inbound packets on the game thread (1.7.10 ran SimpleNetworkWrapper handlers on
 * the network thread; mods written for that are fine on the game thread).
 */
public final class Network {
    private static volatile HostNetwork host;
    /** The player whose packet is being handled on this thread, for REPLY targets. */
    private static final ThreadLocal<EntityPlayer> REPLY_TO = new ThreadLocal<EntityPlayer>();
    private static Field packetHandler;

    private Network() {
    }

    public static void setHost(HostNetwork network) {
        host = network;
    }

    /** Redirect target of FMLOutboundHandler.write. */
    public static void outbound(FMLOutboundHandler handler, ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        promise.setSuccess();
        if (!(message instanceof FMLProxyPacket) || host == null) {
            return;
        }
        FMLProxyPacket packet = (FMLProxyPacket) message;
        FMLOutboundHandler.OutboundTarget target = ctx.channel().attr(FMLOutboundHandler.FML_MESSAGETARGET).get();
        Object args = ctx.channel().attr(FMLOutboundHandler.FML_MESSAGETARGETARGS).get();
        String channel = packet.channel();
        byte[] data = bytes(packet.payload());
        if (target == FMLOutboundHandler.OutboundTarget.TOSERVER) {
            host.sendToServer(channel, data);
        } else if (target == FMLOutboundHandler.OutboundTarget.PLAYER) {
            sendTo(args, channel, data);
        } else if (target == FMLOutboundHandler.OutboundTarget.ALL) {
            host.sendToPlayers(channel, data, Integer.MIN_VALUE, 0, 0, 0, -1);
        } else if (target == FMLOutboundHandler.OutboundTarget.DIMENSION) {
            host.sendToPlayers(channel, data, (Integer) args, 0, 0, 0, -1);
        } else if (target == FMLOutboundHandler.OutboundTarget.ALLAROUNDPOINT) {
            NetworkRegistry.TargetPoint point = (NetworkRegistry.TargetPoint) args;
            host.sendToPlayers(channel, data, point.dimension, point.x, point.y, point.z, point.range);
        } else if (target == FMLOutboundHandler.OutboundTarget.REPLY) {
            EntityPlayer player = REPLY_TO.get();
            if (player == null || player.field_70170_p.field_72995_K) { // a reply on the client goes to the server
                host.sendToServer(channel, data);
            } else {
                sendTo(player, channel, data);
            }
        }
        // DISPATCHER and NOWHERE: FML's own handshake traffic, which the host's connection replaces.
    }

    private static void sendTo(Object player, String channel, byte[] data) {
        HostPlayer target = player instanceof BridgePlayerMP ? ((BridgePlayerMP) player).host : null;
        if (target != null) {
            target.sendPayload(channel, data);
        }
    }

    /** A raw custom payload sent outside FML channels (S3F/C17 packets); see the bridged net handlers. */
    static void customPayload(EntityPlayer from, String channel, byte[] data) {
        if (host == null) {
            return;
        }
        if (from.field_70170_p.field_72995_K) {
            host.sendToServer(channel, data);
        } else {
            sendTo(from, channel, data);
        }
    }

    /**
     * A packet from the other side for a legacy channel; {@code player} is the receiving client's
     * mirror, or on servers the sender's. Returns false if no legacy channel has that name.
     */
    public static boolean inbound(String channel, byte[] data, Object player) throws ReflectiveOperationException {
        EntityPlayer mirror = (EntityPlayer) player;
        boolean client = mirror.field_70170_p.field_72995_K; // worldObj.isRemote
        Side side = client ? Side.CLIENT : Side.SERVER;
        if (!NetworkRegistry.INSTANCE.hasChannel(channel, side)) {
            return false;
        }
        FMLEmbeddedChannel embedded = NetworkRegistry.INSTANCE.getChannel(channel, side);
        INetHandler handler = client ? LegacyWorlds.clientNetHandler() : ((EntityPlayerMP) mirror).field_71135_a; // playerNetServerHandler
        FMLProxyPacket packet = new FMLProxyPacket(Unpooled.wrappedBuffer(data), channel);
        if (packetHandler == null) {
            Field field = FMLProxyPacket.class.getDeclaredField("netHandler");
            field.setAccessible(true);
            packetHandler = field;
        }
        packetHandler.set(packet, handler);
        embedded.attr(NetworkRegistry.NET_HANDLER).set(handler);
        REPLY_TO.set(mirror);
        try {
            embedded.writeInbound(packet);
        } finally {
            REPLY_TO.remove();
        }
        return true;
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] data = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), data);
        return data;
    }
}
