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
    /** The host's: the bridged world of a dimension's 1.20.1 level (made if needed), or null if there's no such level. */
    private static volatile java.util.function.IntFunction<Object> hostWorlds;

    private Dimensions() {
    }

    /**
     * The mods' dimensions: {id, name, flags, average ground level, fog colour, sky colour} each
     * (colours 0xRRGGBB, or -1 if the provider can't say); flags: 1 no sky (a ceiling, like the
     * Nether), 2 hell world, 4 players can respawn there, 8 surface world.
     */
    public static Object[][] legacyDimensions() {
        List<Object[]> result = new ArrayList<Object[]>();
        List<Integer> ids = new ArrayList<Integer>(java.util.Arrays.asList(DimensionManager.getStaticDimensionIDs()));
        List<Integer> later = laterDimensions(ids);
        ids.addAll(later);
        for (Integer id : ids) {
            if (id == 0 || id == -1 || id == 1) {
                continue;
            }
            boolean registered = !later.contains(id);
            if (!registered) {
                DimensionManager.registerDimension(id, id); // just to look at its provider; the mod registers it itself
            }
            try {
                WorldProvider provider = ((net.minecraft.world.World) WorldGen.genWorld(id)).field_73011_w; // provider
                int flags = (provider.field_76576_e ? 1 : 0) | (provider.field_76575_d ? 2 : 0) // hasNoSky, isHellWorld
                        | (provider.func_76567_e() ? 4 : 0) | (provider.func_76569_d() ? 8 : 0); // canRespawnHere, isSurfaceWorld
                result.add(new Object[]{id, provider.func_80007_l(), flags, provider.func_76557_i(), // getDimensionName, getAverageGroundLevel
                        fogColor(provider), skyColor(provider)});
            } catch (Throwable t) {
                System.out.println("[Retrofit] Legacy dimension " + id + " can't be used: " + t);
            } finally {
                if (!registered) {
                    DimensionManager.unregisterDimension(id);
                }
            }
        }
        return result.toArray(new Object[0][]);
    }

    /**
     * Dimensions mods register only once a server starts (Galacticraft's Moon and planets): their
     * provider types exist from loading, under the dimension's own id, with no dimension yet. Each
     * gets its 1.20.1 level now, as 1.20.1 knows its dimensions before a server starts.
     */
    @SuppressWarnings("unchecked")
    private static List<Integer> laterDimensions(List<Integer> registered) {
        List<Integer> later = new ArrayList<Integer>();
        try {
            java.lang.reflect.Field field = DimensionManager.class.getDeclaredField("providers");
            field.setAccessible(true);
            for (Integer provider : ((Map<Integer, Class<?>>) field.get(null)).keySet()) {
                if (provider != 0 && provider != -1 && provider != 1 && !registered.contains(provider)
                        && !DimensionManager.isDimensionRegistered(provider)) {
                    later.add(provider);
                }
            }
        } catch (ReflectiveOperationException e) {
            System.out.println("[Retrofit] Could not list legacy dimension types: " + e);
        }
        return later;
    }

    public static void setHostWorlds(java.util.function.IntFunction<Object> worlds) {
        hostWorlds = worlds;
    }

    /** Whether a dimension's legacy world can be made (its mod registered it; Galacticraft's planets: once the server started). */
    public static boolean dimensionReady(int dimension) {
        return dimension == 0 || dimension == -1 || dimension == 1 || DimensionManager.isDimensionRegistered(dimension);
    }

    /**
     * DimensionManager.initDimension (redirected), for mods that ask for a dimension's world
     * (MinecraftServer.worldServerForDimension) before anyone went there: the bridged world of
     * its 1.20.1 level, which the host makes now for a dimension registered while playing
     * (Galacticraft's space stations, pocket dimensions).
     */
    public static void initDimension(int dimension) {
        if (DimensionManager.getWorld(dimension) != null) {
            return;
        }
        java.util.function.IntFunction<Object> worlds = hostWorlds;
        if (worlds == null || worlds.apply(dimension) == null) {
            System.out.println("[Retrofit] A 1.7.10 mod asked for dimension " + dimension + ", which has no 1.20.1 level"
                    + (DimensionManager.isDimensionRegistered(dimension) ? " (it could not be made; see the log)" : " (no mod registered it)"));
        }
    }

    /** The ids of every registered dimension (the host makes the levels of those it has none of). */
    public static int[] registeredDimensions() {
        Integer[] ids = DimensionManager.getStaticDimensionIDs();
        int[] result = new int[ids.length];
        for (int i = 0; i < ids.length; i++) {
            result[i] = ids[i];
        }
        return result;
    }

    /** A registered dimension's {provider type, average ground level}, for the host to make its level; null if not registered. */
    public static int[] dimensionInfo(int dimension) {
        if (!DimensionManager.isDimensionRegistered(dimension)) {
            return null;
        }
        int ground = 64;
        try {
            ground = ((net.minecraft.world.World) WorldGen.genWorld(dimension)).field_73011_w.func_76557_i(); // provider.getAverageGroundLevel
        } catch (RuntimeException e) {
            // the overworld's
        }
        return new int[]{DimensionManager.getProviderType(dimension), ground};
    }

    /**
     * DimensionManager.registerDimension (redirected). Forge's own, except that a second
     * registration of a dimension id - 1.7.10 mods often shipped clashing default ids, and Forge's
     * version threw and left the whole mod out - warns and skips instead: the first wins.
     */
    @SuppressWarnings("unchecked")
    public static void registerDimension(int id, int providerType) {
        try {
            java.lang.reflect.Field providersField = DimensionManager.class.getDeclaredField("providers");
            providersField.setAccessible(true);
            java.util.Hashtable<Integer, Class<? extends WorldProvider>> providers =
                    (java.util.Hashtable<Integer, Class<? extends WorldProvider>>) providersField.get(null);
            if (!providers.containsKey(providerType)) {
                throw new IllegalArgumentException("Failed to register dimension for id " + id + ", provider type " + providerType + " does not exist");
            }
            java.lang.reflect.Field dimensionsField = DimensionManager.class.getDeclaredField("dimensions");
            dimensionsField.setAccessible(true);
            java.util.Hashtable<Integer, Integer> dimensions = (java.util.Hashtable<Integer, Integer>) dimensionsField.get(null);
            if (dimensions.containsKey(id)) {
                System.out.println("[Retrofit] Dimension id " + id + " is registered already; a mod's second registration of it"
                        + " (provider type " + providerType + ") is skipped, and that mod's dimension will not exist");
                return;
            }
            dimensions.put(id, providerType);
            if (id >= 0) {
                java.lang.reflect.Field mapField = DimensionManager.class.getDeclaredField("dimensionMap");
                mapField.setAccessible(true);
                ((java.util.BitSet) mapField.get(null)).set(id);
            }
        } catch (ReflectiveOperationException e) {
            System.out.println("[Retrofit] Could not register dimension " + id + " (provider type " + providerType + "): " + e);
        }
    }

    private static int rgb(net.minecraft.util.Vec3 colour) {
        int r = (int) Math.max(0, Math.min(255, colour.field_72450_a * 255)); // xCoord
        int g = (int) Math.max(0, Math.min(255, colour.field_72448_b * 255));
        int b = (int) Math.max(0, Math.min(255, colour.field_72449_c * 255));
        return r << 16 | g << 8 | b;
    }

    /** The provider's fog colour at noon (WorldProvider.getFogColor), which mods override for their dimensions' look. */
    private static int fogColor(WorldProvider provider) {
        try {
            java.lang.reflect.Method method = WorldProvider.class.getMethod("func_76562_b", float.class, float.class); // client-only in 1.7.10
            return rgb((net.minecraft.util.Vec3) method.invoke(provider, 0f, 1f));
        } catch (Throwable t) {
            return -1;
        }
    }

    /** The provider's sky colour at noon (getSkyColor), if it doesn't need a viewer for it. */
    private static int skyColor(WorldProvider provider) {
        java.lang.invoke.MethodType signature = java.lang.invoke.MethodType.methodType(net.minecraft.util.Vec3.class,
                net.minecraft.entity.Entity.class, float.class);
        if (!Overrides.overrides(provider.getClass(), WorldProvider.class, "getSkyColor", signature)) {
            return -1; // the default: the biome's sky, which the host already has
        }
        try {
            // looked up on WorldProvider and called on the provider: the mod's override runs
            java.lang.reflect.Method method = WorldProvider.class.getMethod("getSkyColor", net.minecraft.entity.Entity.class, float.class);
            return rgb((net.minecraft.util.Vec3) method.invoke(provider, null, 1f));
        } catch (Throwable t) {
            return -1;
        }
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
    /** Tests: a dimension of the given provider type registered now, as mods register theirs while playing; its id. */
    public static int registerTestDimension(int providerType) {
        int id = DimensionManager.getNextFreeDimId();
        DimensionManager.registerDimension(id, providerType);
        return id;
    }

    /**
     * Tests: a legacy mod's static method called by name, as its own code calls it (Galacticraft's space
     * station API); the argument WORLD stands for the player's world. Null if the mod isn't there.
     */
    public static Object testCallMod(String className, String method, Object legacyPlayer, Object[] args) throws Exception {
        Class<?> type;
        try {
            type = Class.forName(className, true, Dimensions.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
        Object[] actual = args.clone();
        for (int i = 0; i < actual.length; i++) {
            if ("WORLD".equals(actual[i])) {
                actual[i] = ((net.minecraft.entity.Entity) legacyPlayer).field_70170_p; // worldObj
            } else if ("PLAYER".equals(actual[i])) {
                actual[i] = legacyPlayer;
            }
        }
        for (java.lang.reflect.Method candidate : type.getMethods()) {
            if (candidate.getName().equals(method) && candidate.getParameterTypes().length == actual.length) {
                return candidate.invoke(null, actual);
            }
        }
        throw new NoSuchMethodException(className + "." + method);
    }

    public static void testTravel(Object legacyPlayer, int dimension) {
        ((net.minecraft.entity.player.EntityPlayerMP) legacyPlayer).func_71027_c(dimension); // travelToDimension
    }

    /**
     * Tests: a legacy mod's static field, by name, or a field of it ("INSTANCE.erebusDimensionID"): a dimension
     * id from its config. Null if the mod isn't there.
     */
    public static Object testModField(String className, String field) throws Exception {
        Object value;
        try {
            value = Class.forName(className, true, Dimensions.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
        for (String name : field.split("\\.")) {
            Class<?> type = value instanceof Class ? (Class<?>) value : value.getClass();
            value = type.getField(name).get(value instanceof Class ? null : value);
        }
        return value;
    }

    /**
     * Tests: a mirror sent as mods with their own Teleporter send players (transferPlayerToDimension), the
     * teleporter made as such mods make it: (WorldServer destination, int dimension, World from), as OreSpawn's,
     * or (WorldServer destination), as Erebus's.
     */
    public static void testTeleport(Object legacyPlayer, int dimension, String teleporterClass) throws Exception {
        net.minecraft.entity.player.EntityPlayerMP player = (net.minecraft.entity.player.EntityPlayerMP) legacyPlayer;
        net.minecraft.server.MinecraftServer server = LegacyWorlds.server();
        net.minecraft.world.WorldServer destination = server.func_71218_a(dimension); // worldServerForDimension
        Class<?> type = Class.forName(teleporterClass, true, Dimensions.class.getClassLoader());
        Object teleporter;
        try {
            teleporter = type.getConstructor(net.minecraft.world.WorldServer.class, int.class, net.minecraft.world.World.class)
                    .newInstance(destination, dimension, player.field_70170_p); // worldObj
        } catch (NoSuchMethodException e) {
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(net.minecraft.world.WorldServer.class);
            constructor.setAccessible(true);
            teleporter = constructor.newInstance(destination);
        }
        server.func_71203_ab().transferPlayerToDimension(player, dimension, (Teleporter) teleporter); // getConfigurationManager
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
