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
    /** A copy that failed this many consecutive attempts is given up on (10 seconds of ticks). */
    private static final int CREATION_TRIES = 200;

    private final Entity host;
    private final String legacyName;
    private Object legacy;
    private CompoundTag pending;
    private int failedCreations;
    /** The health apply() last put on the host (read back: setHealth clamps); the reference for spotting health writes that bypassed hurt(). */
    private float syncedHealth = Float.NaN;
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

    /** Where riders sit, from the legacy entity (getMountedYOffset), above its feet; below them for some (rockets: inside). NaN until known. */
    private double mountedOffset = Double.NaN;

    double ridingOffset(double fallback) {
        if (host.level().isClientSide) { // the client's host entity moves by packets alone: ask its legacy entity
            Object entity = legacy();
            return entity == null ? fallback : LegacyBridge.<Double>call("mountedOffset", entity);
        }
        return Double.isNaN(mountedOffset) ? fallback : mountedOffset;
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

    /**
     * A legacy entity arriving as its own saved NBT (stored 1.7.10 entity data the host converts:
     * such an "id" names no 1.20.1 entity type). Hosted like an adopted one, loading from that NBT
     * on first use, as if it had been saved with the host entity and loaded back.
     */
    void adoptNbt(CompoundTag legacyNbt) {
        legacy = null;
        pending = legacyNbt;
        failedCreations = 0;
    }

    /**
     * The legacy entity, created on first use (fresh, from saved NBT, or on clients from the spawn
     * data).
     *
     * A failed attempt is not permanent: it is reported once (LegacyErrors collapses repeats) and the
     * next use tries again. Creation runs at fragile moments (against a world being torn down, mid
     * switch, while the entity tracker moves the carrier), and one failure there used to blank the mob
     * and everything watching it (boss bars, spawn caches) for as long as the entity lived: mods like
     * The Titans keep a boss bar per entity, so a copy that stopped coming back took the mob and its
     * bar away for good.
     */
    public Object legacy() {
        if (legacy != null) {
            return legacy;
        }
        try {
            Object world = LegacyContent.legacyWorld(host.level());
            if (pending != null) {
                Object loaded = LegacyBridge.call("entityLoad", (Object) LegacyStacks.toBytes(pending), world);
                if (loaded != null) {
                    legacy = loaded;
                    pending = null;
                }
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
                fail("being created", new IllegalStateException("no legacy entity was made"));
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
            failedCreations = 0;
        } catch (RuntimeException e) {
            fail("being created", e);
            return null;
        }
        return legacy;
    }

    private void fail(String action, RuntimeException e) {
        failedCreations++;
        LegacyErrors.report("entity " + legacyName, action, e);
    }

    /** Server tick: the legacy update, then its state onto the host entity. */
    void serverTick() {
        Object entity = legacy();
        if (entity == null) {
            // The copy isn't there (yet): keep the host so a transient failure can heal. A copy that
            // never comes (a name no entity class answers to, a constructor that always throws) takes
            // the host with it after a grace period, as the immediate discard did.
            if (failedCreations >= CREATION_TRIES) {
                host.discard();
            }
            return;
        }
        try {
            deliverForeignHealthWrites(entity); // a mod's health-write damage: deliver before this tick's state sync erases it
            disarmHealthWriteWatchdog();        // a mod's no-damage watchdog must not erase the host for its own mod's damage rules
            double[] state = LegacyBridge.call("entityTick", entity);
            apply(state, false);
            failedCreations = 0; // a clean tick: the grace period counts consecutive failures only
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
            // A tick that failed (mid world switch, a mod's code hitting a hole): skip this tick and
            // carry the last state; the host goes only when the failure repeats for 10 seconds, so
            // one bad tick no longer kills the mob.
            fail("ticking", e);
            if (failedCreations >= CREATION_TRIES) {
                host.discard();
            }
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
            if (host instanceof LegacyMob mob) mob.setLegacyDying(state[10] <= 0);
            living.setYHeadRot((float) state[5]);
            living.yBodyRot = (float) state[6];
            if (state[11] > 0 && living.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH) != null
                    && living.getAttributeBaseValue(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH) != state[11]) {
                living.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(state[11]);
            }
            living.setHealth((float) Math.max(state[10], state[14] != 0 ? 0 : 0.01));
            syncedHealth = living.getHealth();
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
            double health = 0.0;
            double maxHealth = 0.0;
            if (host instanceof LivingEntity living) {
                // apply() keeps the host barely alive while the legacy death sequence runs. That
                // placeholder must not revive the client's real legacy death animation.
                health = host instanceof LegacyMob mob && mob.legacyDying() ? 0 : living.getHealth();
                maxHealth = living.getMaxHealth();
            }
            LegacyBridge.call("entityClientTick", entity, host.getX(), host.getY(), host.getZ(), host.getYRot(), host.getXRot(), headYaw, health, maxHealth);
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
        Object legacyAttacker = attackerOf(attacker);
        try {
            boolean hurt = LegacyBridge.call("entityHurt", entity, source.getMsgId(), legacyAttacker, amount);
            if (attacker instanceof Player player) {
                LegacyPlayers.writeBack(player, legacyAttacker);
            }
            return hurt;
        } catch (RuntimeException e) {
            LegacyErrors.report("entity " + legacyName, "being hurt", e);
            return false;
        }
    }

    /**
     * Another entity as this one's legacy world sees it: a legacy entity itself, a player's mirror,
     * or the stand-in for a 1.20.1 creature (servers only); null if legacy code can't see it.
     */
    Object legacyOf(Entity entity) {
        return legacyOf(host.level(), entity);
    }

    /** An entity as the legacy world of a level sees it (see above). */
    static Object legacyOf(net.minecraft.world.level.Level level, Entity entity) {
        if (entity == null) {
            return null;
        }
        if (entity instanceof LegacyEntityCarrier carrier) {
            return carrier.core().legacy();
        }
        if (entity instanceof Player player) {
            return LegacyPlayers.mirror(player, false);
        }
        if (level.isClientSide || entity.level() != level) {
            return null;
        }
        Object[] row = HostCreatures.row(entity);
        return row == null ? null : LegacyBridge.call("standIn", LegacyContent.legacyWorld(level), row);
    }

    /**
     * The legacy entity to name as the attacker in a hit on this one: a player's mirror with its inventory
     * (the hit may wear its weapon; written back after), otherwise as legacy code sees the attacker (a stand-in
     * for a 1.20.1 creature, or the owner of the projectile that hit). Anonymous damage is not the same thing:
     * 1.7.10 damage always named its attacker, and mods check the name's class and remember it - The Titans'
     * titans refuse attacker-less damage outright, take multiplied damage from named bosses (a 1.20.1 wither
     * must arrive as a 1.7.10 EntityWither for the x100 rule), and set a revenge target.
     */
    private Object attackerOf(Entity attacker) {
        try {
            return attacker instanceof Player player ? LegacyPlayers.mirror(player, true) : legacyOf(attacker);
        } catch (RuntimeException e) {
            LegacyErrors.report("entity " + legacyName, "naming its attacker", e);
            return null;
        }
    }

    /** 1.20.1 code set the host mob's target: the legacy AI takes it (see Entities.entitySetTarget). */
    void setTarget(LivingEntity target) {
        Object entity = legacy();
        if (entity == null || host.level().isClientSide) {
            return;
        }
        try {
            LegacyBridge.call("entitySetTarget", entity, legacyOf(target));
        } catch (RuntimeException e) {
            LegacyErrors.report("entity " + legacyName, "taking a target", e);
        }
    }

    /** 1.20.1 code healed the host mob: the legacy creature heals (its health is the one the host's follows). */
    void heal(float amount) {
        Object entity = legacy();
        if (entity == null || host.level().isClientSide) {
            return;
        }
        try {
            LegacyBridge.call("entityHeal", entity, amount);
            if (host instanceof LivingEntity living) {
                syncedHealth = living.getHealth(); // heal() already forwarded this write; don't deliver it twice
            }
        } catch (RuntimeException e) {
            LegacyErrors.report("entity " + legacyName, "being healed", e);
        }
    }

    /**
     * Damage that never reached hurt(): some mods damage by writing the target's health field directly
     * (OmniMobs' bosses force their damage onto a health write and never call hurt), which none of the
     * regular paths see - and the next apply() would overwrite the write with the legacy entity's
     * unchanged health, so the attack simply evaporates. A drop on the host since the last sync is
     * therefore taken as damage and delivered to the legacy entity, named after the last entity that
     * hurt it (the force-damage code records that), through the same attackEntityFrom path as regular
     * damage: boss rules, bars, revenge and all. A write with no such name arrives as anonymous
     * generic damage - 1.7.10's rules for anonymous damage apply (a titan refuses it). Writes upward
     * (heals) are delivered as legacy heals too (Usagi's direct health-write fix).
     */
    private void deliverForeignHealthWrites(Object entity) {
        if (!(host instanceof LivingEntity living) || Float.isNaN(syncedHealth)) {
            return;
        }
        float drop = syncedHealth - living.getHealth();
        if (drop < -1.0e-3f && Float.isFinite(drop)) {
            try {
                LegacyBridge.call("entityHeal", entity, -drop);
            } catch (RuntimeException e) {
                LegacyErrors.report("entity " + legacyName, "taking a heal set on its host", e);
            }
            return;
        }
        if (!(drop > 1.0e-3f)) { // also skips a NaN health: it can't be read as a drop
            return;
        }
        // The force-damage record: OmniMobs' EntityUtil.forceHurt writes the attacker onto the target
        // right after the health write, with LivingEntity's "last hurt by a mob" API - and which of its
        // two records that call is depends on the runtime's deobfuscation of it. Read both: getLastHurtByMob
        // ("who last hurt me", the record a passive host only gets from that call - vanilla hurt() is
        // intercepted and never reaches it) first, then getLastHurtMob ("who I last hurt", never set on
        // a passive host except by that same call). Missing either made the delivery anonymous, and a
        // titan refuses anonymous generic damage outright - its bar never moved.
        Entity attacker = living.getLastHurtByMob();
        if (attacker == null) {
            attacker = living.getLastHurtMob();
        }
        Object legacyAttacker = attackerOf(attacker);
        try {
            // entityHurt names the damage after the attacker when there is one (a player's mirror
            // causes player damage, a creature mob damage); only truly anonymous force damage
            // falls through to the kind, generic.
            LegacyBridge.call("entityHurt", entity, "generic", legacyAttacker, drop);
            if (attacker instanceof Player player) {
                LegacyPlayers.writeBack(player, legacyAttacker);
            }
        } catch (RuntimeException e) {
            LegacyErrors.report("entity " + legacyName, "taking damage set on its host", e);
        }
    }

    /**
     * OmniMobs' Flashfur runs a no-damage watchdog on what it hits (FlashfurInvulnerabilityDetection):
     * every 200 ticks it compares the target's health against its last checkpoint, and after ten
     * checkpoints without a >1% drop it erases the target from the world outright (forceRemove plus a
     * teleport a million blocks out - players get an infinite-damage hit instead). That watchdog cannot
     * see the damage a legacy entity's own rules refused - The Titans' titans no-hand hits of 50 or
     * less by design, and refused anonymous damage besides - so a titan fighting the way its mod fights
     * would vanish from the world mid-battle. Its counter ("omnimobs_counter_data" on the target's
     * persistent data, read every checkpoint) is therefore kept at zero on hosts: their damage
     * registration is the bridge's business, not the watchdog's. Absent mod: the tag is never written,
     * so this is a no-op.
     */
    private void disarmHealthWriteWatchdog() {
        if (host instanceof LivingEntity) {
            net.minecraft.nbt.CompoundTag data = host.getPersistentData();
            if (data.contains("omnimobs_counter_data")) {
                data.putFloat("omnimobs_counter_data", 0.0F);
            }
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

    /**
     * Every tick: legacy entities whose host entity left the level without the usual remove() call.
     * 1.20.1 code can take a carrier out of the level straight through its internals (entity-remover
     * items, lag-clearing tools: they call the level callback themselves and never reach remove(),
     * which this class overrides), and the client's remove-packet path calls setRemoved, which is
     * final on Entity. Either way the legacy copy would stay alive in its legacy world forever, and
     * mods tracking entities themselves (boss bars, spawn caches) would never let go of it. removed()
     * takes the copy out of its world's entity list and marks it dead, as 1.7.10's WorldClient did
     * when an entity left the world. Idempotent, and harmless on the usual removal paths (remove()
     * has already called it by then).
     */
    public static void sweepRemovedCarriers(boolean clientSide) {
        if (HOSTS.isEmpty()) {
            return;
        }
        java.util.Map.Entry<Object, Entity>[] entries;
        synchronized (HOSTS) {
            entries = HOSTS.entrySet().toArray(new java.util.Map.Entry[0]);
        }
        for (java.util.Map.Entry<Object, Entity> entry : entries) {
            Entity host = entry.getValue();
            // own side only: the other side's world lives on another thread (integrated server)
            if (!(host instanceof LegacyEntityCarrier carrier) || host.level().isClientSide != clientSide) {
                continue;
            }
            if (host.isRemoved()) {
                carrier.core().removed();
            } else if (clientSide) {
                // A copy a mod's own client-side rule killed (The Titans despawn its dud mobs past 64
                // blocks on the client too) while the carrier is still tracked: 1.7.10's World tick
                // dropped dead entities from the list as it ran, and this world's copies tick through
                // their hosts, so nothing else would ever take it out. The copy is a legacy entity the
                // host side only has as an Object: entityTakeOut checks its dead flag and world (a live
                // copy stays in the list) and is a no-op when they don't ask for a take-out.
                try {
                    LegacyBridge.call("entityTakeOut", entry.getKey());
                } catch (RuntimeException ignored) {
                    // Its world is going away; the carrier's own removal will clean it up.
                }
            }
        }
    }

    /** Client: the legacy copy for the renderer, or null. */
    public Object clientLegacy() {
        return legacy();
    }
}
