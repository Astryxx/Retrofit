package retrofit.legacy.bridge;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityMobSpawner;
import net.minecraft.world.World;
import retrofit.api.HostWorld;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 1.20.1 mob spawners as legacy code sees them: TileEntityMobSpawners naming the mob they spawn
 * (OreSpawn's dungeon mobs check the spawner next to them; mods' spawner tools read it). Never
 * ticked: the host's spawner spawns.
 */
final class HostSpawner extends TileEntityMobSpawner {
    private static final Map<World, Map<Long, HostSpawner>> SPAWNERS = new WeakHashMap<World, Map<Long, HostSpawner>>();

    private HostSpawner() {
    }

    /** updateEntity: the host's spawner does the spawning */
    @Override
    public void func_145845_h() {
    }

    static synchronized TileEntity at(World world, HostWorld host, int x, int y, int z) {
        String mob = host.hostSpawnerEntity(x, y, z);
        long key = ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);
        Map<Long, HostSpawner> spawners = SPAWNERS.get(world);
        if (mob == null) {
            if (spawners != null) {
                spawners.remove(key);
            }
            return null;
        }
        if (spawners == null) {
            spawners = new HashMap<Long, HostSpawner>();
            SPAWNERS.put(world, spawners);
        }
        HostSpawner spawner = spawners.get(key);
        if (spawner == null) {
            spawner = new HostSpawner();
            spawner.func_145834_a(world); // setWorldObj
            spawner.field_145851_c = x;
            spawner.field_145848_d = y;
            spawner.field_145849_e = z;
            spawners.put(key, spawner);
        }
        if (!mob.equals(spawner.func_145881_a().func_98276_e())) { // getSpawnerBaseLogic().getEntityNameToSpawn()
            spawner.func_145881_a().func_98272_a(mob); // setEntityName
        }
        return spawner;
    }
}
