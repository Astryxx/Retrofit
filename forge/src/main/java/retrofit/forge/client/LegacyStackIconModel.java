package retrofit.forge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.geometry.UnbakedGeometryHelper;
import retrofit.forge.RetrofitMod;
import retrofit.forge.world.LegacyBridge;
import retrofit.forge.world.LegacyStackItem;
import retrofit.forge.world.LegacyStacks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The item model of a legacy item that picks its icons from the whole stack (getIcon(ItemStack,
 * pass): Tinkers' tools by their parts, FiskHeroes' suits by their hero, wands by their caps).
 * The generated model only knows the icons of each damage value; this one asks the item for the
 * stack's icons and draws those, as flat layers like item/generated (tint index = pass, coloured by
 * LegacyItemColors). Looked up per item, damage and tag, as the colours are.
 */
final class LegacyStackIconModel implements BakedModel {
    private static final int MAX_CACHED = 4096;
    private static final Map<Key, String> ICONS = new ConcurrentHashMap<>();
    /** Built models by their icons and the damage's model, whose transforms they take. */
    private static final Map<ModelKey, BakedModel> MODELS = new ConcurrentHashMap<>();
    private static volatile boolean failed;

    private record Key(Item item, int meta, int tag) {
    }

    private record ModelKey(String icons, BakedModel like) {
    }

    private final BakedModel base;
    private final ItemOverrides overrides;

    LegacyStackIconModel(BakedModel base) {
        this.base = base;
        this.overrides = new ItemOverrides() {
            @Override
            public BakedModel resolve(BakedModel model, ItemStack stack, ClientLevel level, LivingEntity entity, int seed) {
                BakedModel byDamage = base.getOverrides().resolve(base, stack, level, entity, seed);
                BakedModel byStack = stack.hasTag() ? forStack(stack, byDamage) : null;
                return byStack != null ? byStack : byDamage;
            }
        };
    }

    /** Forgets the built models (the atlas was stitched again). */
    static void clear() {
        ICONS.clear();
        MODELS.clear();
    }

    private static BakedModel forStack(ItemStack stack, BakedModel byDamage) {
        if (failed || !(stack.getItem() instanceof LegacyStackItem item)) {
            return null;
        }
        Key key = new Key(stack.getItem(), item.meta(stack), stack.getTag().hashCode());
        String icons = ICONS.get(key);
        if (icons == null) {
            try {
                icons = String.join("\n", nonNull(LegacyBridge.<String[]>call("stackPassTextures", LegacyStacks.toLegacy(stack), 5)));
            } catch (RuntimeException e) {
                failed = true;
                LegacyBridge.reportClientFailure("item icons", e);
                return null;
            }
            if (ICONS.size() > MAX_CACHED) {
                ICONS.clear();
            }
            ICONS.put(key, icons);
        }
        if (icons.isEmpty()) {
            return null;
        }
        if (MODELS.size() > MAX_CACHED) {
            MODELS.clear();
        }
        return MODELS.computeIfAbsent(new ModelKey(icons, byDamage), wanted -> build(wanted.icons().split("\n"), wanted.like()));
    }

    private static String[] nonNull(String[] icons) {
        List<String> passes = new ArrayList<>();
        for (String icon : icons) {
            if (icon == null) {
                break; // passes after a missing one aren't drawn either
            }
            passes.add(icon);
        }
        return passes.toArray(String[]::new);
    }

    /** Flat layers of the given icons ("#" marks a block atlas icon), with the damage model's transforms. */
    private static BakedModel build(String[] icons, BakedModel byDamage) {
        TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        List<BakedQuad> quads = new ArrayList<>();
        TextureAtlasSprite particle = null;
        ResourceLocation location = new ResourceLocation(RetrofitMod.MOD_ID, "item/legacy_stack_icons");
        for (int layer = 0; layer < icons.length; layer++) {
            String icon = icons[layer];
            String sprite = icon.startsWith("#") ? LegacyAssetPack.spriteId("blocks", icon.substring(1)) : LegacyAssetPack.spriteId("items", icon);
            TextureAtlasSprite texture = atlas.getSprite(new ResourceLocation(sprite));
            if (texture.contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
                return byDamage; // an icon the atlas doesn't have: the damage's model is closer
            }
            if (particle == null) {
                particle = texture;
            }
            quads.addAll(UnbakedGeometryHelper.bakeElements(UnbakedGeometryHelper.createUnbakedItemElements(layer, texture.contents()),
                    material -> texture, BlockModelRotation.X0_Y0, location));
        }
        return particle == null ? byDamage : new Layers(List.copyOf(quads), particle, byDamage);
    }

    /** Baked layers, transformed and lit as the item's own model. */
    private record Layers(List<BakedQuad> quads, TextureAtlasSprite particle, BakedModel like) implements BakedModel {
        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
            return side == null ? quads : List.of();
        }

        @Override
        public boolean useAmbientOcclusion() {
            return like.useAmbientOcclusion();
        }

        @Override
        public boolean isGui3d() {
            return like.isGui3d();
        }

        @Override
        public boolean usesBlockLight() {
            return like.usesBlockLight();
        }

        @Override
        public boolean isCustomRenderer() {
            return false;
        }

        @Override
        public TextureAtlasSprite getParticleIcon() {
            return particle;
        }

        @Override
        public ItemTransforms getTransforms() {
            return like.getTransforms();
        }

        @Override
        public ItemOverrides getOverrides() {
            return ItemOverrides.EMPTY;
        }
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
        return base.getQuads(state, side, random);
    }

    @Override
    public boolean useAmbientOcclusion() {
        return base.useAmbientOcclusion();
    }

    @Override
    public boolean isGui3d() {
        return base.isGui3d();
    }

    @Override
    public boolean usesBlockLight() {
        return base.usesBlockLight();
    }

    @Override
    public boolean isCustomRenderer() {
        return base.isCustomRenderer();
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return base.getParticleIcon();
    }

    @Override
    public ItemTransforms getTransforms() {
        return base.getTransforms();
    }

    @Override
    public ItemOverrides getOverrides() {
        return overrides;
    }
}
