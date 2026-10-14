package retrofit.legacy.bridge;

import cpw.mods.fml.common.IWorldGenerator;
import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import retrofit.api.HostWorld;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * World generation by legacy mods (GameRegistry.registerWorldGenerator: ores, plants,
 * structures). The host runs it for each new chunk with a HostWorld over the region being
 * generated. One legacy world per dimension is kept for this and pointed at each region in turn;
 * generation is serialized, since 1.7.10 mods generated on one thread and many keep state.
 */
public final class WorldGen {
    private static final Object LOCK = new Object();
    private static final Map<Integer, BridgeWorldServer> WORLDS = new HashMap<Integer, BridgeWorldServer>();

    private WorldGen() {
    }

    /** Whether any legacy mod registered a world generator. */
    @SuppressWarnings("unchecked")
    public static boolean hasGenerators() throws ReflectiveOperationException {
        Field field = GameRegistry.class.getDeclaredField("worldGenerators");
        field.setAccessible(true);
        Set<IWorldGenerator> generators = (Set<IWorldGenerator>) field.get(null);
        return generators != null && !generators.isEmpty();
    }

    /** The generation-only legacy world of a dimension (tile entities made during generation belong to it). */
    public static Object genWorld(int dimension) {
        synchronized (LOCK) {
            BridgeWorldServer world = WORLDS.get(dimension);
            if (world == null) {
                // A WorldServer registers itself with DimensionManager; mods looking up the dimension must
                // keep finding the real one, not this generation-only world.
                WorldServer previous = DimensionManager.getWorld(dimension);
                world = new BridgeWorldServer(null, dimension);
                DimensionManager.setWorld(dimension, previous);
                WORLDS.put(dimension, world);
            }
            return world;
        }
    }

    /** Runs every legacy world generator for one chunk, against the region host. */
    public static void generate(HostWorld region, int dimension, int chunkX, int chunkZ) {
        synchronized (LOCK) {
            BridgeWorldServer world = (BridgeWorldServer) genWorld(dimension);
            world.setHost(region);
            LegacyWorlds.SERVER_WORK.set(Boolean.TRUE);
            try {
                GameRegistry.generateWorld(chunkX, chunkZ, world, world.func_72863_F(), world.func_72863_F()); // getChunkProvider
            } finally {
                LegacyWorlds.SERVER_WORK.remove();
                world.setHost(null);
            }
        }
    }

    /**
     * getCanSpawnHere for the mobs 1.20.1 places while generating a chunk: checked against the
     * region being generated (the full world's view would wait for that very chunk).
     */
    public static boolean canSpawnInRegion(HostWorld region, int dimension, String name, double x, double y, double z) {
        synchronized (LOCK) {
            BridgeWorldServer world = (BridgeWorldServer) genWorld(dimension);
            world.setHost(region);
            LegacyWorlds.SERVER_WORK.set(Boolean.TRUE);
            try {
                return Entities.entityCanSpawnHere(name, world, x, y, z);
            } finally {
                LegacyWorlds.SERVER_WORK.remove();
                world.setHost(null);
            }
        }
    }
}
