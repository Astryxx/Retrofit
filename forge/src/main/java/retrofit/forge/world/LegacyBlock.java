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
    private final boolean opaque;
    private final int renderPass;
    private final String translationKey;

    /** @param renderInfo see {@link LegacyBridge#blockRenderInfo} */
    public LegacyBlock(Object legacyBlock, boolean hasTileEntity, int[] renderInfo) {
        super(properties(renderInfo));
        this.legacyBlock = legacyBlock;
        this.hasTileEntity = hasTileEntity;
        this.opaque = renderInfo[1] != 0;
        this.renderPass = renderInfo[2];
        this.translationKey = LegacyBridge.blockTranslationKey(legacyBlock);
        registerDefaultState(stateDefinition.any().setValue(LegacyContent.META, 0));
    }

    private static BlockBehaviour.Properties properties(int[] renderInfo) {
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of().strength(1.5f, 6f);
        if (renderInfo[1] == 0) {
            properties.noOcclusion(); // not an opaque cube: neighbours must still draw their faces
        }
        int light = renderInfo[3];
        if (light > 0) {
            properties.lightLevel(state -> light);
        }
        return properties;
    }

    /** The legacy translation key ("tile.<name>.name"); the asset pack supplies the mods' translations. */
    @Override
    public String getDescriptionId() {
        return translationKey;
    }

    public boolean isOpaque() {
        return opaque;
    }

    /** 0 = solid, 1 = translucent (legacy getRenderBlockPass). */
    public int renderPass() {
        return renderPass;
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
        if (!hasTileEntity || type != LegacyContent.blockEntityType()) {
            return null;
        }
        // 1.7.10 ticked tile entities on both sides (clients use it for animation and effects).
        return (tickLevel, pos, tickState, blockEntity) -> ((LegacyBlockEntity) blockEntity).tick();
    }
}
