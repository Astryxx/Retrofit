package retrofit.legacy.boot;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Static entry points called from transformed legacy code. */
public final class Hooks {
    private Hooks() {
    }

    /**
     * Replaces Minecraft.refreshResources without a window: loads the vanilla 1.7.10 assets and
     * every mod's resource pack (FML adds them to its list) into the resource manager, so mods
     * can load models, sounds and other assets. Texture and render reloads are skipped; the host
     * renders.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void refreshResources(Object minecraft) {
        try {
            ClassLoader game = minecraft.getClass().getClassLoader();
            Class<?> clientHandler = Class.forName("cpw.mods.fml.client.FMLClientHandler", true, game);
            Field packsField = clientHandler.getDeclaredField("resourcePackList");
            packsField.setAccessible(true);
            List modPacks = (List) packsField.get(clientHandler.getMethod("instance").invoke(null));

            List packs = new ArrayList();
            packs.add(Class.forName("net.minecraft.client.resources.DefaultResourcePack", true, game)
                    .getConstructor(Map.class).newInstance(new HashMap())); // vanilla assets, from the 1.7.10 jar
            if (modPacks != null) {
                packs.addAll(modPacks);
            }
            Field managerField = minecraft.getClass().getDeclaredField("field_110451_am"); // mcResourceManager
            managerField.setAccessible(true);
            Object manager = managerField.get(minecraft);
            // Mods' reload listeners are told afterwards, one by one (notifyModListeners): during the
            // reload itself one that threw (FiskHeroes' texture map) kept the rest from ever being told.
            Field listenersField = manager.getClass().getDeclaredField("field_110546_b"); // reloadListeners
            listenersField.setAccessible(true);
            List listeners = (List) listenersField.get(manager);
            List all = new ArrayList(listeners);
            List modListeners = new ArrayList();
            for (Object listener : all) {
                if (!listener.getClass().getName().startsWith("net.minecraft.")) {
                    modListeners.add(listener);
                }
            }
            listeners.removeAll(modListeners);
            Method reload = manager.getClass().getMethod("func_110541_a", List.class); // reloadResources
            try {
                reload.invoke(manager, packs);
            } finally {
                listeners.clear();
                listeners.addAll(all);
            }
            loadLanguage(game, manager);
            registerIcons(game);
            notifyModListeners(game, manager);
        } catch (ReflectiveOperationException e) {
            System.out.println("[Retrofit] Could not load legacy resource packs: " + e);
        }
    }

    /**
     * On 1.7.10 the texture maps were reloaded (every block and item registering its icons) before
     * mods' own reload listeners ran, and those read icons (FiskHeroes reads water's). Once mods
     * are loaded, the bridge's icon registration runs here (see retrofit.legacy.bridge.LegacyIcons).
     */
    private static void registerIcons(ClassLoader game) {
        try {
            Class<?> loader = Class.forName("cpw.mods.fml.common.Loader", true, game);
            Class<?> state = Class.forName("cpw.mods.fml.common.LoaderState", true, game);
            Object postInit = state.getField("POSTINITIALIZATION").get(null);
            Object instance = loader.getMethod("instance").invoke(null);
            if (!(Boolean) loader.getMethod("hasReachedState", state).invoke(instance, postInit)) {
                return;
            }
            Object failed = Class.forName("retrofit.legacy.bridge.LegacyIcons", true, game).getMethod("registerAll").invoke(null);
            if (((Integer) failed) > 0) {
                System.out.println("[Retrofit] " + failed + " blocks and items could not register their icons");
            }
        } catch (ClassNotFoundException noBridge) {
            // booting without the bridge (dumping the SRG jar)
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Retrofit] Could not register legacy icons: " + e);
        }
    }

    /**
     * 1.7.10's language manager was the first reload listener: translations were in place before
     * mods' own listeners ran, and some add to them there (IC2 copies its .properties translations
     * into the Locale). The Locale is made after the reload here, so mods' listeners run after it, each on its own.
     */
    @SuppressWarnings("rawtypes")
    private static void notifyModListeners(ClassLoader game, Object manager) {
        try {
            Field listenersField = manager.getClass().getDeclaredField("field_110546_b"); // reloadListeners
            listenersField.setAccessible(true);
            Class<?> listenerType = Class.forName("net.minecraft.client.resources.IResourceManagerReloadListener", true, game);
            Class<?> managerType = Class.forName("net.minecraft.client.resources.IResourceManager", true, game);
            Method onReload = listenerType.getMethod("func_110549_a", managerType); // onResourceManagerReload
            for (Object listener : new ArrayList((List) listenersField.get(manager))) {
                if (listener.getClass().getName().startsWith("net.minecraft.")) {
                    continue; // vanilla listeners did their part in the reload
                }
                try {
                    onReload.invoke(listener, manager);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    Throwable cause = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
                    System.out.println("[Retrofit] A resource reload listener failed: " + listener.getClass().getName() + ": " + cause);
                }
            }
        } catch (ReflectiveOperationException e) {
            System.out.println("[Retrofit] Could not tell mods about the loaded translations: " + e);
        }
    }

    /**
     * Legacy client translations (I18n, used by GUIs): 1.7.10's LanguageManager loaded them on
     * resource reload. English always, plus the player's language (retrofit.language, set by the
     * host from its options, in 1.7.10's "xx_YY" form).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void loadLanguage(ClassLoader game, Object manager) {
        try {
            Class<?> localeClass = Class.forName("net.minecraft.client.resources.Locale", true, game);
            Object locale = localeClass.getConstructor().newInstance();
            List languages = new ArrayList();
            languages.add("en_US");
            String language = System.getProperty("retrofit.language", "en_US");
            if (!language.equals("en_US")) {
                languages.add(language);
            }
            Class<?> managerType = Class.forName("net.minecraft.client.resources.IResourceManager", true, game);
            localeClass.getMethod("func_135022_a", managerType, List.class).invoke(locale, manager, languages); // loadLocaleDataFiles
            Method set = Class.forName("net.minecraft.client.resources.I18n", true, game).getDeclaredMethod("func_135051_a", localeClass);
            set.setAccessible(true);
            set.invoke(null, locale); // I18n.setLocale
            // StatCollector (translateToLocal) reads StringTranslate, which 1.7.10 refilled from the same data.
            java.lang.reflect.Field properties = localeClass.getDeclaredField("field_135032_a");
            properties.setAccessible(true);
            Method replace = Class.forName("net.minecraft.util.StringTranslate", true, game).getDeclaredMethod("func_135063_a", java.util.Map.class);
            replace.setAccessible(true);
            replace.invoke(null, properties.get(locale)); // StringTranslate.replaceWith
        } catch (ReflectiveOperationException e) {
            System.out.println("[Retrofit] Could not load legacy translations for GUIs: " + e);
        }
    }

    /** Replaces TextureManager.bindTexture: records the texture for the GL emulation. */
    public static void bindTexture(Object location) {
        retrofit.legacy.gl.GL11.bindLegacyTexture(String.valueOf(location));
    }

    /** Replaces FML's StartupQuery.confirm: no one can be asked, so continue (as most players would). */
    public static boolean startupConfirm(String text) {
        System.out.println("[Retrofit] Legacy FML asked: " + text.replace('\n', ' ') + " -> continuing");
        return true;
    }

    /** Replaces FML's StartupQuery.notify. */
    public static void startupNotify(String text) {
        System.out.println("[Retrofit] Legacy FML: " + text.replace('\n', ' '));
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
