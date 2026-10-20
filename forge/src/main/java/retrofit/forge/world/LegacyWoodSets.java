package retrofit.forge.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Wood types of legacy mods under the names 1.20.1 mods look for. 1.7.10 mods keep several woods
 * in one block (metadata), named anything ("blockWoodenDevice"); 1.20.1 mods that add furniture,
 * doors and so on for every wood type (Every Compat and the Moonlight library under it) find
 * woods by "<wood>_planks" and "<wood>_log" blocks. Each legacy wood (a log its mod crafts into
 * planks) gets those two, in the legacy mod's namespace ("thaumcraft:greatwood_planks").
 *
 * They are names, not new blocks: their items are the 1.20.1 form of the legacy planks and log
 * (legacy code gets the legacy stacks, and legacy planks come back as these), placing one places
 * the legacy block, and the blocks turn into the legacy block if anything sets them directly.
 */
public final class LegacyWoodSets {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<Wood> WOODS = new ArrayList<>();
    /** (legacy item, meta) -> alias item */
    private static final Map<Object, Map<Integer, Item>> ALIASES = new HashMap<>();
    private static final Set<String> NOISE = Set.of("tile", "item", "block", "blocks", "planks", "plank", "wood", "wooden", "name", "log", "logs");

    private LegacyWoodSets() {
    }

    record Wood(String namespace, String name, Object planksBlock, int planksMeta, Object logBlock, int logMeta) {
    }

    /** The alias item standing for a legacy item's subtype, or null. */
    static Item alias(Object legacyItem, int meta) {
        Map<Integer, Item> byMeta = ALIASES.get(legacyItem);
        return byMeta == null ? null : byMeta.get(meta);
    }

    static List<Wood> woods() {
        return WOODS;
    }

    /** A wood's name from the planks' unlocalized name ("tile.planks_greatwood" -> "greatwood"), the same on every side. */
    private static String woodName(String unlocalized, String namespace) {
        String spaced = unlocalized.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        List<String> words = new ArrayList<>();
        for (String word : spaced.split("[^a-z0-9]+")) {
            if (!word.isEmpty() && !NOISE.contains(word) && !word.equals(namespace.toLowerCase(Locale.ROOT))) {
                words.add(word);
            }
        }
        return String.join("_", words);
    }

    /** Blocks (after the legacy blocks): reads the legacy wood types and registers "<wood>_planks" and "<wood>_log". */
    static void registerBlocks(RegisterEvent.RegisterHelper<Block> helper, Set<ResourceLocation> taken) {
        Object[][] rows;
        try {
            rows = LegacyBridge.call("woodTypes");
        } catch (RuntimeException e) {
            LOGGER.error("Could not read the legacy wood types", e);
            return;
        }
        Set<ResourceLocation> used = new HashSet<>(taken);
        for (Object[] row : rows) {
            String namespace = LegacyContent.toResourceLocation((String) row[0] + ":x").getNamespace();
            String name = woodName((String) row[5], (String) row[0]);
            if (name.isEmpty() || Character.isDigit(name.charAt(0))) {
                name = "wood_" + (name.isEmpty() ? String.valueOf(row[2]) : name);
            }
            String unique = name;
            for (int n = 2; used.contains(new ResourceLocation(namespace, unique + "_planks"))
                    || used.contains(new ResourceLocation(namespace, unique + "_log")); n++) {
                unique = name + "_" + n;
            }
            Wood wood = new Wood(namespace, unique, row[1], (Integer) row[2], row[3], (Integer) row[4]);
            LegacyBlock planks = LegacyContent.byLegacy(wood.planksBlock());
            LegacyBlock log = LegacyContent.byLegacy(wood.logBlock());
            if (planks == null || log == null) {
                continue;
            }
            ResourceLocation planksId = new ResourceLocation(namespace, unique + "_planks");
            ResourceLocation logId = new ResourceLocation(namespace, unique + "_log");
            helper.register(planksId, new AliasBlock(planks, wood.planksMeta()));
            helper.register(logId, new AliasBlock(log, wood.logMeta()));
            used.add(planksId);
            used.add(logId);
            WOODS.add(wood);
        }
        LOGGER.info("Legacy wood types under 1.20.1 names: {}", WOODS.stream().map(w -> w.namespace() + ":" + w.name()).toList());
    }

    /** Items for the alias blocks. */
    static void registerItems(RegisterEvent.RegisterHelper<Item> helper) {
        for (Wood wood : WOODS) {
            for (boolean planks : new boolean[]{true, false}) {
                ResourceLocation id = new ResourceLocation(wood.namespace(), wood.name() + (planks ? "_planks" : "_log"));
                Block block = ForgeRegistries.BLOCKS.getValue(id);
                if (!(block instanceof AliasBlock alias) || !(alias.original.asItem() instanceof LegacyBlockItem original)) {
                    continue;
                }
                AliasItem item = new AliasItem(alias, original, alias.meta);
                helper.register(id, item);
                ALIASES.computeIfAbsent(original.legacyItem(), k -> new HashMap<>()).put(alias.meta, item);
            }
        }
    }

    /** A legacy block's subtype under a 1.20.1 name; it never stays in the world (it becomes the legacy block). */
    public static final class AliasBlock extends Block {
        final LegacyBlock original;
        final int meta;

        public LegacyBlock original() {
            return original;
        }

        public int meta() {
            return meta;
        }

        AliasBlock(LegacyBlock original, int meta) {
            super(BlockBehaviour.Properties.copy(original));
            this.original = original;
            this.meta = meta;
        }

        BlockState originalState() {
            return original.defaultBlockState().setValue(LegacyContent.META, meta);
        }

        @Override
        public void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
            super.onPlace(state, level, pos, old, moving);
            if (!level.isClientSide) {
                level.setBlock(pos, originalState(), 3);
            }
        }

        @Override
        public String getDescriptionId() {
            return original.asItem() instanceof LegacyBlockItem item ? item.getDescriptionId(item.stackOf(meta)) : original.getDescriptionId();
        }
    }

    /** The 1.20.1 item of a legacy planks or log subtype: legacy code sees the legacy stack. */
    static final class AliasItem extends BlockItem implements LegacyStackItem {
        private final LegacyBlockItem original;
        private final int meta;

        AliasItem(AliasBlock block, LegacyBlockItem original, int meta) {
            super(block, new Properties());
            this.original = original;
            this.meta = meta;
        }

        @Override
        public Object legacyItem() {
            return original.legacyItem();
        }

        @Override
        public boolean hasSubtypes() {
            return original.hasSubtypes();
        }

        @Override
        public int meta(ItemStack stack) {
            return meta;
        }

        @Override
        public ItemStack stackOf(int meta) {
            return meta == this.meta ? new ItemStack(this) : original.stackOf(meta);
        }

        @Override
        public String getDescriptionId(ItemStack stack) {
            return original.getDescriptionId(original.stackOf(meta));
        }

        /** Placing is the legacy ItemBlock's, as for the legacy item itself. */
        @Override
        public InteractionResult useOn(UseOnContext context) {
            return LegacyItemUse.useOn(context);
        }

        @Override
        public int getBurnTime(ItemStack stack, @javax.annotation.Nullable net.minecraft.world.item.crafting.RecipeType<?> type) {
            return LegacyRecipes.burnTime(stack);
        }

        @Override
        public void initializeClient(java.util.function.Consumer<net.minecraftforge.client.extensions.common.IClientItemExtensions> consumer) {
            consumer.accept(LegacyItem.CLIENT_EXTENSIONS.get());
        }
    }
}
