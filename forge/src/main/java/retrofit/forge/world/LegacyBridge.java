package retrofit.forge.world;

import retrofit.api.HostWorld;
import retrofit.host.LegacyRuntime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Typed host-side access to the legacy bridge ({@code retrofit.legacy.bridge.Bridge}, loaded in
 * the 1.7.10 world). Legacy objects are opaque {@code Object}s here.
 */
public final class LegacyBridge {
    private static MethodHandle createWorld;
    private static MethodHandle modBlockNames;
    private static MethodHandle block;
    private static MethodHandle hasTileEntity;
    private static MethodHandle createTileEntity;
    private static MethodHandle load;
    private static MethodHandle save;
    private static MethodHandle canUpdate;
    private static MethodHandle tick;
    private static MethodHandle remove;
    private static MethodHandle describe;

    private LegacyBridge() {
    }

    static synchronized void init(LegacyRuntime runtime) throws ReflectiveOperationException {
        if (createWorld != null) {
            return;
        }
        Class<?> bridge = Class.forName("retrofit.legacy.bridge.Bridge", true, runtime.gameLoader());
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        Class<?> o = Object.class;
        createWorld = lookup.findStatic(bridge, "createWorld", MethodType.methodType(o, HostWorld.class, int.class));
        modBlockNames = lookup.findStatic(bridge, "modBlockNames", MethodType.methodType(String[].class));
        block = lookup.findStatic(bridge, "block", MethodType.methodType(o, String.class));
        hasTileEntity = lookup.findStatic(bridge, "hasTileEntity", MethodType.methodType(boolean.class, o, int.class));
        createTileEntity = lookup.findStatic(bridge, "createTileEntity", MethodType.methodType(o, o, o, int.class, int.class, int.class, int.class));
        load = lookup.findStatic(bridge, "load", MethodType.methodType(o, o, byte[].class, int.class, int.class, int.class));
        save = lookup.findStatic(bridge, "save", MethodType.methodType(byte[].class, o));
        canUpdate = lookup.findStatic(bridge, "canUpdate", MethodType.methodType(boolean.class, o));
        tick = lookup.findStatic(bridge, "tick", MethodType.methodType(void.class, o));
        remove = lookup.findStatic(bridge, "remove", MethodType.methodType(void.class, o));
        describe = lookup.findStatic(bridge, "describe", MethodType.methodType(String.class, o));
    }

    public static Object createWorld(HostWorld host, int dimension) {
        try {
            return (Object) createWorld.invokeExact(host, dimension);
        } catch (Throwable t) {
            throw failure("create a legacy world", t);
        }
    }

    public static String[] modBlockNames() {
        try {
            return (String[]) modBlockNames.invokeExact();
        } catch (Throwable t) {
            throw failure("list legacy blocks", t);
        }
    }

    public static Object block(String name) {
        try {
            return (Object) block.invokeExact(name);
        } catch (Throwable t) {
            throw failure("look up legacy block " + name, t);
        }
    }

    public static boolean hasTileEntity(Object legacyBlock, int meta) {
        try {
            return (boolean) hasTileEntity.invokeExact(legacyBlock, meta);
        } catch (Throwable t) {
            throw failure("query a legacy block", t);
        }
    }

    public static Object createTileEntity(Object world, Object legacyBlock, int meta, int x, int y, int z) {
        try {
            return (Object) createTileEntity.invokeExact(world, legacyBlock, meta, x, y, z);
        } catch (Throwable t) {
            throw failure("create a legacy tile entity", t);
        }
    }

    public static Object load(Object world, byte[] nbt, int x, int y, int z) {
        try {
            return (Object) load.invokeExact(world, nbt, x, y, z);
        } catch (Throwable t) {
            throw failure("load a legacy tile entity", t);
        }
    }

    public static byte[] save(Object tileEntity) {
        try {
            return (byte[]) save.invokeExact(tileEntity);
        } catch (Throwable t) {
            throw failure("save a legacy tile entity", t);
        }
    }

    public static boolean canUpdate(Object tileEntity) {
        try {
            return (boolean) canUpdate.invokeExact(tileEntity);
        } catch (Throwable t) {
            throw failure("query a legacy tile entity", t);
        }
    }

    public static void tick(Object tileEntity) {
        try {
            tick.invokeExact(tileEntity);
        } catch (Throwable t) {
            throw failure("tick a legacy tile entity", t);
        }
    }

    public static void remove(Object tileEntity) {
        try {
            remove.invokeExact(tileEntity);
        } catch (Throwable t) {
            throw failure("remove a legacy tile entity", t);
        }
    }

    public static String describe(Object legacyObject) {
        try {
            return (String) describe.invokeExact(legacyObject);
        } catch (Throwable t) {
            throw failure("describe a legacy object", t);
        }
    }

    private static RuntimeException failure(String action, Throwable cause) {
        return new IllegalStateException("Retrofit could not " + action, cause);
    }
}
