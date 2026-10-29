package retrofit.api;

/**
 * One host (1.20.1) player, as seen by the legacy bridge: what the legacy mirror of a player
 * (a real EntityPlayerMP on servers, EntityClientPlayerMP on clients) needs from the real one
 * beyond the state copied into it around each legacy call. Implemented by the host.
 */
public interface HostPlayer {
    /** The host player (opaque): legacy code attacking with a player's mirror attacks as that player. */
    default Object hostEntity() {
        return null;
    }

    /** The host player's entity id (the same on the server and its clients), or -1 if not known. */
    default int entityId() {
        return -1;
    }

    /** A mod unlocked an achievement (its stat id): the player gets its advancement. */
    default void grantAchievement(String statId) {
    }

    /** A chat message for this player, as 1.7.10 chat JSON (the 1.20.1 format reads it). */
    void sendMessage(String json);

    /** Legacy code moved the player (setPositionAndUpdate, setPlayerLocation); feet position. */
    void teleport(double x, double y, double z, float yaw, float pitch);

    // ---- Server side ----

    /**
     * Legacy code hurt the player (attackEntityFrom on its mirror): kind is the 1.7.10 damage type,
     * attacker the legacy entity responsible (or null).
     */
    boolean hurt(String kind, Object legacyAttacker, float amount);

    /** Absolute damage explicitly allowed against creative players (a mod's kill command). */
    default boolean hurtAbsolute(String kind, Object legacyAttacker, float amount) {
        return hurt(kind, legacyAttacker, amount);
    }

    /** The player's permission level (0 to 4; operators usually 4), for legacy command checks. */
    int permissionLevel();

    /**
     * A mod opened a container GUI (FML openGui). The host opens a menu over the legacy container
     * and has the client build the matching GUI; returns the window id, or -1 if it couldn't.
     */
    int openMenu(Object legacyContainer, String modId, int guiId, int x, int y, int z);

    /** A legacy container's progress bar changed (sendProgressBarUpdate). */
    void windowProperty(int windowId, int property, int value);

    /** A mod packet for this player's client (FML channel name and payload). */
    void sendPayload(String channel, byte[] data);

    // ---- Both sides ----

    /** Legacy code closed the open container or screen (closeScreen). */
    void closeMenu();

    // ---- Client side ----

    /** Client: a legacy screen should be shown (Minecraft.displayGuiScreen), or the current one closed (null). */
    void displayScreen(Object legacyScreen);

    /**
     * Legacy code sent the player to another dimension (legacy id). {@code legacyTeleporter} is the
     * mod's Teleporter (it places the player on arrival), or null for the host's own placement.
     * Happens after the current tick's work, as 1.7.10 did it outside the code that asked.
     */
    default void changeDimension(int dimension, Object legacyTeleporter) {
    }

    /**
     * Legacy code moved the player's mirror to another dimension (legacy id) itself, as mods' own teleports
     * do; the player follows, to where the code put the mirror. After the current tick's work, as above.
     */
    default void followDimension(int dimension) {
    }

    /** Client side: whether this is the local player (the others are drawn players, 1.7.10's EntityOtherPlayerMP). */
    default boolean isLocal() {
        return true;
    }

    /** Client: the open legacy container GUI clicked a slot (GuiContainer.handleMouseClick; legacy click modes). */
    void clickSlot(int windowId, int slotId, int button, int mode);
}
