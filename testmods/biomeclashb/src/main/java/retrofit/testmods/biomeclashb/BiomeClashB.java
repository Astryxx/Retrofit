package retrofit.testmods.biomeclashb;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.DimensionManager;

/**
 * Checks biome ids 100 and 101 in init the way Erebus's ModBiomes.init does (BiomeGenBase.
 * getBiomeGenArray()[id] != null means refusal with "Please choose a different one"), then
 * registers its own biomes there, and checks again in postInit that they are still its own
 * (Erebus's ModBiomes.postInit).
 */
@Mod(modid = "biomeclashb", name = "Biome Clash B", version = "1.0")
public class BiomeClashB {
    public static BiomeBase mine;
    public static BiomeBase mineSecond;

    public static class BiomeBase extends BiomeGenBase {
        public BiomeBase(int id) {
            super(id);
        }
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        for (int id : new int[] {100, 101}) {
            BiomeGenBase occupant = BiomeGenBase.func_150565_n()[id]; // getBiomeGenArray
            if (occupant != null) {
                throw new IllegalArgumentException("Biome Clash B can not use biome ID " + id
                        + " because it's being used by " + occupant + ". Please choose a different one.");
            }
        }
        mine = new BiomeBase(100);
        mineSecond = new BiomeBase(101);
        DimensionManager.registerDimension(7, 0); // Biome Clash A took dimension 7 already
        System.out.println("[BiomeClashB] registered its biomes at ids 100 and 101, and tried dimension 7");
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        for (int id : new int[] {100, 101}) {
            BiomeGenBase at = BiomeGenBase.func_150565_n()[id]; // getBiomeGenArray
            if (!(at instanceof BiomeBase)) {
                throw new IllegalArgumentException("Biome Clash B's biome with id " + id + " was replaced by "
                        + at + ". This is likely an ID conflict or a bug in another mod!");
            }
        }
        System.out.println("[BiomeClashB] postInit: both biomes are still its own");
    }
}
