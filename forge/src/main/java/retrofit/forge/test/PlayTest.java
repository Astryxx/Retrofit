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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
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
        String dump = System.getProperty("retrofit.galleryDump");
        if (dump != null) {
            do_("gallery dump", 0, () -> ItemGallery.dump(dump));
        }
        if (item("carpentersblocks:blockcarpentersblock") != null) {
            carpentersBlocks();
        }
        if (findItem("orespawn", path -> path.contains("ruby"), ArmorItem.class) != null) {
            wornArmor("orespawn", "ruby");
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
        if (item("thaumcraft:itemthaumometer") != null) {
            thaumcraft();
        }
        if (findItem("fiskheroes", path -> path.startsWith("superhero_"), ArmorItem.class) != null) {
            heroSuit("fiskheroes:arrow");
        }
        if (findItem("fiskheroes", path -> true, Item.class) != null) {
            heldItems("fiskheroes");
        }
        heldInHand();
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

    /** A legacy creature in a cave below y 0 (1.7.10 worlds ended at 0): it must stay there, alive. */
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
        do_("rocket: board", 20, () -> {
            if (ship[0] != null) {
                Minecraft.getInstance().gameMode.interact(Minecraft.getInstance().player, ship[0], InteractionHand.MAIN_HAND);
            }
        });
        do_("rocket: launch", 5, () -> {
            boolean riding = Minecraft.getInstance().player.getVehicle() == ship[0] && ship[0] != null;
            result("Galacticraft rocket boarded", riding, "vehicle " + Minecraft.getInstance().player.getVehicle());
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
