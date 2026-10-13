package retrofit.harness;

import retrofit.api.HostWorld;
import retrofit.host.LegacyRuntime;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Exercises the legacy half of the world bridge without Minecraft 1.20.1: an in-memory
 * {@link HostWorld}, a legacy block with a tile entity, ticking, and an NBT save/load round trip.
 */
final class WorldBridgeSmokeTest {
    private WorldBridgeSmokeTest() {
    }

    static void run(LegacyRuntime runtime) throws Exception {
        Class<?> bridge = Class.forName("retrofit.legacy.bridge.Bridge", true, runtime.gameLoader());
        MemoryWorld host = new MemoryWorld();
        Object world = call(bridge, "createWorld", host, 0);
        System.out.println("[Retrofit] Bridge test: legacy world " + world.getClass().getName()
                + ", mod blocks " + Arrays.toString((String[]) call(bridge, "modBlockNames")));

        Object block = call(bridge, "block", "hello1710:counter_block");
        host.blocks.put(key(1, 64, 1), block);
        Object tile = call(bridge, "createTileEntity", world, block, 0, 1, 64, 1);
        host.tiles.put(key(1, 64, 1), tile);
        for (int i = 0; i < 5; i++) {
            call(bridge, "tick", tile);
        }
        System.out.println("[Retrofit] Bridge test: after 5 ticks " + call(bridge, "describe", tile));

        byte[] nbt = (byte[]) call(bridge, "save", tile);
        Object reloaded = call(bridge, "load", world, nbt, 1, 64, 1);
        System.out.println("[Retrofit] Bridge test: reloaded from " + nbt.length + " bytes of NBT: " + call(bridge, "describe", reloaded));
    }

    private static Object call(Class<?> owner, String name, Object... args) throws Exception {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                return method.invoke(null, args);
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) y & 0xFFF) << 26 | ((long) z & 0x3FFFFFF);
    }

    /** A tiny world: just the blocks and tile entities the test puts in it. */
    private static final class MemoryWorld implements HostWorld {
        final Map<Long, Object> blocks = new HashMap<>();
        final Map<Long, Object> tiles = new HashMap<>();
        long time;

        @Override
        public Object getLegacyBlock(int x, int y, int z) {
            return blocks.get(key(x, y, z));
        }

        @Override
        public int getLegacyMeta(int x, int y, int z) {
            return 0;
        }

        @Override
        public boolean setLegacyBlock(int x, int y, int z, Object legacyBlock, int meta, int flags) {
            blocks.put(key(x, y, z), legacyBlock);
            return true;
        }

        @Override
        public Object getLegacyTileEntity(int x, int y, int z) {
            return tiles.get(key(x, y, z));
        }

        @Override
        public void markBlockForUpdate(int x, int y, int z) {
        }

        @Override
        public void markTileEntityChanged(int x, int y, int z) {
        }

        @Override
        public long getTotalWorldTime() {
            return time++;
        }

        @Override
        public boolean isClientSide() {
            return false;
        }
    }
}
