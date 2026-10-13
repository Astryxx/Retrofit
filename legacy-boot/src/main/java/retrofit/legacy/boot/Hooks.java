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
            Method reload = manager.getClass().getMethod("func_110541_a", List.class); // reloadResources
            reload.invoke(manager, packs);
        } catch (ReflectiveOperationException e) {
            System.out.println("[Retrofit] Could not load legacy resource packs: " + e);
        }
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
