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
        // defaultResourcePacks: mods add packs of their own in preInit (Resource Loader); loaded with the rest (Hooks.refreshResources)
        setField(minecraftClass, minecraft, "field_110449_ao", new ArrayList<Object>());
        setField(minecraftClass, null, "field_71432_P", minecraft);    // theMinecraft

        Class<?> gameSettingsClass = Class.forName("net.minecraft.client.settings.GameSettings", true, lcl);
        // The no-argument constructor gives default settings and key bindings without reading options.txt.
        Object gameSettings = gameSettingsClass.getConstructor().newInstance();
        setField(gameSettingsClass, gameSettings, "field_74317_L", minecraft); // mc: left null by that constructor (Galacticraft reads it)
        setField(minecraftClass, minecraft, "field_71474_y", gameSettings); // gameSettings

        Object serializer = Class.forName("net.minecraft.client.resources.data.IMetadataSerializer", true, lcl).newInstance();
        Object resourceManager = Class.forName("net.minecraft.client.resources.SimpleReloadableResourceManager", true, lcl)
                .getConstructors()[0].newInstance(serializer);
        setField(minecraftClass, minecraft, "field_110451_am", resourceManager); // mcResourceManager
        setField(minecraftClass, minecraft, "field_110452_an", serializer); // metadataSerializer_
        setField(minecraftClass, minecraft, "field_135017_as", languageManager(lcl, serializer)); // mcLanguageManager
        // The signed-in player (mods read it during loading, e.g. DragonAPI); offline-style if unknown.
        String name = System.getProperty("retrofit.username", "Player");
        String uuid = System.getProperty("retrofit.uuid", "");
        if (uuid.isEmpty()) {
            uuid = java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", "");
        }
        setField(minecraftClass, minecraft, "field_71449_j", Class.forName("net.minecraft.util.Session", true, lcl) // session
                .getConstructor(String.class, String.class, String.class, String.class).newInstance(name, uuid, "-", "legacy"));

        // OpenGlHelper.initializeTextures ran before mods loaded; mods branch on shader support (see retrofit.legacy.gl.Shaders).
        setField(Class.forName("net.minecraft.client.renderer.OpenGlHelper", true, lcl), null, "field_148824_g", true); // shadersSupported

        Class<?> clientHandlerClass = Class.forName("cpw.mods.fml.client.FMLClientHandler", true, lcl);
        Object clientHandler = clientHandlerClass.getMethod("instance").invoke(null);

        System.out.println("[Retrofit] Starting FML mod loading (construct + preInit)");
        method(clientHandlerClass, "beginMinecraftLoading").invoke(clientHandler, minecraft, new ArrayList<Object>(), resourceManager);
        // 1.7.10 had its texture manager, font renderer and sound handler before init (mods measure text and
        // bind textures while loading); the bridge's legacy client environment provides them.
        // (Absent only when booting without the bridge, to dump the SRG jar.)
        Class<?> clientGuis = null;
        try {
            clientGuis = Class.forName("retrofit.legacy.bridge.ClientGuis", true, lcl);
        } catch (ClassNotFoundException noBridge) {
            System.out.println("[Retrofit] No legacy bridge; skipping the client environment");
        }
        if (clientGuis != null) {
            clientGuis.getMethod("prepareEnvironment").invoke(null);
            registerBridgeContent(lcl);
        }
        System.out.println("[Retrofit] Running init + postInit");
        method(clientHandlerClass, "finishMinecraftLoading").invoke(clientHandler);

        report(lcl, start);
    }

    /** Retrofit's own legacy content (the item that carries 1.20.1 items), registered alongside the mods' (after preInit). */
    static void registerBridgeContent(ClassLoader lcl) {
        try {
            Class.forName("retrofit.legacy.bridge.ModernContent", true, lcl).getMethod("register").invoke(null);
        } catch (ClassNotFoundException noBridge) {
            // booting without the bridge (dumping the SRG jar)
        } catch (ReflectiveOperationException e) {
            System.out.println("[Retrofit] Could not register Retrofit's legacy content: " + e);
        }
    }

    /**
     * 1.7.10 created its LanguageManager before mods loaded, and mods ask it for the current
     * language during loading (Tinkers' manuals, Ars Magica 2). The current language (the player's,
     * retrofit.language) is registered so getCurrentLanguage never returns null; translations
     * themselves are loaded by Hooks.loadLanguage.
     */
    @SuppressWarnings("unchecked")
    private static Object languageManager(ClassLoader lcl, Object serializer) throws Exception {
        String code = System.getProperty("retrofit.language", "en_US");
        Class<?> serializerClass = Class.forName("net.minecraft.client.resources.data.IMetadataSerializer", true, lcl);
        Class<?> managerClass = Class.forName("net.minecraft.client.resources.LanguageManager", true, lcl);
        Object manager = managerClass.getConstructor(serializerClass, String.class).newInstance(serializer, code);
        Class<?> languageClass = Class.forName("net.minecraft.client.resources.Language", true, lcl);
        java.lang.reflect.Constructor<?> language = languageClass.getConstructor(String.class, String.class, String.class, boolean.class);
        for (Field field : managerClass.getDeclaredFields()) {
            if (java.util.Map.class.isAssignableFrom(field.getType()) && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                java.util.Map<String, Object> languages = (java.util.Map<String, Object>) field.get(manager);
                languages.put("en_US", language.newInstance("en_US", "US", "English", false));
                if (!code.equals("en_US")) {
                    String region = code.indexOf('_') >= 0 ? code.substring(code.indexOf('_') + 1).toUpperCase() : "";
                    languages.put(code, language.newInstance(code, region, code, false));
                }
            }
        }
        return manager;
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
