package retrofit.forge.world;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraftforge.network.NetworkHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import retrofit.api.HostPlayer;
import retrofit.forge.RetrofitMod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Host side of the legacy player mirrors (see the legacy bridge's LegacyPlayers). Before legacy
 * code runs on a player's behalf, {@link #mirror} copies the player into the mirror; afterwards
 * {@link #writeBack} applies what legacy code changed (inventory slots, food, health).
 *
 * Positions of all players are copied every server tick into worlds that have legacy content,
 * so tile entities that look for nearby players find them. Clients only mirror the local player
 * and never write back: the server's results reach them through 1.20.1's own syncing.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID)
public final class LegacyPlayers {
    private static final int SNEAKING = 1;
    private static final int CREATIVE = 2;
    private static final int FLYING = 4;
    private static final int MAY_FLY = 8;
    private static final int ON_GROUND = 16;
    private static final int SPRINTING = 32;
    private static final int SLOTS = 40;

    /** One per player and side; the Player object changes on respawn, so it's updated on each sync. */
    private static final Map<UUID, Host> SERVER_HOSTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Host> CLIENT_HOSTS = new ConcurrentHashMap<>();

    private LegacyPlayers() {
    }

    /** The legacy mirror of a player in its level's legacy world, with its state (and optionally inventory) copied in. */
    public static Object mirror(Player player, boolean withInventory) {
        Level level = player.level();
        Object world = LegacyContent.legacyWorld(level);
        Host host = (level.isClientSide ? CLIENT_HOSTS : SERVER_HOSTS).computeIfAbsent(player.getUUID(), id -> new Host());
        host.player = player;
        Object mirror = LegacyBridge.call("player", world, host, player.getUUID().toString(), player.getGameProfile().getName());
        sync(player, mirror);
        if (withInventory) {
            Inventory inventory = player.getInventory();
            Object[] stacks = new Object[SLOTS];
            for (int i = 0; i < SLOTS; i++) {
                stacks[i] = LegacyStacks.toLegacy(inventory.getItem(i));
            }
            LegacyBridge.call("setInventory", mirror, stacks);
        }
        if (level.isClientSide) {
            LegacyBridge.call("makeCurrent", mirror);
        }
        return mirror;
    }

    private static void sync(Player player, Object mirror) {
        int flags = (player.isShiftKeyDown() ? SNEAKING : 0) | (player.getAbilities().instabuild ? CREATIVE : 0)
                | (player.getAbilities().flying ? FLYING : 0) | (player.getAbilities().mayfly ? MAY_FLY : 0)
                | (player.onGround() ? ON_GROUND : 0) | (player.isSprinting() ? SPRINTING : 0);
        LegacyBridge.call("sync", mirror, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(), flags,
                player.getInventory().selected, player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel(),
                player.getHealth());
    }

    /** Applies what legacy code changed in the mirror (server only; clients wait for the server). */
    public static void writeBack(Player player, Object mirror) {
        if (!player.level().isClientSide) {
            applyChanges(player, mirror, false);
        }
    }

    /**
     * After a container click: on both sides (the client's result is its prediction), and without
     * the creative rule (moving stacks around a GUI never "uses them up").
     */
    public static void writeBackContainer(Player player, Object mirror) {
        applyChanges(player, mirror, true);
    }

    private static void applyChanges(Player player, Object mirror, boolean container) {
        Object[] changes = LegacyBridge.call("inventoryChanges", mirror);
        if (changes != null) {
            int[] slots = (int[]) changes[0];
            Object[] stacks = (Object[]) changes[1];
            Inventory inventory = player.getInventory();
            for (int i = 0; i < slots.length; i++) {
                int slot = slots[i];
                ItemStack stack = LegacyStacks.toModern(stacks[i]);
                ItemStack current = inventory.getItem(slot);
                if (!container && player.getAbilities().instabuild && slot == inventory.selected && usedUp(current, stack)) {
                    continue; // 1.7.10 restored creative players' stacks after use; 1.20.1 never takes them
                }
                if (!current.isEmpty() && LegacyStacks.toLegacy(current) == null) {
                    // Legacy code saw this slot as empty (it holds a 1.20.1-only item): keep it, give the legacy stack elsewhere.
                    if (!stack.isEmpty() && !player.level().isClientSide && !inventory.add(stack)) {
                        player.drop(stack, false);
                    }
                    continue;
                }
                inventory.setItem(slot, stack);
            }
        }
        float[] stats = LegacyBridge.call("changedStats", mirror);
        if (stats != null) {
            player.getFoodData().setFoodLevel((int) stats[0]);
            player.getFoodData().setSaturation(stats[1]);
            if (stats[2] != player.getHealth()) {
                player.setHealth(stats[2]);
            }
        }
    }

    private static boolean usedUp(ItemStack before, ItemStack after) {
        return after.isEmpty() || ItemStack.isSameItemSameTags(before, after) && after.getCount() < before.getCount();
    }

    /** {using 1/0, use count left} of the mirror after a legacy call. */
    static int[] useState(Object mirror) {
        return LegacyBridge.call("useState", mirror);
    }

    // ---- Keeping mirrors current ----

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        Player player = event.player;
        if (event.phase != TickEvent.Phase.END || player.level().isClientSide || RetrofitMod.runtime() == null
                || player.tickCount % 2 != 0 || !LegacyContent.hasLegacyWorld(player.level())) {
            return;
        }
        Host host = SERVER_HOSTS.computeIfAbsent(player.getUUID(), id -> new Host());
        host.player = player;
        Object world = LegacyContent.legacyWorld(player.level());
        sync(player, LegacyBridge.call("player", world, host, player.getUUID().toString(), player.getGameProfile().getName()));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (RetrofitMod.runtime() != null) {
            SERVER_HOSTS.remove(event.getEntity().getUUID());
            LegacyBridge.call("removePlayer", event.getEntity().getUUID().toString(), null);
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (RetrofitMod.runtime() != null) {
            LegacyBridge.call("removePlayer", event.getEntity().getUUID().toString(), null);
        }
    }

    /** What the legacy mirror asks of the real player. */
    private static final class Host implements HostPlayer {
        volatile Player player;

        @Override
        public void sendMessage(String json) {
            Player target = player;
            if (target == null) {
                return;
            }
            Component message;
            try {
                message = Component.Serializer.fromJson(json);
            } catch (RuntimeException e) {
                message = Component.literal(json);
            }
            if (message != null) {
                target.displayClientMessage(message, false);
            }
        }

        @Override
        public void teleport(double x, double y, double z, float yaw, float pitch) {
            Player target = player;
            if (target instanceof ServerPlayer server) {
                server.connection.teleport(x, y, z, yaw, pitch);
            } else if (target != null) {
                target.moveTo(x, y, z, yaw, pitch);
            }
        }

        @Override
        public int openMenu(Object legacyContainer, String modId, int guiId, int x, int y, int z) {
            if (!(player instanceof ServerPlayer server)) {
                return -1;
            }
            NetworkHooks.openScreen(server, new SimpleMenuProvider((id, inventory, viewer) -> LegacyMenu.server(id, viewer, legacyContainer),
                    Component.empty()), data -> {
                data.writeUtf(modId);
                data.writeVarInt(guiId);
                data.writeBlockPos(new BlockPos(x, y, z));
            });
            return server.containerMenu instanceof LegacyMenu menu && menu.legacyContainer() == legacyContainer ? menu.containerId : -1;
        }

        @Override
        public void windowProperty(int windowId, int property, int value) {
            if (player instanceof ServerPlayer server) {
                LegacyNetwork.sendWindowProperty(server, windowId, property, value);
            }
        }

        @Override
        public void sendPayload(String channel, byte[] data) {
            if (player instanceof ServerPlayer server) {
                LegacyNetwork.sendTo(server, channel, data);
            }
        }

        @Override
        public void closeMenu() {
            Player target = player;
            if (target instanceof ServerPlayer server) {
                server.closeContainer();
            } else if (target != null) {
                retrofit.forge.client.ClientHooks.closeLegacyScreen();
            }
        }

        @Override
        public void displayScreen(Object legacyScreen) {
            retrofit.forge.client.ClientHooks.displayLegacyScreen(legacyScreen);
        }

        @Override
        public void clickSlot(int windowId, int slotId, int button, int mode) {
            retrofit.forge.client.ClientHooks.clickLegacySlot(windowId, slotId, button, mode);
        }
    }
}
