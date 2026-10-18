package retrofit.forge.world;

import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;

/**
 * Legacy crafting recipes as the recipe book and recipe viewers (JEI, REI, EMI) see them. They
 * never match: crafting itself goes through the legacy recipe list (LegacyRecipes.Crafting), so
 * the mods' crafting events and NBT handling run as on 1.7.10. Filling the grid from the recipe
 * book or a viewer works as for any recipe.
 */
public final class LegacyRecipeDisplays {
    public static final RecipeSerializer<Shaped> SHAPED = new ShapedSerializer();
    public static final RecipeSerializer<Shapeless> SHAPELESS = new ShapelessSerializer();

    private LegacyRecipeDisplays() {
    }

    public static final class Shaped extends ShapedRecipe {
        Shaped(ShapedRecipe base) {
            super(base.getId(), base.getGroup(), base.category(), base.getWidth(), base.getHeight(), base.getIngredients(),
                    base.getResultItem(null), false);
        }

        @Override
        public boolean matches(CraftingContainer container, Level level) {
            return false;
        }

        @Override
        public RecipeSerializer<?> getSerializer() {
            return SHAPED;
        }
    }

    public static final class Shapeless extends ShapelessRecipe {
        Shapeless(ShapelessRecipe base) {
            super(base.getId(), base.getGroup(), base.category(), base.getResultItem(null), base.getIngredients());
        }

        @Override
        public boolean matches(CraftingContainer container, Level level) {
            return false;
        }

        @Override
        public boolean showNotification() {
            return false; // players get them all at once (see LegacyRecipes.unlock): no toasts
        }

        @Override
        public RecipeSerializer<?> getSerializer() {
            return SHAPELESS;
        }
    }

    private static final class ShapedSerializer implements RecipeSerializer<Shaped> {
        @Override
        public Shaped fromJson(ResourceLocation id, JsonObject json) {
            return new Shaped(RecipeSerializer.SHAPED_RECIPE.fromJson(id, json));
        }

        @Override
        public Shaped fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) {
            return new Shaped(RecipeSerializer.SHAPED_RECIPE.fromNetwork(id, buffer));
        }

        @Override
        public void toNetwork(FriendlyByteBuf buffer, Shaped recipe) {
            RecipeSerializer.SHAPED_RECIPE.toNetwork(buffer, recipe);
        }
    }

    private static final class ShapelessSerializer implements RecipeSerializer<Shapeless> {
        @Override
        public Shapeless fromJson(ResourceLocation id, JsonObject json) {
            return new Shapeless(RecipeSerializer.SHAPELESS_RECIPE.fromJson(id, json));
        }

        @Override
        public Shapeless fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) {
            return new Shapeless(RecipeSerializer.SHAPELESS_RECIPE.fromNetwork(id, buffer));
        }

        @Override
        public void toNetwork(FriendlyByteBuf buffer, Shapeless recipe) {
            RecipeSerializer.SHAPELESS_RECIPE.toNetwork(buffer, recipe);
        }
    }
}
