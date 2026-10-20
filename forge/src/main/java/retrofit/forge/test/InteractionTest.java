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
import net.minecraft.world.item.Item;
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
import retrofit.forge.world.LegacyMenu;
import retrofit.forge.world.LegacyMob;
import retrofit.forge.world.LegacyBridge;
import retrofit.forge.world.LegacyPlayers;
import net.minecraft.world.inventory.ClickType;

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

        // 8. A command the legacy mod registered in FMLServerStartingEvent.
        total++;
        int commandResult = player.server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "hello retrofit");
        passed += check(commandResult == 1, "command", "/hello returned " + commandResult);

        // 9. World generation: the legacy generator's ore veins (in stone laid down for it, as the test world is flat)
        //    and, every 8th chunk, a crate it fills with a ruby.
        total++;
        passed += worldGen(level);

        // 10. Legacy armor: a real 1.20.1 armor piece with the legacy armor points (diamond helmet: 3).
        total++;
        net.minecraft.world.item.Item helmet = ForgeRegistries.ITEMS.getValue(new ResourceLocation("hello1710", "ruby_helmet"));
        player.getInventory().setItem(39, new ItemStack(helmet));
        player.getInventory().setChanged();
        int armor = (int) player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
        String texture = helmet instanceof net.minecraft.world.item.ArmorItem
                ? ((net.minecraft.world.item.ArmorItem) helmet).getArmorTexture(new ItemStack(helmet), player, net.minecraft.world.entity.EquipmentSlot.HEAD, null) : null;
        passed += check(helmet instanceof retrofit.forge.world.LegacyArmorItem && "hello1710:textures/models/armor/ruby_layer_1.png".equals(texture),
                "armor", helmet + ", armor attribute now " + armor + ", texture " + texture);

        // 11. A legacy mob: its own AI runs, players hurt it, it dies with its own death and drops.
        total++;
        passed += entity(player, level, target);

        if (passed == total) {
            LOGGER.info("[WorldTest] Interaction PASSED: {}/{}", passed, total);
        } else {
            LOGGER.error("[WorldTest] Interaction FAILED: {}/{} passed", passed, total);
        }
    }

    /**
     * Opens hello1710's crate (a legacy container GUI through FML's openGui) for the player and
     * shift-clicks a diamond into it; leaves it open for the client half to draw and click.
     */
    static void openGui(ServerPlayer player, BlockPos near) {
        ServerLevel level = player.serverLevel();
        Block crate = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("hello1710", "crate"));
        if (!(crate instanceof LegacyBlock)) {
            LOGGER.error("[WorldTest] GUI FAILED: hello1710:crate missing");
            return;
        }
        BlockPos at = near.south();
        if (!level.getBlockState(at).is(crate)) {
            level.setBlock(at, crate.defaultBlockState(), 3);
        }
        player.getInventory().setItem(8, ItemStack.EMPTY);
        player.getInventory().selected = 8;
        player.getInventory().setItem(9, new ItemStack(Items.DIAMOND, 3));
        player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false));
        if (!(player.containerMenu instanceof LegacyMenu menu)) {
            LOGGER.error("[WorldTest] GUI open: FAILED (menu is {})", player.containerMenu);
            return;
        }
        check(menu.slots.size() == 45, "GUI open", menu.slots.size() + " slots, window " + menu.containerId);
        int before = 0;
        for (int i = 0; i < 9; i++) {
            before += menu.slots.get(i).getItem().is(Items.DIAMOND) ? menu.slots.get(i).getItem().getCount() : 0;
        }
        menu.clicked(9, 0, ClickType.QUICK_MOVE, player); // inventory slot 9 -> crate (legacy transferStackInSlot)
        menu.broadcastChanges();
        // A 1.20.1 hopper-style transfer through Forge's item handler on the legacy crate.
        var crateEntity = level.getBlockEntity(at);
        var handler = crateEntity == null ? null : crateEntity.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, Direction.UP).orElse(null);
        ItemStack left = handler == null ? null : net.minecraftforge.items.ItemHandlerHelper.insertItem(handler, new ItemStack(Items.EMERALD, 5), false);
        check(handler != null && left != null && left.isEmpty(), "GUI item handler",
                (handler == null ? "no handler" : handler.getSlots() + " slots") + ", 5 emeralds inserted, left " + left);
        int after = 0;
        for (int i = 0; i < 9; i++) {
            after += menu.slots.get(i).getItem().is(Items.DIAMOND) ? menu.slots.get(i).getItem().getCount() : 0;
        }
        check(after == before + 3 && player.getInventory().getItem(9).isEmpty(), "GUI shift-click",
                "diamonds in the crate " + before + " -> " + after + ", inventory slot 9 has " + player.getInventory().getItem(9));
    }

    private static int worldGen(ServerLevel level) {
        Block ore = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("hello1710", "ruby_ore"));
        var feature = ForgeRegistries.FEATURES.getValue(new ResourceLocation("retrofit", "legacy_generators"));
        if (ore == null || feature == null) {
            return check(false, "world generation", "ruby_ore " + ore + ", feature " + feature);
        }
        int minX = 640;
        int minZ = 640;
        for (int cx = 40; cx <= 41; cx++) {
            for (int cz = 40; cz <= 41; cz++) {
                level.getChunk(cx, cz);
            }
        }
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int x = minX; x < minX + 32; x++) {
            for (int z = minZ; z < minZ + 32; z++) {
                for (int y = 5; y <= 45; y++) {
                    if (!level.getBlockState(at.set(x, y, z)).is(ore)) {
                        level.setBlock(at, Blocks.STONE.defaultBlockState(), 2);
                    }
                }
            }
        }
        @SuppressWarnings("unchecked")
        var generators = (net.minecraft.world.level.levelgen.feature.Feature<net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration>) feature;
        generators.place(net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration.INSTANCE, level,
                level.getChunkSource().getGenerator(), level.random, new BlockPos(minX, level.getMinBuildHeight(), minZ));
        int ores = 0;
        for (int x = minX; x < minX + 32; x++) {
            for (int z = minZ; z < minZ + 32; z++) {
                for (int y = 5; y <= 45; y++) {
                    ores += level.getBlockState(at.set(x, y, z)).is(ore) ? 1 : 0;
                }
            }
        }
        int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, minX + 8, minZ + 8);
        net.minecraft.world.level.block.entity.BlockEntity crate = null;
        for (int y = top; y >= top - 2 && crate == null; y--) {
            crate = level.getBlockEntity(new BlockPos(minX + 8, y, minZ + 8));
        }
        String crateData = crate == null ? "none" : crate.saveWithoutMetadata().toString();
        boolean filled = crateData.contains("test_gem") || crateData.contains("Items");
        return check(ores > 0 && filled, "world generation", ores + " ruby ore blocks; crate " + crateData);
    }

    private static Block bookcase() {
        for (LegacyBlock block : LegacyContent.blocks()) {
            if (LegacyBridge.describe(block.legacyBlock()).startsWith("jds.bibliocraft.blocks.BlockBookcase@")) {
                return block;
            }
        }
        return null;
    }

    /** Placed early, so clients have its tile entity when its GUI opens. */
    static void placeBookcase(ServerLevel level, BlockPos near) {
        Block bookcase = bookcase();
        if (bookcase != null) {
            level.setBlock(near.south().east(), bookcase.defaultBlockState(), 3);
        }
    }

    /** A real mod's GUI, if BiblioCraft is installed: its bookcase, opened the way a player would. */
    static void openModGui(ServerPlayer player, BlockPos near) {
        Block bookcase = bookcase();
        if (bookcase == null) {
            LOGGER.info("[WorldTest] BiblioCraft GUI: skipped (BiblioCraft not installed)");
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos at = near.south().east();
        player.closeContainer();
        // BiblioCraft opens a bookcase's GUI when its upper half is clicked holding something that isn't a book.
        ItemStack held = new ItemStack(Items.STONE);
        player.getInventory().setItem(player.getInventory().selected, held);
        player.gameMode.useItemOn(player, level, held, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(at).add(0, 0.25, 0.5), Direction.SOUTH, at, false));
        check(player.containerMenu instanceof LegacyMenu, "BiblioCraft GUI", "menu " + player.containerMenu.getClass().getSimpleName()
                + " with " + player.containerMenu.slots.size() + " slots");
    }

    private static int entity(ServerPlayer player, ServerLevel level, BlockPos at) {
        var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("hello1710", "rubygolem"));
        net.minecraft.world.entity.Entity golem = type == null ? null : type.create(level);
        if (!(golem instanceof LegacyMob mob)) {
            return check(false, "entity", "hello1710:rubygolem is " + golem);
        }
        // 1.7.10 code assumes y >= 0 (pathfinding included); the test world is superflat at the 1.20.1 floor,
        // so this runs on a platform at y 70, where 1.7.10 worlds had their ground.
        BlockPos platform = new BlockPos(at.getX() + 20, 70, at.getZ()); // away from the rest of the test, in the always-loaded spawn chunks
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                level.setBlock(platform.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 2);
                for (int dy = 1; dy <= 3; dy++) {
                    level.setBlock(platform.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        at = platform.above();
        mob.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 3.5, 0, 0);
        level.addFreshEntity(mob);
        net.minecraft.world.phys.Vec3 start = mob.position();
        player.setGameMode(GameType.SURVIVAL);
        player.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() - 4.5);
        LegacyPlayers.mirror(player, true);
        double startDistance = mob.distanceTo(player);
        for (int i = 0; i < 200; i++) {
            mob.tick(); // the legacy update: AI tasks (chase the player), movement
        }
        boolean moved = mob.position().distanceTo(start) > 0.5;
        LOGGER.info("[WorldTest] Golem: moved from {} to {}, distance to the player {} -> {}", start, mob.position(), startDistance, mob.distanceTo(player));
        float before = mob.getHealth();
        player.setGameMode(GameType.SURVIVAL);
        boolean hit = mob.hurt(level.damageSources().playerAttack(player), 5);
        mob.tick();
        float after = mob.getHealth();
        level.getEntitiesOfClass(ItemEntity.class, mob.getBoundingBox().inflate(4)).forEach(ItemEntity::discard);
        Item gem = ForgeRegistries.ITEMS.getValue(new ResourceLocation("hello1710", "test_gem"));
        // 1.7.10 drops 0-2 items at random (dropFewItems): kill more golems until one drops something.
        boolean removed = true;
        boolean dropped = false;
        List<ItemEntity> drops = List.of();
        for (int kill = 0; kill < 6 && !dropped; kill++) {
            LegacyMob victim = mob;
            if (kill > 0) {
                victim = (LegacyMob) type.create(level);
                victim.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 1.5, 0, 0);
                level.addFreshEntity(victim);
                victim.tick();
            }
            victim.hurt(level.damageSources().playerAttack(player), 1000);
            for (int i = 0; i < 30 && !victim.isRemoved(); i++) {
                victim.tick(); // 1.7.10's death animation, then setDead
            }
            removed &= victim.isRemoved();
            drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(8));
            dropped = drops.stream().anyMatch(item -> item.getItem().is(gem));
        }
        LOGGER.info("[WorldTest] Golem drops: {}", drops.stream().map(item -> item.getItem() + " at " + item.blockPosition()).toList());
        return check(moved && hit && after < before && removed && dropped, "entity",
                "moved " + moved + ", health " + before + " -> " + after + ", removed after death " + removed + ", dropped a ruby " + dropped);
    }

    /** A golem to be seen in the screenshot (the ones from earlier runs go first). */
    static void spawnGolem(ServerLevel level, BlockPos near) {
        level.getEntitiesOfClass(LegacyMob.class, new AABB(near).inflate(48)).forEach(LegacyMob::discard);
        var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("hello1710", "rubygolem"));
        net.minecraft.world.entity.Entity golem = type == null ? null : type.create(level);
        if (golem != null) {
            golem.moveTo(near.getX() - 1.5, near.getY(), near.getZ() + 1.5, 160, 0);
            level.addFreshEntity(golem);
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
