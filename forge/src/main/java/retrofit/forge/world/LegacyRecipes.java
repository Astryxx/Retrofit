package retrofit.forge.world;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/**
 * 1.7.10 recipes in 1.20.1.
 *
 * - Crafting: one special recipe ({@link Crafting}, type "retrofit:legacy_crafting") hands the
 *   grid to the legacy recipe list, so every mod recipe works, including mods' own recipe classes.
 *   They don't show in the recipe book.
 * - Smelting: furnace recipes involving mod items become ordinary 1.20.1 smelting recipes in
 *   the data pack, so furnaces, the recipe book and recipe viewers know them.
 * - Fuel: legacy items burn as long as the legacy furnace and fuel handlers say
 *   ({@link #burnTime}).
 */
public final class LegacyRecipes {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int WILDCARD = 32767;

    public static final RecipeSerializer<Crafting> CRAFTING = new SimpleCraftingRecipeSerializer<>(Crafting::new);

    private LegacyRecipes() {
    }

    static void addTo(LegacyDataPack pack) {
        JsonObject crafting = new JsonObject();
        crafting.addProperty("type", "retrofit:legacy_crafting");
        crafting.addProperty("category", "misc");
        pack.put("retrofit", "recipes/legacy_crafting.json", crafting.toString());

        int smelting = 0;
        try {
            for (Object[] recipe : LegacyBridge.<Object[][]>call("smeltingRecipes")) {
                JsonObject ingredient = ingredient(recipe[0]);
                ItemStack output = LegacyStacks.toModern(recipe[1]);
                if (ingredient == null || output.isEmpty()) {
                    continue;
                }
                JsonObject json = new JsonObject();
                json.addProperty("type", "minecraft:smelting");
                json.add("ingredient", ingredient);
                JsonObject result = new JsonObject();
                result.addProperty("item", ForgeRegistries.ITEMS.getKey(output.getItem()).toString());
                result.addProperty("count", output.getCount());
                if (output.hasTag()) {
                    result.addProperty("nbt", output.getTag().toString());
                }
                json.add("result", result);
                json.addProperty("experience", (Float) recipe[2]);
                json.addProperty("cookingtime", 200);
                pack.put("retrofit", "recipes/legacy_smelting_" + smelting++ + ".json", json.toString());
            }
        } catch (RuntimeException e) {
            LOGGER.error("Could not read the legacy furnace recipes", e);
        }
        LOGGER.info("Legacy recipes: crafting through the legacy recipe list, {} smelting recipes", smelting);
    }

    /** A 1.20.1 ingredient for a legacy stack: the item, narrowed to its subtype where it has one. */
    private static JsonObject ingredient(Object legacyStack) {
        Object[] read = LegacyBridge.readStack(legacyStack);
        if (read == null) {
            return null;
        }
        int damage = (Integer) read[2];
        boolean wildcard = damage == WILDCARD;
        Object stack = wildcard ? LegacyBridge.makeStackOf(LegacyBridge.stackItem(legacyStack), 1, 0, null) : legacyStack;
        ItemStack modern = LegacyStacks.toModern(stack);
        if (modern.isEmpty()) {
            return null;
        }
        JsonObject json = new JsonObject();
        String id = ForgeRegistries.ITEMS.getKey(modern.getItem()).toString();
        if (!wildcard && modern.getItem() instanceof LegacyStackItem item && item.hasSubtypes() && damage != 0) {
            json.addProperty("type", "forge:partial_nbt");
            json.addProperty("item", id);
            JsonObject nbt = new JsonObject();
            nbt.addProperty(LegacyItem.META_TAG, damage);
            json.add("nbt", nbt);
        } else {
            json.addProperty("item", id);
        }
        return json;
    }

    /** Fuel value in ticks for a legacy item's stack (0: not a fuel). */
    static int burnTime(ItemStack stack) {
        Object legacy = LegacyStacks.toLegacy(stack);
        if (legacy == null) {
            return 0;
        }
        try {
            return LegacyBridge.call("burnTime", legacy);
        } catch (RuntimeException e) {
            LegacyErrors.report(stack.getItem(), "reporting its fuel value", e);
            return 0;
        }
    }

    /** The crafting grid as legacy stacks, row by row. */
    private static Object[] legacyGrid(CraftingContainer container) {
        Object[] grid = new Object[container.getContainerSize()];
        for (int i = 0; i < grid.length; i++) {
            grid[i] = LegacyStacks.toLegacy(container.getItem(i));
        }
        return grid;
    }

    /** Hands the grid to the legacy recipe list; see {@link LegacyRecipes}. */
    public static final class Crafting extends CustomRecipe {
        /** The level of the last match on this thread: assembling has no level, and legacy recipes take a World. */
        private static final ThreadLocal<Level> LEVEL = new ThreadLocal<>();

        public Crafting(ResourceLocation id, CraftingBookCategory category) {
            super(id, category);
        }

        @Override
        public boolean matches(CraftingContainer container, Level level) {
            if (container.isEmpty()) {
                return false;
            }
            LEVEL.set(level);
            return !result(container, level).isEmpty();
        }

        @Override
        public ItemStack assemble(CraftingContainer container, RegistryAccess registries) {
            Level level = LEVEL.get();
            return level == null ? ItemStack.EMPTY : result(container, level);
        }

        private static ItemStack result(CraftingContainer container, Level level) {
            Object[] grid = legacyGrid(container);
            boolean any = false;
            for (int i = 0; i < grid.length; i++) {
                if (grid[i] == null && !container.getItem(i).isEmpty()) {
                    return ItemStack.EMPTY; // a 1.20.1-only item is in the grid: no legacy recipe can use it
                }
                any |= grid[i] != null;
            }
            if (!any) {
                return ItemStack.EMPTY;
            }
            try {
                return LegacyStacks.toModern(LegacyBridge.call("craft", LegacyContent.legacyWorld(level), grid,
                        container.getWidth(), container.getHeight()));
            } catch (RuntimeException e) {
                LegacyErrors.report("crafting", "matching a recipe", e);
                return ItemStack.EMPTY;
            }
        }

        @Override
        public NonNullList<ItemStack> getRemainingItems(CraftingContainer container) {
            NonNullList<ItemStack> remaining = super.getRemainingItems(container); // 1.20.1 items (buckets) as usual
            try {
                Object[] legacy = LegacyBridge.call("remaining", (Object) legacyGrid(container));
                for (int i = 0; i < legacy.length && i < remaining.size(); i++) {
                    if (legacy[i] != null) {
                        remaining.set(i, LegacyStacks.toModern(legacy[i]));
                    }
                }
            } catch (RuntimeException e) {
                LegacyErrors.report("crafting", "returning container items", e);
            }
            return remaining;
        }

        @Override
        public boolean canCraftInDimensions(int width, int height) {
            return true;
        }

        @Override
        public RecipeSerializer<?> getSerializer() {
            return CRAFTING;
        }
    }
}
