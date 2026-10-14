package retrofit.forge.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import retrofit.forge.RetrofitMod;

import java.util.function.IntPredicate;
import java.util.function.IntSupplier;

/**
 * GLFW (1.20.1) to LWJGL 2 (1.7.10) key codes, and the live input the legacy Keyboard and Mouse
 * stand-ins read (retrofit.legacy.gl.Input).
 */
public final class LegacyKeys {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int[] TO_LEGACY = new int[GLFW.GLFW_KEY_LAST + 1];
    private static final int[] TO_GLFW = new int[256];
    private static boolean installed;

    static {
        int[][] pairs = {
                {GLFW.GLFW_KEY_ESCAPE, 1}, {GLFW.GLFW_KEY_1, 2}, {GLFW.GLFW_KEY_2, 3}, {GLFW.GLFW_KEY_3, 4}, {GLFW.GLFW_KEY_4, 5},
                {GLFW.GLFW_KEY_5, 6}, {GLFW.GLFW_KEY_6, 7}, {GLFW.GLFW_KEY_7, 8}, {GLFW.GLFW_KEY_8, 9}, {GLFW.GLFW_KEY_9, 10},
                {GLFW.GLFW_KEY_0, 11}, {GLFW.GLFW_KEY_MINUS, 12}, {GLFW.GLFW_KEY_EQUAL, 13}, {GLFW.GLFW_KEY_BACKSPACE, 14},
                {GLFW.GLFW_KEY_TAB, 15}, {GLFW.GLFW_KEY_Q, 16}, {GLFW.GLFW_KEY_W, 17}, {GLFW.GLFW_KEY_E, 18}, {GLFW.GLFW_KEY_R, 19},
                {GLFW.GLFW_KEY_T, 20}, {GLFW.GLFW_KEY_Y, 21}, {GLFW.GLFW_KEY_U, 22}, {GLFW.GLFW_KEY_I, 23}, {GLFW.GLFW_KEY_O, 24},
                {GLFW.GLFW_KEY_P, 25}, {GLFW.GLFW_KEY_LEFT_BRACKET, 26}, {GLFW.GLFW_KEY_RIGHT_BRACKET, 27}, {GLFW.GLFW_KEY_ENTER, 28},
                {GLFW.GLFW_KEY_LEFT_CONTROL, 29}, {GLFW.GLFW_KEY_A, 30}, {GLFW.GLFW_KEY_S, 31}, {GLFW.GLFW_KEY_D, 32},
                {GLFW.GLFW_KEY_F, 33}, {GLFW.GLFW_KEY_G, 34}, {GLFW.GLFW_KEY_H, 35}, {GLFW.GLFW_KEY_J, 36}, {GLFW.GLFW_KEY_K, 37},
                {GLFW.GLFW_KEY_L, 38}, {GLFW.GLFW_KEY_SEMICOLON, 39}, {GLFW.GLFW_KEY_APOSTROPHE, 40}, {GLFW.GLFW_KEY_GRAVE_ACCENT, 41},
                {GLFW.GLFW_KEY_LEFT_SHIFT, 42}, {GLFW.GLFW_KEY_BACKSLASH, 43}, {GLFW.GLFW_KEY_Z, 44}, {GLFW.GLFW_KEY_X, 45},
                {GLFW.GLFW_KEY_C, 46}, {GLFW.GLFW_KEY_V, 47}, {GLFW.GLFW_KEY_B, 48}, {GLFW.GLFW_KEY_N, 49}, {GLFW.GLFW_KEY_M, 50},
                {GLFW.GLFW_KEY_COMMA, 51}, {GLFW.GLFW_KEY_PERIOD, 52}, {GLFW.GLFW_KEY_SLASH, 53}, {GLFW.GLFW_KEY_RIGHT_SHIFT, 54},
                {GLFW.GLFW_KEY_KP_MULTIPLY, 55}, {GLFW.GLFW_KEY_LEFT_ALT, 56}, {GLFW.GLFW_KEY_SPACE, 57}, {GLFW.GLFW_KEY_CAPS_LOCK, 58},
                {GLFW.GLFW_KEY_F1, 59}, {GLFW.GLFW_KEY_F2, 60}, {GLFW.GLFW_KEY_F3, 61}, {GLFW.GLFW_KEY_F4, 62}, {GLFW.GLFW_KEY_F5, 63},
                {GLFW.GLFW_KEY_F6, 64}, {GLFW.GLFW_KEY_F7, 65}, {GLFW.GLFW_KEY_F8, 66}, {GLFW.GLFW_KEY_F9, 67}, {GLFW.GLFW_KEY_F10, 68},
                {GLFW.GLFW_KEY_NUM_LOCK, 69}, {GLFW.GLFW_KEY_SCROLL_LOCK, 70}, {GLFW.GLFW_KEY_KP_7, 71}, {GLFW.GLFW_KEY_KP_8, 72},
                {GLFW.GLFW_KEY_KP_9, 73}, {GLFW.GLFW_KEY_KP_SUBTRACT, 74}, {GLFW.GLFW_KEY_KP_4, 75}, {GLFW.GLFW_KEY_KP_5, 76},
                {GLFW.GLFW_KEY_KP_6, 77}, {GLFW.GLFW_KEY_KP_ADD, 78}, {GLFW.GLFW_KEY_KP_1, 79}, {GLFW.GLFW_KEY_KP_2, 80},
                {GLFW.GLFW_KEY_KP_3, 81}, {GLFW.GLFW_KEY_KP_0, 82}, {GLFW.GLFW_KEY_KP_DECIMAL, 83}, {GLFW.GLFW_KEY_F11, 87},
                {GLFW.GLFW_KEY_F12, 88}, {GLFW.GLFW_KEY_KP_ENTER, 156}, {GLFW.GLFW_KEY_RIGHT_CONTROL, 157},
                {GLFW.GLFW_KEY_KP_DIVIDE, 181}, {GLFW.GLFW_KEY_RIGHT_ALT, 184}, {GLFW.GLFW_KEY_PAUSE, 197}, {GLFW.GLFW_KEY_HOME, 199},
                {GLFW.GLFW_KEY_UP, 200}, {GLFW.GLFW_KEY_PAGE_UP, 201}, {GLFW.GLFW_KEY_LEFT, 203}, {GLFW.GLFW_KEY_RIGHT, 205},
                {GLFW.GLFW_KEY_END, 207}, {GLFW.GLFW_KEY_DOWN, 208}, {GLFW.GLFW_KEY_PAGE_DOWN, 209}, {GLFW.GLFW_KEY_INSERT, 210},
                {GLFW.GLFW_KEY_DELETE, 211}, {GLFW.GLFW_KEY_LEFT_SUPER, 219}, {GLFW.GLFW_KEY_RIGHT_SUPER, 220},
        };
        for (int[] pair : pairs) {
            TO_LEGACY[pair[0]] = pair[1];
            TO_GLFW[pair[1]] = pair[0];
        }
    }

    private LegacyKeys() {
    }

    /** The LWJGL 2 code of a GLFW key, 0 if 1.7.10 had none. */
    public static int toLegacy(int glfwKey) {
        return glfwKey >= 0 && glfwKey < TO_LEGACY.length ? TO_LEGACY[glfwKey] : 0;
    }

    /** The GLFW key of an LWJGL 2 code, or -1 (unknown key). */
    public static int toGlfw(int legacyKey) {
        int glfw = legacyKey >= 0 && legacyKey < TO_GLFW.length ? TO_GLFW[legacyKey] : 0;
        return glfw == 0 ? -1 : glfw;
    }

    /** Keys that type a character: their key event waits for the character (legacy keyTyped takes both). */
    public static boolean typesCharacter(int glfwKey) {
        return glfwKey >= GLFW.GLFW_KEY_SPACE && glfwKey <= GLFW.GLFW_KEY_GRAVE_ACCENT
                || glfwKey >= GLFW.GLFW_KEY_KP_0 && glfwKey <= GLFW.GLFW_KEY_KP_EQUAL && glfwKey != GLFW.GLFW_KEY_KP_ENTER;
    }

    /** Installs the live key, button and pointer queries for legacy code (once). */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        Minecraft minecraft = Minecraft.getInstance();
        IntPredicate keys = key -> {
            int glfw = key >= 0 && key < TO_GLFW.length ? TO_GLFW[key] : 0;
            return glfw != 0 && InputConstants.isKeyDown(minecraft.getWindow().getWindow(), glfw);
        };
        IntPredicate buttons = button -> GLFW.glfwGetMouseButton(minecraft.getWindow().getWindow(), button) == GLFW.GLFW_PRESS;
        IntSupplier x = () -> (int) minecraft.mouseHandler.xpos();
        IntSupplier y = () -> {
            Window window = minecraft.getWindow();
            return window.getHeight() - (int) minecraft.mouseHandler.ypos(); // LWJGL 2 measured from the bottom
        };
        try {
            Class<?> input = RetrofitMod.runtime().rootLoader().loadClass("retrofit.legacy.gl.Input");
            input.getMethod("setHost", IntPredicate.class, IntPredicate.class, IntSupplier.class, IntSupplier.class)
                    .invoke(null, keys, buttons, x, y);
        } catch (ReflectiveOperationException e) {
            LOGGER.error("Legacy GUIs won't see the keyboard and mouse", e);
        }
    }
}
