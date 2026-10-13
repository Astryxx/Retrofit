package retrofit.forge.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The 1.20.1 item standing in for one legacy mod item. 1.7.10 items used the damage value for
 * subtypes; for items with subtypes that value lives in the stack's {@value #META_TAG} tag (for
 * damageable items it is the ordinary 1.20.1 damage).
 */
public class LegacyItem extends Item {
    public static final String META_TAG = "LegacyMeta";

    private final Object legacyItem;
    private final boolean hasSubtypes;
    private final Map<Integer, String> translationKeys = new ConcurrentHashMap<>();

    /** @param info see {@link LegacyBridge#itemInfo} */
    public LegacyItem(Object legacyItem, int[] info) {
        super(properties(info));
        this.legacyItem = legacyItem;
        this.hasSubtypes = info[2] != 0;
    }

    private static Item.Properties properties(int[] info) {
        Item.Properties properties = new Item.Properties();
        int maxDamage = info[1];
        boolean hasSubtypes = info[2] != 0;
        if (maxDamage > 0 && !hasSubtypes) {
            properties.durability(maxDamage); // implies a stack size of 1, as in 1.7.10
        } else {
            properties.stacksTo(Math.max(1, Math.min(64, info[0])));
        }
        return properties;
    }

    public Object legacyItem() {
        return legacyItem;
    }

    public boolean hasSubtypes() {
        return hasSubtypes;
    }

    /** The legacy damage value of a stack: its subtype, or its damage for damageable items. */
    public int meta(ItemStack stack) {
        if (!hasSubtypes) {
            return stack.getDamageValue();
        }
        CompoundTag tag = stack.getTag();
        return tag == null ? 0 : tag.getInt(META_TAG);
    }

    public ItemStack stackOf(int meta) {
        ItemStack stack = new ItemStack(this);
        if (hasSubtypes && meta != 0) {
            stack.getOrCreateTag().putInt(META_TAG, meta);
        }
        return stack;
    }

    /** Legacy items name each subtype separately (getUnlocalizedName(ItemStack)). */
    @Override
    public String getDescriptionId(ItemStack stack) {
        int meta = hasSubtypes ? meta(stack) : 0;
        return translationKeys.computeIfAbsent(meta, m -> LegacyBridge.itemTranslationKey(legacyItem, m));
    }
}
