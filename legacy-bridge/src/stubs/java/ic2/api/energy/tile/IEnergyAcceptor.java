// Compile-time stand-in for an API that 1.7.10 mods ship (not packaged with Retrofit; see build.gradle).
package ic2.api.energy.tile; public interface IEnergyAcceptor extends IEnergyTile { boolean acceptsEnergyFrom(net.minecraft.tileentity.TileEntity emitter, net.minecraftforge.common.util.ForgeDirection direction); }
