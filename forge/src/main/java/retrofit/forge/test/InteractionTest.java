package retrofit.forge.test;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import retrofit.forge.world.LegacyBlock;
import retrofit.forge.world.LegacyContent;
import retrofit.forge.world.LegacyItem;

import java.util.List;

/**
 * Part of the world test: a real server player places, uses, eats and breaks hello1710 content
 * through the same server calls 1.20.1's network handler makes, so every step runs the legacy
 * mod's own code (ItemBlock placing and onBlockPlacedBy, onBlockActivated, item use over time,
 * breakBlock and getDrops) through the player mirror.
 */
final class InteractionTest {
    private static final Logger LOGGER = LogUtils.getLogger();

    private InteractionTest() {
    }

    static void run(ServerPlayer player, BlockPos near) {
        ServerLevel level = player.serverLevel();
        Block counter = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("hello1710", "counter_block"));
        if (!(counter instanceof LegacyBlock) || !(ForgeRegistries.ITEMS.getValue(new ResourceLocation("hello1710", "test_gem")) instanceof LegacyItem gem)) {
            LOGGER.error("[WorldTest] Interaction FAILED: hello1710 content missing");
            return;
        }
        int x = near.getX() + 4;
        int z = near.getZ() + 4;
        BlockPos ground = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z);
        BlockPos target = ground.above();
        int passed = 0;
        int total = 0;

        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().selected = 0;
        player.setYRot(90f);

        // 1. Placing through the legacy ItemBlock: the block faces the player (onBlockPlacedBy sets meta 1 for yaw 90).
        total++;
        ItemStack blocks = new ItemStack(counter.asItem(), 2);
        player.getInventory().setItem(0, blocks);
        InteractionResult placed = player.gameMode.useItemOn(player, level, blocks, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(ground).add(0, 0.5, 0), Direction.UP, ground, false));
        BlockState state = level.getBlockState(target);
        boolean placeOk = state.is(counter) && state.getValue(LegacyContent.META) == 1 && player.getInventory().getItem(0).getCount() == 1;
        passed += check(placeOk, "place", "result " + placed + ", block " + state + ", stack left " + player.getInventory().getItem(0));

        // 2. Right-click: legacy onBlockActivated adds a diamond to the inventory (and chats).
        total++;
        player.getInventory().setItem(0, ItemStack.EMPTY);
        int diamonds = player.getInventory().countItem(Items.DIAMOND);
        InteractionResult used = player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(target), Direction.NORTH, target, false));
        int after = player.getInventory().countItem(Items.DIAMOND);
        passed += check(used.consumesAction() && after == diamonds + 1, "activate", "result " + used + ", diamonds " + diamonds + " -> " + after);

        // 3. Eating: legacy setItemInUse starts 1.20.1 item use; onEaten adds food and shrinks the stack.
        total++;
        player.getFoodData().setFoodLevel(10);
        ItemStack rubies = gem.stackOf(0);
        rubies.setCount(2);
        player.getInventory().setItem(0, rubies);
        player.gameMode.useItem(player, level, rubies, InteractionHand.MAIN_HAND);
        boolean using = player.isUsingItem();
        ItemStack result = player.getUseItem().finishUsingItem(level, player);
        player.setItemInHand(InteractionHand.MAIN_HAND, result);
        player.stopUsingItem();
        int food = player.getFoodData().getFoodLevel();
        passed += check(using && food == 14 && player.getMainHandItem().getCount() == 1, "eat",
                "using " + using + ", food 10 -> " + food + ", rubies left " + player.getMainHandItem().getCount());

        // 4. Breaking with a stone pickaxe: breakBlock drops a ruby, getDrops the block itself.
        total++;
        player.getInventory().setItem(0, new ItemStack(Items.STONE_PICKAXE));
        AABB around = new AABB(target).inflate(2);
        level.getEntitiesOfClass(ItemEntity.class, around).forEach(ItemEntity::discard);
        boolean broken = player.gameMode.destroyBlock(target);
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, around);
        boolean ruby = drops.stream().anyMatch(item -> item.getItem().getItem() == gem);
        boolean block = drops.stream().anyMatch(item -> item.getItem().getItem() == counter.asItem());
        passed += check(broken && level.getBlockState(target).isAir() && ruby && block, "break",
                "broken " + broken + ", drops " + drops.stream().map(item -> item.getItem().toString()).toList());
        drops.forEach(ItemEntity::discard);

        // 5. Without the right tool, a block that needs a pickaxe drops nothing from getDrops.
        total++;
        level.setBlock(target, counter.defaultBlockState(), 3);
        player.getInventory().setItem(0, ItemStack.EMPTY);
        player.gameMode.destroyBlock(target);
        List<ItemEntity> bare = level.getEntitiesOfClass(ItemEntity.class, around);
        boolean noBlock = bare.stream().noneMatch(item -> item.getItem().getItem() == counter.asItem());
        passed += check(noBlock, "harvest tool", "drops by hand " + bare.stream().map(item -> item.getItem().toString()).toList());
        bare.forEach(ItemEntity::discard);
        level.setBlock(target, Blocks.AIR.defaultBlockState(), 3);

        // 6. Crafting through the legacy recipe list: two diamonds side by side make a sapphire.
        total++;
        CraftingContainer grid = player.inventoryMenu.getCraftSlots();
        grid.setItem(0, new ItemStack(Items.DIAMOND));
        grid.setItem(1, new ItemStack(Items.DIAMOND));
        ItemStack crafted = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, level)
                .map(recipe -> recipe.assemble(grid, level.registryAccess())).orElse(ItemStack.EMPTY);
        grid.clearContent();
        passed += check(crafted.getItem() == gem && gem.meta(crafted) == 1, "craft", "two diamonds made " + crafted + " " + crafted.getTag());

        // 7. Smelting from the generated data pack: a ruby smelts into a diamond.
        total++;
        ItemStack smelted = level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, new SimpleContainer(gem.stackOf(0)), level)
                .map(recipe -> recipe.getResultItem(level.registryAccess())).orElse(ItemStack.EMPTY);
        passed += check(smelted.is(Items.DIAMOND), "smelt", "a ruby smelts into " + smelted);

        if (passed == total) {
            LOGGER.info("[WorldTest] Interaction PASSED: {}/{}", passed, total);
        } else {
            LOGGER.error("[WorldTest] Interaction FAILED: {}/{} passed", passed, total);
        }
    }

    private static int check(boolean ok, String step, String detail) {
        if (ok) {
            LOGGER.info("[WorldTest] Interaction {}: OK ({})", step, detail);
        } else {
            LOGGER.error("[WorldTest] Interaction {}: FAILED ({})", step, detail);
        }
        return ok ? 1 : 0;
    }
}
