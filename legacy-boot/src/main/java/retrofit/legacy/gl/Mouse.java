package retrofit.legacy.gl;

/**
 * Implemented subset of LWJGL 2's Mouse, over the host's input (see {@link Input}). The event
 * accessors describe the one event the host is handing to legacy code; next() reports none.
 */
public final class Mouse {
    private Mouse() {
    }

    public static boolean isCreated() {
        return true;
    }

    public static int getX() {
        return Input.mouseX();
    }

    public static int getY() {
        return Input.mouseY();
    }

    public static boolean isButtonDown(int button) {
        return Input.isButtonDown(button);
    }

    public static int getButtonCount() {
        return 3;
    }

    public static boolean next() {
        return false;
    }

    public static int getEventButton() {
        return Input.eventButton;
    }

    public static boolean getEventButtonState() {
        return Input.eventButtonState;
    }

    public static int getEventX() {
        return Input.eventX;
    }

    public static int getEventY() {
        return Input.eventY;
    }

    public static int getEventDWheel() {
        return Input.eventWheel;
    }

    /** Wheel movement since the last call: only the current event's. */
    public static int getDWheel() {
        int wheel = Input.eventWheel;
        Input.eventWheel = 0;
        return wheel;
    }

    public static boolean isGrabbed() {
        return false;
    }

    public static boolean isInsideWindow() {
        return true;
    }
}
