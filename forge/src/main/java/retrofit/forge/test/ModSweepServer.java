package retrofit.forge.test;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import retrofit.forge.RetrofitMod;
import retrofit.forge.world.LegacyArmorItem;
import retrofit.forge.world.LegacyBlock;
import retrofit.forge.world.LegacyBlockEntity;
import retrofit.forge.world.LegacyBlockItem;
import retrofit.forge.world.LegacyBridge;
import retrofit.forge.world.LegacyStackItem;
import retrofit.forge.world.LegacyContent;
import retrofit.forge.world.LegacyEntityTypes;
import retrofit.forge.world.LegacyItem;
import retrofit.forge.world.LegacyMenu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Server half of the mod sweep (-Dretrofit.modSweep=true, Gradle: -PmodSweep), for testing real
 * 1.7.10 mods: every legacy block variant placed and ticked, every machine right-clicked, every
 * legacy entity spawned, ticked and killed, every legacy item ticked in the inventory, and fresh
 * chunks generated. Results are logged per mod as "[ModSweep]" lines; legacy failures are in the
 * log as Retrofit's "Legacy ... failed" reports. The client half (ModSweepClient) creates the
 * world, takes screenshots and quits.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID)
public final class ModSweepServer {
    public static final boolean ENABLED = Boolean.getBoolean("retrofit.modSweep");
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FLOOR_Y = 200; // above any terrain, below 1.7.10's build limit
    private static final int ROW = 32;
    private static final int MAX_ACTIVATIONS = 120;
    private static final int ACTIVATION_TICKS = 12;
    private static final int ITEM_BATCH_TICKS = 20;

    public enum Phase { WAITING, BLOCKS, ACTIVATE, PLACE, ENTITIES, PERF, ITEMS, ITEM_USE, WORLDGEN, DIMENSIONS, DONE }

    /** Read by the client half in singleplayer. */
    public static volatile Phase phase = Phase.WAITING;
    /** Increments when the server wants a screenshot of the current view (client half takes it). */
    public static volatile int screenshotRequest;
    public static volatile String screenshotName = "";

    private static int ticks;
    private static int phaseStart;
    private static BlockPos origin;
    private static final List<Placed> PLACED = new ArrayList<>();
    private static final List<Placed> MACHINES = new ArrayList<>();
    private static int activation;
    private static int guisOpened;
    private static final Map<String, int[]> GUIS_BY_MOD = new TreeMap<>();
    private static final List<Entity> SPAWNED = new ArrayList<>();
    private static final List<ItemStack> ITEMS = new ArrayList<>();
    private static int itemBatch;
    private static int killTick = -1;
    private static int interactIndex;
    private static int interacted;
    private static int mounted;
    private static int ridingTicks;
    private static Entity ridden;
    private static net.minecraft.world.phys.Vec3 rideStart;
    private static List<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>> dimensions = List.of();
    private static int worldGenReadyTick = -1;

    private record Placed(BlockPos pos, LegacyBlock block, int meta, String mod) {
    }

    private ModSweepServer() {
    }

    /** -Dretrofit.perf: after placing every block and spawning every creature, measure server ticks instead of the rest of the sweep. */
    public static final boolean PERF = Boolean.getBoolean("retrofit.perf");
    private static long tickStart;
    private static final List<Long> TICK_NANOS = new ArrayList<>();

    @SubscribeEvent
    public static void onServerTickStart(TickEvent.ServerTickEvent event) {
        if (ENABLED && event.phase == TickEvent.Phase.START) {
            tickStart = System.nanoTime();
        }
    }

    /** Tick times of the window just measured: mean, median, 95th percentile, max (ms). */
    private static String tickReport() {
        List<Long> sorted = new ArrayList<>(TICK_NANOS);
        java.util.Collections.sort(sorted);
        TICK_NANOS.clear();
        if (sorted.isEmpty()) {
            return "no ticks";
        }
        double mean = sorted.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
        return String.format(java.util.Locale.ROOT, "%d ticks: mean %.2f ms, median %.2f, p95 %.2f, max %.2f", sorted.size(), mean,
                sorted.get(sorted.size() / 2) / 1e6, sorted.get((int) (sorted.size() * 0.95)) / 1e6, sorted.get(sorted.size() - 1) / 1e6);
    }

    private static boolean measuring;
    private static jdk.jfr.Recording profile;

    /** A Flight Recorder profile of the measured window: run/perf.jfr. */
    private static void startProfile() {
        try {
            profile = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
            profile.start();
        } catch (Exception e) {
            LOGGER.info("[ModSweep] No profile: {}", e.toString());
        }
    }

    private static void stopProfile() {
        if (profile == null) {
            return;
        }
        try {
            profile.stop();
            profile.dump(java.nio.file.Path.of("perf.jfr"));
            LOGGER.info("[ModSweep] Profile written to perf.jfr");
        } catch (Exception e) {
            LOGGER.info("[ModSweep] Profile not written: {}", e.toString());
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (ENABLED && measuring && event.phase == TickEvent.Phase.END && tickStart > 0) {
            TICK_NANOS.add(System.nanoTime() - tickStart);
        }
        if (!ENABLED || event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server.getPlayerList().getPlayers().isEmpty()) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        ServerLevel level = player.serverLevel();
        ticks++;
        int inPhase = ticks - phaseStart;
        try {
            switch (phase) {
                case WAITING -> {
                    if (ticks == 40) {
                        prepare(server, player, level);
                        placeBlocks(level);
                        enter(Phase.BLOCKS);
                    }
                }
                case BLOCKS -> {
                    if (inPhase == 30) {
                        // The middle of the first rows of the grid (cells are 2 blocks apart).
                        view(player, origin.offset(Math.min(PLACED.size(), ROW), 0, Math.min(PLACED.size() / ROW, 4)), 14, 55);
                    } else if (inPhase == 60) {
                        requestScreenshot("sweep-blocks");
                    } else if (PERF && inPhase == 80) {
                        measuring = true;
                    } else if (inPhase == 200) {
                        if (PERF) {
                            measuring = false;
                            LOGGER.info("[ModSweep] Perf with {} legacy blocks placed: {}", PLACED.size(), tickReport());
                        }
                        reportBlocks(level);
                        checkHostInventories(level);
                        checkEnergy(level);
                        checkCreatures(level);
                        checkVillagerTrades(level);
                        checkWoodTypes();
                        checkThrownPotion(level);
                        enter(Phase.ACTIVATE);
                    }
                }
                case ACTIVATE -> {
                    if (inPhase % ACTIVATION_TICKS == 0) {
                        if (activation < Math.min(MAX_ACTIVATIONS, MACHINES.size())) {
                            activate(player, level, MACHINES.get(activation++));
                        } else {
                            player.closeContainer();
                            LOGGER.info("[ModSweep] GUIs: {} of {} machines opened a GUI; by mod {}", guisOpened, activation,
                                    GUIS_BY_MOD.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()[0] + "/" + e.getValue()[1]).toList());
                            preparePlacements(level);
                            enter(Phase.PLACE);
                        }
                    }
                }
                case PLACE -> {
                    // All placed first (2 ticks apart), then each right-clicked 10 ticks apart once they're
                    // 3 seconds old (GregTech ignores clicks on machines younger than 50 ticks).
                    if (placement < PLACEMENTS.size()) {
                        if (inPhase % 2 == 0) {
                            placeNext(player, level);
                        }
                    } else if (openStart < 0) {
                        openStart = inPhase + 60;
                    } else if (inPhase >= openStart && (inPhase - openStart) % 10 == 0 && !openNext(player, level)) {
                        enter(Phase.ENTITIES);
                    }
                }
                case ENTITIES -> {
                    if (inPhase == 1) {
                        spawnEntities(player, level);
                    } else if (inPhase == 90) {
                        checkLeash(player);
                    } else if (inPhase == 99 && leashed != null) {
                        LOGGER.info("[ModSweep] Leash on the 1.20.1 creature: {}", leashed.isLeashed() && leashed.getLeashHolder() == player ? "OK" : "FAILED");
                        player.teleportTo(leashFrom.x, leashFrom.y, leashFrom.z);
                    } else if (inPhase == 100) {
                        requestScreenshot("sweep-entities");
                        if (PERF) {
                            enter(Phase.PERF);
                        }
                    } else if (inPhase > 100 && killTick < 0) {
                        interactStep(player, level, inPhase);
                    } else if (killTick >= 0 && inPhase == killTick) {
                        killEntities(player, level);
                    } else if (killTick >= 0 && inPhase == killTick + 60) {
                        reportDeaths();
                        collectItems();
                        // Survival, standing on the block grid's floor, so the client shows the plain inventory.
                        player.setGameMode(GameType.SURVIVAL);
                        player.getAbilities().invulnerable = true; // some items hurt whoever carries them
                        player.onUpdateAbilities();
                        player.teleportTo(level, origin.getX() + 1.5, origin.getY() + 1, origin.getZ() + 1.5, 0f, 20f); // beside the first block, on its floor patch
                        enter(Phase.ITEMS);
                    }
                }
                case PERF -> {
                    // Blocks placed, machines placed from items, creatures alive: 30 seconds measured after 10 to settle.
                    if (inPhase == 200) {
                        measuring = true;
                        startProfile();
                    } else if (inPhase == 800) {
                        measuring = false;
                        stopProfile();
                        int entities = 0;
                        for (Entity ignored : level.getAllEntities()) {
                            entities++;
                        }
                        LOGGER.info("[ModSweep] Perf with {} legacy blocks and the creatures: {} ({} entities in the level)",
                                PLACED.size(), tickReport(), entities);
                        enter(Phase.DONE);
                    }
                }
                case ITEMS -> {
                    if (inPhase % ITEM_BATCH_TICKS == 1) {
                        if (!giveItemBatch(player)) {
                            player.getInventory().clearContent();
                            LOGGER.info("[ModSweep] Items: {} legacy item stacks ticked in the inventory", ITEMS.size());
                            checkItemEnergy();
                            enter(Phase.ITEM_USE);
                        }
                    }
                }
                case ITEM_USE -> {
                    // Every item right-clicked in the air once (wands, spell books, food, brooms...), still invulnerable.
                    if (inPhase % 2 == 0) {
                        int index = inPhase / 2;
                        if (index < ITEMS.size()) {
                            useItem(player, level, ITEMS.get(index));
                        } else if (index == ITEMS.size()) {
                            player.getInventory().clearContent();
                            player.stopRiding();
                            player.setGameMode(GameType.CREATIVE);
                            player.getAbilities().flying = true;
                            player.onUpdateAbilities();
                            LOGGER.info("[ModSweep] Item use: {} items right-clicked, {} started being used, {} put the player on something",
                                    ITEMS.size(), itemsUsing, itemsMounted);
                            generateChunks(player, level);
                            enter(Phase.WORLDGEN);
                        }
                    }
                }
                case WORLDGEN -> {
                    // Waits (up to a minute) for the 7x7 chunks around the player to finish generating.
                    if (worldGenReadyTick < 0 && inPhase % 20 == 0 && (readyChunks(player, level, 3) >= 49 || inPhase >= 1200)) {
                        worldGenReadyTick = inPhase;
                    }
                    if (worldGenReadyTick >= 0 && inPhase == worldGenReadyTick + 40) {
                        requestScreenshot("sweep-worldgen");
                    } else if (worldGenReadyTick >= 0 && inPhase == worldGenReadyTick + 60) {
                        reportWorldGen(player, level);
                        dimensions = server.levelKeys().stream()
                                .filter(key -> key.location().getNamespace().equals("retrofit") && key.location().getPath().startsWith("legacy_dim_"))
                                .toList();
                        LOGGER.info("[ModSweep] Legacy dimensions: {}", dimensions.stream().map(key -> key.location().toString()).toList());
                        enter(Phase.DIMENSIONS);
                    }
                }
                case DIMENSIONS -> {
                    // Each legacy dimension: travel there the way mods send players, look around, come back.
                    int step = inPhase % 300;
                    int index = inPhase / 300;
                    if (index >= dimensions.size()) {
                        if (step == 0) {
                            player.teleportTo(server.overworld(), origin.getX() + 0.5, origin.getY() + 3, origin.getZ() - 4, 0f, 30f);
                        } else if (step == 40) {
                            LOGGER.info("[ModSweep] DONE after {} ticks", ticks);
                            enter(Phase.DONE);
                        }
                    } else {
                        var key = dimensions.get(index);
                        int legacyId = Integer.parseInt(key.location().getPath().substring("legacy_dim_".length()));
                        if (step == 0) {
                            player.getAbilities().invulnerable = true;
                            player.onUpdateAbilities();
                            if (player.isDeadOrDying()) {
                                LOGGER.info("[ModSweep] The player died (in {}); respawning", player.level().dimension().location());
                                server.getPlayerList().respawn(player, false);
                            }
                        } else if (step == 1) {
                            Object mirror = retrofit.forge.world.LegacyPlayers.mirror(player, false);
                            LegacyBridge.call("testTravel", mirror, legacyId);
                        } else if (step == 200) {
                            requestScreenshot("sweep-dim" + legacyId);
                        } else if (step == 220) {
                            player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), -45f); // the sky
                        } else if (step == 240) {
                            requestScreenshot("sweep-dim" + legacyId + "-sky");
                        } else if (step == 260) {
                            reportDimension(player, key, legacyId);
                        }
                    }
                }
                case DONE -> {
                }
            }
        } catch (RuntimeException e) {
            LOGGER.error("[ModSweep] Step {} FAILED", phase, e);
            enter(Phase.values()[Math.min(phase.ordinal() + 1, Phase.DONE.ordinal())]);
        }
    }

    private static void enter(Phase next) {
        LOGGER.info("[ModSweep] Phase {} at tick {}", next, ticks);
        phase = next;
        phaseStart = ticks;
    }

    private static void requestScreenshot(String name) {
        screenshotName = name;
        screenshotRequest++;
    }

    /** Stands the player above a point, looking down at it. */
    private static void view(ServerPlayer player, BlockPos at, int height, float pitch) {
        double z = at.getZ() - height * 0.6;
        // Something to stand on: the client does not always keep flying across the teleport.
        player.serverLevel().setBlock(BlockPos.containing(at.getX() + 0.5, at.getY() + height - 1, z), Blocks.BARRIER.defaultBlockState(), 3);
        player.teleportTo(player.serverLevel(), at.getX() + 0.5, at.getY() + height, z, 0f, pitch);
        LOGGER.info("[ModSweep] View from {}", player.position());
    }

    private static void prepare(MinecraftServer server, ServerPlayer player, ServerLevel level) {
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        level.setDayTime(6000);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.setWeatherParameters(6000, 0, false, false);
        BlockPos spawn = level.getSharedSpawnPos();
        origin = new BlockPos(spawn.getX() - ROW, FLOOR_Y, spawn.getZ() + 8);
        LOGGER.info("[ModSweep] Mods: {}", modNames());
    }

    private static String modOf(ResourceLocation name) {
        return name == null ? "?" : name.getNamespace();
    }

    private static List<String> modNames() {
        java.util.Set<String> mods = new java.util.TreeSet<>();
        LegacyContent.blocks().forEach(b -> mods.add(modOf(ForgeRegistries.BLOCKS.getKey(b))));
        LegacyContent.items().forEach(i -> mods.add(modOf(ForgeRegistries.ITEMS.getKey(i))));
        return new ArrayList<>(mods);
    }

    private static int[] variants(LegacyBlock block) {
        if (block.asItem() instanceof LegacyBlockItem item && item.hasSubtypes()) {
            try {
                return java.util.Arrays.stream(LegacyBridge.itemSubtypes(item.legacyItem())).filter(m -> m >= 0 && m < 16).distinct().toArray();
            } catch (RuntimeException e) {
                return new int[]{0};
            }
        }
        return new int[]{0};
    }

    /** Every legacy block variant on a stone floor, one block apart, ROW per row. */
    private static void placeBlocks(ServerLevel level) {
        int index = 0;
        Map<String, int[]> perMod = new TreeMap<>(); // placed, failed
        for (LegacyBlock block : LegacyContent.blocks()) {
            String mod = modOf(ForgeRegistries.BLOCKS.getKey(block));
            if (mod.equals("hello1710")) {
                continue;
            }
            for (int meta : variants(block)) {
                BlockPos at = origin.offset((index % ROW) * 2, 1, (index / ROW) * 2);
                index++;
                for (int dx = 0; dx < 2; dx++) {
                    for (int dz = 0; dz < 2; dz++) {
                        level.setBlock(at.offset(dx, -1, dz), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                        for (int dy = 0; dy < 3; dy++) {
                            level.setBlock(at.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                }
                int[] counts = perMod.computeIfAbsent(mod, m -> new int[2]);
                try {
                    level.setBlock(at, block.defaultBlockState().setValue(LegacyContent.META, meta), 3);
                    Placed placed = new Placed(at, block, meta, mod);
                    PLACED.add(placed);
                    if (LegacyBridge.hasTileEntity(block.legacyBlock(), meta)) {
                        MACHINES.add(placed);
                    }
                    counts[0]++;
                } catch (RuntimeException e) {
                    counts[1]++;
                    LOGGER.error("[ModSweep] Placing {}:{} FAILED", ForgeRegistries.BLOCKS.getKey(block), meta, e);
                }
            }
        }
        LOGGER.info("[ModSweep] Blocks: placed {} variants; by mod (placed, failed) {}", PLACED.size(),
                perMod.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()[0] + "/" + e.getValue()[1]).toList());
    }

    /**
     * In the sweep, barrels store Forge Energy, standing in for a 1.20.1 energy mod's machine:
     * legacy code must see them as RF/EU machines.
     */
    @SubscribeEvent
    public static void onAttachCapabilities(net.minecraftforge.event.AttachCapabilitiesEvent<net.minecraft.world.level.block.entity.BlockEntity> event) {
        if (!ENABLED || !(event.getObject() instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity)) {
            return;
        }
        net.minecraftforge.energy.EnergyStorage storage = new net.minecraftforge.energy.EnergyStorage(100_000, 10_000, 10_000, 50_000);
        var optional = net.minecraftforge.common.util.LazyOptional.<net.minecraftforge.energy.IEnergyStorage>of(() -> storage);
        net.minecraftforge.fluids.capability.templates.FluidTank tank = new net.minecraftforge.fluids.capability.templates.FluidTank(16_000);
        var tankOptional = net.minecraftforge.common.util.LazyOptional.<net.minecraftforge.fluids.capability.IFluidHandler>of(() -> tank);
        event.addCapability(new ResourceLocation(RetrofitMod.MOD_ID, "sweep_energy"), new net.minecraftforge.common.capabilities.ICapabilityProvider() {
            @Override
            public <T> net.minecraftforge.common.util.LazyOptional<T> getCapability(net.minecraftforge.common.capabilities.Capability<T> capability, Direction side) {
                if (capability == net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER) {
                    return tankOptional.cast();
                }
                return capability == net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY ? optional.cast() : net.minecraftforge.common.util.LazyOptional.empty();
            }
        });
    }

    /**
     * Energy across versions: a 1.20.1 energy block (a barrel, see above) seen by legacy code as an
     * RF/EU machine, and every legacy machine of the installed mods offered Forge Energy.
     */
    private static void checkEnergy(ServerLevel level) {
        BlockPos at = origin.offset(-6, 1, -4);
        level.setBlock(at.below(), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        level.setBlock(at, Blocks.BARREL.defaultBlockState(), 3);
        var storage = level.getBlockEntity(at) == null ? null
                : level.getBlockEntity(at).getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY).orElse(null);
        if (storage == null) {
            LOGGER.error("[ModSweep] Energy FAILED: the test barrel has no Forge Energy");
            return;
        }
        try {
            String result = LegacyBridge.call("checkHostEnergy", LegacyContent.legacyWorld(level), at.getX(), at.getY(), at.getZ());
            LOGGER.info("[ModSweep] Energy, 1.20.1 block seen by legacy code: {}; it now stores {} FE", result, storage.getEnergyStored());
            String fluids = LegacyBridge.call("checkHostFluids", LegacyContent.legacyWorld(level), at.getX(), at.getY(), at.getZ());
            var tank = level.getBlockEntity(at).getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER).orElse(null);
            String held = tank == null ? "no tank" : tank.getFluidInTank(0).getAmount() + " mB of " + ForgeRegistries.FLUIDS.getKey(tank.getFluidInTank(0).getFluid());
            LOGGER.info("[ModSweep] Fluids, 1.20.1 tank seen by legacy code: {}; it now holds {}", fluids, held);
        } catch (RuntimeException e) {
            LOGGER.error("[ModSweep] Energy FAILED for the 1.20.1 block", e);
        }
        level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
        // Real 1.20.1 machines, when their mods are installed (-PmodernMods=...).
        for (String id : new String[]{"mekanism:basic_energy_cube", "mekanism:basic_fluid_tank", "mekanism:basic_universal_cable"}) {
            var block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
            if (block == null || block == Blocks.AIR) {
                continue;
            }
            level.setBlock(at, block.defaultBlockState(), 3);
            try {
                String energy = LegacyBridge.call("checkHostEnergy", LegacyContent.legacyWorld(level), at.getX(), at.getY(), at.getZ());
                String fluids = LegacyBridge.call("checkHostFluids", LegacyContent.legacyWorld(level), at.getX(), at.getY(), at.getZ());
                LOGGER.info("[ModSweep] 1.20.1 machine {} seen by legacy code: energy {}; fluids {}", id, energy, fluids);
            } catch (RuntimeException e) {
                LOGGER.error("[ModSweep] 1.20.1 machine {} FAILED in legacy code", id, e);
            }
            level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
        }
        long displays = level.getServer().getRecipeManager().getRecipes().stream()
                .filter(r -> r instanceof retrofit.forge.world.LegacyRecipeDisplays.Shaped || r instanceof retrofit.forge.world.LegacyRecipeDisplays.Shapeless).count();
        LOGGER.info("[ModSweep] Recipe book and viewers: {} legacy crafting recipes shown", displays);
        Map<String, int[]> tanks = new TreeMap<>(); // has tanks, took water
        for (Placed machine : MACHINES) {
            if (level.getBlockEntity(machine.pos) instanceof LegacyBlockEntity legacy) {
                var handler = legacy.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER, Direction.UP).orElse(null);
                if (handler != null) {
                    int[] counts = tanks.computeIfAbsent(machine.mod, m -> new int[2]);
                    counts[0]++;
                    if (handler.fill(new net.minecraftforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1000),
                            net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE) > 0) {
                        counts[1]++;
                    }
                }
            }
        }
        LOGGER.info("[ModSweep] Fluids, legacy tanks as 1.20.1 fluid handlers (tanks, took water): {}",
                tanks.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()[0] + "/" + e.getValue()[1]).toList());
        Map<String, int[]> perMod = new TreeMap<>(); // takes FE, took some, gives FE, gave some
        for (Placed machine : MACHINES) {
            if (!(level.getBlockEntity(machine.pos) instanceof LegacyBlockEntity legacy)) {
                continue;
            }
            for (Direction side : new Direction[]{null, Direction.UP, Direction.NORTH}) {
                var energy = legacy.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY, side).orElse(null);
                if (energy == null) {
                    continue;
                }
                int[] counts = perMod.computeIfAbsent(machine.mod, m -> new int[4]);
                if (energy.canReceive()) {
                    counts[0]++;
                    if (energy.receiveEnergy(1000, false) > 0) {
                        counts[1]++;
                    }
                }
                if (energy.canExtract()) {
                    counts[2]++;
                    if (energy.extractEnergy(1000, false) > 0) {
                        counts[3]++;
                    }
                }
                break;
            }
        }
        LOGGER.info("[ModSweep] Energy, legacy machines as Forge Energy (take, took, give, gave): {}",
                perMod.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()[0] + "/" + e.getValue()[1] + "/" + e.getValue()[2] + "/" + e.getValue()[3]).toList());
    }

    /** 1.20.1 creatures as legacy code sees them: found by entity queries, with their 1.7.10 classes, hurt by legacy damage. */
    private static void checkCreatures(ServerLevel level) {
        BlockPos at = origin.offset(-10, 1, -10);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(at.offset(dx, -1, dz), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
            }
        }
        var cow = EntityType.COW.create(level);
        var zombie = EntityType.ZOMBIE.create(level);
        if (cow == null || zombie == null) {
            return;
        }
        cow.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        zombie.moveTo(at.getX() + 1.5, at.getY(), at.getZ() + 0.5, 0, 0);
        zombie.setNoAi(true);
        level.addFreshEntity(cow);
        level.addFreshEntity(zombie);
        float before = cow.getHealth();
        try {
            String result = LegacyBridge.call("checkHostCreatures", LegacyContent.legacyWorld(level), at.getX(), at.getY(), at.getZ());
            boolean hurt = cow.getHealth() < before;
            if (result.startsWith("ok") && hurt) {
                LOGGER.info("[ModSweep] 1.20.1 creatures in legacy code: {}; the cow went from {} to {} health: OK", result, before, cow.getHealth());
            } else {
                LOGGER.error("[ModSweep] 1.20.1 creatures in legacy code FAILED: {}; cow health {} -> {}", result, before, cow.getHealth());
            }
        } catch (RuntimeException e) {
            LOGGER.error("[ModSweep] 1.20.1 creatures in legacy code FAILED", e);
        }
        cow.discard();
        zombie.discard();
    }

    /** 1.20.1 villagers of every profession (and a wandering trader) picking offers: how many are legacy mods' trades. */
    private static void checkVillagerTrades(ServerLevel level) {
        Map<String, Integer> legacyOffers = new TreeMap<>();
        Map<String, java.util.function.Supplier<net.minecraft.world.entity.npc.AbstractVillager>> traders = new TreeMap<>();
        for (var profession : ForgeRegistries.VILLAGER_PROFESSIONS) {
            if (profession != net.minecraft.world.entity.npc.VillagerProfession.NONE && profession != net.minecraft.world.entity.npc.VillagerProfession.NITWIT) {
                traders.put(profession.name(), () -> {
                    var villager = EntityType.VILLAGER.create(level);
                    villager.setVillagerData(villager.getVillagerData().setProfession(profession).setLevel(3));
                    return villager;
                });
            }
        }
        traders.put("wandering_trader", () -> EntityType.WANDERING_TRADER.create(level));
        traders.forEach((name, factory) -> {
            int count = 0;
            for (int round = 0; round < 8; round++) { // offers are random: several traders' worth
                var trader = factory.get();
                try {
                    trader.moveTo(origin.getX() + 0.5, origin.getY() + 1, origin.getZ() + 0.5, 0, 0);
                    for (var offer : trader.getOffers()) {
                        if (offer.getResult().getItem() instanceof LegacyStackItem || offer.getBaseCostA().getItem() instanceof LegacyStackItem
                                || offer.getCostB().getItem() instanceof LegacyStackItem) {
                            count++;
                        }
                    }
                } catch (RuntimeException e) {
                    LOGGER.error("[ModSweep] Villager trades FAILED for {}", name, e);
                }
                trader.discard();
            }
            if (count > 0) {
                legacyOffers.put(name, count);
            }
        });
        Map<Integer, Integer> legacyTrades = new TreeMap<>(); // what the handlers make, without 1.20.1's picking
        for (int profession : LegacyBridge.<int[]>call("tradeProfessions")) {
            Object[][] rows = LegacyBridge.call("villagerTrades", LegacyContent.legacyWorld(level), profession, 1L);
            legacyTrades.put(profession, rows.length);
        }
        var wandererPool = net.minecraft.world.entity.npc.VillagerTrades.WANDERING_TRADER_TRADES.get(1);
        long inPool = wandererPool == null ? 0 : java.util.Arrays.stream(wandererPool)
                .filter(listing -> listing.getClass().getName().startsWith("retrofit.")).count();
        LOGGER.info("[ModSweep] Villager trades: legacy handlers' trades per profession {}, legacy offers in 8 traders per profession {}, legacy listings among {} wandering trader ones: {}",
                legacyTrades, legacyOffers, wandererPool == null ? 0 : wandererPool.length, inPool);
    }

    /** A 1.7.10 splash potion (poison, damage 16388) legacy code throws arrives as a 1.20.1 one with its potion. */
    private static void checkThrownPotion(ServerLevel level) {
        double x = origin.getX() + 0.5, y = origin.getY() + 40, z = origin.getZ() + 0.5;
        boolean taken = LegacyBridge.call("testThrowPotion", LegacyContent.legacyWorld(level), x, y, z, 16388);
        var potions = level.getEntitiesOfClass(net.minecraft.world.entity.projectile.ThrownPotion.class, new net.minecraft.world.phys.AABB(x - 2, y - 2, z - 2, x + 2, y + 2, z + 2));
        String potion = potions.isEmpty() ? "none" : net.minecraft.world.item.alchemy.PotionUtils.getPotion(potions.get(0).getItem()).getName("");
        LOGGER.info("[ModSweep] Thrown legacy potion: taken {}, arrived as {} ({}): {}", taken, potion,
                potions.isEmpty() ? "-" : potions.get(0).getItem().save(new net.minecraft.nbt.CompoundTag()), potion.equals("poison") ? "OK" : "FAILED");
        potions.forEach(Entity::discard);
    }

    /** With Moonlight (Every Compat's library) installed: which wood types it found outside vanilla. */
    private static void checkWoodTypes() {
        try {
            Class<?> registry = Class.forName("net.mehvahdjukaar.moonlight.api.set.wood.WoodTypeRegistry");
            Object instance = registry.getField("INSTANCE").get(null);
            java.util.List<String> found = new java.util.ArrayList<>();
            for (Object type : (Iterable<?>) registry.getMethod("getValues").invoke(instance)) {
                String id = type.getClass().getMethod("getId").invoke(type).toString();
                if (!id.startsWith("minecraft:")) {
                    found.add(id);
                }
            }
            LOGGER.info("[ModSweep] Moonlight wood types outside vanilla: {}", found);
        } catch (ClassNotFoundException e) {
            // no Moonlight
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[ModSweep] Moonlight wood types not readable", e);
        }
    }

    /** Legacy electric items (RF, IC2) as Forge Energy: charged as a 1.20.1 charger would. */
    private static void checkItemEnergy() {
        Map<String, int[]> perMod = new TreeMap<>(); // energy items, charged
        for (ItemStack sample : ITEMS) {
            ItemStack stack = sample.copy();
            var energy = stack.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY).orElse(null);
            if (energy == null) {
                continue;
            }
            int[] counts = perMod.computeIfAbsent(modOf(ForgeRegistries.ITEMS.getKey(stack.getItem())), m -> new int[2]);
            counts[0]++;
            if (energy.canReceive() && energy.receiveEnergy(10_000, false) > 0) {
                counts[1]++;
            }
        }
        LOGGER.info("[ModSweep] Energy, legacy items as Forge Energy (energy items, charged): {}",
                perMod.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()[0] + "/" + e.getValue()[1]).toList());
    }

    /** Vanilla chests and furnaces as legacy code sees them (pipes, machines): read, take, put, change in place. */
    private static void checkHostInventories(ServerLevel level) {
        BlockPos at = origin.offset(-4, 1, -4);
        level.setBlock(at.below(), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        level.setBlock(at.east(), Blocks.FURNACE.defaultBlockState(), 3);
        if (!(level.getBlockEntity(at) instanceof net.minecraft.world.Container chest)) {
            LOGGER.error("[ModSweep] Host inventories FAILED: no chest placed");
            return;
        }
        chest.setItem(0, new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 10));
        try {
            String result = LegacyBridge.call("checkHostInventory", LegacyContent.legacyWorld(level), at.getX(), at.getY(), at.getZ());
            boolean ok = result.startsWith("ok") && chest.getItem(0).is(net.minecraft.world.item.Items.COBBLESTONE) && chest.getItem(0).getCount() == 5
                    && chest.getItem(1).is(net.minecraft.world.item.Items.DIAMOND) && chest.getItem(1).getCount() == 2;
            if (ok) {
                LOGGER.info("[ModSweep] Host inventories: {}; the chest now holds {} and {}: OK", result, chest.getItem(0), chest.getItem(1));
            } else {
                LOGGER.error("[ModSweep] Host inventories FAILED: {}; the chest holds {} and {}", result, chest.getItem(0), chest.getItem(1));
            }
        } catch (RuntimeException e) {
            LOGGER.error("[ModSweep] Host inventories FAILED", e);
        }
        chest.clearContent(); // no drops
        level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(at.east(), Blocks.AIR.defaultBlockState(), 3);
    }

    /** After ~8 seconds of ticking: which blocks are still there, and which tile entities exist. */
    private static void reportBlocks(ServerLevel level) {
        Map<String, int[]> perMod = new TreeMap<>(); // still there, gone, tile ok, tile missing
        for (Placed placed : PLACED) {
            int[] counts = perMod.computeIfAbsent(placed.mod, m -> new int[4]);
            BlockState state = level.getBlockState(placed.pos);
            counts[state.is(placed.block) ? 0 : 1]++;
            if (state.is(placed.block) && LegacyBridge.hasTileEntity(placed.block.legacyBlock(), placed.meta)) {
                boolean tile = level.getBlockEntity(placed.pos) instanceof LegacyBlockEntity legacy && legacy.legacyTile() != null;
                counts[tile ? 2 : 3]++;
            }
        }
        perMod.forEach((mod, c) -> LOGGER.info("[ModSweep] Blocks {}: {} still placed, {} changed or removed themselves; tile entities {} ok, {} missing",
                mod, c[0], c[1], c[2], c[3]));
    }

    /** Right-clicks a machine with an empty hand, as a player opening it would. */
    private static void activate(ServerPlayer player, ServerLevel level, Placed machine) {
        player.closeContainer();
        if (!level.getBlockState(machine.pos).is(machine.block)) {
            return;
        }
        player.teleportTo(level, machine.pos.getX() + 0.5, machine.pos.getY(), machine.pos.getZ() - 1.5, 0f, 30f);
        player.getInventory().setItem(player.getInventory().selected, ItemStack.EMPTY);
        player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(machine.pos).add(0, 0, -0.5), Direction.NORTH, machine.pos, false));
        boolean opened = player.containerMenu instanceof LegacyMenu || player.containerMenu != player.inventoryMenu;
        int[] counts = GUIS_BY_MOD.computeIfAbsent(machine.mod, m -> new int[2]);
        counts[1]++;
        if (opened) {
            guisOpened++;
            counts[0]++;
            LOGGER.info("[ModSweep] GUI {}:{} opened: {} with {} slots", ForgeRegistries.BLOCKS.getKey(machine.block), machine.meta,
                    player.containerMenu.getClass().getSimpleName(), player.containerMenu.slots.size());
        }
    }

    /** Machines to place from their items: {stack, mod}, up to 40 per mod, spread over each block's subtypes. */
    private static final List<Object[]> PLACEMENTS = new ArrayList<>();
    private static final Map<String, int[]> PLACED_BY_MOD = new TreeMap<>(); // placed, opened a GUI
    private static int placement;

    /**
     * Machines placed from their items, as a player places them: the mod's own ItemBlock runs
     * (GregTech's machines learn what they are there; a bare block is an empty hull), then a
     * right-click opens them.
     */
    private static void preparePlacements(ServerLevel level) {
        Map<String, Integer> perMod = new TreeMap<>();
        for (LegacyBlock block : LegacyContent.blocks()) {
            String mod = modOf(ForgeRegistries.BLOCKS.getKey(block));
            if (mod.equals("hello1710") || !(block.asItem() instanceof LegacyBlockItem item) || !item.hasSubtypes()) {
                continue;
            }
            boolean tiles = false;
            for (int meta = 0; meta < 16 && !tiles; meta++) {
                tiles = LegacyBridge.hasTileEntity(block.legacyBlock(), meta);
            }
            if (!tiles) {
                continue;
            }
            int[] subtypes = LegacyBridge.itemSubtypes(item.legacyItem());
            int step = Math.max(1, subtypes.length / 40); // spread over the whole list (GregTech: hulls first, machines later)
            for (int n = 0; n < subtypes.length && perMod.getOrDefault(mod, 0) < 40; n += step) {
                PLACEMENTS.add(new Object[]{item.stackOf(subtypes[n]), mod});
                perMod.merge(mod, 1, Integer::sum);
            }
        }
    }

    private static final List<BlockPos> PLACED_AT = new ArrayList<>();
    private static int openStart = -1;
    private static int opened;

    private static BlockPos placementFloor(int index) {
        return origin.offset(-40 + (index % 20) * 3, 0, -30 - (index / 20) * 3);
    }

    /** Places the next machine from its item. */
    private static void placeNext(ServerPlayer player, ServerLevel level) {
        int index = placement++;
        ItemStack stack = (ItemStack) PLACEMENTS.get(index)[0];
        int[] counts = PLACED_BY_MOD.computeIfAbsent((String) PLACEMENTS.get(index)[1], m -> new int[2]);
        BlockPos floor = placementFloor(index);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(floor.offset(dx, 0, dz), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int dy = 1; dy <= 3; dy++) {
                    level.setBlock(floor.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        BlockPos at = floor.above();
        player.teleportTo(level, at.getX() + 0.5, at.getY(), at.getZ() - 1.5, 0f, 30f);
        player.getInventory().setItem(player.getInventory().selected, stack.copy());
        try {
            player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false));
            if (!level.getBlockState(at).isAir()) {
                counts[0]++;
                PLACED_AT.add(at);
                if (counts[0] <= 3 && level.getBlockEntity(at) instanceof LegacyBlockEntity placedTile) {
                    Object tile = placedTile.legacyTile();
                    LOGGER.info("[ModSweep] Placed {} from its item: {}", stack.getHoverName().getString(),
                            tile == null ? "no legacy tile entity" : tile.getClass().getSimpleName() + " " + nbtOf(tile));
                }
            } else {
                PLACED_AT.add(null); // not placeable here
            }
        } catch (RuntimeException e) {
            PLACED_AT.add(null);
            LOGGER.error("[ModSweep] Placing {} from its item FAILED", stack, e);
        }
        player.getInventory().setItem(player.getInventory().selected, ItemStack.EMPTY);
    }

    private static String nbtOf(Object tile) {
        try {
            return net.minecraft.nbt.NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(LegacyBridge.save(tile)))).toString();
        } catch (java.io.IOException | RuntimeException e) {
            return "(unreadable: " + e + ")";
        }
    }

    /** Right-clicks the next placed machine; false when all are done. */
    private static boolean openNext(ServerPlayer player, ServerLevel level) {
        player.closeContainer();
        while (opened < PLACED_AT.size() && PLACED_AT.get(opened) == null) {
            opened++;
        }
        if (opened >= PLACED_AT.size()) {
            player.getInventory().clearContent();
            LOGGER.info("[ModSweep] Machines placed from their items (placed, opened a GUI): {}",
                    PLACED_BY_MOD.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()[0] + "/" + e.getValue()[1]).toList());
            return false;
        }
        int index = opened++;
        BlockPos at = PLACED_AT.get(index);
        ItemStack stack = (ItemStack) PLACEMENTS.get(index)[0];
        player.teleportTo(level, at.getX() + 0.5, at.getY(), at.getZ() - 1.5, 0f, 30f);
        player.getInventory().setItem(player.getInventory().selected, ItemStack.EMPTY);
        try {
            player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(at).add(0, 0, -0.5), Direction.NORTH, at, false));
            if (player.containerMenu != player.inventoryMenu) {
                PLACED_BY_MOD.get((String) PLACEMENTS.get(index)[1])[1]++;
                LOGGER.info("[ModSweep] Placed {} opened {}", stack.getHoverName().getString(), player.containerMenu.getClass().getSimpleName());
            }
        } catch (RuntimeException e) {
            LOGGER.error("[ModSweep] Opening {} FAILED", stack, e);
        }
        return true;
    }

    private static retrofit.forge.world.LegacyMob leashed;
    private static net.minecraft.world.phys.Vec3 leashFrom;

    /** A lead used on the first legacy creature that can be leashed, as a player would (1.20.1's interaction). */
    private static void checkLeash(ServerPlayer player) {
        for (Entity entity : SPAWNED) {
            if (!(entity instanceof retrofit.forge.world.LegacyMob mob) || !mob.isAlive()
                    || !LegacyBridge.<boolean[]>call("leashState", mob.core().legacy())[0]) {
                continue;
            }
            leashFrom = player.position();
            player.teleportTo(mob.getX() + 1.5, mob.getY(), mob.getZ() + 0.5); // 1.7.10 leashes break beyond 10 blocks
            ItemStack held = player.getMainHandItem();
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.LEAD));
            try {
                var result = player.interactOn(mob, net.minecraft.world.InteractionHand.MAIN_HAND);
                boolean[] state = LegacyBridge.call("leashState", mob.core().legacy());
                if (state[1] && state[2]) {
                    leashed = mob;
                    LOGGER.info("[ModSweep] Leash: {} leashed to the player ({}), lead used: {}: OK", LegacyEntityTypes.legacyName(mob.getType()), result,
                            player.getMainHandItem().isEmpty());
                } else {
                    LOGGER.error("[ModSweep] Leash FAILED: {} not leashed ({}, leashed {}, to a player {})", LegacyEntityTypes.legacyName(mob.getType()), result, state[1], state[2]);
                }
            } catch (RuntimeException e) {
                LOGGER.error("[ModSweep] Leash FAILED", e);
            }
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held);
            return;
        }
        LOGGER.info("[ModSweep] Leash: no legacy creature can be leashed");
    }

    /** Every legacy entity type on a fenced platform east of the block grid, 4 blocks apart. */
    private static void spawnEntities(ServerPlayer player, ServerLevel level) {
        Map<String, EntityType<?>> types = LegacyEntityTypes.all();
        int perRow = 12;
        int rows = (types.size() + perRow - 1) / perRow;
        BlockPos base = origin.offset(-60, 0, 0);
        for (int dx = -2; dx <= perRow * 4 + 1; dx++) {
            for (int dz = -2; dz <= rows * 4 + 1; dz++) {
                level.setBlock(base.offset(dx, 0, dz), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                boolean edge = dx == -2 || dz == -2 || dx == perRow * 4 + 1 || dz == rows * 4 + 1;
                for (int dy = 1; dy <= 4; dy++) {
                    level.setBlock(base.offset(dx, dy, dz), edge ? Blocks.GLASS.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        int index = 0;
        Map<String, int[]> perMod = new TreeMap<>(); // spawned, failed
        for (Map.Entry<String, EntityType<?>> entry : types.entrySet()) {
            String mod = modOf(ForgeRegistries.ENTITY_TYPES.getKey(entry.getValue()));
            if (mod.equals("hello1710")) {
                continue;
            }
            BlockPos at = base.offset((index % perRow) * 4, 1, (index / perRow) * 4);
            index++;
            int[] counts = perMod.computeIfAbsent(mod, m -> new int[2]);
            try {
                Entity entity = entry.getValue().create(level);
                if (entity == null) {
                    counts[1]++;
                    LOGGER.error("[ModSweep] Entity {} FAILED: could not be created", entry.getKey());
                    continue;
                }
                entity.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                if (level.addFreshEntity(entity)) {
                    SPAWNED.add(entity);
                    counts[0]++;
                } else {
                    counts[1]++;
                    LOGGER.error("[ModSweep] Entity {} FAILED: the level refused it", entry.getKey());
                }
            } catch (RuntimeException e) {
                counts[1]++;
                LOGGER.error("[ModSweep] Entity {} FAILED", entry.getKey(), e);
            }
        }
        LOGGER.info("[ModSweep] Entities: spawned {} of {} types; by mod (spawned, failed) {}", SPAWNED.size(), index,
                perMod.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()[0] + "/" + e.getValue()[1]).toList());
        view(player, base.offset(perRow * 2, 0, 0), 12, 45);
    }

    /**
     * Right-clicks each spawned entity with an empty hand, as a player would (taming, trading, mounting);
     * an entity that mounts the player is ridden forward for a second, to see the mod steer it.
     */
    private static void interactStep(ServerPlayer player, ServerLevel level, int inPhase) {
        if (ridingTicks > 0) {
            player.zza = 1.0f; // forward
            if (--ridingTicks == 0) {
                double moved = ridden.position().distanceTo(rideStart);
                LOGGER.info("[ModSweep] Rode {} forward: moved {} blocks{}", ForgeRegistries.ENTITY_TYPES.getKey(ridden.getType()),
                        String.format("%.1f", moved), player.getVehicle() == ridden ? "" : " (the player is no longer on it)");
                player.zza = 0;
                player.stopRiding();
            }
            return;
        }
        if (inPhase % 3 != 0) {
            return;
        }
        while (interactIndex < SPAWNED.size() && SPAWNED.get(interactIndex).isRemoved()) {
            interactIndex++;
        }
        if (interactIndex >= SPAWNED.size()) {
            LOGGER.info("[ModSweep] Entity right-clicks: {} entities, {} of them mounted the player", interacted, mounted);
            killTick = inPhase + 1;
            return;
        }
        Entity entity = SPAWNED.get(interactIndex++);
        player.teleportTo(level, entity.getX() + 1.5, entity.getY(), entity.getZ(), 90f, 20f);
        player.getInventory().setItem(player.getInventory().selected, ItemStack.EMPTY);
        net.minecraft.world.InteractionResult result = player.interactOn(entity, InteractionHand.MAIN_HAND);
        interacted++;
        if (player.getVehicle() == entity) {
            mounted++;
            ridden = entity;
            rideStart = entity.position();
            ridingTicks = 20;
            LOGGER.info("[ModSweep] {} mounted the player ({})", ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()), result);
        } else if (player.containerMenu != player.inventoryMenu) {
            LOGGER.info("[ModSweep] {} opened {}", ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()), player.containerMenu.getClass().getSimpleName());
            player.closeContainer();
        }
    }

    private static void killEntities(ServerPlayer player, ServerLevel level) {
        Map<String, int[]> perMod = new TreeMap<>(); // alive after 10 s, gone
        for (Entity entity : SPAWNED) {
            String mod = modOf(ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()));
            int[] counts = perMod.computeIfAbsent(mod, m -> new int[2]);
            if (entity.isRemoved()) {
                counts[1]++;
                LOGGER.info("[ModSweep] Entity {} was gone after 10 seconds ({})", ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()),
                        entity.getRemovalReason());
                continue;
            }
            counts[0]++;
            if (entity instanceof LivingEntity) {
                entity.hurt(level.damageSources().playerAttack(player), 10000);
            } else {
                entity.discard();
            }
        }
        LOGGER.info("[ModSweep] Entities after 10 seconds (alive, gone) {}",
                perMod.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()[0] + "/" + e.getValue()[1]).toList());
    }

    private static void reportDeaths() {
        int living = 0;
        int died = 0;
        for (Entity entity : SPAWNED) {
            if (entity instanceof LivingEntity) {
                living++;
                if (entity.isRemoved()) {
                    died++;
                } else {
                    LOGGER.info("[ModSweep] Entity {} survived a fatal hit (health {})", ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()),
                            ((LivingEntity) entity).getHealth());
                }
            }
        }
        LOGGER.info("[ModSweep] Entities: {} of {} living entities died and were removed", died, living);
        SPAWNED.forEach(Entity::discard);
    }

    private static void collectItems() {
        for (LegacyItem item : LegacyContent.items()) {
            if (modOf(ForgeRegistries.ITEMS.getKey(item)).equals("hello1710")) {
                continue;
            }
            int[] metas = new int[]{0};
            if (item.hasSubtypes()) {
                try {
                    metas = LegacyBridge.itemSubtypes(item.legacyItem());
                } catch (RuntimeException e) {
                    LOGGER.error("[ModSweep] Item {} FAILED: subtypes", ForgeRegistries.ITEMS.getKey(item), e);
                }
            }
            for (int meta : metas) {
                ITEMS.add(item.stackOf(meta));
            }
        }
        for (LegacyArmorItem armor : LegacyContent.armor()) {
            ITEMS.add(new ItemStack(armor));
        }
    }

    /** 27 legacy items at a time in the main inventory, ticked for ITEM_BATCH_TICKS; false once all were. */
    private static boolean giveItemBatch(ServerPlayer player) {
        player.getInventory().clearContent();
        int from = itemBatch * 27;
        if (from >= ITEMS.size()) {
            return false;
        }
        for (int i = 0; i < 27 && from + i < ITEMS.size(); i++) {
            player.getInventory().setItem(9 + i, ITEMS.get(from + i).copy());
        }
        if (itemBatch < 4) {
            requestScreenshot("sweep-items-" + itemBatch); // the client opens the inventory for it
        }
        itemBatch++;
        return true;
    }

    private static int itemsUsing;
    private static int itemsMounted;

    private static void useItem(ServerPlayer player, ServerLevel level, ItemStack stack) {
        if (player.isDeadOrDying()) {
            player.server.getPlayerList().respawn(player, false);
            return; // an item killed the player; carry on with the next one
        }
        player.stopRiding();
        player.getInventory().clearContent();
        player.setItemInHand(InteractionHand.MAIN_HAND, stack.copy());
        player.teleportTo(level, origin.getX() + 1.5, origin.getY() + 1, origin.getZ() + 1.5, 0f, -30f); // looking up: into the air
        player.gameMode.useItem(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND);
        if (player.isUsingItem()) {
            itemsUsing++;
            player.releaseUsingItem();
        }
        if (player.getVehicle() != null) {
            itemsMounted++;
            LOGGER.info("[ModSweep] Using {} put the player on {}", ForgeRegistries.ITEMS.getKey(stack.getItem()),
                    ForgeRegistries.ENTITY_TYPES.getKey(player.getVehicle().getType()));
        }
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
    }

    private static void generateChunks(ServerPlayer player, ServerLevel level) {
        BlockPos far = level.getSharedSpawnPos().offset(4000, 0, 0);
        player.teleportTo(level, far.getX() + 0.5, 140, far.getZ() + 0.5, 0f, 40f);
    }

    /** Where the player ended up, and what the dimension's generator built around it. */
    private static void reportDimension(ServerPlayer player, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> key, int legacyId) {
        ServerLevel level = player.serverLevel();
        boolean arrived = level.dimension() == key;
        int chunks = 0;
        long solid = 0;
        Map<String, Integer> blocks = new TreeMap<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int x = player.chunkPosition().x - 2; x <= player.chunkPosition().x + 2; x++) {
            for (int z = player.chunkPosition().z - 2; z <= player.chunkPosition().z + 2; z++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) {
                    continue;
                }
                chunks++;
                for (int bx = 0; bx < 16; bx++) {
                    for (int bz = 0; bz < 16; bz++) {
                        for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                            BlockState state = chunk.getBlockState(at.set(bx, y, bz));
                            if (!state.isAir()) {
                                solid++;
                                blocks.merge(String.valueOf(ForgeRegistries.BLOCKS.getKey(state.getBlock())), 1, Integer::sum);
                            }
                        }
                    }
                }
            }
        }
        List<Map.Entry<String, Integer>> top = new ArrayList<>(blocks.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        LOGGER.info("[ModSweep] Dimension {} ({}): {} at {}; {} chunks, {} blocks; most common {}", legacyId, key.location(),
                arrived ? "arrived" : "NOT ARRIVED (in " + level.dimension().location() + ")", player.blockPosition(), chunks, solid,
                top.subList(0, Math.min(6, top.size())));
    }

    private static int readyChunks(ServerPlayer player, ServerLevel level, int radius) {
        int ready = 0;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                ready += level.getChunkSource().getChunkNow(player.chunkPosition().x + x, player.chunkPosition().z + z) != null ? 1 : 0;
            }
        }
        return ready;
    }

    /** Legacy blocks in the generated chunks around the player, by block. */
    private static void reportWorldGen(ServerPlayer player, ServerLevel level) {
        Map<String, Integer> found = new TreeMap<>();
        Map<String, Integer> deep = new TreeMap<>(); // below y 0
        int chunks = 0;
        int cx = player.chunkPosition().x;
        int cz = player.chunkPosition().z;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int x = cx - 6; x <= cx + 6; x++) {
            for (int z = cz - 6; z <= cz + 6; z++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) {
                    continue;
                }
                chunks++;
                for (int bx = 0; bx < 16; bx++) {
                    for (int bz = 0; bz < 16; bz++) {
                        for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                            BlockState state = chunk.getBlockState(at.set(bx, y, bz));
                            if (state.getBlock() instanceof LegacyBlock) {
                                found.merge(String.valueOf(ForgeRegistries.BLOCKS.getKey(state.getBlock())), 1, Integer::sum);
                                if (y < 0) {
                                    deep.merge(modOf(ForgeRegistries.BLOCKS.getKey(state.getBlock())), 1, Integer::sum);
                                }
                            }
                        }
                    }
                }
            }
        }
        Map<String, Integer> byMod = new TreeMap<>();
        found.forEach((block, count) -> byMod.merge(block.substring(0, block.indexOf(':')), count, Integer::sum));
        LOGGER.info("[ModSweep] World generation: {} chunks scanned; legacy blocks by mod {}", chunks, byMod);
        LOGGER.info("[ModSweep] World generation blocks: {}", found);
        LOGGER.info("[ModSweep] World generation below y 0 (the deep pass), by mod: {}", deep);
        long entities = level.getEntitiesOfClass(Entity.class, player.getBoundingBox().inflate(96),
                e -> LegacyEntityTypes.legacyName(e.getType()) != null).size();
        LOGGER.info("[ModSweep] Legacy entities near the new chunks (natural spawning): {}", entities);
    }
}
