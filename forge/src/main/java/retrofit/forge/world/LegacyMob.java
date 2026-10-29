package retrofit.forge.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;
import net.minecraftforge.network.NetworkHooks;

/**
 * The 1.20.1 entity for a living legacy entity (mobs, animals, bosses). A Mob, so 1.20.1 spawns,
 * counts and tracks it like its own creatures; its behaviour is entirely the legacy entity's (see
 * {@link LegacyEntityCore}): no 1.20.1 AI or physics run on servers.
 */
public class LegacyMob extends Mob implements IEntityAdditionalSpawnData, LegacyEntityCarrier {
    private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> LEGACY_DYING =
            net.minecraft.network.syncher.SynchedEntityData.defineId(LegacyMob.class, net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);
    private final LegacyEntityCore core;

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(LEGACY_DYING, false);
    }

    void setLegacyDying(boolean dying) { entityData.set(LEGACY_DYING, dying); }
    boolean legacyDying() { return entityData.get(LEGACY_DYING); }

    public LegacyMob(EntityType<? extends LegacyMob> type, Level level, String legacyName, float width, float height) {
        super(type, level);
        this.core = new LegacyEntityCore(this, legacyName, width, height);
    }

    @Override
    public LegacyEntityCore core() {
        return core;
    }

    @Override
    public void tick() {
        if (level().isClientSide) {
            super.tick(); // interpolation towards the server's positions
            core.clientTick();
            if (tickCount % 5 == 0) {
                clientLeash();
            }
        } else {
            core.serverTick();
            if (tickCount % 5 == 0) {
                syncLeash();
            }
            tickModTargetGoals();
        }
    }

    /**
     * Target goals 1.20.1 mods give a legacy mob (Mob Battle's teams pick their enemies with them),
     * at Mob.serverAiStep's pace; the targets they pick go to the legacy AI (setTarget), which moves
     * and fights. The mob's other goals don't run: the legacy AI is the one that moves it.
     */
    private void tickModTargetGoals() {
        if (targetSelector.getAvailableGoals().isEmpty() || isNoAi()) {
            return;
        }
        if (tickCount % 2 == 0) {
            getSensing().tick();
            targetSelector.tick();
        } else {
            targetSelector.tickRunningGoals(false);
        }
    }

    // ---- Leashes: 1.7.10's own (EntityLiving: leads, pulling, letting go); the host entity shows them ----

    /** Leads go to the legacy creature (its interactFirst), which 1.7.10 leashes and pulls itself. */
    @Override
    public boolean canBeLeashed(Player player) {
        return false;
    }

    private boolean syncingLeash;

    /** The host's leash follows the legacy creature's (to a player), so clients see and save it. */
    private void syncLeash() {
        Object legacy = core.legacy();
        if (legacy == null) {
            return;
        }
        Object holder = LegacyBridge.call("leashHolder", legacy);
        Player hostHolder = null;
        if (holder != null) {
            for (Player player : level().players()) {
                if (player instanceof net.minecraft.server.level.ServerPlayer && LegacyPlayers.mirror(player, false) == holder) {
                    hostHolder = player;
                }
            }
        }
        if (hostHolder != getLeashHolder()) {
            syncingLeash = true;
            try {
                if (hostHolder != null) {
                    setLeashedTo(hostHolder, true);
                } else if (isLeashed()) {
                    dropLeash(true, false); // the legacy creature dropped its own lead
                }
            } finally {
                syncingLeash = false;
            }
        }
    }

    /** Letting go from the host (the holder right-clicking, a lead cut): the legacy creature lets go, and drops the lead itself. */
    @Override
    public void dropLeash(boolean broadcast, boolean dropLead) {
        super.dropLeash(broadcast, false);
        if (!syncingLeash && !level().isClientSide && core.legacy() != null) {
            LegacyBridge.call("clearLeash", core.legacy(), dropLead);
        }
    }

    /** Clients: the legacy copy is leashed to the local player when the host one is, so its renderer draws the lead. */
    private void clientLeash() {
        Object legacy = clientLegacy();
        if (legacy != null) {
            LegacyBridge.call("setClientLeash", legacy, isLeashed() && getLeashHolder() == LegacyPlayers.localPlayerOrNull());
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return !level().isClientSide && !isInvulnerableTo(source) && core.hurt(source, amount);
    }

    /** Targets 1.20.1 code sets (enragers, mob battle items) go to the legacy AI; no 1.20.1 AI runs to use them. */
    @Override
    public void setTarget(@javax.annotation.Nullable net.minecraft.world.entity.LivingEntity target) {
        super.setTarget(target);
        core.setTarget(target);
    }

    @Override
    public void heal(float amount) {
        super.heal(amount);
        core.heal(amount);
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        return core.interact(player, hand);
    }

    private net.minecraft.world.entity.MobType mobType;

    /** Undead and arthropod legacy mobs (getCreatureAttribute) for Smite, Bane of Arthropods, healing and harming. */
    @Override
    public net.minecraft.world.entity.MobType getMobType() {
        if (mobType == null) {
            Object legacy = core.legacy();
            if (legacy == null) {
                return net.minecraft.world.entity.MobType.UNDEFINED;
            }
            int attribute;
            try {
                attribute = LegacyBridge.call("entityCreatureAttribute", legacy);
            } catch (RuntimeException e) {
                attribute = 0;
            }
            mobType = switch (attribute) {
                case 1 -> net.minecraft.world.entity.MobType.UNDEAD;
                case 2 -> net.minecraft.world.entity.MobType.ARTHROPOD;
                default -> net.minecraft.world.entity.MobType.UNDEFINED;
            };
        }
        return mobType;
    }

    /** Legacy entities despawn themselves (EntityLiving.despawnEntity). */
    @Override
    public void checkDespawn() {
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return EntityDimensions.scalable(Math.max(0.1f, core.width), Math.max(0.1f, core.height));
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        core.save(tag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        core.load(tag);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    @Override
    public void writeSpawnData(FriendlyByteBuf buffer) {
        core.writeSpawn(buffer);
    }

    @Override
    public void readSpawnData(FriendlyByteBuf buffer) {
        core.readSpawn(buffer);
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        core.removed();
    }

    /** Riders sit where the legacy entity puts them (getMountedYOffset). */
    @Override
    public double getPassengersRidingOffset() {
        return core().ridingOffset(super.getPassengersRidingOffset()) - 0.15;
    }
}
