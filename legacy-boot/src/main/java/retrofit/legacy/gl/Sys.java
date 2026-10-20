package retrofit.legacy.gl;

/** Implemented subset of LWJGL 2's Sys: its timer, which mods use for animation (the real one needs natives). */
public final class Sys {
    private Sys() {
    }

    public static long getTimerResolution() {
        return 1000;
    }

    public static long getTime() {
        return System.nanoTime() / 1_000_000L;
    }
}
