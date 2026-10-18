package retrofit.forge.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.ForgeFlowingFluid;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import org.slf4j.Logger;
import retrofit.api.HostFluids;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Fluids across versions (the host half; see the legacy bridge's Fluids). Every legacy fluid is a
 * 1.20.1 fluid: water and lava are themselves, the others are registered here
 * ("retrofit:oil"...) with the legacy fluid's properties, look and name. Legacy tanks get Forge's
 * fluid handler capability; 1.20.1 tanks are IFluidHandlers to legacy pipes and pumps.
 */
public final class LegacyFluids {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** legacy name -> 1.20.1 source fluid */
    private static final Map<String, Fluid> BY_LEGACY = new LinkedHashMap<>();
    /** 1.20.1 source fluid -> legacy name */
    private static final Map<Fluid, String> TO_LEGACY = new HashMap<>();
    private static final Map<String, Object[]> INFO = new LinkedHashMap<>();

    private LegacyFluids() {
    }

    private static final Map<String, String> PATHS = new HashMap<>();

    /** The 1.20.1 id path for a legacy fluid; unique even when two names clean up alike. */
    private static synchronized String path(String legacyName) {
        return PATHS.computeIfAbsent(legacyName, name -> {
            String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
            String path = base;
            for (int n = 2; PATHS.containsValue(path); n++) {
                path = base + "_" + n;
            }
            return path;
        });
    }

    /** Fluid types and fluids for the legacy fluids 1.20.1 doesn't have (from RegisterEvent). */
    public static void register(RegisterEvent event) {
        if (!LegacyBridge.isReady()) {
            return;
        }
        if (INFO.isEmpty()) {
            try {
                for (Object[] fluid : LegacyBridge.<Object[][]>call("legacyFluids")) {
                    String name = (String) fluid[0];
                    if (!name.equals("water") && !name.equals("lava")) {
                        INFO.put(name, fluid);
                    }
                }
            } catch (RuntimeException e) {
                LOGGER.error("Could not list the legacy fluids", e);
            }
        }
        event.register(ForgeRegistries.Keys.FLUID_TYPES, helper -> {
            for (Object[] fluid : INFO.values()) {
                String name = (String) fluid[0];
                FluidType.Properties properties = FluidType.Properties.create()
                        .density((Integer) fluid[1]).temperature((Integer) fluid[2]).lightLevel(Math.max(0, Math.min(15, (Integer) fluid[3])))
                        .viscosity((Integer) fluid[4]).descriptionId((String) fluid[7]);
                helper.register(new ResourceLocation("retrofit", path(name)), new LegacyFluidType(properties, name, (Integer) fluid[6]));
            }
        });
        event.register(ForgeRegistries.Keys.FLUIDS, helper -> {
            for (Object[] fluid : INFO.values()) {
                String name = (String) fluid[0];
                ResourceLocation typeId = new ResourceLocation("retrofit", path(name));
                ForgeFlowingFluid.Source[] source = new ForgeFlowingFluid.Source[1];
                ForgeFlowingFluid.Flowing[] flowing = new ForgeFlowingFluid.Flowing[1];
                ForgeFlowingFluid.Properties properties = new ForgeFlowingFluid.Properties(
                        () -> ForgeRegistries.FLUID_TYPES.get().getValue(typeId), () -> source[0], () -> flowing[0]);
                source[0] = new ForgeFlowingFluid.Source(properties);
                flowing[0] = new ForgeFlowingFluid.Flowing(properties);
                helper.register(typeId, source[0]);
                helper.register(new ResourceLocation("retrofit", "flowing_" + path(name)), flowing[0]);
                BY_LEGACY.put(name, source[0]);
                TO_LEGACY.put(source[0], name);
            }
            LOGGER.info("Registered {} legacy fluids", INFO.size());
        });
    }

    /** The 1.20.1 fluid for a legacy fluid name, or null. */
    static Fluid modern(String legacyName) {
        if (legacyName == null) {
            return null;
        }
        if (legacyName.equals("water")) {
            return Fluids.WATER;
        }
        if (legacyName.equals("lava")) {
            return Fluids.LAVA;
        }
        Fluid fluid = BY_LEGACY.get(legacyName);
        if (fluid != null) {
            return fluid;
        }
        // A fluid a 1.20.1 mod added under the legacy side's name for it (see legacy()).
        int colon = legacyName.indexOf(':');
        if (colon > 0) {
            Fluid named = ForgeRegistries.FLUIDS.getValue(new ResourceLocation(legacyName.substring(0, colon), legacyName.substring(colon + 1)));
            return named == Fluids.EMPTY ? null : named;
        }
        return null;
    }

    /**
     * 1.20.1 mods' fluids join the legacy FluidRegistry under their ids ("mekanism:hydrogen"), so
     * legacy tanks and pipes can carry them. Done once at common setup, on every side in the same
     * order: legacy mods send fluid ids over the network.
     */
    public static void registerModernFluids() {
        if (!LegacyBridge.isReady()) {
            return;
        }
        java.util.List<ResourceLocation> ids = new java.util.ArrayList<>(BuiltInRegistries.FLUID.keySet());
        java.util.Collections.sort(ids);
        int added = 0;
        for (ResourceLocation id : ids) {
            Fluid fluid = BuiltInRegistries.FLUID.get(id);
            if (fluid == Fluids.EMPTY || !fluid.isSource(fluid.defaultFluidState()) || fluid == Fluids.WATER || fluid == Fluids.LAVA
                    || TO_LEGACY.containsKey(fluid)) {
                continue;
            }
            FluidType type = fluid.getFluidType();
            try {
                LegacyBridge.call("registerModernFluid", id.toString(), type.getDensity(), type.getTemperature(), type.getLightLevel(),
                        type.getViscosity(), type.getDensity() < 0, type.getDescriptionId());
                TO_LEGACY.put(fluid, id.toString());
                added++;
            } catch (RuntimeException e) {
                LOGGER.warn("1.20.1 fluid {} could not join the legacy fluids", id, e);
            }
        }
        LOGGER.info("{} 1.20.1 fluids joined the legacy fluids", added);
    }

    /** The legacy name of a 1.20.1 fluid (its legacy original, or its id; see registerModernFluids), or null. */
    static String legacy(Fluid fluid) {
        if (fluid == null || fluid == Fluids.EMPTY) {
            return null;
        }
        Fluid source = fluid instanceof net.minecraft.world.level.material.FlowingFluid flowing ? flowing.getSource() : fluid;
        if (source == Fluids.WATER) {
            return "water";
        }
        if (source == Fluids.LAVA) {
            return "lava";
        }
        return TO_LEGACY.get(source);
    }

    // ---- Legacy tanks as Forge's 1.20.1 fluid handler ----

    /** A legacy tile entity's tanks (IFluidHandler) for a side, or null. */
    static IFluidHandler handlerOf(Object tile, int side) {
        try {
            return LegacyBridge.<Boolean>call("legacyHasTanks", tile) ? new LegacyTanks(tile, side) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private record LegacyTanks(Object tile, int side) implements IFluidHandler {
        private Object[][] tanks() {
            try {
                return LegacyBridge.call("legacyTanks", tile, side);
            } catch (RuntimeException e) {
                return new Object[0][];
            }
        }

        @Override
        public int getTanks() {
            return tanks().length;
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            Object[][] tanks = tanks();
            if (tank < 0 || tank >= tanks.length) {
                return FluidStack.EMPTY;
            }
            Fluid fluid = modern((String) tanks[tank][0]);
            return fluid == null ? FluidStack.EMPTY : new FluidStack(fluid, (Integer) tanks[tank][1]);
        }

        @Override
        public int getTankCapacity(int tank) {
            Object[][] tanks = tanks();
            return tank < 0 || tank >= tanks.length ? 0 : (Integer) tanks[tank][2];
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            return fill(new FluidStack(stack, 1), FluidAction.SIMULATE) > 0;
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            String name = resource.isEmpty() ? null : legacy(resource.getFluid());
            if (name == null) {
                return 0;
            }
            try {
                return LegacyBridge.call("legacyFluidFill", tile, side, name, resource.getAmount(), null, action.execute());
            } catch (RuntimeException e) {
                LegacyErrors.report(tile.getClass().getName(), "filling", e);
                return 0;
            }
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            String name = resource.isEmpty() ? null : legacy(resource.getFluid());
            return name == null ? FluidStack.EMPTY : drain(name, resource.getAmount(), action);
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            return drain(null, maxDrain, action);
        }

        private FluidStack drain(String name, int amount, FluidAction action) {
            try {
                Object[] drained = LegacyBridge.call("legacyFluidDrain", tile, side, name, amount, action.execute());
                Fluid fluid = drained == null ? null : modern((String) drained[0]);
                return fluid == null ? FluidStack.EMPTY : new FluidStack(fluid, (Integer) drained[1]);
            } catch (RuntimeException e) {
                LegacyErrors.report(tile.getClass().getName(), "draining", e);
                return FluidStack.EMPTY;
            }
        }
    }

    // ---- 1.20.1 tanks for legacy code ----

    /** A 1.20.1 block entity's tanks, or null if it has none (or is a legacy one). */
    static HostFluids hostFluids(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null || blockEntity instanceof LegacyBlockEntity) {
            return null;
        }
        boolean any = blockEntity.getCapability(ForgeCapabilities.FLUID_HANDLER, null).isPresent();
        for (Direction direction : Direction.values()) {
            any |= blockEntity.getCapability(ForgeCapabilities.FLUID_HANDLER, direction).isPresent();
        }
        return any ? new HostTanks(blockEntity) : null;
    }

    private record HostTanks(BlockEntity blockEntity) implements HostFluids {
        private IFluidHandler handler(int side) {
            if (blockEntity.isRemoved()) {
                return null;
            }
            Direction direction = side >= 0 && side < 6 ? Direction.from3DDataValue(side) : null;
            IFluidHandler handler = blockEntity.getCapability(ForgeCapabilities.FLUID_HANDLER, direction).orElse(null);
            if (handler == null && direction == null) {
                for (Direction each : Direction.values()) {
                    handler = blockEntity.getCapability(ForgeCapabilities.FLUID_HANDLER, each).orElse(null);
                    if (handler != null) {
                        break;
                    }
                }
            }
            return handler;
        }

        @Override
        public Object[][] tanks(int side) {
            IFluidHandler handler = handler(side);
            if (handler == null) {
                return new Object[0][];
            }
            Object[][] tanks = new Object[handler.getTanks()][];
            for (int i = 0; i < tanks.length; i++) {
                FluidStack fluid = handler.getFluidInTank(i);
                String name = fluid.isEmpty() ? null : legacy(fluid.getFluid());
                tanks[i] = new Object[]{name, name == null ? 0 : fluid.getAmount(), handler.getTankCapacity(i)};
            }
            return tanks;
        }

        @Override
        public int fill(int side, String legacyFluid, int amount, boolean simulate) {
            IFluidHandler handler = handler(side);
            Fluid fluid = modern(legacyFluid);
            if (handler == null || fluid == null || amount <= 0) {
                return 0;
            }
            return handler.fill(new FluidStack(fluid, amount), simulate ? IFluidHandler.FluidAction.SIMULATE : IFluidHandler.FluidAction.EXECUTE);
        }

        @Override
        public Object[] drain(int side, String legacyFluid, int amount, boolean simulate) {
            IFluidHandler handler = handler(side);
            if (handler == null || amount <= 0) {
                return null;
            }
            IFluidHandler.FluidAction action = simulate ? IFluidHandler.FluidAction.SIMULATE : IFluidHandler.FluidAction.EXECUTE;
            FluidStack drained;
            if (legacyFluid == null) {
                drained = handler.drain(amount, action);
            } else {
                Fluid fluid = modern(legacyFluid);
                drained = fluid == null ? FluidStack.EMPTY : handler.drain(new FluidStack(fluid, amount), action);
            }
            String name = drained.isEmpty() ? null : legacy(drained.getFluid());
            return name == null ? null : new Object[]{name, drained.getAmount()};
        }
    }

    /** A legacy fluid's type: its look on clients comes from the legacy fluid's icons and colour. */
    private static final class LegacyFluidType extends FluidType {
        private final String legacyName;
        private final int color;

        LegacyFluidType(Properties properties, String legacyName, int color) {
            super(properties);
            this.legacyName = legacyName;
            this.color = color;
        }

        @Override
        public void initializeClient(java.util.function.Consumer<net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions> consumer) {
            consumer.accept(retrofit.forge.client.LegacyFluidLook.of(legacyName, color));
        }
    }
}
