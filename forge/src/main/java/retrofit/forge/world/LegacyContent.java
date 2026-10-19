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
import net.minecraft.world.item.ItemStack;
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
    private static final List<LegacyArmorItem> ARMOR = new ArrayList<>();
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

    public static List<LegacyArmorItem> armor() {
        return java.util.Collections.unmodifiableList(ARMOR);
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

    /** Whether legacy code has used this level yet (without creating its legacy world). */
    public static synchronized boolean hasLegacyWorld(Level level) {
        return WORLDS.containsKey(level);
    }

    /** The legacy dimension id of a level: the mods' own (see LegacyDimensions), -1 nether, 1 end, 0 everything else. */
    static int legacyDimension(Level level) {
        return LegacyDimensions.legacyDimension(level);
    }

    @SubscribeEvent
    public static void onRegister(RegisterEvent event) {
        LegacyRuntime runtime = RetrofitMod.runtime();
        if (runtime == null) {
            return;
        }
        try {
            LegacyBridge.init(runtime); // at the first registry: fluids come before blocks
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retrofit could not connect to the legacy bridge", e);
        }
        LegacyFluids.register(event);
        LegacyEnchantments.register(event);
        registerCreativeTabs(event);
        event.register(Registries.BLOCK, helper -> {
            try {
                LegacyBridge.init(runtime);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Retrofit could not connect to the legacy bridge", e);
            }
            for (String name : LegacyBridge.modBlockNames()) {
                if (name.equals("retrofit:modern_block") || name.equals("retrofit:modern_open_block")) {
                    continue; // Retrofit's stand-ins for 1.20.1 mods' blocks in legacy code
                }
                Object legacyBlock = LegacyBridge.block(name);
                LegacyBlock block = LegacyBlock.create(legacyBlock, LegacyBridge.blockRenderInfo(legacyBlock),
                        LegacyBridge.call("blockInfo", legacyBlock));
                helper.register(toResourceLocation(name), block);
                BLOCKS.add(block);
                LEGACY_NAMES.put(block, name);
                BY_LEGACY.put(legacyBlock, block);
            }
            LOGGER.info("Registered {} legacy blocks", BLOCKS.size());
            java.util.Set<ResourceLocation> taken = new java.util.HashSet<>();
            for (LegacyBlock block : BLOCKS) {
                taken.add(toResourceLocation(LEGACY_NAMES.get(block)));
            }
            LegacyWoodSets.registerBlocks(helper, taken);
        });
        event.register(Registries.ITEM, helper -> {
            for (LegacyBlock block : BLOCKS) {
                helper.register(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block),
                        new LegacyBlockItem(block, LegacyBridge.item(LEGACY_NAMES.get(block))));
            }
            LegacyWoodSets.registerItems(helper);
            for (String name : LegacyBridge.modItemNames()) {
                if (name.equals(ModernItems.LEGACY_NAME)) {
                    continue; // carries 1.20.1 items through legacy code; never an item of its own here
                }
                Object legacyItem = LegacyBridge.item(name);
                int[] armor = LegacyBridge.call("armorInfo", legacyItem);
                if (armor[0] >= 0) {
                    LegacyArmorItem item = new LegacyArmorItem(legacyItem, LegacyBridge.itemInfo(legacyItem), armor);
                    helper.register(toResourceLocation(name), item);
                    ARMOR.add(item);
                    continue;
                }
                LegacyItem item = new LegacyItem(legacyItem, LegacyBridge.itemInfo(legacyItem));
                helper.register(toResourceLocation(name), item);
                ITEMS.add(item);
            }
            LOGGER.info("Registered {} legacy items ({} armor pieces)", ITEMS.size() + ARMOR.size(), ARMOR.size());
        });
        event.register(Registries.MENU, helper ->
                helper.register(new ResourceLocation(RetrofitMod.MOD_ID, "legacy_menu"), LegacyMenu.type()));
        event.register(Registries.RECIPE_SERIALIZER, helper ->
                helper.register(new ResourceLocation(RetrofitMod.MOD_ID, "legacy_crafting"), LegacyRecipes.CRAFTING));
        event.register(Registries.RECIPE_SERIALIZER, helper -> {
            helper.register(new ResourceLocation(RetrofitMod.MOD_ID, "legacy_shaped"), LegacyRecipeDisplays.SHAPED);
            helper.register(new ResourceLocation(RetrofitMod.MOD_ID, "legacy_shapeless"), LegacyRecipeDisplays.SHAPELESS);
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
            event.enqueueWork(LegacyNetwork::register);
            event.enqueueWork(LegacyFluids::registerModernFluids);
        }
    }

    /** 1.7.10's own creative tabs (index 0 to 10) and where their mod items go in 1.20.1's. */
    private static final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.item.CreativeModeTab>, int[]> VANILLA_TABS = java.util.Map.of(
            CreativeModeTabs.BUILDING_BLOCKS, new int[]{0}, CreativeModeTabs.FUNCTIONAL_BLOCKS, new int[]{1},
            CreativeModeTabs.REDSTONE_BLOCKS, new int[]{2}, CreativeModeTabs.TOOLS_AND_UTILITIES, new int[]{3, 7},
            CreativeModeTabs.INGREDIENTS, new int[]{4, 10}, CreativeModeTabs.FOOD_AND_DRINKS, new int[]{6, 9},
            CreativeModeTabs.COMBAT, new int[]{8});

    /** The mods' own creative tabs (registered with the other content). */
    static void registerCreativeTabs(RegisterEvent event) {
        event.register(Registries.CREATIVE_MODE_TAB, helper -> {
            Object[][] tabs;
            try {
                tabs = LegacyBridge.call("legacyCreativeTabs");
            } catch (RuntimeException e) {
                LOGGER.error("Could not list the legacy creative tabs", e);
                return;
            }
            for (Object[] tab : tabs) {
                int index = (Integer) tab[0];
                String label = (String) tab[1];
                helper.register(new ResourceLocation(RetrofitMod.MOD_ID, "tab_" + toResourceLocation(label).getPath().replace('.', '_')),
                        net.minecraft.world.item.CreativeModeTab.builder()
                                .title(net.minecraft.network.chat.Component.translatable("itemGroup." + label))
                                .icon(() -> creativeTabIcon(index))
                                .displayItems((parameters, output) -> addTabContents(index, output::accept))
                                .build());
            }
            LOGGER.info("Registered {} legacy creative tabs", tabs.length);
        });
    }

    private static ItemStack creativeTabIcon(int index) {
        try {
            ItemStack icon = LegacyStacks.toModern(LegacyBridge.call("legacyCreativeTabIcon", index));
            return icon.isEmpty() ? new ItemStack(net.minecraft.world.item.Items.BOOK) : icon;
        } catch (RuntimeException e) {
            return new ItemStack(net.minecraft.world.item.Items.BOOK);
        }
    }

    /** Client: a legacy tab's items, each once (1.20.1 refuses a stack twice in a tab). */
    private static void addTabContents(int index, java.util.function.Consumer<ItemStack> output) {
        Object[] stacks;
        try {
            stacks = LegacyBridge.call("legacyCreativeTabContents", index);
        } catch (RuntimeException e) {
            LegacyErrors.report("creative tab " + index, "listing its items", e);
            return;
        }
        java.util.Set<ItemStack> seen = net.minecraft.world.item.ItemStackLinkedSet.createTypeAndTagSet();
        for (Object legacy : stacks) {
            ItemStack stack = LegacyStacks.toModern(legacy);
            if (!stack.isEmpty() && seen.add(stack)) {
                stack.setCount(1);
                output.accept(stack);
            }
        }
    }

    @SubscribeEvent
    public static void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        int[] legacyTabs = VANILLA_TABS.get(event.getTabKey());
        if (legacyTabs != null && LegacyBridge.isReady()) {
            for (int index : legacyTabs) {
                addTabContents(index, stack -> {
                    try {
                        event.accept(stack);
                    } catch (IllegalArgumentException | IllegalStateException duplicate) {
                        // already there (from another legacy tab mapped to this one)
                    }
                });
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
