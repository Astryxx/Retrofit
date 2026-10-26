package retrofit.legacy.bridge;

import net.minecraft.entity.EnumCreatureType;
import net.minecraft.util.IProgressUpdate;
import net.minecraft.util.LongHashMap;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderServer;

import java.util.List;

/**
 * WorldServer.theChunkProviderServer of the bridged server worlds. Mods reach for it directly to load
 * and check chunks (Galacticraft loads the chunk a player arrives in through it), and on 1.7.10 every
 * server world had one; it answers as the world's own chunk provider does (see BridgeChunkProvider):
 * the host level owns the chunks, loads and unloads them. Its loadedChunkHashMap, which mods read
 * themselves (IC2 finds its machines' chunks there), has every chunk, as the world's chunk provider does.
 */
final class BridgeChunkProviderServer extends ChunkProviderServer {
    private final BridgeChunkProvider chunks;

    BridgeChunkProviderServer(WorldServer world, BridgeChunkProvider chunks) {
        super(world, null, chunks);
        this.chunks = chunks;
        this.field_73244_f = new LoadedChunks(chunks); // loadedChunkHashMap
    }

    /** loadedChunkHashMap: keyed as ChunkCoordIntPair.chunkXZ2Int (x low, z high); the host adds and drops chunks. */
    private static final class LoadedChunks extends LongHashMap {
        private final BridgeChunkProvider chunks;

        LoadedChunks(BridgeChunkProvider chunks) {
            this.chunks = chunks;
        }

        /** getValueByKey */
        @Override
        public Object func_76164_a(long key) {
            return chunks.func_73154_d((int) key, (int) (key >>> 32));
        }

        /** containsItem */
        @Override
        public boolean func_76161_b(long key) {
            return true;
        }

        /** add */
        @Override
        public void func_76163_a(long key, Object value) {
        }

        /** remove */
        @Override
        public Object func_76159_d(long key) {
            return null;
        }
    }

    /** chunkExists */
    @Override
    public boolean func_73149_a(int x, int z) {
        return chunks.func_73149_a(x, z);
    }

    /** dropChunk: the host unloads chunks */
    @Override
    public void func_73241_b(int x, int z) {
    }

    /** unloadAllChunks */
    @Override
    public void func_73240_a() {
    }

    /** loadChunk */
    @Override
    public Chunk func_73158_c(int x, int z) {
        return chunks.func_73158_c(x, z);
    }

    /** Forge's loadChunk with a callback for when it is loaded: here, at once. */
    @Override
    public Chunk loadChunk(int x, int z, Runnable loaded) {
        Chunk chunk = chunks.func_73158_c(x, z);
        if (loaded != null) {
            loaded.run();
        }
        return chunk;
    }

    @Override
    public Chunk originalLoadChunk(int x, int z) {
        return chunks.func_73158_c(x, z);
    }

    /** provideChunk */
    @Override
    public Chunk func_73154_d(int x, int z) {
        return chunks.func_73154_d(x, z);
    }

    /** populate */
    @Override
    public void func_73153_a(IChunkProvider provider, int x, int z) {
        chunks.func_73153_a(provider, x, z);
    }

    /** saveChunks */
    @Override
    public boolean func_73151_a(boolean all, IProgressUpdate progress) {
        return chunks.func_73151_a(all, progress);
    }

    /** saveExtraData */
    @Override
    public void func_104112_b() {
        chunks.func_104112_b();
    }

    /** unloadQueuedChunks */
    @Override
    public boolean func_73156_b() {
        return chunks.func_73156_b();
    }

    /** canSave */
    @Override
    public boolean func_73157_c() {
        return chunks.func_73157_c();
    }

    /** makeString */
    @Override
    public String func_73148_d() {
        return chunks.func_73148_d();
    }

    /** getPossibleCreatures */
    @Override
    public List func_73155_a(EnumCreatureType type, int x, int y, int z) {
        return chunks.func_73155_a(type, x, y, z);
    }

    /** findClosestStructure */
    @Override
    public ChunkPosition func_147416_a(World world, String name, int x, int y, int z) {
        return chunks.func_147416_a(world, name, x, y, z);
    }

    /** getLoadedChunkCount */
    @Override
    public int func_73152_e() {
        return chunks.func_73152_e();
    }

    /** recreateStructures */
    @Override
    public void func_82695_e(int x, int z) {
        chunks.func_82695_e(x, z);
    }
}
