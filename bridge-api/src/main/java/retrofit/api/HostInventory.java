package retrofit.api;

/**
 * An inventory of the host level (a vanilla chest, furnace or hopper, or any 1.20.1 block with an
 * item handler) as legacy code sees it through a tile entity. Stacks are legacy ItemStacks (or
 * null); sides are legacy side numbers.
 */
public interface HostInventory {
    int size();

    Object getStack(int slot);

    void setStack(int slot, Object legacyStack);

    int stackLimit();

    boolean canPlace(int slot, Object legacyStack);

    /** The slots reachable from a side (ISidedInventory.getAccessibleSlotsFromSide). */
    int[] slotsForSide(int side);

    boolean canInsert(int slot, Object legacyStack, int side);

    boolean canExtract(int slot, Object legacyStack, int side);

    void changed();

    String name();
}
