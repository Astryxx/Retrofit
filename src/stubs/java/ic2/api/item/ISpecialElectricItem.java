// Compile-time stand-in for an API that 1.7.10 mods ship (not packaged with Retrofit; see build.gradle).
package ic2.api.item; public interface ISpecialElectricItem extends IElectricItem { IElectricItemManager getManager(net.minecraft.item.ItemStack stack); }
