package retrofit.forge.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import retrofit.forge.RetrofitMod;
import retrofit.forge.world.LegacyBlock;
import retrofit.forge.world.LegacyBlockEntity;
import retrofit.forge.world.LegacyBridge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automated world bridge test, enabled with -Dretrofit.worldTest=true (Gradle: -PworldTest).
 * Run 1 places hello1710's counter block next to spawn, lets its legacy tile entity tick, then
 * saves and quits. Run 2 finds the block in the save and checks the counter resumed.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, value = Dist.CLIENT)
public final class WorldBridgeTest {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean("retrofit.worldTest");
    private static final ResourceLocation COUNTER_BLOCK = new ResourceLocation("hello1710", "counter_block");
    private static final Pattern TICKS = Pattern.compile("ticks=(\\d+)");
    private static final int START_TICK = 40;
    private static final int QUIT_TICK = 240;

    private static int serverTicks;
    private static BlockPos pos;
    private static int ticksFromSave = -1;

    private WorldBridgeTest() {
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
        } else if (pos != null && serverTicks % 40 == 0) {
            report(level, serverTicks == QUIT_TICK);
        }
        if (serverTicks == QUIT_TICK) {
            LOGGER.info("[WorldTest] Saving and quitting");
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(minecraft::stop);
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
        level.setBlock(pos, counter.defaultBlockState(), 3);
        LOGGER.info("[WorldTest] Placed legacy block {} at {}: {}", COUNTER_BLOCK, pos, describe(level));
    }

    private static void report(ServerLevel level, boolean last) {
        String tile = describe(level);
        LOGGER.info("[WorldTest] tick {}: {}", serverTicks, tile);
        if (!last) {
            return;
        }
        int ticks = ticks(tile);
        if (ticksFromSave < 0) {
            LOGGER.info("[WorldTest] Run 1 done: counter reached {}; run again to check it survives the reload", ticks);
        } else if (ticksFromSave > 0 && ticks > ticksFromSave) {
            LOGGER.info("[WorldTest] PASSED: counter was {} after the reload and kept counting to {}", ticksFromSave, ticks);
        } else {
            LOGGER.error("[WorldTest] FAILED: counter was {} after the reload, now {}", ticksFromSave, ticks);
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
}
