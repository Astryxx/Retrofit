package retrofit.legacy.bridge;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;
import retrofit.api.HostWorld;

/**
 * A 1.7.10 {@code World} backed by a 1.20.1 level. Legacy code gets a real World object, but its
 * block and tile entity methods read and write the host level through {@link HostWorld}; the
 * legacy chunk storage is never used.
 *
 * Methods are SRG-named, as in any compiled 1.7.10 class; MCP names are in comments. The legacy
 * World constructor calls some of these before {@link #host} is assigned, hence the null checks.
 */
public final class BridgeWorld extends World {
    private final HostWorld host;

    public BridgeWorld(HostWorld host, int dimension) {
        super(new SaveHandlerMP(), "Retrofit", WorldProvider.func_76570_a(dimension), // getProviderForDimension
                survivalSettings(), new Profiler());
        this.host = host;
        this.field_72995_K = host.isClientSide(); // isRemote
    }

    /**
     * new WorldSettings(0, GameType.SURVIVAL, false, false, WorldType.DEFAULT). Built reflectively:
     * the deobfuscated jar's metadata marks WorldSettings.GameType non-public, so javac rejects it.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static WorldSettings survivalSettings() {
        try {
            Class gameType = Class.forName("net.minecraft.world.WorldSettings$GameType");
            return WorldSettings.class.getConstructor(long.class, gameType, boolean.class, boolean.class, WorldType.class)
                    .newInstance(0L, Enum.valueOf(gameType, "SURVIVAL"), false, false, WorldType.field_77137_b); // DEFAULT
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retrofit: could not create legacy WorldSettings", e);
        }
    }

    /** createChunkProvider */
    @Override
    protected IChunkProvider func_72970_h() {
        return new BridgeChunkProvider(this);
    }

    /** getRenderDistanceChunks */
    @Override
    protected int func_152379_p() {
        return 8;
    }

    /** getEntityByID; entities are not bridged yet. */
    @Override
    public Entity func_73045_a(int id) {
        return null;
    }

    /** getBlock */
    @Override
    public Block func_147439_a(int x, int y, int z) {
        Object block = host == null ? null : host.getLegacyBlock(x, y, z);
        return block == null ? Blocks.field_150350_a : (Block) block; // air
    }

    /** getBlockMetadata */
    @Override
    public int func_72805_g(int x, int y, int z) {
        return host == null ? 0 : host.getLegacyMeta(x, y, z);
    }

    /** setBlock(x, y, z, block, meta, flags) */
    @Override
    public boolean func_147465_d(int x, int y, int z, Block block, int meta, int flags) {
        return host != null && host.setLegacyBlock(x, y, z, block, meta, flags);
    }

    /** setBlock(x, y, z, block) */
    @Override
    public boolean func_147449_b(int x, int y, int z, Block block) {
        return func_147465_d(x, y, z, block, 0, 3);
    }

    /** setBlockToAir */
    @Override
    public boolean func_147468_f(int x, int y, int z) {
        return func_147465_d(x, y, z, Blocks.field_150350_a, 0, 3);
    }

    /** getTileEntity */
    @Override
    public TileEntity func_147438_o(int x, int y, int z) {
        return host == null ? null : (TileEntity) host.getLegacyTileEntity(x, y, z);
    }

    /** markBlockForUpdate */
    @Override
    public void func_147471_g(int x, int y, int z) {
        if (host != null) {
            host.markBlockForUpdate(x, y, z);
        }
    }

    /** markTileEntityChunkModified */
    @Override
    public void func_147476_b(int x, int y, int z, TileEntity tileEntity) {
        if (host != null) {
            host.markTileEntityChanged(x, y, z);
        }
    }

    /** getTotalWorldTime */
    @Override
    public long func_82737_E() {
        return host == null ? 0 : host.getTotalWorldTime();
    }
}
