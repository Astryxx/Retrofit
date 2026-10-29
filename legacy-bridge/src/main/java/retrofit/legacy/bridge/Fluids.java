package retrofit.legacy.bridge;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fluids between legacy mods and 1.20.1 mods. Each legacy fluid is a 1.20.1 fluid as well (water
 * and lava are themselves; others are registered by the host, see LegacyFluids), and amounts are
 * millibuckets on both sides. Legacy tanks (IFluidHandler) offer Forge's 1.20.1 fluid handler,
 * and 1.20.1 tanks appear to legacy pipes and pumps as IFluidHandlers (HostTiles).
 */
public final class Fluids {
    private Fluids() {
    }

    /** {name, density, temperature, luminosity, viscosity, gaseous (1/0), color, translation key} of every legacy fluid but left-out mods'. */
    @SuppressWarnings("unchecked")
    public static Object[][] legacyFluids() {
        List<Object[]> fluids = new ArrayList<Object[]>();
        for (Map.Entry<String, Fluid> entry : FluidRegistry.getRegisteredFluids().entrySet()) {
            Fluid fluid = entry.getValue();
            net.minecraft.block.Block block = fluid.getBlock();
            if (Bridge.leftOut(fluid.getClass()) || (block != null && Bridge.leftOut(String.valueOf(net.minecraft.block.Block.field_149771_c.func_148750_c(block))))) {
                continue; // (see Bridge.leftOut; blockRegistry.getNameForObject)
            }
            int color;
            try {
                color = fluid.getColor();
            } catch (Throwable t) {
                color = 0xFFFFFF;
            }
            fluids.add(new Object[]{entry.getKey(), fluid.getDensity(), fluid.getTemperature(), fluid.getLuminosity(), fluid.getViscosity(),
                    fluid.isGaseous() ? 1 : 0, color, fluid.getUnlocalizedName()});
        }
        return fluids.toArray(new Object[0][]);
    }

    /** A 1.20.1 mod's fluid joins the FluidRegistry under its id, so legacy tanks and pipes carry it. */
    public static void registerModernFluid(String name, int density, int temperature, int luminosity, int viscosity, boolean gaseous,
                                           String translationKey) {
        if (FluidRegistry.isFluidRegistered(name)) {
            return;
        }
        Fluid fluid = new Fluid(name).setDensity(density).setTemperature(temperature).setLuminosity(luminosity)
                .setViscosity(viscosity).setGaseous(gaseous).setUnlocalizedName(translationKey);
        FluidRegistry.registerFluid(fluid);
    }

    /** Self-test (the mod sweep): a 1.20.1 tank at a position, filled with 1000 mB of water by legacy code. */
    public static String checkHostFluids(Object legacyWorld, int x, int y, int z) {
        Object tile = ((net.minecraft.world.World) legacyWorld).func_147438_o(x, y, z); // getTileEntity
        if (!(tile instanceof IFluidHandler)) {
            return "FAILED: not an IFluidHandler: " + tile;
        }
        IFluidHandler handler = (IFluidHandler) tile;
        int filled = handler.fill(ForgeDirection.UP, new FluidStack(FluidRegistry.WATER, 1000), true);
        FluidTankInfo[] tanks = handler.getTankInfo(ForgeDirection.UP);
        return (filled == 1000 ? "filled 1000 mB: OK" : "filled " + filled + " mB: FAILED") + "; " + tanks.length + " tank(s) of "
                + (tanks.length > 0 ? tanks[0].capacity : 0) + " mB";
    }

    /** Client: {still icon name, flowing icon name} of a legacy fluid (null where it has none). */
    public static String[] fluidIcons(String name) {
        Fluid fluid = FluidRegistry.getFluid(name);
        if (fluid == null) {
            return new String[2];
        }
        String still = null;
        String flowing = null;
        try {
            still = fluid.getStillIcon() == null ? null : fluid.getStillIcon().func_94215_i(); // getIconName
            flowing = fluid.getFlowingIcon() == null ? null : fluid.getFlowingIcon().func_94215_i();
        } catch (Throwable t) {
            // icons not registered yet
        }
        return new String[]{still, flowing};
    }

    static FluidStack stack(String name, int amount, byte[] tag) throws IOException {
        Fluid fluid = name == null ? null : FluidRegistry.getFluid(name);
        if (fluid == null || amount <= 0) {
            return null;
        }
        FluidStack stack = new FluidStack(fluid, amount);
        if (tag != null) {
            stack.tag = Bridge.fromBytes(tag);
        }
        return stack;
    }

    static Object[] read(FluidStack stack) throws IOException {
        if (stack == null || stack.getFluid() == null || stack.amount <= 0) {
            return null;
        }
        NBTTagCompound tag = stack.tag;
        return new Object[]{stack.getFluid().getName(), stack.amount, tag == null ? null : Bridge.toBytes(tag)};
    }

    static ForgeDirection direction(int side) {
        return side < 0 || side > 5 ? ForgeDirection.UNKNOWN : ForgeDirection.getOrientation(side);
    }

    // ---- Legacy tanks as 1.20.1 fluid handlers (the host asks) ----

    public static boolean legacyHasTanks(Object tile) {
        return tile instanceof IFluidHandler;
    }

    /** {fluid name or null, amount, capacity} of each tank on a side. */
    public static Object[][] legacyTanks(Object tile, int side) {
        if (!(tile instanceof IFluidHandler)) {
            return new Object[0][];
        }
        FluidTankInfo[] tanks = ((IFluidHandler) tile).getTankInfo(direction(side));
        if (tanks == null) {
            return new Object[0][];
        }
        Object[][] result = new Object[tanks.length][];
        for (int i = 0; i < tanks.length; i++) {
            FluidStack fluid = tanks[i] == null ? null : tanks[i].fluid;
            boolean has = fluid != null && fluid.getFluid() != null && fluid.amount > 0;
            result[i] = new Object[]{has ? fluid.getFluid().getName() : null, has ? fluid.amount : 0, tanks[i] == null ? 0 : tanks[i].capacity};
        }
        return result;
    }

    public static int legacyFluidFill(Object tile, int side, String name, int amount, byte[] tag, boolean doFill) throws IOException {
        if (!(tile instanceof IFluidHandler)) {
            return 0;
        }
        FluidStack stack = stack(name, amount, tag);
        IFluidHandler handler = (IFluidHandler) tile;
        if (stack == null || !handler.canFill(direction(side), stack.getFluid())) {
            return 0;
        }
        return Math.max(0, handler.fill(direction(side), stack, doFill));
    }

    /** {name, amount, tag bytes} drained (of the given fluid, or any if name is null), or null. */
    public static Object[] legacyFluidDrain(Object tile, int side, String name, int amount, boolean doDrain) throws IOException {
        if (!(tile instanceof IFluidHandler) || amount <= 0) {
            return null;
        }
        IFluidHandler handler = (IFluidHandler) tile;
        ForgeDirection from = direction(side);
        if (name == null) {
            return read(handler.drain(from, amount, doDrain));
        }
        FluidStack wanted = stack(name, amount, null);
        if (wanted == null || !handler.canDrain(from, wanted.getFluid())) {
            return null;
        }
        return read(handler.drain(from, wanted, doDrain));
    }
}
