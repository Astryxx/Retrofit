package retrofit.forge.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import retrofit.api.HostWorld;

/**
 * A 1.20.1 level as seen by legacy code. Legacy mod blocks map to their {@link LegacyBlock}s;
 * vanilla blocks map to and from real 1.20.1 blocks through {@link LegacyBlockTable}.
 */
final class LevelHostWorld implements HostWorld {
    private final Level level;
    private final LegacyBlockTable table = LegacyBlockTable.get();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    LevelHostWorld(Level level) {
        this.level = level;
    }

    @Override
    public Object getLegacyBlock(int x, int y, int z) {
        BlockState state = level.getBlockState(cursor.set(x, y, z));
        if (state.getBlock() instanceof LegacyBlock block) {
            return block.legacyBlock();
        }
        return table.legacyBlock(table.legacyOf(state) >> 4);
    }

    @Override
    public int getLegacyMeta(int x, int y, int z) {
        BlockState state = level.getBlockState(cursor.set(x, y, z));
        if (state.getBlock() instanceof LegacyBlock) {
            return state.getValue(LegacyContent.META);
        }
        return table.legacyOf(state) & 15;
    }

    @Override
    public boolean setLegacyBlock(int x, int y, int z, Object legacyBlock, int meta, int flags) {
        BlockState target;
        LegacyBlock modBlock = LegacyContent.byLegacy(legacyBlock);
        if (modBlock != null) {
            target = modBlock.defaultBlockState().setValue(LegacyContent.META, meta & 15);
        } else {
            target = table.modernOf(legacyBlock, meta);
            if (target == null) {
                return false;
            }
        }
        // Legacy flags 1 (notify neighbours), 2 (send to clients) and 4 (no re-render) mean the same in 1.20.1.
        return level.setBlock(new BlockPos(x, y, z), target, flags & 7);
    }

    @Override
    public Object getLegacyTileEntity(int x, int y, int z) {
        BlockEntity blockEntity = level.getBlockEntity(cursor.set(x, y, z));
        return blockEntity instanceof LegacyBlockEntity legacy ? legacy.legacyTile() : null;
    }

    @Override
    public void markBlockForUpdate(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        level.sendBlockUpdated(pos, state, state, 3);
    }

    @Override
    public void markTileEntityChanged(int x, int y, int z) {
        BlockEntity blockEntity = level.getBlockEntity(cursor.set(x, y, z));
        if (blockEntity != null) {
            blockEntity.setChanged();
        }
    }

    @Override
    public long getTotalWorldTime() {
        return level.getGameTime();
    }

    @Override
    public boolean isClientSide() {
        return level.isClientSide;
    }
}
