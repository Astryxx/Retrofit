package retrofit.forge.world;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;

/**
 * A legacy electric item (RF tools and batteries, IC2's electric tools, armour and batteries) as
 * Forge Energy, so 1.20.1 chargers fill it and 1.20.1 machines can run off it. Each call works on
 * the stack's legacy form and writes its charge back into the stack.
 */
final class LegacyItemEnergy implements IEnergyStorage, ICapabilityProvider {
    private final ItemStack stack;
    private final LazyOptional<IEnergyStorage> self = LazyOptional.of(() -> this);

    private LegacyItemEnergy(ItemStack stack) {
        this.stack = stack;
    }

    /** The capability provider for a legacy item's stack (Item.initCapabilities). */
    static ICapabilityProvider of(ItemStack stack) {
        return new LegacyItemEnergy(stack);
    }

    private int kind() {
        Object legacy = LegacyStacks.toLegacy(stack);
        try {
            return legacy == null ? 0 : LegacyBridge.<Integer>call("itemEnergyKind", legacy);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Whether an item handles energy at all (asked once per item: capabilities are asked for constantly). */
    private static final java.util.Map<net.minecraft.world.item.Item, Boolean> ENERGY_ITEMS = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
        if (capability != ForgeCapabilities.ENERGY || !LegacyBridge.isReady() || stack.isEmpty()) {
            return LazyOptional.empty();
        }
        Boolean energy = ENERGY_ITEMS.get(stack.getItem());
        if (energy == null) {
            energy = kind() != 0;
            ENERGY_ITEMS.put(stack.getItem(), energy);
        }
        return energy ? self.cast() : LazyOptional.empty();
    }

    private int transfer(String call, int amount, boolean simulate) {
        if (amount <= 0 || stack.isEmpty()) {
            return 0;
        }
        Object legacy = LegacyStacks.toLegacy(stack);
        if (legacy == null) {
            return 0;
        }
        try {
            int moved = LegacyBridge.call(call, legacy, amount, simulate);
            if (!simulate && moved > 0) {
                ItemStack changed = LegacyStacks.toModern(legacy);
                if (changed.getItem() == stack.getItem()) {
                    stack.setTag(changed.getTag()); // the new charge (and durability bar)
                }
            }
            return moved;
        } catch (RuntimeException e) {
            LegacyErrors.report(stack.getItem(), "moving energy", e);
            return 0;
        }
    }

    @Override
    public int receiveEnergy(int amount, boolean simulate) {
        return (kind() & 1) == 0 ? 0 : transfer("itemEnergyReceive", amount, simulate);
    }

    @Override
    public int extractEnergy(int amount, boolean simulate) {
        return (kind() & 2) == 0 ? 0 : transfer("itemEnergyExtract", amount, simulate);
    }

    private int[] storage() {
        Object legacy = LegacyStacks.toLegacy(stack);
        try {
            return legacy == null ? new int[2] : LegacyBridge.call("itemEnergyStorage", legacy);
        } catch (RuntimeException e) {
            return new int[2];
        }
    }

    @Override
    public int getEnergyStored() {
        return storage()[0];
    }

    @Override
    public int getMaxEnergyStored() {
        return storage()[1];
    }

    @Override
    public boolean canExtract() {
        return (kind() & 2) != 0;
    }

    @Override
    public boolean canReceive() {
        return (kind() & 1) != 0;
    }
}
