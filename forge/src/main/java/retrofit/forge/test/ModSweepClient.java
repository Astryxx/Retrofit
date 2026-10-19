package retrofit.forge.test;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import retrofit.forge.RetrofitMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Client half of the mod sweep (see ModSweepServer): from the title screen, creates a fresh
 * normal world (deleting the previous sweep world), takes the screenshots the server asks for,
 * one of every legacy GUI the sweep opens (up to a limit), and quits once the sweep is done.
 * Screenshots go to run/screenshots/sweep-*.png.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, value = Dist.CLIENT)
public final class ModSweepClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String WORLD = "RetrofitSweep";
    private static final int MAX_GUI_SCREENSHOTS = 40;
    private static final int GIVE_UP_TICK = 20 * 60 * 20;

    private static int ticks;
    private static int titleTicks;
    private static boolean creating;
    private static int handledRequest;
    private static int pendingShot = -1;
    private static String pendingName;
    private static Object lastScreen;
    private static int screenTicks;
    private static int guiShots;
    private static int doneTicks;

    private ModSweepClient() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!ModSweepServer.ENABLED || event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (++ticks == GIVE_UP_TICK) {
            LOGGER.error("[ModSweep] Client: gave up after {} ticks in phase {}", ticks, ModSweepServer.phase);
            minecraft.stop();
            return;
        }
        if (!creating && minecraft.screen instanceof TitleScreen && ++titleTicks >= 40) {
            creating = true;
            createWorld(minecraft);
            return;
        }
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        // Screenshots the server asked for (a few ticks later, so the view has been drawn).
        if (ModSweepServer.screenshotRequest != handledRequest) {
            handledRequest = ModSweepServer.screenshotRequest;
            pendingName = ModSweepServer.screenshotName;
            pendingShot = pendingName.startsWith("sweep-items") ? 12 : 8;
            if (pendingName.startsWith("sweep-items")) {
                minecraft.setScreen(new InventoryScreen(minecraft.player));
            }
        }
        if (pendingShot >= 0 && pendingShot-- == 0) {
            shot(minecraft, pendingName);
            if (minecraft.screen instanceof InventoryScreen) {
                minecraft.setScreen(null);
            }
        }
        // Every legacy GUI the sweep opens.
        Object screen = minecraft.screen;
        if (screen != lastScreen) {
            lastScreen = screen;
            screenTicks = 0;
            if (screen != null) {
                LOGGER.info("[ModSweep] Client screen: {}", screen.getClass().getName());
            }
        }
        screenTicks++;
        boolean legacy = screen instanceof retrofit.forge.client.LegacyScreen || screen instanceof retrofit.forge.client.LegacyPlainScreen;
        if (legacy && screenTicks == 8 && guiShots < MAX_GUI_SCREENSHOTS) {
            shot(minecraft, "sweep-gui-" + (++guiShots));
        }
        if (screen instanceof retrofit.forge.client.LegacyPlainScreen && screenTicks == 12) {
            minecraft.setScreen(null); // plain legacy screens pause singleplayer games, which would stop the sweep
        }
        if (ModSweepServer.phase == ModSweepServer.Phase.DONE && ++doneTicks == 40) {
            minecraft.setScreen(ItemGallery.create(minecraft)); // every legacy item, as the inventory draws it
        }
        if (doneTicks > 40 && (!(minecraft.screen instanceof ItemGallery gallery) || gallery.done()) && heldDone(minecraft)) {
            LOGGER.info("[ModSweep] Client: done; quitting");
            minecraft.stop();
        }
    }

    private static java.util.List<net.minecraft.world.item.ItemStack> held;
    private static int heldIndex;
    private static int heldTicks;
    private static final int MAX_HELD = 30;

    /**
     * After the gallery: items with their own in-hand renderers (IItemRenderer: OreSpawn's big
     * swords, guns, staffs) held one by one, in first and third person. True when done.
     */
    private static boolean heldDone(Minecraft minecraft) {
        if (held == null) {
            minecraft.setScreen(null);
            held = new java.util.ArrayList<>();
            for (net.minecraft.world.item.Item item : net.minecraftforge.registries.ForgeRegistries.ITEMS) {
                if (item instanceof retrofit.forge.world.LegacyStackItem legacy && legacy.legacyItem() != null && held.size() < MAX_HELD) {
                    int[] metas = legacy.hasSubtypes() ? retrofit.forge.world.LegacyBridge.itemSubtypes(legacy.legacyItem()) : new int[]{0};
                    if (retrofit.forge.world.LegacyBridge.hasItemRenderer(legacy.legacyItem(), metas[0], "EQUIPPED_FIRST_PERSON")) {
                        held.add(legacy.stackOf(metas[0]));
                    }
                }
            }
            LOGGER.info("[ModSweep] Held: {} items with their own in-hand renderers", held.size());
            minecraft.player.setXRot(10);
        }
        if (heldIndex >= held.size()) {
            minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
            return true;
        }
        int step = heldTicks++ % 16;
        if (step == 0) {
            minecraft.player.getInventory().setItem(minecraft.player.getInventory().selected, held.get(heldIndex).copy()); // client side: drawing only
            minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
        } else if (step == 6) {
            shot(minecraft, "sweep-held-" + (heldIndex + 1) + "-first");
            minecraft.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
        } else if (step == 14) {
            shot(minecraft, "sweep-held-" + (heldIndex + 1) + "-third");
            LOGGER.info("[ModSweep] Held {}: {}", heldIndex + 1, held.get(heldIndex).getHoverName().getString());
            heldIndex++;
        }
        return false;
    }

    private static void shot(Minecraft minecraft, String name) {
        LOGGER.info("[ModSweep] Client: {} from {} (yaw {}, pitch {})", name, minecraft.player.position(),
                minecraft.player.getYRot(), minecraft.player.getXRot());
        Screenshot.grab(minecraft.gameDirectory, name + ".png", minecraft.getMainRenderTarget(),
                message -> LOGGER.info("[ModSweep] Client: {}", message.getString()));
    }

    /** A new normal world (fixed seed), replacing the one from the last sweep. */
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
                    files.filter(path -> path.getFileName().toString().startsWith("sweep-")).forEach(path -> path.toFile().delete());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[ModSweep] Could not clear the previous sweep", e);
        }
        minecraft.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF); // the test platforms are just above them
        LOGGER.info("[ModSweep] Client: creating world {}", WORLD);
        LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.NORMAL, true, new GameRules(),
                WorldDataConfiguration.DEFAULT);
        minecraft.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(1710L, true, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL).value()
                        .createWorldDimensions());
    }
}
