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
        int shown = addDisplays(pack);
        LOGGER.info("Legacy recipes: crafting through the legacy recipe list ({} shown in the recipe book and viewers), {} smelting recipes",
                shown, smelting);
    }

    /** Legacy crafting recipes for the recipe book and recipe viewers (LegacyRecipeDisplays); returns how many. */
    private static int addDisplays(LegacyDataPack pack) {
        Object[][] recipes;
        try {
            recipes = LegacyBridge.call("craftingRecipes");
        } catch (RuntimeException e) {
            LOGGER.error("Could not list the legacy crafting recipes", e);
            return 0;
        }
        int count = 0;
        for (Object[] recipe : recipes) {
            try {
                JsonObject json = display(recipe);
                if (json != null) {
                    pack.put("retrofit", "recipes/legacy_display_" + count++ + ".json", json.toString());
                }
            } catch (RuntimeException e) {
                // one that can't be shown: it still crafts
            }
        }
        return count;
    }

    private static JsonObject display(Object[] recipe) {
        boolean shaped = (Integer) recipe[0] == 1;
        Object[][] cells = (Object[][]) recipe[3];
        ItemStack output = LegacyStacks.toModern(recipe[4]);
        if (output.isEmpty()) {
            return null;
        }
        com.google.gson.JsonElement[] ingredients = new com.google.gson.JsonElement[cells.length];
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] == null) {
                continue;
            }
            com.google.gson.JsonArray alternatives = new com.google.gson.JsonArray();
            for (Object stack : cells[i]) {
                JsonObject ingredient = ingredient(stack);
                if (ingredient != null && !alternatives.contains(ingredient)) {
                    alternatives.add(ingredient);
                }
            }
            if (alternatives.isEmpty()) {
                return null; // an ingredient nothing in 1.20.1 is (an empty ore name)
            }
            ingredients[i] = alternatives.size() == 1 ? alternatives.get(0) : alternatives;
        }
        JsonObject json = new JsonObject();
        json.addProperty("category", "misc");
        json.addProperty("group", "retrofit_legacy");
        if (shaped) {
            int width = (Integer) recipe[1];
            int height = (Integer) recipe[2];
            if (width < 1 || height < 1 || width > 3 || height > 3 || width * height != cells.length) {
                return null;
            }
            json.addProperty("type", "retrofit:legacy_shaped");
            com.google.gson.JsonArray pattern = new com.google.gson.JsonArray();
            JsonObject key = new JsonObject();
            for (int row = 0; row < height; row++) {
                StringBuilder line = new StringBuilder();
                for (int column = 0; column < width; column++) {
                    int cell = row * width + column;
                    if (ingredients[cell] == null) {
                        line.append(' ');
                    } else {
                        char symbol = (char) ('A' + cell);
                        line.append(symbol);
                        key.add(String.valueOf(symbol), ingredients[cell]);
                    }
                }
                pattern.add(line.toString());
            }
            json.add("pattern", pattern);
            json.add("key", key);
        } else {
            json.addProperty("type", "retrofit:legacy_shapeless");
            com.google.gson.JsonArray list = new com.google.gson.JsonArray();
            for (com.google.gson.JsonElement ingredient : ingredients) {
                if (ingredient != null) {
                    list.add(ingredient);
                }
            }
            if (list.isEmpty() || list.size() > 9) {
                return null;
            }
            json.add("ingredients", list);
        }
        JsonObject result = new JsonObject();
        result.addProperty("item", ForgeRegistries.ITEMS.getKey(output.getItem()).toString());
        result.addProperty("count", output.getCount());
        if (output.hasTag()) {
            result.addProperty("nbt", output.getTag().toString());
        }
        json.add("result", result);
        return json;
    }

    /** Players know every legacy recipe (the recipe book shows only known ones); they join on login, without toasts. */
    static void unlock(net.minecraft.server.level.ServerPlayer player) {
        java.util.List<net.minecraft.world.item.crafting.Recipe<?>> displays = new java.util.ArrayList<>();
        for (net.minecraft.world.item.crafting.Recipe<?> recipe : player.server.getRecipeManager().getRecipes()) {
            if (recipe instanceof LegacyRecipeDisplays.Shaped || recipe instanceof LegacyRecipeDisplays.Shapeless) {
                displays.add(recipe);
            }
        }
        if (!displays.isEmpty()) {
            player.awardRecipes(displays);
        }
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
