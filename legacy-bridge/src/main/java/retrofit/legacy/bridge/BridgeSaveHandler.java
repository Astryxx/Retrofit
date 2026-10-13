package retrofit.legacy.bridge;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.chunk.storage.IChunkLoader;
import net.minecraft.world.storage.IPlayerFileData;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.WorldInfo;

import java.io.File;

/**
 * Save handler of legacy worlds. The host level saves blocks, tile entities and players; what
 * remains is mods' world data (WorldSavedData), kept in the host world's "retrofit" folder for
 * server worlds. Client and isolated worlds store nothing (as 1.7.10's SaveHandlerMP).
 */
final class BridgeSaveHandler implements ISaveHandler {
    private final boolean persistent;

    BridgeSaveHandler(boolean persistent) {
        this.persistent = persistent;
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

    /** getSaveHandler (player data) */
    @Override
    public IPlayerFileData func_75756_e() {
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
