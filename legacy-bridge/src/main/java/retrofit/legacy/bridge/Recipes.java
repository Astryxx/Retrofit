package retrofit.legacy.bridge;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Legacy crafting, smelting and fuel for the host. Crafting asks the legacy recipe list itself,
 * so mods' own IRecipe classes (NBT recipes, dyeing, repairs) work. Vanilla 1.7.10 recipes are
 * skipped (1.20.1 has its own), unless a mod item is involved.
 */
public final class Recipes {
    private static final Container NO_CONTAINER = new Container() {
        @Override
        public boolean func_75145_c(EntityPlayer player) { // canInteractWith
            return true;
        }

        @Override
        public void func_75130_a(net.minecraft.inventory.IInventory inventory) { // onCraftMatrixChanged
        }
    };

    private Recipes() {
    }

    private static InventoryCrafting grid(Object[] stacks, int width, int height) {
        InventoryCrafting grid = new InventoryCrafting(NO_CONTAINER, width, height);
        for (int i = 0; i < stacks.length && i < width * height; i++) {
            grid.func_70299_a(i, (ItemStack) stacks[i]); // setInventorySlotContents
        }
        return grid;
    }

    private static boolean isModItem(ItemStack stack) {
        if (stack == null || stack.func_77973_b() == null) {
            return false;
        }
        String name = Item.field_150901_e.func_148750_c(stack.func_77973_b()); // itemRegistry.getNameForObject
        return name != null && !name.startsWith("minecraft:");
    }

    private static boolean isVanillaRecipe(IRecipe recipe) {
        String type = recipe.getClass().getName();
        return type.startsWith("net.minecraft.") || type.startsWith("net.minecraftforge.");
    }

    /** The result of the first legacy recipe matching the grid (a copy), or null. */
    public static Object craft(Object world, Object[] stacks, int width, int height) {
        InventoryCrafting grid = grid(stacks, width, height);
        boolean modInput = false;
        for (Object stack : stacks) {
            modInput |= isModItem((ItemStack) stack);
        }
        for (Object element : CraftingManager.func_77594_a().func_77592_b()) { // getInstance().getRecipeList()
            IRecipe recipe = (IRecipe) element;
            if (!modInput && isVanillaRecipe(recipe) && !isModItem(recipe.func_77571_b())) { // getRecipeOutput
                continue;
            }
            if (recipe.func_77569_a(grid, (World) world)) { // matches
                ItemStack result = recipe.func_77572_b(grid); // getCraftingResult
                return result == null ? null : result.func_77946_l(); // copy
            }
        }
        return null;
    }

    /** What stays in each grid slot after crafting (Forge container items: buckets, tools that wear). */
    public static Object[] remaining(Object[] stacks) {
        Object[] remaining = new Object[stacks.length];
        for (int i = 0; i < stacks.length; i++) {
            ItemStack stack = (ItemStack) stacks[i];
            if (stack != null && stack.func_77973_b().hasContainerItem(stack)) {
                ItemStack container = stack.func_77973_b().getContainerItem(stack);
                remaining[i] = container != null && container.func_77984_f() && container.func_77960_j() > container.func_77958_k()
                        ? null : container; // isItemStackDamageable, getItemDamage, getMaxDamage: used up
            }
        }
        return remaining;
    }

    /**
     * The crafting recipes with a mod item in them that recipe viewers can show: {shaped (1/0),
     * width, height, ingredients (each an array of alternative stacks, or null for an empty
     * cell), output}. Vanilla shaped and shapeless recipes and Forge's ore recipes; mods' own
     * recipe classes still craft, but can't be listed.
     */
    @SuppressWarnings("unchecked")
    public static Object[][] craftingRecipes() {
        List<Object[]> recipes = new ArrayList<Object[]>();
        for (Object element : CraftingManager.func_77594_a().func_77592_b()) { // getInstance().getRecipeList()
            try {
                Object[] recipe = describe((IRecipe) element);
                if (recipe != null && involvesMod(recipe)) {
                    recipes.add(recipe);
                }
            } catch (Throwable t) {
                // a recipe that can't be read: it still crafts
            }
        }
        return recipes.toArray(new Object[0][]);
    }

    private static Object[] describe(IRecipe recipe) throws ReflectiveOperationException {
        ItemStack output = recipe.func_77571_b(); // getRecipeOutput
        if (output == null || output.func_77973_b() == null) {
            return null;
        }
        if (recipe.getClass() == net.minecraft.item.crafting.ShapedRecipes.class) {
            net.minecraft.item.crafting.ShapedRecipes shaped = (net.minecraft.item.crafting.ShapedRecipes) recipe;
            Object[][] cells = new Object[shaped.field_77574_d.length][]; // recipeItems
            for (int i = 0; i < cells.length; i++) {
                cells[i] = shaped.field_77574_d[i] == null ? null : new Object[]{shaped.field_77574_d[i]};
            }
            return new Object[]{1, shaped.field_77576_b, shaped.field_77577_c, cells, output}; // recipeWidth, recipeHeight
        }
        if (recipe.getClass() == net.minecraft.item.crafting.ShapelessRecipes.class) {
            List<Object> items = ((net.minecraft.item.crafting.ShapelessRecipes) recipe).field_77579_b; // recipeItems
            return new Object[]{0, 0, 0, cells(items.toArray()), output};
        }
        if (recipe.getClass() == net.minecraftforge.oredict.ShapedOreRecipe.class) {
            net.minecraftforge.oredict.ShapedOreRecipe shaped = (net.minecraftforge.oredict.ShapedOreRecipe) recipe;
            java.lang.reflect.Field width = shaped.getClass().getDeclaredField("width");
            java.lang.reflect.Field height = shaped.getClass().getDeclaredField("height");
            width.setAccessible(true);
            height.setAccessible(true);
            return new Object[]{1, width.getInt(shaped), height.getInt(shaped), cells(shaped.getInput()), output};
        }
        if (recipe.getClass() == net.minecraftforge.oredict.ShapelessOreRecipe.class) {
            return new Object[]{0, 0, 0, cells(((net.minecraftforge.oredict.ShapelessOreRecipe) recipe).getInput().toArray()), output};
        }
        return null;
    }

    /** Recipe inputs (stacks, or ore lists) as arrays of alternatives. */
    private static Object[][] cells(Object[] inputs) {
        Object[][] cells = new Object[inputs.length][];
        for (int i = 0; i < inputs.length; i++) {
            Object input = inputs[i];
            if (input instanceof ItemStack) {
                cells[i] = new Object[]{input};
            } else if (input instanceof List) {
                cells[i] = ((List<?>) input).isEmpty() ? new Object[0] : ((List<?>) input).toArray();
            }
        }
        return cells;
    }

    private static boolean involvesMod(Object[] recipe) {
        if (isModItem((ItemStack) recipe[4])) {
            return true;
        }
        for (Object[] cell : (Object[][]) recipe[3]) {
            if (cell != null) {
                for (Object stack : cell) {
                    if (stack instanceof ItemStack && isModItem((ItemStack) stack)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Smelting recipes with a mod item on either side: {input, output, experience}. */
    @SuppressWarnings("unchecked")
    public static Object[][] smeltingRecipes() {
        List<Object[]> recipes = new ArrayList<Object[]>();
        FurnaceRecipes furnace = FurnaceRecipes.func_77602_a(); // smelting
        for (Map.Entry<ItemStack, ItemStack> entry : ((Map<ItemStack, ItemStack>) furnace.func_77599_b()).entrySet()) { // getSmeltingList
            if (isModItem(entry.getKey()) || isModItem(entry.getValue())) {
                recipes.add(new Object[]{entry.getKey(), entry.getValue(), furnace.func_151398_b(entry.getValue())}); // func_151398_b: experience
            }
        }
        return recipes.toArray(new Object[0][]);
    }

    /** Fuel value in ticks: vanilla rules plus mods' fuel handlers (GameRegistry.registerFuelHandler). */
    public static int burnTime(Object legacyStack) {
        return TileEntityFurnace.func_145952_a((ItemStack) legacyStack); // getItemBurnTime
    }
}
