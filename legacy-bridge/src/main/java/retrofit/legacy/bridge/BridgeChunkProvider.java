package retrofit.legacy.bridge;

import net.minecraft.entity.EnumCreatureType;
import net.minecraft.util.IProgressUpdate;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.IChunkProvider;

import java.util.Collections;
import java.util.List;

/**
 * Chunk provider for {@link BridgeWorld}. The host level owns all chunk data, so this only
 * hands out empty chunks to legacy code paths that reach for chunks directly.
 */
final class BridgeChunkProvider implements IChunkProvider {
    private final World world;

    BridgeChunkProvider(World world) {
        this.world = world;
    }

    /** chunkExists */
    @Override
    public boolean func_73149_a(int x, int z) {
        return true;
    }

    /** provideChunk */
    @Override
    public Chunk func_73154_d(int x, int z) {
        return new EmptyChunk(world, x, z);
    }

    /** loadChunk */
    @Override
    public Chunk func_73158_c(int x, int z) {
        return func_73154_d(x, z);
    }

    /** populate */
    @Override
    public void func_73153_a(IChunkProvider provider, int x, int z) {
    }

    /** saveChunks */
    @Override
    public boolean func_73151_a(boolean all, IProgressUpdate progress) {
        return true;
    }

    /** unloadQueuedChunks */
    @Override
    public boolean func_73156_b() {
        return false;
    }

    /** canSave */
    @Override
    public boolean func_73157_c() {
        return false;
    }

    /** makeString */
    @Override
    public String func_73148_d() {
        return "RetrofitBridgeChunkProvider";
    }

    /** getPossibleCreatures */
    @Override
    public List func_73155_a(EnumCreatureType type, int x, int y, int z) {
        return Collections.emptyList();
    }

    /** findClosestStructure */
    @Override
    public ChunkPosition func_147416_a(World world, String name, int x, int y, int z) {
        return null;
    }

    /** getLoadedChunkCount */
    @Override
    public int func_73152_e() {
        return 0;
    }

    /** recreateStructures */
    @Override
    public void func_82695_e(int x, int z) {
    }

    /** saveExtraData */
    @Override
    public void func_104112_b() {
    }
}
