package retrofit.legacy.bridge;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.chunk.storage.IChunkLoader;
import net.minecraft.world.storage.IPlayerFileData;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.WorldInfo;

import java.io.File;

/**
 * Save handler for {@link BridgeWorld}: the host level saves everything, so this stores
 * nothing. (1.7.10's own no-op handler, SaveHandlerMP, only exists on the client.)
 */
final class BridgeSaveHandler implements ISaveHandler {
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
        return null;
    }

    /** getMapFileFromName */
    @Override
    public File func_75758_b(String name) {
        return null;
    }

    /** getWorldDirectoryName */
    @Override
    public String func_75760_g() {
        return "retrofit";
    }
}
