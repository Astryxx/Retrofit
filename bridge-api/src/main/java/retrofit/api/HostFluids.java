package retrofit.api;

/**
 * The fluid tanks of a host block (any 1.20.1 block with Forge's fluid handler) as legacy code
 * sees them: legacy pipes and pumps fill and drain them. Fluids go by their legacy names
 * (FluidRegistry: "water", "oil"...); amounts are millibuckets; sides are legacy side numbers.
 */
public interface HostFluids {
    /** {legacy fluid name or null if empty, amount, capacity} of each tank. */
    Object[][] tanks(int side);

    /** Millibuckets of a legacy fluid accepted (or that would be). */
    int fill(int side, String legacyFluid, int amount, boolean simulate);

    /** {legacy fluid name, amount} drained (of one fluid, or any if null), or null. */
    Object[] drain(int side, String legacyFluid, int amount, boolean simulate);
}
