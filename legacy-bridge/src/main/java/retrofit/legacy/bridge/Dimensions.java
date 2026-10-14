package retrofit.legacy.bridge;

import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.world.Teleporter;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.common.DimensionManager;
import retrofit.api.HostWorld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mods' own dimensions (Thaumcraft's Outer Lands, Witchery's spirit worlds, ...). Each becomes a
 * 1.20.1 dimension; its terrain comes from the mod's own chunk generator (the WorldProvider's
 * createChunkGenerator), run on a generation-only legacy world of that dimension:
 * {@link #provideChunk} for the blocks of a new chunk, {@link #populate} for decoration and the
 * mods' world generators. Players and the mod's Teleporter go between dimensions through the host
 * (BridgePlayerMP.travelToDimension, BridgePlayerList.transferPlayerToDimension).
 */
public final class Dimensions {
    /** The generator of each legacy dimension's generation world. */
    private static final Map<Integer, IChunkProvider> GENERATORS = new HashMap<Integer, IChunkProvider>();

    private Dimensions() {
    }

    /**
     * The mods' dimensions: {id, name, flags, average ground level} each; flags: 1 no sky (a ceiling,
     * like the Nether), 2 hell world, 4 players can respawn there, 8 surface world.
     */
    public static Object[][] legacyDimensions() {
        List<Object[]> result = new ArrayList<Object[]>();
        for (Integer id : DimensionManager.getStaticDimensionIDs()) {
            if (id == 0 || id == -1 || id == 1) {
                continue;
            }
            try {
                WorldProvider provider = ((net.minecraft.world.World) WorldGen.genWorld(id)).field_73011_w; // provider
                int flags = (provider.field_76576_e ? 1 : 0) | (provider.field_76575_d ? 2 : 0) // hasNoSky, isHellWorld
                        | (provider.func_76567_e() ? 4 : 0) | (provider.func_76569_d() ? 8 : 0); // canRespawnHere, isSurfaceWorld
                result.add(new Object[]{id, provider.func_80007_l(), flags, provider.func_76557_i()}); // getDimensionName, getAverageGroundLevel
            } catch (Throwable t) {
                System.out.println("[Retrofit] Legacy dimension " + id + " can't be used: " + t);
            }
        }
        return result.toArray(new Object[0][]);
    }

    private static IChunkProvider generator(net.minecraft.world.World world, int dimension) {
        IChunkProvider generator = GENERATORS.get(dimension);
        if (generator == null) {
            generator = world.field_73011_w.func_76555_c(); // provider.createChunkGenerator
            GENERATORS.put(dimension, generator);
        }
        return generator;
    }

    /**
     * The blocks of a new chunk as the mod's generator makes them: (legacy block id << 4 | meta) for
     * each block, index (y * 16 + z) * 16 + x, y from 0 to 255; 0 is air. {@code chunkView} answers
     * the generator's world reads (the chunk being made is still empty).
     */
    public static int[] provideChunk(HostWorld chunkView, int dimension, int chunkX, int chunkZ) {
        synchronized (WorldGen.LOCK) {
            BridgeWorldServer world = (BridgeWorldServer) WorldGen.genWorld(dimension);
            world.setHost(chunkView);
            LegacyWorlds.SERVER_WORK.set(Boolean.TRUE);
            try {
                Chunk chunk = generator(world, dimension).func_73154_d(chunkX, chunkZ); // provideChunk
                int[] blocks = new int[16 * 16 * 256];
                for (ExtendedBlockStorage storage : chunk.func_76587_i()) { // getBlockStorageArray
                    if (storage == null) {
                        continue;
                    }
                    int baseY = storage.func_76662_d(); // getYLocation
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                Block block = storage.func_150819_a(x, y, z); // getBlockByExtId
                                int id = Block.func_149682_b(block); // getIdFromBlock
                                if (id != 0) {
                                    blocks[((baseY + y) * 16 + z) * 16 + x] = id << 4 | storage.func_76665_b(x, y, z); // getExtBlockMetadata
                                }
                            }
                        }
                    }
                }
                return blocks;
            } finally {
                LegacyWorlds.SERVER_WORK.remove();
                world.setHost(null);
            }
        }
    }

    /** Decorates a chunk as 1.7.10's ChunkProviderServer.populate did: the dimension's generator, then the mods' world generators. */
    public static void populate(HostWorld region, int dimension, int chunkX, int chunkZ) {
        synchronized (WorldGen.LOCK) {
            BridgeWorldServer world = (BridgeWorldServer) WorldGen.genWorld(dimension);
            world.setHost(region);
            LegacyWorlds.SERVER_WORK.set(Boolean.TRUE);
            try {
                IChunkProvider generator = generator(world, dimension);
                generator.func_73153_a(world.func_72863_F(), chunkX, chunkZ); // populate(chunkProvider, x, z)
                GameRegistry.generateWorld(chunkX, chunkZ, world, generator, world.func_72863_F());
            } finally {
                LegacyWorlds.SERVER_WORK.remove();
                world.setHost(null);
            }
        }
    }

    /**
     * Creatures the dimension's generator lists for a spot (IChunkProvider.getPossibleCreatures),
     * category 0 monster, 1 creature, 2 ambient, 3 water: {legacy name, weight, min, max} each.
     */
    @SuppressWarnings("rawtypes")
    public static Object[][] dimensionSpawns(int dimension, int category, int x, int y, int z) {
        net.minecraft.entity.EnumCreatureType type = net.minecraft.entity.EnumCreatureType.values()[category];
        List spawns;
        synchronized (WorldGen.LOCK) {
            BridgeWorldServer world = (BridgeWorldServer) WorldGen.genWorld(dimension);
            spawns = generator(world, dimension).func_73155_a(type, x, y, z); // getPossibleCreatures
        }
        List<Object[]> result = new ArrayList<Object[]>();
        if (spawns != null) {
            for (Object element : spawns) {
                net.minecraft.world.biome.BiomeGenBase.SpawnListEntry entry = (net.minecraft.world.biome.BiomeGenBase.SpawnListEntry) element;
                Object name = net.minecraft.entity.EntityList.field_75626_c.get(entry.field_76300_b); // classToStringMapping, entityClass
                if (name != null) {
                    result.add(new Object[]{name, entry.field_76292_a, entry.field_76301_c, entry.field_76299_d}); // itemWeight, minGroupCount, maxGroupCount
                }
            }
        }
        return result.toArray(new Object[0][]);
    }

    /** Tests: a player's mirror travels as mods send players (EntityPlayerMP.travelToDimension). */
    public static void testTravel(Object legacyPlayer, int dimension) {
        ((net.minecraft.entity.player.EntityPlayerMP) legacyPlayer).func_71027_c(dimension); // travelToDimension
    }

    /** The legacy world of a dimension the host has loaded, for mods that look it up (DimensionManager.getWorld). */
    public static boolean isLoaded(int dimension) {
        return DimensionManager.getWorld(dimension) != null;
    }

    /**
     * After the host moved a player (or entity) to another dimension: the mod's Teleporter places it
     * there (it may build a portal or a platform); returns {x, y, z, yaw} of where it put it.
     */
    public static double[] placeInPortal(Object teleporter, Object legacyEntity, double x, double y, double z, float yaw) {
        Entity entity = (Entity) legacyEntity;
        entity.func_70012_b(x, y, z, yaw, entity.field_70125_A); // setLocationAndAngles
        ((Teleporter) teleporter).func_77185_a(entity, x, y, z, yaw); // placeInPortal
        return new double[]{entity.field_70165_t, entity.field_70163_u - entity.field_70129_M, entity.field_70161_v, entity.field_70177_z}; // posX, posY - yOffset, posZ, rotationYaw
    }
}
