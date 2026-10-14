package retrofit.forge.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.random.WeightedRandomList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.portal.PortalInfo;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.ITeleporter;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegisterEvent;
import org.slf4j.Logger;
import retrofit.api.HostWorld;
import retrofit.forge.RetrofitMod;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * Mods' own dimensions as 1.20.1 dimensions ("retrofit:legacy_dim_N" for legacy dimension N).
 *
 * The data pack defines each: a dimension type from the mod's WorldProvider (sky or ceiling, bed
 * use), a biome of its own, and the "retrofit:legacy" chunk generator, which takes each new chunk's
 * blocks from the mod's chunk generator and decorates it with the mod's populator and world
 * generators (see the legacy bridge's Dimensions). Creatures spawn from the mod's generator's
 * lists. The dimension's legacy world uses the mod's WorldProvider, so mods recognise it by its
 * legacy id.
 *
 * Travel: legacy code sending a player elsewhere (travelToDimension, transferPlayerToDimension)
 * moves the host player there ({@link Teleport}), and the mod's Teleporter places it.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID)
public final class LegacyDimensions {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final ResourceLocation GENERATOR_ID = new ResourceLocation(RetrofitMod.MOD_ID, "legacy");
    private static final String PREFIX = "legacy_dim_";

    private LegacyDimensions() {
    }

    /** Mod-bus registration (see {@link Registration}). */
    @Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        private Registration() {
        }

        @SubscribeEvent
        public static void onRegister(RegisterEvent event) {
            event.register(Registries.CHUNK_GENERATOR, helper -> helper.register(GENERATOR_ID, Generator.CODEC));
        }
    }

    // ---- Identity ----

    /** The 1.20.1 dimension of a legacy dimension id. */
    public static ResourceKey<Level> levelKey(int legacyDimension) {
        return switch (legacyDimension) {
            case 0 -> Level.OVERWORLD;
            case -1 -> Level.NETHER;
            case 1 -> Level.END;
            default -> ResourceKey.create(Registries.DIMENSION, new ResourceLocation(RetrofitMod.MOD_ID, PREFIX + legacyDimension));
        };
    }

    /** The legacy id of a 1.20.1 dimension: the mods' own, -1, 1, else 0 (dimensions of 1.20.1 mods count as the overworld). */
    static int legacyDimension(Level level) {
        ResourceLocation id = level.dimension().location();
        if (id.getNamespace().equals(RetrofitMod.MOD_ID) && id.getPath().startsWith(PREFIX)) {
            try {
                return Integer.parseInt(id.getPath().substring(PREFIX.length()));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        if (level.dimension() == Level.NETHER) {
            return -1;
        }
        return level.dimension() == Level.END ? 1 : 0;
    }

    // ---- Data pack ----

    /** Dimension type, biome and dimension entries for each mod dimension. */
    static void addTo(LegacyDataPack pack) {
        Object[][] dimensions;
        try {
            dimensions = LegacyBridge.call("legacyDimensions");
        } catch (RuntimeException e) {
            LOGGER.error("Could not list the legacy mods' dimensions", e);
            return;
        }
        for (Object[] dimension : dimensions) {
            int id = (Integer) dimension[0];
            String name = (String) dimension[1];
            int flags = (Integer) dimension[2];
            boolean noSky = (flags & 1) != 0;
            boolean hell = (flags & 2) != 0;
            boolean respawn = (flags & 4) != 0;
            String path = PREFIX + id;

            JsonObject type = new JsonObject();
            type.addProperty("ultrawarm", hell);
            type.addProperty("natural", !hell);
            type.addProperty("coordinate_scale", 1.0);
            type.addProperty("has_skylight", !noSky);
            type.addProperty("has_ceiling", noSky);
            type.addProperty("ambient_light", hell ? 0.1 : 0.0);
            type.addProperty("piglin_safe", false);
            type.addProperty("bed_works", respawn);
            type.addProperty("respawn_anchor_works", false);
            type.addProperty("has_raids", false);
            type.addProperty("min_y", 0); // 1.7.10 worlds: 0 to 255
            type.addProperty("height", 256);
            type.addProperty("logical_height", 256);
            type.addProperty("infiniburn", "#minecraft:infiniburn_overworld");
            type.addProperty("effects", noSky ? "minecraft:the_nether" : "minecraft:overworld");
            JsonObject light = new JsonObject();
            light.addProperty("type", "minecraft:uniform");
            JsonObject range = new JsonObject();
            range.addProperty("min_inclusive", 0);
            range.addProperty("max_inclusive", 7);
            light.add("value", range);
            type.add("monster_spawn_light_level", light);
            type.addProperty("monster_spawn_block_light_limit", 0);
            pack.put(RetrofitMod.MOD_ID, "dimension_type/" + path + ".json", type.toString());

            JsonObject biome = new JsonObject();
            biome.addProperty("has_precipitation", !noSky && !hell);
            biome.addProperty("temperature", hell ? 2.0 : 0.8);
            biome.addProperty("downfall", 0.4);
            JsonObject effects = new JsonObject();
            effects.addProperty("sky_color", hell ? 0x330808 : 0x78A7FF);
            effects.addProperty("fog_color", hell ? 0x330808 : 0xC0D8FF);
            effects.addProperty("water_color", 0x3F76E4);
            effects.addProperty("water_fog_color", 0x050533);
            biome.add("effects", effects);
            biome.add("spawners", new JsonObject()); // creatures come from the mod's generator (Generator.getMobsAt)
            biome.add("spawn_costs", new JsonObject());
            biome.add("carvers", new JsonObject());
            biome.add("features", new JsonArray());
            pack.put(RetrofitMod.MOD_ID, "worldgen/biome/" + path + ".json", biome.toString());

            JsonObject source = new JsonObject();
            source.addProperty("type", "minecraft:fixed");
            source.addProperty("biome", RetrofitMod.MOD_ID + ":" + path);
            JsonObject generator = new JsonObject();
            generator.addProperty("type", GENERATOR_ID.toString());
            generator.addProperty("legacy_dimension", id);
            generator.addProperty("ground_level", (Integer) dimension[3]);
            generator.add("biome_source", source);
            JsonObject stem = new JsonObject();
            stem.addProperty("type", RetrofitMod.MOD_ID + ":" + path);
            stem.add("generator", generator);
            pack.put(RetrofitMod.MOD_ID, "dimension/" + path + ".json", stem.toString());
            LOGGER.info("Legacy dimension {} ({}) is {}:{}", id, name, RetrofitMod.MOD_ID, path);
        }
    }

    /** Server levels of mod dimensions get their legacy world right away: mods look dimensions up by id (DimensionManager.getWorld). */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (RetrofitMod.runtime() != null && event.getLevel() instanceof ServerLevel level && legacyDimension(level) != 0) {
            try {
                LegacyContent.legacyWorld(level);
            } catch (RuntimeException e) {
                LegacyErrors.report("legacy dimension " + level.dimension().location(), "being loaded", e);
            }
        }
    }

    // ---- Travel ----

    /** Moves a player to a legacy dimension, after the current tick's work (see HostPlayer.changeDimension). */
    static void travel(ServerPlayer player, int legacyDimension, Object legacyTeleporter) {
        MinecraftServer server = player.server;
        ServerLevel target = server.getLevel(levelKey(legacyDimension));
        if (target == null) {
            LOGGER.warn("Legacy code sent {} to dimension {}, which isn't loaded", player.getGameProfile().getName(), legacyDimension);
            return;
        }
        LOGGER.info("Legacy code sends {} to dimension {} ({}){}", player.getGameProfile().getName(), legacyDimension,
                target.dimension().location(), legacyTeleporter == null ? "" : " with " + legacyTeleporter.getClass().getName());
        server.execute(() -> {
            if (!player.isRemoved() && player.level() != target) {
                player.changeDimension(target, new Teleport(legacyTeleporter));
            }
        });
    }

    /** A dimension change as 1.7.10 made it: same coordinates (an eighth for the Nether), then the mod's Teleporter. */
    static final class Teleport implements ITeleporter {
        private final Object legacyTeleporter;

        Teleport(Object legacyTeleporter) {
            this.legacyTeleporter = legacyTeleporter;
        }

        @Override
        public PortalInfo getPortalInfo(Entity entity, ServerLevel destination, Function<ServerLevel, PortalInfo> defaultPortalInfo) {
            double scale = entity.level().dimensionType().coordinateScale() / destination.dimensionType().coordinateScale();
            double x = entity.getX() * scale;
            double z = entity.getZ() * scale;
            double y = entity.getY();
            if (legacyTeleporter == null) {
                // No teleporter to place the player: stand on the ground there, or on a platform over a void (as 1.7.10's End did).
                int bx = (int) Math.floor(x);
                int bz = (int) Math.floor(z);
                y = destination.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
                if (y <= destination.getMinBuildHeight()) {
                    y = 64;
                    for (int dx = -2; dx <= 2; dx++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            destination.setBlockAndUpdate(new BlockPos(bx + dx, 63, bz + dz), Blocks.OBSIDIAN.defaultBlockState());
                        }
                    }
                }
            }
            return new PortalInfo(new Vec3(x, y, z), Vec3.ZERO, entity.getYRot(), entity.getXRot());
        }

        @Override
        public Entity placeEntity(Entity entity, ServerLevel current, ServerLevel destination, float yaw, Function<Boolean, Entity> reposition) {
            Entity moved = reposition.apply(false);
            if (legacyTeleporter != null && moved instanceof ServerPlayer player) {
                try {
                    Object mirror = LegacyPlayers.mirror(player, false);
                    double[] placed = LegacyBridge.call("placeInPortal", legacyTeleporter, mirror, player.getX(), player.getY(), player.getZ(), yaw);
                    player.teleportTo(destination, placed[0], placed[1], placed[2], (float) placed[3], player.getXRot());
                } catch (RuntimeException e) {
                    LegacyErrors.report("legacy teleporter " + legacyTeleporter.getClass().getName(), "placing a player", e);
                }
            }
            return moved;
        }

        @Override
        public boolean playTeleportSound(ServerPlayer player, ServerLevel source, ServerLevel destination) {
            return false;
        }
    }

    // ---- Generation ----

    /** The "retrofit:legacy" chunk generator: a mod dimension's terrain from the mod's own generator. */
    public static final class Generator extends ChunkGenerator {
        static final Codec<Generator> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.biomeSource),
                Codec.INT.fieldOf("legacy_dimension").forGetter(generator -> generator.dimension),
                Codec.INT.optionalFieldOf("ground_level", 64).forGetter(generator -> generator.groundLevel)
        ).apply(instance, Generator::new));

        private final int dimension;
        private final int groundLevel;
        /** Legacy (id << 4 | meta) -> state, filled as blocks show up. */
        private final BlockState[] states = new BlockState[4096 * 16];

        Generator(BiomeSource biomes, int dimension, int groundLevel) {
            super(biomes);
            this.dimension = dimension;
            this.groundLevel = groundLevel;
        }

        @Override
        protected Codec<? extends ChunkGenerator> codec() {
            return CODEC;
        }

        private BlockState state(int legacy) {
            BlockState state = states[legacy];
            if (state == null) {
                Object block = LegacyBlockTable.get().legacyBlock(legacy >> 4);
                state = block == null ? null : LegacyBlockTable.modernState(block, legacy & 15);
                if (state == null) {
                    state = Blocks.STONE.defaultBlockState(); // a block with no 1.20.1 form: keep the terrain solid
                }
                states[legacy] = state;
            }
            return state;
        }

        @Override
        public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender, RandomState random, StructureManager structures, ChunkAccess chunk) {
            ChunkPos pos = chunk.getPos();
            try {
                int[] blocks = LegacyBridge.call("provideChunk", new ChunkView(chunk), dimension, pos.x, pos.z);
                BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
                for (int i = 0; i < blocks.length; i++) {
                    int legacy = blocks[i];
                    if (legacy != 0) {
                        at.set(pos.getMinBlockX() + (i & 15), i >> 8, pos.getMinBlockZ() + (i >> 4 & 15));
                        chunk.setBlockState(at, state(legacy), false);
                    }
                }
            } catch (RuntimeException e) {
                LegacyErrors.report("legacy dimension " + dimension, "generating chunk " + pos, e);
            }
            return CompletableFuture.completedFuture(chunk);
        }

        /** The mod's populator and world generators (1.7.10's ChunkProviderServer.populate), instead of 1.20.1 features. */
        @Override
        public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {
            ChunkPos pos = chunk.getPos();
            try {
                RegionHostWorld host = new RegionHostWorld(level, LegacyBridge.call("genWorld", dimension), pos);
                LegacyBridge.call("populate", host, dimension, pos.x, pos.z);
                host.flushTiles();
            } catch (RuntimeException e) {
                LegacyErrors.report("legacy dimension " + dimension, "decorating chunk " + pos, e);
            }
        }

        /** Creatures the mod's generator lists for the spot (IChunkProvider.getPossibleCreatures). */
        @Override
        public WeightedRandomList<MobSpawnSettings.SpawnerData> getMobsAt(net.minecraft.core.Holder<Biome> biome, StructureManager structures,
                                                                          MobCategory category, BlockPos pos) {
            int legacyCategory = switch (category) {
                case MONSTER -> 0;
                case CREATURE -> 1;
                case AMBIENT -> 2;
                case WATER_CREATURE -> 3;
                default -> -1;
            };
            if (legacyCategory < 0) {
                return WeightedRandomList.create();
            }
            try {
                Object[][] spawns = LegacyBridge.call("dimensionSpawns", dimension, legacyCategory, pos.getX(), pos.getY(), pos.getZ());
                List<MobSpawnSettings.SpawnerData> result = new ArrayList<>();
                for (Object[] spawn : spawns) {
                    EntityType<?> type = LegacyEntityTypes.byName((String) spawn[0]);
                    if (type != null) {
                        result.add(new MobSpawnSettings.SpawnerData(type, Math.max(1, (Integer) spawn[1]), (Integer) spawn[2], (Integer) spawn[3]));
                    }
                }
                return WeightedRandomList.create(result);
            } catch (RuntimeException e) {
                return WeightedRandomList.create();
            }
        }

        @Override
        public void applyCarvers(WorldGenRegion region, long seed, RandomState random, BiomeManager biomes, StructureManager structures,
                                 ChunkAccess chunk, GenerationStep.Carving step) {
        }

        @Override
        public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState random, ChunkAccess chunk) {
        }

        @Override
        public void spawnOriginalMobs(WorldGenRegion region) {
        }

        @Override
        public int getGenDepth() {
            return 256;
        }

        @Override
        public int getSeaLevel() {
            return 63;
        }

        @Override
        public int getMinY() {
            return 0;
        }

        @Override
        public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
            return groundLevel;
        }

        @Override
        public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
            BlockState[] column = new BlockState[256];
            for (int y = 0; y < column.length; y++) {
                column[y] = y < groundLevel ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            }
            return new NoiseColumn(0, column);
        }

        @Override
        public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
            info.add("Retrofit legacy dimension " + dimension);
        }
    }

    /** A chunk being generated, for the mod's generator's world reads: only that chunk, as made so far. */
    static final class ChunkView implements HostWorld {
        private final ChunkAccess chunk;
        private final LegacyBlockTable table = LegacyBlockTable.get();
        private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();

        ChunkView(ChunkAccess chunk) {
            this.chunk = chunk;
        }

        private BlockState state(int x, int y, int z) {
            ChunkPos pos = chunk.getPos();
            if (x >> 4 != pos.x || z >> 4 != pos.z || y < 0 || y > 255) {
                return Blocks.AIR.defaultBlockState();
            }
            return chunk.getBlockState(at.set(x, y, z));
        }

        @Override
        public Object getLegacyBlock(int x, int y, int z) {
            BlockState state = state(x, y, z);
            return state.getBlock() instanceof LegacyBlock block ? block.legacyBlock() : table.legacyBlock(table.legacyOf(state) >> 4);
        }

        @Override
        public int getLegacyMeta(int x, int y, int z) {
            BlockState state = state(x, y, z);
            return state.getBlock() instanceof LegacyBlock ? state.getValue(LegacyContent.META) : table.legacyOf(state) & 15;
        }

        @Override
        public boolean setLegacyBlock(int x, int y, int z, Object legacyBlock, int meta, int flags) {
            ChunkPos pos = chunk.getPos();
            BlockState target = LegacyBlockTable.modernState(legacyBlock, meta);
            if (target == null || x >> 4 != pos.x || z >> 4 != pos.z || y < 0 || y > 255) {
                return false;
            }
            chunk.setBlockState(at.set(x, y, z), target, false);
            return true;
        }

        @Override
        public Object getLegacyTileEntity(int x, int y, int z) {
            return null;
        }

        @Override
        public void markBlockForUpdate(int x, int y, int z) {
        }

        @Override
        public void markTileEntityChanged(int x, int y, int z) {
        }

        @Override
        public long getTotalWorldTime() {
            return 0;
        }

        @Override
        public boolean isClientSide() {
            return false;
        }
    }
}
