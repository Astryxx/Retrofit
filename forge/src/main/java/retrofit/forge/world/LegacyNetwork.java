package retrofit.forge.world;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import retrofit.api.HostNetwork;
import retrofit.forge.RetrofitMod;

import java.util.function.Supplier;

/**
 * Retrofit's one 1.20.1 network channel ("retrofit:legacy"). It carries:
 *
 * - legacy mod packets ({@link Payload}: the FML channel name and the bytes the mod wrote), in
 *   both directions, handed to the same legacy channel on the other side on the game thread;
 * - legacy container properties ({@link WindowProperty}: progress bars and the like).
 *
 * The protocol version includes the list of legacy mods, so a client and server with different
 * 1.7.10 mods are told so when connecting instead of failing later.
 */
public final class LegacyNetwork {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PROTOCOL = "3"; // 3: entity sync carries watchable attributes
    private static SimpleChannel channel;
    private static String version;

    private LegacyNetwork() {
    }

    public static synchronized void register() {
        if (channel != null) {
            return;
        }
        channel = NetworkRegistry.newSimpleChannel(new ResourceLocation(RetrofitMod.MOD_ID, "legacy"),
                LegacyNetwork::version, LegacyNetwork::accepts, LegacyNetwork::accepts);
        channel.messageBuilder(Payload.class, 0).encoder(Payload::write).decoder(Payload::read)
                .consumerMainThread(LegacyNetwork::handle).add();
        channel.messageBuilder(WindowProperty.class, 1, NetworkDirection.PLAY_TO_CLIENT).encoder(WindowProperty::write)
                .decoder(WindowProperty::read).consumerMainThread(LegacyNetwork::handle).add();
        channel.messageBuilder(EntityData.class, 2, NetworkDirection.PLAY_TO_CLIENT).encoder(EntityData::write)
                .decoder(EntityData::read).consumerMainThread(LegacyNetwork::handle).add();
        channel.messageBuilder(EntityEvent.class, 3, NetworkDirection.PLAY_TO_CLIENT).encoder(EntityEvent::write)
                .decoder(EntityEvent::read).consumerMainThread(LegacyNetwork::handle).add();
        LegacyBridge.call("setHost", new Host());
    }

    private static String version() {
        if (version == null) {
            version = PROTOCOL + ":" + Integer.toHexString(LegacyBridge.<String>call("modList").hashCode());
        }
        return version;
    }

    private static boolean accepts(String other) {
        return version().equals(other);
    }

    // ---- Messages ----

    public record Payload(String legacyChannel, byte[] data) {
        void write(FriendlyByteBuf buffer) {
            buffer.writeUtf(legacyChannel);
            buffer.writeByteArray(data);
        }

        static Payload read(FriendlyByteBuf buffer) {
            return new Payload(buffer.readUtf(), buffer.readByteArray());
        }
    }

    public record WindowProperty(int windowId, int property, int value) {
        void write(FriendlyByteBuf buffer) {
            buffer.writeVarInt(windowId);
            buffer.writeVarInt(property);
            buffer.writeVarInt(value);
        }

        static WindowProperty read(FriendlyByteBuf buffer) {
            return new WindowProperty(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt());
        }
    }

    /** A legacy entity's DataWatcher changes, for its client copies. */
    public record EntityData(int entityId, byte[] data) {
        void write(FriendlyByteBuf buffer) {
            buffer.writeVarInt(entityId);
            buffer.writeByteArray(data);
        }

        static EntityData read(FriendlyByteBuf buffer) {
            return new EntityData(buffer.readVarInt(), buffer.readByteArray());
        }
    }

    /** World.setEntityState for a legacy entity. */
    public record EntityEvent(int entityId, int event) {
        void write(FriendlyByteBuf buffer) {
            buffer.writeVarInt(entityId);
            buffer.writeByte(event);
        }

        static EntityEvent read(FriendlyByteBuf buffer) {
            return new EntityEvent(buffer.readVarInt(), buffer.readByte());
        }
    }

    private static void handle(EntityData message, Supplier<NetworkEvent.Context> context) {
        retrofit.forge.client.ClientHooks.entityData(message.entityId(), message.data());
    }

    private static void handle(EntityEvent message, Supplier<NetworkEvent.Context> context) {
        retrofit.forge.client.ClientHooks.entityEvent(message.entityId(), message.event());
    }

    static void sendEntityData(net.minecraft.world.entity.Entity entity, byte[] data) {
        channel.send(PacketDistributor.TRACKING_ENTITY.with(() -> entity), new EntityData(entity.getId(), data));
    }

    static void sendEntityEvent(net.minecraft.world.entity.Entity entity, int event) {
        channel.send(PacketDistributor.TRACKING_ENTITY.with(() -> entity), new EntityEvent(entity.getId(), event));
    }

    private static void handle(Payload payload, Supplier<NetworkEvent.Context> context) {
        ServerPlayer sender = context.get().getSender();
        try {
            if (sender != null) {
                Object mirror = LegacyPlayers.mirror(sender, true);
                deliver(payload, mirror);
                LegacyPlayers.writeBack(sender, mirror);
            } else {
                retrofit.forge.client.ClientHooks.legacyPayload(payload.legacyChannel(), payload.data());
            }
        } catch (RuntimeException e) {
            LegacyErrors.report("packet on " + payload.legacyChannel(), "being handled", e);
        }
    }

    /** Hands a payload to its legacy channel; the mirror is the receiving client's player or the sending one. */
    public static void deliver(Payload payload, Object mirror) {
        if (!LegacyBridge.<Boolean>call("inbound", payload.legacyChannel(), payload.data(), mirror)) {
            LOGGER.debug("No legacy channel {} for a packet", payload.legacyChannel());
        }
    }

    private static void handle(WindowProperty property, Supplier<NetworkEvent.Context> context) {
        retrofit.forge.client.ClientHooks.windowProperty(property.windowId(), property.property(), property.value());
    }

    // ---- Sending ----

    static void sendTo(ServerPlayer player, String legacyChannel, byte[] data) {
        channel.send(PacketDistributor.PLAYER.with(() -> player), new Payload(legacyChannel, data));
    }

    static void sendWindowProperty(ServerPlayer player, int windowId, int property, int value) {
        channel.send(PacketDistributor.PLAYER.with(() -> player), new WindowProperty(windowId, property, value));
    }

    /** Where legacy code sends packets (see the legacy bridge's Network). */
    private static final class Host implements HostNetwork {
        @Override
        public void sendToServer(String legacyChannel, byte[] data) {
            channel.sendToServer(new Payload(legacyChannel, data));
        }

        @Override
        public void sendToPlayers(String legacyChannel, byte[] data, int dimension, double x, double y, double z, double range) {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return;
            }
            Payload payload = new Payload(legacyChannel, data);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (dimension != Integer.MIN_VALUE && LegacyContent.legacyDimension(player.level()) != dimension) {
                    continue;
                }
                if (range >= 0 && player.distanceToSqr(x, y, z) > range * range) {
                    continue;
                }
                channel.send(PacketDistributor.PLAYER.with(() -> player), payload);
            }
        }
    }
}
