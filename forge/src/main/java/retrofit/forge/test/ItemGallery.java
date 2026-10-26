package retrofit.forge.test;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import retrofit.forge.world.LegacyBridge;
import retrofit.forge.world.LegacyStackItem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Development: every legacy item (each subtype, block items included) drawn as the inventory
 * draws it, in pages screenshotted as sweep-gallery-N.png, with sweep-gallery.txt saying which
 * item is where. Missing or broken textures, wrong models and dark lighting show at a glance.
 */
final class ItemGallery extends Screen {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final int COLUMNS = 16;
    static final int ROWS = 9;
    static final int MAX_PAGES = 60;
    private static final int CELL = 26;

    private final List<ItemStack> stacks;
    /** Screenshots are named prefix + page number (from 1). */
    private final String prefix;
    private int page;
    private int frames;

    private ItemGallery(List<ItemStack> stacks, String prefix) {
        super(Component.literal("Item gallery"));
        this.stacks = stacks;
        this.prefix = prefix;
    }

    /** Given stacks (empty ones leave their cell empty), shot as prefix + page.png. */
    static ItemGallery of(List<ItemStack> stacks, String prefix) {
        return new ItemGallery(stacks, prefix);
    }

    static ItemGallery create(Minecraft minecraft) {
        List<ItemStack> stacks = new ArrayList<>();
        StringBuilder index = new StringBuilder();
        for (Item item : ForgeRegistries.ITEMS) {
            if (!(item instanceof LegacyStackItem legacy) || legacy.legacyItem() == null) {
                continue;
            }
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id == null || id.getNamespace().equals("hello1710")) {
                continue;
            }
            // the stacks the creative menu shows, NBT and all (facades, prebuilt tools, filled containers)
            List<ItemStack> listed = new ArrayList<>();
            for (Object legacyStack : LegacyBridge.<Object[]>call("subItemStacks", legacy.legacyItem())) {
                listed.add(retrofit.forge.world.LegacyStacks.toModern(legacyStack));
            }
            if (listed.isEmpty()) {
                listed.add(legacy.stackOf(0));
            }
            for (ItemStack stack : listed) {
                if (!stack.isEmpty() && stack.getItem() == item) {
                    int n = stacks.size();
                    index.append(n / (COLUMNS * ROWS)).append(' ').append(n % (COLUMNS * ROWS) / COLUMNS).append(' ')
                            .append(n % COLUMNS).append(' ').append(id).append(' ').append(legacy.meta(stack)).append('\n');
                    stacks.add(stack);
                }
            }
        }
        try {
            Files.writeString(minecraft.gameDirectory.toPath().resolve("screenshots").resolve("sweep-gallery.txt"), index.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[ModSweep] Gallery index not written", e);
        }
        LOGGER.info("[ModSweep] Gallery: {} legacy item stacks in {} pages", stacks.size(), pages(stacks.size()));
        dump(System.getProperty("retrofit.galleryDump", ""));
        return new ItemGallery(stacks, "sweep-gallery-");
    }

    /** Development: the captured vertices (colour, normal, state) of block items' inventory drawing, "modid:name/meta,...". */
    static void dump(String items) {
        for (String entry : items.split(",")) {
            if (entry.isBlank()) {
                continue;
            }
            String[] parts = entry.split("/");
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(parts[0]));
            if (!(item instanceof net.minecraft.world.item.BlockItem blockItem) || !(blockItem.getBlock() instanceof retrofit.forge.world.LegacyBlock block)) {
                continue;
            }
            int meta = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            try {
                synchronized (LegacyBridge.renderLock()) {
                    int vertices = LegacyBridge.call("renderBlockItem", block.legacyBlock(), meta & 15, meta);
                    float[] d = retrofit.forge.client.LegacyCapture.data();
                    int[] states = retrofit.forge.client.LegacyCapture.states();
                    StringBuilder out = new StringBuilder();
                    for (int v = 0; v < Math.min(vertices, 24); v += 4) {
                        int o = v * retrofit.forge.client.LegacyCapture.FLOATS_PER_VERTEX;
                        out.append(String.format(" [xyz %.2f %.2f %.2f uv %.3f %.3f rgba %.2f %.2f %.2f %.2f n %.2f %.2f %.2f state %x]", d[o], d[o + 1], d[o + 2],
                                d[o + 3], d[o + 4], d[o + 8], d[o + 9], d[o + 10], d[o + 11], d[o + 5], d[o + 6], d[o + 7], states[v] >>> 16));
                    }
                    LOGGER.info("[ModSweep] Gallery dump {}: {} vertices{}", entry, vertices, out);
                }
            } catch (Throwable t) {
                LOGGER.warn("[ModSweep] Gallery dump {} failed", entry, t);
            }
        }
    }

    private static int pages(int count) {
        return Math.min(MAX_PAGES, (count + COLUMNS * ROWS - 1) / (COLUMNS * ROWS));
    }

    /** True once every page has been shot. */
    boolean done() {
        return page >= pages(stacks.size());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF707070); // mid grey: black, white and magenta all show
        int perPage = COLUMNS * ROWS;
        int left = (width - COLUMNS * CELL) / 2;
        int top = (height - ROWS * CELL) / 2;
        for (int i = 0; i < perPage; i++) {
            int n = page * perPage + i;
            if (n >= stacks.size()) {
                break;
            }
            int x = left + (i % COLUMNS) * CELL;
            int y = top + (i / COLUMNS) * CELL;
            graphics.fill(x, y, x + CELL - 2, y + CELL - 2, 0xFF8B8B8B);
            graphics.pose().pushPose();
            graphics.pose().translate(x + 1, y + 1, 0);
            graphics.pose().scale(1.5f, 1.5f, 1);
            try {
                graphics.renderItem(stacks.get(n), 0, 0);
                graphics.renderItemDecorations(font, stacks.get(n), 0, 0);
            } catch (RuntimeException e) {
                graphics.fill(0, 0, 16, 16, 0xFFFF0000); // drawing failed: red
            }
            graphics.pose().popPose();
        }
        graphics.drawString(font, "gallery page " + (page + 1) + "/" + pages(stacks.size()), 4, 4, 0xFFFFFF);
        frames++;
    }

    /** Between frames: the last drawn page is complete in the frame buffer. */
    @Override
    public void tick() {
        if (frames >= 3 && !done()) { // drawn a few times: textures and models settled
            Minecraft minecraft = Minecraft.getInstance();
            Screenshot.grab(minecraft.gameDirectory, prefix + (page + 1) + ".png", minecraft.getMainRenderTarget(), message -> { });
            frames = 0;
            page++;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
