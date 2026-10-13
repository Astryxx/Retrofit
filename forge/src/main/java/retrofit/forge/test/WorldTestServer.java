package retrofit.forge.test;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import retrofit.forge.RetrofitMod;
import retrofit.forge.world.LegacyBlock;
import retrofit.forge.world.LegacyContent;
import retrofit.forge.world.LegacyBlockEntity;
import retrofit.forge.world.LegacyBridge;
import retrofit.forge.world.LegacyItem;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server half of the automated world test (-Dretrofit.worldTest=true, Gradle: -PworldTest).
 * Works on the integrated server and on a dedicated server.
 *
 * Places hello1710's counter block next to spawn (or finds it in the save), checks the legacy
 * tile entity ticks, saw a real 1.20.1 block below it and placed spruce planks through the
 * vanilla table, and that its count survives reloads. Joining players are put in front of it
 * holding the test gems. On a dedicated server it stops once the last player leaves.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID)
public final class WorldTestServer {
    public static final boolean ENABLED = Boolean.getBoolean("retrofit.worldTest");
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation COUNTER_BLOCK = new ResourceLocation("hello1710", "counter_block");
    private static final ResourceLocation TEST_GEM = new ResourceLocation("hello1710", "test_gem");
    private static final Pattern TICKS = Pattern.compile("ticks=(\\d+)");
    private static final int START_TICK = 40;
    private static final int VERDICT_TICK = 200;

    private static int serverTicks;
    private static BlockPos pos;
    private static int ticksFromSave = -1;

    private WorldTestServer() {
    }

    /** Where the test block is, once placed; read by the client half in singleplayer. */
    public static BlockPos blockPos() {
        return pos;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerLevel level = server.overworld();
        serverTicks++;
        if (serverTicks == START_TICK) {
            start(level);
            if (pos != null) {
                placeShowcase(level);
            }
            server.getPlayerList().getPlayers().forEach(WorldTestServer::prepare);
        } else if (serverTicks == START_TICK + 10 && pos != null && !server.getPlayerList().getPlayers().isEmpty()) {
            ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            InteractionTest.run(player, pos);
            prepare(player);
        } else if (pos != null && serverTicks % 40 == 0 && serverTicks <= VERDICT_TICK) {
            report(level, serverTicks == VERDICT_TICK);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (ENABLED && pos != null && event.getEntity() instanceof ServerPlayer player) {
            prepare(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (ENABLED && server.isDedicatedServer() && server.getPlayerCount() <= 1) {
            LOGGER.info("[WorldTest] Last player left; stopping the dedicated server");
            server.halt(false);
        }
    }

    private static void start(ServerLevel level) {
        BlockPos spawn = level.getSharedSpawnPos();
        int x = spawn.getX() + 2;
        int z = spawn.getZ() + 2;
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        BlockPos below = new BlockPos(x, top - 1, z);
        if (level.getBlockState(below).getBlock() instanceof LegacyBlock) {
            pos = below;
            String tile = describe(level);
            ticksFromSave = ticks(tile);
            LOGGER.info("[WorldTest] Found the legacy block from the save at {}: {}", pos, tile);
            return;
        }
        Block counter = ForgeRegistries.BLOCKS.getValue(COUNTER_BLOCK);
        if (!(counter instanceof LegacyBlock)) {
            LOGGER.error("[WorldTest] FAILED: {} is not registered as a legacy block (got {})", COUNTER_BLOCK, counter);
            return;
        }
        pos = new BlockPos(x, top, z);
        level.setBlock(pos.east(), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(pos, counter.defaultBlockState(), 3);
        LOGGER.info("[WorldTest] Placed legacy block {} at {}: {}", COUNTER_BLOCK, pos, describe(level));
    }

    /**
     * Every legacy block (first metadata value) in a grid of 7 per row, 2 blocks apart, starting
     * just north of the test block, so one screenshot shows how all of them render.
     */
    private static void placeShowcase(ServerLevel level) {
        int placed = 0;
        for (LegacyBlock block : LegacyContent.blocks()) {
            if (block.asItem() == null || ForgeRegistries.BLOCKS.getKey(block).getPath().equals("counter_block")) {
                continue;
            }
            int column = placed % 7;
            int row = placed / 7;
            BlockPos at = pos.offset(-6 + column * 2, 0, -2 - row * 2);
            level.setBlock(at, block.defaultBlockState(), 3);
            placed++;
        }
        LOGGER.info("[WorldTest] Showcase: placed {} legacy blocks", placed);
    }

    /**
     * Stands the player 3 blocks south of the test block, raised to see the showcase grid,
     * holding both test gems and some legacy block items (for inventory rendering).
     */
    private static void prepare(ServerPlayer player) {
        // Creative, so hovering above the grid is allowed (survival players get kicked for floating on servers).
        player.setGameMode(GameType.CREATIVE);
        player.teleportTo(player.server.overworld(), pos.getX() + 0.5, pos.getY() + 3, pos.getZ() + 3.5, 180f, 35f);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        if (ForgeRegistries.ITEMS.getValue(TEST_GEM) instanceof LegacyItem gem) {
            player.getInventory().setItem(0, gem.stackOf(0));
            player.getInventory().setItem(1, gem.stackOf(1));
        }
        int slot = 2;
        for (LegacyBlock block : LegacyContent.blocks()) {
            if (slot > 8) {
                break;
            }
            if (!ForgeRegistries.BLOCKS.getKey(block).getPath().equals("counter_block")) {
                player.getInventory().setItem(slot++, new ItemStack(block));
            }
        }
    }

    private static void report(ServerLevel level, boolean verdict) {
        String tile = describe(level);
        LOGGER.info("[WorldTest] tick {}: {}", serverTicks, tile);
        if (!verdict) {
            return;
        }
        boolean planks = level.getBlockState(pos.east()).is(Blocks.SPRUCE_PLANKS);
        LOGGER.info("[WorldTest] Vanilla table: the legacy tile saw '{}' below it; east of it is {} ({})",
                below(tile), level.getBlockState(pos.east()), planks ? "spruce planks as placed by legacy code: OK" : "FAILED");
        int ticks = ticks(tile);
        if (ticksFromSave < 0) {
            LOGGER.info("[WorldTest] Server checks done: counter reached {}; run again to check it survives the reload", ticks);
        } else if (ticksFromSave > 0 && ticks > ticksFromSave && planks) {
            LOGGER.info("[WorldTest] Server PASSED: counter was {} after the reload and kept counting to {}", ticksFromSave, ticks);
        } else {
            LOGGER.error("[WorldTest] Server FAILED: counter was {} after the reload, now {}; planks {}", ticksFromSave, ticks, planks);
        }
    }

    private static String describe(ServerLevel level) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof LegacyBlockEntity legacy)) {
            return "no legacy block entity (" + blockEntity + ")";
        }
        Object tile = legacy.legacyTile();
        return tile == null ? "no legacy tile entity" : LegacyBridge.describe(tile);
    }

    private static int ticks(String description) {
        Matcher matcher = TICKS.matcher(description);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static String below(String description) {
        int start = description.indexOf("below=");
        return start < 0 ? "?" : description.substring(start + 6, description.indexOf(',', start));
    }
}
