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

    /**
     * EntityList.getEntityString names a stand-in as its 1.7.10 class ("Creeper"): mods that save a
     * copy of a creature by name (Pistronics' statues) save the vanilla one. Only lookups answer for
     * stand-ins; the map's entries, which list the registered entities, stay as they are.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void nameStandIns() {
        try {
            java.lang.reflect.Field field = net.minecraft.entity.EntityList.class.getDeclaredField("field_75626_c"); // classToStringMapping
            Map names = (Map) field.get(null);
            if (!(names instanceof StandInNames)) {
                retrofit.legacy.compat.FinalFields.set(field, null, new StandInNames(names));
            }
        } catch (Exception e) {
            System.out.println("[Retrofit] Stand-ins for 1.20.1 creatures will have no entity names: " + e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class StandInNames extends HashMap {
        StandInNames(Map names) {
            super(names);
        }

        @Override
        public Object get(Object key) {
            Object name = super.get(key);
            if (name == null && key instanceof Class && HostCreatureProxies.Proxy.class.isAssignableFrom((Class<?>) key)) {
                for (Class<?> type = ((Class<?>) key).getSuperclass(); type != null && name == null; type = type.getSuperclass()) {
                    name = super.get(type);
                }
            }
            return name;
        }
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
        for (Object[] row : rows) {
            Entity proxy = standIn(world, host, row, false);
            if (proxy == null) {
                continue;
            }
            result.add(proxy);
        }
        return result;
    }

    /**
     * The stand-in for one row ({handle, type, category, state, stack}), made or reused and set to
     * where its 1.20.1 entity now is; null if the type has no stand-in class. Quiet rows (a hurt
     * naming its attacker) skip the log: projectiles and fireballs have none, and that is normal.
     */
    private static Entity standIn(World world, HostWorld host, Object[] row, boolean quiet) {
        if (row == null || row.length < 4) {
            return null;
        }
        Map<Object, Entity> byHandle = BY_HANDLE.get(world);
        if (byHandle == null) {
            byHandle = new IdentityHashMap<Object, Entity>();
            BY_HANDLE.put(world, byHandle);
            BY_ID.put(world, new HashMap<Integer, Entity>());
        }
        Object handle = row[0];
        String type = (String) row[1];
        Entity proxy = byHandle.get(handle);
        if (proxy == null || !type.equals(core(proxy).type)) {
            try {
                proxy = HostCreatureProxies.create(type, (Integer) row[2], world);
            } catch (Throwable t) {
                if (!quiet) {
                    System.out.println("[Retrofit] No legacy stand-in for " + type + ": " + t);
                }
                return null;
            }
            HostCreatureProxies.Core core = core(proxy);
            core.host = host;
            core.handle = handle;
            core.type = type;
            byHandle.put(handle, proxy);
            BY_ID.get(world).put(proxy.func_145782_y(), proxy); // getEntityId
        }
        core(proxy).sync((double[]) row[3], row[4]);
        return proxy;
    }

    /**
     * The stand-in of one 1.20.1 creature (a row of hostEntities' shape), made if legacy code hasn't seen
     * it yet, in a legacy world: a creature that hurts a legacy one is its attacker (1.7.10 damage named
     * its attacker, and mods act on the name: The Titans' titans refuse attacker-less damage outright,
     * take multiplied damage from bosses - a 1.20.1 wither must arrive as a 1.7.10 EntityWither for the
     * x100 rule - and remember it as their revenge target), one that 1.20.1 code sets as a legacy mob's
     * target is that mob's target, and clicks on it reach legacy events and items. It follows its entity
     * every tick until the entity is gone, like the stand-ins box queries make (the same entry in the
     * same maps). Null if the type has no stand-in (projectiles, fireballs, TNT) or there is no host.
     */
    public static synchronized Object standIn(Object world, Object[] row) {
        World legacyWorld = (World) world;
        HostWorld host = legacyWorld == null || legacyWorld.field_72995_K ? null : WorldGlue.hostOf(legacyWorld); // isRemote
        return host == null ? null : standIn(legacyWorld, host, row, true);
    }

    /** PlayTest: the direct motion writes legacy blasts make, flushed through the ordinary tick-end path. */
    public static void testBlastMotion(Object world, Object[] row, double x, double y, double z) {
        Entity proxy = (Entity) standIn(world, row);
        proxy.field_70159_w += x;
        proxy.field_70181_x += y;
        proxy.field_70179_y += z;
        tickEnd();
        tickEnd(); // a second sync must not apply the same push twice
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
                try {
                    // a motion write by legacy code (a blast's knockback) goes host-ward first: the state
                    // read below would overwrite it with the host's own motion, and the blast would fling
                    // nothing but the stand-in's shadow
                    core.flushForeignMotion();
                } catch (RuntimeException e) {
                    // a push the host can't take: sync will overwrite it, as before
                }
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
