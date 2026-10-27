package retrofit.forge.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;
import net.minecraftforge.registries.RegisterEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Mods' enchantments as 1.20.1 enchantments ("retrofit:<name>"), so enchanted legacy gear shows
 * them, and enchanting tables, anvils and books handle them. What an enchantment does stays the
 * mod's own code: legacy items carry their enchantments to legacy code in 1.7.10's form (see
 * LegacyStacks).
 */
public final class LegacyEnchantments {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String RETROFIT = retrofit.forge.RetrofitMod.MOD_ID;
    private static final Map<Integer, String> MODERN = new HashMap<>();
    private static final Map<String, Integer> LEGACY = new HashMap<>();

    private LegacyEnchantments() {
    }

    static String modernId(int legacyId) {
        return MODERN.get(legacyId);
    }

    static int legacyId(String modernId) {
        Integer id = LEGACY.get(modernId);
        return id == null ? -1 : id;
    }

    private static EnchantmentCategory category(String legacyType) {
        return switch (legacyType) {
            case "armor" -> EnchantmentCategory.ARMOR;
            case "armor_feet" -> EnchantmentCategory.ARMOR_FEET;
            case "armor_legs" -> EnchantmentCategory.ARMOR_LEGS;
            case "armor_torso" -> EnchantmentCategory.ARMOR_CHEST;
            case "armor_head" -> EnchantmentCategory.ARMOR_HEAD;
            case "weapon" -> EnchantmentCategory.WEAPON;
            case "digger" -> EnchantmentCategory.DIGGER;
            case "fishing_rod" -> EnchantmentCategory.FISHING_ROD;
            case "bow" -> EnchantmentCategory.BOW;
            default -> EnchantmentCategory.BREAKABLE;
        };
    }

    private static Enchantment.Rarity rarity(int weight) {
        return weight >= 10 ? Enchantment.Rarity.COMMON : weight >= 5 ? Enchantment.Rarity.UNCOMMON : weight >= 2 ? Enchantment.Rarity.RARE : Enchantment.Rarity.VERY_RARE;
    }

    public static void register(RegisterEvent event) {
        event.register(Registries.ENCHANTMENT, helper -> {
            Object[][] enchantments;
            try {
                enchantments = LegacyBridge.call("legacyEnchantments");
            } catch (RuntimeException e) {
                LOGGER.error("Could not list the legacy enchantments", e);
                return;
            }
            // in an order that doesn't depend on the ids configs give them: names must be the same on every
            // client and server (Forge refuses a connection over a registry difference)
            java.util.Arrays.sort(enchantments, java.util.Comparator.comparing((Object[] row) -> (String) row[1]).thenComparing(row -> (String) row[5]));
            for (Object[] row : enchantments) {
                int legacyId = (Integer) row[0];
                String key = (String) row[1];
                String path = key.toLowerCase(Locale.ROOT).replaceFirst("^enchantment\\.", "").replaceAll("[^a-z0-9_./-]", "_");
                ResourceLocation base = new ResourceLocation(RETROFIT, path.isEmpty()
                        ? "enchantment_" + ((String) row[5]).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_") : path);
                ResourceLocation id = base;
                // a second enchantment with the same name key: a number in order (of name, then class), not its legacy
                // id (which depends on the mods installed and their configs: a clashing one is moved, see LegacyIds)
                for (int n = 2; LEGACY.containsKey(id.toString()); n++) {
                    id = new ResourceLocation(RETROFIT, base.getPath() + "_" + n);
                }
                helper.register(id, new Legacy(rarity((Integer) row[2]), category((String) row[4]), Math.max(1, (Integer) row[3]), key));
                MODERN.put(legacyId, id.toString());
                LEGACY.put(id.toString(), legacyId);
            }
            LOGGER.info("Registered {} legacy enchantments", enchantments.length);
        });
    }

    private static final class Legacy extends Enchantment {
        private final int maxLevel;
        private final String key;

        Legacy(Rarity rarity, EnchantmentCategory category, int maxLevel, String key) {
            super(rarity, category, EquipmentSlot.values());
            this.maxLevel = maxLevel;
            this.key = key;
        }

        @Override
        public int getMaxLevel() {
            return maxLevel;
        }

        @Override
        public String getDescriptionId() {
            return key; // the legacy translation ("enchantment.name"), from the mod's .lang
        }
    }
}
