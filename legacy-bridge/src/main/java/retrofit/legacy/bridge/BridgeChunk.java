package retrofit.legacy.bridge;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * A chunk of a legacy world as a view of its world: legacy worlds keep no block storage (the host
 * level does), and mods that read chunks directly (chunk.getBlock, chunk.getTileEntity, as IC2 does
 * to find a machine's tile entity) get what the world has at those coordinates. Writes go to the
 * world too, also those into its block storage sections (ManaMetal builds its dungeons there,
 * section.setBlock). Entity lists are those of an empty chunk.
 */
final class BridgeChunk extends Chunk {
    private final int baseX;
    private final int baseZ;
    private ExtendedBlockStorage[] sections;

    BridgeChunk(World world, int chunkX, int chunkZ) {
        super(world, chunkX, chunkZ);
        this.baseX = chunkX << 4;
        this.baseZ = chunkZ << 4;
        this.field_76636_d = true; // isChunkLoaded
    }

    /** getBlock */
    @Override
    public Block func_150810_a(int x, int y, int z) {
        return field_76637_e.func_147439_a(baseX + x, y, baseZ + z); // worldObj.getBlock
    }

    /** getBlockMetadata */
    @Override
    public int func_76628_c(int x, int y, int z) {
        return field_76637_e.func_72805_g(baseX + x, y, baseZ + z);
    }

    /** func_150807_a: setBlock (without notifying neighbours) */
    @Override
    public boolean func_150807_a(int x, int y, int z, Block block, int meta) {
        return field_76637_e.func_147465_d(baseX + x, y, baseZ + z, block, meta, 2);
    }

    /** setBlockMetadata */
    @Override
    public boolean func_76589_b(int x, int y, int z, int meta) {
        return field_76637_e.func_72921_c(baseX + x, y, baseZ + z, meta, 2);
    }

    /** getBlockLightOpacity */
    @Override
    public int func_150808_b(int x, int y, int z) {
        return func_150810_a(x, y, z).getLightOpacity(field_76637_e, baseX + x, y, baseZ + z);
    }

    /** getTileEntity (func_150806_e) */
    @Override
    public TileEntity func_150806_e(int x, int y, int z) {
        return field_76637_e.func_147438_o(baseX + x, y, baseZ + z);
    }

    @Override
    public TileEntity getTileEntityUnsafe(int x, int y, int z) {
        return func_150806_e(x, y, z);
    }

    /** func_150812_a: setTileEntity (chunk coordinates) */
    @Override
    public void func_150812_a(int x, int y, int z, TileEntity tileEntity) {
        field_76637_e.func_147455_a(baseX + x, y, baseZ + z, tileEntity);
    }

    /** addTileEntity (world coordinates from the tile entity) */
    @Override
    public void func_150813_a(TileEntity tileEntity) {
        field_76637_e.func_147455_a(tileEntity.field_145851_c, tileEntity.field_145848_d, tileEntity.field_145849_e, tileEntity);
    }

    /** removeTileEntity */
    @Override
    public void func_150805_f(int x, int y, int z) {
        field_76637_e.func_147475_p(baseX + x, y, baseZ + z);
    }

    /** getSavedLightValue */
    @Override
    public int func_76614_a(EnumSkyBlock type, int x, int y, int z) {
        return field_76637_e.func_72972_b(type, baseX + x, y, baseZ + z);
    }

    /** getBlockLightValue */
    @Override
    public int func_76629_c(int x, int y, int z, int skylightSubtracted) {
        return field_76637_e.func_72957_l(baseX + x, y, baseZ + z);
    }

    /** getHeightValue */
    @Override
    public int func_76611_b(int x, int z) {
        return field_76637_e.func_72976_f(baseX + x, baseZ + z);
    }

    /** canBlockSeeTheSky */
    @Override
    public boolean func_76619_d(int x, int y, int z) {
        return field_76637_e.func_72937_j(baseX + x, y, baseZ + z);
    }

    /** getBiomeGenForWorldCoords */
    @Override
    public BiomeGenBase func_76591_a(int x, int z, WorldChunkManager manager) {
        return field_76637_e.func_72807_a(baseX + x, baseZ + z);
    }

    // ---- What EmptyChunk did: entities, lighting and loading belong to the host ----

    /** addEntity */
    @Override
    public void func_76612_a(net.minecraft.entity.Entity entity) {
    }

    /** removeEntity */
    @Override
    public void func_76622_b(net.minecraft.entity.Entity entity) {
    }

    /** removeEntityAtIndex */
    @Override
    public void func_76608_a(net.minecraft.entity.Entity entity, int index) {
    }

    /** getEntitiesWithinAABBForEntity */
    @Override
    @SuppressWarnings("rawtypes")
    public void func_76588_a(net.minecraft.entity.Entity exclude, net.minecraft.util.AxisAlignedBB box, java.util.List list,
                             net.minecraft.command.IEntitySelector selector) {
    }

    /** getEntitiesOfTypeWithinAAAB */
    @Override
    @SuppressWarnings("rawtypes")
    public void func_76618_a(Class type, net.minecraft.util.AxisAlignedBB box, java.util.List list, net.minecraft.command.IEntitySelector selector) {
    }

    /** generateHeightMap */
    @Override
    public void func_76590_a() {
    }

    /** generateSkylightMap */
    @Override
    public void func_76603_b() {
    }

    /** setLightValue */
    @Override
    public void func_76633_a(EnumSkyBlock type, int x, int y, int z, int value) {
    }

    /** onChunkLoad */
    @Override
    public void func_76631_c() {
    }

    /** onChunkUnload */
    @Override
    public void func_76623_d() {
    }

    /** setChunkModified */
    @Override
    public void func_76630_e() {
    }

    /** needsSaving: the host saves */
    @Override
    public boolean func_76601_a(boolean all) {
        return false;
    }

    /** isEmpty */
    @Override
    public boolean func_76621_g() {
        return false;
    }

    /** getBlockStorageArray: all 16 sections, each a view of the world as this chunk is. */
    @Override
    public ExtendedBlockStorage[] func_76587_i() {
        if (sections == null) {
            ExtendedBlockStorage[] made = new ExtendedBlockStorage[16];
            for (int i = 0; i < made.length; i++) {
                made[i] = new Section(this, i << 4);
            }
            sections = made;
        }
        return sections;
    }

    /**
     * A block storage section whose blocks and metadata are the world's: writes are setBlock and
     * setBlockMetadata with flag 2 (clients see them, neighbours aren't notified), as close as the
     * world comes to a write into storage. Its own arrays stay empty: code that copies them
     * (S21PacketChunkData) gets nothing, which the world's own updates cover.
     */
    private static final class Section extends ExtendedBlockStorage {
        private final BridgeChunk chunk;

        Section(BridgeChunk chunk, int baseY) {
            super(baseY, !chunk.field_76637_e.field_73011_w.field_76576_e); // worldObj.provider.hasNoSky
            this.chunk = chunk;
        }

        private int y(int y) {
            return func_76662_d() + y; // getYLocation
        }

        /** getBlockByExtId */
        @Override
        public Block func_150819_a(int x, int y, int z) {
            return chunk.func_150810_a(x, y(y), z);
        }

        /** func_150818_a: set the block, keeping the metadata there */
        @Override
        public void func_150818_a(int x, int y, int z, Block block) {
            World world = chunk.field_76637_e;
            int worldX = chunk.baseX + x, worldY = y(y), worldZ = chunk.baseZ + z;
            world.func_147465_d(worldX, worldY, worldZ, block, world.func_72805_g(worldX, worldY, worldZ), 2); // setBlock, getBlockMetadata
        }

        /** getExtBlockMetadata */
        @Override
        public int func_76665_b(int x, int y, int z) {
            return chunk.func_76628_c(x, y(y), z);
        }

        /** setExtBlockMetadata */
        @Override
        public void func_76654_b(int x, int y, int z, int meta) {
            if (chunk.func_76628_c(x, y(y), z) != meta) {
                chunk.func_76589_b(x, y(y), z, meta);
            }
        }

        /** getExtSkylightValue */
        @Override
        public int func_76670_c(int x, int y, int z) {
            return chunk.func_76614_a(EnumSkyBlock.Sky, x, y(y), z);
        }

        /** getExtBlocklightValue */
        @Override
        public int func_76674_d(int x, int y, int z) {
            return chunk.func_76614_a(EnumSkyBlock.Block, x, y(y), z);
        }

        /** setExtSkylightValue: the host lights its world */
        @Override
        public void func_76657_c(int x, int y, int z, int value) {
        }

        /** setExtBlocklightValue */
        @Override
        public void func_76677_d(int x, int y, int z, int value) {
        }

        /** isEmpty: never, or mods that skip empty sections when scanning a chunk would miss the world's blocks */
        @Override
        public boolean func_76663_a() {
            return false;
        }
    }
}
