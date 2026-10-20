package retrofit.legacy.gl;

/** Implemented subset of LWJGL 2's Display. The host owns the window; legacy code sees an open, focused one. */
public final class Display {
    private Display() {
    }

    public static boolean isCreated() {
        return true;
    }

    public static boolean isActive() {
        return true;
    }

    public static boolean isVisible() {
        return true;
    }

    public static int getWidth() {
        return 854;
    }

    public static int getHeight() {
        return 480;
    }
}
