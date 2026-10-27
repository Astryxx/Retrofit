package retrofit.forge.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import retrofit.forge.RetrofitMod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Block writes legacy generators made outside the bounds of the region they were given.
 *
 * <p>1.7.10 loaded chunks on demand, so a generator could build a structure anywhere it liked;
 * 1.20.1's feature stage takes writes to the generating chunk and its neighbours only, and
 * OreSpawn's structures reach two chunks out (the Enormous Castle clears the ground from 20
 * blocks before its anchor). Taking only the writes the region allowed left every structure's
 * outer parts ungenerated.
 *
 * <p>Refused writes are queued for the chunk they belong to instead, and generators read them
 * back as placed. When that chunk is generated, its queued writes are placed first (as 1.7.10's
 * populate found the structure in the raw terrain); a chunk that finished first is filled on the
 * server thread during the level tick. A spawner or chest the generator set up rides along with
 * its block (see {@link Entry#tile}). The queue is saved with each level ({@link Saved}), so a
 * chunk first generated after a restart still gets its part of the structure.
 *
 * <p>Blocks placed late keep their neighbours' shapes honest through {@link #fixShapesAround}:
 * 1.20.1 stores a fence's or iron bars' connections in the block state (1.7.10 computed them
 * at render time), so a state written among later neighbours must be re-shaped once they stand.
 *
 * <p>Contributed by UsagiTsukino-01 (0.5.3), merged with Retrofit's earlier overflow queue.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID)
public final class LegacyDeferredBlocks {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** How far out of the generating chunk a write may be queued (the region's read range is 8 chunks). */
    static final int RANGE = 8;
    /** Writes kept for one chunk before the rest are dropped (a big OreSpawn castle queues ~30k per chunk). */
    private static final int MAX_PER_CHUNK = 262144;
    /** Block writes placed per level tick for chunks that finished first (the rest wait for the next tick). */
    private static final int DRAIN_BUDGET = 16384;
    /** Flags for a late placement: tell the clients, but keep the neighbours' shapes to ourselves. */
    private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** One refused write: the block, where it belongs, and the tile entity the generator gave it (if any). */
    static final class Entry {
        final int x;
        final int y;
        final int z;
        final Object block;
        final int meta;
        final int flags;
        final BlockState state;
        volatile Object tile;
        /** A tile entity read back from the saved queue (its 1.7.10 NBT), made again when the block is placed. */
        byte[] savedTile;

        Entry(int x, int y, int z, Object block, int meta, int flags, BlockState state, Object tile) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
            this.meta = meta;
            this.flags = flags;
            this.state = state;
            this.tile = tile;
        }

        BlockPos pos() {
            return new BlockPos(this.x, this.y, this.z);
        }

        /** The tile entity this write carries, if any, in a legacy world. */
        Object tile(Object legacyWorld) {
            if (this.tile == null && this.savedTile != null) {
                this.tile = LegacyBridge.load(legacyWorld, this.savedTile, this.x, this.y, this.z);
                this.savedTile = null;
            }
            return this.tile;
        }
    }

    /** Queued writes per dimension, then per target chunk. Lists stay in the map (cleared, not removed): a write enqueued while its chunk is being filled must not be lost. */
    private static final Map<ResourceKey<Level>, Map<Long, List<Entry>>> QUEUES = new ConcurrentHashMap<>();
    /** The latest queued write per position, per dimension: what generators read back there, and where a generated tile entity goes. */
    private static final Map<ResourceKey<Level>, Map<Long, Entry>> PENDING = new ConcurrentHashMap<>();
    /** The saved form of each level's queue, marked dirty when the queue changes. */
    private static final Map<ResourceKey<Level>, Saved> SAVED = new ConcurrentHashMap<>();
    private static final AtomicLong DEFERRED = new AtomicLong();
    private static final AtomicLong PLACED = new AtomicLong();
    private static volatile boolean warnedCap;

    private LegacyDeferredBlocks() {
    }

    private static Map<Long, List<Entry>> chunks(ResourceKey<Level> dimension) {
        return QUEUES.computeIfAbsent(dimension, key -> new ConcurrentHashMap<>());
    }

    private static Map<Long, Entry> pending(ResourceKey<Level> dimension) {
        return PENDING.computeIfAbsent(dimension, key -> new ConcurrentHashMap<>());
    }

    /**
     * Queues a refused write for its own chunk. The tile entity (a spawner the generator names, a
     * chest it fills) is created by the caller when the block has one and handed in here, so the
     * generator's read-back finds it as it would have found it in the world in 1.7.10.
     */
    static void enqueue(ResourceKey<Level> dimension, int x, int y, int z, Object block, int meta, int flags, BlockState state, Object tile) {
        enqueue(dimension, new Entry(x, y, z, block, meta, flags, state, tile));
    }

    private static void enqueue(ResourceKey<Level> dimension, Entry entry) {
        int x = entry.x;
        int y = entry.y;
        int z = entry.z;
        List<Entry> list = chunks(dimension).computeIfAbsent(ChunkPos.asLong(x >> 4, z >> 4), key -> new ArrayList<>());
        synchronized (list) {
            if (list.size() >= MAX_PER_CHUNK) {
                if (!warnedCap) {
                    warnedCap = true;
                    LOGGER.warn("A legacy generator queued more than {} writes for one chunk; the rest are dropped", MAX_PER_CHUNK);
                }
                return;
            }
            list.add(entry);
        }
        pending(dimension).put(BlockPos.asLong(x, y, z), entry);
        DEFERRED.incrementAndGet();
        changed(dimension);
    }

    private static void changed(ResourceKey<Level> dimension) {
        Saved saved = SAVED.get(dimension);
        if (saved != null) {
            saved.setDirty();
        }
    }

    /** The block queued at a position (as generators read it back), or null. */
    static BlockState pendingState(ResourceKey<Level> dimension, BlockPos pos) {
        Map<Long, Entry> positions = PENDING.get(dimension);
        Entry entry = positions == null ? null : positions.get(pos.asLong());
        return entry == null ? null : entry.state;
    }

    /** Whether a block write for this position is queued (a tile entity made for it rides the queue, not the region). */
    static boolean isPending(ResourceKey<Level> dimension, BlockPos pos) {
        Map<Long, Entry> positions = PENDING.get(dimension);
        return positions != null && positions.containsKey(pos.asLong());
    }

    /** Hands the generator's finished tile entity to the queued write of its block (the mob it named, the loot it put in). */
    static void attachTile(ResourceKey<Level> dimension, BlockPos pos, Object tile) {
        Map<Long, Entry> positions = PENDING.get(dimension);
        Entry entry = positions == null ? null : positions.get(pos.asLong());
        if (entry != null) {
            entry.tile = tile;
        }
    }

    private static void taken(ResourceKey<Level> dimension, Entry entry) {
        pending(dimension).remove(BlockPos.asLong(entry.x, entry.y, entry.z), entry);
        changed(dimension);
    }

    /** Tests: {blocks placed past the generation window, of those placed in their chunk since}. */
    public static long[] counts() {
        return new long[]{DEFERRED.get(), PLACED.get()};
    }

    /**
     * Places the writes queued for the chunk now being generated (its own parts of structures
     * anchored in its neighbours), before the generators run, as 1.7.10's populate found them.
     */
    static void applyTo(ResourceKey<Level> dimension, int cx, int cz, RegionHostWorld host) {
        Map<Long, List<Entry>> byChunk = QUEUES.get(dimension);
        List<Entry> list = byChunk == null ? null : byChunk.get(ChunkPos.asLong(cx, cz));
        if (list == null) {
            return;
        }
        List<Entry> taken;
        synchronized (list) {
            if (list.isEmpty()) {
                return;
            }
            taken = new ArrayList<>(list);
            list.clear();
        }
        for (Entry entry : taken) {
            taken(dimension, entry);
            if (host.setLegacyBlock(entry.x, entry.y, entry.z, entry.block, entry.meta, entry.flags)) {
                PLACED.incrementAndGet();
            }
            Object tile = entry.tile(host.legacyWorld());
            if (tile != null) {
                host.setLegacyTileEntity(entry.x, entry.y, entry.z, tile);
            }
        }
    }

    /**
     * Fills chunks that finished before the chunk their writer ran in (the player stood nearer to
     * them): they are already full, so the server thread places the queued writes between ticks,
     * a budget per tick so the server stays smooth.
     */
    static void drain(ServerLevel level) {
        Map<Long, List<Entry>> byChunk = QUEUES.get(level.dimension());
        if (byChunk == null || byChunk.isEmpty()) {
            return;
        }
        int budget = DRAIN_BUDGET;
        for (Map.Entry<Long, List<Entry>> chunk : byChunk.entrySet()) {
            if (budget <= 0) {
                return;
            }
            long key = chunk.getKey();
            if (!level.hasChunk(ChunkPos.getX(key), ChunkPos.getZ(key))) {
                continue; // still to be generated: its writes are placed when it is
            }
            List<Entry> list = chunk.getValue();
            List<Entry> batch;
            synchronized (list) {
                if (list.isEmpty()) {
                    continue;
                }
                int count = Math.min(budget, list.size());
                batch = new ArrayList<>(list.subList(0, count));
                list.subList(0, count).clear();
                budget -= count;
            }
            for (Entry entry : batch) {
                placeLate(level, entry);
                taken(level.dimension(), entry);
            }
        }
    }

    /** One write into a finished chunk, with the tile entity it carries and its neighbours' shapes. */
    private static void placeLate(ServerLevel level, Entry entry) {
        try {
            BlockPos pos = entry.pos();
            if (level.getBlockState(pos) != entry.state && !level.setBlock(pos, entry.state, PLACE_FLAGS)) {
                return;
            }
            PLACED.incrementAndGet();
            Object tile = entry.tile(LegacyContent.legacyWorld(level));
            if (tile != null) {
                applyLateTile(level, pos, entry.state, tile);
            }
            fixShapesAround(level, pos);
        } catch (RuntimeException e) {
            LegacyErrors.report("deferred block write", "placing a late block at " + entry.x + " " + entry.y + " " + entry.z, e);
        }
    }

    /** The generator's tile entity into the block placed late: a chest's loot, a spawner's mob, or a legacy block's own data. */
    private static void applyLateTile(ServerLevel level, BlockPos pos, BlockState state, Object tile) {
        BlockEntity target = level.getBlockEntity(pos);
        if (target == null) {
            return;
        }
        if (state.getBlock() instanceof LegacyBlock) {
            if (target instanceof LegacyBlockEntity existing) {
                existing.load(RegionHostWorld.legacyTileTag(pos, tile));
                target.setChanged();
            }
            return;
        }
        CompoundTag data = target.saveWithoutMetadata();
        if (RegionHostWorld.mergeLegacyTileData(data, tile)) {
            target.load(data);
            target.setChanged();
        }
    }

    /**
     * Re-shapes a block and its neighbours: connections (a fence's rails, iron bars' panes, a wall's
     * edges) come from the neighbours in 1.20.1, and a state placed among later neighbours keeps the
     * shape it was placed with, as vanilla's own templates re-shape what they placed.
     */
    static void fixShapesAround(LevelAccessor level, BlockPos pos) {
        fixShape(level, pos);
        for (Direction direction : Direction.values()) {
            fixShape(level, pos.relative(direction));
        }
    }

    static void fixShape(LevelAccessor level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.hasBlockEntity() || state.getBlock() instanceof LegacyBlock) {
            return; // block entities keep the state their data was written for; legacy blocks have no connections
        }
        BlockState updated = Block.updateFromNeighbourShapes(state, level, pos);
        if (updated != state) {
            level.setBlock(pos, updated, PLACE_FLAGS);
        }
    }

    /** Queued writes belong to one running server (saved with its levels); they go when it does. */
    static void clear() {
        QUEUES.clear();
        PENDING.clear();
        SAVED.clear();
    }

    /** A level's saved queue is read back as the level loads, before any of its chunks generate. */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (RetrofitMod.runtime() != null && event.getLevel() instanceof ServerLevel level) {
            SAVED.put(level.dimension(), level.getDataStorage().computeIfAbsent(tag -> Saved.load(level, tag),
                    () -> new Saved(level.dimension()), "retrofit_deferred_blocks"));
        }
    }

    /**
     * A level's queue on disk: each write's position, 1.20.1 block state, flags and the generator's
     * tile entity (its 1.7.10 NBT, made again when read back).
     */
    static final class Saved extends SavedData {
        private final ResourceKey<Level> dimension;

        Saved(ResourceKey<Level> dimension) {
            this.dimension = dimension;
        }

        static Saved load(ServerLevel level, CompoundTag tag) {
            Saved saved = new Saved(level.dimension());
            var blocks = level.holderLookup(Registries.BLOCK);
            LegacyBlockTable table = LegacyBlockTable.get();
            int read = 0;
            for (Tag element : tag.getList("Writes", Tag.TAG_COMPOUND)) {
                CompoundTag write = (CompoundTag) element;
                try {
                    BlockState state = NbtUtils.readBlockState(blocks, write.getCompound("State"));
                    int x = write.getInt("X");
                    int y = write.getInt("Y");
                    int z = write.getInt("Z");
                    Object block;
                    int meta;
                    if (state.getBlock() instanceof LegacyBlock legacy) {
                        block = legacy.legacyBlock();
                        meta = state.getValue(LegacyContent.META);
                    } else {
                        int id = table.legacyOf(state);
                        block = table.legacyBlock(id >> 4);
                        meta = id & 15;
                    }
                    Entry entry = new Entry(x, y, z, block, meta, write.getInt("Flags"), state, null);
                    if (write.contains("Tile")) {
                        entry.savedTile = write.getByteArray("Tile");
                    }
                    enqueue(level.dimension(), entry);
                    read++;
                } catch (RuntimeException e) {
                    LegacyErrors.report("deferred block write", "reading a saved write", e);
                }
            }
            if (read > 0) {
                LOGGER.info("{} block writes of legacy structures wait for their chunks in {}", read, level.dimension().location());
            }
            return saved;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag writes = new ListTag();
            Map<Long, List<Entry>> byChunk = QUEUES.get(dimension);
            if (byChunk != null) {
                for (List<Entry> list : byChunk.values()) {
                    List<Entry> copy;
                    synchronized (list) {
                        copy = new ArrayList<>(list);
                    }
                    for (Entry entry : copy) {
                        CompoundTag write = new CompoundTag();
                        write.putInt("X", entry.x);
                        write.putInt("Y", entry.y);
                        write.putInt("Z", entry.z);
                        write.putInt("Flags", entry.flags);
                        write.put("State", NbtUtils.writeBlockState(entry.state));
                        Object tile = entry.tile;
                        if (tile != null) {
                            try {
                                write.putByteArray("Tile", LegacyBridge.save(tile));
                            } catch (RuntimeException e) {
                                LegacyErrors.report("deferred block write", "saving a queued tile entity", e);
                            }
                        } else if (entry.savedTile != null) {
                            write.putByteArray("Tile", entry.savedTile);
                        }
                        writes.add(write);
                    }
                }
            }
            tag.put("Writes", writes);
            return tag;
        }
    }
}
