package retrofit.legacy.bridge;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.chunk.storage.IChunkLoader;
import net.minecraft.world.storage.IPlayerFileData;
import net.minecraft.world.storage.SaveHandler;
import net.minecraft.world.storage.WorldInfo;

import java.io.File;

/**
 * Save handler of legacy worlds. The host level saves blocks, tile entities and players; what
 * remains is mods' world data (WorldSavedData), kept in the host world's "retrofit" folder for
 * server worlds. Client and isolated worlds store nothing (as 1.7.10's SaveHandlerMP).
 *
 * A SaveHandler, as every server world's was (Forge's DimensionManager and mods like NEI cast to
 * it), but created without its constructor, which would make folders and take a session lock.
 */
final class BridgeSaveHandler extends SaveHandler {
    private boolean persistent;

    private BridgeSaveHandler() {
        super(null, null, false); // never called; see create
    }

    static BridgeSaveHandler create(boolean persistent) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            BridgeSaveHandler handler = (BridgeSaveHandler) unsafeClass.getMethod("allocateInstance", Class.class)
                    .invoke(theUnsafe.get(null), BridgeSaveHandler.class);
            handler.persistent = persistent;
            return handler;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retrofit: cannot create the legacy save handler", e);
        }
    }

    /** loadWorldInfo */
    @Override
    public WorldInfo func_75757_d() {
        return null;
    }

    /** checkSessionLock */
    @Override
    public void func_75762_c() {
    }

    /** getChunkLoader */
    @Override
    public IChunkLoader func_75763_a(WorldProvider provider) {
        return null;
    }

    /** saveWorldInfoWithPlayer */
    @Override
    public void func_75755_a(WorldInfo info, NBTTagCompound player) {
    }

    /** saveWorldInfo */
    @Override
    public void func_75761_a(WorldInfo info) {
    }

    /** getSaveHandler (player data): players are saved by the host */
    @Override
    public IPlayerFileData func_75756_e() {
        return this;
    }

    /** writePlayerData */
    @Override
    public void func_75753_a(EntityPlayer player) {
    }

    /** readPlayerData */
    @Override
    public NBTTagCompound func_75752_b(EntityPlayer player) {
        return null;
    }

    /** getAvailablePlayerDat */
    @Override
    public String[] func_75754_f() {
        return new String[0];
    }

    @Override
    public NBTTagCompound getPlayerNBT(EntityPlayerMP player) {
        return null;
    }

    /** flush */
    @Override
    public void func_75759_a() {
    }

    /** getWorldDirectory */
    @Override
    public File func_75765_b() {
        return persistent ? LegacyWorlds.saveDirectory() : null;
    }

    /** getMapFileFromName: shared world data (world.mapStorage) */
    @Override
    public File func_75758_b(String name) {
        if (!persistent) {
            return null;
        }
        File data = new File(LegacyWorlds.saveDirectory(), "data");
        data.mkdirs();
        return new File(data, name + ".dat");
    }

    /** getWorldDirectoryName */
    @Override
    public String func_75760_g() {
        return "retrofit";
    }
}
