package retrofit.forge.world;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Attributes 1.20.1 gives gear that 1.7.10 had no use for (asked for by players): swords and tools attack at
 * 1.20.1's speed for their kind, and armor gets toughness from its armor points, as diamond's does. Players set
 * them per item, or turn them off, in retrofit/item-attributes.properties (written with its explanation at the
 * first start).
 */
final class LegacyGearAttributes {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE = "item-attributes.properties";
    /** 1.20.1's attacks per second without a modifier. */
    private static final double BASE_ATTACK_SPEED = 4;
    /** Each armor slot's share of a vanilla set's armor points (diamond: 3, 8, 6, 3), by ArmorItem.Type ordinal. */
    private static final double[] SLOT_SHARE = {0.15, 0.4, 0.3, 0.15};

    private static Settings settings;

    private LegacyGearAttributes() {
    }

    /** The attack speed modifier (added to 1.20.1's 4 attacks per second) of a weapon of this kind, or NaN for none. */
    static double attackSpeed(Item item, String kind) {
        Settings settings = settings();
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        String set = id == null ? null : settings.values.getProperty(id + ".attackSpeed");
        if (set != null) {
            return parse(set, id + ".attackSpeed", Double.NaN) - BASE_ATTACK_SPEED;
        }
        if (!settings.generates(id)) {
            return Double.NaN;
        }
        return switch (kind) { // 1.20.1's own (diamond tools)
            case "sword" -> -2.4;
            case "pickaxe" -> -2.8;
            case "axe", "shovel" -> -3.0;
            default -> Double.NaN; // other weapons attacked without cooldown in 1.7.10: kept so
        };
    }

    /**
     * Armor toughness for a piece with these armor points in this slot (ArmorItem.Type ordinal), worked out from its
     * set's armor points (0 if unknown: judged by the piece): a set of 20 points (diamond) has 2 per piece, 15 (iron)
     * and less none.
     */
    static float toughness(Item item, int slot, int armor, int setArmor) {
        Settings settings = settings();
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        String set = id == null ? null : settings.values.getProperty(id + ".toughness");
        if (set != null) {
            return (float) parse(set, id + ".toughness", 0);
        }
        if (!settings.generates(id)) {
            return 0;
        }
        double points = setArmor > 0 ? setArmor : armor / SLOT_SHARE[Math.max(0, Math.min(3, slot))];
        return (float) Math.min(20, Math.max(0, Math.floor((points - 15) / 2.5)));
    }

    private static double parse(String value, String key, double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("retrofit/{}: {} is not a number ({}); ignored", FILE, key, value);
            return fallback;
        }
    }

    private static synchronized Settings settings() {
        if (settings == null) {
            settings = load(FMLPaths.GAMEDIR.get().resolve("retrofit").resolve(FILE));
        }
        return settings;
    }

    private static Settings load(Path file) {
        Properties values = new Properties();
        try {
            if (!Files.isRegularFile(file)) {
                writeDefaults(file);
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                values.load(reader);
            }
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.warn("Could not read retrofit/{}; 1.7.10 gear gets the attributes worked out for it", FILE, e);
        }
        boolean all = !values.getProperty("generate", "all").trim().equalsIgnoreCase("listed");
        return new Settings(values, all, list(values.getProperty("skip", "")), list(values.getProperty("only", "")));
    }

    private static Set<String> list(String value) {
        return Arrays.stream(value.split(",")).map(entry -> entry.trim().toLowerCase(Locale.ROOT))
                .filter(entry -> !entry.isEmpty()).collect(Collectors.toSet());
    }

    private static void writeDefaults(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("""
                    # Attributes 1.20.1 gives weapons and armor that 1.7.10 did not have, for 1.7.10 mods' gear:
                    #   attack speed - swords 1.6 attacks per second, pickaxes 1.2, axes and shovels 1.0 (1.20.1's own);
                    #                  other 1.7.10 weapons keep attacking without a cooldown, as in 1.7.10
                    #   toughness    - armor with more points than iron gets some, as diamond does (a diamond-like set: 2)
                    #
                    # generate: all (every 1.7.10 sword, tool and armor piece, except those under skip)
                    #           listed (only those under only)
                    generate=all
                    # Comma-separated item ids (as /give names them); modid:* for all of a mod's items.
                    skip=
                    only=
                    #
                    # Values set by hand for one item, any item (they apply whatever generate, skip and only say), e.g.:
                    # manametalmod:itemswordsteel.attackSpeed=1.6
                    # manametalmod:itemarmorsteel_helmet.toughness=2
                    """);
        }
    }

    private record Settings(Properties values, boolean all, Set<String> skip, Set<String> only) {
        boolean generates(ResourceLocation id) {
            if (id == null) {
                return all;
            }
            return all ? !listed(skip, id) : listed(only, id);
        }

        private static boolean listed(Set<String> list, ResourceLocation id) {
            return list.contains(id.toString()) || list.contains(id.getNamespace() + ":*");
        }
    }
}
