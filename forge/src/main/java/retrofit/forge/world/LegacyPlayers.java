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
    private static final int INVULNERABLE = 64;
    private static final int SLOTS = 40;
    private static final boolean DEBUG_MOTION = Boolean.getBoolean("retrofit.debugInteractions");
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

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
        host.synced = player.position();
        if (withInventory) {
            LegacyBridge.call("setInventory", mirror, (Object) host.legacyInventory(player.getInventory()));
        }
        if (level.isClientSide && host.isLocal()) {
            LegacyBridge.call("makeCurrent", mirror);
        }
        return mirror;
    }

    /**
     * Start of a server level's tick: mirrors of players who went far since their last sync (a teleport,
     * which 1.20.1 does between ticks) are placed where the players now are. Players tick after the level's
     * entities, so legacy creatures would otherwise measure the distance to the old place for a tick, and
     * monsters 128 blocks from it despawned right where the player arrived, as 1.7.10 despawns them.
     */
    public static void placeTeleported(net.minecraft.server.level.ServerLevel level) {
        Object world = LegacyContent.legacyWorld(level);
        for (ServerPlayer player : level.players()) {
            Host host = SERVER_HOSTS.get(player.getUUID());
            if (host == null || host.synced == null || host.synced.distanceToSqr(player.position()) < TELEPORTED * TELEPORTED) {
                continue;
            }
            Object mirror = LegacyBridge.call("player", world, host, player.getUUID().toString(), player.getGameProfile().getName());
            LegacyBridge.call("entityPlace", mirror, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
            host.synced = player.position();
        }
    }

    /** Where the player's mirror in a level is (feet), as legacy code left it: {x, y, z, yaw, pitch}; null if it has none. */
    static double[] mirrorPlace(ServerPlayer player, net.minecraft.server.level.ServerLevel level) {
        Host host = SERVER_HOSTS.get(player.getUUID());
        if (host == null) {
            return null;
        }
        Object mirror = LegacyBridge.call("player", LegacyContent.legacyWorld(level), host, player.getUUID().toString(), player.getGameProfile().getName());
        return LegacyBridge.call("entityState", mirror);
    }

    /** Farther than a player moves in a tick: a teleport. */
    private static final double TELEPORTED = 16;

    private static void sync(Player player, Object mirror) {
        int flags = (player.isShiftKeyDown() ? SNEAKING : 0) | (player.getAbilities().instabuild ? CREATIVE : 0)
                | (player.getAbilities().flying ? FLYING : 0) | (player.getAbilities().mayfly ? MAY_FLY : 0)
                | (player.onGround() ? ON_GROUND : 0) | (player.isSprinting() ? SPRINTING : 0)
                | (player.getAbilities().invulnerable ? INVULNERABLE : 0);
        net.minecraft.world.phys.Vec3 motion = player.getDeltaMovement();
        LegacyBridge.call("sync", mirror, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(),
                motion.x, motion.y, motion.z, player.fallDistance, flags,
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
        Object mirror = mirror(player, true);
        tickRiding(player, mirror);
        LegacyBridge.call("tickServerPlayer", mirror);
        writeBack(player, mirror);
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
        applyAbilities(player, mirror);
        applyPotionModifiers(player, mirror);
        float[] stats = LegacyBridge.call("changedStats", mirror);
        if (stats != null) {
            player.getFoodData().setFoodLevel((int) stats[0]);
            player.getFoodData().setSaturation(stats[1]);
            if (stats[2] != player.getHealth()) {
                player.setHealth(stats[2]);
            }
        }
    }

    /** Flight legacy code granted or took away (Ars Magica 2's flight, brooms) goes to the player. */
    private static void applyAbilities(Player player, Object mirror) {
        int abilities = LegacyBridge.call("changedAbilities", mirror);
        if (abilities < 0 || player.getAbilities().instabuild) {
            return;
        }
        player.getAbilities().mayfly = (abilities & MAY_FLY) != 0;
        player.getAbilities().flying = player.getAbilities().mayfly && (abilities & FLYING) != 0;
        player.onUpdateAbilities();
    }

    /** Attribute modifiers of mod potions currently on each player (by UUID), so they come off when the potion ends. */
    private static final Map<UUID, Map<UUID, net.minecraft.world.entity.ai.attributes.Attribute>> POTION_MODIFIERS = new ConcurrentHashMap<>();

    private static net.minecraft.world.entity.ai.attributes.Attribute attribute(String legacyName) {
        return switch (legacyName) {
            case "generic.maxHealth" -> net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH;
            case "generic.movementSpeed" -> net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED;
            case "generic.attackDamage" -> net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE;
            case "generic.knockbackResistance" -> net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE;
            case "generic.followRange" -> net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE;
            default -> null;
        };
    }

    /** What mod potions do to speed, health, damage and so on (their attribute modifiers) applies to the host player. */
    private static void applyPotionModifiers(Player player, Object mirror) {
        Object[][] modifiers = LegacyBridge.call("modAttributeModifiers", mirror);
        Map<UUID, net.minecraft.world.entity.ai.attributes.Attribute> applied =
                POTION_MODIFIERS.computeIfAbsent(player.getUUID(), id -> new java.util.HashMap<>());
        if (modifiers.length == 0 && applied.isEmpty()) {
            return;
        }
        java.util.Set<UUID> now = new java.util.HashSet<>();
        for (Object[] modifier : modifiers) {
            net.minecraft.world.entity.ai.attributes.Attribute attribute = attribute((String) modifier[0]);
            var instance = attribute == null ? null : player.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            UUID id = UUID.fromString((String) modifier[1]);
            double amount = (Double) modifier[2];
            now.add(id);
            var current = instance.getModifier(id);
            if (current == null || current.getAmount() != amount) {
                instance.removeModifier(id);
                instance.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(id, "Legacy potion", amount,
                        net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.fromValue((Integer) modifier[3])));
                applied.put(id, attribute);
            }
        }
        for (var entry : new java.util.ArrayList<>(applied.entrySet())) {
            if (!now.contains(entry.getKey())) {
                var instance = player.getAttribute(entry.getValue());
                if (instance != null) {
                    instance.removeModifier(entry.getKey());
                }
                applied.remove(entry.getKey());
            }
        }
    }

    // ---- Riding legacy creatures ----

    private static java.lang.reflect.Field jumping;

    /**
     * Makes host and legacy riding agree after legacy code ran: legacy code that mounted the mirror
     * (or took it off) mounts the host player; a host player that got off (sneaking) gets the mirror off.
     */
    public static void syncRiding(Player player, Object mirror) {
        Object legacyVehicle = LegacyBridge.call("entityRiding", mirror);
        net.minecraft.world.entity.Entity hostVehicle = player.getVehicle();
        if (legacyVehicle != null) {
            net.minecraft.world.entity.Entity target = LegacyEntityCore.hostOf(legacyVehicle);
            if (target != null && hostVehicle != target) {
                player.startRiding(target, true);
            }
        } else if (hostVehicle instanceof LegacyEntityCarrier && !player.level().isClientSide) {
            player.stopRiding();
        }
    }

    /** Every tick while riding a legacy creature: the mirror rides it too, steering with the player's keys. */
    private static void tickRiding(ServerPlayer player, Object mirror) {
        net.minecraft.world.entity.Entity vehicle = player.getVehicle();
        if (vehicle instanceof LegacyEntityCarrier carrier) {
            Object legacyVehicle = carrier.core().legacy();
            LegacyBridge.call("setRiding", mirror, legacyVehicle);
            boolean jump = false;
            try {
                if (jumping == null) {
                    jumping = net.minecraftforge.fml.util.ObfuscationReflectionHelper.findField(net.minecraft.world.entity.LivingEntity.class, "f_20899_");
                }
                jump = jumping.getBoolean(player);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // no jump
            }
            LegacyBridge.call("setRiderInput", mirror, player.zza, player.xxa, jump);
        } else if (LegacyBridge.call("entityRiding", mirror) != null) {
            LegacyBridge.call("setRiding", mirror, null); // got off (sneak) or the vehicle went away
        }
    }

    /** Client: the local player's mirror rides what the player rides (mods check player.ridingEntity in renderers and HUDs). */
    public static void syncClientRiding(Player player, Object mirror) {
        net.minecraft.world.entity.Entity vehicle = player.getVehicle();
        Object legacyVehicle = vehicle instanceof LegacyEntityCarrier carrier ? carrier.clientLegacy() : null;
        LegacyBridge.call("setRiding", mirror, legacyVehicle);
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

    /** Applies what legacy code changed in the mirror (server only; clients wait for the server, except for motion). */
    public static void writeBack(Player player, Object mirror) {
        if (!player.level().isClientSide) {
            applyChanges(player, mirror, false);
        }
        applyMotion(player, mirror);
    }

    /**
     * Motion and fall distance legacy code gave the mirror. As on 1.7.10, the client moves its own
     * player (a jetpack's push on the client is the one that counts), while the server's changes
     * reach the player only when legacy code marked them (velocityChanged: knockback, leaps).
     */
    public static void applyMotion(Player player, Object mirror) {
        double[] motion = LegacyBridge.call("changedMotion", mirror);
        if (motion == null) {
            return;
        }
        if (DEBUG_MOTION) {
            LOGGER.info("[Interactions] legacy motion for {} on the {}: {}", player.getName().getString(), player.level().isClientSide ? "client" : "server",
                    java.util.Arrays.toString(motion));
        }
        if ((float) motion[3] != player.fallDistance) {
            player.fallDistance = (float) motion[3];
        }
        if (player.level().isClientSide || motion[4] != 0) {
            player.setDeltaMovement(motion[0], motion[1], motion[2]);
            if (!player.level().isClientSide) {
                player.hurtMarked = true; // sends the motion to the client
            }
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
            applyAbilities(player, mirror);
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
        public boolean isLocal() {
            Player current = player;
            return current == null || !current.level().isClientSide || current == retrofit.forge.client.ClientHooks.localPlayer();
        }

        @Override
        public int entityId() {
            Player current = player;
            return current == null ? -1 : current.getId();
        }

        /** Where the player was when the mirror last followed it. */
        volatile net.minecraft.world.phys.Vec3 synced;
        /** The inventory last converted, slot by slot, and its legacy stacks: only changed slots are converted again. */
        private final ItemStack[] convertedFrom = new ItemStack[SLOTS];
        private final Object[] converted = new Object[SLOTS];

        Object[] legacyInventory(Inventory inventory) {
            for (int i = 0; i < SLOTS; i++) {
                ItemStack stack = inventory.getItem(i);
                ItemStack before = convertedFrom[i];
                if (before == null || !ItemStack.matches(before, stack)) {
                    convertedFrom[i] = stack.copy();
                    converted[i] = LegacyStacks.toLegacy(stack);
                }
            }
            return converted.clone();
        }

        @Override
        public Object hostEntity() {
            return player;
        }

        @Override
        public void grantAchievement(String statId) {
            if (player instanceof ServerPlayer server) {
                LegacyAchievements.grant(server, statId);
            }
        }

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
        public void followDimension(int dimension) {
            if (player instanceof ServerPlayer server) {
                LegacyDimensions.follow(server, dimension);
            }
        }

        @Override
        public boolean hurt(String kind, Object legacyAttacker, float amount) {
            Player target = player;
            if (target == null || target.level().isClientSide) {
                return false;
            }
            net.minecraft.world.damagesource.DamageSource source = HostCreatures.damageSource(target, kind, legacyAttacker);
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
