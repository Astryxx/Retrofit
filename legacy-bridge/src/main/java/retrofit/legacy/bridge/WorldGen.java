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
    static final Object LOCK = new Object();
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

    /** The generation-only worlds, readable without LOCK (generation holds it while placing tile entities). */
    private static final Set<Object> GEN_WORLDS = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Object, Boolean>());

    /** Whether a world is one of the generation-only worlds below. */
    static boolean isGenWorld(Object world) {
        return GEN_WORLDS.contains(world);
    }

    /** The generation-only legacy world of a dimension (tile entities made during generation belong to it). */
    public static Object genWorld(final int dimension) {
        synchronized (LOCK) {
            BridgeWorldServer world = WORLDS.get(dimension);
            if (world == null) {
                // A WorldServer registers itself with DimensionManager; mods looking up the dimension must
                // keep finding the real one, not this generation-only world.
                WorldServer previous = DimensionManager.getWorld(dimension);
                try {
                    world = Biomes.actAs(dimension, new java.util.concurrent.Callable<BridgeWorldServer>() {
                        @Override
                        public BridgeWorldServer call() {
                            return new BridgeWorldServer(null, dimension);
                        }
                    });
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                DimensionManager.setWorld(dimension, previous);
                WORLDS.put(dimension, world);
                GEN_WORLDS.add(world);
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
                generateWorld(world, chunkX, chunkZ);
            } finally {
                LegacyWorlds.SERVER_WORK.remove();
                world.setHost(null);
            }
        }
    }

    private static java.util.List<IWorldGenerator> generators;
    private static final Set<String> FAILED_GENERATORS = new java.util.HashSet<String>();

    /**
     * GameRegistry.generateWorld, with the same seeds, one generator at a time: one that throws is
     * reported once and the others still generate the chunk (in FML's loop the generators after it
     * did nothing there).
     */
    private static void generateWorld(BridgeWorldServer world, int chunkX, int chunkZ) {
        if (generators == null) {
            try {
                java.lang.reflect.Method compute = GameRegistry.class.getDeclaredMethod("computeSortedGeneratorList");
                compute.setAccessible(true);
                compute.invoke(null);
                Field sorted = GameRegistry.class.getDeclaredField("sortedGeneratorList");
                sorted.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.List<IWorldGenerator> list = (java.util.List<IWorldGenerator>) sorted.get(null);
                generators = list;
            } catch (ReflectiveOperationException e) {
                System.out.println("[Retrofit] FML's world generators can't be run one by one; they run as FML runs them: " + e);
                GameRegistry.generateWorld(chunkX, chunkZ, world, world.func_72863_F(), world.func_72863_F()); // getChunkProvider
                return;
            }
        }
        long worldSeed = world.func_72905_C(); // getSeed
        java.util.Random random = new java.util.Random(worldSeed);
        long xSeed = random.nextLong() >> 2 + 1L;
        long zSeed = random.nextLong() >> 2 + 1L;
        long chunkSeed = (xSeed * chunkX + zSeed * chunkZ) ^ worldSeed;
        for (IWorldGenerator generator : generators) {
            random.setSeed(chunkSeed);
            try {
                generator.generate(random, chunkX, chunkZ, world, world.func_72863_F(), world.func_72863_F());
            } catch (RuntimeException | LinkageError e) {
                if (FAILED_GENERATORS.add(generator.getClass().getName())) {
                    java.io.StringWriter trace = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(trace));
                    System.out.println("[Retrofit] The world generator " + generator.getClass().getName() + " failed in chunk " + chunkX + ","
                            + chunkZ + "; the others still generate (logged once):\n" + trace);
                }
            }
        }
    }

    private static final java.util.Set<String> NOT_ORE_WORDS = new java.util.HashSet<String>(java.util.Arrays.asList(
            "core", "more", "store", "bore", "score", "shore", "chore", "adore", "snore", "spore", "explore", "ignore",
            "restore", "before", "therefore", "sore", "pore", "fore", "lore", "tore", "wore", "swore", "gore", "yore"));

    /**
     * Whether a block is an ore, for the deep pass of world generation: BlockOre and its kin, an
     * Ore Dictionary "ore..." entry, or "ore" as a word of its registry name ("oreCopper",
     * "gt.blockores", "copper_ore").
     */
    public static boolean isOreBlock(Object legacyBlock, int meta) {
        net.minecraft.block.Block block = (net.minecraft.block.Block) legacyBlock;
        if (block instanceof net.minecraft.block.BlockOre || block instanceof net.minecraft.block.BlockRedstoneOre) {
            return true;
        }
        net.minecraft.item.Item item = net.minecraft.item.Item.func_150898_a(block); // getItemFromBlock
        if (item != null) {
            try {
                for (int id : net.minecraftforge.oredict.OreDictionary.getOreIDs(new net.minecraft.item.ItemStack(item, 1, meta))) {
                    if (net.minecraftforge.oredict.OreDictionary.getOreName(id).startsWith("ore")) {
                        return true;
                    }
                }
            } catch (RuntimeException e) {
                // no Ore Dictionary entry
            }
        }
        String name = net.minecraft.block.Block.field_149771_c.func_148750_c(block); // blockRegistry.getNameForObject
        if (name == null) {
            return false;
        }
        String path = name.substring(name.indexOf(':') + 1).replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(java.util.Locale.ROOT);
        for (String word : path.split("[^a-z]+")) {
            if (word.equals("ore") || word.equals("ores") || (word.endsWith("ore") || word.endsWith("ores")) && word.length() > 4
                    && !NOT_ORE_WORDS.contains(word) && !NOT_ORE_WORDS.contains(word.endsWith("s") ? word.substring(0, word.length() - 1) : word)) {
                return true;
            }
        }
        return false;
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
