package retrofit.forge.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraftforge.common.world.BiomeModifier;
import net.minecraftforge.common.world.ModifiableBiomeInfo;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import org.slf4j.Logger;
import retrofit.forge.RetrofitMod;

/**
 * Legacy world generators (GameRegistry.registerWorldGenerator) in 1.20.1 world generation: one
 * feature, "retrofit:legacy_generators", added to every biome by a biome modifier, runs all of
 * them for each new chunk, last among the chunk's features (1.7.10 ran them after decoration).
 * The data pack ({@link LegacyDataPack}) defines the feature and the modifier when any legacy
 * mod registered a generator.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class LegacyWorldGen {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final ResourceLocation ID = new ResourceLocation(RetrofitMod.MOD_ID, "legacy_generators");
    /** Legacy ores also generate in the deepslate layer (-Dretrofit.deepOres=false: only above y 0, as 1.7.10 had it). */
    static final boolean DEEP_ORES = !"false".equals(System.getProperty("retrofit.deepOres"));

    private LegacyWorldGen() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterEvent event) {
        event.register(Registries.FEATURE, helper -> helper.register(ID, new Generators()));
        event.register(ForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, helper -> helper.register(ID, Everywhere.CODEC));
    }

    /** Data pack entries for the feature and the biome modifier; nothing if no legacy mod generates anything. */
    static void addTo(LegacyDataPack pack) {
        boolean generators;
        try {
            generators = LegacyBridge.call("hasGenerators");
        } catch (RuntimeException e) {
            LOGGER.error("Could not check for legacy world generators", e);
            return;
        }
        if (!generators) {
            return;
        }
        JsonObject configured = new JsonObject();
        configured.addProperty("type", ID.toString());
        configured.add("config", new JsonObject());
        pack.put(ID.getNamespace(), "worldgen/configured_feature/" + ID.getPath() + ".json", configured.toString());
        JsonObject placed = new JsonObject();
        placed.addProperty("feature", ID.toString());
        placed.add("placement", new JsonArray());
        pack.put(ID.getNamespace(), "worldgen/placed_feature/" + ID.getPath() + ".json", placed.toString());
        JsonObject modifier = new JsonObject();
        modifier.addProperty("type", ID.toString());
        modifier.addProperty("feature", ID.toString());
        pack.put(ID.getNamespace(), "forge/biome_modifier/" + ID.getPath() + ".json", modifier.toString());
        LOGGER.info("Legacy world generators run in every new chunk");
    }

    /** Runs the legacy generators for the chunk being decorated. */
    static final class Generators extends Feature<NoneFeatureConfiguration> {
        Generators() {
            super(NoneFeatureConfiguration.CODEC);
        }

        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
            WorldGenLevel region = context.level();
            ChunkPos chunk = new ChunkPos(context.origin());
            int dimension = LegacyContent.legacyDimension(region.getLevel());
            try {
                Object world = LegacyBridge.call("genWorld", dimension);
                RegionHostWorld host = new RegionHostWorld(region, world, chunk);
                LegacyDeferredBlocks.applyTo(region.getLevel().dimension(), chunk.x, chunk.z, host); // structures a neighbour's generators reached in here
                LegacyBridge.call("generate", host, dimension, chunk.x, chunk.z);
                host.flushTiles();
                host.fixShapes();
                if (dimension == 0 && DEEP_ORES) {
                    generateDeep(region, world, chunk); // legacy ores below y 0 too
                }
            } catch (RuntimeException e) {
                LegacyErrors.report("world generator", "generating chunk " + chunk, e);
            }
            return true;
        }
    }

    /** The deep pass of the overworld's legacy generators over a chunk (see RegionHostWorld); also run by the play test. */
    public static void generateDeep(WorldGenLevel region, Object legacyWorld, ChunkPos chunk) {
        RegionHostWorld deep = RegionHostWorld.deep(region, legacyWorld, chunk);
        if (deep != null) {
            LegacyBridge.call("generate", deep, 0, chunk.x, chunk.z);
            deep.flushTiles();
        }
    }

    /** Adds one placed feature to every biome, in the last decoration step. */
    record Everywhere(Holder<PlacedFeature> feature) implements BiomeModifier {
        static final Codec<Everywhere> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                PlacedFeature.CODEC.fieldOf("feature").forGetter(Everywhere::feature)).apply(instance, Everywhere::new));

        @Override
        public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
            if (phase == Phase.ADD) {
                builder.getGenerationSettings().addFeature(GenerationStep.Decoration.TOP_LAYER_MODIFICATION, feature);
            }
        }

        @Override
        public Codec<? extends BiomeModifier> codec() {
            return CODEC;
        }
    }
}
