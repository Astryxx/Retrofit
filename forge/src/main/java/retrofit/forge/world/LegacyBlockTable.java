package retrofit.forge.world;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.BlockStateData;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Two-way table between vanilla 1.7.10 blocks (numeric id + metadata) and real 1.20.1 block
 * states, so legacy code reads and places modern vanilla blocks: a mod placing planks:0 gets
 * oak planks, and reading oak planks back gives planks:0.
 *
 * Built from Mojang's own conversion data, the same tables 1.20.1 uses to upgrade pre-1.13
 * worlds ({@link BlockStateData}, then the data fixer up to the current version). Modern blocks
 * that didn't exist in 1.7.10 map to the closest stand-in (stone for full cubes, air otherwise)
 * when legacy code reads them.
 *
 * Modern states are keyed by identity, not numeric state id: Forge reassigns state ids when a
 * client joins a server (registry sync), but the BlockState objects stay the same.
 */
public final class LegacyBlockTable {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Data version of 1.13's flattening (17w47a), which BlockStateData's names are in. */
    private static final int FLATTENING_DATA_VERSION = 1451;
    private static final int LEGACY_IDS = 256;
    private static final int STONE = 1 << 4;
    private static final int AIR = 0;

    private static volatile LegacyBlockTable instance;

    /** (legacy id << 4 | meta) -> 1.20.1 state, or null if no vanilla block uses that id. */
    private final BlockState[] toModern = new BlockState[LEGACY_IDS << 4];
    /** 1.20.1 state -> (legacy id << 4 | meta), for every registered state. */
    private final Map<BlockState, Integer> toLegacy = new IdentityHashMap<>();
    /** Legacy id -> legacy Block object (vanilla only). */
    private final Object[] legacyBlocks = new Object[LEGACY_IDS];
    /** Legacy Block object -> legacy id (vanilla only). */
    private final Map<Object, Integer> legacyIds = new IdentityHashMap<>();

    private LegacyBlockTable() {
        long start = System.nanoTime();
        int currentVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        int mapped = 0;
        for (int id = 0; id < LEGACY_IDS; id++) {
            Object legacy = LegacyBridge.blockById(id);
            if (legacy == null || !LegacyBridge.blockName(legacy).startsWith("minecraft:")) {
                continue; // unused id, or a legacy mod block (those are LegacyBlocks)
            }
            legacyBlocks[id] = legacy;
            legacyIds.put(legacy, id);
            for (int meta = 0; meta < 16; meta++) {
                BlockState state;
                try {
                    state = upgrade(id << 4 | meta, currentVersion);
                } catch (RuntimeException e) {
                    LOGGER.debug("No 1.20.1 equivalent for legacy block {}:{}", id, meta, e);
                    continue;
                }
                if (state != null) {
                    toModern[id << 4 | meta] = state;
                    mapped++;
                }
            }
        }

        // Lowest metadata wins when several legacy values flatten to the same state.
        for (int combined = 0; combined < toModern.length; combined++) {
            BlockState state = toModern[combined];
            if (state != null) {
                toLegacy.putIfAbsent(state, combined);
            }
        }
        int approximated = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            Integer viaDefault = toLegacy.get(block.defaultBlockState());
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                if (toLegacy.containsKey(state)) {
                    continue;
                }
                if (viaDefault != null) {
                    toLegacy.put(state, viaDefault); // e.g. a block state property 1.7.10 didn't have
                } else {
                    toLegacy.put(state, state.isAir() || !state.canOcclude() ? AIR : STONE);
                    approximated++;
                }
            }
        }
        LOGGER.info("Vanilla block table: {} legacy id:meta values map to 1.20.1 blocks; {} modern-only block states read as stone/air; built in {} ms",
                mapped, approximated, (System.nanoTime() - start) / 1_000_000);
    }

    public static LegacyBlockTable get() {
        LegacyBlockTable table = instance;
        if (table == null) {
            synchronized (LegacyBlockTable.class) {
                table = instance;
                if (table == null) {
                    table = new LegacyBlockTable();
                    instance = table;
                }
            }
        }
        return table;
    }

    @SuppressWarnings("unchecked")
    private static BlockState upgrade(int combined, int currentVersion) {
        Dynamic<Tag> flattened = (Dynamic<Tag>) BlockStateData.getTag(combined);
        Dynamic<Tag> current = DataFixers.getDataFixer().update(References.BLOCK_STATE, flattened, FLATTENING_DATA_VERSION, currentVersion);
        if (!(current.getValue() instanceof CompoundTag tag) || !ResourceLocation.isValidResourceLocation(tag.getString("Name"))) {
            return null; // e.g. "%%FILTER_ME%%", Mojang's placeholder for ids it dropped
        }
        BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag);
        // Unknown ids and invalid metadata flatten to air; only keep air for legacy air itself.
        return state.isAir() && combined >> 4 != 0 ? null : state;
    }

    private int[] modernBlockIds;
    private Object solidStandIn;
    private Object openStandIn;

    /** {solid, non-solid} legacy ids of Retrofit's stand-ins for 1.20.1 mods' blocks (0: none). */
    private int[] modernBlockIds() {
        int[] ids = modernBlockIds;
        if (ids == null) {
            try {
                ids = LegacyBridge.call("modernBlockIds");
            } catch (RuntimeException e) {
                ids = new int[2];
            }
            modernBlockIds = ids;
        }
        return ids;
    }

    /**
     * The legacy (id << 4 | meta) a modern state reads as: vanilla states as their 1.7.10 selves
     * (1.20.1-only vanilla blocks as stone), 1.20.1 mods' blocks as Retrofit's stand-ins.
     */
    public int legacyOf(BlockState state) {
        Integer combined = toLegacy.get(state);
        if (combined != null) {
            return combined;
        }
        net.minecraft.resources.ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id != null && !id.getNamespace().equals("minecraft")) {
            int[] standIns = modernBlockIds();
            int standIn = state.canOcclude() ? standIns[0] : standIns[1];
            if (standIn > 0) {
                return standIn << 4;
            }
        }
        return STONE; // a vanilla state 1.7.10 didn't have
    }

    public Object legacyBlock(int legacyId) {
        if (legacyId >= legacyBlocks.length) { // a mod block, or a stand-in for a 1.20.1 mod's
            int[] standIns = modernBlockIds();
            if (legacyId == standIns[0] && solidStandIn != null) {
                return solidStandIn;
            }
            if (legacyId == standIns[1] && openStandIn != null) {
                return openStandIn;
            }
            Object block = legacyId < 4096 ? LegacyBridge.call("blockById", legacyId) : null;
            if (legacyId == standIns[0]) {
                solidStandIn = block;
            } else if (legacyId == standIns[1]) {
                openStandIn = block;
            }
            return block != null ? block : legacyBlocks[0];
        }
        Object block = legacyBlocks[legacyId];
        return block != null ? block : legacyBlocks[0];
    }

    /** The 1.20.1 state for any legacy block (a mod block or a vanilla one), or null. */
    public static BlockState modernState(Object legacyBlock, int meta) {
        LegacyBlock modBlock = LegacyContent.byLegacy(legacyBlock);
        if (modBlock != null) {
            return modBlock.defaultBlockState().setValue(LegacyContent.META, meta & 15);
        }
        return get().modernOf(legacyBlock, meta);
    }

    /** The modern state for a vanilla legacy block, or null if it isn't vanilla. */
    public BlockState modernOf(Object legacyBlock, int meta) {
        Integer legacyId = legacyIds.get(legacyBlock);
        if (legacyId == null) {
            return null;
        }
        int id = legacyId;
        BlockState state = toModern[id << 4 | (meta & 15)];
        if (state == null) {
            state = toModern[id << 4]; // metadata 1.7.10 never used: fall back to the block's first state
        }
        return state != null ? state : (id == 0 ? Blocks.AIR.defaultBlockState() : null);
    }
}
