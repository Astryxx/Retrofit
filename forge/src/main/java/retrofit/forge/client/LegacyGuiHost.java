package retrofit.forge.client;

import com.mojang.blaze3d.platform.Window;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import retrofit.forge.world.LegacyBridge;

/**
 * One legacy GUI screen shown by a 1.20.1 screen ({@link LegacyScreen} for container GUIs,
 * {@link LegacyPlainScreen} for the rest): sizing, drawing and input, all through the legacy
 * screen's own code (see the legacy bridge's ClientGuis). A legacy screen that throws is closed
 * and reported, never crashing the game.
 */
final class LegacyGuiHost {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Object screen;
    private final Screen owner;
    private int pendingKey;
    private long pressedAt;
    private boolean failed;

    LegacyGuiHost(Object screen, Screen owner) {
        this.screen = screen;
        this.owner = owner;
        LegacyKeys.install();
    }

    Object screen() {
        return screen;
    }

    private void fail(String action, Throwable e) {
        if (!failed) {
            failed = true;
            LOGGER.error("Legacy screen {} failed while {}; closing it", screen == null ? null : screen.getClass().getName(), action, e);
            Minecraft.getInstance().tell(owner::onClose);
        }
    }

    void init(int width, int height) {
        if (screen == null) {
            fail("opening", new IllegalStateException("the mod made no client GUI"));
            return;
        }
        Window window = Minecraft.getInstance().getWindow();
        try {
            LegacyBridge.call("guiInit", screen, width, height, window.getWidth(), window.getHeight());
        } catch (RuntimeException e) {
            fail("opening", e);
        }
    }

    /** {xSize, ySize, guiLeft, guiTop}; zeros for screens without a container. */
    int[] layout() {
        try {
            return screen == null ? new int[4] : LegacyBridge.call("guiLayout", screen);
        } catch (RuntimeException e) {
            return new int[4];
        }
    }

    void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks, AbstractContainerMenu menu) {
        if (failed || screen == null) {
            return;
        }
        try {
            synchronized (LegacyBridge.renderLock()) {
                int vertices = LegacyBridge.call("guiDraw", screen, mouseX, mouseY, partialTicks);
                Object[] markers = LegacyBridge.call("guiMarkers");
                LegacyGuiRenderer.draw(graphics, LegacyGuiRenderer.Captured.copy(vertices, markers), menu, true);
            }
        } catch (Throwable e) {
            fail("drawing", e);
        }
    }

    // ---- Input ----

    boolean mouseClicked(double x, double y, int button) {
        pressedAt = System.currentTimeMillis();
        return input("handling a click", () -> LegacyBridge.call("guiMouseClicked", screen, (int) x, (int) y, button));
    }

    boolean mouseReleased(double x, double y, int button) {
        return input("handling a click", () -> LegacyBridge.call("guiMouseReleased", screen, (int) x, (int) y, button));
    }

    boolean mouseDragged(double x, double y, int button) {
        long held = System.currentTimeMillis() - pressedAt;
        return input("handling a drag", () -> LegacyBridge.call("guiMouseDragged", screen, (int) x, (int) y, button, held));
    }

    boolean mouseScrolled(double delta) {
        Minecraft minecraft = Minecraft.getInstance();
        int windowX = (int) minecraft.mouseHandler.xpos();
        int windowY = minecraft.getWindow().getHeight() - (int) minecraft.mouseHandler.ypos();
        return input("scrolling", () -> LegacyBridge.call("guiMouseWheel", screen, (int) Math.round(delta * 120), windowX, windowY));
    }

    /**
     * Keys that type a character wait for {@link #charTyped}, since legacy keyTyped takes the
     * character and the key together. Control combinations type the control character, as LWJGL 2
     * reported them (text fields use 1, 3, 22 and 24 for select all, copy, paste and cut).
     */
    boolean keyPressed(int glfwKey, int modifiers) {
        int key = LegacyKeys.toLegacy(glfwKey);
        boolean control = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        if (control && glfwKey >= GLFW.GLFW_KEY_A && glfwKey <= GLFW.GLFW_KEY_Z) {
            char character = (char) (glfwKey - GLFW.GLFW_KEY_A + 1);
            return input("typing", () -> LegacyBridge.call("guiKeyTyped", screen, character, key));
        }
        if (LegacyKeys.typesCharacter(glfwKey) && !control) {
            pendingKey = key;
            return true;
        }
        return input("typing", () -> LegacyBridge.call("guiKeyTyped", screen, (char) 0, key));
    }

    boolean charTyped(char character) {
        int key = pendingKey;
        pendingKey = 0;
        return input("typing", () -> LegacyBridge.call("guiKeyTyped", screen, character, key));
    }

    void tick() {
        input("updating", () -> LegacyBridge.call("guiTick", screen));
    }

    void closed() {
        if (screen != null) {
            try {
                LegacyBridge.call("guiClosed", screen);
            } catch (RuntimeException e) {
                LOGGER.error("Legacy screen {} failed while closing", screen.getClass().getName(), e);
            }
        }
    }

    boolean pauses() {
        try {
            return screen != null && LegacyBridge.<Boolean>call("guiPauses", screen);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean input(String action, Runnable call) {
        if (failed || screen == null) {
            return false;
        }
        try {
            call.run();
        } catch (RuntimeException e) {
            fail(action, e);
        }
        return true;
    }
}
