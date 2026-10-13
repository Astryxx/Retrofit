package retrofit.testmods.hello;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ICrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * The crate's container: 9 crate slots, the player's inventory, shift-click between them, and
 * the crate's tick count sent as a progress bar (the way furnaces send their cook time).
 */
public class CrateContainer extends Container {
    private final CrateTile crate;
    private int lastTicks = -1;
    int clientTicks;

    public CrateContainer(InventoryPlayer playerInventory, CrateTile crate) {
        this.crate = crate;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                func_75146_a(new Slot(crate, column + row * 3, 62 + column * 18, 17 + row * 18)); // addSlotToContainer
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                func_75146_a(new Slot(playerInventory, column + row * 9 + 9, 8 + column * 18, 84 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            func_75146_a(new Slot(playerInventory, column, 8 + column * 18, 142));
        }
    }

    /** canInteractWith */
    @Override
    public boolean func_75145_c(EntityPlayer player) {
        return crate.func_70300_a(player);
    }

    /** transferStackInSlot: crate <-> player inventory */
    @Override
    public ItemStack func_82846_b(EntityPlayer player, int index) {
        Slot slot = (Slot) field_75151_b.get(index);
        if (slot == null || !slot.func_75216_d()) { // getHasStack
            return null;
        }
        ItemStack stack = slot.func_75211_c();
        ItemStack original = stack.func_77946_l(); // copy
        if (index < 9) {
            if (!func_75135_a(stack, 9, 45, true)) { // mergeItemStack
                return null;
            }
        } else if (!func_75135_a(stack, 0, 9, false)) {
            return null;
        }
        if (stack.field_77994_a == 0) {
            slot.func_75215_d(null); // putStack
        } else {
            slot.func_75218_e(); // onSlotChanged
        }
        return original;
    }

    /** detectAndSendChanges: the tick count as progress bar 0 */
    @Override
    public void func_75142_b() {
        super.func_75142_b();
        if (crate.ticks != lastTicks) {
            lastTicks = crate.ticks;
            for (Object crafter : field_75149_d) { // crafters
                ((ICrafting) crafter).func_71112_a(this, 0, lastTicks % 32000); // sendProgressBarUpdate
            }
        }
    }

    /** updateProgressBar */
    @Override
    public void func_75137_b(int id, int value) {
        if (id == 0) {
            clientTicks = value;
        }
    }
}
