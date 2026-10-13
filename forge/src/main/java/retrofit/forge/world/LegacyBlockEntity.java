package retrofit.forge.world;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

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
 */
public class LegacyBlockEntity extends BlockEntity {
    private static final String LEGACY_TAG = "legacy";
    private static final String DESCRIPTION_TAG = "legacyDescription";

    private Object legacyTile;
    private CompoundTag pendingNbt;
    private CompoundTag pendingDescription;

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
                CompoundTag description = pendingDescription;
                pendingDescription = null;
                applyDescription(description);
            }
        }
        return legacyTile;
    }

    void tick() {
        Object tile = legacyTile();
        if (tile == null || !LegacyBridge.canUpdate(tile)) {
            return;
        }
        LegacyBridge.tick(tile);
        if (!level.isClientSide) {
            // 1.7.10 saved chunks with ticking content on every world save; many mods rely on that
            // instead of calling markDirty. Flag the chunk unsaved (cheap: no neighbour updates).
            level.blockEntityChanged(getBlockPos());
        }
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
        Object tile = legacyTile();
        byte[] description = tile == null ? null : LegacyBridge.descriptionTag(tile);
        if (description != null) {
            tag.put(DESCRIPTION_TAG, fromBytes(description));
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
        CompoundTag description = tag.getCompound(DESCRIPTION_TAG);
        if (legacyTile() == null) {
            pendingDescription = description; // delivered once the level is known
        } else {
            applyDescription(description);
        }
    }

    private void applyDescription(CompoundTag description) {
        LegacyBridge.applyDescription(legacyTile, toBytes(description));
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (legacyTile != null) {
            LegacyBridge.remove(legacyTile);
        }
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
