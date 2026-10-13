package retrofit.legacy.bridge;

import net.minecraft.block.Block;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import retrofit.api.HostWorld;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Static entry points the host calls into the legacy world. Legacy objects cross the boundary
 * as opaque {@code Object}s and NBT as bytes (the binary NBT format is the same in 1.7.10 and
 * 1.20.1), so the host never needs legacy types.
 */
public final class Bridge {
    private Bridge() {
    }

    public static Object createWorld(HostWorld host, int dimension) {
        return new BridgeWorld(host, dimension);
    }

    /** Registry names of all blocks added by legacy mods ("modid:name"). */
    public static String[] modBlockNames() {
        List<String> names = new ArrayList<String>();
        for (Object key : Block.field_149771_c.func_148742_b()) { // blockRegistry.getKeys()
            String name = String.valueOf(key);
            if (!name.startsWith("minecraft:")) {
                names.add(name);
            }
        }
        return names.toArray(new String[0]);
    }

    /** getBlockFromName */
    public static Object block(String name) {
        return Block.func_149684_b(name);
    }

    public static boolean hasTileEntity(Object block, int meta) {
        return ((Block) block).hasTileEntity(meta);
    }

    public static Object createTileEntity(Object world, Object block, int meta, int x, int y, int z) {
        TileEntity tileEntity = ((Block) block).createTileEntity((World) world, meta);
        if (tileEntity != null) {
            place(tileEntity, (World) world, x, y, z);
        }
        return tileEntity;
    }

    /** Rebuilds a tile entity from NBT saved by {@link #save}. */
    public static Object load(Object world, byte[] nbt, int x, int y, int z) throws IOException {
        NBTTagCompound tag = CompressedStreamTools.func_74794_a(new DataInputStream(new ByteArrayInputStream(nbt))); // read
        TileEntity tileEntity = TileEntity.func_145827_c(tag); // createAndLoadEntity
        if (tileEntity != null) {
            place(tileEntity, (World) world, x, y, z);
        }
        return tileEntity;
    }

    public static byte[] save(Object tileEntity) throws IOException {
        NBTTagCompound tag = new NBTTagCompound();
        ((TileEntity) tileEntity).func_145841_b(tag); // writeToNBT
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompressedStreamTools.func_74800_a(tag, new DataOutputStream(bytes)); // write
        return bytes.toByteArray();
    }

    public static boolean canUpdate(Object tileEntity) {
        return ((TileEntity) tileEntity).canUpdate();
    }

    /** updateEntity */
    public static void tick(Object tileEntity) {
        ((TileEntity) tileEntity).func_145845_h();
    }

    /** invalidate */
    public static void remove(Object tileEntity) {
        ((TileEntity) tileEntity).func_145843_s();
    }

    public static String describe(Object legacyObject) {
        return String.valueOf(legacyObject);
    }

    /** What World.setTileEntity does for a new tile entity. */
    private static void place(TileEntity tileEntity, World world, int x, int y, int z) {
        tileEntity.func_145834_a(world); // setWorldObj
        tileEntity.field_145851_c = x;   // xCoord
        tileEntity.field_145848_d = y;   // yCoord
        tileEntity.field_145849_e = z;   // zCoord
        tileEntity.func_145829_t();      // validate
    }
}
