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
