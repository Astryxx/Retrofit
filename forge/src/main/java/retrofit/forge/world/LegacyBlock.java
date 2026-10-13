package retrofit.forge.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;

/**
 * The 1.20.1 block standing in for one legacy block. Metadata is the {@link LegacyContent#META}
 * property. Its outline and collision shapes come from the legacy block's own bounds and
 * collision code, per metadata value (see {@link LegacyBridge#blockShape}).
 */
public class LegacyBlock extends Block implements EntityBlock {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Object legacyBlock;
    private final boolean hasTileEntity;
    private final boolean opaque;
    private final int renderType;
    private final int renderPass;
    private final String translationKey;
    private final VoxelShape[] outlines = new VoxelShape[16];
    private final VoxelShape[] collisions = new VoxelShape[16];

    /** @param renderInfo see {@link LegacyBridge#blockRenderInfo} */
    public LegacyBlock(Object legacyBlock, boolean hasTileEntity, int[] renderInfo) {
        super(properties(renderInfo));
        this.legacyBlock = legacyBlock;
        this.hasTileEntity = hasTileEntity;
        this.renderType = renderInfo[0];
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

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int meta = state.getValue(LegacyContent.META);
        if (outlines[meta] == null) {
            computeShapes(meta);
        }
        return outlines[meta];
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int meta = state.getValue(LegacyContent.META);
        if (collisions[meta] == null) {
            computeShapes(meta);
        }
        return collisions[meta];
    }

    /** Cached per metadata value; a block whose shape code fails keeps a full cube. */
    private void computeShapes(int meta) {
        VoxelShape outline = Shapes.block();
        VoxelShape collision = Shapes.block();
        try {
            double[] shape = LegacyBridge.blockShape(legacyBlock, meta);
            outline = box(shape, 0);
            collision = Shapes.empty();
            for (int o = 6; o + 6 <= shape.length; o += 6) {
                collision = Shapes.or(collision, box(shape, o));
            }
            collision = collision.optimize();
        } catch (RuntimeException e) {
            LOGGER.warn("Legacy block {} meta {} has no usable shape; using a full cube", translationKey, meta, e);
        }
        collisions[meta] = collision;
        outlines[meta] = outline;
    }

    private static VoxelShape box(double[] values, int o) {
        if (values[o] >= values[o + 3] || values[o + 1] >= values[o + 4] || values[o + 2] >= values[o + 5]) {
            return Shapes.empty();
        }
        return Shapes.box(values[o], values[o + 1], values[o + 2], values[o + 3], values[o + 4], values[o + 5]);
    }

    public boolean isOpaque() {
        return opaque;
    }

    /** The legacy render type: 0 = plain cube, -1 = drawn only by its tile entity renderer, else RenderBlocks. */
    public int renderType() {
        return renderType;
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
