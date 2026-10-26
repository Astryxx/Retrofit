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
    /** Whether the JSON model is the stand-alone one (plain cubes drawn in place only for their world icons). */
    private final boolean baseStandsAlone;
    private volatile List<BakedQuad> quads;
    /** For the item model: the model per metadata value, chosen by the stack's subtype. */
    private LegacyBakedModel[] itemVariants;
    /** The item damage this model draws the block as an item with (renderBlockAsItem); -1 for a block model. */
    private int itemDamage = -1;

    LegacyBakedModel(LegacyBlock block, int meta, BakedModel base, boolean baseStandsAlone) {
        this.block = block;
        this.meta = meta;
        this.base = base;
        this.baseStandsAlone = baseStandsAlone;
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
                if (DEBUG_BLOCK != null && block.getDescriptionId().contains(DEBUG_BLOCK)) {
                    debugDump(vertices, pos);
                }
                placed = quads(vertices, level, pos, 0);
            }
            return data.derive().with(IN_WORLD, placed).build();
        } catch (Throwable t) {
            failedInWorld = true;
            LOGGER.error("Legacy block {} meta {} could not be rendered in place; using its stand-alone model", block.getDescriptionId(), meta, t);
            return data;
        }
    }

    private volatile boolean failedInWorld;

    /** -Dretrofit.debugBlock=<part of a description id>: logs what the legacy renderer drew in place for such blocks. */
    private static final String DEBUG_BLOCK = System.getProperty("retrofit.debugBlock");

    private void debugDump(int vertices, net.minecraft.core.BlockPos pos) throws Throwable {
        float[] d = LegacyCapture.data();
        int[] lights = LegacyCapture.lights();
        StringBuilder out = new StringBuilder();
        for (int v = 0; v < Math.min(vertices, 48); v += 4) {
            int o = v * LegacyCapture.FLOATS_PER_VERTEX;
            int opposite = o + 2 * LegacyCapture.FLOATS_PER_VERTEX; // the quad's far corner: the texture's extent
            out.append(String.format(" [n %.0f %.0f %.0f rgba %.2f %.2f %.2f %.2f light %x uv %.5f,%.5f-%.5f,%.5f]", d[o + 5], d[o + 6], d[o + 7],
                    d[o + 8], d[o + 9], d[o + 10], d[o + 11], lights[v], d[o + 3], d[o + 4], d[opposite + 3], d[opposite + 4]));
        }
        LOGGER.info("[Debug] {} meta {} at {}: {} vertices{}", block.getDescriptionId(), meta, pos, vertices, out);
    }

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
        if (baseStandsAlone) {
            return base.getQuads(state, side, random);
        }
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
                if (itemDamage >= 0) {
                    // as RenderItem drew item blocks: renderBlockAsItem, centred on the origin, unshaded (GUI light shades it)
                    int vertices = LegacyBridge.call("renderBlockItem", block.legacyBlock(), meta, itemDamage);
                    List<BakedQuad> item = quads(vertices, null, null, 0.5f);
                    if (DEBUG_BLOCK != null && block.getDescriptionId().contains(DEBUG_BLOCK) && !item.isEmpty()) {
                        int[] first = item.get(0).getVertices();
                        LOGGER.info("[Debug] {} item {}: {} quads, first corner colour {} light {} normal {} face {}", block.getDescriptionId(),
                                itemDamage, item.size(), Integer.toHexString(first[3]), Integer.toHexString(first[6]),
                                Integer.toHexString(first[7]), item.get(0).getDirection());
                    }
                    return item;
                }
                return quads(LegacyBridge.bakeBlock(block.legacyBlock(), meta), null, null, 0);
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
     * black (lit from inside the solid block). {@code offset} is added to every coordinate (0.5 takes
     * an item drawing, centred on the origin, to the model's 0 to 1).
     */
    private List<BakedQuad> quads(int vertices, net.minecraft.world.level.BlockAndTintGetter level, net.minecraft.core.BlockPos pos,
                                  float offset) throws Throwable {
        List<BakedQuad> result = new ArrayList<>();
        TextureAtlasSprite sprite = base.getParticleIcon();
        int[] faceLight = level == null ? null : new int[6];
        if (faceLight != null) {
            for (Direction direction : Direction.values()) {
                faceLight[direction.ordinal()] = net.minecraft.client.renderer.LevelRenderer.getLightColor(level, pos.relative(direction));
            }
        }
        int[] lights = level == null ? null : LegacyCapture.lights();
        int[] states = LegacyCapture.states();
        float[] d = LegacyCapture.data();
        // the first time this block and metadata is built: what of it the block model can't show
        TextureAtlasSprite missing = DIAGNOSED.add(block.getDescriptionId() + "#" + meta) ? missingSprite() : null;
        java.util.Set<String> problems = missing == null ? java.util.Set.of() : new java.util.TreeSet<>();
        for (int v = 0; v + 3 < vertices; v += 4) {
            String problem = missing == null ? null : textureProblem(states[v], d, v, missing);
            if (problem != null) {
                problems.add(problem);
            }
            // 1.7.10's block renderers set no normals: a quad faces where its corners say
            float[] normal = faceNormal(d, v);
            // an item drawn with lighting off (glowing overlays): lit as facing straight up, which both
            // of the item lights reach fully, so the item shader leaves it as bright as it was drawn
            int lightNormal = itemDamage >= 0 && (states[v] & LegacyCapture.UNLIT) != 0 ? normal(0, 1, 0) : normal(normal[0], normal[1], normal[2]);
            int[] packed = new int[32];
            for (int corner = 0; corner < 4; corner++) {
                int o = (v + corner) * LegacyCapture.FLOATS_PER_VERTEX;
                int p = corner * 8;
                packed[p] = Float.floatToRawIntBits(d[o] + offset);
                packed[p + 1] = Float.floatToRawIntBits(d[o + 1] + offset);
                packed[p + 2] = Float.floatToRawIntBits(d[o + 2] + offset);
                packed[p + 3] = abgr(d[o + 8], d[o + 9], d[o + 10], d[o + 11]);
                packed[p + 4] = Float.floatToRawIntBits(d[o + 3]);
                packed[p + 5] = Float.floatToRawIntBits(d[o + 4]);
                packed[p + 6] = 0; // lightmap: computed by the chunk renderer (the brighter of it and this wins)
                packed[p + 7] = lightNormal;
            }
            Direction face = Direction.getNearest(normal[0], normal[1], normal[2]);
            if (faceLight != null) {
                for (int corner = 0; corner < 4; corner++) {
                    int set = lights != null && v + corner < lights.length ? lights[v + corner] : -1;
                    packed[corner * 8 + 6] = set >= 0 ? set : faceLight[face.ordinal()];
                }
            }
            result.add(new BakedQuad(packed, -1, face, sprite, false));
        }
        if (!problems.isEmpty()) {
            LOGGER.warn("Legacy block {} meta {} draws quads its block model can't show: {}", block.getDescriptionId(), meta, problems);
        }
        return List.copyOf(result);
    }

    /** Blocks and metadata values whose drawing has been checked (see textureProblem), "id#meta". */
    private static final java.util.Set<String> DIAGNOSED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static TextureAtlasSprite missingSprite() {
        return net.minecraft.client.Minecraft.getInstance().getModelManager()
                .getAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS)
                .getSprite(net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation());
    }

    /**
     * Why a captured quad would come out wrong in a block model, which samples only the block atlas:
     * drawn untextured, with another texture bound, or with the atlas's missing sprite; null if fine.
     */
    private static String textureProblem(int state, float[] d, int v, TextureAtlasSprite missing) throws Throwable {
        if ((state & LegacyCapture.UNTEXTURED) != 0) {
            return "untextured";
        }
        String texture = LegacyCapture.textureName(state & LegacyCapture.TEXTURE_MASK);
        if (!texture.contains("textures/atlas/")) {
            return "texture " + texture;
        }
        // the middle of the quad (between opposite corners) in the atlas
        int first = v * LegacyCapture.FLOATS_PER_VERTEX, opposite = (v + 2) * LegacyCapture.FLOATS_PER_VERTEX;
        float centreU = (d[first + 3] + d[opposite + 3]) / 2, centreV = (d[first + 4] + d[opposite + 4]) / 2;
        if (centreU >= missing.getU0() && centreU <= missing.getU1() && centreV >= missing.getV0() && centreV <= missing.getV1()) {
            return "missing icon";
        }
        return null;
    }

    /**
     * The normal of the quad starting at vertex {@code v}, from its corners (1.7.10 wound them
     * counter-clockwise seen from the front); the captured normal when the corners don't span a
     * plane (a degenerate quad).
     */
    private static float[] faceNormal(float[] d, int v) {
        int stride = LegacyCapture.FLOATS_PER_VERTEX;
        int a = v * stride, b = (v + 1) * stride, c = (v + 2) * stride;
        float e1x = d[b] - d[a], e1y = d[b + 1] - d[a + 1], e1z = d[b + 2] - d[a + 2];
        float e2x = d[c] - d[b], e2y = d[c + 1] - d[b + 1], e2z = d[c + 2] - d[b + 2];
        float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1e-6f) {
            c = (v + 3) * stride; // a triangle drawn as a quad repeats its last corner: try the other corner
            e2x = d[c] - d[b];
            e2y = d[c + 1] - d[b + 1];
            e2z = d[c + 2] - d[b + 2];
            nx = e1y * e2z - e1z * e2y;
            ny = e1z * e2x - e1x * e2z;
            nz = e1x * e2y - e1y * e2x;
            length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        }
        if (length < 1e-6f) {
            return new float[]{d[a + 5], d[a + 6], d[a + 7]};
        }
        return new float[]{nx / length, ny / length, nz / length};
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
        // items: the entity version of the block's render type, lit as items (as Forge does for block items)
        RenderType chunkType = block.renderPass() == 1 ? RenderType.translucent() : RenderType.cutout();
        return List.of(net.minecraftforge.client.RenderTypeHelper.getEntityRenderType(chunkType, fabulous));
    }

    @Override
    public boolean useAmbientOcclusion() {
        return false; // legacy renderers shade their own corners
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

    /**
     * Makes this the item model, picking the variant for the stack's legacy subtype: the block drawn
     * as an item, as 1.7.10 drew it (see itemDamage), for each metadata value the block has.
     */
    LegacyBakedModel forItems(LegacyBakedModel[] variants) {
        LegacyBakedModel item = asItem(meta);
        item.itemVariants = new LegacyBakedModel[variants.length];
        for (int m = 0; m < variants.length; m++) {
            if (variants[m] != null) {
                item.itemVariants[m] = m == meta ? item : variants[m].asItem(m);
            }
        }
        return item;
    }

    private LegacyBakedModel asItem(int damage) {
        LegacyBakedModel item = new LegacyBakedModel(block, meta, base, baseStandsAlone);
        item.itemDamage = damage;
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
