package retrofit.forge.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.model.data.ModelData;
import org.slf4j.Logger;
import retrofit.forge.world.LegacyBlock;
import retrofit.forge.world.LegacyBridge;
import retrofit.forge.world.LegacyStackItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Static model of a legacy block with a custom render type: built once, on first use, by running
 * 1.7.10's RenderBlocks (vanilla render types and mods' block render handlers) for the block alone,
 * and kept as ordinary baked quads. Chunk meshes include it like any other model, so it costs
 * nothing per frame and works with mesh-replacing renderers.
 *
 * Legacy vertex colours already include 1.7.10's side shading, so the quads are unshaded.
 * Transforms and the particle sprite come from the generated JSON model.
 */
final class LegacyBakedModel implements BakedModel {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final LegacyBlock block;
    private final int meta;
    private final BakedModel base;
    private volatile List<BakedQuad> quads;
    /** For the item model: the model per metadata value, chosen by the stack's subtype. */
    private LegacyBakedModel[] itemVariants;

    LegacyBakedModel(LegacyBlock block, int meta, BakedModel base) {
        this.block = block;
        this.meta = meta;
        this.base = base;
    }

    /** Quads of one block as its renderer drew it in place (see getModelData). */
    private static final net.minecraftforge.client.model.data.ModelProperty<List<BakedQuad>> IN_WORLD =
            new net.minecraftforge.client.model.data.ModelProperty<>();

    /**
     * While a chunk mesh is built: runs the legacy renderer for this block in its place in the region,
     * so it can draw from its neighbours and tile entity (connected textures, covers, pipes). Falls back
     * to the static model if that fails.
     */
    @Override
    public ModelData getModelData(net.minecraft.world.level.BlockAndTintGetter level, net.minecraft.core.BlockPos pos, BlockState state, ModelData data) {
        if (failedInWorld || net.minecraft.client.Minecraft.getInstance().level == null) {
            return data;
        }
        try {
            List<BakedQuad> placed;
            synchronized (LegacyBridge.renderLock()) {
                retrofit.forge.world.RenderRegionHost host = new retrofit.forge.world.RenderRegionHost(level,
                        net.minecraft.client.Minecraft.getInstance().level.getGameTime());
                int vertices = LegacyBridge.call("renderBlockAt", host, pos.getX(), pos.getY(), pos.getZ());
                placed = quads(vertices, level, pos);
            }
            return data.derive().with(IN_WORLD, placed).build();
        } catch (Throwable t) {
            failedInWorld = true;
            LOGGER.error("Legacy block {} meta {} could not be rendered in place; using its stand-alone model", block.getDescriptionId(), meta, t);
            return data;
        }
    }

    private volatile boolean failedInWorld;

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType renderType) {
        List<BakedQuad> placed = data.get(IN_WORLD);
        if (placed != null) {
            return side == null ? placed : List.of();
        }
        return getQuads(state, side, random);
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
        if (side != null) {
            return List.of();
        }
        List<BakedQuad> built = quads;
        if (built == null) {
            built = build();
            quads = built;
        }
        return built;
    }

    private List<BakedQuad> build() {
        synchronized (LegacyBridge.renderLock()) {
            try {
                return quads(LegacyBridge.bakeBlock(block.legacyBlock(), meta), null, null);
            } catch (Throwable t) {
                LOGGER.error("Legacy block {} meta {} could not be rendered; it will be invisible", block.getDescriptionId(), meta, t);
                return List.of();
            }
        }
    }

    /**
     * The captured vertices as baked quads. Hold the render lock. In the world ({@code level} not
     * null), each quad carries its light: what the legacy renderer set, or the light of the
     * neighbour it faces. The chunk renderer only lights faces lying exactly on the block's surface
     * from outside; legacy renderers often draw just inside or outside it, and those faces came out
     * black (lit from inside the solid block).
     */
    private List<BakedQuad> quads(int vertices, net.minecraft.world.level.BlockAndTintGetter level, net.minecraft.core.BlockPos pos) throws Throwable {
        List<BakedQuad> result = new ArrayList<>();
        TextureAtlasSprite sprite = base.getParticleIcon();
        int[] faceLight = level == null ? null : new int[6];
        if (faceLight != null) {
            for (Direction direction : Direction.values()) {
                faceLight[direction.ordinal()] = net.minecraft.client.renderer.LevelRenderer.getLightColor(level, pos.relative(direction));
            }
        }
        int[] lights = level == null ? null : LegacyCapture.lights();
        {
            {
                float[] d = LegacyCapture.data();
                for (int v = 0; v + 3 < vertices; v += 4) {
                    int[] packed = new int[32];
                    for (int corner = 0; corner < 4; corner++) {
                        int o = (v + corner) * LegacyCapture.FLOATS_PER_VERTEX;
                        int p = corner * 8;
                        packed[p] = Float.floatToRawIntBits(d[o]);
                        packed[p + 1] = Float.floatToRawIntBits(d[o + 1]);
                        packed[p + 2] = Float.floatToRawIntBits(d[o + 2]);
                        packed[p + 3] = abgr(d[o + 8], d[o + 9], d[o + 10], d[o + 11]);
                        packed[p + 4] = Float.floatToRawIntBits(d[o + 3]);
                        packed[p + 5] = Float.floatToRawIntBits(d[o + 4]);
                        packed[p + 6] = 0; // lightmap: computed by the chunk renderer (the brighter of it and this wins)
                        packed[p + 7] = normal(d[o + 5], d[o + 6], d[o + 7]);
                    }
                    int o = v * LegacyCapture.FLOATS_PER_VERTEX;
                    Direction face = Direction.getNearest(d[o + 5], d[o + 6], d[o + 7]);
                    if (faceLight != null) {
                        for (int corner = 0; corner < 4; corner++) {
                            int set = lights != null && v + corner < lights.length ? lights[v + corner] : -1;
                            packed[corner * 8 + 6] = set >= 0 ? set : faceLight[face.ordinal()];
                        }
                    }
                    result.add(new BakedQuad(packed, -1, face, sprite, false));
                }
            }
        }
        return List.copyOf(result);
    }

    private static int abgr(float r, float g, float b, float a) {
        return (channel(a) << 24) | (channel(b) << 16) | (channel(g) << 8) | channel(r);
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255)));
    }

    private static int normal(float x, float y, float z) {
        return ((int) (x * 127) & 0xFF) | (((int) (y * 127) & 0xFF) << 8) | (((int) (z * 127) & 0xFF) << 16);
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
        return ChunkRenderTypeSet.of(block.renderPass() == 1 ? RenderType.translucent() : RenderType.cutout());
    }

    @Override
    public List<RenderType> getRenderTypes(ItemStack stack, boolean fabulous) {
        return List.of(block.renderPass() == 1 ? RenderType.translucent() : RenderType.cutout());
    }

    @Override
    public boolean useAmbientOcclusion() {
        return false;
    }

    @Override
    public boolean isGui3d() {
        return true;
    }

    @Override
    public boolean usesBlockLight() {
        return true;
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return base.getParticleIcon();
    }

    @Override
    public ItemTransforms getTransforms() {
        return base.getTransforms();
    }

    /** Makes this the item model, picking the variant for the stack's legacy subtype. */
    LegacyBakedModel forItems(LegacyBakedModel[] variants) {
        LegacyBakedModel item = new LegacyBakedModel(block, meta, base);
        item.itemVariants = variants;
        return item;
    }

    private static final java.util.Map<Long, Integer> BLOCK_METAS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The block metadata an item block's damage places (ItemBlock.getMetadata: the mod decides). */
    private static int blockMeta(LegacyStackItem item, int damage) {
        if (damage >= 0 && damage < 16 || item.legacyItem() == null) {
            return damage & 15;
        }
        long key = (long) System.identityHashCode(item) << 32 | damage;
        return BLOCK_METAS.computeIfAbsent(key, k -> {
            try {
                return LegacyBridge.<Integer>call("itemBlockMeta", item.legacyItem(), damage) & 15;
            } catch (RuntimeException e) {
                return damage & 15;
            }
        });
    }

    @Override
    public ItemOverrides getOverrides() {
        if (itemVariants == null) {
            return ItemOverrides.EMPTY;
        }
        return new ItemOverrides() {
            @Override
            public BakedModel resolve(BakedModel model, ItemStack stack, ClientLevel level, LivingEntity entity, int seed) {
                int meta = stack.getItem() instanceof LegacyStackItem item ? blockMeta(item, item.meta(stack)) : 0;
                LegacyBakedModel variant = itemVariants[meta];
                return variant != null ? variant : model;
            }
        };
    }
}
