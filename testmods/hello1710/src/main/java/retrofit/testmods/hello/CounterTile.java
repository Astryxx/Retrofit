package retrofit.testmods.hello;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

/**
 * A 1.7.10 tile entity that counts server ticks and saves the count. Written the way a 1.7.10
 * mod's compiled code looks: against SRG names (MCP names in comments).
 */
public class CounterTile extends TileEntity {
    private int ticks;
    private int loadedTicks = -1;

    /** updateEntity */
    @Override
    public void func_145845_h() {
        if (!this.field_145850_b.field_72995_K) { // !worldObj.isRemote
            this.ticks++;
        }
    }

    /** writeToNBT */
    @Override
    public void func_145841_b(NBTTagCompound tag) {
        super.func_145841_b(tag);
        tag.func_74768_a("Ticks", this.ticks); // setInteger
    }

    /** readFromNBT */
    @Override
    public void func_145839_a(NBTTagCompound tag) {
        super.func_145839_a(tag);
        this.ticks = tag.func_74762_e("Ticks"); // getInteger
        this.loadedTicks = this.ticks;
    }

    @Override
    public String toString() {
        return "CounterTile{ticks=" + ticks
                + ", loadedFromSave=" + (loadedTicks >= 0 ? "yes (had " + loadedTicks + ")" : "no")
                + ", pos=" + field_145851_c + "," + field_145848_d + "," + field_145849_e
                + ", world=" + (field_145850_b == null ? "null" : field_145850_b.getClass().getSimpleName()) + "}";
    }
}
