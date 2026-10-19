package retrofit.forge.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import retrofit.forge.RetrofitMod;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The Ore Dictionary and 1.20.1 item tags, both ways, so materials are shared across versions:
 * legacy copper ingots work in 1.20.1 recipes that ask for #forge:ingots/copper, and 1.20.1 copper
 * ingots in legacy recipes that ask for "ingotCopper".
 *
 * - Legacy ore names become item tags in Retrofit's data pack ("ingotCopper" -> forge:ingots/copper).
 * - Items in 1.20.1 tags with no 1.7.10 form join the Ore Dictionary under the matching name
 *   (as the carriers of ModernItems), whenever tags load.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID)
public final class LegacyOreDictionary {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Ore name prefix -> tag folder in the forge namespace; longest prefixes first. */
    private static final Map<String, String> PREFIXES = new LinkedHashMap<>();
    /** Whole ore names with a tag of their own. */
    private static final Map<String, String> NAMES = new HashMap<>();
    private static final Map<String, String> TAG_TO_NAME = new HashMap<>();

    static {
        String[][] prefixes = {
                {"dustSmall", null}, {"dustTiny", null}, {"ingot", "ingots"}, {"nugget", "nuggets"}, {"dust", "dusts"},
                {"gem", "gems"}, {"ore", "ores"}, {"block", "storage_blocks"}, {"plate", "plates"}, {"gear", "gears"},
                {"rod", "rods"}, {"wire", "wires"}, {"crop", "crops"}, {"seed", "seeds"}, {"dye", "dyes"},
        };
        for (String[] prefix : prefixes) {
            PREFIXES.put(prefix[0], prefix[1]);
        }
        String[][] names = {
                {"logWood", "minecraft:logs"}, {"plankWood", "minecraft:planks"}, {"slabWood", "minecraft:wooden_slabs"},
                {"stairWood", "minecraft:wooden_stairs"}, {"treeSapling", "minecraft:saplings"}, {"treeLeaves", "minecraft:leaves"},
                {"stickWood", "forge:rods/wooden"}, {"blockGlass", "forge:glass"}, {"paneGlass", "forge:glass_panes"},
                {"stone", "forge:stone"}, {"cobblestone", "forge:cobblestone"}, {"sand", "forge:sand"}, {"gravel", "forge:gravel"},
                {"chestWood", "forge:chests/wooden"}, {"record", "minecraft:music_discs"}, {"slimeball", "forge:slimeballs"},
                {"string", "forge:string"}, {"feather", "forge:feathers"}, {"leather", "forge:leather"}, {"bone", "forge:bones"},
                {"enderpearl", "forge:ender_pearls"}, {"gunpowder", "forge:gunpowder"}, {"egg", "forge:eggs"},
                {"blockWool", "minecraft:wool"}, {"obsidian", "forge:obsidian"}, {"netherrack", "forge:netherrack"},
        };
        for (String[] name : names) {
            NAMES.put(name[0], name[1]);
            TAG_TO_NAME.put(name[1], name[0]);
        }
    }

    private LegacyOreDictionary() {
    }

    private static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static String camel(String snake) {
        StringBuilder result = new StringBuilder();
        for (String part : snake.split("_")) {
            if (!part.isEmpty()) {
                result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return result.toString();
    }

    /** The tag for an ore name ("ingotCopper" -> "forge:ingots/copper"), or null. */
    static String tagFor(String oreName) {
        String named = NAMES.get(oreName);
        if (named != null) {
            return named;
        }
        for (Map.Entry<String, String> prefix : PREFIXES.entrySet()) {
            String p = prefix.getKey();
            if (oreName.length() > p.length() && oreName.startsWith(p) && Character.isUpperCase(oreName.charAt(p.length()))) {
                return prefix.getValue() == null ? null : "forge:" + prefix.getValue() + "/" + snake(oreName.substring(p.length()));
            }
        }
        return null;
    }

    /** The ore name for a tag ("forge:ingots/copper" -> "ingotCopper"), or null. */
    static String oreFor(ResourceLocation tag) {
        String named = TAG_TO_NAME.get(tag.toString());
        if (named != null) {
            return named;
        }
        if (!tag.getNamespace().equals("forge")) {
            return null;
        }
        int slash = tag.getPath().indexOf('/');
        if (slash < 0 || tag.getPath().indexOf('/', slash + 1) >= 0) {
            return null;
        }
        String folder = tag.getPath().substring(0, slash);
        for (Map.Entry<String, String> prefix : PREFIXES.entrySet()) {
            if (folder.equals(prefix.getValue())) {
                return prefix.getKey() + camel(tag.getPath().substring(slash + 1));
            }
        }
        return null;
    }

    /** Legacy ore names as item tags (legacy mods' items only: vanilla ones are tagged by Forge already), and block tags. */
    static void addTo(LegacyDataPack pack) {
        Map<String, JsonArray> tags = new TreeMap<>();
        // tag -> legacy block -> metas (or -1: all) in that ore name: blocks get the tag when all their kinds are in it
        Map<String, Map<LegacyBlock, java.util.Set<Integer>>> blockMetas = new TreeMap<>();
        try {
            for (Object[] entry : LegacyBridge.<Object[][]>call("oreDictionary")) {
                String tag = tagFor((String) entry[0]);
                if (tag == null) {
                    continue;
                }
                for (Object legacyStack : (Object[]) entry[1]) {
                    Object[] read = LegacyBridge.readStack(legacyStack);
                    if (read == null || ((String) read[0]).startsWith("minecraft:") || read[0].equals(ModernItems.LEGACY_NAME)) {
                        continue;
                    }
                    ItemStack modern = LegacyStacks.toModern(legacyStack);
                    if (modern.isEmpty()) {
                        Object item = LegacyBridge.stackItem(legacyStack); // a wildcard: the item itself
                        modern = LegacyStacks.toModern(LegacyBridge.makeStackOf(item, 1, 0, null));
                    }
                    ResourceLocation id = modern.isEmpty() ? null : ForgeRegistries.ITEMS.getKey(modern.getItem());
                    LegacyBlock block = modern.getItem() instanceof LegacyWoodSets.AliasItem alias ? ((LegacyWoodSets.AliasBlock) alias.getBlock()).original()
                            : modern.getItem() instanceof LegacyBlockItem blockItem ? (LegacyBlock) blockItem.getBlock() : null;
                    if (block != null) {
                        int damage = (Integer) read[2];
                        blockMetas.computeIfAbsent(tag, t -> new java.util.HashMap<>()).computeIfAbsent(block, b -> new java.util.HashSet<>())
                                .add(damage == 32767 ? -1 : damage);
                    }
                    if (id != null) {
                        JsonArray values = tags.computeIfAbsent(tag, t -> new JsonArray());
                        if (!values.toString().contains("\"" + id + "\"")) {
                            values.add(id.toString());
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            LOGGER.error("Could not read the Ore Dictionary", e);
            return;
        }
        tags.forEach((tag, values) -> {
            ResourceLocation location = new ResourceLocation(tag);
            JsonObject json = new JsonObject();
            json.addProperty("replace", false);
            json.add("values", values);
            pack.put(location.getNamespace(), "tags/items/" + location.getPath() + ".json", json.toString());
        });
        int[] blockTags = {0};
        // every kind of a block the Ore Dictionary knows (a block's subtypes themselves are only listed on clients)
        Map<LegacyBlock, java.util.Set<Integer>> known = new java.util.HashMap<>();
        blockMetas.values().forEach(blocks -> blocks.forEach((block, metas) -> known.computeIfAbsent(block, b -> new java.util.HashSet<>()).addAll(metas)));
        blockMetas.forEach((tag, blocks) -> {
            JsonArray values = new JsonArray();
            blocks.forEach((block, metas) -> {
                if (metas.contains(-1) || metas.containsAll(known.get(block))) { // every kind of the block is in the ore name: the block is one (logs, ores, planks...)
                    values.add(ForgeRegistries.BLOCKS.getKey(block).toString());
                }
            });
            if (!values.isEmpty()) {
                ResourceLocation location = new ResourceLocation(tag);
                JsonObject json = new JsonObject();
                json.addProperty("replace", false);
                json.add("values", values);
                pack.put(location.getNamespace(), "tags/blocks/" + location.getPath() + ".json", json.toString());
                blockTags[0]++;
            }
        });
        LOGGER.info("Ore Dictionary: {} ore names as item tags, {} as block tags", tags.size(), blockTags[0]);
    }

    /** Tags loaded (server start, /reload): 1.20.1 items join the Ore Dictionary under their tags' names. */
    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD || !LegacyBridge.isReady()) {
            return;
        }
        int[] added = {0};
        try {
            event.getRegistryAccess().registryOrThrow(Registries.ITEM).getTags().forEach(pair -> {
                String ore = oreFor(pair.getFirst().location());
                if (ore == null) {
                    return;
                }
                for (Holder<Item> holder : pair.getSecond()) {
                    Object legacy = LegacyStacks.toLegacy(new ItemStack(holder.value()));
                    if (isCarrier(legacy)) { // a 1.20.1-only item (legacy items and vanilla ones are in the Ore Dictionary already)
                        LegacyBridge.call("registerModernOre", ore, legacy);
                        added[0]++;
                    }
                }
            });
        } catch (RuntimeException e) {
            LOGGER.error("Could not add 1.20.1 items to the Ore Dictionary", e);
        }
        LOGGER.info("Ore Dictionary: {} 1.20.1 item entries from tags", added[0]);
    }

    private static boolean isCarrier(Object legacyStack) {
        Object[] read = legacyStack == null ? null : LegacyBridge.readStack(legacyStack);
        return read != null && read[0].equals(ModernItems.LEGACY_NAME);
    }
}
