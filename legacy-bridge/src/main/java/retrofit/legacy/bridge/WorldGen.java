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
