package retrofit.forge.world;

import retrofit.api.HostWorld;
import retrofit.host.LegacyRuntime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Typed host-side access to the legacy bridge ({@code retrofit.legacy.bridge.Bridge}, loaded in
 * the 1.7.10 world). Legacy objects are opaque {@code Object}s here. Per-tick calls use exact
 * method handles; everything else goes through {@link #call}.
 */
public final class LegacyBridge {
    private static final Map<String, MethodHandle> HANDLES = new HashMap<>();
    private static MethodHandle tick;
    private static MethodHandle canUpdate;

    private LegacyBridge() {
    }

    static synchronized void init(LegacyRuntime runtime) throws ReflectiveOperationException {
        if (!HANDLES.isEmpty()) {
            return;
        }
        Class<?> bridge = Class.forName("retrofit.legacy.bridge.Bridge", true, runtime.gameLoader());
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        for (Method method : bridge.getMethods()) {
            if (method.getDeclaringClass() == bridge) {
                HANDLES.put(method.getName(), lookup.unreflect(method));
            }
        }
        tick = lookup.findStatic(bridge, "tick", MethodType.methodType(void.class, Object.class));
        canUpdate = lookup.findStatic(bridge, "canUpdate", MethodType.methodType(boolean.class, Object.class));
    }

    @SuppressWarnings("unchecked")
    private static <T> T call(String name, Object... args) {
        MethodHandle handle = HANDLES.get(name);
        if (handle == null) {
            throw new IllegalStateException("Retrofit's legacy bridge has no " + name + " (not booted?)");
        }
        try {
            return (T) handle.invokeWithArguments(args);
        } catch (Throwable t) {
            throw new IllegalStateException("Retrofit's legacy bridge failed in " + name, t);
        }
    }

    // ---- Worlds and blocks ----

    public static Object createWorld(HostWorld host, int dimension) {
        return call("createWorld", host, dimension);
    }

    public static String[] modBlockNames() {
        return call("modBlockNames");
    }

    public static Object block(String name) {
        return call("block", name);
    }

    /** Legacy block for a numeric id, or null if unused. */
    public static Object blockById(int id) {
        return call("blockById", id);
    }

    public static int blockId(Object legacyBlock) {
        return call("blockId", legacyBlock);
    }

    /** Legacy registry name, e.g. "minecraft:planks". */
    public static String blockName(Object legacyBlock) {
        return call("blockName", legacyBlock);
    }

    public static String blockTranslationKey(Object legacyBlock) {
        return call("blockTranslationKey", legacyBlock);
    }

    /** {render type, opaque cube 1/0, render pass, light value}. */
    public static int[] blockRenderInfo(Object legacyBlock) {
        return call("blockRenderInfo", legacyBlock);
    }

    public static boolean hasTileEntity(Object legacyBlock, int meta) {
        return call("hasTileEntity", legacyBlock, meta);
    }

    // ---- Items ----

    public static String[] modItemNames() {
        return call("modItemNames");
    }

    public static Object item(String name) {
        return call("item", name);
    }

    /** {max stack size, max damage, has subtypes 1/0}. */
    public static int[] itemInfo(Object legacyItem) {
        return call("itemInfo", legacyItem);
    }

    public static String itemTranslationKey(Object legacyItem, int meta) {
        return call("itemTranslationKey", legacyItem, meta);
    }

    // ---- Client-only assets ----

    public static String[] modSources() {
        return call("modSources");
    }

    public static int registerIcons(Object[] legacyBlocks) {
        return call("registerIcons", (Object) legacyBlocks);
    }

    /** Six face texture names (down, up, north, south, west, east), or null. */
    public static String[] faceTextures(Object legacyBlock, int meta) {
        return call("faceTextures", legacyBlock, meta);
    }

    public static int registerItemIcons(Object[] legacyItems) {
        return call("registerItemIcons", (Object) legacyItems);
    }

    public static String itemTexture(Object legacyItem, int meta) {
        return call("itemTexture", legacyItem, meta);
    }

    public static int[] itemSubtypes(Object legacyItem) {
        return call("itemSubtypes", legacyItem);
    }

    /** {language, key, value} triples registered in code. */
    public static String[][] codeTranslations() {
        return call("codeTranslations");
    }

    // ---- Tile entities ----

    public static Object createTileEntity(Object world, Object legacyBlock, int meta, int x, int y, int z) {
        return call("createTileEntity", world, legacyBlock, meta, x, y, z);
    }

    public static Object load(Object world, byte[] nbt, int x, int y, int z) {
        return call("load", world, nbt, x, y, z);
    }

    public static byte[] save(Object tileEntity) {
        return call("save", tileEntity);
    }

    /** NBT bytes of the tile entity's 1.7.10 description packet, or null if it sends none. */
    public static byte[] descriptionTag(Object tileEntity) {
        return call("descriptionTag", tileEntity);
    }

    public static void applyDescription(Object tileEntity, byte[] description) {
        call("applyDescription", tileEntity, description);
    }

    public static void remove(Object tileEntity) {
        call("remove", tileEntity);
    }

    public static String describe(Object legacyObject) {
        return call("describe", legacyObject);
    }

    public static boolean canUpdate(Object tileEntity) {
        try {
            return (boolean) canUpdate.invokeExact(tileEntity);
        } catch (Throwable t) {
            throw new IllegalStateException("Retrofit's legacy bridge failed in canUpdate", t);
        }
    }

    public static void tick(Object tileEntity) {
        try {
            tick.invokeExact(tileEntity);
        } catch (Throwable t) {
            throw new IllegalStateException("Retrofit's legacy bridge failed in tick", t);
        }
    }
}
