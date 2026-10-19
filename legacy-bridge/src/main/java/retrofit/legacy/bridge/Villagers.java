package retrofit.legacy.bridge;

import com.google.common.collect.Multimap;
import cpw.mods.fml.common.registry.VillagerRegistry;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.item.ItemStack;
import net.minecraft.village.MerchantRecipe;
import net.minecraft.village.MerchantRecipeList;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Trades legacy mods give villagers (VillagerRegistry trade handlers), for 1.20.1 villagers: the
 * host asks for a profession's trades when its villagers pick new offers.
 */
public final class Villagers {
    private Villagers() {
    }

    /** The professions that have legacy trade handlers (vanilla's 0 to 4, and mods' own ids). */
    @SuppressWarnings("unchecked")
    public static int[] tradeProfessions() throws ReflectiveOperationException {
        Field field = VillagerRegistry.class.getDeclaredField("tradeHandlers");
        field.setAccessible(true);
        Multimap<Integer, ?> handlers = (Multimap<Integer, ?>) field.get(VillagerRegistry.instance());
        int[] professions = new int[handlers.keySet().size()];
        int n = 0;
        for (Integer profession : handlers.keySet()) {
            professions[n++] = profession;
        }
        return professions;
    }

    /**
     * The trades the handlers make for a new villager of a profession: rows {first price, second
     * price or null, result, max uses} as legacy stacks.
     */
    public static Object[][] villagerTrades(Object legacyWorld, int profession, long seed) {
        Random random = new Random(seed);
        MerchantRecipeList list = new MerchantRecipeList();
        EntityVillager villager = new EntityVillager((World) legacyWorld, profession);
        VillagerRegistry.manageVillagerTrades(list, villager, profession, random);
        List<Object[]> rows = new ArrayList<Object[]>();
        for (Object entry : (List<?>) list) { // ArrayList's iterator (MerchantRecipeList has none of its own)
            MerchantRecipe recipe = (MerchantRecipe) entry;
            ItemStack buy = recipe.func_77394_a(); // getItemToBuy
            ItemStack sell = recipe.func_77397_d(); // getItemToSell
            if (buy == null || sell == null || buy.func_77973_b() == null || sell.func_77973_b() == null) {
                continue;
            }
            int maxUses = recipe.func_77395_g().func_74762_e("maxUses"); // writeToTags, getInteger
            rows.add(new Object[]{buy, recipe.func_77396_b(), sell, maxUses > 0 ? maxUses : 7}); // getSecondItemToBuy
        }
        return rows.toArray(new Object[0][]);
    }
}
