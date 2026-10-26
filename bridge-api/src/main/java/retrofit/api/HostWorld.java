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

    /** World.blockExists: inside the host's height (below y 0 too) and in a loaded chunk. */
    default boolean blockLoaded(int x, int y, int z) {
        return y >= 0 && y < 256;
    }

    /** Whether a position has a host block entity that isn't a legacy one (a quick check before the others). */
    default boolean hasHostBlockEntity(int x, int y, int z) {
        return true;
    }

    /**
     * A host block legacy code sees as Retrofit's stand-in (one 1.7.10 never had), as binary NBT:
     * {"State": its block state, "Entity": its block entity's data, if any}. Carried by the
     * stand-in's tile entity, so mods that move blocks (pistons, frames) move the real block.
     */
    default byte[] hostBlockData(int x, int y, int z) {
        return null;
    }

    /** Places a host block legacy code carried (see {@link #hostBlockData}); false if it can't. */
    default boolean setHostBlockData(int x, int y, int z, byte[] data) {
        return false;
    }

    /** The inventory of a host block entity that isn't a legacy one (vanilla chests, furnaces...), or null. */
    default HostInventory getHostInventory(int x, int y, int z) {
        return null;
    }

    /** The fluid tanks of a host block that isn't a legacy one (any 1.20.1 block with Forge's fluid handler), or null. */
    default HostFluids getHostFluids(int x, int y, int z) {
        return null;
    }

    /** The energy storage of a host block that isn't a legacy one (any 1.20.1 block with Forge Energy), or null. */
    default HostEnergy getHostEnergy(int x, int y, int z) {
        return null;
    }

    // ---- 1.20.1 mods' blocks (legacy code sees them as Retrofit's stand-in blocks) ----

    /** The legacy stacks the host block at a position drops when broken (quarries, miners), or null. */
    default Object[] hostBlockDrops(int x, int y, int z, int fortune) {
        return null;
    }

    default float hostBlockHardness(int x, int y, int z) {
        return 1.5f;
    }

    default int hostBlockLight(int x, int y, int z) {
        return 0;
    }

    /** Whether the host block is a kind of stone (ore generators replace stone). */
    default boolean hostBlockIsStone(int x, int y, int z) {
        return false;
    }

    // ---- 1.20.1 creatures and items legacy code can see (legacy AI targets them, legacy weapons hurt them) ----

    /**
     * The host's own entities (not legacy ones, not players) in a box: rows of {handle (opaque),
     * type id ("minecraft:zombie"), category (0 monster, 1 animal, 2 water, 3 ambient, 4 villager,
     * 5 golem, 6 other living, 7 item, 8 experience orb), state (see hostEntityState), legacy item
     * stack (items) or null}.
     */
    default Object[][] hostEntities(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new Object[0][];
    }

    /**
     * {x, y, z, yaw, pitch, head yaw, motion x, y, z, width, height, health, max health, flags (1 on
     * ground, 2 baby, 4 burning, 8 sneaking), experience value or item age}, or null once the entity
     * is gone.
     */
    default double[] hostEntityState(Object handle) {
        return null;
    }

    /** Legacy code hurt a host entity (attackEntityFrom on its stand-in). */
    default boolean hurtHostEntity(Object handle, String kind, Object legacyAttacker, float amount) {
        return false;
    }

    /** Legacy code acted on a host entity: 0 remove, 1 add motion, 2 set motion, 3 set on fire (a seconds), 4 teleport, 5 potion effect (a legacy id, b duration, c amplifier), 6 set health. */
    default void hostEntityAction(Object handle, int action, double a, double b, double c) {
    }

    /** Legacy code changed a host item entity's stack (took part of it). */
    default void setHostItemStack(Object handle, Object legacyStack) {
    }

    /** The legacy entity name a host mob spawner at a position spawns ("Zombie"), or null if there is no spawner. */
    default String hostSpawnerEntity(int x, int y, int z) {
        return null;
    }

    /** The server's view distance in chunks (who sees a chunk, for mods that send to its watchers). */
    default int viewDistance() {
        return 10;
    }

    /** A legacy chunk-loading ticket forces (or no longer forces) a chunk to stay loaded. */
    default void forceChunk(int chunkX, int chunkZ, boolean forced) {
    }

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

    /** A legacy mod entity legacy code spawned: the host makes its own entity for it. */
    default boolean spawnLegacyEntity(Object legacyEntity) {
        return false;
    }

    /**
     * A vanilla 1.7.10 entity legacy code spawned (arrows, mobs, falling blocks...), as its
     * 1.7.10 save data; the host makes the 1.20.1 equivalent. {@code ownerUuid} is the player who
     * shot or threw it, or null.
     */
    default boolean spawnVanillaEntity(byte[] legacyNbt, String ownerUuid) {
        return false;
    }

    /** World.setEntityState: an event for a legacy entity's client copies (hurt, death, taming...). */
    default void entityEvent(Object legacyEntity, int event) {
    }

    /** World.newExplosion, done by the host (blocks, entities, effects). */
    default void explode(Object legacyExploder, double x, double y, double z, float strength, boolean fire, boolean breakBlocks) {
    }

    /** World.addWeatherEffect with a lightning bolt. */
    default void lightning(double x, double y, double z) {
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
