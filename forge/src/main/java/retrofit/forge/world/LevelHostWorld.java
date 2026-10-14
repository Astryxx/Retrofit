package retrofit.forge.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.ticks.TickPriority;
import retrofit.api.HostWorld;

import java.util.Arrays;

/**
 * A 1.20.1 level as seen by legacy code. Legacy mod blocks map to their {@link LegacyBlock}s;
 * vanilla blocks map to and from real 1.20.1 blocks through {@link LegacyBlockTable}.
 *
 * Reads of unloaded chunks see air, as in 1.7.10 (which never loaded chunks to answer a query),
 * so legacy code scanning far away can't stall the server by loading or generating terrain.
 */
final class LevelHostWorld implements HostWorld {
    private final Level level;
    private final LegacyBlockTable table = LegacyBlockTable.get();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    LevelHostWorld(Level level) {
        this.level = level;
    }

    /** The block state at a position, or air if its chunk isn't loaded. */
    private BlockState state(int x, int y, int z) {
        BlockPos pos = cursor.set(x, y, z);
        return level.isLoaded(pos) ? level.getBlockState(pos) : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
    }

    /** The 1.20.1 block standing for a legacy block (mod or vanilla), or null. */
    private static Block modernBlock(Object legacyBlock) {
        BlockState state = legacyBlock == null ? null : LegacyBlockTable.modernState(legacyBlock, 0);
        return state == null ? null : state.getBlock();
    }

    // ---- Blocks and tile entities ----

    @Override
    public Object getLegacyBlock(int x, int y, int z) {
        BlockState state = state(x, y, z);
        if (state.getBlock() instanceof LegacyBlock block) {
            return block.legacyBlock();
        }
        return table.legacyBlock(table.legacyOf(state) >> 4);
    }

    @Override
    public int getLegacyMeta(int x, int y, int z) {
        BlockState state = state(x, y, z);
        if (state.getBlock() instanceof LegacyBlock) {
            return state.getValue(LegacyContent.META);
        }
        return table.legacyOf(state) & 15;
    }

    @Override
    public boolean setLegacyBlock(int x, int y, int z, Object legacyBlock, int meta, int flags) {
        BlockState target = LegacyBlockTable.modernState(legacyBlock, meta);
        if (target == null) {
            return false;
        }
        // Legacy flags 1 (notify neighbours), 2 (send to clients) and 4 (no re-render) mean the same in 1.20.1.
        return level.setBlock(new BlockPos(x, y, z), target, flags & 7);
    }

    @Override
    public Object getLegacyTileEntity(int x, int y, int z) {
        BlockPos pos = cursor.set(x, y, z);
        if (!level.isLoaded(pos)) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof LegacyBlockEntity legacy ? legacy.legacyTile() : null;
    }

    @Override
    public void setLegacyTileEntity(int x, int y, int z, Object legacyTileEntity) {
        if (level.getBlockEntity(new BlockPos(x, y, z)) instanceof LegacyBlockEntity legacy) {
            legacy.adopt(legacyTileEntity);
        }
    }

    @Override
    public void removeLegacyTileEntity(int x, int y, int z) {
        if (level.getBlockEntity(new BlockPos(x, y, z)) instanceof LegacyBlockEntity legacy) {
            legacy.discardLegacy();
        }
    }

    @Override
    public void markBlockForUpdate(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        level.sendBlockUpdated(pos, state, state, 3);
    }

    @Override
    public void markTileEntityChanged(int x, int y, int z) {
        BlockEntity blockEntity = level.getBlockEntity(cursor.set(x, y, z));
        if (blockEntity != null) {
            blockEntity.setChanged();
        }
    }

    @Override
    public void markBlockRangeForRenderUpdate(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (level.isClientSide) {
            retrofit.forge.client.ClientHooks.setBlocksDirty(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    @Override
    public void scheduleBlockUpdate(int x, int y, int z, Object legacyBlock, int delay, int priority) {
        Block block = modernBlock(legacyBlock);
        if (block != null && !level.isClientSide) {
            level.scheduleTick(new BlockPos(x, y, z), block, Math.max(0, delay),
                    TickPriority.byValue(Math.max(-3, Math.min(3, priority))));
        }
    }

    @Override
    public void notifyNeighbors(int x, int y, int z, Object legacyBlock) {
        Block block = modernBlock(legacyBlock);
        BlockPos pos = new BlockPos(x, y, z);
        level.updateNeighborsAt(pos, block != null ? block : level.getBlockState(pos).getBlock());
    }

    @Override
    public void notifyNeighbor(int x, int y, int z, Object neighborLegacyBlock) {
        Block block = modernBlock(neighborLegacyBlock);
        BlockPos pos = new BlockPos(x, y, z);
        level.neighborChanged(pos, block != null ? block : level.getBlockState(pos).getBlock(), pos);
    }

    // ---- Redstone ----

    @Override
    public int getIndirectPower(int x, int y, int z, int side) {
        return level.getSignal(new BlockPos(x, y, z), Direction.from3DDataValue(side));
    }

    @Override
    public int getDirectPowerInput(int x, int y, int z) {
        return level.getDirectSignalTo(new BlockPos(x, y, z));
    }

    @Override
    public int getStrongestIndirectPower(int x, int y, int z) {
        return level.getBestNeighborSignal(new BlockPos(x, y, z));
    }

    // ---- Light, height, biome, time, weather ----

    @Override
    public int getBlockLight(int x, int y, int z) {
        return level.getBrightness(LightLayer.BLOCK, cursor.set(x, y, z));
    }

    @Override
    public int getSkyLight(int x, int y, int z) {
        return level.getBrightness(LightLayer.SKY, cursor.set(x, y, z));
    }

    @Override
    public int getPackedLight(int x, int y, int z) {
        return getSkyLight(x, y, z) << 20 | getBlockLight(x, y, z) << 4;
    }

    @Override
    public boolean canSeeSky(int x, int y, int z) {
        return level.canSeeSky(cursor.set(x, y, z));
    }

    @Override
    public int getHeight(int x, int z) {
        return level.isLoaded(cursor.set(x, 0, z)) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) : 64;
    }

    @Override
    public String getBiome(int x, int z) {
        return level.getBiome(cursor.set(x, 64, z)).unwrapKey().map(key -> key.location().toString()).orElse("minecraft:plains");
    }

    @Override
    public long getTotalWorldTime() {
        return level.getGameTime();
    }

    @Override
    public long getDayTime() {
        return level.getDayTime();
    }

    @Override
    public long getSeed() {
        return level instanceof ServerLevel server ? server.getSeed() : 0;
    }

    @Override
    public boolean isRaining() {
        return level.isRaining();
    }

    @Override
    public boolean isThundering() {
        return level.isThundering();
    }

    @Override
    public boolean isClientSide() {
        return level.isClientSide;
    }

    // ---- Effects and spawning ----

    @Override
    public void playSound(double x, double y, double z, String legacySoundName, float volume, float pitch) {
        SoundEvent sound = legacySoundName == null ? null : LegacyEffects.sound(legacySoundName);
        if (sound == null) {
            return;
        }
        if (level.isClientSide) {
            level.playLocalSound(x, y, z, sound, SoundSource.BLOCKS, volume, pitch, false);
        } else {
            level.playSound(null, x, y, z, sound, SoundSource.BLOCKS, volume, pitch);
        }
    }

    @Override
    public void spawnParticle(String legacyParticleName, double x, double y, double z, double vx, double vy, double vz) {
        if (!level.isClientSide || legacyParticleName == null) {
            return; // 1.7.10 servers never showed particles either
        }
        ParticleOptions particle = LegacyEffects.particle(legacyParticleName, vx, vy, vz);
        if (particle != null) {
            level.addParticle(particle, x, y, z, vx, vy, vz);
        }
    }

    @Override
    public boolean spawnItem(double x, double y, double z, Object legacyItemStack, double motionX, double motionY, double motionZ, int pickupDelay) {
        if (level.isClientSide) {
            return false; // client-side copies would be ghosts; the server's item reaches clients
        }
        ItemStack stack = LegacyStacks.toModern(legacyItemStack);
        if (stack.isEmpty()) {
            return false;
        }
        ItemEntity item = new ItemEntity(level, x, y, z, stack, motionX, motionY, motionZ);
        item.setPickUpDelay(pickupDelay);
        boolean added = level.addFreshEntity(item);
        if (Boolean.getBoolean("retrofit.debugEntities")) {
            com.mojang.logging.LogUtils.getLogger().info("Legacy code dropped {} at {},{},{}: {}", stack, x, y, z, added ? "added" : "refused");
        }
        return added;
    }

    @Override
    public boolean spawnExperience(double x, double y, double z, int amount) {
        if (level.isClientSide || amount <= 0) {
            return false;
        }
        return level.addFreshEntity(new ExperienceOrb(level, x, y, z, amount));
    }

    @Override
    public boolean spawnLegacyEntity(Object legacyEntity) {
        if (level.isClientSide) {
            return false; // client-only legacy entities would be ghosts; the server's reach clients
        }
        try {
            String name = LegacyBridge.call("entityName", legacyEntity);
            net.minecraft.world.entity.EntityType<?> type = name == null ? null : LegacyEntityTypes.byName(name);
            net.minecraft.world.entity.Entity entity = type == null ? null : type.create(level);
            if (!(entity instanceof LegacyEntityCarrier carrier)) {
                return false;
            }
            carrier.core().adopt(legacyEntity);
            return level.addFreshEntity(entity);
        } catch (RuntimeException e) {
            LegacyErrors.report("entity spawn", "adding a legacy entity", e);
            return false;
        }
    }

    @Override
    public boolean spawnVanillaEntity(byte[] legacyNbt, String ownerUuid) {
        if (level.isClientSide) {
            return false;
        }
        try {
            net.minecraft.nbt.CompoundTag legacy = LegacyStacks.fromBytes(legacyNbt);
            legacy.remove("UUIDMost");
            legacy.remove("UUIDLeast");
            com.mojang.serialization.Dynamic<net.minecraft.nbt.Tag> fixed = net.minecraft.util.datafix.DataFixers.getDataFixer().update(
                    net.minecraft.util.datafix.fixes.References.ENTITY, new com.mojang.serialization.Dynamic<>(net.minecraft.nbt.NbtOps.INSTANCE, legacy),
                    99, net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion());
            net.minecraft.world.entity.Entity entity = net.minecraft.world.entity.EntityType.create((net.minecraft.nbt.CompoundTag) fixed.getValue(), level).orElse(null);
            if (entity == null) {
                return false;
            }
            if (ownerUuid != null && entity instanceof net.minecraft.world.entity.projectile.Projectile projectile) {
                projectile.setOwner(level.getPlayerByUUID(java.util.UUID.fromString(ownerUuid)));
            }
            return level.addFreshEntity(entity);
        } catch (RuntimeException e) {
            LegacyErrors.report("entity spawn", "converting a vanilla 1.7.10 entity", e);
            return false;
        }
    }

    @Override
    public void entityEvent(Object legacyEntity, int event) {
        net.minecraft.world.entity.Entity entity = LegacyEntityCore.hostOf(legacyEntity);
        if (entity != null && !level.isClientSide) {
            LegacyNetwork.sendEntityEvent(entity, event);
        }
    }

    @Override
    public void explode(Object legacyExploder, double x, double y, double z, float strength, boolean fire, boolean breakBlocks) {
        if (level.isClientSide) {
            return;
        }
        net.minecraft.world.entity.Entity exploder = LegacyEntityCore.hostOf(legacyExploder);
        Level.ExplosionInteraction interaction = !breakBlocks ? Level.ExplosionInteraction.NONE
                : exploder instanceof net.minecraft.world.entity.Mob ? Level.ExplosionInteraction.MOB : Level.ExplosionInteraction.TNT;
        level.explode(exploder, x, y, z, strength, fire, interaction);
    }

    @Override
    public void lightning(double x, double y, double z) {
        net.minecraft.world.entity.LightningBolt bolt = net.minecraft.world.entity.EntityType.LIGHTNING_BOLT.create(level);
        if (bolt != null && !level.isClientSide) {
            bolt.moveTo(x, y, z);
            level.addFreshEntity(bolt);
        }
    }

    // ---- Collision ----

    @Override
    public double[] getCollisionBoxes(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        AABB region = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        double[] boxes = new double[24];
        int count = 0;
        for (VoxelShape shape : level.getBlockCollisions(null, region)) {
            for (AABB box : shape.toAabbs()) {
                if (!box.intersects(region)) {
                    continue;
                }
                if (count + 6 > boxes.length) {
                    boxes = Arrays.copyOf(boxes, boxes.length * 2);
                }
                boxes[count++] = box.minX;
                boxes[count++] = box.minY;
                boxes[count++] = box.minZ;
                boxes[count++] = box.maxX;
                boxes[count++] = box.maxY;
                boxes[count++] = box.maxZ;
            }
        }
        return Arrays.copyOf(boxes, count);
    }
}
