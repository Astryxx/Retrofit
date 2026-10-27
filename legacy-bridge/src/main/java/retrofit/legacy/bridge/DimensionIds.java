package retrofit.legacy.bridge;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.Packet;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.world.Teleporter;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dimension ids and provider types two mods both asked for. The mod registering a taken one gets a free one
 * (from 1000 up, so as not to take the id a mod loaded later asks for; the same one at every start, kept in
 * retrofit/dimension-ids.txt), and that mod's classes see every id through its view: the id it asked for and
 * the one it got trade places (the rewrite of its classes is retrofit.legacy.boot.DimensionViews). A mod
 * registering its own id again (many re-register on every server start) keeps it.
 *
 * The converters take the name of the class asking, which names its mod (ModOwners). With no id moved they
 * return what they get.
 */
public final class DimensionIds {
    /** mod -> asked id -> id it got; dimension ids, then provider types. */
    private static final Map<String, Map<Integer, Integer>> DIMENSIONS = new ConcurrentHashMap<String, Map<Integer, Integer>>();
    private static final Map<String, Map<Integer, Integer>> TYPES = new ConcurrentHashMap<String, Map<Integer, Integer>>();
    /** Whether any id was moved: until then every view is the real ids. */
    private static volatile boolean moved;
    /** Real id -> the mod that registered it ("minecraft" for vanilla's), dimensions and provider types. */
    private static final Map<Integer, String> DIMENSION_OWNERS = new HashMap<Integer, String>();
    private static final Map<Integer, String> TYPE_OWNERS = new HashMap<Integer, String>();
    /** Choices of earlier starts: "dim mod asked" or "type mod asked" -> id. */
    private static Map<String, Integer> saved;

    private static final int FIRST_MOVED = 1000;

    static {
        for (int vanilla : new int[]{0, -1, 1}) {
            DIMENSION_OWNERS.put(vanilla, "minecraft");
            TYPE_OWNERS.put(vanilla, "minecraft");
        }
    }

    private DimensionIds() {
    }

    // ---- Converters (called from mods' rewritten classes) ----

    /** A real dimension id as the asking class's mod sees it. */
    public static int view(int real, String owner) {
        return moved ? swap(DIMENSIONS, real, owner) : real;
    }

    /** A dimension id the asking class's mod named, as the game knows it. */
    public static int real(int view, String owner) {
        return moved ? swap(DIMENSIONS, view, owner) : view;
    }

    public static int viewType(int real, String owner) {
        return moved ? swap(TYPES, real, owner) : real;
    }

    public static int realType(int view, String owner) {
        return moved ? swap(TYPES, view, owner) : view;
    }

    public static Integer[] viewIds(Integer[] reals, String owner) {
        if (!moved || reals == null) {
            return reals;
        }
        Integer[] views = new Integer[reals.length];
        for (int i = 0; i < reals.length; i++) {
            views[i] = reals[i] == null ? null : swap(DIMENSIONS, reals[i], owner);
        }
        return views;
    }

    /** The asked and the given id trade places (the same both ways). */
    private static int swap(Map<String, Map<Integer, Integer>> table, int id, String owner) {
        String mod = ModOwners.of(owner);
        Map<Integer, Integer> mine = mod == null ? null : table.get(mod);
        if (mine == null) {
            return id;
        }
        for (Map.Entry<Integer, Integer> entry : mine.entrySet()) {
            if (entry.getKey() == id) {
                return entry.getValue();
            }
            if (entry.getValue() == id) {
                return entry.getKey();
            }
        }
        return id;
    }

    // ---- Registration ----

    /** DimensionManager.registerDimension from a mod's class. */
    public static synchronized void registerDimension(int asked, int type, String owner) {
        String mod = ModOwners.of(owner);
        int realType = realType(type, owner);
        Integer earlier = mod == null ? null : given(DIMENSIONS, mod, asked);
        int id = earlier != null ? earlier : asked;
        if (earlier == null && mod != null && DimensionManager.isDimensionRegistered(asked) && !mod.equals(DIMENSION_OWNERS.get(asked))) {
            id = choose("dim", mod, asked);
            give(DIMENSIONS, mod, asked, id);
            System.out.println("[Retrofit] Dimension id " + asked + " of " + mod + " is " + DIMENSION_OWNERS.get(asked)
                    + "'s already; " + mod + " gets " + id + " (and still sees " + asked + ")");
        }
        DimensionManager.registerDimension(id, realType);
        if (!DIMENSION_OWNERS.containsKey(id) && mod != null) {
            DIMENSION_OWNERS.put(id, mod);
        }
    }

    /** DimensionManager.registerProviderType from a mod's class. */
    @SuppressWarnings("unchecked")
    public static synchronized boolean registerProviderType(int asked, Class<?> provider, boolean keepLoaded, String owner) {
        String mod = ModOwners.of(owner);
        Integer earlier = mod == null ? null : given(TYPES, mod, asked);
        int id = earlier != null ? earlier : asked;
        if (earlier == null && mod != null && providerRegistered(asked) && !mod.equals(TYPE_OWNERS.get(asked))) {
            id = chooseType(mod, asked);
            give(TYPES, mod, asked, id);
            System.out.println("[Retrofit] Dimension provider type " + asked + " of " + mod + " is " + TYPE_OWNERS.get(asked)
                    + "'s already; " + mod + " gets " + id + " (and still sees " + asked + ")");
        }
        boolean registered = DimensionManager.registerProviderType(id, (Class<? extends WorldProvider>) provider, keepLoaded);
        if (registered && mod != null) {
            TYPE_OWNERS.put(id, mod);
        }
        return registered;
    }

    private static Integer given(Map<String, Map<Integer, Integer>> table, String mod, int asked) {
        Map<Integer, Integer> mine = table.get(mod);
        return mine == null ? null : mine.get(asked);
    }

    private static void give(Map<String, Map<Integer, Integer>> table, String mod, int asked, int id) {
        Map<Integer, Integer> mine = table.get(mod);
        if (mine == null) {
            mine = new ConcurrentHashMap<Integer, Integer>();
            table.put(mod, mine);
        }
        mine.put(asked, id);
        moved = true;
    }

    private static boolean providerRegistered(int type) {
        try {
            java.lang.reflect.Field providers = DimensionManager.class.getDeclaredField("providers");
            providers.setAccessible(true);
            return ((Map<?, ?>) providers.get(null)).containsKey(type);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** A free dimension id for a mod's asked one: the one it had last time, if still free, else the first free from 1000. */
    private static int choose(String kind, String mod, int asked) {
        Integer last = saved().get(kind + " " + mod + " " + asked);
        if (last != null && !DimensionManager.isDimensionRegistered(last)) {
            return last;
        }
        int id = FIRST_MOVED;
        while (DimensionManager.isDimensionRegistered(id) || saved().containsValue(id) && !Integer.valueOf(id).equals(last)) {
            id++;
        }
        remember(kind + " " + mod + " " + asked, id);
        return id;
    }

    private static int chooseType(String mod, int asked) {
        String key = "type " + mod + " " + asked;
        Integer last = saved().get(key);
        if (last != null && !providerRegistered(last)) {
            return last;
        }
        int id = FIRST_MOVED;
        while (providerRegistered(id) || savedTypes().contains(id) && !Integer.valueOf(id).equals(last)) {
            id++;
        }
        remember(key, id);
        return id;
    }

    private static List<Integer> savedTypes() {
        List<Integer> types = new ArrayList<Integer>();
        for (Map.Entry<String, Integer> entry : saved().entrySet()) {
            if (entry.getKey().startsWith("type ")) {
                types.add(entry.getValue());
            }
        }
        return types;
    }

    private static File file() {
        File config = cpw.mods.fml.common.Loader.instance().getConfigDir();
        return new File(config.getParentFile(), "dimension-ids.txt");
    }

    private static Map<String, Integer> saved() {
        if (saved == null) {
            saved = new HashMap<String, Integer>();
            File file = file();
            if (file.isFile()) {
                try {
                    for (String line : java.nio.file.Files.readAllLines(file.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                        String[] parts = line.trim().split(" ");
                        if (parts.length == 4 && !line.startsWith("#")) {
                            saved.put(parts[0] + " " + parts[1] + " " + parts[2], Integer.parseInt(parts[3]));
                        }
                    }
                } catch (Exception e) {
                    System.out.println("[Retrofit] Could not read " + file + ": " + e);
                }
            }
        }
        return saved;
    }

    private static void remember(String key, int id) {
        saved().put(key, id);
        List<String> lines = new ArrayList<String>();
        lines.add("# Dimension ids and provider types Retrofit gave mods whose own were another mod's: kind, mod, asked, given");
        for (Map.Entry<String, Integer> entry : saved().entrySet()) {
            lines.add(entry.getKey() + " " + entry.getValue());
        }
        try {
            java.nio.file.Files.write(file().toPath(), lines, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[Retrofit] Could not write " + file() + ": " + e);
        }
    }

    // ---- Wrappers of calls whose dimension id isn't the last argument ----

    public static void setWorld(int id, WorldServer world, String owner) {
        DimensionManager.setWorld(real(id, owner), world);
    }

    public static void transferPlayerToDimension(ServerConfigurationManager players, EntityPlayerMP player, int id, Teleporter teleporter, String owner) {
        players.transferPlayerToDimension(player, real(id, owner), teleporter);
    }

    public static void transferEntityToWorld(ServerConfigurationManager players, Entity entity, int id, WorldServer from, WorldServer to, String owner) {
        players.func_82448_a(entity, real(id, owner), from, to);
    }

    public static void transferEntityToWorldWith(ServerConfigurationManager players, Entity entity, int id, WorldServer from, WorldServer to,
                                                 Teleporter teleporter, String owner) {
        players.transferEntityToWorld(entity, real(id, owner), from, to, teleporter);
    }

    public static void sendToAllNear(ServerConfigurationManager players, double x, double y, double z, double range, int id, Packet packet, String owner) {
        players.func_148541_a(x, y, z, range, real(id, owner), packet);
    }

    public static void sendToAllNearExcept(ServerConfigurationManager players, EntityPlayer except, double x, double y, double z, double range, int id,
                                           Packet packet, String owner) {
        players.func_148543_a(except, x, y, z, range, real(id, owner), packet);
    }

    // ---- Objects made with an id first (fixed right after) ----

    public static void fixTargetPoint(Object point, String owner) {
        fix(point, cpw.mods.fml.common.network.NetworkRegistry.TargetPoint.class, "dimension", owner);
    }

    public static void fixRespawnPacket(Object packet, String owner) {
        fix(packet, net.minecraft.network.play.server.S07PacketRespawn.class, "field_149088_a", owner); // dimensionID
    }

    private static void fix(Object made, Class<?> declaring, String fieldName, String owner) {
        if (!moved || made == null) {
            return;
        }
        try {
            java.lang.reflect.Field field = declaring.getDeclaredField(fieldName); // (mods may make subclasses)
            field.setAccessible(true);
            int id = field.getInt(made);
            int real = real(id, owner);
            if (real != id) {
                retrofit.legacy.compat.FinalFields.set(field, made, real);
            }
        } catch (Exception e) {
            // left as the mod made it
        }
    }
}
