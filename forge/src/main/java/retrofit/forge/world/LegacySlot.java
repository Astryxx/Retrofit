package retrofit.forge.world;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A view of one legacy slot in a {@link LegacyMenu}: its stack, limit and rules are the legacy
 * slot's (getStack, putStack, isItemValid, getSlotStackLimit, canTakeStack). Stacks are converted
 * on each read; the conversion is skipped while the legacy stack is the same object with the same
 * size and damage.
 */
final class LegacySlot extends Slot {
    private final LegacyMenu menu;
    private final int legacyIndex;
    private Object lastLegacy;
    private int lastFingerprint;
    private ItemStack lastModern = ItemStack.EMPTY;

    LegacySlot(Container none, LegacyMenu menu, int legacyIndex, int x, int y) {
        super(none, legacyIndex, x, y);
        this.menu = menu;
        this.legacyIndex = legacyIndex;
    }

    @Override
    public ItemStack getItem() {
        try {
            Object legacy = LegacyBridge.call("slotStack", menu.legacyContainer(), legacyIndex);
            if (legacy == null) {
                lastLegacy = null;
                lastModern = ItemStack.EMPTY;
                return lastModern;
            }
            int fingerprint = LegacyBridge.call("stackFingerprint", legacy);
            if (legacy != lastLegacy || fingerprint != lastFingerprint) {
                lastLegacy = legacy;
                lastFingerprint = fingerprint;
                lastModern = LegacyStacks.toModern(legacy);
            }
            return lastModern;
        } catch (RuntimeException e) {
            LegacyErrors.report("slot of " + menu.legacyContainer().getClass().getName(), "reading its item", e);
            return ItemStack.EMPTY;
        }
    }

    @Override
    public boolean hasItem() {
        return !getItem().isEmpty();
    }

    @Override
    public void set(ItemStack stack) {
        try {
            LegacyBridge.call("setSlotStack", menu.legacyContainer(), legacyIndex, LegacyStacks.toLegacy(stack));
            lastLegacy = null;
        } catch (RuntimeException e) {
            LegacyErrors.report("slot of " + menu.legacyContainer().getClass().getName(), "receiving an item", e);
        }
    }

    @Override
    public void setByPlayer(ItemStack stack) {
        set(stack);
    }

    @Override
    public void setChanged() {
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        Object legacy = LegacyStacks.toLegacy(stack);
        try {
            return legacy != null && LegacyBridge.<Boolean>call("slotAccepts", menu.legacyContainer(), legacyIndex, legacy);
        } catch (RuntimeException e) {
            LegacyErrors.report("slot of " + menu.legacyContainer().getClass().getName(), "checking an item", e);
            return false;
        }
    }

    @Override
    public int getMaxStackSize() {
        try {
            return LegacyBridge.call("slotLimit", menu.legacyContainer(), legacyIndex);
        } catch (RuntimeException e) {
            return 64;
        }
    }

    @Override
    public boolean mayPickup(Player player) {
        try {
            return LegacyBridge.call("slotCanTake", menu.legacyContainer(), legacyIndex, LegacyPlayers.mirror(player, false));
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public ItemStack remove(int amount) {
        ItemStack current = getItem().copy();
        ItemStack taken = current.split(amount);
        set(current);
        return taken;
    }
}
