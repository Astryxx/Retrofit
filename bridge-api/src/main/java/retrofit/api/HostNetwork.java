package retrofit.api;

/**
 * Where legacy mod packets go: the host carries each one (FML channel name and payload) over
 * its own network channel and hands it to the same legacy channel on the other side. Packets for
 * one player go through {@link HostPlayer#sendPayload}. Implemented by the host.
 */
public interface HostNetwork {
    /** Client: a packet for the server. */
    void sendToServer(String channel, byte[] data);

    /**
     * Server: a packet for every player, or those in one legacy dimension (when {@code dimension}
     * isn't {@link Integer#MIN_VALUE}), or those within {@code range} of a point there (range >= 0).
     */
    void sendToPlayers(String channel, byte[] data, int dimension, double x, double y, double z, double range);
}
