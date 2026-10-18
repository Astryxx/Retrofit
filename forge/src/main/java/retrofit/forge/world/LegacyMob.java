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
    private final LegacyEntityCore core;

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
        } else {
            core.serverTick();
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return !level().isClientSide && !isInvulnerableTo(source) && core.hurt(source, amount);
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
