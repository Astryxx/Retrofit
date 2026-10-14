package retrofit.legacy.bridge;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.util.ForgeDirection;

import java.lang.reflect.Field;

/**
 * ChunkCache (the block snapshot 1.7.10 pathfinding and some renderers read) answers from its
 * world instead: bridged worlds keep their blocks in the host level, not in legacy chunks.
 * Its methods are redirected here (see HeadlessTransformer).
 */
public final class ChunkCacheGlue {
    private static Field world;

    private ChunkCacheGlue() {
    }

    private static World world(ChunkCache cache) {
        try {
            if (world == null) {
                Field field = ChunkCache.class.getDeclaredField("field_72815_e"); // worldObj
                field.setAccessible(true);
                world = field;
            }
            return (World) world.get(cache);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean extendedLevels(ChunkCache cache) {
        return false;
    }

    public static Block getBlock(ChunkCache cache, int x, int y, int z) {
        return world(cache).func_147439_a(x, y, z);
    }

    public static TileEntity getTileEntity(ChunkCache cache, int x, int y, int z) {
        return world(cache).func_147438_o(x, y, z);
    }

    public static int getLightBrightnessForSkyBlocks(ChunkCache cache, int x, int y, int z, int minBlockLight) {
        return world(cache).func_72802_i(x, y, z, minBlockLight);
    }

    public static int getBlockMetadata(ChunkCache cache, int x, int y, int z) {
        return world(cache).func_72805_g(x, y, z);
    }

    public static int isBlockProvidingPowerTo(ChunkCache cache, int x, int y, int z, int side) {
        return world(cache).func_72879_k(x, y, z, side);
    }

    public static BiomeGenBase getBiomeGenForCoords(ChunkCache cache, int x, int z) {
        return world(cache).func_72807_a(x, z);
    }

    public static boolean isAirBlock(ChunkCache cache, int x, int y, int z) {
        return world(cache).func_147437_c(x, y, z);
    }

    public static int getSkyBlockTypeBrightness(ChunkCache cache, EnumSkyBlock type, int x, int y, int z) {
        return world(cache).func_72972_b(type, x, y, z); // getSavedLightValue
    }

    public static int getSpecialBlockBrightness(ChunkCache cache, EnumSkyBlock type, int x, int y, int z) {
        return world(cache).func_72972_b(type, x, y, z);
    }

    public static int getHeight(ChunkCache cache) {
        return 256;
    }

    public static boolean isSideSolid(ChunkCache cache, int x, int y, int z, ForgeDirection side, boolean fallback) {
        return world(cache).isSideSolid(x, y, z, side, fallback);
    }
}
