package retrofit.legacy.compat;

/**
 * Threads legacy mods start (Thread.start, redirected here by RetrofitTransformer) are daemon
 * threads. 1.7.10 ended with System.exit, so mods never stopped their background threads
 * (GregTech's activity logger sleeps forever); in a shared JVM those would keep a stopped 1.20.1
 * server, or the game, from exiting.
 */
public final class LegacyThreads {
    private LegacyThreads() {
    }

    public static void start(Thread thread) {
        if (!thread.isDaemon() && thread.getState() == Thread.State.NEW) {
            thread.setDaemon(true);
        }
        thread.start();
    }
}
