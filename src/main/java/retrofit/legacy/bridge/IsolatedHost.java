package retrofit.legacy.bridge;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import retrofit.api.HostWorld;

/**
 * A world holding one block at the origin and air everywhere else. Block shapes and static
 * models are computed against it, so they depend only on the block and its metadata (and can be
 * cached per state). Callers hold {@link Bridge#LOCK}.
 */
final class IsolatedHost implements HostWorld {
    private static IsolatedHost instance;
    private static World world;

    private Object block = Blocks.field_150350_a; // air
    private int meta;
    private TileEntity tile;

    private IsolatedHost() {
    }

    /** The isolated world, now containing {@code block}:{@code meta} at 0,0,0. */
    static World with(Object block, int meta) {
        if (world == null) {
            instance = new IsolatedHost();
            world = new BridgeWorld(instance, 0);
        }
        if (instance.block != block || instance.meta != meta) {
            instance.block = block;
            instance.meta = meta;
            instance.tile = null;
        }
        return world;
    }

    private static boolean origin(int x, int y, int z) {
        return x == 0 && y == 0 && z == 0;
    }

    @Override
    public Object getLegacyBlock(int x, int y, int z) {
        return origin(x, y, z) ? block : Blocks.field_150350_a;
    }

    @Override
    public int getLegacyMeta(int x, int y, int z) {
        return origin(x, y, z) ? meta : 0;
    }

    @Override
    public boolean setLegacyBlock(int x, int y, int z, Object legacyBlock, int newMeta, int flags) {
        return false;
    }

    /** Some renderers read their tile entity (e.g. to count books); give them a fresh one. */
    @Override
    public Object getLegacyTileEntity(int x, int y, int z) {
        if (!origin(x, y, z)) {
            return null;
        }
        if (tile == null && ((Block) block).hasTileEntity(meta)) {
            tile = (TileEntity) Bridge.createTileEntity(world, block, meta, 0, 0, 0);
        }
        return tile;
    }

    @Override
    public void markBlockForUpdate(int x, int y, int z) {
    }

    @Override
    public void markTileEntityChanged(int x, int y, int z) {
    }

    @Override
    public long getTotalWorldTime() {
        return 0;
    }

    @Override
    public boolean isClientSide() {
        return false;
    }
}
