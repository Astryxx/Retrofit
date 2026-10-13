package retrofit.forge.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/** The 1.20.1 block standing in for one legacy block. Metadata is the {@link LegacyContent#META} property. */
public class LegacyBlock extends Block implements EntityBlock {
    private final Object legacyBlock;
    private final boolean hasTileEntity;

    public LegacyBlock(Object legacyBlock, boolean hasTileEntity) {
        super(BlockBehaviour.Properties.of().strength(1.5f, 6f));
        this.legacyBlock = legacyBlock;
        this.hasTileEntity = hasTileEntity;
        registerDefaultState(stateDefinition.any().setValue(LegacyContent.META, 0));
    }

    public Object legacyBlock() {
        return legacyBlock;
    }

    public boolean hasTileEntity() {
        return hasTileEntity;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LegacyContent.META);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return hasTileEntity ? new LegacyBlockEntity(pos, state) : null;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || !hasTileEntity || type != LegacyContent.blockEntityType()) {
            return null;
        }
        return (tickLevel, pos, tickState, blockEntity) -> ((LegacyBlockEntity) blockEntity).serverTick();
    }
}
