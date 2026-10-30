package retrofit.legacy.bridge;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityMobSpawner;
import net.minecraft.world.World;
import retrofit.api.HostWorld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 1.20.1 mob spawners as legacy code sees them: TileEntityMobSpawners naming the mob they spawn
 * (OreSpawn's dungeon mobs check the spawner next to them; mods' spawner tools read it). Never
 * ticked: the host's spawner spawns. A mob legacy code names (setEntityName, as structure
 * builders do right after placing the spawner) goes to the host's spawner on the next access and
 * at the end of every server tick.
 */
final class HostSpawner extends TileEntityMobSpawner {
    static synchronized void forgetWorld(World world) {
        SPAWNERS.remove(world);
    }

    private static final Map<World, Map<Long, HostSpawner>> SPAWNERS = new WeakHashMap<World, Map<Long, HostSpawner>>();

    /** The mob the host's spawner had when last compared: a different one in the logic is legacy code's. */
    private String synced;

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
        } else if (spawner.push(host)) {
            return spawner;
        }
        if (!mob.equals(spawner.func_145881_a().func_98276_e())) { // getSpawnerBaseLogic().getEntityNameToSpawn()
            spawner.func_145881_a().func_98272_a(mob); // setEntityName
        }
        spawner.synced = mob;
        return spawner;
    }

    /** Legacy code named another mob since the last comparison: it goes to the host's spawner. */
    private boolean push(HostWorld host) {
        String named = func_145881_a().func_98276_e(); // getSpawnerBaseLogic().getEntityNameToSpawn()
        if (named == null || named.equals(synced)) {
            return false;
        }
        host.setHostSpawnerEntity(field_145851_c, field_145848_d, field_145849_e, named);
        synced = named;
        return true;
    }

    /** End of every server tick: mobs legacy code named reach the host's spawners. */
    static synchronized void flushAll() {
        for (Map.Entry<World, Map<Long, HostSpawner>> entry : new ArrayList<Map.Entry<World, Map<Long, HostSpawner>>>(SPAWNERS.entrySet())) {
            HostWorld host = WorldGlue.hostOf(entry.getKey());
            if (host != null) {
                for (HostSpawner spawner : entry.getValue().values()) {
                    spawner.push(host);
                }
            }
        }
    }
}
