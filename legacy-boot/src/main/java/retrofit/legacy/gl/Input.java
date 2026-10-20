package retrofit.legacy.gl;

import java.util.function.IntPredicate;
import java.util.function.IntSupplier;

/**
 * Keyboard and mouse state for the LWJGL 2 stand-ins ({@link Keyboard}, {@link Mouse}). The host
 * owns input: it installs live queries once ({@link #setHost}) and sets the current event before
 * handing one to legacy GUI code ({@link #keyEvent}, {@link #mouseEvent}). Key codes are LWJGL 2's;
 * the host translates from GLFW. Mouse positions are window pixels with y up, as LWJGL 2 had them.
 */
public final class Input {
    private static volatile IntPredicate keyDown = key -> false;
    private static volatile IntPredicate buttonDown = button -> false;
    private static volatile IntSupplier mouseX = () -> 0;
    private static volatile IntSupplier mouseY = () -> 0;

    static int eventKey;
    static char eventCharacter;
    static boolean eventKeyState;
    static int eventButton = -1;
    static boolean eventButtonState;
    static int eventX;
    static int eventY;
    static int eventWheel;

    private Input() {
    }

    /** Called once by the host: live key and button state (LWJGL 2 codes) and the mouse position. */
    public static void setHost(IntPredicate keys, IntPredicate buttons, IntSupplier x, IntSupplier y) {
        keyDown = keys;
        buttonDown = buttons;
        mouseX = x;
        mouseY = y;
    }

    public static void keyEvent(int key, char character, boolean pressed) {
        eventKey = key;
        eventCharacter = character;
        eventKeyState = pressed;
    }

    public static void mouseEvent(int button, boolean pressed, int x, int y, int wheel) {
        eventButton = button;
        eventButtonState = pressed;
        eventX = x;
        eventY = y;
        eventWheel = wheel;
    }

    static boolean isKeyDown(int key) {
        return keyDown.test(key);
    }

    static boolean isButtonDown(int button) {
        return buttonDown.test(button);
    }

    static int mouseX() {
        return mouseX.getAsInt();
    }

    static int mouseY() {
        return mouseY.getAsInt();
    }
}
