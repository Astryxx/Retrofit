package retrofit.forge.world;

import net.minecraft.world.item.ItemStack;

/** A 1.20.1 item standing in for a legacy item: either a {@link LegacyItem} or a {@link LegacyBlockItem}. */
public interface LegacyStackItem {
    /** The legacy Item object. */
    Object legacyItem();

    /** Whether the legacy item uses its damage value for subtypes. */
    boolean hasSubtypes();

    /** The legacy damage value of a stack. */
    int meta(ItemStack stack);

    ItemStack stackOf(int meta);

    /** 1: the legacy item ticks in inventories (onUpdate); 2: while worn (onArmorTick). */
    default int tickFlags() {
        return 0;
    }
}
