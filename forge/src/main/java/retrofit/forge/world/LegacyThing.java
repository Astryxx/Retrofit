package retrofit.forge.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;
import net.minecraftforge.network.NetworkHooks;

/** The 1.20.1 entity for a non-living legacy entity (projectiles, vehicles, effects); see {@link LegacyEntityCore}. */
public class LegacyThing extends Entity implements IEntityAdditionalSpawnData, LegacyEntityCarrier {
    private final LegacyEntityCore core;

    public LegacyThing(EntityType<? extends LegacyThing> type, Level level, String legacyName, float width, float height) {
        super(type, level);
        this.core = new LegacyEntityCore(this, legacyName, width, height);
    }

    @Override
    public LegacyEntityCore core() {
        return core;
    }

    @Override
    protected void defineSynchedData() {
    }

    @Override
    public void tick() {
        if (level().isClientSide) {
            super.tick();
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
    public InteractionResult interact(Player player, InteractionHand hand) {
        return core.interact(player, hand);
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return EntityDimensions.scalable(Math.max(0.1f, core.width), Math.max(0.1f, core.height));
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        core.load(tag);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        core.save(tag);
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
