package retrofit.forge.world;

import net.minecraftforge.energy.IEnergyStorage;

/**
 * A legacy tile entity's energy (CoFH RF, IC2 EU) as Forge Energy for one side, so 1.20.1
 * cables, generators and machines connect to legacy machines. RF is the same unit; 1 EU is 4 FE.
 */
final class LegacyEnergyStorage implements IEnergyStorage {
    private final Object tile;
    private final int side;
    private final int kind;

    LegacyEnergyStorage(Object tile, int side, int kind) {
        this.tile = tile;
        this.side = side;
        this.kind = kind;
    }

    /** Forge Energy for a legacy tile entity's side, or null if it has none there. */
    static LegacyEnergyStorage of(Object tile, int side) {
        int kind = LegacyBridge.call("legacyEnergyKind", tile, side);
        return kind == 0 ? null : new LegacyEnergyStorage(tile, side, kind);
    }

    @Override
    public int receiveEnergy(int amount, boolean simulate) {
        if ((kind & 1) == 0 || amount <= 0) {
            return 0;
        }
        try {
            return LegacyBridge.call("legacyEnergyReceive", tile, side, amount, simulate);
        } catch (RuntimeException e) {
            LegacyErrors.report(tile.getClass().getName(), "receiving energy", e);
            return 0;
        }
    }

    @Override
    public int extractEnergy(int amount, boolean simulate) {
        if ((kind & 2) == 0 || amount <= 0) {
            return 0;
        }
        try {
            return LegacyBridge.call("legacyEnergyExtract", tile, side, amount, simulate);
        } catch (RuntimeException e) {
            LegacyErrors.report(tile.getClass().getName(), "giving energy", e);
            return 0;
        }
    }

    private int[] storage() {
        try {
            return LegacyBridge.call("legacyEnergyStorage", tile, side);
        } catch (RuntimeException e) {
            return new int[]{0, 0};
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
        return (kind & 2) != 0;
    }

    @Override
    public boolean canReceive() {
        return (kind & 1) != 0;
    }
}
