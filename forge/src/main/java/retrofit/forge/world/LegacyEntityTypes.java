package retrofit.forge.world;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.common.world.BiomeModifier;
import net.minecraftforge.common.world.ModifiableBiomeInfo;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.entity.SpawnPlacementRegisterEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import org.slf4j.Logger;
import retrofit.forge.RetrofitMod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One 1.20.1 entity type per legacy mod entity type (EntityRegistry.registerModEntity and
 * registerGlobalEntityID), "modid.Name" becoming "modid:name": {@link LegacyMob} for living ones,
 * {@link LegacyThing} for the rest. Types with a legacy spawn egg get a 1.20.1 spawn egg in the
 * same colours. Natural spawning follows the legacy biome spawn lists (EntityRegistry.addSpawn),
 * with the legacy entity's own getCanSpawnHere deciding each spot.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class LegacyEntityTypes {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final ResourceLocation SPAWNS = new ResourceLocation(RetrofitMod.MOD_ID, "legacy_spawns");
    private static final MobCategory[] LEGACY_CATEGORIES = {MobCategory.MONSTER, MobCategory.CREATURE, MobCategory.AMBIENT, MobCategory.WATER_CREATURE};

    /** legacy name -> type */
    private static final Map<String, EntityType<?>> TYPES = new LinkedHashMap<>();
    private static final Map<EntityType<?>, String> NAMES = new LinkedHashMap<>();
    private static final List<EntityType<? extends LegacyMob>> LIVING = new ArrayList<>();
    private static final List<Item> EGGS = new ArrayList<>();
    private static final List<Object[]> INFO = new ArrayList<>();
    private static boolean built;

    private LegacyEntityTypes() {
    }

    public static EntityType<?> byName(String legacyName) {
        return TYPES.get(legacyName);
    }

    public static Map<String, EntityType<?>> all() {
        return java.util.Collections.unmodifiableMap(TYPES);
    }

    public static String legacyName(EntityType<?> type) {
        return NAMES.get(type);
    }

    /** 1.20.1 vanilla ids whose 1.7.10 names differ from the capitalised path. */
    private static final Map<String, String> VANILLA_LEGACY_NAMES = Map.ofEntries(
            Map.entry("minecraft:zombified_piglin", "PigZombie"), Map.entry("minecraft:cave_spider", "CaveSpider"),
            Map.entry("minecraft:magma_cube", "LavaSlime"), Map.entry("minecraft:snow_golem", "SnowMan"),
            Map.entry("minecraft:iron_golem", "VillagerGolem"), Map.entry("minecraft:mooshroom", "MushroomCow"),
            Map.entry("minecraft:ocelot", "Ozelot"), Map.entry("minecraft:horse", "EntityHorse"),
            Map.entry("minecraft:wither", "WitherBoss"), Map.entry("minecraft:ender_dragon", "EnderDragon"),
            Map.entry("minecraft:wither_skeleton", "Skeleton"), Map.entry("minecraft:stray", "Skeleton"),
            Map.entry("minecraft:husk", "Zombie"), Map.entry("minecraft:drowned", "Zombie"));

    /** The 1.7.10 EntityList name for a 1.20.1 entity id (legacy mods' entities, vanilla ones), or "Pig" if it has none. */
    static String legacyNameOf(String modernId) {
        EntityType<?> type = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse(modernId));
        String legacy = type == null ? null : NAMES.get(type);
        if (legacy != null) {
            return legacy;
        }
        String vanilla = VANILLA_LEGACY_NAMES.get(modernId);
        if (vanilla != null) {
            return vanilla;
        }
        if (modernId.startsWith("minecraft:")) {
            StringBuilder name = new StringBuilder();
            for (String part : modernId.substring(10).split("_")) {
                if (!part.isEmpty()) {
                    name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
                }
            }
            return name.toString();
        }
        return "Pig"; // a 1.20.1 mod's mob: legacy code knows no name for it
    }

    private static final Map<String, ResourceLocation> IDS = new LinkedHashMap<>();

    /** "modid.Name" (or a global name registered by a mod) -> "modid:name". */
    static ResourceLocation idOf(String legacyName) {
        return IDS.get(legacyName);
    }

    private static ResourceLocation makeId(String legacyName, String modId) {
        String path = legacyName.startsWith(modId + ".") ? legacyName.substring(modId.length() + 1) : legacyName;
        return LegacyContent.toResourceLocation(modId.toLowerCase(java.util.Locale.ROOT) + ":" + path.replace(':', '_').replace('.', '_'));
    }

    /** Built at item registration (spawn eggs need the types), registered with the entity types. */
    private static synchronized void build() {
        if (built) {
            return;
        }
        built = true;
        Object[][] entities;
        try {
            entities = LegacyBridge.call("modEntities");
        } catch (RuntimeException e) {
            LOGGER.error("Could not list legacy entities", e);
            return;
        }
        for (Object[] entity : entities) {
            String name = (String) entity[0];
            if (TYPES.containsKey(name)) {
                // Registered twice under one name (1.7.10 kept the last); one type per name, or one would go unregistered.
                LOGGER.info("Legacy entity {} is registered more than once; keeping the first", name);
                continue;
            }
            boolean living = (Integer) entity[1] != 0;
            int category = (Integer) entity[2];
            float width = (Float) entity[3];
            float height = (Float) entity[4];
            ResourceLocation id = makeId(name, (String) entity[7]);
            if (id.getNamespace().equals("minecraft")) {
                // Registered under a vanilla name (a mod replacing a vanilla entity); legacy code spawning it gets the vanilla one.
                LOGGER.info("Legacy entity {} has a vanilla name; it is not added as a new entity type", name);
                continue;
            }
            if (IDS.containsValue(id)) {
                int n = 2;
                while (IDS.containsValue(new ResourceLocation(id.getNamespace(), id.getPath() + "_" + n))) {
                    n++;
                }
                id = new ResourceLocation(id.getNamespace(), id.getPath() + "_" + n); // two legacy names that clean up to the same id
            }
            IDS.put(name, id);
            MobCategory mobCategory = switch (category) {
                case 0 -> MobCategory.MONSTER;
                case 1 -> MobCategory.CREATURE;
                case 2 -> MobCategory.WATER_CREATURE;
                case 3 -> MobCategory.AMBIENT;
                default -> MobCategory.MISC;
            };
            EntityType<?> type;
            if (living) {
                boolean monster = category == 0;
                EntityType<LegacyMob> mob = EntityType.Builder.<LegacyMob>of((t, level) -> monster
                                ? new LegacyMonster(t, level, name, width, height) : new LegacyMob(t, level, name, width, height), mobCategory)
                        .sized(Math.max(0.1f, width), Math.max(0.1f, height)).clientTrackingRange(10).build(id.toString());
                LIVING.add(mob);
                type = mob;
            } else {
                type = EntityType.Builder.<LegacyThing>of((t, level) -> new LegacyThing(t, level, name, width, height), MobCategory.MISC)
                        .sized(Math.max(0.1f, width), Math.max(0.1f, height)).clientTrackingRange(8).updateInterval(1).build(id.toString());
            }
            TYPES.put(name, type);
            NAMES.put(type, name);
            INFO.add(entity);
        }
        LOGGER.info("Legacy entity types: {} ({} living)", TYPES.size(), LIVING.size());
    }

    @SubscribeEvent
    public static void onRegister(RegisterEvent event) {
        if (RetrofitMod.runtime() == null) {
            return;
        }
        event.register(Registries.ITEM, helper -> {
            build();
            for (Object[] entity : INFO) {
                int background = (Integer) entity[5];
                int foreground = (Integer) entity[6];
                EntityType<?> type = TYPES.get((String) entity[0]);
                if (background >= 0 && type != null && LIVING.contains(type)) {
                    @SuppressWarnings("unchecked")
                    EntityType<? extends Mob> mob = (EntityType<? extends Mob>) type;
                    ResourceLocation id = idOf((String) entity[0]);
                    Item egg = new ForgeSpawnEggItem(() -> mob, background, foreground, new Item.Properties());
                    helper.register(new ResourceLocation(id.getNamespace(), id.getPath() + "_spawn_egg"), egg);
                    EGGS.add(egg);
                }
            }
        });
        event.register(Registries.ENTITY_TYPE, helper -> {
            build();
            TYPES.forEach((name, type) -> helper.register(idOf(name), type));
        });
        event.register(ForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, helper -> helper.register(SPAWNS, Spawns.CODEC));
    }

    @SubscribeEvent
    public static void onAttributes(EntityAttributeCreationEvent event) {
        for (EntityType<? extends LegacyMob> type : LIVING) {
            event.put(type, Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 20).build());
        }
    }

    @SubscribeEvent
    public static void onSpawnPlacements(SpawnPlacementRegisterEvent event) {
        for (EntityType<? extends LegacyMob> type : LIVING) {
            String name = NAMES.get(type);
            SpawnPlacements.Type placement = type.getCategory() == MobCategory.WATER_CREATURE ? SpawnPlacements.Type.IN_WATER : SpawnPlacements.Type.ON_GROUND;
            event.register(type, placement, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (t, level, reason, pos, random) -> {
                try {
                    if (level instanceof net.minecraft.world.level.WorldGenLevel region && !(level instanceof net.minecraft.server.level.ServerLevel)) {
                        // Mobs placed while a chunk generates (off the server thread): check against that region.
                        int dimension = LegacyContent.legacyDimension(region.getLevel());
                        RegionHostWorld host = new RegionHostWorld(region, LegacyBridge.call("genWorld", dimension), new net.minecraft.world.level.ChunkPos(pos));
                        return LegacyBridge.call("canSpawnInRegion", host, dimension, name, pos.getX() + 0.5, (double) pos.getY(), pos.getZ() + 0.5);
                    }
                    return LegacyBridge.call("entityCanSpawnHere", name, LegacyContent.legacyWorld(level.getLevel()), pos.getX() + 0.5, (double) pos.getY(), pos.getZ() + 0.5);
                } catch (RuntimeException e) {
                    LegacyErrors.report("entity " + name, "checking a spawn spot", e);
                    return false;
                }
            }, SpawnPlacementRegisterEvent.Operation.REPLACE);
        }
    }

    @SubscribeEvent
    public static void onCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            EGGS.forEach(event::accept);
        }
    }

    public static List<Item> eggs() {
        return java.util.Collections.unmodifiableList(EGGS);
    }

    /** Data pack entry for the natural spawns (see {@link Spawns}), if any legacy entity can spawn. */
    static void addTo(LegacyDataPack pack) {
        if (LIVING.isEmpty()) {
            return;
        }
        com.google.gson.JsonObject modifier = new com.google.gson.JsonObject();
        modifier.addProperty("type", SPAWNS.toString());
        pack.put(SPAWNS.getNamespace(), "forge/biome_modifier/" + SPAWNS.getPath() + ".json", modifier.toString());
    }

    /** Adds the legacy biome spawn lists to the matching 1.20.1 biomes. */
    record Spawns() implements BiomeModifier {
        static final Codec<Spawns> CODEC = Codec.unit(Spawns::new);

        @Override
        public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
            if (phase != Phase.ADD) {
                return;
            }
            String id = biome.unwrapKey().map(key -> key.location().toString()).orElse(null);
            if (id == null) {
                return;
            }
            try {
                Object[][] spawns = LegacyBridge.call("entitySpawns", id);
                for (Object[] spawn : spawns) {
                    EntityType<?> type = TYPES.get((String) spawn[0]);
                    int category = (Integer) spawn[1];
                    if (type == null || !LIVING.contains(type) || category < 0 || category >= LEGACY_CATEGORIES.length) {
                        continue;
                    }
                    builder.getMobSpawnSettings().addSpawn(LEGACY_CATEGORIES[category],
                            new MobSpawnSettings.SpawnerData(type, (Integer) spawn[2], (Integer) spawn[3], Math.max((Integer) spawn[3], (Integer) spawn[4])));
                }
            } catch (RuntimeException e) {
                LegacyErrors.report("biome " + id, "listing legacy spawns", e);
            }
        }

        @Override
        public Codec<? extends BiomeModifier> codec() {
            return CODEC;
        }
    }
}
