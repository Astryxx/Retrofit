package retrofit.forge.test;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import retrofit.forge.RetrofitMod;
import retrofit.forge.world.LegacyBlockEntity;
import retrofit.forge.world.LegacyBridge;
import retrofit.forge.world.LegacyItem;

/**
 * Client half of the automated world test: once in a world (singleplayer or a server), checks
 * that the client-side legacy tile entity received synced data, that legacy names translate,
 * and that legacy-placed vanilla blocks arrived; saves retrofit-world-test.png and quits.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, value = Dist.CLIENT)
public final class WorldTestClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHECK_TICK = 160;
    private static final int QUIT_TICK = 200;

    private static int ticksInWorld;

    private WorldTestClient() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!WorldTestServer.ENABLED || event.phase != TickEvent.Phase.END || minecraft.level == null || minecraft.player == null) {
            return;
        }
        ticksInWorld++;
        if (ticksInWorld == CHECK_TICK) {
            check(minecraft);
            Screenshot.grab(minecraft.gameDirectory, "retrofit-world-test.png", minecraft.getMainRenderTarget(),
                    message -> LOGGER.info("[WorldTest] Client: {}", message.getString()));
        } else if (ticksInWorld == QUIT_TICK) {
            LOGGER.info("[WorldTest] Client: quitting");
            minecraft.stop();
        }
    }

    private static void check(Minecraft minecraft) {
        // The test block sits 3 blocks north of where the server put the player.
        BlockPos pos = BlockPos.containing(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ() - 3);
        BlockEntity blockEntity = minecraft.level.getBlockEntity(pos);
        String synced = blockEntity instanceof LegacyBlockEntity legacy && legacy.legacyTile() != null
                ? LegacyBridge.describe(legacy.legacyTile()) : "no legacy block entity at " + pos;
        boolean syncOk = synced.contains("syncedTicks=") && !synced.contains("syncedTicks=-1");
        LOGGER.info("[WorldTest] Client sync: {} ({})", synced, syncOk ? "OK" : "FAILED");

        boolean planks = minecraft.level.getBlockState(pos.east()).is(Blocks.SPRUCE_PLANKS);
        LOGGER.info("[WorldTest] Client sees spruce planks placed by legacy code: {}", planks ? "OK" : "FAILED");

        Block counter = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("hello1710", "counter_block"));
        String blockName = counter == null ? "?" : counter.getName().getString();
        String gemNames = "?";
        if (ForgeRegistries.ITEMS.getValue(new ResourceLocation("hello1710", "test_gem")) instanceof LegacyItem gem) {
            ItemStack ruby = gem.stackOf(0);
            ItemStack sapphire = gem.stackOf(1);
            gemNames = ruby.getHoverName().getString() + " / " + sapphire.getHoverName().getString();
        }
        boolean namesOk = blockName.equals("Counter Block (1.7.10)") && gemNames.equals("Ruby (1.7.10) / Sapphire (1.7.10)");
        LOGGER.info("[WorldTest] Client names: block '{}', items '{}' ({})", blockName, gemNames, namesOk ? "OK" : "FAILED");

        if (syncOk && planks && namesOk) {
            LOGGER.info("[WorldTest] Client PASSED");
        } else {
            LOGGER.error("[WorldTest] Client FAILED");
        }
    }
}
