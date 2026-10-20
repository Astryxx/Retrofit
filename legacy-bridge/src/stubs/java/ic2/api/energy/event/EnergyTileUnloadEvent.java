// Compile-time stand-in for an API that 1.7.10 mods ship (not packaged with Retrofit; see build.gradle).
package ic2.api.energy.event; public class EnergyTileUnloadEvent extends EnergyTileEvent { public EnergyTileUnloadEvent(ic2.api.energy.tile.IEnergyTile tile) { super(tile); } }
