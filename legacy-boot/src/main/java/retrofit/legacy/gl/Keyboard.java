package retrofit.legacy.gl;

import java.util.HashMap;
import java.util.Map;

/**
 * Implemented subset of LWJGL 2's Keyboard, over the host's input (see {@link Input}). There is
 * no event queue: the host hands legacy GUIs one event at a time, so next() reports none.
 */
public final class Keyboard {
    private static final String[] NAMES = new String[256];
    private static final Map<String, Integer> CODES = new HashMap<String, Integer>();

    static {
        String[] names = {
                null, "ESCAPE", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "MINUS", "EQUALS", "BACK", "TAB",
                "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "LBRACKET", "RBRACKET", "RETURN", "LCONTROL", "A", "S",
                "D", "F", "G", "H", "J", "K", "L", "SEMICOLON", "APOSTROPHE", "GRAVE", "LSHIFT", "BACKSLASH", "Z", "X", "C", "V",
                "B", "N", "M", "COMMA", "PERIOD", "SLASH", "RSHIFT", "MULTIPLY", "LMENU", "SPACE", "CAPITAL", "F1", "F2", "F3", "F4", "F5",
                "F6", "F7", "F8", "F9", "F10", "NUMLOCK", "SCROLL", "NUMPAD7", "NUMPAD8", "NUMPAD9", "SUBTRACT", "NUMPAD4", "NUMPAD5",
                "NUMPAD6", "ADD", "NUMPAD1", "NUMPAD2", "NUMPAD3", "NUMPAD0", "DECIMAL",
        };
        for (int i = 0; i < names.length; i++) {
            NAMES[i] = names[i];
        }
        NAMES[87] = "F11";
        NAMES[88] = "F12";
        NAMES[156] = "NUMPADENTER";
        NAMES[157] = "RCONTROL";
        NAMES[181] = "DIVIDE";
        NAMES[184] = "RMENU";
        NAMES[197] = "PAUSE";
        NAMES[199] = "HOME";
        NAMES[200] = "UP";
        NAMES[201] = "PRIOR";
        NAMES[203] = "LEFT";
        NAMES[205] = "RIGHT";
        NAMES[207] = "END";
        NAMES[208] = "DOWN";
        NAMES[209] = "NEXT";
        NAMES[210] = "INSERT";
        NAMES[211] = "DELETE";
        NAMES[219] = "LMETA";
        NAMES[220] = "RMETA";
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i] != null) {
                CODES.put(NAMES[i], i);
            }
        }
    }

    private Keyboard() {
    }

    public static boolean isCreated() {
        return true;
    }

    public static boolean isKeyDown(int key) {
        return key > 0 && Input.isKeyDown(key);
    }

    public static boolean next() {
        return false;
    }

    public static int getEventKey() {
        return Input.eventKey;
    }

    public static char getEventCharacter() {
        return Input.eventCharacter;
    }

    public static boolean getEventKeyState() {
        return Input.eventKeyState;
    }

    public static boolean isRepeatEvent() {
        return false;
    }

    public static String getKeyName(int key) {
        return key >= 0 && key < NAMES.length && NAMES[key] != null ? NAMES[key] : null;
    }

    public static int getKeyIndex(String name) {
        Integer code = CODES.get(name);
        return code == null ? 0 : code;
    }

    public static int getKeyCount() {
        return NAMES.length;
    }
}
