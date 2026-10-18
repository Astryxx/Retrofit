package retrofit.forge.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * What the 1.20.1 entities for legacy entities ({@link LegacyMob}, {@link LegacyThing}) share.
 *
 * Servers: the legacy entity lives in the level's legacy world and runs its own update each tick
 * (AI, movement against the host's blocks, attacks on players' mirrors); the host entity copies
 * its position, rotation, motion, size and health, so 1.20.1 tracks and sends it as usual. Damage
 * and right-clicks go to the legacy entity; it decides when it dies (the host entity is then
 * removed). Saved as the legacy entity's own NBT.
 *
 * Clients: a copy of the legacy entity, created from the spawn data (legacy id, DataWatcher, the
 * mod's own spawn data), follows the host entity and runs its client update, which drives the
 * animations its renderer reads.
 */
public final class LegacyEntityCore {
    /** Legacy entity -> its host entity, both sides (for damage attribution, events, lookups). */
    private static final Map<Object, Entity> HOSTS = Collections.synchronizedMap(new WeakHashMap<>());
    static final String LEGACY_TAG = "legacy";

    private final Entity host;
    private final String legacyName;
    private Object legacy;
    private CompoundTag pending;
    private boolean broken;
    // client spawn data
    private int legacyId = -1;
    private byte[] spawnWatcher;
    private byte[] spawnData;
    float width;
    float height;

    LegacyEntityCore(Entity host, String legacyName, float width, float height) {
        this.host = host;
        this.legacyName = legacyName;
        this.width = width;
        this.height = height;
    }

    /** Where riders sit, from the legacy entity (getMountedYOffset), above its feet. */
    private double mountedOffset = -1;

    double ridingOffset(double fallback) {
        return mountedOffset >= 0 ? mountedOffset : fallback;
    }

    /** The host entity of a legacy entity, a player's mirror, or a stand-in for a 1.20.1 entity; null if none. */
    static Entity hostOf(Object legacy) {
        if (legacy == null) {
            return null;
        }
        Entity host = HOSTS.get(legacy);
        if (host == null) {
            Object handle = LegacyBridge.call("hostHandle", legacy);
            host = handle instanceof Entity entity ? entity : null;
        }
        return host;
    }

    String legacyName() {
        return legacyName;
    }

    /** Adopt a legacy entity legacy code made itself (spawnEntityInWorld). */
    void adopt(Object entity) {
        legacy = entity;
        HOSTS.put(entity, host);
        if (!LegacyBridge.<Boolean>call("entityJoin", entity, LegacyContent.legacyWorld(host.level()))) {
            host.discard(); // a mod cancelled its EntityJoinWorldEvent
            return;
        }
        apply(LegacyBridge.call("entityState", entity), true);
    }

    /** The legacy entity, created on first use (fresh, from saved NBT, or on clients from the spawn data). */
    public Object legacy() {
        if (legacy != null || broken) {
            return legacy;
        }
        try {
            Object world = LegacyContent.legacyWorld(host.level());
            if (pending != null) {
                legacy = LegacyBridge.call("entityLoad", (Object) LegacyStacks.toBytes(pending), world);
                pending = null;
            }
            boolean fresh = false;
            if (legacy == null) {
                legacy = LegacyBridge.call("entityCreate", legacyName, world, host.getX(), host.getY(), host.getZ());
                fresh = legacy != null && !host.level().isClientSide;
            }
            if (fresh) {
                LegacyBridge.call("entityFinalizeSpawn", legacy); // natural spawns, spawn eggs, /summon: equipment, variants
            }
            if (legacy == null) {
                broken = true;
                return null;
            }
            HOSTS.put(legacy, host);
            if (host.level().isClientSide) {
                if (legacyId >= 0) {
                    LegacyBridge.call("entitySetId", legacy, legacyId);
                }
                if (spawnWatcher != null) {
                    LegacyBridge.call("entityApplyWatcher", legacy, spawnWatcher);
                }
                LegacyBridge.call("entityApplySpawnData", legacy, spawnData);
                LegacyBridge.call("entityPlace", legacy, host.getX(), host.getY(), host.getZ(), host.getYRot(), host.getXRot());
            }
            if (!LegacyBridge.<Boolean>call("entityJoin", legacy, world)) {
                host.discard(); // a mod cancelled its EntityJoinWorldEvent
            }
        } catch (RuntimeException e) {
            fail("being created", e);
        }
        return legacy;
    }

    private void fail(String action, RuntimeException e) {
        broken = true;
        LegacyErrors.report("entity " + legacyName, action, e);
    }

    /** Server tick: the legacy update, then its state onto the host entity. */
    void serverTick() {
        Object entity = legacy();
        if (entity == null) {
            host.discard();
            return;
        }
        try {
            double[] state = LegacyBridge.call("entityTick", entity);
            apply(state, false);
            byte[] watcher = LegacyBridge.call("entityWatcherChanges", entity);
            if (watcher != null) {
                LegacyNetwork.sendEntityData(host, watcher);
            }
            syncVehicle(entity);
            if (state[14] != 0) { // dead
                com.mojang.logging.LogUtils.getLogger().debug("Legacy entity {} at {} is dead (health {})", legacyName, host.blockPosition(), state[10]);
                if (Boolean.getBoolean("retrofit.debugEntities")) {
                    com.mojang.logging.LogUtils.getLogger().info("Legacy entity {} at {} is dead (health {})", legacyName, host.blockPosition(), state[10]);
                }
                LegacyBridge.call("entityLeave", entity);
                host.discard();
            }
        } catch (RuntimeException e) {
            fail("ticking", e);
            host.discard();
        }
    }

    /** Legacy creatures riding legacy creatures (jockeys, mounts of mounts): the host entities ride likewise. */
    private void syncVehicle(Object entity) {
        // A legacy vehicle that threw its rider off (water, bucking): the host player gets off too.
        if (!host.getPassengers().isEmpty() && host.getFirstPassenger() instanceof net.minecraft.world.entity.player.Player
                && LegacyBridge.call("entityRider", entity) == null) {
            host.getFirstPassenger().stopRiding();
        }
        Object vehicle = LegacyBridge.call("entityRiding", entity);
        Entity hostVehicle = vehicle == null ? null : hostOf(vehicle);
        if (hostVehicle != null && host.getVehicle() != hostVehicle) {
            host.startRiding(hostVehicle, true);
        } else if (vehicle == null && host.getVehicle() instanceof LegacyEntityCarrier) {
            host.stopRiding();
        }
    }

    /** {x, y, z, yaw, pitch, head yaw, body yaw, motion x, y, z, health, max health, width, height, dead, on ground, mounted offset} */
    private void apply(double[] state, boolean exact) {
        // Some mods leave NaN in rotations or motion (1.7.10 tolerated it; 1.20.1 discards such entities).
        if (Double.isNaN(state[0]) || Double.isNaN(state[1]) || Double.isNaN(state[2])) {
            return;
        }
        double[] fallback = {0, 0, 0, host.getYRot(), host.getXRot(), host.getYHeadRot(), host.getYRot(), 0, 0, 0};
        for (int i = 3; i <= 9; i++) {
            if (!Double.isFinite(state[i])) {
                state[i] = fallback[i];
            }
        }
        if (state.length > 16) {
            mountedOffset = state[16];
        }
        if (exact) {
            host.moveTo(state[0], state[1], state[2], (float) state[3], (float) state[4]);
        } else {
            host.setPos(state[0], state[1], state[2]);
            host.setYRot((float) state[3]);
            host.setXRot((float) state[4]);
        }
        host.setDeltaMovement(new Vec3(state[7], state[8], state[9]));
        host.setOnGround(state[15] != 0);
        if (host instanceof LivingEntity living) {
            living.setYHeadRot((float) state[5]);
            living.yBodyRot = (float) state[6];
            if (state[11] > 0 && living.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH) != null
                    && living.getAttributeBaseValue(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH) != state[11]) {
                living.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(state[11]);
            }
            living.setHealth((float) Math.max(state[10], state[14] != 0 ? 0 : 0.01));
        }
        if ((float) state[12] != width || (float) state[13] != height) {
            width = (float) state[12];
            height = (float) state[13];
            host.refreshDimensions();
        }
    }

    /** Client tick: follow the host entity. */
    void clientTick() {
        Object entity = legacy();
        if (entity == null) {
            return;
        }
        try {
            float headYaw = host instanceof LivingEntity living ? living.getYHeadRot() : host.getYRot();
            LegacyBridge.call("entityClientTick", entity, host.getX(), host.getY(), host.getZ(), host.getYRot(), host.getXRot(), headYaw);
        } catch (RuntimeException e) {
            fail("updating on the client", e);
        }
    }

    // ---- Damage and use ----

    boolean hurt(DamageSource source, float amount) {
        Object entity = legacy();
        if (entity == null || host.level().isClientSide) {
            return false;
        }
        Entity attacker = source.getEntity();
        Object legacyAttacker = null;
        Player player = attacker instanceof Player p ? p : null;
        if (player != null) {
            legacyAttacker = LegacyPlayers.mirror(player, true);
        } else if (attacker instanceof LegacyMob mob) {
            legacyAttacker = mob.core().legacy();
        }
        try {
            boolean hurt = LegacyBridge.call("entityHurt", entity, source.getMsgId(), legacyAttacker, amount);
            if (player != null) {
                LegacyPlayers.writeBack(player, legacyAttacker);
            }
            return hurt;
        } catch (RuntimeException e) {
            LegacyErrors.report("entity " + legacyName, "being hurt", e);
            return false;
        }
    }

    InteractionResult interact(Player player, InteractionHand hand) {
        Object entity = legacy();
        if (entity == null || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        try {
            Object mirror = LegacyPlayers.mirror(player, true);
            boolean used = LegacyBridge.call("entityInteract", entity, mirror);
            LegacyPlayers.writeBack(player, mirror);
            LegacyPlayers.syncRiding(player, mirror); // mounts (horses, brooms) mount the player in legacy code
            return used ? InteractionResult.sidedSuccess(host.level().isClientSide) : InteractionResult.PASS;
        } catch (RuntimeException e) {
            LegacyErrors.report("entity " + legacyName, "being used", e);
            return InteractionResult.PASS;
        }
    }

    /** Client: a legacy entity state event from the server. */
    public void entityEvent(int event) {
        Object entity = legacy();
        if (entity != null) {
            try {
                LegacyBridge.call("entityEvent", entity, event);
            } catch (RuntimeException e) {
                LegacyErrors.report("entity " + legacyName, "showing an event", e);
            }
        }
    }

    /** Client: DataWatcher changes from the server. */
    public void watcher(byte[] data) {
        Object entity = legacy();
        if (entity != null) {
            try {
                LegacyBridge.call("entityApplyWatcher", entity, data);
            } catch (RuntimeException e) {
                LegacyErrors.report("entity " + legacyName, "receiving its data", e);
            }
        }
    }

    // ---- Saving and spawning ----

    void save(CompoundTag tag) {
        try {
            if (legacy != null) {
                tag.put(LEGACY_TAG, LegacyStacks.fromBytes(LegacyBridge.call("entitySave", legacy)));
            } else if (pending != null) {
                tag.put(LEGACY_TAG, pending);
            }
        } catch (RuntimeException e) {
            LegacyErrors.report("entity " + legacyName, "being saved", e);
        }
    }

    void load(CompoundTag tag) {
        if (tag.contains(LEGACY_TAG)) {
            pending = tag.getCompound(LEGACY_TAG);
        }
    }

    void writeSpawn(FriendlyByteBuf buffer) {
        Object entity = legacy();
        byte[] watcher = null;
        byte[] data = null;
        int id = -1;
        try {
            if (entity != null) {
                id = LegacyBridge.call("entityId", entity);
                watcher = LegacyBridge.call("entityWatcherAll", entity);
                data = LegacyBridge.call("entitySpawnData", entity);
            }
        } catch (RuntimeException e) {
            LegacyErrors.report("entity " + legacyName, "being sent to a client", e);
        }
        buffer.writeInt(id);
        buffer.writeFloat(width);
        buffer.writeFloat(height);
        buffer.writeByteArray(watcher == null ? new byte[0] : watcher);
        buffer.writeBoolean(data != null);
        if (data != null) {
            buffer.writeByteArray(data);
        }
    }

    void readSpawn(FriendlyByteBuf buffer) {
        legacyId = buffer.readInt();
        width = buffer.readFloat();
        height = buffer.readFloat();
        byte[] watcher = buffer.readByteArray();
        spawnWatcher = watcher.length == 0 ? null : watcher;
        spawnData = buffer.readBoolean() ? buffer.readByteArray() : null;
        host.refreshDimensions();
    }

    void removed() {
        if (legacy != null) {
            try {
                LegacyBridge.call("entityLeave", legacy);
            } catch (RuntimeException ignored) {
                // Its world is going away too.
            }
            HOSTS.remove(legacy);
        }
    }

    /** Client: the legacy copy for the renderer, or null. */
    public Object clientLegacy() {
        return legacy();
    }
}
