package retrofit.forge.test;

import io.netty.buffer.Unpooled;
import net.minecraft.core.NonNullList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import retrofit.forge.world.LegacyRecipes;

import java.util.List;

/** Version-specific network oracle: do not import 1.21 recipe deletion without a 1.20.1 failure. */
final class CandidateReviewCheck {
    interface Result { void report(String name, boolean passed, String detail); }
    static void recipes(Result result) {
        var ingredient = NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.STONE));
        Recipe<?> valid = new ShapedRecipe(new ResourceLocation("retrofit", "candidate_valid"), "", CraftingBookCategory.MISC,
                1, 1, ingredient, new ItemStack(Items.DIAMOND), false);
        Recipe<?> emptyOutput = new ShapedRecipe(new ResourceLocation("retrofit", "candidate_empty"), "", CraftingBookCategory.MISC,
                1, 1, ingredient, ItemStack.EMPTY, false);
        Recipe<?> emptyIngredient = new ShapelessRecipe(new ResourceLocation("retrofit", "candidate_ingredient"), "", CraftingBookCategory.MISC,
                new ItemStack(Items.DIAMOND), NonNullList.of(Ingredient.EMPTY, Ingredient.EMPTY));
        Recipe<?> nativeDispatch = new LegacyRecipes.Crafting(new ResourceLocation("retrofit", "legacy_crafting"), CraftingBookCategory.MISC);
        List<Recipe<?>> cases = List.of(valid, emptyOutput, emptyIngredient, nativeDispatch);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new ClientboundUpdateRecipesPacket(cases).write(buffer);
            var received = new ClientboundUpdateRecipesPacket(buffer).getRecipes();
            result.report("1.20.1 recipe packet preserves valid recipe", received.size()==4
                    && received.get(0).getResultItem(null).is(Items.DIAMOND), "decoded="+received.size());
            result.report("1.20.1 tolerates an empty recipe result on the wire", received.get(1).getResultItem(null).isEmpty(), "empty-result packet decoded without disconnect");
            result.report("1.20.1 tolerates empty ingredient encoding", received.get(2).getIngredients().size()==1, "empty ingredient decoded");
            result.report("Native crafting dispatcher survives recipe sync", received.get(3) instanceof LegacyRecipes.Crafting
                    && received.get(3).isSpecial(), "special empty display output retained");
        } finally { buffer.release(); }
    }
}
