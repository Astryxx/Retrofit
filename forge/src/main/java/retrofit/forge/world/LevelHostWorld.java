package retrofit.forge.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import retrofit.api.HostWorld;

/**
 * A 1.20.1 level as seen by legacy code. Legacy mod blocks map to their {@link LegacyBlock}s;
 * vanilla blocks map to legacy air or stone for now (a full vanilla block table comes later).
 */
final class LevelHostWorld implements HostWorld {
    private static Object legacyAir;
    private static Object legacyStone;

    private final Level level;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    LevelHostWorld(Level level) {
        this.level = level;
        if (legacyAir == null) {
            legacyAir = LegacyBridge.block("minecraft:air");
            legacyStone = LegacyBridge.block("minecraft:stone");
        }
    }

    @Override
    public Object getLegacyBlock(int x, int y, int z) {
        BlockState state = level.getBlockState(cursor.set(x, y, z));
        if (state.getBlock() instanceof LegacyBlock block) {
            return block.legacyBlock();
        }
        return state.isAir() ? legacyAir : legacyStone;
    }

    @Override
    public int getLegacyMeta(int x, int y, int z) {
        BlockState state = level.getBlockState(cursor.set(x, y, z));
        return state.getBlock() instanceof LegacyBlock ? state.getValue(LegacyContent.META) : 0;
    }

    @Override
    public boolean setLegacyBlock(int x, int y, int z, Object legacyBlock, int meta, int flags) {
        BlockState target;
        if (legacyBlock == legacyAir) {
            target = Blocks.AIR.defaultBlockState();
        } else if (legacyBlock == legacyStone) {
            target = Blocks.STONE.defaultBlockState();
        } else {
            LegacyBlock block = LegacyContent.byLegacy(legacyBlock);
            if (block == null) {
                return false;
            }
            target = block.defaultBlockState().setValue(LegacyContent.META, meta & 15);
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
