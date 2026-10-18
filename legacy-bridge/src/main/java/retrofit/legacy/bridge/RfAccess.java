package retrofit.legacy.bridge;

import cofh.api.energy.IEnergyConnection;
import cofh.api.energy.IEnergyProvider;
import cofh.api.energy.IEnergyReceiver;
import net.minecraftforge.common.util.ForgeDirection;

/** CoFH RF on legacy tile entities. Loaded only when a mod ships the RF API (see Energy). */
final class RfAccess {
    private RfAccess() {
    }

    /**
     * 1.20.1 code may ask without a side; legacy machines index arrays by side (UNKNOWN crashes
     * BuildCraft's), so a side-less request goes through the first side the machine connects on.
     */
    private static ForgeDirection side(Object tile, ForgeDirection from) {
        if (from != ForgeDirection.UNKNOWN) {
            return from;
        }
        for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
            try {
                if (!(tile instanceof IEnergyConnection) || ((IEnergyConnection) tile).canConnectEnergy(direction)) {
                    return direction;
                }
            } catch (RuntimeException e) {
                // this side can't be asked
            }
        }
        return null;
    }

    private static boolean connects(Object tile, ForgeDirection from) {
        return from != null && (!(tile instanceof IEnergyConnection) || ((IEnergyConnection) tile).canConnectEnergy(from));
    }

    static int kind(Object tile, ForgeDirection from) {
        from = side(tile, from);
        if (!(tile instanceof IEnergyConnection) || !connects(tile, from)) {
            return 0;
        }
        return (tile instanceof IEnergyReceiver ? 1 : 0) | (tile instanceof IEnergyProvider ? 2 : 0);
    }

    static int receive(Object tile, ForgeDirection from, int amount, boolean simulate) {
        from = side(tile, from);
        return tile instanceof IEnergyReceiver && connects(tile, from) ? Math.max(0, ((IEnergyReceiver) tile).receiveEnergy(from, amount, simulate)) : 0;
    }

    static int extract(Object tile, ForgeDirection from, int amount, boolean simulate) {
        from = side(tile, from);
        return tile instanceof IEnergyProvider && connects(tile, from) ? Math.max(0, ((IEnergyProvider) tile).extractEnergy(from, amount, simulate)) : 0;
    }

    // ---- Items (IEnergyContainerItem: RF tools, batteries) ----

    static int itemKind(Object stack) {
        return stack instanceof net.minecraft.item.ItemStack && ((net.minecraft.item.ItemStack) stack).func_77973_b() instanceof cofh.api.energy.IEnergyContainerItem ? 3 : 0;
    }

    static int itemReceive(Object stack, int amount, boolean simulate) {
        net.minecraft.item.ItemStack item = (net.minecraft.item.ItemStack) stack;
        return Math.max(0, ((cofh.api.energy.IEnergyContainerItem) item.func_77973_b()).receiveEnergy(item, amount, simulate));
    }

    static int itemExtract(Object stack, int amount, boolean simulate) {
        net.minecraft.item.ItemStack item = (net.minecraft.item.ItemStack) stack;
        return Math.max(0, ((cofh.api.energy.IEnergyContainerItem) item.func_77973_b()).extractEnergy(item, amount, simulate));
    }

    static int[] itemStorage(Object stack) {
        net.minecraft.item.ItemStack item = (net.minecraft.item.ItemStack) stack;
        cofh.api.energy.IEnergyContainerItem container = (cofh.api.energy.IEnergyContainerItem) item.func_77973_b();
        return new int[]{container.getEnergyStored(item), container.getMaxEnergyStored(item)};
    }

    static int[] storage(Object tile, ForgeDirection from) {
        from = side(tile, from);
        if (from == null) {
            return null;
        }
        if (tile instanceof IEnergyReceiver) {
            IEnergyReceiver receiver = (IEnergyReceiver) tile;
            return new int[]{receiver.getEnergyStored(from), receiver.getMaxEnergyStored(from)};
        }
        if (tile instanceof IEnergyProvider) {
            IEnergyProvider provider = (IEnergyProvider) tile;
            return new int[]{provider.getEnergyStored(from), provider.getMaxEnergyStored(from)};
        }
        return null;
    }
}
