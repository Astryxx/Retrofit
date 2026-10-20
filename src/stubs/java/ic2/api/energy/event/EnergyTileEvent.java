// Compile-time stand-in for an API that 1.7.10 mods ship (not packaged with Retrofit; see build.gradle).
package ic2.api.energy.event; public class EnergyTileEvent extends net.minecraftforge.event.world.WorldEvent { public final ic2.api.energy.tile.IEnergyTile energyTile; public EnergyTileEvent(ic2.api.energy.tile.IEnergyTile tile) { super(null); energyTile = tile; } }
