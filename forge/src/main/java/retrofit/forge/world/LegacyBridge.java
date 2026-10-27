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
    /** Classes of the legacy bridge whose public static methods the host calls by name. */
    private static final String[] ENTRY_CLASSES = {
            "retrofit.legacy.bridge.Bridge", "retrofit.legacy.bridge.Interactions", "retrofit.legacy.bridge.LegacyPlayers",
            "retrofit.legacy.bridge.Recipes", "retrofit.legacy.bridge.Guis", "retrofit.legacy.bridge.Network",
            "retrofit.legacy.bridge.Events", "retrofit.legacy.bridge.WorldGen", "retrofit.legacy.bridge.Entities",
            "retrofit.legacy.bridge.PlayerState", "retrofit.legacy.bridge.LivingEvents", "retrofit.legacy.bridge.Dimensions",
            "retrofit.legacy.bridge.Energy", "retrofit.legacy.bridge.HostCreatures", "retrofit.legacy.bridge.ModernContent",
            "retrofit.legacy.bridge.Fluids", "retrofit.legacy.bridge.Achievements", "retrofit.legacy.bridge.Villagers",
    };
    /** Entry classes that name client-only legacy types; dedicated servers must never load them. */
    private static final String[] CLIENT_ENTRY_CLASSES = {"retrofit.legacy.bridge.ClientGuis", "retrofit.legacy.bridge.ClientEvents",
            "retrofit.legacy.bridge.ClientTextures", "retrofit.legacy.bridge.ModernIcons", "retrofit.legacy.bridge.ClientMods"};
    private static final Map<String, MethodHandle> HANDLES = new HashMap<>();
    private static MethodHandle tick;
    private static MethodHandle canUpdate;
    private static MethodHandle renderTileEntity;
    private static MethodHandle prepareFrame;
    private static Object lock;

    private LegacyBridge() {
    }

    public static synchronized void init(LegacyRuntime runtime) throws ReflectiveOperationException {
        if (!HANDLES.isEmpty()) {
            return;
        }
        Class<?> bridge = Class.forName("retrofit.legacy.bridge.Bridge", true, runtime.gameLoader());
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        java.util.List<String> entries = new java.util.ArrayList<>(java.util.List.of(ENTRY_CLASSES));
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            entries.addAll(java.util.List.of(CLIENT_ENTRY_CLASSES));
        }
        for (String name : entries) {
            Class<?> entry = Class.forName(name, true, runtime.gameLoader());
            for (Method method : entry.getMethods()) {
                if (method.getDeclaringClass() == entry && java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        && HANDLES.put(method.getName(), lookup.unreflect(method)) != null) {
                    throw new IllegalStateException("Two legacy bridge entry points are called " + method.getName());
                }
            }
        }
        tick = lookup.findStatic(bridge, "tick", MethodType.methodType(void.class, Object.class));
        canUpdate = lookup.findStatic(bridge, "canUpdate", MethodType.methodType(boolean.class, Object.class));
        renderTileEntity = lookup.findStatic(bridge, "renderTileEntity", MethodType.methodType(int.class, Object.class, float.class,
                double.class, double.class, double.class));
        prepareFrame = lookup.findStatic(bridge, "prepareFrame", MethodType.methodType(void.class, Object.class,
                double.class, double.class, double.class, float.class, float.class, float.class));
        lock = bridge.getField("LOCK").get(null);
    }

    /** Whether the legacy side has booted (no legacy mods: never). */
    public static boolean isReady() {
        return !HANDLES.isEmpty();
    }

    /** Calls a legacy bridge entry point by name; legacy exceptions come out as IllegalStateException. */
    @SuppressWarnings("unchecked")
    public static <T> T call(String name, Object... args) {
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

    /** For client code outside this package: report a legacy failure once (see LegacyErrors). */
    public static void reportClientFailure(String what, RuntimeException e) {
        LegacyErrors.report(what, "running", e);
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

    // ---- Item stacks ----

    /** A legacy stack of a registered legacy item name, or null if the item doesn't exist. */
    public static Object makeStack(String name, int count, int damage, byte[] tag) {
        return call("makeStack", name, count, damage, tag);
    }

    public static Object makeStackOf(Object legacyItem, int count, int damage, byte[] tag) {
        return call("makeStackOf", legacyItem, count, damage, tag);
    }

    /** {name, count, damage, tag bytes or null}; null for an empty or missing stack. */
    public static Object[] readStack(Object legacyStack) {
        return call("readStack", legacyStack);
    }

    public static Object itemById(int id) {
        return call("itemById", id);
    }

    public static Object stackItem(Object legacyStack) {
        return call("stackItem", legacyStack);
    }

    /** {name, has subtypes 1/0, max damage} of each vanilla legacy item. */
    public static Object[][] vanillaItems() {
        return call("vanillaItems");
    }

    // ---- Client-only assets ----

    public static String[] modSources() {
        return call("modSources");
    }

    /** Every block and item, vanilla included, registers its icons; returns the number that failed. */
    public static int registerAllIcons() {
        return call("registerAllIcons");
    }

    /** Recorded icon names: kind 0 = block textures, 1 = item textures. */
    public static String[] iconNames(int kind) {
        return call("iconNames", kind);
    }

    public static void setIconUV(int kind, String name, float minU, float maxU, float minV, float maxV, int width, int height) {
        call("setIconUV", kind, name, minU, maxU, minV, maxV, width, height);
    }

    /** Six face texture names (down, up, north, south, west, east), or null. */
    public static String[] faceTextures(Object legacyBlock, int meta) {
        return call("faceTextures", legacyBlock, meta);
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

    // ---- Shapes and rendering ----

    /** {selection box, then collision boxes}, 6 values each; see Bridge.blockShape. */
    public static double[] blockShape(Object legacyBlock, int meta) {
        return call("blockShape", legacyBlock, meta);
    }

    /**
     * Serializes all use of legacy rendering (the GL emulation is one global context). Hold it
     * from a render call until the capture has been read.
     */
    public static Object renderLock() {
        return lock;
    }

    /** Places the legacy viewer at the host camera; call once per frame, holding {@link #renderLock()}. */
    public static void prepareFrame(Object legacyWorld, double x, double y, double z, float yaw, float pitch, float partialTicks) {
        try {
            prepareFrame.invokeExact(legacyWorld, x, y, z, yaw, pitch, partialTicks);
        } catch (Throwable t) {
            throw new IllegalStateException("Retrofit's legacy bridge failed in prepareFrame", t);
        }
    }

    /** Renders a block model into the capture; returns the vertex count. Hold {@link #renderLock()}. */
    public static int bakeBlock(Object legacyBlock, int meta) {
        return call("bakeBlock", legacyBlock, meta);
    }

    public static boolean hasSpecialRenderer(Object tileEntity) {
        return call("hasSpecialRenderer", tileEntity);
    }

    /**
     * Runs a tile entity's special renderer into the capture, given where it is relative to the
     * camera; returns the vertex count. Hold {@link #renderLock()}.
     */
    public static int renderTileEntity(Object tileEntity, float partialTicks, double x, double y, double z) {
        try {
            return (int) renderTileEntity.invokeExact(tileEntity, partialTicks, x, y, z);
        } catch (Throwable t) {
            throw new IllegalStateException("Retrofit's legacy bridge failed in renderTileEntity", t);
        }
    }

    /** type: INVENTORY, ENTITY, EQUIPPED or EQUIPPED_FIRST_PERSON. */
    public static boolean hasItemRenderer(Object legacyItem, int meta, String type) {
        return call("hasItemRenderer", legacyItem, meta, type);
    }

    /** Runs a Forge item renderer into the capture; returns the vertex count. Hold {@link #renderLock()}. */
    public static int renderItem(Object legacyItem, int meta, String type) {
        return call("renderItem", legacyItem, meta, type);
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

    /** The slots of a legacy tile entity's inventory (legacy stacks, null for empty), or null if it has none. */
    public static Object[] inventoryStacks(Object tileEntity) {
        return call("inventoryStacks", tileEntity);
    }

    /** The entity name a legacy mob spawner spawns, or null if the tile entity is no spawner. */
    public static String spawnerEntity(Object tileEntity) {
        return call("spawnerEntity", tileEntity);
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
