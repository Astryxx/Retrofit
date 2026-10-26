package retrofit.testmods.biomeclasha;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.DimensionManager;

/**
 * Takes biome ids 100 and 101 in preInit, the way Advent of Ascension's dimension biomes do:
 * they just construct their biomes at their configured ids, whoever else wants them. It also
 * registers dimension 7, the way mods with clashing default dimension ids do.
 */
@Mod(modid = "biomeclasha", name = "Biome Clash A", version = "1.0")
public class BiomeClashA {
    public static BiomeGenBase first;
    public static BiomeGenBase second;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        first = new BiomeGenBase(100) {};
        second = new BiomeGenBase(101) {};
        DimensionManager.registerDimension(7, 0);
        System.out.println("[BiomeClashA] registered biomes at ids 100 and 101, and dimension 7");
    }
}
