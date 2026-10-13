package retrofit.legacy.boot;

/** Static entry points called from transformed legacy code. */
public final class Hooks {
    private Hooks() {
    }

    /** Replaces Minecraft.displayCrashReport so a legacy crash prints instead of opening a window. */
    public static void crash(Object crashReport) {
        try {
            Object text = crashReport.getClass().getMethod("func_71502_e").invoke(crashReport); // getCompleteReport
            System.out.println("[Retrofit] Legacy crash report:\n" + text);
        } catch (ReflectiveOperationException e) {
            System.out.println("[Retrofit] Legacy crash (report unavailable): " + crashReport);
        }
    }
}
