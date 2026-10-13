package retrofit.testmods.hello;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

/** A 9-slot inventory tile entity, the way 1.7.10 machines and chests are built. */
public class CrateTile extends TileEntity implements IInventory {
    private final ItemStack[] stacks = new ItemStack[9];
    int ticks;

    /** updateEntity */
    @Override
    public void func_145845_h() {
        if (!field_145850_b.field_72995_K) {
            ticks++;
        }
    }

    @Override
    public int func_70302_i_() { // getSizeInventory
        return stacks.length;
    }

    @Override
    public ItemStack func_70301_a(int slot) { // getStackInSlot
        return stacks[slot];
    }

    @Override
    public ItemStack func_70298_a(int slot, int amount) { // decrStackSize
        if (stacks[slot] == null) {
            return null;
        }
        if (stacks[slot].field_77994_a <= amount) {
            ItemStack taken = stacks[slot];
            stacks[slot] = null;
            func_70296_d();
            return taken;
        }
        ItemStack taken = stacks[slot].func_77979_a(amount); // splitStack
        func_70296_d();
        return taken;
    }

    @Override
    public ItemStack func_70304_b(int slot) { // getStackInSlotOnClosing
        return null;
    }

    @Override
    public void func_70299_a(int slot, ItemStack stack) { // setInventorySlotContents
        stacks[slot] = stack;
        func_70296_d();
    }

    @Override
    public String func_145825_b() { // getInventoryName
        return "container.crate";
    }

    @Override
    public boolean func_145818_k_() { // hasCustomInventoryName
        return false;
    }

    @Override
    public int func_70297_j_() { // getInventoryStackLimit
        return 64;
    }

    @Override
    public boolean func_70300_a(EntityPlayer player) { // isUseableByPlayer
        return player.func_70092_e(field_145851_c + 0.5, field_145848_d + 0.5, field_145849_e + 0.5) <= 64; // getDistanceSq
    }

    @Override
    public void func_70295_k_() { // openInventory
    }

    @Override
    public void func_70305_f() { // closeInventory
    }

    @Override
    public boolean func_94041_b(int slot, ItemStack stack) { // isItemValidForSlot
        return true;
    }

    /** writeToNBT */
    @Override
    public void func_145841_b(NBTTagCompound tag) {
        super.func_145841_b(tag);
        NBTTagList items = new NBTTagList();
        for (int i = 0; i < stacks.length; i++) {
            if (stacks[i] != null) {
                NBTTagCompound item = new NBTTagCompound();
                item.func_74774_a("Slot", (byte) i); // setByte
                stacks[i].func_77955_b(item); // writeToNBT
                items.func_74742_a(item); // appendTag
            }
        }
        tag.func_74782_a("Items", items); // setTag
    }

    /** readFromNBT */
    @Override
    public void func_145839_a(NBTTagCompound tag) {
        super.func_145839_a(tag);
        NBTTagList items = tag.func_150295_c("Items", 10); // getTagList(compound)
        for (int i = 0; i < items.func_74745_c(); i++) { // tagCount
            NBTTagCompound item = items.func_150305_b(i); // getCompoundTagAt
            int slot = item.func_74771_c("Slot") & 255; // getByte
            if (slot < stacks.length) {
                stacks[slot] = ItemStack.func_77949_a(item); // loadItemStackFromNBT
            }
        }
    }
}
