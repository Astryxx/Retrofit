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
import retrofit.api.HostPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Host side of the legacy player mirrors (see the legacy bridge's LegacyPlayers). Before legacy
 * code runs on a player's behalf, {@link #mirror} copies the player into the mirror; afterwards
 * {@link #writeBack} applies what legacy code changed (inventory slots, food, health).
 *
 * Every player is mirrored and synced each tick (see LegacyEvents, which also fires the legacy
 * player tick events), so tile entities that look for nearby players find them. Clients only
 * mirror the local player and never write back outside GUIs: the server's results reach them
 * through 1.20.1's own syncing.
 */
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
        LegacyBridge.call("setHostEffects", mirror, (Object) vanillaEffects(player));
    }

    /** The player's vanilla effects (ids 1-23, the same in 1.7.10) as {id, duration, amplifier, ambient} quadruples. */
    private static int[] vanillaEffects(Player player) {
        java.util.Collection<net.minecraft.world.effect.MobEffectInstance> active = player.getActiveEffects();
        if (active.isEmpty()) {
            return new int[0];
        }
        int[] effects = new int[active.size() * 4];
        int n = 0;
        for (net.minecraft.world.effect.MobEffectInstance effect : active) {
            int id = net.minecraft.world.effect.MobEffect.getId(effect.getEffect());
            if (id >= 1 && id <= 23) {
                effects[n++] = id;
                effects[n++] = effect.getDuration();
                effects[n++] = effect.getAmplifier();
                effects[n++] = effect.isAmbient() ? 1 : 0;
            }
        }
        return java.util.Arrays.copyOf(effects, n);
    }

    /** Vanilla effects legacy code gave the mirror (food, brews, wands...) go to the player. */
    private static void applyNewEffects(Player player, Object mirror) {
        int[] effects = LegacyBridge.call("takeNewEffects", mirror);
        for (int i = 0; i + 3 < effects.length; i += 4) {
            net.minecraft.world.effect.MobEffect effect = net.minecraft.world.effect.MobEffect.byId(effects[i]);
            if (effect != null) {
                player.addEffect(new net.minecraft.world.effect.MobEffectInstance(effect, effects[i + 1], effects[i + 2], effects[i + 3] != 0, true));
            }
        }
    }

    /** Mod potion effects (on the mirror) last shown on each player, so a host-side removal (milk) reaches the mirror. */
    private static final Map<UUID, java.util.Set<Integer>> SHOWN_MOD_EFFECTS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Every server tick: the mirror's mod potions tick and its LivingUpdateEvent runs; vanilla effects
     * legacy code added go to the player; mod potions show on the player (as display-only effects).
     */
    public static void tickServer(ServerPlayer player) {
        Object mirror = mirror(player, false);
        LegacyBridge.call("tickServerPlayer", mirror);
        applyNewEffects(player, mirror);
        int[] modEffects = LegacyBridge.call("modEffects", mirror);
        java.util.Set<Integer> shown = SHOWN_MOD_EFFECTS.computeIfAbsent(player.getUUID(), id -> new java.util.HashSet<>());
        java.util.Set<Integer> now = new java.util.HashSet<>();
        for (int i = 0; i + 2 < modEffects.length; i += 3) {
            now.add(modEffects[i]);
        }
        for (Integer id : new java.util.ArrayList<>(shown)) {
            net.minecraft.world.effect.MobEffect effect = LegacyMobEffects.byLegacyId(id);
            if (now.contains(id) && effect != null && !player.hasEffect(effect)) {
                LegacyBridge.call("removeModEffect", mirror, id); // removed on the host (milk, /effect clear)
                now.remove(id);
            }
        }
        for (int i = 0; i + 2 < modEffects.length; i += 3) {
            net.minecraft.world.effect.MobEffect effect = LegacyMobEffects.byLegacyId(modEffects[i]);
            if (effect == null || !now.contains(modEffects[i])) {
                continue;
            }
            net.minecraft.world.effect.MobEffectInstance current = player.getEffect(effect);
            if (current == null || current.getAmplifier() != modEffects[i + 2] || Math.abs(current.getDuration() - modEffects[i + 1]) > 20) {
                player.forceAddEffect(new net.minecraft.world.effect.MobEffectInstance(effect, modEffects[i + 1], modEffects[i + 2], false, true, true), null);
            }
        }
        for (Integer id : shown) {
            net.minecraft.world.effect.MobEffect effect = LegacyMobEffects.byLegacyId(id);
            if (!now.contains(id) && effect != null) {
                player.removeEffect(effect); // ran out on the mirror
            }
        }
        shown.clear();
        shown.addAll(now);
        float[] stats = LegacyBridge.call("changedStats", mirror);
        if (stats != null) {
            player.getFoodData().setFoodLevel((int) stats[0]);
            player.getFoodData().setSaturation(stats[1]);
            if (stats[2] != player.getHealth()) {
                player.setHealth(stats[2]);
            }
        }
    }

    /** Client: the local player; null on servers. */
    static Player localPlayerOrNull() {
        return net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient() ? retrofit.forge.client.ClientHooks.localPlayer() : null;
    }

    // ---- The players' legacy state (Forge extended properties, entity data, mod potions) ----

    private static final String STATE_KEY = "RetrofitLegacyPlayer";

    /** Login: what was saved with the player becomes its mirrors' state. */
    public static void loadState(ServerPlayer player) {
        byte[] data = player.getPersistentData().getByteArray(STATE_KEY);
        if (data.length > 0) {
            LegacyBridge.call("loadPlayerData", player.getUUID().toString(), (Object) data);
        }
    }

    /** Puts the mirror's state into the player's saved data (logout, server stop, and now and then). */
    public static void saveState(ServerPlayer player) {
        byte[] data = LegacyBridge.call("playerData", mirror(player, false));
        if (data != null) {
            player.getPersistentData().putByteArray(STATE_KEY, data);
        }
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
        if (!player.level().isClientSide) {
            applyNewEffects(player, mirror);
        }
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

    /** Removes a player's mirrors (logout), or only those outside its current level (dimension change). */
    static void forget(Player player, boolean loggedOut) {
        if (loggedOut) {
            SERVER_HOSTS.remove(player.getUUID());
            SHOWN_MOD_EFFECTS.remove(player.getUUID());
        }
        LegacyBridge.call("removePlayer", player.getUUID().toString(), null);
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
        public void changeDimension(int dimension, Object legacyTeleporter) {
            if (player instanceof ServerPlayer server) {
                LegacyDimensions.travel(server, dimension, legacyTeleporter);
            }
        }

        @Override
        public boolean hurt(String kind, Object legacyAttacker, float amount) {
            Player target = player;
            if (target == null || target.level().isClientSide) {
                return false;
            }
            net.minecraft.world.damagesource.DamageSources sources = target.damageSources();
            net.minecraft.world.entity.Entity attacker = LegacyEntityCore.hostOf(legacyAttacker);
            net.minecraft.world.damagesource.DamageSource source;
            if (attacker instanceof net.minecraft.world.entity.LivingEntity living) {
                source = sources.mobAttack(living);
            } else {
                source = switch (kind == null ? "generic" : kind) {
                    case "fall" -> sources.fall();
                    case "inFire" -> sources.inFire();
                    case "onFire" -> sources.onFire();
                    case "lava" -> sources.lava();
                    case "drown" -> sources.drown();
                    case "cactus" -> sources.cactus();
                    case "magic" -> sources.magic();
                    case "wither" -> sources.wither();
                    case "outOfWorld" -> sources.fellOutOfWorld();
                    case "inWall" -> sources.inWall();
                    case "starve" -> sources.starve();
                    case "explosion", "explosion.player" -> sources.explosion(null, null);
                    default -> attacker != null ? sources.mobProjectile(attacker, null) : sources.generic();
                };
            }
            return target.hurt(source, amount);
        }

        @Override
        public int permissionLevel() {
            Player target = player;
            if (target instanceof ServerPlayer server) {
                for (int level = 4; level > 0; level--) {
                    if (server.hasPermissions(level)) {
                        return level;
                    }
                }
            }
            return 0;
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
