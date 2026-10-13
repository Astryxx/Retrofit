package retrofit.forge.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.registries.RegisterEvent;
import org.slf4j.Logger;
import retrofit.forge.RetrofitMod;
import retrofit.host.LegacyRuntime;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Registers every block added by legacy mods as a 1.20.1 block (with a block item), plus one
 * block entity type that hosts legacy tile entities. Also owns the legacy World for each level.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class LegacyContent {
    /** Legacy block metadata (0-15) carried as a block state property. */
    public static final IntegerProperty META = IntegerProperty.create("meta", 0, 15);

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<LegacyBlock> BLOCKS = new ArrayList<>();
    private static final List<LegacyItem> ITEMS = new ArrayList<>();
    private static final Map<LegacyBlock, String> LEGACY_NAMES = new IdentityHashMap<>();
    private static final Map<Object, LegacyBlock> BY_LEGACY = new IdentityHashMap<>();
    private static final Map<Level, Object> WORLDS = new WeakHashMap<>();

    private static BlockEntityType<LegacyBlockEntity> blockEntityType;

    private LegacyContent() {
    }

    public static List<LegacyBlock> blocks() {
        return java.util.Collections.unmodifiableList(BLOCKS);
    }

    public static List<LegacyItem> items() {
        return java.util.Collections.unmodifiableList(ITEMS);
    }

    public static BlockEntityType<LegacyBlockEntity> blockEntityType() {
        return blockEntityType;
    }

    /** The 1.20.1 block standing in for a legacy block, or null. */
    public static LegacyBlock byLegacy(Object legacyBlock) {
        return BY_LEGACY.get(legacyBlock);
    }

    /** The legacy World view of a level, created on first use. */
    public static synchronized Object legacyWorld(Level level) {
        return WORLDS.computeIfAbsent(level, l -> LegacyBridge.createWorld(new LevelHostWorld(l), legacyDimension(l)));
    }

    private static int legacyDimension(Level level) {
        if (level.dimension() == Level.NETHER) {
            return -1;
        }
        return level.dimension() == Level.END ? 1 : 0;
    }

    @SubscribeEvent
    public static void onRegister(RegisterEvent event) {
        LegacyRuntime runtime = RetrofitMod.runtime();
        if (runtime == null) {
            return;
        }
        event.register(Registries.BLOCK, helper -> {
            try {
                LegacyBridge.init(runtime);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Retrofit could not connect to the legacy bridge", e);
            }
            for (String name : LegacyBridge.modBlockNames()) {
                Object legacyBlock = LegacyBridge.block(name);
                LegacyBlock block = new LegacyBlock(legacyBlock, LegacyBridge.hasTileEntity(legacyBlock, 0),
                        LegacyBridge.blockRenderInfo(legacyBlock));
                helper.register(toResourceLocation(name), block);
                BLOCKS.add(block);
                LEGACY_NAMES.put(block, name);
                BY_LEGACY.put(legacyBlock, block);
            }
            LOGGER.info("Registered {} legacy blocks", BLOCKS.size());
        });
        event.register(Registries.ITEM, helper -> {
            for (LegacyBlock block : BLOCKS) {
                helper.register(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block),
                        new LegacyBlockItem(block, LegacyBridge.item(LEGACY_NAMES.get(block))));
            }
            for (String name : LegacyBridge.modItemNames()) {
                Object legacyItem = LegacyBridge.item(name);
                LegacyItem item = new LegacyItem(legacyItem, LegacyBridge.itemInfo(legacyItem));
                helper.register(toResourceLocation(name), item);
                ITEMS.add(item);
            }
            LOGGER.info("Registered {} legacy items", ITEMS.size());
        });
        event.register(Registries.BLOCK_ENTITY_TYPE, helper -> {
            LegacyBlock[] withTiles = BLOCKS.stream().filter(LegacyBlock::hasTileEntity).toArray(LegacyBlock[]::new);
            blockEntityType = BlockEntityType.Builder.of(LegacyBlockEntity::new, withTiles).build(null);
            helper.register(new ResourceLocation(RetrofitMod.MOD_ID, "legacy_tile"), blockEntityType);
        });
    }

    /** Builds the vanilla block table during parallel mod setup, not on the first legacy tick. */
    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        if (RetrofitMod.runtime() != null) {
            LegacyBlockTable.get();
        }
    }

    @SubscribeEvent
    public static void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            for (LegacyBlock block : BLOCKS) {
                if (block.asItem() instanceof LegacyBlockItem item && item.hasSubtypes()) {
                    for (int meta : LegacyBridge.itemSubtypes(item.legacyItem())) {
                        event.accept(item.stackOf(meta));
                    }
                } else {
                    event.accept(block);
                }
            }
        } else if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            for (LegacyItem item : ITEMS) {
                for (int meta : item.hasSubtypes() ? LegacyBridge.itemSubtypes(item.legacyItem()) : new int[]{0}) {
                    event.accept(item.stackOf(meta));
                }
            }
        }
    }

    /** Legacy names are "modid:name" but may use characters 1.20.1 forbids (e.g. upper case). */
    static ResourceLocation toResourceLocation(String legacyName) {
        String cleaned = legacyName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.:/-]", "_");
        int colon = cleaned.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : cleaned.substring(0, colon);
        String path = cleaned.substring(colon + 1).replace(':', '_');
        return new ResourceLocation(namespace, path);
    }
}
