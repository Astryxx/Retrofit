package retrofit.forge.world;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.ticks.TickPriority;
import net.minecraftforge.registries.ForgeRegistries;
import retrofit.api.HostWorld;

import java.util.HashMap;
import java.util.Map;

/**
 * The region of a chunk being generated, as seen by legacy world generators (see
 * {@link LegacyWorldGen}). Writes are limited to what the region allows (the chunk and its
 * neighbours, which is where 1.7.10 generators place things: 8 blocks into the next chunks).
 *
 * Tile entities legacy generators create (chests they fill, spawners they set up) are kept here
 * and written into the chunk as block entity data when the chunk is done, so the real block
 * entity loads them when the chunk becomes part of the world.
 *
 * The deep pass ({@link #deep}): 1.7.10 worlds ended at y 0, 1.20.1's go down to -64. Generators
 * run a second time over the region seen 64 blocks lower (legacy y 0 is host y -64), with another
 * seed, and only their ores land, only in stone and deepslate below y 0, and in the bedrock floor
 * (bedrock ores, HBM's above all, are written at legacy y 0 and read the tile entity back right
 * away, so their placement must not be refused): legacy ores fill the deepslate layer as densely
 * as the layer above, and nothing else (structures, plants) doubles.
 */
final class RegionHostWorld implements HostWorld {
    private final WorldGenLevel region;
    private final Object legacyWorld;
    private final ChunkPos center;
    private final LegacyBlockTable table = LegacyBlockTable.get();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final Map<BlockPos, Object> tiles = new HashMap<>();
    /** Legacy y minus host y: 0, or 64 in the deep pass. */
    private final int shift;
    private final boolean deep;
    /** Deep pass: positions where an ore was accepted (its tile entity may follow). */
    private final java.util.Set<BlockPos> deepOres = new java.util.HashSet<>();

    RegionHostWorld(WorldGenLevel region, Object legacyWorld, ChunkPos center) {
        this(region, legacyWorld, center, false);
    }

    private RegionHostWorld(WorldGenLevel region, Object legacyWorld, ChunkPos center, boolean deep) {
        this.region = region;
        this.legacyWorld = legacyWorld;
        this.center = center;
        this.deep = deep;
        this.shift = deep ? -region.getMinBuildHeight() : 0;
    }

    /** The deep pass over the same region, or null if the level doesn't go below y 0. */
    static RegionHostWorld deep(WorldGenLevel region, Object legacyWorld, ChunkPos center) {
        return region.getMinBuildHeight() < 0 ? new RegionHostWorld(region, legacyWorld, center, true) : null;
    }

    private static final Map<Long, Boolean> ORES = new java.util.concurrent.ConcurrentHashMap<>();

    /** Whether a legacy block (and metadata) is an ore, by the legacy side's judgement; asked once per kind. */
    private static boolean isOre(Object legacyBlock, int meta) {
        long key = (long) System.identityHashCode(legacyBlock) << 4 | (meta & 15);
        return ORES.computeIfAbsent(key, k -> {
            try {
                return LegacyBridge.<Boolean>call("isOreBlock", legacyBlock, meta);
            } catch (RuntimeException e) {
                return false;
            }
        });
    }

    /**
     * Deep pass: an ore into stone or deepslate below y 0 (host coordinates), or into the bedrock
     * floor: 1.7.10's bedrock ores sit on the y 0 layer, which lands here at the world's bottom.
     * Nothing else, and never into air.
     */
    private boolean deepAccepts(BlockPos pos, Object legacyBlock, int meta) {
        if (pos.getY() >= 0 || !isOre(legacyBlock, meta)) {
            return false;
        }
        BlockState current = region.getBlockState(pos);
        return current.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || current.is(net.minecraft.tags.BlockTags.DEEPSLATE_ORE_REPLACEABLES)
                || current.is(net.minecraft.tags.BlockTags.STONE_ORE_REPLACEABLES)
                || current.is(Blocks.BEDROCK);
    }

    private boolean readable(int x, int y, int z) {
        return y >= region.getMinBuildHeight() && y < region.getMaxBuildHeight() && region.hasChunk(x >> 4, z >> 4);
    }

    private boolean writable(int x, int y, int z) {
        return y >= region.getMinBuildHeight() && y < region.getMaxBuildHeight()
                && Math.abs((x >> 4) - center.x) <= 1 && Math.abs((z >> 4) - center.z) <= 1;
    }

    @Override
    public Object getLegacyBlock(int x, int y, int z) {
        y -= shift;
        if (!readable(x, y, z)) {
            return table.legacyBlock(0);
        }
        BlockState state = region.getBlockState(cursor.set(x, y, z));
        if (state.getBlock() instanceof LegacyBlock block) {
            return block.legacyBlock();
        }
        return table.legacyBlock(table.legacyOf(state) >> 4);
    }

    @Override
    public int getLegacyMeta(int x, int y, int z) {
        y -= shift;
        if (!readable(x, y, z)) {
            return 0;
        }
        BlockState state = region.getBlockState(cursor.set(x, y, z));
        if (state.getBlock() instanceof LegacyBlock) {
            return state.getValue(LegacyContent.META);
        }
        return table.legacyOf(state) & 15;
    }

    @Override
    public boolean setLegacyBlock(int x, int y, int z, Object legacyBlock, int meta, int flags) {
        y -= shift;
        BlockState target = LegacyBlockTable.modernState(legacyBlock, meta);
        if (target == null || !writable(x, y, z)) {
            return false;
        }
        BlockPos pos = new BlockPos(x, y, z);
        if (deep) {
            if (!deepAccepts(pos, legacyBlock, meta)) {
                return false;
            }
            deepOres.add(pos);
        }
        tiles.remove(pos);
        return region.setBlock(pos, target, 2);
    }

    /** Legacy tile entities of legacy blocks placed during this chunk's generation, created on first access. */
    @Override
    public Object getLegacyTileEntity(int x, int y, int z) {
        y -= shift;
        if (!readable(x, y, z)) {
            return null;
        }
        BlockPos pos = new BlockPos(x, y, z);
        Object tile = tiles.get(pos);
        if (tile == null && region.getBlockState(pos).getBlock() instanceof LegacyBlock block && block.hasTileEntity()) {
            tile = LegacyBridge.createTileEntity(legacyWorld, block.legacyBlock(), region.getBlockState(pos).getValue(LegacyContent.META), x, y, z);
            if (tile != null) {
                tiles.put(pos, tile);
            }
        }
        return tile;
    }

    @Override
    public void setLegacyTileEntity(int x, int y, int z, Object legacyTileEntity) {
        y -= shift;
        if (writable(x, y, z) && (!deep || deepOres.contains(new BlockPos(x, y, z)))) {
            tiles.put(new BlockPos(x, y, z), legacyTileEntity);
        }
    }

    @Override
    public void removeLegacyTileEntity(int x, int y, int z) {
        tiles.remove(new BlockPos(x, y - shift, z));
    }

    /** Writes the tile entities legacy generators made into their chunks, for the block entities to load. */
    void flushTiles() {
        ResourceKeyHolder.init();
        tiles.forEach((pos, tile) -> {
            if (!(region.getBlockState(pos).getBlock() instanceof LegacyBlock)) {
                return;
            }
            CompoundTag tag = new CompoundTag();
            tag.putString("id", ResourceKeyHolder.id);
            tag.putInt("x", pos.getX());
            tag.putInt("y", pos.getY());
            tag.putInt("z", pos.getZ());
            tag.put(LegacyBlockEntity.LEGACY_TAG, LegacyStacks.fromBytes(LegacyBridge.save(tile)));
            if (region.getBlockEntity(pos) instanceof LegacyBlockEntity existing) {
                existing.load(tag); // a live chunk already made its block entity
                // Not setChanged(): it updates comparators through the live level, which from a world
                // generation thread waits for the server thread, which waits for world generation.
                region.getChunk(pos).setUnsaved(true);
            } else {
                ChunkAccess chunk = region.getChunk(pos);
                chunk.setBlockEntityNbt(tag); // loaded by the block entity when the chunk is finished
            }
        });
        tiles.clear();
    }

    /** The registry id of the legacy block entity type, looked up once. */
    private static final class ResourceKeyHolder {
        static String id;

        static void init() {
            if (id == null) {
                id = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(LegacyContent.blockEntityType()).toString();
            }
        }
    }

    @Override
    public void markBlockForUpdate(int x, int y, int z) {
    }

    @Override
    public void markTileEntityChanged(int x, int y, int z) {
    }

    @Override
    public void scheduleBlockUpdate(int x, int y, int z, Object legacyBlock, int delay, int priority) {
        y -= shift;
        if (deep && !deepOres.contains(new BlockPos(x, y, z))) {
            return;
        }
        BlockState state = legacyBlock == null ? null : LegacyBlockTable.modernState(legacyBlock, 0);
        Block block = state == null ? null : state.getBlock();
        if (block != null && writable(x, y, z)) {
            region.scheduleTick(new BlockPos(x, y, z), block, Math.max(0, delay), TickPriority.byValue(Math.max(-3, Math.min(3, priority))));
        }
    }

    @Override
    public int getBlockLight(int x, int y, int z) {
        y -= shift;
        return readable(x, y, z) ? region.getBrightness(LightLayer.BLOCK, cursor.set(x, y, z)) : 0;
    }

    @Override
    public int getSkyLight(int x, int y, int z) {
        y -= shift;
        return readable(x, y, z) ? region.getBrightness(LightLayer.SKY, cursor.set(x, y, z)) : 15;
    }

    @Override
    public boolean canSeeSky(int x, int y, int z) {
        return y >= getHeight(x, z) || !readable(x, y - shift, z);
    }

    /** In legacy coordinates (the deep pass sees the surface 64 higher). */
    @Override
    public int getHeight(int x, int z) {
        return (readable(x, 64, z) ? region.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) : 64) + shift;
    }

    @Override
    public String getBiome(int x, int z) {
        if (!readable(x, 64, z)) {
            return "minecraft:plains";
        }
        return region.getBiome(cursor.set(x, 64, z)).unwrapKey().map(key -> key.location().toString()).orElse("minecraft:plains");
    }

    @Override
    public long getTotalWorldTime() {
        return region.getLevelData().getGameTime();
    }

    @Override
    public long getSeed() {
        return deep ? region.getSeed() ^ 0x5DEECE66DL * 31 : region.getSeed(); // the deep pass gets its own veins
    }

    @Override
    public boolean isClientSide() {
        return false;
    }
}
