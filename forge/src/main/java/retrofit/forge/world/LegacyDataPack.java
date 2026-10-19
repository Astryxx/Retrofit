package retrofit.forge.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import retrofit.forge.RetrofitMod;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A built-in, always-enabled data pack generated from the legacy mods: what 1.20.1 keeps in data
 * rather than code.
 *
 * - Block tags for harvest tools: 1.7.10 blocks named their tool and level in code
 *   (setHarvestLevel), 1.20.1 reads mineable/* and needs_*_tool tags.
 * - Recipes: see {@link LegacyRecipes}.
 * - World generation: see {@link LegacyWorldGen}.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class LegacyDataPack implements PackResources {
    private static final int PACK_FORMAT = 15;
    private static final String[] LEVEL_TAGS = {null, "needs_stone_tool", "needs_iron_tool", "needs_diamond_tool"};

    private final String id;
    /** namespace -> (path -> contents) */
    private final Map<String, Map<String, byte[]>> files = new HashMap<>();

    private LegacyDataPack(String id) {
        this.id = id;
        build();
    }

    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA || RetrofitMod.runtime() == null) {
            return;
        }
        event.addRepositorySource(packs -> {
            Pack pack = Pack.readMetaAndCreate("retrofit_legacy_data", Component.literal("Retrofit: 1.7.10 mod data"), true,
                    LegacyDataPack::new, PackType.SERVER_DATA, Pack.Position.TOP, PackSource.BUILT_IN);
            if (pack != null) {
                packs.accept(pack);
            }
        });
    }

    private void build() {
        Map<String, JsonArray> tags = new TreeMap<>();
        for (LegacyBlock block : LegacyContent.blocks()) {
            String tool = block.harvestTool();
            if (tool == null) {
                continue;
            }
            String name = ForgeRegistries.BLOCKS.getKey(block).toString();
            String mineable = switch (tool) {
                case "pickaxe", "axe", "shovel", "hoe" -> "mineable/" + tool;
                default -> null; // tool classes 1.20.1 has no tag for (e.g. "sword", mod tools)
            };
            if (mineable != null) {
                tags.computeIfAbsent(mineable, t -> new JsonArray()).add(name);
            }
            int level = Math.min(3, block.harvestLevel());
            if (level > 0) {
                tags.computeIfAbsent(LEVEL_TAGS[level], t -> new JsonArray()).add(name);
            }
        }
        tags.forEach((tag, values) -> {
            JsonObject json = new JsonObject();
            json.addProperty("replace", false);
            json.add("values", values);
            put("minecraft", "tags/blocks/" + tag + ".json", json.toString());
        });
        // each part on its own: one failing must not take the others (dimensions, recipes) with it
        section("recipes", LegacyRecipes::addTo);
        section("Ore Dictionary tags", LegacyOreDictionary::addTo);
        section("achievements", LegacyAchievements::addTo);
        section("world generation", LegacyWorldGen::addTo);
        section("entity types", LegacyEntityTypes::addTo);
        section("dimensions", LegacyDimensions::addTo);
    }

    private void section(String what, java.util.function.Consumer<LegacyDataPack> part) {
        try {
            part.accept(this);
        } catch (RuntimeException e) {
            LegacyErrors.report("data pack", "making the legacy " + what, e);
        }
    }

    void put(String namespace, String path, String contents) {
        files.computeIfAbsent(namespace, ns -> new HashMap<>()).put(path, contents.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... elements) {
        return null;
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
        Map<String, byte[]> namespace = type == PackType.SERVER_DATA ? files.get(location.getNamespace()) : null;
        byte[] contents = namespace == null ? null : namespace.get(location.getPath());
        return contents == null ? null : () -> new ByteArrayInputStream(contents);
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        Map<String, byte[]> entries = type == PackType.SERVER_DATA ? files.get(namespace) : null;
        if (entries == null) {
            return;
        }
        String prefix = path.endsWith("/") ? path : path + "/";
        entries.forEach((file, contents) -> {
            if (file.startsWith(prefix)) {
                output.accept(new ResourceLocation(namespace, file), () -> new ByteArrayInputStream(contents));
            }
        });
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == PackType.SERVER_DATA ? files.keySet() : Set.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) {
        if (serializer == PackMetadataSection.TYPE) {
            return (T) new PackMetadataSection(Component.literal("Data of your 1.7.10 mods"), PACK_FORMAT);
        }
        return null;
    }

    @Override
    public String packId() {
        return id;
    }

    @Override
    public boolean isBuiltin() {
        return true;
    }

    @Override
    public void close() {
    }

    /** For reports. */
    List<String> paths() {
        return files.values().stream().flatMap(map -> map.keySet().stream()).sorted().toList();
    }
}
