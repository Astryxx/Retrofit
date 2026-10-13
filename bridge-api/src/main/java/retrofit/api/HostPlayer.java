package retrofit.api;

/**
 * One host (1.20.1) player, as seen by the legacy bridge: what the legacy mirror of a player
 * (a real EntityPlayerMP on servers, EntityClientPlayerMP on clients) needs from the real one
 * beyond the state copied into it around each legacy call. Implemented by the host.
 */
public interface HostPlayer {
    /** A chat message for this player, as 1.7.10 chat JSON (the 1.20.1 format reads it). */
    void sendMessage(String json);

    /** Legacy code moved the player (setPositionAndUpdate, setPlayerLocation); feet position. */
    void teleport(double x, double y, double z, float yaw, float pitch);
}
