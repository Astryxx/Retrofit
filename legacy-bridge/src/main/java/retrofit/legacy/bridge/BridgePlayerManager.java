package retrofit.legacy.bridge;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerManager;
import net.minecraft.world.WorldServer;

/**
 * WorldServer.getPlayerManager() for bridged worlds. The host tracks which players see which
 * chunks; mods ask this one (GregTech sends its machines' data only to players watching the
 * chunk; others mark blocks for update through it), so it answers from the players' positions
 * and the host's view distance, and passes block updates to the world.
 */
final class BridgePlayerManager extends PlayerManager {
    private final WorldServer world;

    BridgePlayerManager(WorldServer world) {
        super(world);
        this.world = world;
    }

    private int viewDistance() {
        retrofit.api.HostWorld host = WorldGlue.hostOf(world);
        return host == null ? 10 : host.viewDistance();
    }

    /** isPlayerWatchingChunk */
    @Override
    public boolean func_72694_a(EntityPlayerMP player, int chunkX, int chunkZ) {
        if (player == null || player.field_70170_p != world) { // worldObj
            return false;
        }
        int playerX = (int) Math.floor(player.field_70165_t) >> 4; // posX
        int playerZ = (int) Math.floor(player.field_70161_v) >> 4;
        int range = viewDistance();
        return Math.abs(playerX - chunkX) <= range && Math.abs(playerZ - chunkZ) <= range;
    }

    /** func_152621_a: whether any player watches a chunk */
    @Override
    public boolean func_152621_a(int chunkX, int chunkZ) {
        for (Object element : world.field_73010_i) { // playerEntities
            if (element instanceof EntityPlayerMP && func_72694_a((EntityPlayerMP) element, chunkX, chunkZ)) {
                return true;
            }
        }
        return false;
    }

    /** markBlockForUpdate: the host sends it to the players who see it */
    @Override
    public void func_151250_a(int x, int y, int z) {
        world.func_147471_g(x, y, z); // World.markBlockForUpdate (bridged)
    }

    /** updatePlayerInstances: the host does it */
    @Override
    public void func_72693_b() {
    }

    @Override
    public void func_72683_a(EntityPlayerMP player) { // addPlayer
    }

    @Override
    public void func_72695_c(EntityPlayerMP player) { // removePlayer
    }

    @Override
    public void func_72685_d(EntityPlayerMP player) { // updateMountedMovingPlayer
    }
}
