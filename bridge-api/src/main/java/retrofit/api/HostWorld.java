package retrofit.api;

/**
 * One host (1.20.1) level, as seen by the legacy bridge. Implemented by the host; the legacy
 * World subclasses forward their block, tile entity, redstone, light, sound, spawning and query
 * methods here.
 *
 * Legacy objects (blocks, tile entities, item stacks) are passed as opaque {@code Object}s: the
 * host never needs their types, only their identity or the bridge's helpers to read them.
 * Directions are legacy side numbers (0 down, 1 up, 2 north, 3 south, 4 west, 5 east), the same
 * order as 1.20.1's Direction ids. Methods past the core block access have neutral defaults so
 * simple hosts (tests, the isolated one-block world) only implement what they need.
 */
public interface HostWorld {
    // ---- Blocks and tile entities ----

    /** The legacy Block at a position: a legacy mod block, or the legacy equivalent of a host block. */
        Object getLegacyBlock(int x, int y, int z);

        int getLegacyMeta(int x, int y, int z);

    /**
     * Places a legacy block. Returns false if the host has no equivalent for it.
     *
     * @param flags legacy update flags (1 = notify neighbours, 2 = send to clients, 4 = no re-render)
     */
        boolean setLegacyBlock(int x, int y, int z, Object legacyBlock, int meta, int flags);

    /** The legacy TileEntity hosted at a position, or null. */
        Object getLegacyTileEntity(int x, int y, int z);

    /** Legacy code placed a tile entity itself (World.setTileEntity); the host adopts it if it can. */
    default void setLegacyTileEntity(int x, int y, int z, Object legacyTileEntity) {
    }

    default void removeLegacyTileEntity(int x, int y, int z) {
    }

        void markBlockForUpdate(int x, int y, int z);

        void markTileEntityChanged(int x, int y, int z);

    default void markBlockRangeForRenderUpdate(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }

    /** World.scheduleBlockUpdate: a tick for the block at a position after {@code delay} ticks. */
    default void scheduleBlockUpdate(int x, int y, int z, Object legacyBlock, int delay, int priority) {
    }

    /** World.notifyBlocksOfNeighborChange from the block at a position. */
    default void notifyNeighbors(int x, int y, int z, Object legacyBlock) {
    }

    /** World.notifyBlockOfNeighborChange: tell the block at a position that a neighbour changed. */
    default void notifyNeighbor(int x, int y, int z, Object neighborLegacyBlock) {
    }

    // ---- Redstone ----

    /** Weak power the block at a position emits towards {@code side} (World.getIndirectPowerLevelTo). */
    default int getIndirectPower(int x, int y, int z, int side) {
        return 0;
    }

    /** Strong power into the block at a position (World.getBlockPowerInput). */
    default int getDirectPowerInput(int x, int y, int z) {
        return 0;
    }

    /** The strongest power reaching a position from any side (World.getStrongestIndirectPower). */
    default int getStrongestIndirectPower(int x, int y, int z) {
        return 0;
    }

    // ---- Light, height, biome, time, weather ----

    default int getBlockLight(int x, int y, int z) {
        return 0;
    }

    default int getSkyLight(int x, int y, int z) {
        return 15;
    }

    /** Combined light as the renderer packs it (World.getLightBrightnessForSkyBlocks). */
    default int getPackedLight(int x, int y, int z) {
        return 0xF000F0;
    }

    default boolean canSeeSky(int x, int y, int z) {
        return true;
    }

    /** The first air block above the highest non-air block in a column. */
    default int getHeight(int x, int z) {
        return 64;
    }

    /** The 1.20.1 biome id at a column, e.g. "minecraft:plains". */
    default String getBiome(int x, int z) {
        return "minecraft:plains";
    }

        long getTotalWorldTime();

    default long getDayTime() {
        return 0;
    }

    default long getSeed() {
        return 0;
    }

    default boolean isRaining() {
        return false;
    }

    default boolean isThundering() {
        return false;
    }

        boolean isClientSide();

    // ---- Effects and spawning ----

    default void playSound(double x, double y, double z, String legacySoundName, float volume, float pitch) {
    }

    default void spawnParticle(String legacyParticleName, double x, double y, double z, double vx, double vy, double vz) {
    }

    /** Spawns a dropped item (a legacy EntityItem's stack and motion). */
    default boolean spawnItem(double x, double y, double z, Object legacyItemStack, double motionX, double motionY, double motionZ, int pickupDelay) {
        return false;
    }

    default boolean spawnExperience(double x, double y, double z, int amount) {
        return false;
    }

    // ---- Collision ----

    /**
     * Collision boxes of host blocks intersecting a region, flattened as
     * {minX, minY, minZ, maxX, maxY, maxZ}* (World.getCollidingBoundingBoxes).
     */
    default double[] getCollisionBoxes(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new double[0];
    }
}
