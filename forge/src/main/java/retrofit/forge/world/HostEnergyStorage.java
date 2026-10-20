package retrofit.forge.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import retrofit.api.HostEnergy;

/**
 * A 1.20.1 block entity's Forge Energy for legacy code (RF machines, IC2's energy net; see the
 * legacy bridge's Energy). Each call asks the block entity for the side's storage, as Forge
 * capabilities are per side.
 */
final class HostEnergyStorage implements HostEnergy {
    private final BlockEntity blockEntity;

    private HostEnergyStorage(BlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    /** The energy at a position, or null if the block entity there has none (or is a legacy one). */
    static HostEnergy at(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null || blockEntity instanceof LegacyBlockEntity) {
            return null;
        }
        if (!blockEntity.getCapability(ForgeCapabilities.ENERGY, null).isPresent()) {
            boolean anySide = false;
            for (Direction direction : Direction.values()) {
                anySide |= blockEntity.getCapability(ForgeCapabilities.ENERGY, direction).isPresent();
            }
            if (!anySide) {
                return null;
            }
        }
        return new HostEnergyStorage(blockEntity);
    }

    private IEnergyStorage storage(int side) {
        if (blockEntity.isRemoved()) {
            return null;
        }
        Direction direction = side >= 0 && side < 6 ? Direction.from3DDataValue(side) : null;
        IEnergyStorage storage = blockEntity.getCapability(ForgeCapabilities.ENERGY, direction).orElse(null);
        if (storage == null && direction == null) { // only per side: any side will do
            for (Direction each : Direction.values()) {
                storage = blockEntity.getCapability(ForgeCapabilities.ENERGY, each).orElse(null);
                if (storage != null) {
                    break;
                }
            }
        }
        return storage;
    }

    @Override
    public int receive(int side, int amount, boolean simulate) {
        IEnergyStorage storage = storage(side);
        return storage == null || amount <= 0 || !storage.canReceive() ? 0 : storage.receiveEnergy(amount, simulate);
    }

    @Override
    public int extract(int side, int amount, boolean simulate) {
        IEnergyStorage storage = storage(side);
        return storage == null || amount <= 0 || !storage.canExtract() ? 0 : storage.extractEnergy(amount, simulate);
    }

    @Override
    public int stored(int side) {
        IEnergyStorage storage = storage(side);
        return storage == null ? 0 : storage.getEnergyStored();
    }

    @Override
    public int capacity(int side) {
        IEnergyStorage storage = storage(side);
        return storage == null ? 0 : storage.getMaxEnergyStored();
    }

    @Override
    public boolean canReceive(int side) {
        IEnergyStorage storage = storage(side);
        return storage != null && storage.canReceive();
    }

    @Override
    public boolean canExtract(int side) {
        IEnergyStorage storage = storage(side);
        return storage != null && storage.canExtract();
    }
}
