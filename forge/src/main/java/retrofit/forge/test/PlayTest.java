package retrofit.forge.test;

import com.mojang.logging.LogUtils;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import retrofit.forge.RetrofitMod;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Plays the corpus mods the way a player does (-PplayTest): everything goes through the client,
 * as clicks and key presses would (MultiPlayerGameMode's use, attack and interact, held keys),
 * so the client's prediction, the packets and the server all take part, which the server-side
 * mod sweep skips. Scenarios run for the mods that are present: covering a Carpenter's Block
 * with a 1.20.1 block, wearing OreSpawn armor, flying an IC2 jetpack, launching a Galacticraft
 * rocket, opening machine GUIs. Each logs "[PlayTest] name: PASSED/FAILED" and saves
 * play-*.png screenshots; the game quits when all have run.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, value = Dist.CLIENT)
public final class PlayTest {
    public static final boolean ENABLED = Boolean.getBoolean("retrofit.playTest");
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String WORLD = "RetrofitPlayTest";

    private static final Deque<Step> STEPS = new ArrayDeque<>();
    private static final List<String> RESULTS = new ArrayList<>();
    private static int titleTicks;
    private static boolean creating;
    private static boolean planned;
    private static int wait;
    private static int inWorldTicks;
    /** Where the current scenario happens: a fresh patch of the flat world, away from the last one. */
    private static BlockPos site;
    private static int sites;

    private PlayTest() {
    }

    /** One step: an action, then a number of ticks to wait; or a condition polled each tick until true (or it times out). */
    private record Step(String what, Runnable action, int delay, BooleanSupplier until, int timeout) {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!creating && minecraft.screen instanceof TitleScreen && ++titleTicks >= 40) {
            creating = true;
            createWorld(minecraft);
            return;
        }
        if (minecraft.level == null || minecraft.player == null || minecraft.getSingleplayerServer() == null) {
            return;
        }
        if (++inWorldTicks < 100) {
            return; // the world settles (chunks, the player's legacy mirror)
        }
        if (!planned) {
            planned = true;
            plan();
        }
        if (wait > 0) {
            wait--;
            return;
        }
        Step step = STEPS.peek();
        if (step == null) {
            LOGGER.info("[PlayTest] Done: {}", RESULTS);
            minecraft.stop();
            return;
        }
        if (step.until() != null) {
            if (step.until().getAsBoolean()) {
                STEPS.poll();
            } else if (step.timeout() <= 0) {
                STEPS.poll();
                LOGGER.info("[PlayTest] {}: gave up waiting", step.what());
            } else {
                STEPS.poll();
                STEPS.push(new Step(step.what(), null, 0, step.until(), step.timeout() - 1));
            }
            return;
        }
        STEPS.poll();
        try {
            step.action().run();
        } catch (RuntimeException e) {
            LOGGER.error("[PlayTest] {} threw", step.what(), e);
        }
        wait = step.delay();
    }

    // ---- Scenarios ----

    private static void plan() {
        do_("start", 10, () -> server(player -> {
            player.setGameMode(GameType.SURVIVAL);
            player.getAbilities().invulnerable = true;
            player.onUpdateAbilities();
            player.serverLevel().setDayTime(6000);
            player.serverLevel().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, player.server);
            player.serverLevel().getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, player.server);
        }));
        do_("names", 0, PlayTest::untranslatedNames);
        do_("asset pack", 0, () -> {
            // every legacy mod's models and textures are in one pack: one block failing it (a half-loaded mod's) broke them all
            String build = retrofit.forge.client.LegacyAssetPacks.lastBuild();
            String[] counts = build == null || build.startsWith("FAILED") ? null : build.split("[/ ]");
            result("legacy asset pack built, with its textures", counts != null
                    && Integer.parseInt(counts[0]) >= Integer.parseInt(counts[1]) * 95 / 100, String.valueOf(build));
        });
        String compare = System.getProperty("retrofit.compare");
        if (compare != null) {
            compare(Path.of(compare));
            return;
        }
        String dump = System.getProperty("retrofit.galleryDump");
        if (dump != null) {
            do_("gallery dump", 0, () -> ItemGallery.dump(dump));
        }
        if (item("carpentersblocks:blockcarpentersblock") != null) {
            carpentersBlocks();
        }
        if (item("pistronics2:block_of_camou") != null) {
            pistronics();
        }
        if (findItem("thetitans", path -> path.endsWith("_spawn_egg"), net.minecraft.world.item.SpawnEggItem.class) != null) {
            titansEgg();
            titansBossBar();
        }
        if (findItem("orespawn", path -> path.contains("ruby"), ArmorItem.class) != null) {
            wornArmor("orespawn", "ruby");
            oreSpawnBossBar();
        }
        if (item("ic2:itemarmorjetpackelectric") != null) {
            jetpack();
        }
        if (item("galacticraftcore:item.spaceship") != null) {
            rocket();
        }
        if (item("ic2:blockmachine") != null) {
            machineGui("IC2 macerator", "ic2:blockmachine", 3);
        }
        if (item("gregtech:gt.blockmachines") instanceof retrofit.forge.world.LegacyStackItem machines) {
            inventoryShot("gregtech-machines", java.util.stream.IntStream.of(1, 11, 101, 102, 103, 1000, 1180, 1190, 1210)
                    .mapToObj(machines::stackOf).toList());
        }
        if (item("gregtech:gt.blockmachines") != null) {
            machineGui("GregTech steam macerator", "gregtech:gt.blockmachines", 101);
        }
        deepCreature();
        deepOres();
        runtimeDimensions();
        if (findItem("orespawn", path -> path.contains("ruby"), ArmorItem.class) != null) {
            oreSpawnDimension();
        }
        if (findItem("erebus", path -> true, Item.class) != null) {
            erebusDimension();
        }
        if (findBlock("atum", path -> path.contains("portal")) != null) {
            atumPortal();
        }
        if (item("galacticraftcore:item.spaceship") != null) {
            spaceStation();
        }
        if (item("thaumcraft:itemthaumometer") != null) {
            thaumcraft();
        }
        if (findItem("fiskheroes", path -> path.startsWith("superhero_"), ArmorItem.class) != null) {
            heroSuit("fiskheroes:arrow");
        }
        if (findItem("fiskheroes", path -> true, Item.class) != null) {
            heldItems("fiskheroes");
        }
        if (findItem("manametalmod", path -> path.contains("initial_sickle"), Item.class) != null) {
            manaMetal();
        }
        heldInHand();
        if (findItem("ironchest", path -> true, Item.class) != null) {
            legacyModsScreen();
        }
    }

    /**
     * The 1.7.10 mods page (asked for by players): opened from the options screen as a player would,
     * a mod found by searching and selected, its configuration opened, and Escape back to the list.
     */
    private static void legacyModsScreen() {
        do_("mods page: options", 10, () -> Minecraft.getInstance().setScreen(new net.minecraft.client.gui.screens.OptionsScreen(null, Minecraft.getInstance().options)));
        do_("mods page: open", 10, () -> press("1.7.10 Mods..."));
        do_("mods page: search", 5, () -> {
            for (var child : Minecraft.getInstance().screen.children()) {
                if (child instanceof net.minecraft.client.gui.components.EditBox search) {
                    search.setValue("iron");
                }
            }
        });
        do_("mods page: select", 5, () -> Minecraft.getInstance().screen.mouseClicked(20, 40, 0)); // the first mod in the list
        do_("mods page: listed", 2, () -> {
            boolean shown = Minecraft.getInstance().screen instanceof retrofit.forge.client.LegacyModsScreen;
            result("1.7.10 mods page opens from the options screen", shown, String.valueOf(Minecraft.getInstance().screen));
            grab("play-mods-page");
        });
        do_("mods page: config", 10, () -> press("Config"));
        do_("mods page: config shown", 2, () -> {
            var screen = Minecraft.getInstance().screen;
            result("a 1.7.10 mod's configuration opens from it", screen instanceof retrofit.forge.client.LegacyPlainScreen, String.valueOf(screen));
            grab("play-mods-config");
        });
        do_("mods page: escape", 10, () -> Minecraft.getInstance().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0));
        do_("mods page: back", 0, () -> {
            var screen = Minecraft.getInstance().screen;
            result("closing it goes back to the mods page", screen instanceof retrofit.forge.client.LegacyModsScreen, String.valueOf(screen));
            grab("play-mods-back");
            Minecraft.getInstance().setScreen(null);
        });
    }

    /** Presses the button of the current screen with this text. */
    private static void press(String text) {
        for (var child : Minecraft.getInstance().screen.children()) {
            if (child instanceof net.minecraft.client.gui.components.Button button && button.getMessage().getString().equals(text)) {
                button.onPress();
                return;
            }
        }
        LOGGER.info("[PlayTest] No button {} on {}", text, Minecraft.getInstance().screen);
    }

    /**
     * ManaMetal, as players reported it (0.5.1): its own tooltips (drawn by its coremod's renderer,
     * with icons from its font pages), the food decay bars under the hotbar's items, its scythes'
     * projectiles (its own particles) and its instance dungeons (built straight into chunk storage).
     */
    private static void manaMetal() {
        Item sickle = findItem("manametalmod", path -> path.contains("initial_sickle"), Item.class);
        newSite("ManaMetal");
        do_("manametal: tooltips", 5, () -> {
            boolean modTooltips = retrofit.forge.world.LegacyBridge.<Boolean>call("modTooltips");
            int vertices;
            synchronized (retrofit.forge.world.LegacyBridge.renderLock()) {
                vertices = retrofit.forge.world.LegacyBridge.call("drawTooltip", retrofit.forge.world.LegacyStacks.toLegacy(new ItemStack(sickle)),
                        100, 100, 800, 450, 1600, 900, 2);
            }
            boolean icon;
            try {
                java.lang.reflect.Method fontSet = net.minecraft.client.gui.Font.class.getDeclaredMethod("getFontSet", ResourceLocation.class);
                fontSet.setAccessible(true);
                Object glyphs = fontSet.invoke(Minecraft.getInstance().font, net.minecraft.network.chat.Style.DEFAULT_FONT);
                icon = ((net.minecraft.client.gui.font.FontSet) glyphs).getGlyphInfo(0xE00D, false)
                        != net.minecraft.client.gui.font.glyphs.SpecialGlyphs.MISSING;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            result("ManaMetal draws its own tooltips, with the icons of its font pages", modTooltips && vertices > 0 && icon,
                    "coremod tooltips " + modTooltips + ", " + vertices + " vertices, U+E00D " + (icon ? "has a glyph" : "missing"));
        });
        do_("manametal: food", 10, () -> server(player -> {
            player.getInventory().setItem(0, new ItemStack(Items.BREAD, 4));
            player.getInventory().setItem(1, new ItemStack(sickle));
        }));
        do_("manametal: food decay under the hotbar", 0, () -> {
            var window = Minecraft.getInstance().getWindow();
            int before = -1;
            synchronized (retrofit.forge.world.LegacyBridge.renderLock()) {
                retrofit.forge.world.LegacyBridge.call("drawOverlay", 0f, window.getWidth(), window.getHeight(), (int) window.getGuiScale());
                for (Object marker : retrofit.forge.world.LegacyBridge.<Object[]>call("guiMarkers")) {
                    if ("hotbar".equals(((Object[]) marker)[1])) {
                        before = (Integer) ((Object[]) marker)[0];
                    }
                }
            }
            result("ManaMetal's food decay bars are drawn before the hotbar", before > 0, before + " vertices before the hotbar");
            grab("play-manametal-hotbar");
        });
        do_("manametal: scythe", 5, () -> {
            select(1);
            Minecraft.getInstance().player.setXRot(0);
        });
        do_("manametal: cast", 10, () -> KeyMapping.set(Minecraft.getInstance().options.keyUse.getKey(), true)); // it fires after 10 ticks held
        until("manametal: projectile flies", 100, () -> { // the server may be slow to fire it
            synchronized (retrofit.forge.world.LegacyBridge.renderLock()) {
                return retrofit.forge.world.LegacyBridge.<Integer>call("drawParticles", 0f) > 0;
            }
        });
        do_("manametal: projectile", 2, () -> {
            int vertices;
            int untextured = 0;
            synchronized (retrofit.forge.world.LegacyBridge.renderLock()) {
                vertices = retrofit.forge.world.LegacyBridge.call("drawParticles", 0f);
                try {
                    int[] states = retrofit.forge.client.LegacyCapture.states();
                    for (int v = 0; v < vertices; v++) {
                        untextured += (states[v] & retrofit.forge.client.LegacyCapture.UNTEXTURED) != 0 ? 1 : 0;
                    }
                } catch (Throwable e) {
                    throw new IllegalStateException(e);
                }
            }
            result("ManaMetal's scythe projectile draws its particles, textured", vertices > 0 && untextured == 0,
                    vertices + " particle vertices, " + untextured + " untextured");
            grab("play-manametal-projectile");
        });
        do_("manametal: projectile in flight", 6, () -> {
        });
        do_("manametal: projectile in flight shot", 0, () -> grab("play-manametal-projectile-flight"));
        do_("manametal: cast done", 5, () -> KeyMapping.set(Minecraft.getInstance().options.keyUse.getKey(), false));
        BlockPos dungeon = new BlockPos(-400, 40, -400); // 1.7.10's worlds end at y 256; the tower is 202 high
        int[][] placed = {null};
        do_("manametal: dungeon", 40, () -> server(player -> {
            player.teleportTo(player.serverLevel(), dungeon.getX() - 4, dungeon.getY() + 30, dungeon.getZ() - 4, -45, 35);
            placed[0] = retrofit.forge.world.LegacyBridge.call("testManaMetalDungeon", retrofit.forge.world.LegacyPlayers.mirror(player, false),
                    "InstanceDungeon/IllusoryTower", dungeon.getX(), dungeon.getY(), dungeon.getZ());
        }));
        do_("manametal: dungeon built?", 0, () -> server(player -> {
            int[] size = placed[0];
            int solid = 0;
            for (BlockPos pos : BlockPos.betweenClosed(dungeon, dungeon.offset(size[0] - 1, size[1] - 1, size[2] - 1))) {
                solid += player.level().getBlockState(pos).isAir() ? 0 : 1;
            }
            result("ManaMetal's dungeon template is built in full", solid >= size[3] * 95 / 100,
                    solid + " of the template's " + size[3] + " blocks (" + size[0] + "x" + size[1] + "x" + size[2] + ")");
        }));
        do_("manametal: dungeon shot", 5, () -> grab("play-manametal-dungeon"));
    }

    /**
     * Pistronics 2: a Camoublock takes the texture of the block it is right-clicked with (stone, and
     * 1.20.1's prismarine), its mechanic piston moves a block 1.7.10 never had as itself, and its
     * petrify arrows, shot from a vanilla bow, turn what they kill into a statue.
     */
    private static void pistronics() {
        Item camou = item("pistronics2:block_of_camou");
        Item piston = item("pistronics2:mechanic_piston");
        Item rod = item("pistronics2:rod_block");
        Item arrows = item("pistronics2:petrify_arrow");
        newSite("Pistronics");
        do_("pistronics: give", 10, () -> server(player -> {
            player.getInventory().setItem(0, new ItemStack(camou, 2));
            player.getInventory().setItem(1, new ItemStack(Items.STONE, 8));
            player.getInventory().setItem(2, new ItemStack(Items.PRISMARINE, 8));
            if (piston != null && rod != null) {
                player.getInventory().setItem(3, new ItemStack(piston));
                player.getInventory().setItem(6, new ItemStack(rod, 2));
            }
            if (arrows != null) {
                player.getInventory().setItem(4, new ItemStack(Items.BOW));
                player.getInventory().setItem(5, new ItemStack(arrows, 8));
            }
        }));
        do_("pistronics: place camoublock", 10, () -> {
            select(0);
            useOn(ground(2, 0), Direction.UP);
        });
        String[] before = new String[1];
        do_("pistronics: camou with stone", 20, () -> {
            before[0] = blockEntityTag(ground(2, 0).above());
            select(1);
            useOn(ground(2, 0).above(), Direction.WEST);
        });
        do_("pistronics: camou with prismarine", 20, () -> {
            select(2);
            useOn(ground(2, 0).above(), Direction.UP);
        });
        do_("pistronics: camouflaged?", 0, () -> {
            String after = blockEntityTag(ground(2, 0).above());
            int stone = serverPlayer().getInventory().getItem(1).getCount(), prismarine = serverPlayer().getInventory().getItem(2).getCount();
            result("pistronics camoublock takes stone and prismarine", after != null && !after.equals(before[0]) && stone == 8 && prismarine == 8,
                    "stone left " + stone + ", prismarine left " + prismarine + ", tile entity " + before[0] + " -> " + after);
        });
        shot("play-pistronics-camou", () -> ground(2, 0).above());
        Item slime = item("pistronics2:slimeblock");
        if (piston != null && rod != null && slime != null) {
            // the piston faces the player (west), fitted with a rod and with another behind it, a slime block in
            // front and 1.20.1's prismarine beyond, then powered: the blocks around a Pistronics machine as real
            // 1.7.10 leaves them
            java.util.function.Supplier<BlockPos> at = () -> ground(3, -2).above(); // (the site exists once the steps run)
            do_("pistronics: place the piston", 10, () -> {
                Minecraft.getInstance().player.setYRot(-90);
                select(3);
                useOn(at.get().below(), Direction.UP);
            });
            do_("pistronics: fit a rod", 10, () -> {
                select(6);
                useOn(at.get(), Direction.UP);
            });
            do_("pistronics: a rod behind", 10, () -> {
                Minecraft.getInstance().player.setYRot(-90);
                useOn(at.get().east().below(), Direction.UP);
            });
            do_("pistronics: a slime block in front", 10, () -> {
                server(player -> player.getInventory().setItem(7, new ItemStack(slime)));
                select(7);
                useOn(at.get().west().below(), Direction.UP);
            });
            do_("pistronics: prismarine beyond", 10, () -> server(player ->
                    player.serverLevel().setBlock(at.get().west(2), Blocks.PRISMARINE.defaultBlockState(), 3)));
            do_("pistronics: power the piston", 80, () -> server(player ->
                    player.serverLevel().setBlock(at.get().north(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3)));
            // Real 1.7.10 (the reference mod's PistonReference, the same scene with stone for the prismarine) ends with
            // nothing moved: east to west air, the rod, the piston, the slime block, the stone, air.
            String[] expected = {"minecraft:air", "pistronics2:rod_block", "pistronics2:mechanic_piston", "pistronics2:slimeblock",
                    "minecraft:prismarine", "minecraft:air", "minecraft:air"};
            do_("pistronics: as on 1.7.10?", 0, () -> {
                StringBuilder row = new StringBuilder();
                boolean same = true;
                for (int dx = 2; dx >= -4; dx--) {
                    String block = String.valueOf(ForgeRegistries.BLOCKS.getKey(serverBlock(at.get().offset(dx, 0, 0))));
                    same &= block.equals(expected[2 - dx]);
                    row.append(dx).append(' ').append(block).append("; ");
                }
                result("pistronics piston does what it does on 1.7.10, 1.20.1's prismarine kept as itself", same, "east to west: " + row);
            });
            shot("play-pistronics-piston", at);
        }
        if (arrows != null) {
            java.util.function.Supplier<BlockPos> target = () -> ground(6, 4).above(); // clear of the blocks above
            do_("pistronics: a weak zombie", 10, () -> server(player -> {
                var zombie = net.minecraft.world.entity.EntityType.ZOMBIE.create(player.serverLevel());
                zombie.moveTo(target.get().getX() + 0.5, target.get().getY(), target.get().getZ() + 0.5, 90, 0);
                zombie.setNoAi(true);
                zombie.setHealth(1);
                // or the sun sets it alight, and with 1 health it burns to death before the arrow arrives
                zombie.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
                player.serverLevel().addFreshEntity(zombie);
            }));
            do_("pistronics: draw the bow", 25, () -> {
                var player = Minecraft.getInstance().player;
                Vec3 to = Vec3.atCenterOf(target.get()).subtract(player.getEyePosition());
                player.setYRot((float) (Math.toDegrees(Math.atan2(to.z, to.x)) - 90));
                player.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z))) - 2); // a little high: the arrow drops
                select(4);
                Minecraft.getInstance().options.keyUse.setDown(true); // held, or the client lets go at once
                Minecraft.getInstance().gameMode.useItem(player, InteractionHand.MAIN_HAND);
            });
            do_("pistronics: loose", 40, () -> Minecraft.getInstance().options.keyUse.setDown(false)); // the client's own release
            do_("pistronics: petrified?", 0, () -> {
                Block statue = block("pistronics2:statue");
                BlockPos found = null;
                for (BlockPos pos : BlockPos.betweenClosed(target.get().offset(-2, -1, -2), target.get().offset(2, 1, 2))) {
                    if (statue != null && serverBlock(pos) == statue) {
                        found = pos.immutable();
                    }
                }
                result("pistronics petrify arrow from a vanilla bow makes a statue", found != null,
                        "statue at " + found + ", arrows left " + serverPlayer().getInventory().getItem(5).getCount());
            });
            shot("play-pistronics-statue", target);
        }
    }

    /**
     * Legacy items whose names show as their keys ("tile.foo.name", "item.bar"), as a player sees them:
     * each item's own stack and its first subtypes. A report by mod, with examples, not a pass/fail.
     */
    private static void untranslatedNames() {
        java.util.regex.Pattern key = java.util.regex.Pattern.compile("^((tile|item|block|entity)\\.\\S+|\\S+\\.name)$");
        Map<String, List<String>> raw = new java.util.TreeMap<>();
        int checked = 0;
        for (Item item : ForgeRegistries.ITEMS) {
            if (!(item instanceof retrofit.forge.world.LegacyStackItem legacy) || legacy.legacyItem() == null) {
                continue;
            }
            List<ItemStack> stacks = new ArrayList<>(List.of(new ItemStack(item)));
            try {
                int[] subtypes = retrofit.forge.world.LegacyBridge.call("itemSubtypes", legacy.legacyItem());
                for (int i = 0; subtypes != null && i < Math.min(4, subtypes.length); i++) {
                    stacks.add(legacy.stackOf(subtypes[i]));
                }
            } catch (RuntimeException e) {
                // the item's own stack only
            }
            for (ItemStack stack : stacks) {
                String name;
                try {
                    name = stack.getHoverName().getString();
                } catch (RuntimeException e) {
                    name = stack.getDescriptionId();
                }
                checked++;
                if (name.equals(stack.getDescriptionId()) || key.matcher(name).matches()) {
                    List<String> examples = raw.computeIfAbsent(ForgeRegistries.ITEMS.getKey(item).getNamespace(), mod -> new ArrayList<>());
                    examples.add(name);
                }
            }
        }
        String dump = System.getProperty("retrofit.nameDump"); // as the reference mod writes it in real 1.7.10
        if (dump != null) {
            List<String> lines = new ArrayList<>();
            for (Item item : ForgeRegistries.ITEMS) {
                if (item instanceof retrofit.forge.world.LegacyStackItem legacy && legacy.legacyItem() != null) {
                    ItemStack stack = legacy.stackOf(0);
                    try {
                        Object legacyStack = retrofit.forge.world.LegacyStacks.toLegacy(stack);
                        Object[] read = legacyStack == null ? null : retrofit.forge.world.LegacyBridge.readStack(legacyStack);
                        if (read != null) {
                            lines.add(read[0] + "\t0\t" + stack.getHoverName().getString());
                        }
                    } catch (RuntimeException e) {
                        // not dumped
                    }
                }
            }
            try {
                Files.write(Path.of(dump), lines, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.warn("[PlayTest] Could not write the name dump", e);
            }
        }
        StringBuilder report = new StringBuilder();
        raw.forEach((mod, names) -> report.append("\n  ").append(mod).append(": ").append(names.size()).append(", e.g. ")
                .append(String.join(", ", names.subList(0, Math.min(3, names.size())))));
        LOGGER.info("[PlayTest] Untranslated names: {} of {} legacy stacks{}", raw.values().stream().mapToInt(List::size).sum(), checked, report);
    }

    /**
     * The Titans keeps its creatures in net.minecraft.entity.* packages, which Retrofit once took for
     * vanilla ones (they got no 1.20.1 type, and their eggs failed): one of its minions' spawn eggs,
     * used on the ground, spawns the minion.
     */
    private static void titansEgg() {
        Item found = findItem("thetitans", path -> path.endsWith("_spawn_egg") && path.contains("loyalist"), net.minecraft.world.item.SpawnEggItem.class);
        Item egg = found != null ? found : findItem("thetitans", path -> path.endsWith("_spawn_egg"), net.minecraft.world.item.SpawnEggItem.class);
        net.minecraft.world.entity.EntityType<?> type = ((net.minecraft.world.item.SpawnEggItem) egg).getType(null);
        newSite("The Titans");
        do_("titans: give the egg", 10, () -> server(player -> player.getInventory().setItem(0, new ItemStack(egg))));
        do_("titans: use the egg", 40, () -> {
            select(0);
            useOn(ground(2, 0), Direction.UP);
        });
        do_("titans: spawned?", 0, () -> {
            java.util.List<? extends Entity> spawned = serverPlayer().serverLevel().getEntities(type,
                    new net.minecraft.world.phys.AABB(ground(2, 0)).inflate(6), Entity::isAlive);
            result("the titans spawn egg spawns its creature (" + ForgeRegistries.ENTITY_TYPES.getKey(type) + ")", !spawned.isEmpty(),
                    spawned.isEmpty() ? "none near the egg, eggs left " + serverPlayer().getInventory().getItem(0).getCount()
                            : "at " + spawned.get(0).blockPosition());
        });
        shot("play-titans-egg", () -> ground(2, 0).above());
    }

    /**
     * 1.7.10's boss bar (BossStatus, set by the boss's renderer) over a titan in view, alone and
     * with a second one (players saw a bar only with two or more bosses around).
     */
    private static void titansBossBar() {
        net.minecraft.world.entity.EntityType<?> type = entityType("thetitans", path -> path.contains("zombietitan"));
        if (type == null) {
            return;
        }
        List<java.util.UUID> titans = bossArena("Titans boss bar");
        for (int count = 1; count <= 2; count++) {
            spawnBoss("titans boss bar", type, count, titans, boss -> { });
            faceNearestBoss("titans boss bar", type, "play-titans-bossbar-" + count);
            int near = count;
            do_("titans boss bar: " + count + " shown?", 0, () -> result("the boss bar shows with " + near + " titan(s) in view",
                    retrofit.forge.client.LegacyBossBar.shown() != null, "bar: " + retrofit.forge.client.LegacyBossBar.shown()));
        }
        removeBosses("titans boss bar", titans);
    }

    /**
     * OreSpawn's boss bar (its HUD names the creature the player points at, found by its own ray
     * through the client's entities, 16 blocks ahead): Mobzilla in view, alone and with a second one
     * (players saw a bar only with two or more around).
     */
    private static void oreSpawnBossBar() {
        net.minecraft.world.entity.EntityType<?> type = entityType("orespawn", path -> path.equals("mobzilla"));
        if (type == null) {
            return;
        }
        List<java.util.UUID> bosses = bossArena("OreSpawn boss bar");
        for (int count = 1; count <= 2; count++) {
            spawnBoss("orespawn boss bar", type, count, bosses, boss -> boss.setSecondsOnFire(30));
            faceNearestBoss("orespawn boss bar", type, "play-orespawn-bossbar-" + count);
            int near = count;
            do_("orespawn boss bar: " + count + " pointed at?", 0, () -> {
                String seen = retrofit.forge.world.LegacyBridge.call("testClientPointedAt", "danger.orespawn.OreSpawnMain", "getPointedAtEntity", 16.0);
                result("OreSpawn's HUD finds the Mobzilla looked at with " + near + " around", seen.startsWith("Godzilla"), seen);
            });
        }
        removeBosses("orespawn boss bar", bosses);
    }

    private static net.minecraft.world.entity.EntityType<?> entityType(String namespace, java.util.function.Predicate<String> path) {
        return ForgeRegistries.ENTITY_TYPES.getValues().stream().filter(candidate -> {
            ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(candidate);
            return id.getNamespace().equals(namespace) && path.test(id.getPath().toLowerCase(java.util.Locale.ROOT));
        }).findFirst().orElse(null);
    }

    /**
     * A site for big bosses: a bedrock floor they stand on and can't break (they walk off the usual
     * patch, and Mobzilla breaks what it walks through), and nothing else around to look at. Returns
     * the list the bosses spawned there go in.
     */
    private static List<java.util.UUID> bossArena(String name) {
        newSite(name);
        do_(name + ": arena", 20, () -> server(player -> {
            ServerLevel level = player.serverLevel();
            for (BlockPos pos : BlockPos.betweenClosed(site.offset(-8, 0, -24), site.offset(40, 0, 40))) {
                level.setBlock(pos, Blocks.BEDROCK.defaultBlockState(), 2);
            }
            level.getEntities((Entity) null, new net.minecraft.world.phys.AABB(site).inflate(96),
                    entity -> !(entity instanceof net.minecraft.world.entity.player.Player)).forEach(Entity::discard);
        }));
        return new java.util.ArrayList<>();
    }

    /** The count'th boss, 14 blocks east of the site (the second 16 blocks south of the first). */
    private static void spawnBoss(String name, net.minecraft.world.entity.EntityType<?> type, int count, List<java.util.UUID> bosses,
                                  Consumer<Entity> setup) {
        int dz = (count - 1) * 16;
        do_(name + ": boss " + count, 40, () -> server(player -> {
            Entity boss = type.create(player.serverLevel());
            boss.moveTo(site.getX() + 14.5, site.getY() + 1, site.getZ() + 0.5 + dz, 90, 0);
            setup.accept(boss);
            player.serverLevel().addFreshEntity(boss);
            bosses.add(boss.getUUID());
        }));
    }

    /** The player 6 blocks west of the nearest boss's side (it may walk off a few before the check), looking at its middle, then a screenshot. */
    private static void faceNearestBoss(String name, net.minecraft.world.entity.EntityType<?> type, String picture) {
        do_(name + ": step up", 10, () -> server(player -> player.serverLevel().getEntities(type, player.getBoundingBox().inflate(64), Entity::isAlive)
                .stream().min(java.util.Comparator.comparingDouble(boss -> boss.distanceToSqr(player))).ifPresent(boss ->
                        player.teleportTo(player.serverLevel(), boss.getBoundingBox().minX - 6, site.getY() + 1, boss.getZ(), -90, 0))));
        do_(name + ": look", 5, () -> {
            var player = Minecraft.getInstance().player;
            Minecraft.getInstance().level.getEntities((Entity) null, player.getBoundingBox().inflate(64), entity -> entity.getType() == type).stream()
                    .min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(player))).ifPresent(boss -> {
                        Vec3 to = boss.getBoundingBox().getCenter().subtract(player.getEyePosition());
                        player.setYRot((float) (Math.toDegrees(Math.atan2(to.z, to.x)) - 90));
                        player.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z))));
                    });
        });
        do_("screenshot " + picture, 5, () -> grab(picture));
    }

    private static void removeBosses(String name, List<java.util.UUID> bosses) {
        do_(name + ": remove", 20, () -> server(player -> bosses.forEach(id -> {
            Entity boss = player.serverLevel().getEntity(id);
            if (boss != null) {
                boss.discard();
            }
        })));
    }

    /** Places a Carpenter's Block, covers it with 1.20.1 oak planks, then places and hammers a slope. */
    private static void carpentersBlocks() {
        Item carpentersBlock = item("carpentersblocks:blockcarpentersblock");
        Item slope = item("carpentersblocks:blockcarpentersslope");
        Item hammer = item("carpentersblocks:itemcarpentershammer");
        String[] before = new String[1];
        newSite("Carpenter's Blocks");
        do_("carpenters: give", 10, () -> server(player -> {
            player.getInventory().setItem(0, new ItemStack(carpentersBlock, 4));
            player.getInventory().setItem(1, new ItemStack(Items.OAK_PLANKS, 8));
            if (slope != null) {
                player.getInventory().setItem(2, new ItemStack(slope, 2));
            }
            if (hammer != null) {
                player.getInventory().setItem(3, new ItemStack(hammer));
            }
        }));
        do_("carpenters: place", 10, () -> {
            select(0);
            useOn(ground(2, 0), Direction.UP);
        });
        do_("carpenters: placed?", 0, () -> {
            boolean placed = serverBlock(ground(2, 0).above()) == Block.byItem(carpentersBlock);
            before[0] = blockEntityTag(ground(2, 0).above());
            if (!placed) {
                result("carpenters place", false, "block above the ground is " + serverBlock(ground(2, 0).above()));
            }
        });
        do_("carpenters: cover", 20, () -> {
            select(1);
            useOn(ground(2, 0).above(), Direction.WEST);
        });
        do_("carpenters: covered?", 0, () -> {
            String after = blockEntityTag(ground(2, 0).above());
            int planks = serverPlayer().getInventory().getItem(1).getCount();
            result("carpenters cover with 1.20.1 planks", after != null && !after.equals(before[0]) && planks == 7,
                    "planks left " + planks + ", tile entity " + before[0] + " -> " + after);
        });
        shot("play-carpenters-cover", () -> ground(2, 0).above());
        // a block 1.7.10 never had: carried to legacy code as Retrofit's block carrier
        String[] plainBefore = new String[1];
        do_("carpenters: modern cover", 20, () -> {
            server(player -> player.getInventory().setItem(4, new ItemStack(Items.DEEPSLATE, 8)));
            select(0);
            useOn(ground(2, -2), Direction.UP);
        });
        do_("carpenters: cover with deepslate", 20, () -> {
            plainBefore[0] = blockEntityTag(ground(2, -2).above());
            select(4);
            useOn(ground(2, -2).above(), Direction.WEST);
        });
        do_("carpenters: deepslate covered?", 0, () -> {
            String after = blockEntityTag(ground(2, -2).above());
            int left = serverPlayer().getInventory().getItem(4).getCount();
            result("carpenters cover with 1.20.1-only deepslate", after != null && !after.equals(plainBefore[0]) && left == 7,
                    "deepslate left " + left + ", tile entity " + after);
        });
        shot("play-carpenters-deepslate", () -> ground(2, -2).above());
        if (slope != null) {
            do_("carpenters: slope", 20, () -> {
                select(2);
                useOn(ground(2, 2), Direction.UP);
            });
            do_("carpenters: slope placed?", 0, () -> result("carpenters place slope", serverBlock(ground(2, 2).above()) == Block.byItem(slope),
                    "block " + serverBlock(ground(2, 2).above())));
            if (hammer != null) {
                String[] slopeBefore = new String[1];
                do_("carpenters: take the hammer", 5, () -> {
                    slopeBefore[0] = blockEntityTag(ground(2, 2).above()) + serverState(ground(2, 2).above());
                    select(3);
                });
                do_("carpenters: hammer", 20, () -> {
                    Minecraft.getInstance().gameMode.startDestroyBlock(ground(2, 2).above(), Direction.WEST); // left click: the hammer turns the slope
                    Minecraft.getInstance().gameMode.stopDestroyBlock();
                });
                do_("carpenters: hammered?", 0, () -> {
                    String after = blockEntityTag(ground(2, 2).above()) + serverState(ground(2, 2).above());
                    result("carpenters hammer turns slope", !after.equals(slopeBefore[0]) && serverBlock(ground(2, 2).above()) == Block.byItem(slope),
                            slopeBefore[0] + " -> " + after);
                });
            }
            shot("play-carpenters-slope", () -> ground(2, 2).above());
        }
    }

    /** A full set of a mod's armor, worn; seen from the front. */
    private static void wornArmor(String namespace, String material) {
        newSite(namespace + " armor");
        do_(namespace + " armor: wear", 10, () -> server(player -> {
            for (Item item : ForgeRegistries.ITEMS) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                if (id.getNamespace().equals(namespace) && id.getPath().contains(material) && item instanceof ArmorItem armor) {
                    player.setItemSlot(armor.getEquipmentSlot(), new ItemStack(item));
                }
            }
        }));
        do_(namespace + " armor: textures", 0, () -> {
            Minecraft minecraft = Minecraft.getInstance();
            List<String> textures = new ArrayList<>();
            boolean all = true;
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                ItemStack worn = minecraft.player.getItemBySlot(slot);
                if (worn.getItem() instanceof ArmorItem armor) {
                    String texture = armor.getArmorTexture(worn, minecraft.player, slot, null);
                    boolean found = texture != null && minecraft.getResourceManager().getResource(new ResourceLocation(texture)).isPresent();
                    all &= found;
                    textures.add(slot.getName() + "=" + texture + (found ? "" : " (missing)"));
                } else {
                    all = false;
                    textures.add(slot.getName() + " empty");
                }
            }
            result(namespace + " armor textures", all, String.join(", ", textures));
            minecraft.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        });
        do_(namespace + " armor: look", 10, () -> {
        });
        do_(namespace + " armor: screenshot", 5, () -> grab("play-" + namespace + "-armor"));
        do_(namespace + " armor: off", 5, () -> {
            Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
            server(player -> {
                for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                    player.setItemSlot(slot, ItemStack.EMPTY);
                }
            });
        });
    }

    /**
     * Mods' ores below y 0 (the deep pass of world generation): players found none in the deepslate
     * layer (0.5.2), which legacy ore generators saw as a block they don't replace. The test world is
     * flat, so a patch of four chunks gets a deepslate layer and the deep pass runs over it.
     */
    private static void deepOres() {
        net.minecraft.world.level.ChunkPos chunk = new net.minecraft.world.level.ChunkPos(-125, -125);
        do_("deep ores", 0, () -> server(player -> {
            ServerLevel level = player.server.overworld();
            BlockPos from = chunk.getWorldPosition().atY(level.getMinBuildHeight() + 1);
            BlockPos to = from.offset(31, -level.getMinBuildHeight() - 2, 31); // y -63 to -1
            for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
                level.setBlock(pos, Blocks.DEEPSLATE.defaultBlockState(), 2 | 16);
            }
            retrofit.forge.world.LegacyWorldGen.generateDeep(level, retrofit.forge.world.LegacyBridge.call("genWorld", 0), chunk);
            Map<String, Integer> ores = new java.util.TreeMap<>();
            for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
                BlockState state = level.getBlockState(pos);
                if (state.getBlock() instanceof retrofit.forge.world.LegacyBlock) {
                    ores.merge(ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString(), 1, Integer::sum);
                }
            }
            result("mods' ores generate in the deepslate layer below y 0", !ores.isEmpty(), ores.isEmpty() ? "none" : ores.toString());
        }));
    }

    /** A legacy creature in a cave below y 0 (1.7.10 worlds ended at 0): it must stay there, alive. */
    /**
     * Dimensions mods register while playing (Galacticraft's space stations, pocket and personal dimensions):
     * one of a mod's provider type and one of the overworld's (which has no dimension type of its own, so the
     * generic one), registered as mods register them, and the player sent to each as mods send them
     * (travelToDimension); each must have a 1.20.1 level the player arrives in. Then back home.
     */
    private static void runtimeDimensions() {
        Object[][] modDimensions = retrofit.forge.world.LegacyBridge.call("legacyDimensions");
        List<Integer> providerTypes = new ArrayList<>();
        if (modDimensions.length > 0) {
            providerTypes.add((Integer) modDimensions[0][0]);
        }
        providerTypes.add(0);
        for (int providerType : providerTypes) {
            int[] id = new int[1];
            do_("dimension made while playing (provider type " + providerType + "): register and go", 80, () -> server(player -> {
                id[0] = retrofit.forge.world.LegacyBridge.call("registerTestDimension", providerType);
                retrofit.forge.world.LegacyBridge.call("testTravel", retrofit.forge.world.LegacyPlayers.mirror(player, false), id[0]);
            }));
            do_("dimension made while playing (provider type " + providerType + "): arrived?", 0, () -> {
                String where = serverPlayer().level().dimension().location().toString();
                result("dimension a mod makes while playing (provider type " + providerType + ")",
                        where.equals(RetrofitMod.MOD_ID + ":legacy_dim_" + id[0]), "dimension " + id[0] + ", player in " + where);
            });
            if (providerType == 0) {
                generatedTileEntities();
            }
            do_("dimension made while playing: home", 60, () -> server(player ->
                    retrofit.forge.world.LegacyBridge.call("testTravel", retrofit.forge.world.LegacyPlayers.mirror(player, false), 0)));
        }
    }

    /**
     * Vanilla blocks a legacy world generator set up through their tile entities in a legacy dimension
     * (hello1710's: a chest with rubies and bread, a spawner of its golem and one of skeletons, every 8th
     * chunk; the play test's flat overworld decorates nothing): the 1.20.1 block
     * entities must hold what it put there. Before 0.5.2 they came out empty and pig spawners.
     */
    private static void generatedTileEntities() {
        do_("generated tile entities", 0, () -> server(player -> {
            List<String> found = new ArrayList<>();
            int chunkX = player.chunkPosition().x;
            int chunkZ = player.chunkPosition().z;
            for (int cx = (chunkX >> 3 << 3) - 8; cx <= (chunkX >> 3 << 3) + 8; cx += 8) {
                for (int cz = (chunkZ >> 3 << 3) - 8; cz <= (chunkZ >> 3 << 3) + 8; cz += 8) {
                    if (!player.serverLevel().hasChunk(cx, cz)) {
                        continue;
                    }
                    for (var blockEntity : player.serverLevel().getChunk(cx, cz).getBlockEntities().values()) {
                        if (blockEntity instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
                            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                                if (!chest.getItem(slot).isEmpty()) {
                                    found.add("chest " + slot + ": " + ForgeRegistries.ITEMS.getKey(chest.getItem(slot).getItem()) + " x" + chest.getItem(slot).getCount());
                                }
                            }
                        } else if (blockEntity instanceof net.minecraft.world.level.block.entity.SpawnerBlockEntity spawner) {
                            found.add("spawner " + spawner.getSpawner().save(new net.minecraft.nbt.CompoundTag())
                                    .getCompound("SpawnData").getCompound("entity").getString("id"));
                        }
                    }
                }
            }
            String all = String.join(", ", found);
            result("chest and spawners a legacy generator set up", all.contains("chest 0: hello1710:") && all.contains("chest 5: minecraft:bread x3")
                    && all.contains("spawner hello1710:") && all.contains("spawner minecraft:skeleton"), all);
        }));
    }

    /**
     * OreSpawn's Utopia, reached as OreSpawn sends players there (transferPlayerToDimension with its
     * OreSpawnTeleporter, which looks for the ground from y 180 down): the player must stand on it, not
     * at the bottom of the world, where it put them while the chunks there weren't loaded yet.
     */
    private static void oreSpawnDimension() {
        int[] utopia = {Integer.MIN_VALUE};
        do_("orespawn dimension: go", 100, () -> server(player -> {
            Object id = retrofit.forge.world.LegacyBridge.call("testModField", "danger.orespawn.OreSpawnMain", "DimensionID");
            if (id instanceof Integer own) {
                int dimension = retrofit.forge.world.LegacyBridge.<Integer>call("testRealDimension", "danger.orespawn.OreSpawnMain", own);
                utopia[0] = dimension;
                retrofit.forge.world.LegacyBridge.call("testTeleport", retrofit.forge.world.LegacyPlayers.mirror(player, false),
                        dimension, "danger.orespawn.OreSpawnTeleporter");
            }
        }));
        do_("orespawn dimension: on the ground?", 0, () -> {
            ServerPlayer player = serverPlayer();
            String where = player.level().dimension().location().toString();
            BlockPos feet = player.blockPosition();
            result("OreSpawn dimension reached through its teleporter, on the ground",
                    where.equals(RetrofitMod.MOD_ID + ":legacy_dim_" + utopia[0]) && feet.getY() > 10
                            && !player.level().getBlockState(feet.below()).isAir(),
                    "dimension " + utopia[0] + ", player in " + where + " at " + feet + " above " + player.level().getBlockState(feet.below()));
        });
        shot("play-orespawn-utopia", () -> serverPlayer().blockPosition());
        naturalSpawns("OreSpawn's Utopia");
        int[] utopiaFps = frameRate("orespawn utopia");
        // The Crystal dimension: players saw the frame rate drop wherever its crystal blocks were in view (0.5.1)
        int[] crystal = {Integer.MIN_VALUE};
        do_("orespawn crystal: go", 200, () -> server(player -> {
            Object id = retrofit.forge.world.LegacyBridge.call("testModField", "danger.orespawn.OreSpawnMain", "DimensionID5");
            if (id instanceof Integer own) {
                crystal[0] = retrofit.forge.world.LegacyBridge.<Integer>call("testRealDimension", "danger.orespawn.OreSpawnMain", own);
                retrofit.forge.world.LegacyBridge.call("testTeleport", retrofit.forge.world.LegacyPlayers.mirror(player, false),
                        crystal[0], "danger.orespawn.OreSpawnTeleporter");
            }
        }));
        int[] crystalFps = frameRate("orespawn crystal");
        do_("orespawn: structures in full", 0, () -> {
            long[] counts = retrofit.forge.world.LegacyDeferredBlocks.counts();
            result("structures reaching past a chunk's neighbours are built in full", counts[0] == 0 || counts[1] > 0,
                    counts[0] + " blocks placed past the generation window, " + counts[1] + " of them in their chunk since");
        });
        do_("orespawn crystal: frame rate", 0, () -> result("OreSpawn's Crystal dimension draws about as fast as Utopia",
                crystalFps[0] * 2 >= utopiaFps[0], crystalFps[0] + " fps in Crystal (dimension " + crystal[0] + "), " + utopiaFps[0] + " in Utopia"));
        shot("play-orespawn-crystal", () -> serverPlayer().blockPosition());
        do_("orespawn dimension: home", 60, () -> server(player ->
                retrofit.forge.world.LegacyBridge.call("testTravel", retrofit.forge.world.LegacyPlayers.mirror(player, false), 0)));
    }

    /**
     * Creatures spawning by themselves in the dimension the player is in, from the mod's own lists
     * (players found none in any mod dimension, 0.5.2): mob spawning on for a while, then the legacy
     * creatures around counted.
     */
    private static void naturalSpawns(String where) {
        do_(where + ": spawning on", 400, () -> server(player ->
                player.serverLevel().getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(true, player.server)));
        do_(where + ": creatures spawned?", 0, () -> server(player -> {
            player.serverLevel().getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, player.server);
            Map<String, Integer> found = new java.util.TreeMap<>();
            for (Entity entity : player.serverLevel().getEntities((Entity) null, player.getBoundingBox().inflate(128),
                    entity -> entity instanceof retrofit.forge.world.LegacyEntityCarrier && entity.getType().getCategory() != net.minecraft.world.entity.MobCategory.MISC)) {
                found.merge(ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()).toString(), 1, Integer::sum);
            }
            result(where + ": creatures spawn by themselves", !found.isEmpty(), found.isEmpty() ? "none within 128 blocks" : found.toString());
        }));
    }

    /** Index counts of the chunk sections drawn this frame, per chunk render type (dev names; what the GPU draws). */
    private static Map<String, Long> visibleIndices() {
        Map<String, Long> counts = new java.util.TreeMap<>();
        try {
            java.lang.reflect.Field inFrustum = net.minecraft.client.renderer.LevelRenderer.class.getDeclaredField("renderChunksInFrustum");
            inFrustum.setAccessible(true);
            java.lang.reflect.Field indexCount = com.mojang.blaze3d.vertex.VertexBuffer.class.getDeclaredField("indexCount");
            indexCount.setAccessible(true);
            for (Object info : (Iterable<?>) inFrustum.get(Minecraft.getInstance().levelRenderer)) {
                java.lang.reflect.Field chunkField = info.getClass().getDeclaredField("chunk");
                chunkField.setAccessible(true);
                var chunk = (net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk) chunkField.get(info);
                for (net.minecraft.client.renderer.RenderType type : net.minecraft.client.renderer.RenderType.chunkBufferLayers()) {
                    String name = type.toString();
                    counts.merge(name.substring(name.indexOf('[') + 1, name.indexOf(':')), (long) indexCount.getInt(chunk.getBuffer(type)), Long::sum);
                }
            }
        } catch (ReflectiveOperationException e) {
            counts.put("unavailable: " + e, 0L);
        }
        return counts;
    }

    /**
     * The frame rate over five seconds on the surface where the player is, looking out over it (the
     * lowest of the per-second counts, uncapped), with the chunk statistics logged.
     */
    private static int[] frameRate(String where) {
        int[] lowest = {Integer.MAX_VALUE};
        int[] limit = {0};
        do_(where + ": stand on top", 60, () -> {
            limit[0] = Minecraft.getInstance().options.framerateLimit().get();
            Minecraft.getInstance().options.framerateLimit().set(260); // unlimited
            server(player -> {
                BlockPos top = player.level().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, player.blockPosition());
                player.teleportTo(player.serverLevel(), top.getX() + 0.5, top.getY() + 1, top.getZ() + 0.5, 0, 15);
            });
        });
        for (int second = 0; second < 6; second++) {
            int n = second;
            do_(where + ": frames " + n, 20, () -> {
                Minecraft minecraft = Minecraft.getInstance();
                if (n > 0) { // the first second still counts frames from before
                    lowest[0] = Math.min(lowest[0], minecraft.getFps());
                }
                LOGGER.info("[PlayTest] {}: {} fps, {}, particles {}, {}, indices {}", where, minecraft.getFps(), minecraft.levelRenderer.getChunkStatistics(),
                        minecraft.particleEngine.countParticles(), minecraft.levelRenderer.getEntityStatistics(), visibleIndices());
            });
        }
        do_(where + ": frame limit back", 0, () -> Minecraft.getInstance().options.framerateLimit().set(limit[0]));
        return lowest;
    }

    /**
     * The Erebus, reached as mods send players (travelToDimension): its terrain must generate around the
     * player. With HBM loaded, Erebus's biome layers got HBM's view of the biome array while chunks generated
     * (0.5.1), and nothing came out past the error.
     */
    private static void erebusDimension() {
        int[] erebus = {Integer.MIN_VALUE};
        do_("erebus: go", 120, () -> server(player -> {
            Object id = retrofit.forge.world.LegacyBridge.call("testModField", "erebus.core.handler.configs.ConfigHandler", "INSTANCE.erebusDimensionID");
            if (id instanceof Integer own) {
                int dimension = retrofit.forge.world.LegacyBridge.<Integer>call("testRealDimension", "erebus.core.handler.configs.ConfigHandler", own);
                erebus[0] = dimension;
                retrofit.forge.world.LegacyBridge.call("testTeleport", retrofit.forge.world.LegacyPlayers.mirror(player, false),
                        dimension, "erebus.world.teleporter.TeleporterErebus");
            }
        }));
        do_("erebus: generated?", 0, () -> server(player -> {
            String where = player.level().dimension().location().toString();
            int solid = 0;
            int all = 0;
            BlockPos center = player.blockPosition();
            for (BlockPos pos : BlockPos.betweenClosed(center.offset(-16, -24, -16), center.offset(16, 24, 16))) {
                all++;
                solid += player.level().getBlockState(pos).isAir() ? 0 : 1;
            }
            boolean errors = retrofit.forge.world.LegacyErrors.reportedFor("legacy dimension " + erebus[0]);
            result("Erebus generates its terrain, and its teleporter puts the player in the open", where.equals(RetrofitMod.MOD_ID + ":legacy_dim_" + erebus[0])
                    && solid > all / 4 && !errors && standsFree(player),
                    "dimension " + erebus[0] + ", player in " + where + " at " + center + standing(player) + ", " + solid + " of " + all
                            + " blocks around not air" + (errors ? ", generation errors (see the log)" : ""));
        }));
        shot("play-erebus", () -> serverPlayer().blockPosition());
        naturalSpawns("Erebus");
        do_("erebus: home", 60, () -> server(player ->
                retrofit.forge.world.LegacyBridge.call("testTravel", retrofit.forge.world.LegacyPlayers.mirror(player, false), 0)));
    }

    /**
     * Atum's portal, walked into: the player must arrive in Atum and stay there, standing in the portal Atum's
     * teleporter builds on arrival. The portal waits for the player's portal cooldown, which it sets before
     * sending; the player's legacy copy in Atum didn't have it, and the player was sent straight back, again
     * and again (the screen flickered between "joining world" and "loading terrain").
     */
    private static void atumPortal() {
        int[] atum = {Integer.MIN_VALUE};
        do_("atum portal: step in", 0, () -> server(player -> {
            Object id = retrofit.forge.world.LegacyBridge.call("testModField", "com.teammetallurgy.atum.handler.AtumConfig", "DIMENSION_ID");
            atum[0] = id instanceof Integer dimension // Atum's own number, as Atum sees it; the game's may differ (see DimensionIds)
                    ? retrofit.forge.world.LegacyBridge.<Integer>call("testRealDimension", "com.teammetallurgy.atum.handler.AtumConfig", dimension)
                    : Integer.MIN_VALUE;
            BlockPos feet = player.blockPosition();
            player.serverLevel().setBlock(feet, findBlock("atum", path -> path.contains("portal")).defaultBlockState(), 2);
        }));
        until("atum portal: arrived", 400, () -> serverPlayer().level().dimension().location().toString()
                .equals(RetrofitMod.MOD_ID + ":legacy_dim_" + atum[0]));
        // placed once the arrival's chunks have generated (the server thread runs queued tasks while it waits for them)
        until("atum portal: placed", 600, () -> serverPlayer().getY() > 2);
        do_("atum portal: still there?", 60, () -> server(player -> {
            String where = player.level().dimension().location().toString();
            boolean errors = retrofit.forge.world.LegacyErrors.reportedFor("legacy dimension " + atum[0]);
            result("Atum's portal takes the player there, the player stays, in the open", where.equals(RetrofitMod.MOD_ID + ":legacy_dim_" + atum[0])
                    && standsFree(player) && !errors,
                    "dimension " + atum[0] + ", player in " + where + " at " + player.blockPosition() + standing(player)
                            + (errors ? ", generation errors (see the log)" : ""));
        }));
        shot("play-atum", () -> serverPlayer().blockPosition());
        do_("atum: home", 60, () -> server(player ->
                retrofit.forge.world.LegacyBridge.call("testTravel", retrofit.forge.world.LegacyPlayers.mirror(player, false), 0)));
    }

    /**
     * A Galacticraft space station, made as its recipe makes one (WorldUtil.bindSpaceStationToNewDimension: a
     * dimension registered while playing, the station built in it), and the player sent there as Galacticraft sends them.
     */
    private static void spaceStation() {
        int[] station = {Integer.MIN_VALUE};
        do_("space station: make and go", 100, () -> server(player -> {
            Object mirror = retrofit.forge.world.LegacyPlayers.mirror(player, false);
            java.util.Set<Integer> before = new java.util.HashSet<>();
            for (int id : retrofit.forge.world.LegacyBridge.<int[]>call("registeredDimensions")) {
                before.add(id);
            }
            retrofit.forge.world.LegacyBridge.call("testCallMod", "micdoodle8.mods.galacticraft.core.util.WorldUtil",
                    "bindSpaceStationToNewDimension", mirror, new Object[]{"WORLD", "PLAYER", 0});
            for (int id : retrofit.forge.world.LegacyBridge.<int[]>call("registeredDimensions")) {
                if (!before.contains(id)) {
                    station[0] = id;
                }
            }
            if (station[0] != Integer.MIN_VALUE) { // as Galacticraft sends players there (its teleporter puts them on the station)
                retrofit.forge.world.LegacyBridge.call("testCallMod", "micdoodle8.mods.galacticraft.core.util.WorldUtil",
                        "transferEntityToDimension", mirror, new Object[]{"PLAYER", station[0], "WORLD"});
            }
        }));
        do_("space station: arrived?", 0, () -> {
            ServerPlayer player = serverPlayer();
            String where = player.level().dimension().location().toString();
            int blocks = 0;
            for (BlockPos pos : BlockPos.betweenClosed(player.blockPosition().offset(-8, -6, -8), player.blockPosition().offset(8, 2, 8))) {
                blocks += player.level().getBlockState(pos).isAir() ? 0 : 1;
            }
            result("Galacticraft space station made and reached", station[0] != Integer.MIN_VALUE
                    && where.equals(RetrofitMod.MOD_ID + ":legacy_dim_" + station[0]) && blocks > 0,
                    "station dimension " + station[0] + ", player in " + where + " at " + player.blockPosition() + ", " + blocks + " blocks around");
        });
        shot("play-galacticraft-station", () -> serverPlayer().blockPosition());
        do_("space station: home", 60, () -> server(player ->
                retrofit.forge.world.LegacyBridge.call("testTravel", retrofit.forge.world.LegacyPlayers.mirror(player, false), 0)));
    }

    private static void deepCreature() {
        net.minecraft.world.entity.EntityType<?>[] type = new net.minecraft.world.entity.EntityType<?>[1];
        java.util.UUID[] id = new java.util.UUID[1];
        BlockPos cave = new BlockPos(-40, -40, 200);
        do_("deep creature: spawn", 60, () -> server(player -> {
            ServerLevel level = player.serverLevel();
            for (BlockPos pos : BlockPos.betweenClosed(cave.offset(-3, -1, -3), cave.offset(3, 4, 3))) {
                level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
            }
            for (BlockPos pos : BlockPos.betweenClosed(cave.offset(-2, 0, -2), cave.offset(2, 3, 2))) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            }
            // the player close by: 1.7.10 despawns monsters with no player within 128 blocks
            player.teleportTo(level, cave.getX() + 2.5, cave.getY(), cave.getZ() + 2.5, 135, 0);
            for (var candidate : ForgeRegistries.ENTITY_TYPES) {
                // a land creature (water creatures suffocate on land, as on 1.7.10)
                Entity made = ForgeRegistries.ENTITY_TYPES.getKey(candidate).getNamespace().equals("minecraft")
                        || candidate.getCategory() == net.minecraft.world.entity.MobCategory.WATER_CREATURE
                        || candidate.getCategory() == net.minecraft.world.entity.MobCategory.WATER_AMBIENT ? null : candidate.create(level);
                if (made instanceof retrofit.forge.world.LegacyMob) {
                    made.moveTo(cave.getX() + 0.5, cave.getY(), cave.getZ() + 0.5);
                    level.addFreshEntity(made);
                    type[0] = candidate;
                    id[0] = made.getUUID();
                    break;
                }
                if (made != null) {
                    made.discard();
                }
            }
        }));
        do_("deep creature: alive?", 0, () -> {
            if (type[0] == null) {
                return;
            }
            Entity[] found = new Entity[1];
            server(player -> found[0] = player.serverLevel().getEntity(id[0]));
            result("legacy creature below y 0 (" + ForgeRegistries.ENTITY_TYPES.getKey(type[0]) + ")", found[0] != null && found[0].isAlive(),
                    found[0] == null ? "gone" : "at " + found[0].blockPosition());
        });
    }

    /**
     * Thaumcraft: infused ores in a dark cave pocket (their crystals glow) and by a torch, an aura
     * node made by Thaumcraft's own world generator seen through Goggles of Revealing, and the
     * Thaumometer held in first person facing four ways.
     */
    private static void thaumcraft() {
        BlockPos cave = new BlockPos(-80, -40, 200);
        Block ore = block("thaumcraft:blockcustomore");
        do_("thaumcraft: cave", 20, () -> server(player -> {
            ServerLevel level = player.serverLevel();
            for (BlockPos pos : BlockPos.betweenClosed(cave.offset(-5, -1, -5), cave.offset(5, 5, 5))) {
                level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
            }
            for (BlockPos pos : BlockPos.betweenClosed(cave.offset(-4, 0, -4), cave.offset(4, 4, 4))) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            }
            for (int meta = 1; meta <= 6; meta++) { // the six infused ores, on the far wall
                level.setBlock(cave.offset(5, 1, meta - 4), ore.defaultBlockState().setValue(retrofit.forge.world.LegacyContent.META, meta), 2);
            }
            level.setBlock(cave.offset(4, 1, 2), Blocks.TORCH.defaultBlockState(), 2); // lights the southern ores
            player.getInventory().clearContent();
            player.teleportTo(level, cave.getX() + 0.5, cave.getY(), cave.getZ() + 0.5, -90, 10);
        }));
        do_("thaumcraft: ores screenshot", 5, () -> grab("play-thaumcraft-ores"));
        do_("thaumcraft: node", 20, () -> server(player -> {
            try {
                ClassLoader legacy = RetrofitMod.runtime().gameLoader();
                Class<?> world = Class.forName("net.minecraft.world.World", false, legacy);
                Class.forName("thaumcraft.common.lib.world.ThaumcraftWorldGenerator", true, legacy)
                        .getMethod("createRandomNodeAt", world, int.class, int.class, int.class, java.util.Random.class, boolean.class, boolean.class, boolean.class)
                        .invoke(null, retrofit.forge.world.LegacyContent.legacyWorld(player.serverLevel()), cave.getX() + 3, cave.getY() + 2, cave.getZ(),
                                new java.util.Random(1710), false, false, false);
            } catch (ReflectiveOperationException e) {
                LOGGER.error("[PlayTest] Could not make a Thaumcraft node", e);
            }
            player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(item("thaumcraft:itemgoggles")));
        }));
        do_("thaumcraft: node screenshot", 5, () -> {
            result("Thaumcraft node made", serverBlock(cave.offset(3, 2, 0)) == block("thaumcraft:blockairy"), "block " + serverBlock(cave.offset(3, 2, 0)));
            grab("play-thaumcraft-node");
        });
        float[] yaws = {-90, 0, 90, 180};
        for (int i = 0; i < yaws.length; i++) {
            float yaw = yaws[i];
            int n = i;
            do_("thaumcraft: thaumometer " + n, 15, () -> {
                server(player -> player.getInventory().setItem(0, new ItemStack(item("thaumcraft:itemthaumometer"))));
                select(0);
                Minecraft.getInstance().player.setYRot(yaw);
                Minecraft.getInstance().player.setXRot(10);
            });
            do_("thaumcraft: thaumometer shot " + n, 2, () -> grab("play-thaumcraft-thaumometer-" + n));
        }
        // scanning an ore with the thaumometer (held use): the aspects it gives fly to the screen's corner
        do_("thaumcraft: aim at an ore", 10, () -> {
            Minecraft.getInstance().player.setYRot(-90);
            Minecraft.getInstance().player.setXRot(8);
        });
        do_("thaumcraft: scan", 30, () -> KeyMapping.set(Minecraft.getInstance().options.keyUse.getKey(), true));
        do_("thaumcraft: scanning shot", 15, () -> grab("play-thaumcraft-scanning"));
        do_("thaumcraft: scan done", 4, () -> KeyMapping.set(Minecraft.getInstance().options.keyUse.getKey(), false));
        do_("thaumcraft: scanned shot", 2, () -> grab("play-thaumcraft-scanned"));
        // the Thaumonomicon, all research known (as players test it): its research map clips its nodes to
        // the page by depth (GL_GEQUAL then GL_LEQUAL), and its tooltips stand in front of the nodes
        do_("thaumcraft: all research", 20, () -> server(player -> {
            player.server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(4),
                    "thaumcraft research " + player.getGameProfile().getName() + " all");
            player.getInventory().setItem(0, new ItemStack(item("thaumcraft:itemthaumonomicon")));
        }));
        do_("thaumcraft: open the book", 30, () -> {
            select(0);
            Minecraft.getInstance().gameMode.useItem(Minecraft.getInstance().player, InteractionHand.MAIN_HAND);
        });
        do_("thaumcraft: book shot", 2, () -> {
            result("the Thaumonomicon opens", Minecraft.getInstance().screen instanceof retrofit.forge.client.LegacyPlainScreen,
                    String.valueOf(Minecraft.getInstance().screen));
            grab("play-thaumcraft-thaumonomicon");
        });
        do_("thaumcraft: hover a node", 5, () -> {
            // the game's own cursor position, not the system mouse: the node left of the middle, 2 rows down
            Minecraft minecraft = Minecraft.getInstance();
            try {
                java.lang.reflect.Field x = net.minecraft.client.MouseHandler.class.getDeclaredField("xpos");
                java.lang.reflect.Field y = net.minecraft.client.MouseHandler.class.getDeclaredField("ypos");
                x.setAccessible(true);
                y.setAccessible(true);
                x.setDouble(minecraft.mouseHandler, minecraft.getWindow().getScreenWidth() * 0.509);
                y.setDouble(minecraft.mouseHandler, minecraft.getWindow().getScreenHeight() * 0.583);
            } catch (ReflectiveOperationException e) {
                LOGGER.warn("[PlayTest] could not place the cursor", e);
            }
        });
        do_("thaumcraft: tooltip shot", 2, () -> grab("play-thaumcraft-thaumonomicon-hover"));
        do_("thaumcraft: book closed", 5, () -> Minecraft.getInstance().setScreen(null));
        // a wand in first person: Thaumcraft's own item renderer places it (it sat in the wrong place)
        do_("thaumcraft: wand", 10, () -> server(player -> player.getInventory().setItem(0, new ItemStack(item("thaumcraft:wandcasting")))));
        do_("thaumcraft: wand shot", 10, () -> {
            Minecraft.getInstance().player.setXRot(0);
            grab("play-thaumcraft-wand");
        });
    }

    /** A FiskHeroes suit (its armor pieces, tagged with the hero), worn and seen from the front: its own 3D model must draw. */
    private static void heroSuit(String hero) {
        newSite("FiskHeroes suit");
        do_("suit: wear", 10, () -> server(player -> {
            for (Item item : ForgeRegistries.ITEMS) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                if (id.getNamespace().equals("fiskheroes") && id.getPath().startsWith("superhero_") && item instanceof ArmorItem armor) {
                    ItemStack stack = new ItemStack(item);
                    stack.getOrCreateTag().putString("HeroType", hero);
                    player.setItemSlot(armor.getEquipmentSlot(), stack);
                }
            }
            player.getInventory().setItem(0, ItemStack.EMPTY);
        }));
        do_("suit: look", 20, () -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT));
        do_("suit: screenshot", 5, () -> grab("play-fiskheroes-suit"));
        do_("suit: off", 5, () -> {
            Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
            server(player -> {
                for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                    player.setItemSlot(slot, ItemStack.EMPTY);
                }
            });
        });
    }

    /** IC2's electric jetpack, charged, worn; holding jump must lift the player well above a jump's height. */
    private static void jetpack() {
        Item jetpack = item("ic2:itemarmorjetpackelectric");
        double[] start = new double[1];
        double[] highest = new double[1];
        newSite("IC2 jetpack");
        do_("jetpack: wear", 10, () -> server(player -> {
            ItemStack stack = new ItemStack(jetpack);
            CompoundTag tag = stack.getOrCreateTag();
            tag.putDouble("charge", 30000.0);
            player.setItemSlot(EquipmentSlot.CHEST, stack);
        }));
        do_("jetpack: hold jump", 0, () -> {
            start[0] = highest[0] = Minecraft.getInstance().player.getY();
            KeyMapping.set(Minecraft.getInstance().options.keyJump.getKey(), true);
        });
        until("jetpack: rising", 60, () -> {
            highest[0] = Math.max(highest[0], Minecraft.getInstance().player.getY());
            return highest[0] - start[0] > 4;
        });
        do_("jetpack: release", 40, () -> {
            KeyMapping.set(Minecraft.getInstance().options.keyJump.getKey(), false);
            result("IC2 jetpack lifts the player", highest[0] - start[0] > 4, "rose " + String.format("%.2f", highest[0] - start[0]) + " blocks");
            server(player -> player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY));
        });
    }

    /** Galacticraft: a tier 1 rocket on a launch pad, fuelled, boarded, launched with jump; it must climb. */
    private static void rocket() {
        Item rocket = item("galacticraftcore:item.spaceship");
        Block pad = block("galacticraftcore:tile.landingpad");
        Entity[] ship = new Entity[1];
        double[] startY = new double[1];
        newSite("Galacticraft rocket");
        do_("rocket: pads", 20, () -> server(player -> {
            ServerLevel level = player.serverLevel();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.setBlock(ground(4 + dx, dz).above(), pad.defaultBlockState(), 3);
                }
            }
            player.setGameMode(GameType.CREATIVE); // creative players' rockets come fuelled
            ItemStack stack = new ItemStack(rocket);
            stack.getOrCreateTag().putInt("RocketFuel", 1000);
            player.getInventory().setItem(0, stack);
        }));
        do_("rocket: place", 20, () -> {
            select(0);
            useOn(ground(4, 0).above(), Direction.UP);
        });
        do_("rocket: find", 0, () -> {
            ship[0] = clientEntityNear(ground(4, 0).above(), entity -> ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()).getPath().contains("rocket")
                    || entity.getClass().getSimpleName().contains("Legacy"));
            result("Galacticraft rocket placed", ship[0] != null, ship[0] == null ? "no rocket entity near the pad"
                    : String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(ship[0].getType())));
        });
        shot("play-galacticraft-pad", () -> ground(4, 0).above());
        do_("rocket: board", 20, () -> {
            if (ship[0] != null) {
                Minecraft.getInstance().gameMode.interact(Minecraft.getInstance().player, ship[0], InteractionHand.MAIN_HAND);
            }
        });
        do_("rocket: launch", 5, () -> {
            boolean riding = Minecraft.getInstance().player.getVehicle() == ship[0] && ship[0] != null;
            result("Galacticraft rocket boarded", riding, "vehicle " + Minecraft.getInstance().player.getVehicle());
            // Inside, as on 1.7.10 (getMountedYOffset -0.9 from posY, yOffset 1.5 above its feet: the rider's feet 0.1 above the rocket's).
            double seat = riding ? Minecraft.getInstance().player.getY() - ship[0].getY() : Double.NaN;
            result("Galacticraft rocket rider sits inside", Math.abs(seat - 0.1) < 0.05, "rider's feet " + seat + " above the rocket's");
            startY[0] = ship[0] == null ? 0 : ship[0].getY();
            KeyMapping.set(Minecraft.getInstance().options.keyJump.getKey(), true);
        });
        do_("rocket: let go", 20, () -> KeyMapping.set(Minecraft.getInstance().options.keyJump.getKey(), false));
        // Galacticraft warns about the missing parachute first: launch is pressed again, as players do
        do_("rocket: launch again", 5, () -> KeyMapping.set(Minecraft.getInstance().options.keyJump.getKey(), true));
        do_("rocket: let go again", 0, () -> KeyMapping.set(Minecraft.getInstance().options.keyJump.getKey(), false));
        until("rocket: climbing", 20 * 40, () -> ship[0] != null && ship[0].getY() - startY[0] > 10);
        do_("rocket: result", 0, () -> {
            double climbed = ship[0] == null ? 0 : ship[0].getY() - startY[0];
            result("Galacticraft rocket launches", climbed > 10, "climbed " + String.format("%.1f", climbed) + " blocks");
        });
        do_("rocket: screenshot", 5, () -> grab("play-galacticraft-rocket"));
        do_("rocket: off", 20, () -> server(player -> {
            player.stopRiding();
            player.setGameMode(GameType.SURVIVAL);
            player.getAbilities().invulnerable = true; // (survival takes it away; the later scenarios have hostile mods' creatures)
            player.onUpdateAbilities();
            player.teleportTo(site.getX() + 0.5, site.getY() + 1, site.getZ() + 0.5);
        }));
    }

    /** A machine placed from its item and right-clicked: its GUI must open on the client. */
    private static void machineGui(String name, String itemId, int damage) {
        Item machine = item(itemId);
        newSite(name);
        do_(name + ": give", 10, () -> server(player -> player.getInventory().setItem(0,
                machine instanceof retrofit.forge.world.LegacyStackItem legacy ? legacy.stackOf(damage) : new ItemStack(machine))));
        do_(name + ": place", 70, () -> { // GregTech machines ignore clicks until they have ticked 50 times
            select(0);
            useOn(ground(2, 0), Direction.UP);
        });
        // machines turn their front to whoever placed them (icons chosen from the world, not the metadata)
        do_(name + ": placed", 2, () -> grab("play-placed-" + name.toLowerCase().replace(' ', '-')));
        do_(name + ": open", 20, () -> useOn(ground(2, 0).above(), Direction.WEST));
        do_(name + ": opened?", 5, () -> {
            Object screen = Minecraft.getInstance().screen;
            result(name + " GUI opens", screen instanceof retrofit.forge.client.LegacyScreen || screen instanceof retrofit.forge.client.LegacyPlainScreen,
                    "block " + serverBlock(ground(2, 0).above()) + ", screen " + (screen == null ? "none" : screen.getClass().getSimpleName()));
            grab("play-gui-" + name.toLowerCase().replace(' ', '-'));
        });
        do_(name + ": close", 5, () -> Minecraft.getInstance().setScreen(null));
    }

    /** Stacks in the hotbar, seen in the inventory screen (items drawn by their mods' own renderers too). */
    private static void inventoryShot(String name, List<ItemStack> stacks) {
        newSite(name);
        do_(name + ": give", 10, () -> server(player -> {
            for (int i = 0; i < stacks.size() && i < 36; i++) {
                player.getInventory().setItem(i, stacks.get(i).copy());
            }
        }));
        do_(name + ": open", 10, () -> Minecraft.getInstance().setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(Minecraft.getInstance().player)));
        do_(name + ": screenshot", 2, () -> grab("play-inventory-" + name));
        do_(name + ": close", 2, () -> Minecraft.getInstance().setScreen(null));
    }

    /** A mod's first items held one by one, in third person (their renderers draw them on the player). */
    private static void heldItems(String namespace) {
        newSite(namespace + " items");
        List<Item> items = new ArrayList<>();
        for (Item item : ForgeRegistries.ITEMS) {
            if (ForgeRegistries.ITEMS.getKey(item).getNamespace().equals(namespace) && items.size() < 6) {
                items.add(item);
            }
        }
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            int n = i;
            do_(namespace + " hold " + n, 10, () -> {
                server(player -> player.getInventory().setItem(0, new ItemStack(item)));
                select(0);
                Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            });
            do_(namespace + " shot " + n, 2, () -> grab("play-" + namespace + "-held-" + n));
        }
        do_(namespace + " items done", 2, () -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON));
    }

    /**
     * Items in the hand, first and third person, next to 1.20.1's own stone and sword as the
     * reference: legacy block items (drawn as 1.7.10 drew them as items) and items with their own
     * renderers, a few of each kind the loaded mods have.
     */
    private static void heldInHand() {
        List<ItemStack> stacks = new ArrayList<>(List.of(new ItemStack(Items.STONE), new ItemStack(Items.DIAMOND_SWORD)));
        int blocks = 0, rendered = 0;
        for (Item item : ForgeRegistries.ITEMS) {
            if (!(item instanceof retrofit.forge.world.LegacyStackItem legacy) || legacy.legacyItem() == null
                    || ForgeRegistries.ITEMS.getKey(item).getNamespace().equals("hello1710")) {
                continue;
            }
            boolean custom = net.minecraft.client.Minecraft.getInstance().getItemRenderer().getModel(new ItemStack(item), null, null, 0).isCustomRenderer();
            if (item instanceof retrofit.forge.world.LegacyBlockItem && blocks < 4 && (custom || blocks < 2)) {
                stacks.add(new ItemStack(item));
                blocks++;
            } else if (!(item instanceof retrofit.forge.world.LegacyBlockItem) && custom && rendered < 4) {
                stacks.add(new ItemStack(item));
                rendered++;
            }
        }
        newSite("held in hand");
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            int n = i;
            do_("hand: hold " + n, 10, () -> {
                server(player -> player.getInventory().setItem(0, stack.copy()));
                select(0);
                Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
                LOGGER.info("[PlayTest] hand {}: {}", n, ForgeRegistries.ITEMS.getKey(stack.getItem()));
            });
            do_("hand: first " + n, 2, () -> grab("play-hand-" + n + "-first"));
            do_("hand: turn " + n, 10, () -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT));
            do_("hand: third " + n, 2, () -> grab("play-hand-" + n + "-third"));
        }
        do_("hand: done", 2, () -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON));
    }

    /**
     * The scenes real 1.7.10 plays with the reference mod (testmods/reference1710), for putting the
     * two side by side: each listed item marked "hand" ("modid:name", damage and "hand", tab-separated, as 1.7.10 names it,
     * one per line) held in first person and from the front, after the hand has settled, then the
     * blocks of the item blocks placed in the world (see compareBlocks), then all of them in a gallery.
     * Shots: cmp-hand-N-first/third.png (N counting the list's items), cmp-blocks-N.png and cmp-gallery-N.png.
     */
    private static void compare(Path list) {
        List<ItemStack> stacks = new ArrayList<>();
        List<Boolean> held = new ArrayList<>(); // "hand" after the damage: shot in the hand too
        try {
            for (String line : Files.readAllLines(list)) {
                String[] parts = line.trim().split("\t"); // names may have spaces
                if (parts[0].isEmpty() || parts[0].startsWith("#")) {
                    continue;
                }
                Object legacy = retrofit.forge.world.LegacyBridge.makeStack(parts[0], 1, parts.length > 1 ? Integer.parseInt(parts[1]) : 0, null);
                ItemStack stack = legacy == null ? ItemStack.EMPTY : retrofit.forge.world.LegacyStacks.toModern(legacy);
                if (stack.isEmpty()) {
                    LOGGER.info("[PlayTest] No item {}", parts[0]);
                }
                stacks.add(stack);
                held.add(parts.length > 2 && parts[2].equals("hand"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        newSite("comparison");
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            int n = i;
            if (stack.isEmpty() || !held.get(i)) {
                continue;
            }
            do_("compare: hold " + n, COMPARE_SETTLE, () -> {
                server(player -> player.getInventory().setItem(0, stack.copy()));
                select(0);
                Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
                LOGGER.info("[PlayTest] hand {}: {}", n, ForgeRegistries.ITEMS.getKey(stack.getItem()));
            });
            do_("compare: first " + n, 2, () -> grab("cmp-hand-" + n + "-first"));
            do_("compare: turn " + n, COMPARE_SETTLE, () -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT));
            do_("compare: third " + n, 2, () -> grab("cmp-hand-" + n + "-third"));
        }
        compareBlocks(stacks);
        do_("compare: gallery", 0, () -> {
            Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
            Minecraft.getInstance().setScreen(ItemGallery.of(stacks, "cmp-gallery-"));
        });
        until("compare: gallery shot", 600, () -> Minecraft.getInstance().screen instanceof ItemGallery gallery && gallery.done());
    }

    /** Blocks per batch, as the reference's: a grid GRID_X deep (east of the player) and GRID_Z wide. */
    private static final int GRID_X = 5, GRID_Z = 7;
    /** Ticks for a batch of blocks: chunk meshes rebuilt, tile entities and their renderers running. */
    private static final int BLOCKS_SETTLE = 60;

    /**
     * The blocks of the listed item blocks, placed in front of the player as their items place them,
     * a batch of GRID_X * GRID_Z at a time, row by row from 3 blocks east (cmp-blocks-N.png).
     */
    private static void compareBlocks(List<ItemStack> stacks) {
        List<BlockState> states = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack.getItem() instanceof retrofit.forge.world.LegacyBlockItem item) {
                int meta = retrofit.forge.world.LegacyBridge.<Integer>call("itemBlockMeta", item.legacyItem(), item.meta(stack)) & 15;
                states.add(item.getBlock().defaultBlockState().setValue(retrofit.forge.world.LegacyContent.META, meta));
            } else if (stack.getItem() instanceof net.minecraft.world.item.BlockItem item) {
                states.add(item.getBlock().defaultBlockState()); // 1.20.1's own (stone): as 1.7.10's metadata 0
            }
        }
        int perBatch = GRID_X * GRID_Z;
        for (int start = 0; start < states.size(); start += perBatch) {
            List<BlockState> batch = states.subList(start, Math.min(states.size(), start + perBatch));
            int number = start / perBatch + 1;
            do_("compare: blocks " + number, BLOCKS_SETTLE, () -> {
                Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
                server(player -> {
                    ServerLevel level = player.serverLevel();
                    for (int x = 0; x < GRID_X; x++) {
                        for (int z = 0; z < GRID_Z; z++) {
                            for (int y = 1; y <= 4; y++) {
                                level.setBlock(ground(3 + x, -3 + z).above(y), Blocks.AIR.defaultBlockState(), 2);
                            }
                        }
                    }
                    for (int i = 0; i < batch.size(); i++) {
                        level.setBlock(ground(3 + i / GRID_Z, -3 + i % GRID_Z).above(), batch.get(i), 2); // as the reference: no neighbour updates
                    }
                });
            });
            do_("compare: blocks shot " + number, 2, () -> grab("cmp-blocks-" + number));
        }
    }

    /** Ticks after giving an item or turning the camera: the equip animation and the camera have settled (as the reference). */
    private static final int COMPARE_SETTLE = 25;

    // ---- Steps ----

    private static void do_(String what, int delay, Runnable action) {
        STEPS.add(new Step(what, action, delay, null, 0));
    }

    private static void until(String what, int timeout, BooleanSupplier condition) {
        STEPS.add(new Step(what, null, 0, condition, timeout));
    }

    /** The next scenario's patch: the player stands at its middle facing east (+x), on the flat world's ground. */
    private static void newSite(String name) {
        int index = sites++;
        do_("site: " + name, 20, () -> {
            Minecraft.getInstance().setScreen(null);
            site = new BlockPos(index * 16, 100, 200);
            server(player -> {
                ServerLevel level = player.serverLevel();
                for (BlockPos pos : BlockPos.betweenClosed(site.offset(-6, 1, -6), site.offset(8, 6, 8))) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                }
                for (BlockPos pos : BlockPos.betweenClosed(site.offset(-6, 0, -6), site.offset(8, 0, 8))) {
                    level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
                }
                player.getInventory().clearContent();
                player.teleportTo(level, site.getX() + 0.5, site.getY() + 1, site.getZ() + 0.5, -90, 35);
            });
            LOGGER.info("[PlayTest] Scenario: {} at {}", name, site);
        });
    }

    /** A ground block of the current site, dx east and dz south of the player's spot. */
    private static BlockPos ground(int dx, int dz) {
        return site.offset(dx, 0, dz);
    }

    private static void select(int slot) {
        Minecraft.getInstance().player.getInventory().selected = slot; // sent to the server before the next use
    }

    /** A right click on a block face, as the client's input handling makes it. */
    private static void useOn(BlockPos pos, Direction face) {
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        var result = minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
        LOGGER.info("[PlayTest] right click on {} {} ({} on the client) holding {}: {}", pos, face, minecraft.level.getBlockState(pos),
                minecraft.player.getMainHandItem(), result);
        if (!result.consumesAction()) {
            minecraft.gameMode.useItem(minecraft.player, InteractionHand.MAIN_HAND);
        }
        minecraft.player.swing(InteractionHand.MAIN_HAND);
    }

    /** Looks at a block from the player's spot and takes a screenshot. */
    private static void shot(String name, java.util.function.Supplier<BlockPos> at) {
        do_("look at " + name, 10, () -> {
            var player = Minecraft.getInstance().player;
            Vec3 eye = player.getEyePosition();
            Vec3 to = Vec3.atCenterOf(at.get()).subtract(eye);
            player.setYRot((float) (Math.toDegrees(Math.atan2(to.z, to.x)) - 90));
            player.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z))));
        });
        do_("screenshot " + name, 5, () -> grab(name));
    }

    private static void grab(String name) {
        Minecraft minecraft = Minecraft.getInstance();
        Screenshot.grab(minecraft.gameDirectory, name + ".png", minecraft.getMainRenderTarget(), message -> { });
    }

    private static void result(String name, boolean passed, String detail) {
        RESULTS.add(name + (passed ? " ok" : " FAILED"));
        LOGGER.info("[PlayTest] {}: {} ({})", name, passed ? "PASSED" : "FAILED", detail);
    }

    // ---- Server side (the integrated server's thread) ----

    private static void server(Consumer<ServerPlayer> action) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.executeBlocking(() -> action.accept(server.getPlayerList().getPlayer(Minecraft.getInstance().player.getUUID())));
    }

    private static ServerPlayer serverPlayer() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        return server.getPlayerList().getPlayer(Minecraft.getInstance().player.getUUID());
    }

    private static Block serverBlock(BlockPos pos) {
        Block[] block = new Block[1];
        server(player -> block[0] = player.serverLevel().getBlockState(pos).getBlock());
        return block[0];
    }

    private static String serverState(BlockPos pos) {
        String[] state = new String[1];
        server(player -> state[0] = player.serverLevel().getBlockState(pos).toString());
        return state[0];
    }

    private static String blockEntityTag(BlockPos pos) {
        String[] tag = new String[1];
        server(player -> {
            var blockEntity = player.serverLevel().getBlockEntity(pos);
            tag[0] = blockEntity == null ? null : blockEntity.saveWithoutMetadata().toString();
        });
        return tag[0];
    }

    private static Entity clientEntityNear(BlockPos pos, Predicate<Entity> wanted) {
        var player = Minecraft.getInstance().player;
        return Minecraft.getInstance().level.getEntities(player, new AABB(pos).inflate(3), wanted).stream()
                .min(Comparator.comparingDouble(entity -> entity.distanceToSqr(Vec3.atCenterOf(pos)))).orElse(null);
    }

    // ---- Registries ----

    private static Item item(String id) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        return item == null || item == Items.AIR ? null : item;
    }

    private static Block block(String id) {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
        return block == null || block == Blocks.AIR ? null : block;
    }

    private static Item findItem(String namespace, Predicate<String> path, Class<? extends Item> type) {
        for (Item item : ForgeRegistries.ITEMS) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id.getNamespace().equals(namespace) && path.test(id.getPath()) && type.isInstance(item)) {
                return item;
            }
        }
        return null;
    }

    /** Whether the player stands where they are: ground below, feet and head not in a solid block. */
    private static boolean standsFree(ServerPlayer player) {
        BlockPos feet = player.blockPosition();
        return player.level().getBlockState(feet.below()).blocksMotion()
                && !player.level().getBlockState(feet).isSuffocating(player.level(), feet)
                && !player.level().getBlockState(feet.above()).isSuffocating(player.level(), feet.above());
    }

    private static String standing(ServerPlayer player) {
        BlockPos feet = player.blockPosition();
        return " (in " + player.level().getBlockState(feet) + " / " + player.level().getBlockState(feet.above())
                + ", on " + player.level().getBlockState(feet.below()) + ")";
    }

    private static Block findBlock(String namespace, Predicate<String> path) {
        for (Block block : ForgeRegistries.BLOCKS) {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
            if (id.getNamespace().equals(namespace) && path.test(id.getPath())) {
                return block;
            }
        }
        return null;
    }

    /** A new flat world, replacing the one from the last run. */
    private static void createWorld(Minecraft minecraft) {
        Path saves = minecraft.gameDirectory.toPath().resolve("saves").resolve(WORLD);
        Path screenshots = minecraft.gameDirectory.toPath().resolve("screenshots");
        try {
            if (Files.isDirectory(saves)) {
                try (Stream<Path> files = Files.walk(saves)) {
                    files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
                }
            }
            if (Files.isDirectory(screenshots)) {
                try (Stream<Path> files = Files.list(screenshots)) {
                    files.filter(path -> path.getFileName().toString().startsWith("play-")).forEach(path -> path.toFile().delete());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[PlayTest] Could not clear the previous run", e);
        }
        LOGGER.info("[PlayTest] Creating world {}", WORLD);
        LevelSettings settings = new LevelSettings(WORLD, GameType.SURVIVAL, false, Difficulty.NORMAL, true, new GameRules(),
                WorldDataConfiguration.DEFAULT);
        minecraft.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(1710L, false, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value()
                        .createWorldDimensions());
    }
}
