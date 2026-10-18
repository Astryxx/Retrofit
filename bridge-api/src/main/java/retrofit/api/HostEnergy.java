package retrofit.api;

/**
 * Energy storage of a host block (any 1.20.1 block with Forge Energy) as legacy code sees it: RF
 * machines and cables, and IC2's energy net, move energy in and out of it. Amounts are in Forge
 * Energy (the same unit as RF); sides are legacy side numbers, -1 for none.
 */
public interface HostEnergy {
    /** Energy accepted (or that would be). */
    int receive(int side, int amount, boolean simulate);

    /** Energy given out (or that would be). */
    int extract(int side, int amount, boolean simulate);

    int stored(int side);

    int capacity(int side);

    boolean canReceive(int side);

    boolean canExtract(int side);
}
