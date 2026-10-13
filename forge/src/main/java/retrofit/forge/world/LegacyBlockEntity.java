package retrofit.forge.world;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Hosts one legacy tile entity: ticks it on the server and stores its NBT under "legacy".
 * The legacy object is created lazily, once the level is known, either fresh or from saved NBT.
 */
public class LegacyBlockEntity extends BlockEntity {
    private static final String LEGACY_TAG = "legacy";

    private Object legacyTile;
    private CompoundTag pendingNbt;

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
        }
        return legacyTile;
    }

    void serverTick() {
        Object tile = legacyTile();
        if (tile != null && LegacyBridge.canUpdate(tile)) {
            LegacyBridge.tick(tile);
            // 1.7.10 saved chunks with ticking content on every world save; many mods rely on that
            // instead of calling markDirty. Flag the chunk unsaved (cheap: no neighbour updates).
            level.blockEntityChanged(getBlockPos());
        }
    }

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
