package retrofit.testmods.hello;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;

/**
 * A 1.7.10 tile entity that exercises the world bridge the way real mods do. Written against SRG
 * names, as compiled 1.7.10 mods are (MCP names in comments).
 *
 * - Counts server ticks and saves the count (persistence).
 * - Once per load, reads the block below it and places spruce planks (planks:1) to the east
 *   (vanilla block table, both directions).
 * - Sends its count to clients with a description packet every second (client sync).
 */
public class CounterTile extends TileEntity {
    private int ticks;
    private int loadedTicks = -1;
    private int clientTicks = -1;
    private String seenBelow = "?";

    int ticks() {
        return ticks;
    }

    /** updateEntity */
    @Override
    public void func_145845_h() {
        if (this.field_145850_b.field_72995_K) { // worldObj.isRemote
            return;
        }
        this.ticks++;
        if ("?".equals(seenBelow)) {
            int x = field_145851_c, y = field_145848_d, z = field_145849_e;
            Block below = field_145850_b.func_147439_a(x, y - 1, z); // getBlock
            seenBelow = Block.field_149771_c.func_148750_c(below) + ":" + field_145850_b.func_72805_g(x, y - 1, z); // name:meta
            field_145850_b.func_147465_d(x + 1, y, z, Blocks.field_150344_f, 1, 3); // setBlock(planks, spruce)
        }
        if (this.ticks % 20 == 0) {
            field_145850_b.func_147471_g(field_145851_c, field_145848_d, field_145849_e); // markBlockForUpdate
        }
    }

    /** getDescriptionPacket */
    @Override
    public Packet func_145844_m() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.func_74768_a("Ticks", this.ticks);
        return new S35PacketUpdateTileEntity(field_145851_c, field_145848_d, field_145849_e, 1, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity packet) {
        this.clientTicks = packet.func_148857_g().func_74762_e("Ticks"); // getNbtCompound().getInteger
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
        boolean client = field_145850_b != null && field_145850_b.field_72995_K;
        if (client) {
            return "CounterTile{client, syncedTicks=" + clientTicks + "}";
        }
        return "CounterTile{ticks=" + ticks
                + ", loadedFromSave=" + (loadedTicks >= 0 ? "yes (had " + loadedTicks + ")" : "no")
                + ", below=" + seenBelow
                + ", pos=" + field_145851_c + "," + field_145848_d + "," + field_145849_e
                + ", world=" + (field_145850_b == null ? "null" : field_145850_b.getClass().getSimpleName()) + "}";
    }
}
