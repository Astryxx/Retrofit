package retrofit.testmods.reference;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.block.Block;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The comparison scenes, as Retrofit's play test plays them (PlayTest.compare): a flat world, a
 * stone platform at y 100, the player on it facing east and looking down 35 degrees. Each listed
 * item marked "hand" is held and shot in first person and from the front (cmp-hand-N-first/third.png),
 * then the blocks of the listed item blocks are placed in front of the player, 35 at a time
 * (cmp-blocks-N.png), then all of them are shown in a gallery laid out as Retrofit's
 * (cmp-gallery-N.png). The list has
 * one item per line, "modid:name", damage and optionally "hand", separated by tabs (names may have
 * spaces), as 1.7.10 names it; N is its line among the items.
 */
public final class ReferenceDriver {
    private static final String WORLD = "RetrofitReference";
    /** Ticks to wait after giving an item: the hand's equip animation and the camera have settled. */
    private static final int SETTLE = 25;

    /** {name, damage[, "hand"]} per listed item, resolved once the mods have registered their items. */
    private final List<String[]> listed = new ArrayList<String[]>();
    private final List<ItemStack> stacks = new ArrayList<ItemStack>();
    private final Deque<Step> steps = new ArrayDeque<Step>();
    private final ConcurrentLinkedQueue<Runnable> serverActions = new ConcurrentLinkedQueue<Runnable>();
    private int titleTicks;
    private int worldTicks;
    private int wait;
    private boolean creating;
    private boolean planned;

    private static final class Step {
        final String what;
        final Runnable action;
        final int delay;

        Step(String what, int delay, Runnable action) {
            this.what = what;
            this.delay = delay;
            this.action = action;
        }
    }

    ReferenceDriver(File list) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(list), Charset.forName("UTF-8")))) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                listed.add(line.split("\t")); // names may have spaces
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the item list " + list, e);
        }
        NoGrabMouse.install();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            for (Runnable action; (action = serverActions.poll()) != null; ) {
                action.run();
            }
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.func_71410_x();
        silence(mc);
        if (!creating && mc.field_71462_r instanceof GuiMainMenu && ++titleTicks >= 40) { // currentScreen
            creating = true;
            createWorld(mc);
            return;
        }
        if (mc.field_71441_e == null || mc.field_71439_g == null || ++worldTicks < 100) { // theWorld, thePlayer: chunks settle
            return;
        }
        if (!planned) {
            planned = true;
            plan();
        }
        if (wait > 0) {
            wait--;
            return;
        }
        Step step = steps.poll();
        if (step == null) {
            System.out.println("[Reference] Done");
            mc.func_71400_g(); // shutdown
            return;
        }
        try {
            step.action.run();
        } catch (RuntimeException e) {
            System.out.println("[Reference] " + step.what + " threw: " + e);
            e.printStackTrace(System.out);
        }
        wait = step.delay;
    }

    private static java.lang.reflect.Field soundManager;
    private static java.lang.reflect.Field soundLoaded;

    /**
     * Keeps 1.7.10's sound system switched off: OpenAL isn't unpacked, yet its starter thread
     * reports it loaded, and the music ticker's first check of a playing song then dies in OpenAL.
     */
    private static void silence(Minecraft mc) {
        try {
            if (soundLoaded == null) {
                soundManager = net.minecraft.client.audio.SoundHandler.class.getDeclaredField("field_147694_f"); // sndManager
                soundManager.setAccessible(true);
                soundLoaded = net.minecraft.client.audio.SoundManager.class.getDeclaredField("field_148617_f"); // loaded
                soundLoaded.setAccessible(true);
            }
            soundLoaded.setBoolean(soundManager.get(mc.func_147118_V()), false); // getSoundHandler
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot switch 1.7.10's sound off", e);
        }
    }

    private static void createWorld(Minecraft mc) {
        deleteRecursively(new File(mc.field_71412_D, "saves/" + WORLD)); // mcDataDir
        File[] old = new File(mc.field_71412_D, "screenshots").listFiles();
        if (old != null) {
            for (File file : old) {
                if (file.getName().startsWith("cmp-")) {
                    file.delete();
                }
            }
        }
        System.out.println("[Reference] Creating world " + WORLD);
        WorldSettings settings = new WorldSettings(1710L, WorldSettings.GameType.SURVIVAL, false, false, WorldType.field_77138_c); // FLAT
        mc.func_71371_a(WORLD, WORLD, settings); // launchIntegratedServer
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    private void plan() {
        for (String[] parts : listed) {
            Object item = Item.field_150901_e.func_82594_a(parts[0]); // itemRegistry.getObject
            int damage = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            stacks.add(item instanceof Item ? new ItemStack((Item) item, 1, damage) : null); // keeps N in step with the list
            if (!(item instanceof Item)) {
                System.out.println("[Reference] No item " + parts[0]);
            }
        }
        step("site", 20, new Runnable() {
            @Override
            public void run() {
                onServer(new Runnable() {
                    @Override
                    public void run() {
                        site();
                    }
                });
            }
        });
        for (int i = 0; i < stacks.size(); i++) {
            final ItemStack stack = stacks.get(i);
            final int n = i;
            if (stack == null || !held(listed.get(i))) {
                continue;
            }
            step("hold " + n, SETTLE, new Runnable() {
                @Override
                public void run() {
                    onServer(new Runnable() {
                        @Override
                        public void run() {
                            player().field_71071_by.func_70299_a(0, stack.func_77946_l()); // inventory.setInventorySlotContents(0, copy)
                        }
                    });
                    Minecraft mc = Minecraft.func_71410_x();
                    mc.field_71439_g.field_71071_by.field_70461_c = 0; // inventory.currentItem
                    mc.field_71474_y.field_74320_O = 0; // thirdPersonView: first person
                    System.out.println("[Reference] hand " + n + ": " + Item.field_150901_e.func_148750_c(stack.func_77973_b()) + " " + stack.func_77960_j());
                }
            });
            step("first " + n, 2, shot("cmp-hand-" + n + "-first"));
            step("turn " + n, SETTLE, new Runnable() {
                @Override
                public void run() {
                    Minecraft.func_71410_x().field_71474_y.field_74320_O = 2; // thirdPersonView: from the front
                }
            });
            step("third " + n, 2, shot("cmp-hand-" + n + "-third"));
        }
        planBlocks();
        step("gallery", 10, new Runnable() {
            @Override
            public void run() {
                Minecraft mc = Minecraft.func_71410_x();
                mc.field_71474_y.field_74320_O = 0;
                mc.func_147108_a(new GalleryScreen(stacks)); // displayGuiScreen
            }
        });
        for (int page = 0; page < GalleryScreen.pages(stacks.size()); page++) {
            final int shown = page;
            step("gallery page " + page, 5, new Runnable() {
                @Override
                public void run() {
                    ((GalleryScreen) Minecraft.func_71410_x().field_71462_r).page = shown;
                }
            });
            step("gallery shot " + page, 2, shot("cmp-gallery-" + (page + 1)));
        }
    }

    /** Blocks per batch: a grid GRID_X deep (east of the player) and GRID_Z wide. */
    private static final int GRID_X = 5, GRID_Z = 7;
    /** Ticks for a batch of blocks: chunk meshes rebuilt, tile entities and their renderers running. */
    private static final int BLOCKS_SETTLE = 60;

    /** As PlayTest.compareBlocks: the listed item blocks' blocks, placed as their items place them. */
    private void planBlocks() {
        final List<ItemStack> blocks = new ArrayList<ItemStack>();
        for (ItemStack stack : stacks) {
            if (stack != null && stack.func_77973_b() instanceof ItemBlock) { // getItem
                blocks.add(stack);
            }
        }
        int perBatch = GRID_X * GRID_Z;
        for (int start = 0; start < blocks.size(); start += perBatch) {
            final List<ItemStack> batch = blocks.subList(start, Math.min(blocks.size(), start + perBatch));
            final int number = start / perBatch + 1;
            step("blocks " + number, BLOCKS_SETTLE, new Runnable() {
                @Override
                public void run() {
                    Minecraft.func_71410_x().field_71474_y.field_74320_O = 0;
                    onServer(new Runnable() {
                        @Override
                        public void run() {
                            placeBatch(batch);
                        }
                    });
                }
            });
            step("blocks shot " + number, 2, shot("cmp-blocks-" + number));
        }
    }

    /** Clears the grid, then places a batch row by row: x from 3 to 7 blocks east of the player, z from -3 to 3. */
    private static void placeBatch(List<ItemStack> batch) {
        WorldServer world = MinecraftServer.func_71276_C().func_71218_a(0);
        int x0 = 0, y0 = 100, z0 = 200;
        for (int x = 0; x < GRID_X; x++) {
            for (int z = 0; z < GRID_Z; z++) {
                for (int y = 1; y <= 4; y++) {
                    world.func_147465_d(x0 + 3 + x, y0 + y, z0 - 3 + z, Blocks.field_150350_a, 0, 2);
                }
            }
        }
        for (int i = 0; i < batch.size(); i++) {
            ItemStack stack = batch.get(i);
            Block block = Block.func_149634_a(stack.func_77973_b()); // getBlockFromItem
            int meta = stack.func_77973_b().func_77647_b(stack.func_77960_j()); // getMetadata(damage)
            // flag 2: to clients, no neighbour updates (blocks placed out of context, as Pistronics' wrapper
            // without contents, break on them); the same in Retrofit's play test
            world.func_147465_d(x0 + 3 + i / GRID_Z, y0 + 1, z0 - 3 + i % GRID_Z, block, meta, 2);
        }
    }

    /** Whether a listed item is shot in the hand ("hand" after its damage); every item is in the gallery. */
    private static boolean held(String[] parts) {
        return parts.length > 2 && parts[2].equals("hand");
    }

    /** As PlayTest.newSite: clear air over a 15x15 stone floor, the player in it facing east, looking down. */
    private void site() {
        WorldServer world = MinecraftServer.func_71276_C().func_71218_a(0); // getServer().worldServerForDimension(0)
        int x0 = 0, y0 = 100, z0 = 200;
        for (int x = x0 - 6; x <= x0 + 8; x++) {
            for (int z = z0 - 6; z <= z0 + 8; z++) {
                world.func_147465_d(x, y0, z, Blocks.field_150348_b, 0, 2); // setBlock: stone
                for (int y = y0 + 1; y <= y0 + 6; y++) {
                    world.func_147465_d(x, y, z, Blocks.field_150350_a, 0, 2); // air
                }
            }
        }
        world.func_72877_b(6000); // setWorldTime: noon
        world.func_82736_K().func_82764_b("doDaylightCycle", "false"); // getGameRules().setOrCreateGameRule
        world.func_82736_K().func_82764_b("doMobSpawning", "false");
        EntityPlayerMP player = player();
        player.field_71075_bZ.field_75102_a = true; // capabilities.disableDamage
        player.func_71016_p(); // sendPlayerAbilities
        player.field_71071_by.func_146027_a(null, -1); // inventory.clearInventory: everything
        player.field_71135_a.func_147364_a(x0 + 0.5, y0 + 1, z0 + 0.5, -90, 35); // playerNetServerHandler.setPlayerLocation
    }

    private static EntityPlayerMP player() {
        return (EntityPlayerMP) MinecraftServer.func_71276_C().func_71203_ab().field_72404_b.get(0); // getConfigurationManager().playerEntityList
    }

    private void onServer(Runnable action) {
        serverActions.add(action);
    }

    private void step(String what, int delay, Runnable action) {
        steps.add(new Step(what, delay, action));
    }

    private static Runnable shot(final String name) {
        return new Runnable() {
            @Override
            public void run() {
                Minecraft mc = Minecraft.func_71410_x();
                ScreenShotHelper.func_148259_a(mc.field_71412_D, name + ".png", mc.field_71443_c, mc.field_71440_d, mc.func_147110_a()); // saveScreenshot
            }
        };
    }
}
