package retrofit.forge.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import retrofit.api.HostWorld;

/**
 * A read-only view of the region a chunk mesh is being built from (client chunk builder threads),
 * for legacy block renderers that draw a block from its surroundings: neighbours (connected
 * textures, fences, pipes) and its tile entity (Carpenter's Blocks' covers). Nothing can change
 * through it.
 */
public final class RenderRegionHost implements HostWorld {
    private final BlockAndTintGetter region;
    private final long time;
    private final LegacyBlockTable table = LegacyBlockTable.get();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public RenderRegionHost(BlockAndTintGetter region, long time) {
        this.region = region;
        this.time = time;
    }

    private BlockState state(int x, int y, int z) {
        return region.getBlockState(cursor.set(x, y, z));
    }

    @Override
    public Object getLegacyBlock(int x, int y, int z) {
        BlockState state = state(x, y, z);
        if (state.getBlock() instanceof LegacyBlock block) {
            return block.legacyBlock();
        }
        return table.legacyBlock(table.legacyOf(state) >> 4);
    }

    @Override
    public int getLegacyMeta(int x, int y, int z) {
        BlockState state = state(x, y, z);
        if (state.getBlock() instanceof LegacyBlock) {
            return state.getValue(LegacyContent.META);
        }
        return table.legacyOf(state) & 15;
    }

    @Override
    public boolean setLegacyBlock(int x, int y, int z, Object legacyBlock, int meta, int flags) {
        return false;
    }

    @Override
    public Object getLegacyTileEntity(int x, int y, int z) {
        BlockEntity blockEntity = region.getBlockEntity(cursor.set(x, y, z));
        return blockEntity instanceof LegacyBlockEntity legacy ? legacy.legacyTile() : null;
    }

    @Override
    public void markBlockForUpdate(int x, int y, int z) {
    }

    @Override
    public void markTileEntityChanged(int x, int y, int z) {
    }

    @Override
    public int getBlockLight(int x, int y, int z) {
        return region.getBrightness(LightLayer.BLOCK, cursor.set(x, y, z));
    }

    @Override
    public int getSkyLight(int x, int y, int z) {
        return region.getBrightness(LightLayer.SKY, cursor.set(x, y, z));
    }

    @Override
    public int getPackedLight(int x, int y, int z) {
        return getSkyLight(x, y, z) << 20 | getBlockLight(x, y, z) << 4;
    }

    @Override
    public long getTotalWorldTime() {
        return time;
    }

    @Override
    public boolean isClientSide() {
        return true;
    }
}
