package retrofit.legacy.bridge;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.item.ItemStack;
import net.minecraft.util.FoodStats;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import retrofit.api.HostPlayer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Legacy mirrors of host players, one per player and legacy world: a real EntityPlayerMP on
 * servers ({@link BridgePlayerMP}), an EntityClientPlayerMP on clients ({@link BridgeClientPlayer}).
 * They are in the world's playerEntities and loadedEntityList and the server's player list, so
 * mods find players the usual ways.
 *
 * The host copies a player's state in before a legacy call (position, rotation, flags, food and
 * health; the inventory around interactions) and reads back what legacy code changed. Inventory
 * slots are compared with the stacks the host put in, so only slots legacy code really changed
 * are written back, and 1.20.1 stacks with no legacy form are never touched.
 */
public final class LegacyPlayers {
    public static final int SNEAKING = 1;
    public static final int CREATIVE = 2;
    public static final int FLYING = 4;
    public static final int MAY_FLY = 8;
    public static final int ON_GROUND = 16;
    public static final int SPRINTING = 32;

    private static final int SLOTS = 40; // 36 main, then 4 armour (feet first, as both versions order them)
    private static final Map<World, Map<String, EntityPlayer>> PLAYERS = new WeakHashMap<World, Map<String, EntityPlayer>>();
    private static final Map<EntityPlayer, ItemStack[]> GIVEN = new WeakHashMap<EntityPlayer, ItemStack[]>();
    private static final Map<EntityPlayer, float[]> GIVEN_STATS = new WeakHashMap<EntityPlayer, float[]>();
    private static Field foodLevel;
    private static Field foodSaturation;

    private LegacyPlayers() {
    }

    /** The mirror of a host player in a legacy world, created on first use. */
    @SuppressWarnings("unchecked")
    public static synchronized Object player(Object legacyWorld, HostPlayer host, String uuid, String name) {
        World world = (World) legacyWorld;
        Map<String, EntityPlayer> players = PLAYERS.get(world);
        if (players == null) {
            players = new HashMap<String, EntityPlayer>();
            PLAYERS.put(world, players);
        }
        EntityPlayer player = players.get(uuid);
        if (player == null) {
            if (world.field_72995_K) { // isRemote
                player = ClientPlayers.create(world, host, uuid, name);
            } else {
                GameProfile profile = new GameProfile(UUID.fromString(uuid), name);
                player = new BridgePlayerMP(LegacyWorlds.server(), (WorldServer) world, profile, host);
                LegacyWorlds.server().func_71203_ab().field_72404_b.add(player); // getConfigurationManager().playerEntityList
                EntityPlayer other = serverMirror(uuid, world);
                if (other != null) {
                    PlayerState.load(player, PlayerState.save(other)); // the player's mod state follows it between worlds
                } else {
                    PlayerState.restore(uuid, player);
                }
                PlayerState.handOver(other, player, uuid);
            }
            player.field_71093_bK = world.field_73011_w.field_76574_g; // dimension = provider.dimensionId: mods check where players are
            world.field_73010_i.add(player); // playerEntities
            world.field_72996_f.add(player); // loadedEntityList
            players.put(uuid, player);
            // As when a 1.7.10 player joined a world (login, dimension change): mods load and sync player data here.
            try {
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.entity.EntityJoinWorldEvent(player, world));
            } catch (Throwable t) {
                System.out.println("[Retrofit] An EntityJoinWorldEvent handler failed for player " + name + ": " + t);
            }
        }
        return player;
    }

    /** Removes a player's mirrors from every legacy world (logout, or leaving for another dimension). */
    public static synchronized void removePlayer(String uuid, Object keepWorld) {
        for (Map.Entry<World, Map<String, EntityPlayer>> entry : PLAYERS.entrySet()) {
            if (entry.getKey() == keepWorld) {
                continue;
            }
            EntityPlayer player = entry.getValue().remove(uuid);
            if (player != null && !entry.getKey().field_72995_K) {
                PlayerState.keep(uuid, player);
                EntityPlayer kept = keepWorld == null ? null : serverMirror(uuid, keepWorld);
                if (kept != null) {
                    PlayerState.load(kept, PlayerState.save(player));
                }
                PlayerState.handOver(player, kept, uuid);
            }
            if (player != null) {
                entry.getKey().field_73010_i.remove(player);
                entry.getKey().field_72996_f.remove(player);
                if (!entry.getKey().field_72995_K) {
                    LegacyWorlds.server().func_71203_ab().field_72404_b.remove(player);
                }
            }
        }
    }

    /** A server mirror of a player in another world than {@code except}, or null. */
    private static EntityPlayer serverMirror(String uuid, Object except) {
        for (Map.Entry<World, Map<String, EntityPlayer>> entry : PLAYERS.entrySet()) {
            if (entry.getKey() != except && !entry.getKey().field_72995_K && entry.getValue().containsKey(uuid)) {
                return entry.getValue().get(uuid);
            }
        }
        return null;
    }

    /** All server mirrors of a player. */
    static synchronized List<EntityPlayer> serverMirrors(String uuid) {
        List<EntityPlayer> mirrors = new ArrayList<EntityPlayer>();
        for (Map.Entry<World, Map<String, EntityPlayer>> entry : PLAYERS.entrySet()) {
            if (!entry.getKey().field_72995_K && entry.getValue().containsKey(uuid)) {
                mirrors.add(entry.getValue().get(uuid));
            }
        }
        return mirrors;
    }

    /** Copies the host player's position (feet), rotation, motion, fall distance, flags, held slot, food and health in. */
    public static void sync(Object legacyPlayer, double x, double y, double z, float yaw, float pitch, double motionX, double motionY,
                            double motionZ, float fallDistance, int flags, int slot, int food, float saturation, float health) {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        player.field_70159_w = motionX; // motionX
        player.field_70181_x = motionY;
        player.field_70179_y = motionZ;
        player.field_70143_R = fallDistance; // fallDistance
        player.field_70133_I = false; // velocityChanged
        GIVEN_MOTION.put(player, new double[]{motionX, motionY, motionZ, fallDistance});
        double legacyY = y + player.field_70129_M; // yOffset: client players stand at eye height
        player.func_70080_a(x, legacyY, z, yaw, pitch); // setPositionAndRotation
        player.field_70169_q = player.field_70142_S = x;  // prevPosX, lastTickPosX
        player.field_70167_r = player.field_70137_T = legacyY;
        player.field_70166_s = player.field_70136_U = z;
        player.field_70759_as = player.field_70758_at = yaw; // rotationYawHead, prevRotationYawHead
        player.field_70126_B = yaw;
        player.field_70127_C = pitch;
        player.field_70122_E = (flags & ON_GROUND) != 0; // onGround
        player.func_70095_a((flags & SNEAKING) != 0);   // setSneaking
        if (player.field_70170_p.field_72995_K) { // client world: only then may the client-only class load
            ClientPlayers.setSneaking(player, (flags & SNEAKING) != 0);
        }
        player.func_70031_b((flags & SPRINTING) != 0);  // setSprinting
        PlayerCapabilities capabilities = player.field_71075_bZ;
        capabilities.field_75098_d = (flags & CREATIVE) != 0; // isCreativeMode
        capabilities.field_75102_a = capabilities.field_75098_d; // disableDamage
        capabilities.field_75101_c = (flags & MAY_FLY) != 0; // allowFlying
        capabilities.field_75100_b = (flags & FLYING) != 0;  // isFlying
        if (slot >= 0 && slot < 9) {
            player.field_71071_by.field_70461_c = slot; // inventory.currentItem
        }
        if (player.func_110143_aJ() != health) { // getHealth
            player.func_70606_j(health); // setHealth
        }
        setFood(player.func_71024_bL(), food, saturation);
        GIVEN_STATS.put(player, new float[]{food, saturation, health});
        GIVEN_ABILITIES.put(player, flags & (MAY_FLY | FLYING));
    }

    private static final Map<EntityPlayer, Integer> GIVEN_ABILITIES = new WeakHashMap<EntityPlayer, Integer>();
    private static final Map<EntityPlayer, double[]> GIVEN_MOTION = new WeakHashMap<EntityPlayer, double[]>();

    /**
     * {motionX, motionY, motionZ, fallDistance, velocityChanged (0/1)} if legacy code changed the
     * player's motion or fall distance since {@link #sync} (jetpacks, flying armor, leaps, knockback,
     * boots that soften falls), else null.
     */
    public static double[] changedMotion(Object legacyPlayer) {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        double[] given = GIVEN_MOTION.get(player);
        double[] now = {player.field_70159_w, player.field_70181_x, player.field_70179_y, player.field_70143_R, player.field_70133_I ? 1 : 0};
        if (given == null || given[0] == now[0] && given[1] == now[1] && given[2] == now[2] && (float) given[3] == (float) now[3] && now[4] == 0) {
            return null;
        }
        GIVEN_MOTION.put(player, new double[]{now[0], now[1], now[2], now[3]});
        player.field_70133_I = false;
        return now;
    }

    /** MAY_FLY and FLYING if legacy code changed them since {@link #sync} (flight buffs, brooms), else -1. */
    public static int changedAbilities(Object legacyPlayer) {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        Integer given = GIVEN_ABILITIES.get(player);
        PlayerCapabilities capabilities = player.field_71075_bZ;
        int now = (capabilities.field_75101_c ? MAY_FLY : 0) | (capabilities.field_75100_b ? FLYING : 0); // allowFlying, isFlying
        if (given == null || given == now) {
            return -1;
        }
        GIVEN_ABILITIES.put(player, now);
        return now;
    }

    /** {food, saturation, health} if legacy code changed any of them since {@link #sync}, else null. */
    public static float[] changedStats(Object legacyPlayer) {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        float[] given = GIVEN_STATS.get(player);
        FoodStats food = player.func_71024_bL();
        float[] now = {food.func_75116_a(), food.func_75115_e(), player.func_110143_aJ()};
        if (given != null && given[0] == now[0] && given[1] == now[1] && given[2] == now[2]) {
            return null;
        }
        return now;
    }

    /** Puts the host inventory (40 legacy stacks or nulls: 36 main, 4 armour) into the mirror. */
    public static void setInventory(Object legacyPlayer, Object[] stacks) {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        InventoryPlayer inventory = player.field_71071_by;
        ItemStack[] given = new ItemStack[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = ItemStack.func_77944_b((ItemStack) stacks[i]); // a copy: the host reuses the stacks it converted
            if (i < 36) {
                inventory.field_70462_a[i] = stack; // mainInventory
            } else {
                inventory.field_70460_b[i - 36] = stack; // armorInventory
            }
            given[i] = ItemStack.func_77944_b(stack); // copyItemStack
        }
        GIVEN.put(player, given);
    }

    /** {int[] slots, Object[] stacks} that legacy code changed since {@link #setInventory}, or null. */
    public static Object[] inventoryChanges(Object legacyPlayer) {
        if (legacyPlayer instanceof BridgePlayerMP) {
            Guis.adoptOpenContainer((BridgePlayerMP) legacyPlayer); // after legacy code ran for the player
        }
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        ItemStack[] given = GIVEN.get(player);
        if (given == null) {
            return null;
        }
        InventoryPlayer inventory = player.field_71071_by;
        List<Integer> slots = new ArrayList<Integer>();
        List<Object> stacks = new ArrayList<Object>();
        for (int i = 0; i < SLOTS; i++) {
            ItemStack now = i < 36 ? inventory.field_70462_a[i] : inventory.field_70460_b[i - 36];
            if (now != null && now.field_77994_a <= 0) {
                now = null; // 1.7.10 code often leaves used-up stacks with a size of 0
            }
            if (!ItemStack.func_77989_b(now, given[i])) { // areItemStacksEqual
                slots.add(i);
                stacks.add(now);
                given[i] = ItemStack.func_77944_b(now);
            }
        }
        if (slots.isEmpty()) {
            return null;
        }
        int[] changed = new int[slots.size()];
        for (int i = 0; i < changed.length; i++) {
            changed[i] = slots.get(i);
        }
        return new Object[]{changed, stacks.toArray()};
    }

    private static Field itemInUseCount;

    /** {using an item 1/0, use count left}. getItemInUseCount is client-only in 1.7.10, so the field is read. */
    public static int[] useState(Object legacyPlayer) throws ReflectiveOperationException {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        if (itemInUseCount == null) {
            Field field = EntityPlayer.class.getDeclaredField("field_71072_f"); // itemInUseCount
            field.setAccessible(true);
            itemInUseCount = field;
        }
        return new int[]{player.func_71039_bw() ? 1 : 0, itemInUseCount.getInt(player)}; // isUsingItem
    }

    /** Client only: Minecraft.thePlayer and theWorld become this mirror and its world. */
    public static void makeCurrent(Object legacyPlayer) {
        ClientPlayers.makeCurrent((EntityPlayer) legacyPlayer);
    }

    private static void setFood(FoodStats stats, int food, float saturation) {
        try {
            if (foodLevel == null) {
                foodLevel = FoodStats.class.getDeclaredField("field_75127_a");
                foodLevel.setAccessible(true);
                foodSaturation = FoodStats.class.getDeclaredField("field_75125_b");
                foodSaturation.setAccessible(true);
            }
            foodLevel.setInt(stats, food);
            foodSaturation.setFloat(stats, saturation);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
