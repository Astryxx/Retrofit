package retrofit.legacy.bridge;

import ic2.api.energy.event.EnergyTileLoadEvent;
import ic2.api.energy.event.EnergyTileUnloadEvent;
import ic2.api.energy.tile.IEnergySink;
import ic2.api.energy.tile.IEnergySource;
import ic2.api.energy.tile.IEnergyTile;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.ForgeDirection;

/** IC2's EU on legacy tile entities, and IC2's energy net. Loaded only when IC2's API is installed (see Energy). */
final class EuAccess {
    private EuAccess() {
    }

    /** The highest voltage a sink takes without exploding: 8 * 4^tier EU per packet. */
    private static double voltage(int tier) {
        return 8 * Math.pow(4, Math.max(0, Math.min(tier, 13)));
    }

    private static boolean accepts(IEnergySink sink, ForgeDirection from) {
        try {
            return sink.acceptsEnergyFrom(null, from);
        } catch (RuntimeException e) {
            return true; // a sink that looks at the emitter: the 1.20.1 block has none
        }
    }

    private static boolean emits(IEnergySource source, ForgeDirection to) {
        try {
            return source.emitsEnergyTo(null, to);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** A side-less request (1.20.1 code may ask without one) goes through the first side the machine takes or gives on. */
    private static ForgeDirection side(Object tile, ForgeDirection side, boolean in) {
        if (side != ForgeDirection.UNKNOWN) {
            return side;
        }
        for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
            if (in ? tile instanceof IEnergySink && accepts((IEnergySink) tile, direction)
                    : tile instanceof IEnergySource && emits((IEnergySource) tile, direction)) {
                return direction;
            }
        }
        return ForgeDirection.NORTH;
    }

    static int kind(Object tile, ForgeDirection side) {
        int kind = 0;
        if (tile instanceof IEnergySink && accepts((IEnergySink) tile, side(tile, side, true))) {
            kind |= 1;
        }
        if (tile instanceof IEnergySource && emits((IEnergySource) tile, side(tile, side, false))) {
            kind |= 2;
        }
        return kind;
    }

    static int receive(Object tile, ForgeDirection from, int amount, boolean simulate) {
        from = side(tile, from, true);
        if (!(tile instanceof IEnergySink) || !accepts((IEnergySink) tile, from)) {
            return 0;
        }
        IEnergySink sink = (IEnergySink) tile;
        double voltage = voltage(sink.getSinkTier());
        double eu = Math.min(Math.min(amount / (double) Energy.FE_PER_EU, sink.getDemandedEnergy()), voltage);
        if (eu <= 0) {
            return 0;
        }
        if (simulate) {
            return (int) (eu * Energy.FE_PER_EU);
        }
        double left = sink.injectEnergy(from, eu, voltage);
        return (int) Math.max(0, (eu - left) * Energy.FE_PER_EU);
    }

    static int extract(Object tile, ForgeDirection to, int amount, boolean simulate) {
        to = side(tile, to, false);
        if (!(tile instanceof IEnergySource) || !emits((IEnergySource) tile, to)) {
            return 0;
        }
        IEnergySource source = (IEnergySource) tile;
        double eu = Math.min(source.getOfferedEnergy(), amount / (double) Energy.FE_PER_EU);
        if (eu <= 0) {
            return 0;
        }
        if (!simulate) {
            source.drawEnergy(eu);
        }
        return (int) (eu * Energy.FE_PER_EU);
    }

    /** What IC2 tells about a tile's energy: room for sinks, what's on offer for sources. */
    static int[] storage(Object tile) {
        if (tile instanceof IEnergySource) {
            int offered = (int) (((IEnergySource) tile).getOfferedEnergy() * Energy.FE_PER_EU);
            return new int[]{offered, offered};
        }
        if (tile instanceof IEnergySink) {
            return new int[]{0, (int) Math.min(Integer.MAX_VALUE, ((IEnergySink) tile).getDemandedEnergy() * Energy.FE_PER_EU)};
        }
        return null;
    }

    // ---- Items (IElectricItem: IC2 tools, armour, batteries) ----

    private static ic2.api.item.IElectricItemManager manager(net.minecraft.item.ItemStack stack) {
        return stack.func_77973_b() instanceof ic2.api.item.ISpecialElectricItem
                ? ((ic2.api.item.ISpecialElectricItem) stack.func_77973_b()).getManager(stack) : ic2.api.item.ElectricItem.manager;
    }

    static int itemKind(Object stack) {
        if (!(stack instanceof net.minecraft.item.ItemStack) || !(((net.minecraft.item.ItemStack) stack).func_77973_b() instanceof ic2.api.item.IElectricItem)) {
            return 0;
        }
        net.minecraft.item.ItemStack item = (net.minecraft.item.ItemStack) stack;
        return 1 | (((ic2.api.item.IElectricItem) item.func_77973_b()).canProvideEnergy(item) ? 2 : 0);
    }

    static int itemReceive(Object stack, int amount, boolean simulate) {
        net.minecraft.item.ItemStack item = (net.minecraft.item.ItemStack) stack;
        ic2.api.item.IElectricItemManager manager = manager(item);
        if (manager == null) {
            return 0;
        }
        int tier = ((ic2.api.item.IElectricItem) item.func_77973_b()).getTier(item);
        double charged = manager.charge(item, amount / (double) Energy.FE_PER_EU, tier, false, simulate);
        return (int) (charged * Energy.FE_PER_EU);
    }

    static int itemExtract(Object stack, int amount, boolean simulate) {
        net.minecraft.item.ItemStack item = (net.minecraft.item.ItemStack) stack;
        ic2.api.item.IElectricItemManager manager = manager(item);
        if (manager == null) {
            return 0;
        }
        int tier = ((ic2.api.item.IElectricItem) item.func_77973_b()).getTier(item);
        double drawn = manager.discharge(item, amount / (double) Energy.FE_PER_EU, tier, false, true, simulate);
        return (int) (drawn * Energy.FE_PER_EU);
    }

    static int[] itemStorage(Object stack) {
        net.minecraft.item.ItemStack item = (net.minecraft.item.ItemStack) stack;
        ic2.api.item.IElectricItemManager manager = manager(item);
        double max = ((ic2.api.item.IElectricItem) item.func_77973_b()).getMaxCharge(item);
        return new int[]{manager == null ? 0 : (int) Math.min(Integer.MAX_VALUE, manager.getCharge(item) * Energy.FE_PER_EU),
                (int) Math.min(Integer.MAX_VALUE, max * Energy.FE_PER_EU)};
    }

    static void join(TileEntity proxy) {
        if (proxy instanceof IEnergyTile && !proxy.func_145837_r()) { // isInvalid
            MinecraftForge.EVENT_BUS.post(new EnergyTileLoadEvent((IEnergyTile) proxy));
        }
    }

    static void leave(TileEntity proxy) {
        if (proxy instanceof IEnergyTile) {
            MinecraftForge.EVENT_BUS.post(new EnergyTileUnloadEvent((IEnergyTile) proxy));
        }
    }
}
