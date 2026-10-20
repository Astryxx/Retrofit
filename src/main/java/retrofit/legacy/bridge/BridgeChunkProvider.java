package retrofit.legacy.bridge;

import net.minecraft.entity.EnumCreatureType;
import net.minecraft.util.IProgressUpdate;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;

import java.util.Collections;
import java.util.List;

/**
 * Chunk provider of the legacy worlds. The host level owns all chunk data; legacy code that reaches
 * for chunks directly gets views of the world ({@link BridgeChunk}).
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

    /** Recently used chunk views (they are cheap, but mods ask for the same few chunks over and over). */
    private final java.util.Map<Long, Chunk> chunks = new java.util.LinkedHashMap<Long, Chunk>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Long, Chunk> eldest) {
            return size() > 256;
        }
    };

    /** provideChunk: a view of the world (see BridgeChunk) */
    @Override
    public synchronized Chunk func_73154_d(int x, int z) {
        long key = (long) x << 32 | (z & 0xFFFFFFFFL);
        Chunk chunk = chunks.get(key);
        if (chunk == null) {
            chunk = new BridgeChunk(world, x, z);
            chunks.put(key, chunk);
        }
        return chunk;
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
