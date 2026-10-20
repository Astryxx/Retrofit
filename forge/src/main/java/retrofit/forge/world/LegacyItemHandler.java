package retrofit.forge.world;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

/**
 * A legacy tile entity's inventory (IInventory, or ISidedInventory for one side) as Forge's item
 * handler, so 1.20.1 hoppers, pipes and storage mods can move items in and out. Every operation
 * goes through the legacy inventory's own rules (isItemValidForSlot, canInsertItem,
 * canExtractItem, the stack limit).
 */
final class LegacyItemHandler implements IItemHandler {
    private final Object tile;
    private final int side;
    private final int[] slots;

    LegacyItemHandler(Object tile, int side, int[] slots) {
        this.tile = tile;
        this.side = side;
        this.slots = slots;
    }

    @Override
    public int getSlots() {
        return slots.length;
    }

    private Object legacy(int slot) {
        return LegacyBridge.call("inventoryGet", tile, slots[slot]);
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        try {
            return LegacyStacks.toModern(legacy(slot));
        } catch (RuntimeException e) {
            LegacyErrors.report(tile.getClass().getName(), "reading its inventory", e);
            return ItemStack.EMPTY;
        }
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        Object incoming = LegacyStacks.toLegacy(stack);
        if (incoming == null) {
            return stack; // a 1.20.1-only item: no legacy inventory can hold it
        }
        try {
            if (!LegacyBridge.<Boolean>call("inventoryCanInsert", tile, slots[slot], incoming, side)) {
                return stack;
            }
            ItemStack current = getStackInSlot(slot);
            int limit = Math.min(getSlotLimit(slot), stack.getMaxStackSize());
            if (!current.isEmpty() && !ItemStack.isSameItemSameTags(current, stack)) {
                return stack;
            }
            int room = limit - current.getCount();
            if (room <= 0) {
                return stack;
            }
            int moved = Math.min(room, stack.getCount());
            if (!simulate) {
                ItemStack result = stack.copyWithCount(current.getCount() + moved);
                LegacyBridge.call("inventorySet", tile, slots[slot], LegacyStacks.toLegacy(result));
            }
            return stack.copyWithCount(stack.getCount() - moved);
        } catch (RuntimeException e) {
            LegacyErrors.report(tile.getClass().getName(), "receiving items", e);
            return stack;
        }
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount <= 0) {
            return ItemStack.EMPTY;
        }
        try {
            Object legacy = legacy(slot);
            ItemStack current = LegacyStacks.toModern(legacy);
            if (current.isEmpty() || !LegacyBridge.<Boolean>call("inventoryCanExtract", tile, slots[slot], legacy, side)) {
                return ItemStack.EMPTY;
            }
            int taken = Math.min(amount, current.getCount());
            if (!simulate) {
                ItemStack rest = current.copyWithCount(current.getCount() - taken);
                LegacyBridge.call("inventorySet", tile, slots[slot], LegacyStacks.toLegacy(rest));
            }
            return current.copyWithCount(taken);
        } catch (RuntimeException e) {
            LegacyErrors.report(tile.getClass().getName(), "giving items", e);
            return ItemStack.EMPTY;
        }
    }

    @Override
    public int getSlotLimit(int slot) {
        try {
            return LegacyBridge.call("inventoryLimit", tile);
        } catch (RuntimeException e) {
            return 64;
        }
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        Object legacy = LegacyStacks.toLegacy(stack);
        try {
            return legacy != null && LegacyBridge.<Boolean>call("inventoryCanInsert", tile, slots[slot], legacy, side);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
