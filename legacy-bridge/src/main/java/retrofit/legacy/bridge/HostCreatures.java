package retrofit.legacy.bridge;

import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import retrofit.api.HostWorld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 1.20.1 entities in legacy worlds: legacy code that looks for entities (mob AI picking targets,
 * area spells, turrets, magnets, mob grinders) finds stand-ins for the host's own creatures,
 * items and experience orbs next to the legacy ones (see {@link HostCreatureProxies}).
 * Stand-ins are made on first sight, follow their entity every tick while anything may hold
 * them, and die with it.
 */
public final class HostCreatures {
    private static final Map<World, Map<Object, Entity>> BY_HANDLE = new WeakHashMap<World, Map<Object, Entity>>();
    private static final Map<World, Map<Integer, Entity>> BY_ID = new WeakHashMap<World, Map<Integer, Entity>>();

    private HostCreatures() {
    }

    private static HostCreatureProxies.Core core(Entity proxy) {
        return ((HostCreatureProxies.Proxy) proxy).core();
    }

    /** The host entities in a box, as stand-ins (servers only: client worlds have the host's own tracking). */
    static synchronized List<Entity> inBox(World world, AxisAlignedBB box) {
        List<Entity> result = new ArrayList<Entity>();
        HostWorld host = world.field_72995_K ? null : WorldGlue.hostOf(world); // isRemote
        if (host == null) {
            return result;
        }
        Object[][] rows = host.hostEntities(box.field_72340_a, box.field_72338_b, box.field_72339_c, box.field_72336_d, box.field_72337_e, box.field_72334_f);
        if (rows.length == 0) {
            return result;
        }
        Map<Object, Entity> byHandle = BY_HANDLE.get(world);
        if (byHandle == null) {
            byHandle = new IdentityHashMap<Object, Entity>();
            BY_HANDLE.put(world, byHandle);
            BY_ID.put(world, new HashMap<Integer, Entity>());
        }
        for (Object[] row : rows) {
            Object handle = row[0];
            String type = (String) row[1];
            Entity proxy = byHandle.get(handle);
            if (proxy == null || !type.equals(core(proxy).type)) {
                try {
                    proxy = HostCreatureProxies.create(type, (Integer) row[2], world);
                } catch (Throwable t) {
                    System.out.println("[Retrofit] No legacy stand-in for " + type + ": " + t);
                    continue;
                }
                HostCreatureProxies.Core core = core(proxy);
                core.host = host;
                core.handle = handle;
                core.type = type;
                byHandle.put(handle, proxy);
                BY_ID.get(world).put(proxy.func_145782_y(), proxy); // getEntityId
            }
            core(proxy).sync((double[]) row[3], row[4]);
            result.add(proxy);
        }
        return result;
    }

    /** getEntityByID for stand-ins (mods' packets and GUIs name entities by id). */
    static synchronized Entity byId(World world, int id) {
        Map<Integer, Entity> byId = BY_ID.get(world);
        return byId == null ? null : byId.get(id);
    }

    /** End of every server tick: stand-ins follow their entities; those whose entity is gone die (mods holding them see isDead). */
    static synchronized void tickEnd() {
        for (Map.Entry<World, Map<Object, Entity>> entry : BY_HANDLE.entrySet()) {
            Map<Integer, Entity> byId = BY_ID.get(entry.getKey());
            for (Entity proxy : new ArrayList<Entity>(entry.getValue().values())) {
                HostCreatureProxies.Core core = core(proxy);
                double[] state = core.removed || core.host == null ? null : core.host.hostEntityState(core.handle);
                if (state == null) {
                    core.removed = true;
                    proxy.field_70128_L = true; // isDead
                    entry.getValue().remove(core.handle);
                    if (byId != null) {
                        byId.remove(proxy.func_145782_y());
                    }
                } else {
                    try {
                        core.sync(state, null);
                    } catch (RuntimeException e) {
                        // a stand-in that can't follow: it stays where it was
                    }
                }
            }
        }
    }

    /** Self-test (the mod sweep): a cow and a zombie the host put at a position, as legacy code finds them. */
    @SuppressWarnings("rawtypes")
    public static String checkHostCreatures(Object legacyWorld, int x, int y, int z) {
        World world = (World) legacyWorld;
        AxisAlignedBB box = AxisAlignedBB.func_72330_a(x - 3, y - 1, z - 3, x + 4, y + 3, z + 4); // getBoundingBox
        List animals = world.func_72872_a(net.minecraft.entity.passive.EntityAnimal.class, box); // getEntitiesWithinAABB
        List monsters = world.func_72872_a(net.minecraft.entity.EntityLivingBase.class, box);
        net.minecraft.entity.passive.EntityCow cow = null;
        boolean zombie = false;
        for (Object entity : animals) {
            if (entity instanceof net.minecraft.entity.passive.EntityCow) {
                cow = (net.minecraft.entity.passive.EntityCow) entity;
            }
        }
        for (Object entity : monsters) {
            zombie |= entity instanceof net.minecraft.entity.monster.EntityZombie && entity instanceof net.minecraft.entity.monster.IMob;
        }
        if (cow == null || !zombie) {
            HostWorld host = WorldGlue.hostOf(world);
            Object[][] rows = host == null ? null : host.hostEntities(box.field_72340_a, box.field_72338_b, box.field_72339_c, box.field_72336_d, box.field_72337_e, box.field_72334_f);
            StringBuilder seen = new StringBuilder();
            if (rows != null) {
                for (Object[] row : rows) {
                    seen.append(row[1]).append(' ');
                }
            }
            return "FAILED: animals " + animals + ", living " + monsters + "; host " + (host == null ? "none" : host.getClass().getSimpleName())
                    + " sees " + (rows == null ? "nothing" : rows.length + " [" + seen.toString().trim() + "]") + " in " + box;
        }
        boolean hurt = cow.func_70097_a(net.minecraft.util.DamageSource.field_76377_j, 3); // attackEntityFrom(generic)
        return "ok: " + animals.size() + " animal(s) and " + monsters.size() + " living, the cow is a " + cow.getClass().getSimpleName()
                + " at " + (int) cow.field_70165_t + "," + (int) cow.field_70163_u + "," + (int) cow.field_70161_v + "; hurt " + hurt;
    }

    /** The host entity behind a stand-in or a player's mirror (opaque), or null: the host turns legacy attackers into its own. */
    public static Object hostHandle(Object legacyEntity) {
        if (legacyEntity instanceof HostCreatureProxies.Proxy) {
            return ((HostCreatureProxies.Proxy) legacyEntity).core().handle;
        }
        if (legacyEntity instanceof BridgePlayerMP) {
            return ((BridgePlayerMP) legacyEntity).host.hostEntity();
        }
        return null;
    }
}
