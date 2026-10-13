package retrofit.legacy.boot;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Boot test: runs FML's client loading sequence (construct, preInit, init, postInit)
 * against a Minecraft instance that never opens a window.
 *
 * Everything goes through reflection so this class compiles without the deobfuscated
 * 1.7.10 jar; names are SRG because the LaunchClassLoader deobfuscates at load time.
 */
public final class HeadlessClient {
    private HeadlessClient() {
    }

    public static void run(ClassLoader lcl, File gameDir) throws Throwable {
        long start = System.nanoTime();

        // Registers vanilla blocks, items and dispenser behaviours; normally done by Minecraft's constructor.
        Class.forName("net.minecraft.init.Bootstrap", true, lcl).getMethod("func_151354_b").invoke(null);

        Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft", true, lcl);
        Object minecraft = allocate(minecraftClass);
        setField(minecraftClass, minecraft, "field_71412_D", gameDir); // mcDataDir
        setField(minecraftClass, null, "field_71432_P", minecraft);    // theMinecraft

        Class<?> gameSettingsClass = Class.forName("net.minecraft.client.settings.GameSettings", true, lcl);
        // The no-argument constructor gives default settings and key bindings without reading options.txt.
        setField(minecraftClass, minecraft, "field_71474_y", gameSettingsClass.getConstructor().newInstance()); // gameSettings

        Object serializer = Class.forName("net.minecraft.client.resources.data.IMetadataSerializer", true, lcl).newInstance();
        Object resourceManager = Class.forName("net.minecraft.client.resources.SimpleReloadableResourceManager", true, lcl)
                .getConstructors()[0].newInstance(serializer);
        setField(minecraftClass, minecraft, "field_110451_am", resourceManager); // mcResourceManager

        Class<?> clientHandlerClass = Class.forName("cpw.mods.fml.client.FMLClientHandler", true, lcl);
        Object clientHandler = clientHandlerClass.getMethod("instance").invoke(null);

        System.out.println("[Retrofit] Starting FML mod loading (construct + preInit)");
        method(clientHandlerClass, "beginMinecraftLoading").invoke(clientHandler, minecraft, new ArrayList<Object>(), resourceManager);
        System.out.println("[Retrofit] Running init + postInit");
        method(clientHandlerClass, "finishMinecraftLoading").invoke(clientHandler);

        report(lcl, start);
    }

    static void report(ClassLoader lcl, long start) throws Exception {
        Class<?> loaderClass = Class.forName("cpw.mods.fml.common.Loader", true, lcl);
        Object loader = loaderClass.getMethod("instance").invoke(null);
        List<?> mods = (List<?>) loaderClass.getMethod("getActiveModList").invoke(loader);
        Method getModState = loaderClass.getMethod("getModState", Class.forName("cpw.mods.fml.common.ModContainer", true, lcl));

        StringBuilder modList = new StringBuilder();
        for (Object mod : mods) {
            Object modId = mod.getClass().getMethod("getModId").invoke(mod);
            Object state = getModState.invoke(loader, mod);
            modList.append("\n    ").append(modId).append(" -> ").append(state);
        }

        Class<?> gameData = Class.forName("cpw.mods.fml.common.registry.GameData", true, lcl);
        Object blocks = gameData.getMethod("getBlockRegistry").invoke(null);
        Object items = gameData.getMethod("getItemRegistry").invoke(null);
        int blockCount = ((Collection<?>) method(blocks.getClass(), "func_148742_b").invoke(blocks)).size(); // getKeys
        int itemCount = ((Collection<?>) method(items.getClass(), "func_148742_b").invoke(items)).size();

        Field stone = Class.forName("net.minecraft.init.Blocks", true, lcl).getField("field_150348_b"); // Blocks.stone
        Object stoneBlock = stone.get(null);

        Runtime rt = Runtime.getRuntime();
        System.gc();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long millis = (System.nanoTime() - start) / 1_000_000;

        Object logFactory = Class.forName("org.apache.logging.log4j.LogManager", true, lcl).getMethod("getFactory").invoke(null);
        System.out.println("[Retrofit] Legacy log4j factory: " + logFactory.getClass().getName());
        System.out.println("[Retrofit] ===== BOOT OK =====");
        System.out.println("[Retrofit] Mods (" + mods.size() + "):" + modList);
        System.out.println("[Retrofit] Registered blocks: " + blockCount + ", items: " + itemCount
                + "; Blocks.stone = " + (stoneBlock == null ? "null" : stoneBlock.getClass().getName()));
        System.out.println("[Retrofit] FML loading took " + millis + " ms, heap in use after GC: " + usedMb + " MB");
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

    private static Method method(Class<?> owner, String name) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new IllegalArgumentException("No method " + name + " on " + owner.getName());
    }
}
