package retrofit.forge.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Hosts one legacy tile entity, on the server and on clients, as 1.7.10 did.
 *
 * - Saving: the legacy NBT is stored under {@value #LEGACY_TAG}.
 * - Client sync: the legacy description packet (getDescriptionPacket) becomes this block
 *   entity's update tag, sent with chunk data and whenever the block is marked for update, and
 *   is handed to the client-side legacy tile entity's onDataPacket.
 * - The legacy object is created lazily, once the level is known, fresh or from saved NBT.
 * - A legacy tile entity that throws is contained: the error is logged once with its position,
 *   and it never crashes the server or disconnects a player.
 */
public class LegacyBlockEntity extends BlockEntity {
    static final String LEGACY_TAG = "legacy";
    private static final String DESCRIPTION_TAG = "legacyDescription";
    private static final Logger LOGGER = LogUtils.getLogger();

    private Object legacyTile;
    private CompoundTag pendingNbt;
    private byte[] pendingDescription;
    private boolean broken;

    public LegacyBlockEntity(BlockPos pos, BlockState state) {
        super(LegacyContent.blockEntityType(), pos, state);
    }

    /** The hosted legacy tile entity, or null if the level isn't available yet. */
    public Object legacyTile() {
        if (legacyTile == null && level != null) {
            Object world = LegacyContent.legacyWorld(level);
            BlockPos pos = getBlockPos();
            if (pendingNbt != null) {
                legacyTile = LegacyBridge.load(world, toBytes(pendingNbt), pos.getX(), pos.getY(), pos.getZ());
                pendingNbt = null;
            }
            if (legacyTile == null && getBlockState().getBlock() instanceof LegacyBlock block) {
                legacyTile = LegacyBridge.createTileEntity(world, block.legacyBlock(), getBlockState().getValue(LegacyContent.META),
                        pos.getX(), pos.getY(), pos.getZ());
            }
            if (legacyTile != null && pendingDescription != null) {
                byte[] description = pendingDescription;
                pendingDescription = null;
                applyDescription(description);
            }
        }
        return legacyTile;
    }

    /** Legacy code placed its own tile entity here (World.setTileEntity): host that one instead. */
    void adopt(Object tile) {
        if (tile == legacyTile) {
            return;
        }
        if (legacyTile != null) {
            LegacyBridge.remove(legacyTile);
        }
        legacyTile = tile;
        pendingNbt = null;
        broken = false;
    }

    /** Legacy code removed the tile entity (World.removeTileEntity); a fresh one is made if the block stays. */
    void discardLegacy() {
        if (legacyTile != null) {
            LegacyBridge.remove(legacyTile);
            legacyTile = null;
        }
        pendingNbt = null;
    }

    void tick() {
        if (broken) {
            return;
        }
        Object tile;
        try {
            tile = legacyTile();
            if (tile == null || !LegacyBridge.canUpdate(tile)) {
                return;
            }
            LegacyBridge.tick(tile);
        } catch (RuntimeException e) {
            fail("ticking", e);
            return;
        }
        if (!level.isClientSide) {
            // 1.7.10 saved chunks with ticking content on every world save; many mods rely on that
            // instead of calling markDirty. Flag the chunk unsaved (cheap: no neighbour updates).
            level.blockEntityChanged(getBlockPos());
        }
    }

    // ---- Item handler (hoppers, pipes) ----

    @Override
    public <T> net.minecraftforge.common.util.LazyOptional<T> getCapability(net.minecraftforge.common.capabilities.Capability<T> capability,
                                                                             @javax.annotation.Nullable net.minecraft.core.Direction side) {
        if (capability == net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER && !broken && level != null) {
            try {
                Object tile = legacyTile();
                int legacySide = side == null ? -1 : side.get3DDataValue();
                int[] slots = tile == null ? null : LegacyBridge.call("inventorySlots", tile, legacySide);
                if (slots != null) {
                    return net.minecraftforge.common.util.LazyOptional.of(() -> new LegacyItemHandler(tile, legacySide, slots)).cast();
                }
            } catch (RuntimeException e) {
                fail("offering its inventory", e);
            }
        }
        if (capability == net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER && !broken && level != null) {
            try {
                Object tile = legacyTile();
                var tanks = tile == null ? null : LegacyFluids.handlerOf(tile, side == null ? -1 : side.get3DDataValue());
                if (tanks != null) {
                    return net.minecraftforge.common.util.LazyOptional.of(() -> tanks).cast();
                }
            } catch (RuntimeException e) {
                fail("offering its tanks", e);
            }
        }
        if (capability == net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY && !broken && level != null) {
            try {
                Object tile = legacyTile();
                if (tile != null) {
                    LegacyEnergyStorage energy = LegacyEnergyStorage.of(tile, side == null ? -1 : side.get3DDataValue());
                    if (energy != null) {
                        return net.minecraftforge.common.util.LazyOptional.of(() -> energy).cast();
                    }
                }
            } catch (RuntimeException e) {
                fail("offering its energy", e);
            }
        }
        return super.getCapability(capability, side);
    }

    // ---- Saving ----

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (legacyTile != null) {
            tag.put(LEGACY_TAG, fromBytes(LegacyBridge.save(legacyTile)));
        } else if (pendingNbt != null) {
            tag.put(LEGACY_TAG, pendingNbt);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains(LEGACY_TAG)) {
            pendingNbt = tag.getCompound(LEGACY_TAG);
            legacyTile = null;
        }
    }

    // ---- Client sync ----

    /** Sent to clients with chunk data and in update packets: the legacy description, if any. */
    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        if (broken) {
            return tag;
        }
        try {
            Object tile = legacyTile();
            byte[] description = tile == null ? null : LegacyBridge.descriptionTag(tile);
            if (description != null) {
                tag.putByteArray(DESCRIPTION_TAG, description);
            }
        } catch (RuntimeException e) {
            fail("sending its client data", e);
        }
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Chunk data on the client. Not load(): the update tag only carries the description. */
    @Override
    public void handleUpdateTag(CompoundTag tag) {
        receiveDescription(tag);
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        CompoundTag tag = packet.getTag();
        if (tag != null) {
            receiveDescription(tag);
        }
    }

    private void receiveDescription(CompoundTag tag) {
        if (!tag.contains(DESCRIPTION_TAG)) {
            return;
        }
        byte[] description = tag.getByteArray(DESCRIPTION_TAG);
        if (legacyTile() == null) {
            pendingDescription = description; // delivered once the level is known
        } else {
            applyDescription(description);
        }
    }

    private void applyDescription(byte[] description) {
        try {
            LegacyBridge.applyDescription(legacyTile, description);
        } catch (RuntimeException e) {
            fail("receiving its client data", e);
        }
    }

    /** Disables this legacy tile entity after an error instead of letting it crash or kick anyone. */
    private void fail(String action, RuntimeException e) {
        broken = true;
        LOGGER.error("Legacy tile entity at {} failed while {} and has been disabled", getBlockPos(), action, e);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (legacyTile != null && !unloading) {
            LegacyBridge.remove(legacyTile); // invalidate: the block is gone
        }
    }

    /** The chunk unloads (setRemoved follows): 1.7.10 only called onChunkUnload then; invalidate means the block was removed. */
    private boolean unloading;

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        unloading = true;
        if (legacyTile != null) {
            try {
                LegacyBridge.call("unloadTile", legacyTile);
            } catch (RuntimeException e) {
                LegacyErrors.report(this, "unloading with its chunk", e);
            }
        }
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        unloading = false;
    }

    /** Binary NBT is the same format in 1.7.10 and 1.20.1, so tags cross as bytes. */
    private static byte[] toBytes(CompoundTag tag) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.write(tag, new DataOutputStream(bytes));
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CompoundTag fromBytes(byte[] bytes) {
        try {
            return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
