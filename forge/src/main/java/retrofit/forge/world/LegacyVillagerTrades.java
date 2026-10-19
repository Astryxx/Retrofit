package retrofit.forge.world;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraftforge.event.village.VillagerTradesEvent;
import net.minecraftforge.event.village.WandererTradesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import retrofit.forge.RetrofitMod;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Villager trades of legacy mods (VillagerRegistry trade handlers) for 1.20.1 villagers. 1.7.10
 * had five professions; each 1.20.1 profession gets the trades of the one it grew out of
 * (blacksmiths' go to armorers, weaponsmiths and toolsmiths), among its offers of the first three
 * levels. Mods' own professions have no 1.20.1 villagers (no job sites), so wandering traders
 * carry their trades.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID)
public final class LegacyVillagerTrades {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<VillagerProfession, Integer> LEGACY = Map.ofEntries(
            Map.entry(VillagerProfession.FARMER, 0), Map.entry(VillagerProfession.FISHERMAN, 0),
            Map.entry(VillagerProfession.SHEPHERD, 0), Map.entry(VillagerProfession.FLETCHER, 0),
            Map.entry(VillagerProfession.LIBRARIAN, 1), Map.entry(VillagerProfession.CARTOGRAPHER, 1),
            Map.entry(VillagerProfession.CLERIC, 2),
            Map.entry(VillagerProfession.ARMORER, 3), Map.entry(VillagerProfession.WEAPONSMITH, 3),
            Map.entry(VillagerProfession.TOOLSMITH, 3),
            Map.entry(VillagerProfession.BUTCHER, 4), Map.entry(VillagerProfession.LEATHERWORKER, 4));
    private static Set<Integer> professions;

    private LegacyVillagerTrades() {
    }

    private static synchronized Set<Integer> professions() {
        if (professions == null) {
            professions = new HashSet<>();
            if (RetrofitMod.runtime() != null) {
                try {
                    for (int profession : LegacyBridge.<int[]>call("tradeProfessions")) {
                        professions.add(profession);
                    }
                } catch (RuntimeException e) {
                    LOGGER.error("Could not read the legacy villager trades", e);
                }
                LOGGER.info("Legacy villager trades for professions {}", professions);
            }
        }
        return professions;
    }

    @SubscribeEvent
    public static void onVillagerTrades(VillagerTradesEvent event) {
        Integer legacy = LEGACY.get(event.getType());
        if (legacy != null && professions().contains(legacy)) {
            for (int level = 1; level <= 3; level++) {
                event.getTrades().get(level).add(new Listing(legacy, level));
            }
        }
    }

    @SubscribeEvent
    public static void onWandererTrades(WandererTradesEvent event) {
        for (int profession : professions()) {
            if (profession > 4) { // among ~70 common offers, and the dozen rare ones (a trader shows one of those)
                event.getGenericTrades().add(new Listing(profession, 1));
                event.getRareTrades().add(new Listing(profession, 2));
            }
        }
    }

    /** One offer, picked from the trades the legacy handlers make for a villager of the profession. */
    private record Listing(int profession, int level) implements VillagerTrades.ItemListing {
        @Override
        public MerchantOffer getOffer(Entity trader, RandomSource random) {
            if (!(trader.level() instanceof ServerLevel world)) {
                return null;
            }
            try {
                Object[][] rows = LegacyBridge.call("villagerTrades", LegacyContent.legacyWorld(world), profession, random.nextLong());
                if (rows.length == 0) {
                    return null;
                }
                Object[] row = rows[random.nextInt(rows.length)];
                ItemStack price = LegacyStacks.toModern(row[0]);
                ItemStack second = row[1] == null ? ItemStack.EMPTY : LegacyStacks.toModern(row[1]);
                ItemStack result = LegacyStacks.toModern(row[2]);
                if (price.isEmpty() || result.isEmpty()) {
                    return null;
                }
                return new MerchantOffer(price, second, result, (Integer) row[3], 2 + 3 * level, 0.05f);
            } catch (RuntimeException e) {
                LOGGER.warn("Legacy villager trades for profession {} failed", profession, e);
                return null;
            }
        }
    }
}
