package retrofit.legacy.boot;

import java.io.File;
import java.lang.reflect.Field;

/**
 * Dedicated-server counterpart of {@link HeadlessClient}: runs FML's server loading sequence
 * (construct and preInit via onServerStart, init and postInit via onServerStarted) against a
 * legacy server that never opens a port or loads a world. The 1.20.1 server does that.
 */
public final class HeadlessServer {
    private HeadlessServer() {
    }

    public static void run(ClassLoader lcl, File gameDir) throws Throwable {
        long start = System.nanoTime();

        // Registers vanilla blocks, items and dispenser behaviours; normally done by DedicatedServer.main.
        Class.forName("net.minecraft.init.Bootstrap", true, lcl).getMethod("func_151354_b").invoke(null);

        // A real legacy MinecraftServer (Retrofit's BridgeServer, from the legacy bridge), never started.
        Class<?> minecraftServer = Class.forName("net.minecraft.server.MinecraftServer", true, lcl);
        Object server = Class.forName("retrofit.legacy.bridge.LegacyWorlds", true, lcl)
                .getMethod("create", File.class, boolean.class).invoke(null, gameDir, true);

        Class<?> commonHandler = Class.forName("cpw.mods.fml.common.FMLCommonHandler", true, lcl);
        Object handler = commonHandler.getMethod("instance").invoke(null);
        System.out.println("[Retrofit] Starting FML server mod loading (construct + preInit)");
        commonHandler.getMethod("onServerStart", minecraftServer).invoke(handler, server);
        System.out.println("[Retrofit] Running init + postInit");
        commonHandler.getMethod("onServerStarted").invoke(handler);

        HeadlessClient.report(lcl, start);
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
