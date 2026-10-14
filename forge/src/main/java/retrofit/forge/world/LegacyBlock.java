package retrofit.forge.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
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

import java.util.ArrayList;
import java.util.List;

/**
 * The 1.20.1 block standing in for one legacy block. Metadata is the {@link LegacyContent#META}
 * property. Its outline and collision shapes come from the legacy block's own bounds and
 * collision code, per metadata value (see {@link LegacyBridge#blockShape}).
 */
public class LegacyBlock extends Block {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Object legacyBlock;
    private final boolean hasTileEntity;
    private final boolean opaque;
    private final int renderType;
    private final int renderPass;
    private final String translationKey;
    private final String harvestTool;
    private final int harvestLevel;
    private final boolean ticksRandomly;
    private final boolean emitsPower;
    private final boolean comparatorOutput;
    private final VoxelShape[] outlines = new VoxelShape[16];
    private final VoxelShape[] collisions = new VoxelShape[16];

    /**
     * @param renderInfo see {@link LegacyBridge#blockRenderInfo}
     * @param info       see the legacy bridge's Interactions.blockInfo
     */
    private LegacyBlock(Object legacyBlock, boolean hasTileEntity, int[] renderInfo, Object[] info) {
        super(properties(renderInfo, info));
        this.legacyBlock = legacyBlock;
        this.harvestTool = info[3] != null ? (String) info[3] : (Integer) info[5] != 0 ? "pickaxe" : null;
        this.harvestLevel = (Integer) info[4];
        this.ticksRandomly = (Integer) info[6] != 0;
        this.emitsPower = (Integer) info[7] != 0;
        this.comparatorOutput = (Integer) info[8] != 0;
        this.hasTileEntity = hasTileEntity;
        this.renderType = renderInfo[0];
        this.opaque = renderInfo[1] != 0;
        this.renderPass = renderInfo[2];
        this.translationKey = LegacyBridge.blockTranslationKey(legacyBlock);
        registerDefaultState(stateDefinition.any().setValue(LegacyContent.META, 0));
    }

    private static BlockBehaviour.Properties properties(int[] renderInfo, Object[] info) {
        float hardness = (Float) info[0];
        float resistance = (Float) info[1];
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of()
                .strength(hardness < 0 ? -1 : hardness, Math.max(0, resistance))
                .sound(soundType((String) info[2]));
        if (info[3] != null || (Integer) info[5] != 0) {
            properties.requiresCorrectToolForDrops(); // the data pack tags the tool (see LegacyDataPack)
        }
        if (renderInfo[1] == 0) {
            properties.noOcclusion(); // not an opaque cube: neighbours must still draw their faces
        }
        int light = renderInfo[3];
        if (light > 0) {
            properties.lightLevel(state -> light);
        }
        return properties;
    }

    /** 1.7.10 step sounds by name (Block.SoundType.soundName); mod sounds fall back to stone. */
    private static SoundType soundType(String name) {
        if (name == null) {
            return SoundType.STONE;
        }
        return switch (name) {
            case "wood" -> SoundType.WOOD;
            case "gravel" -> SoundType.GRAVEL;
            case "grass" -> SoundType.GRASS;
            case "cloth" -> SoundType.WOOL;
            case "sand" -> SoundType.SAND;
            case "snow" -> SoundType.SNOW;
            case "ladder" -> SoundType.LADDER;
            case "anvil" -> SoundType.ANVIL;
            default -> SoundType.STONE;
        };
    }

    /** "pickaxe", "axe", "shovel" or another Forge tool class, or null if any tool (or none) works. */
    public String harvestTool() {
        return harvestTool;
    }

    public int harvestLevel() {
        return harvestLevel;
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

    /** The stand-in for a legacy block: {@link WithTile} if it has a tile entity for any metadata value. */
    public static LegacyBlock create(Object legacyBlock, int[] renderInfo, Object[] info) {
        boolean tile = false;
        for (int meta = 0; meta < 16 && !tile; meta++) {
            try {
                tile = LegacyBridge.hasTileEntity(legacyBlock, meta);
            } catch (RuntimeException e) {
                // metadata the block does not use
            }
        }
        return tile ? new WithTile(legacyBlock, renderInfo, info) : new LegacyBlock(legacyBlock, false, renderInfo, info);
    }

    /** Plain legacy block. */
    public LegacyBlock(Object legacyBlock, int[] renderInfo, Object[] info) {
        this(legacyBlock, false, renderInfo, info);
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

    /**
     * A legacy block with a tile entity (for at least one metadata value). Only these are
     * EntityBlocks: 1.20.1 gives every state of an EntityBlock a block entity slot (world
     * generation records one for each placed block), which plain legacy blocks must not have.
     */
    public static final class WithTile extends LegacyBlock implements EntityBlock {
        public WithTile(Object legacyBlock, int[] renderInfo, Object[] info) {
            super(legacyBlock, true, renderInfo, info);
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new LegacyBlockEntity(pos, state);
        }

        @Override
        public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
            if (type != LegacyContent.blockEntityType()) {
                return null;
            }
            // 1.7.10 ticked tile entities on both sides (clients use it for animation and effects).
            return (tickLevel, pos, tickState, blockEntity) -> ((LegacyBlockEntity) blockEntity).tick();
        }
    }

    // ---- Behaviour: each forwards to the legacy block, contained by LegacyErrors ----

    private static int meta(BlockState state) {
        return state.getValue(LegacyContent.META);
    }

    // ---- Entities touching the block (portals, webs, traps, goo) ----

    /** The legacy form of an entity touching a block: a player's mirror or a legacy creature; 1.20.1 creatures have none. */
    private static Object touching(net.minecraft.world.entity.Entity entity) {
        if (entity instanceof Player player) {
            return LegacyPlayers.mirror(player, false);
        }
        if (entity instanceof LegacyEntityCarrier carrier) {
            return entity.level().isClientSide ? carrier.clientLegacy() : carrier.core().legacy();
        }
        return null;
    }

    private void touch(Level level, BlockPos pos, net.minecraft.world.entity.Entity entity, int kind, float fallDistance) {
        if (level.isClientSide && !(entity instanceof LegacyEntityCarrier) && entity != retrofit.forge.world.LegacyPlayers.localPlayerOrNull()) {
            return; // on clients, only the local player (and legacy creatures) are moved by their own client
        }
        try {
            Object legacy = touching(entity);
            if (legacy == null) {
                return;
            }
            Vec3 motion = entity.getDeltaMovement();
            double[] after = LegacyBridge.call("entityTouch", LegacyContent.legacyWorld(level), legacyBlock, pos.getX(), pos.getY(), pos.getZ(),
                    legacy, kind, motion.x, motion.y, motion.z, fallDistance);
            if (after[0] != motion.x || after[1] != motion.y || after[2] != motion.z) {
                if (!(entity instanceof Player) || level.isClientSide) { // players move on their client
                    entity.setDeltaMovement(after[0], after[1], after[2]);
                }
            }
            if (entity instanceof Player player && !level.isClientSide) {
                LegacyPlayers.writeBack(player, legacy); // damage, effects, items
            }
        } catch (RuntimeException e) {
            LegacyErrors.report(this, "touched by an entity", e);
        }
    }

    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos, net.minecraft.world.entity.Entity entity) {
        touch(level, pos, entity, 0, 0);
    }

    @Override
    public void stepOn(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.Entity entity) {
        touch(level, pos, entity, 1, 0);
    }

    @Override
    public void fallOn(Level level, BlockState state, BlockPos pos, net.minecraft.world.entity.Entity entity, float fallDistance) {
        super.fallOn(level, state, pos, entity, fallDistance);
        touch(level, pos, entity, 2, fallDistance);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS; // 1.7.10 had one hand
        }
        try {
            Object mirror = LegacyPlayers.mirror(player, true);
            Vec3 at = hit.getLocation();
            boolean used = LegacyBridge.call("activate", legacyBlock, LegacyContent.legacyWorld(level), pos.getX(), pos.getY(), pos.getZ(),
                    mirror, hit.getDirection().get3DDataValue(), (float) (at.x - pos.getX()), (float) (at.y - pos.getY()),
                    (float) (at.z - pos.getZ()));
            LegacyPlayers.writeBack(player, mirror);
            return used ? InteractionResult.sidedSuccess(level.isClientSide) : InteractionResult.PASS;
        } catch (RuntimeException e) {
            LegacyErrors.report(translationKey, "being used", e);
            return InteractionResult.PASS;
        }
    }

    @Override
    public void attack(BlockState state, Level level, BlockPos pos, Player player) {
        try {
            Object mirror = LegacyPlayers.mirror(player, true);
            LegacyBridge.call("clicked", legacyBlock, LegacyContent.legacyWorld(level), pos.getX(), pos.getY(), pos.getZ(), mirror);
            LegacyPlayers.writeBack(player, mirror);
        } catch (RuntimeException e) {
            LegacyErrors.report(translationKey, "being clicked", e);
        }
    }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        try {
            Object mirror = LegacyPlayers.mirror(player, true);
            LegacyBridge.call("harvested", legacyBlock, LegacyContent.legacyWorld(level), pos.getX(), pos.getY(), pos.getZ(), meta(state), mirror);
            LegacyPlayers.writeBack(player, mirror);
        } catch (RuntimeException e) {
            LegacyErrors.report(translationKey, "being broken", e);
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    /** breakBlock while the tile entity is still there (mods drop their contents here), then the usual removal. */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!state.is(newState.getBlock()) && !level.isClientSide) {
            try {
                LegacyBridge.call("breakBlock", legacyBlock, LegacyContent.legacyWorld(level), pos.getX(), pos.getY(), pos.getZ(), meta(state));
            } catch (RuntimeException e) {
                LegacyErrors.report(translationKey, "being removed", e);
            }
        }
        super.onRemove(state, level, pos, newState, moving);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moving) {
        super.onPlace(state, level, pos, oldState, moving);
        if (!oldState.is(this) && !level.isClientSide) {
            try {
                LegacyBridge.call("added", legacyBlock, LegacyContent.legacyWorld(level), pos.getX(), pos.getY(), pos.getZ());
            } catch (RuntimeException e) {
                LegacyErrors.report(translationKey, "being placed", e);
            }
        }
    }

    /** Blocks placed by 1.20.1 code for a player (legacy item blocks place through their own code instead). */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (placer instanceof Player player) {
            try {
                Object mirror = LegacyPlayers.mirror(player, true);
                LegacyBridge.call("placedBy", legacyBlock, LegacyContent.legacyWorld(level), pos.getX(), pos.getY(), pos.getZ(), mirror);
            } catch (RuntimeException e) {
                LegacyErrors.report(translationKey, "being placed by a player", e);
            }
        }
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos fromPos, boolean moving) {
        super.neighborChanged(state, level, pos, neighbor, fromPos, moving);
        try {
            Object legacyNeighbor = neighbor instanceof LegacyBlock block ? block.legacyBlock
                    : LegacyBlockTable.get().legacyBlock(LegacyBlockTable.get().legacyOf(neighbor.defaultBlockState()) >> 4);
            LegacyBridge.call("neighborChanged", legacyBlock, LegacyContent.legacyWorld(level), pos.getX(), pos.getY(), pos.getZ(), legacyNeighbor);
        } catch (RuntimeException e) {
            LegacyErrors.report(translationKey, "reacting to a neighbour", e);
        }
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        updateTick(level, pos);
    }

    @Override
    public boolean isRandomlyTicking(BlockState state) {
        return ticksRandomly;
    }

    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        updateTick(level, pos); // 1.7.10 random ticks were updateTick calls
    }

    private void updateTick(Level level, BlockPos pos) {
        try {
            LegacyBridge.call("updateTick", legacyBlock, LegacyContent.legacyWorld(level), pos.getX(), pos.getY(), pos.getZ());
        } catch (RuntimeException e) {
            LegacyErrors.report(translationKey, "ticking", e);
        }
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        try {
            LegacyBridge.call("displayTick", legacyBlock, LegacyContent.legacyWorld(level), pos.getX(), pos.getY(), pos.getZ());
        } catch (RuntimeException e) {
            LegacyErrors.report(translationKey, "showing effects", e);
        }
    }

    // ---- Redstone ----

    @Override
    public boolean isSignalSource(BlockState state) {
        return emitsPower;
    }

    @Override
    public int getSignal(BlockState state, BlockGetter getter, BlockPos pos, Direction direction) {
        return power("weakPower", getter, pos, direction);
    }

    @Override
    public int getDirectSignal(BlockState state, BlockGetter getter, BlockPos pos, Direction direction) {
        return power("strongPower", getter, pos, direction);
    }

    private int power(String kind, BlockGetter getter, BlockPos pos, Direction direction) {
        if (!emitsPower || !(getter instanceof Level level)) {
            return 0;
        }
        try {
            int power = LegacyBridge.call(kind, legacyBlock, LegacyContent.legacyWorld(level), pos.getX(), pos.getY(), pos.getZ(),
                    direction.get3DDataValue());
            return Math.max(0, Math.min(15, power));
        } catch (RuntimeException e) {
            LegacyErrors.report(translationKey, "giving redstone power", e);
            return 0;
        }
    }

    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return comparatorOutput;
    }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        try {
            int output = LegacyBridge.call("comparatorOutput", legacyBlock, LegacyContent.legacyWorld(level), pos.getX(), pos.getY(), pos.getZ(), 0);
            return Math.max(0, Math.min(15, output));
        } catch (RuntimeException e) {
            LegacyErrors.report(translationKey, "giving a comparator output", e);
            return 0;
        }
    }

    // ---- Drops ----

    /** The legacy block's own drops (Forge getDrops), with fortune and silk touch from the tool. */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        Vec3 origin = builder.getOptionalParameter(LootContextParams.ORIGIN);
        if (origin == null) {
            return List.of();
        }
        BlockPos pos = BlockPos.containing(origin);
        ItemStack tool = builder.getOptionalParameter(LootContextParams.TOOL);
        Entity entity = builder.getOptionalParameter(LootContextParams.THIS_ENTITY);
        Level level = builder.getLevel();
        List<ItemStack> drops = new ArrayList<>();
        try {
            Object world = LegacyContent.legacyWorld(level);
            if (tool != null && tool.getEnchantmentLevel(Enchantments.SILK_TOUCH) > 0 && entity instanceof Player player) {
                Object silk = LegacyBridge.call("silkDrop", legacyBlock, world, pos.getX(), pos.getY(), pos.getZ(), meta(state),
                        LegacyPlayers.mirror(player, false));
                if (silk != null) {
                    drops.add(LegacyStacks.toModern(silk));
                    return drops;
                }
            }
            int fortune = tool == null ? 0 : tool.getEnchantmentLevel(Enchantments.BLOCK_FORTUNE);
            Object[] legacy = LegacyBridge.call("drops", legacyBlock, world, pos.getX(), pos.getY(), pos.getZ(), meta(state), fortune);
            for (Object stack : legacy) {
                ItemStack drop = LegacyStacks.toModern(stack);
                if (!drop.isEmpty()) {
                    drops.add(drop);
                }
            }
        } catch (RuntimeException e) {
            LegacyErrors.report(translationKey, "dropping items", e);
        }
        return drops;
    }

    @Override
    public int getExpDrop(BlockState state, LevelReader reader, RandomSource random, BlockPos pos, int fortune, int silkTouch) {
        if (silkTouch > 0 || !(reader instanceof Level level)) {
            return 0;
        }
        try {
            return LegacyBridge.call("expDrop", legacyBlock, LegacyContent.legacyWorld(level), meta(state), fortune);
        } catch (RuntimeException e) {
            LegacyErrors.report(translationKey, "dropping experience", e);
            return 0;
        }
    }

    @Override
    public ItemStack getCloneItemStack(BlockGetter getter, BlockPos pos, BlockState state) {
        if (getter instanceof Level level) {
            try {
                ItemStack stack = LegacyStacks.toModern(LegacyBridge.call("pickStack", legacyBlock, LegacyContent.legacyWorld(level),
                        pos.getX(), pos.getY(), pos.getZ()));
                if (!stack.isEmpty()) {
                    return stack;
                }
            } catch (RuntimeException e) {
                LegacyErrors.report(translationKey, "being picked", e);
            }
        }
        return super.getCloneItemStack(getter, pos, state);
    }
}
