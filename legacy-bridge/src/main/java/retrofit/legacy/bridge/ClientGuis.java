package retrofit.legacy.bridge;

import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.network.NetworkRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import retrofit.legacy.gl.Capture;
import retrofit.legacy.gl.GL11;
import retrofit.legacy.gl.Input;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Client only. Legacy GUI screens shown by the host.
 *
 * - Drawing: {@link #draw} runs the screen's own drawScreen into the GL emulation. Slots and items
 *   are not drawn by legacy code; the redirects below leave markers in the capture instead
 *   (drawSlot, RenderItem's GUI methods, renderToolTip), and the host draws 1.20.1 items and
 *   tooltips at those points, in order.
 * - Input: the host calls the screen's own mouse and key handlers, with LWJGL 2 key codes.
 *   GuiContainer.handleMouseClick is redirected to the host, which sends the click like any
 *   1.20.1 click; the menu runs the legacy slotClick on both sides.
 * - Minecraft.displayGuiScreen is redirected to the host, which opens or closes its screen.
 */
public final class ClientGuis {
    /** guiId of a vanilla chest GUI over a mod's inventory (EntityPlayerMP.displayGUIChest). */
    static final int CHEST = -1;

    private static Method mouseClicked;
    private static Method mouseReleased;
    private static Method mouseDragged;
    private static Method keyTyped;
    private static boolean fontReady;

    private ClientGuis() {
    }

    private static Minecraft minecraft() {
        return Minecraft.func_71410_x();
    }

    private static retrofit.api.HostPlayer host() {
        Object player = minecraft().field_71439_g; // thePlayer
        return player instanceof BridgeClientPlayer ? ((BridgeClientPlayer) player).host : null;
    }

    static void openLocal(EntityPlayer player, ModContainer container, int guiId, World world, int x, int y, int z) {
        Object gui = NetworkRegistry.INSTANCE.getLocalGuiContainer(container, player, guiId, world, x, y, z);
        if (gui instanceof GuiScreen) {
            displayScreen(minecraft(), (GuiScreen) gui);
        }
    }

    static Object vanillaGui(EntityPlayer player, int guiId, String title, int size) {
        if (guiId == CHEST) {
            return new GuiChest(player.field_71071_by, new InventoryBasic(title, false, size));
        }
        return null;
    }

    // ---- Redirect targets (see HeadlessTransformer) ----

    /** Minecraft.displayGuiScreen */
    public static void displayScreen(Minecraft minecraft, GuiScreen screen) {
        retrofit.api.HostPlayer host = host();
        if (host != null) {
            host.displayScreen(screen);
        } else if (screen != null) {
            System.out.println("[Retrofit] A legacy screen was opened before the player was known: " + screen.getClass().getName());
        }
    }

    /** GuiContainer.handleMouseClick: the host sends the click (and predicts it through the menu). */
    public static void handleMouseClick(GuiContainer screen, Slot slot, int slotId, int button, int mode) {
        retrofit.api.HostPlayer host = host();
        if (host != null) {
            host.clickSlot(screen.field_147002_h.field_75152_c, slot != null ? slot.field_75222_d : slotId, button, mode); // inventorySlots.windowId, slotNumber
        }
    }

    /** GuiContainer.drawSlot: the host draws the slot's item here. */
    public static void drawSlot(GuiContainer screen, Slot slot) {
        Capture.mark("slot", slot.field_75222_d, GL11.transform(slot.field_75223_e, slot.field_75221_f, 0)); // slotNumber, x, y
    }

    /** GuiScreen.renderToolTip: the host draws the item's 1.20.1 tooltip (with the mod's lines) on top. */
    public static void itemTooltip(GuiScreen screen, ItemStack stack, int x, int y) {
        Capture.mark("tooltip", stack, new float[]{x, y, 0, 1});
    }

    /** RenderItem.renderItemAndEffectIntoGUI and renderItemIntoGUI */
    public static void drawItem(RenderItem renderer, FontRenderer font, TextureManager textures, ItemStack stack, int x, int y) {
        if (stack != null) {
            Capture.mark("item", stack, GL11.transform(x, y, renderer.field_77023_b)); // zLevel
        }
    }

    /** RenderItem.renderItemOverlayIntoGUI: count and damage bar. */
    public static void drawItemOverlay(RenderItem renderer, FontRenderer font, TextureManager textures, ItemStack stack, int x, int y) {
        drawItemOverlayText(renderer, font, textures, stack, x, y, null);
    }

    public static void drawItemOverlayText(RenderItem renderer, FontRenderer font, TextureManager textures, ItemStack stack, int x, int y, String text) {
        if (stack != null) {
            Capture.mark("overlay", new Object[]{stack, text}, GL11.transform(x, y, renderer.field_77023_b));
        }
    }

    // ---- What the host's screen calls ----

    public static Object guiContainer(Object screen) {
        return screen instanceof GuiContainer ? ((GuiContainer) screen).field_147002_h : null; // inventorySlots
    }

    /** {xSize, ySize, guiLeft, guiTop} of a container screen; zeros for other screens. */
    public static int[] guiLayout(Object screen) throws ReflectiveOperationException {
        if (!(screen instanceof GuiContainer)) {
            return new int[4];
        }
        return new int[]{intField(screen, "field_146999_f"), intField(screen, "field_147000_g"),
                intField(screen, "field_147003_i"), intField(screen, "field_147009_r")};
    }

    private static int intField(Object screen, String name) throws ReflectiveOperationException {
        Field field = GuiContainer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(screen);
    }

    /** setWorldAndResolution (which runs initGui), with the window size legacy input code divides by. */
    public static void guiInit(Object screen, int width, int height, int windowWidth, int windowHeight) {
        Minecraft minecraft = minecraft();
        LegacyRendering.ensureEnvironment();
        if (!fontReady) {
            fontReady = true;
            minecraft.field_71466_p.func_110549_a(minecraft.func_110442_L()); // fontRenderer.onResourceManagerReload: glyph widths
        }
        minecraft.field_71443_c = windowWidth;  // displayWidth
        minecraft.field_71440_d = windowHeight; // displayHeight
        minecraft.field_71462_r = (GuiScreen) screen; // currentScreen
        ((GuiScreen) screen).func_146280_a(minecraft, width, height); // setWorldAndResolution
    }

    /** drawScreen into the capture; returns the vertex count. Hold Bridge.LOCK until the capture is read. */
    public static int guiDraw(Object screen, int mouseX, int mouseY, float partialTicks) {
        Capture.begin();
        try {
            ((GuiScreen) screen).func_73863_a(mouseX, mouseY, partialTicks); // drawScreen
        } finally {
            LegacyRendering.resetTessellatorIfDrawing();
        }
        return Capture.vertexCount();
    }

    /** Markers left while drawing: {vertex index, kind, subject, float[] {x, y, z, scale}}. */
    public static Object[] guiMarkers() {
        return Capture.markers().toArray();
    }

    public static void guiMouseClicked(Object screen, int x, int y, int button) throws ReflectiveOperationException {
        if (mouseClicked == null) {
            mouseClicked = method("func_73864_a", int.class, int.class, int.class);
        }
        mouseClicked.invoke(screen, x, y, button);
    }

    /** mouseMovedOrUp */
    public static void guiMouseReleased(Object screen, int x, int y, int button) throws ReflectiveOperationException {
        if (mouseReleased == null) {
            mouseReleased = method("func_146286_b", int.class, int.class, int.class);
        }
        mouseReleased.invoke(screen, x, y, button);
    }

    /** mouseClickMove */
    public static void guiMouseDragged(Object screen, int x, int y, int button, long heldMillis) throws ReflectiveOperationException {
        if (mouseDragged == null) {
            mouseDragged = method("func_146273_a", int.class, int.class, int.class, long.class);
        }
        mouseDragged.invoke(screen, x, y, button, heldMillis);
    }

    /** Scrolling: handleMouseInput with a wheel-only event (window pixels, y up). */
    public static void guiMouseWheel(Object screen, int wheel, int windowX, int windowY) {
        Input.mouseEvent(-1, false, windowX, windowY, wheel);
        try {
            ((GuiScreen) screen).func_146274_d(); // handleMouseInput
        } finally {
            Input.mouseEvent(-1, false, windowX, windowY, 0);
        }
    }

    public static void guiKeyTyped(Object screen, char character, int key) throws ReflectiveOperationException {
        if (keyTyped == null) {
            keyTyped = method("func_73869_a", char.class, int.class);
        }
        Input.keyEvent(key, character, true);
        keyTyped.invoke(screen, character, key);
    }

    /** updateScreen */
    public static void guiTick(Object screen) {
        ((GuiScreen) screen).func_73876_c();
    }

    /** onGuiClosed */
    public static void guiClosed(Object screen) {
        Minecraft minecraft = minecraft();
        ((GuiScreen) screen).func_146281_b();
        if (minecraft.field_71462_r == screen) {
            minecraft.field_71462_r = null;
        }
    }

    /** doesGuiPauseGame */
    public static boolean guiPauses(Object screen) {
        return ((GuiScreen) screen).func_73868_f();
    }

    private static Method method(String name, Class<?>... parameters) throws NoSuchMethodException {
        Method method = GuiScreen.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }
}
