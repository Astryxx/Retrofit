package retrofit.forge.test;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
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
    /** After the server half's verdict (server tick 200), which in singleplayer shares this game. */
    private static final int QUIT_TICK = 560;

    /** Give up if no world is reached (e.g. disconnected), instead of hanging. */
    private static final int GIVE_UP_TICK = 4800; // room for reconnecting after a stalled login

    /** -Dretrofit.testServer=host:port: connect from the title screen like a player (instead of quick play). */
    private static final String SERVER = System.getProperty("retrofit.testServer");
    /** Title-screen ticks to wait before connecting, so the game has fully settled. */
    private static final int CONNECT_TICK = 100;

    private static int ticksInWorld;
    private static int ticksTotal;
    private static int ticksOnTitle;
    private static boolean connecting;
    private static int retries;
    private static int guiTicks;
    private static Object lastScreen;
    private static int screens;
    private static boolean pongInChat;

    @SubscribeEvent
    public static void onChat(net.minecraftforge.client.event.ClientChatReceivedEvent event) {
        if (WorldTestServer.ENABLED && event.getMessage().getString().contains("Pong!")) {
            pongInChat = true;
        }
    }

    private WorldTestClient() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!WorldTestServer.ENABLED || event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++ticksTotal == GIVE_UP_TICK && ticksInWorld < CHECK_TICK) {
            LOGGER.error("[WorldTest] Client FAILED: never got into a world; quitting");
            // Diagnose stalls (e.g. a login that never completes): where is every thread?
            Thread.getAllStackTraces().forEach((thread, stack) -> {
                StringBuilder trace = new StringBuilder();
                for (StackTraceElement frame : stack) {
                    trace.append("\n        at ").append(frame);
                }
                LOGGER.error("[WorldTest] Thread '{}' ({}){}", thread.getName(), thread.getState(), trace);
            });
            minecraft.stop();
        }
        if (SERVER != null && connecting && minecraft.level == null && minecraft.screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen
                && retries < 3) {
            // the known stall at "Logging in..." (also without Retrofit): connect again, as a player would
            retries++;
            LOGGER.info("[WorldTest] Client: disconnected before getting in; connecting again ({})", retries);
            connecting = false;
            ticksOnTitle = CONNECT_TICK - 20;
            minecraft.setScreen(new TitleScreen());
        }
        if (SERVER != null && !connecting && minecraft.screen instanceof TitleScreen && ++ticksOnTitle >= CONNECT_TICK) {
            connecting = true;
            LOGGER.info("[WorldTest] Client: connecting to {} from the title screen", SERVER);
            ConnectScreen.startConnecting(minecraft.screen, minecraft, ServerAddress.parseString(SERVER),
                    new ServerData("Retrofit test", SERVER, false), false);
        }
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        ticksInWorld++;
        if (ticksInWorld == CHECK_TICK - 40 || ticksInWorld == CHECK_TICK - 30) {
            // The test mod's 1.7.10 key binding (K), pressed the way a player would.
            net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(org.lwjgl.glfw.GLFW.GLFW_KEY_K));
        }
        if (ticksInWorld == CHECK_TICK) {
            check(minecraft);
            Screenshot.grab(minecraft.gameDirectory, "retrofit-world-test.png", minecraft.getMainRenderTarget(),
                    message -> LOGGER.info("[WorldTest] Client: {}", message.getString()));
        }
        if (ticksInWorld % 40 == 0 && ticksInWorld > 200) {
            LOGGER.info("[WorldTest] Client screen at tick {}: {}", ticksInWorld, minecraft.screen == null ? "none" : minecraft.screen.getClass().getName());
        }
        if (minecraft.screen instanceof retrofit.forge.client.LegacyScreen screen) {
            guiTest(minecraft, screen);
        }
        if (ticksInWorld == QUIT_TICK) {
            LOGGER.info("[WorldTest] Client: quitting");
            minecraft.stop();
        }
    }

    /** The crate GUI the server opened: click its legacy Ping button, then screenshot it. */
    private static void guiTest(Minecraft minecraft, retrofit.forge.client.LegacyScreen screen) {
        if (screen != lastScreen) {
            lastScreen = screen;
            guiTicks = 0;
            screens++;
        }
        guiTicks++;
        if (screens > 1) {
            if (guiTicks == 40) {
                Screenshot.grab(minecraft.gameDirectory, "retrofit-gui-test-" + screens + ".png", minecraft.getMainRenderTarget(),
                        message -> LOGGER.info("[WorldTest] Client: {}", message.getString()));
            }
            return;
        }
        if (guiTicks == 30) {
            double x = screen.getGuiLeft() + 140;
            double y = screen.getGuiTop() + 30;
            LOGGER.info("[WorldTest] Client: clicking the legacy Ping button at {},{}", x, y);
            screen.mouseClicked(x, y, 0);
            screen.mouseReleased(x, y, 0);
        } else if (guiTicks == 70) {
            boolean pong = pongInChat;
            LOGGER.info("[WorldTest] Client GUI: server answered the legacy packet in chat: {}", pong ? "OK" : "FAILED");
            Screenshot.grab(minecraft.gameDirectory, "retrofit-gui-test.png", minecraft.getMainRenderTarget(),
                    message -> LOGGER.info("[WorldTest] Client: {}", message.getString()));
        }
    }

    private static void check(Minecraft minecraft) {
        // The test block sits 3 blocks north of and 3 below where the server put the player (raised to see the showcase).
        BlockPos pos = BlockPos.containing(minecraft.player.getX(), minecraft.player.getY() - 3, minecraft.player.getZ() - 3);
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

        // Mod sounds: BiblioCraft's sounds.json, served by the asset pack (only when BiblioCraft is installed).
        var sound = minecraft.getSoundManager().getSoundEvent(new ResourceLocation("bibliocraft", "copen"));
        LOGGER.info("[WorldTest] Client sound bibliocraft:copen: {}", sound == null ? "not registered (BiblioCraft missing?)"
                : "OK, " + sound.getSound(net.minecraft.util.RandomSource.create()).getLocation());

        // Legacy entities: the golem the server spawned in view, its client copy and what its renderer draws.
        for (net.minecraft.world.entity.Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof retrofit.forge.world.LegacyEntityCarrier carrier) {
                Object legacy = carrier.clientLegacy();
                int vertices = -1;
                if (legacy != null) {
                    synchronized (LegacyBridge.renderLock()) {
                        vertices = LegacyBridge.call("renderEntity", legacy, entity.getYRot(), 0f);
                    }
                }
                LOGGER.info("[WorldTest] Client entity {} at {}: legacy copy {}, renderer drew {} vertices", entity.getType(),
                        entity.blockPosition(), legacy == null ? "missing" : LegacyBridge.describe(legacy), vertices);
            }
        }

        if (syncOk && planks && namesOk) {
            LOGGER.info("[WorldTest] Client PASSED");
        } else {
            LOGGER.error("[WorldTest] Client FAILED");
        }
    }
}
