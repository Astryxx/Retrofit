package retrofit.legacy.bridge;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which mod a class is part of, by FML's own record of the packages each mod's jar holds
 * (LoadController.packageOwners, which its call-stack guess at the active mod uses too).
 */
final class ModOwners {
    /** The mods that are part of the game itself; their classes belong to no mod here (see ModFailures.CORE). */
    static final Set<String> CORE = new HashSet<String>(Arrays.asList("mcp", "FML", "Forge", "Minecraft"));

    /** Internal class name -> mod id, or "" for classes no mod owns. */
    private static final Map<String, String> BY_CLASS = new ConcurrentHashMap<String, String>();

    private ModOwners() {
    }

    /** The id of the mod owning a class ("com/teammetallurgy/atum/Atum" or dotted), or null for the game's own and unknown ones. */
    static String of(String className) {
        String known = BY_CLASS.get(className);
        if (known != null) {
            return known.isEmpty() ? null : known;
        }
        String dotted = className.replace('/', '.');
        int dot = dotted.lastIndexOf('.');
        String owner = ownerOfPackage(dot < 0 ? "" : dotted.substring(0, dot));
        if (owner != null || Loader.isModLoaded("Forge")) { // (before mods are known, nothing is remembered)
            BY_CLASS.put(className, owner == null ? "" : owner);
        }
        return owner;
    }

    private static String ownerOfPackage(String packageName) {
        try {
            java.lang.reflect.Field controllerField = Loader.class.getDeclaredField("modController");
            controllerField.setAccessible(true);
            Object controller = controllerField.get(Loader.instance());
            if (controller == null) {
                return null;
            }
            java.lang.reflect.Field owners = controller.getClass().getDeclaredField("packageOwners");
            owners.setAccessible(true);
            Object table = owners.get(controller);
            if (table == null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Iterator<?> mods = ((com.google.common.collect.Multimap<String, ?>) table).get(packageName).iterator();
            String id = mods.hasNext() ? ((ModContainer) mods.next()).getModId() : null;
            return id == null || CORE.contains(id) ? null : id;
        } catch (Throwable t) {
            return null;
        }
    }
}
