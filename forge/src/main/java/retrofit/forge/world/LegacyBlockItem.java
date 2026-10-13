package retrofit.forge.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The item form of a legacy block. 1.7.10 block items often carried the block's metadata as
 * their damage value (sixteen colours of one block, several wood types); for those it lives in
 * the stack's {@value LegacyItem#META_TAG} tag, names the stack, and is placed as the block's
 * {@link LegacyContent#META}.
 */
public class LegacyBlockItem extends BlockItem implements LegacyStackItem {
    private final Object legacyItem;
    private final boolean hasSubtypes;
    private final Map<Integer, String> translationKeys = new ConcurrentHashMap<>();

    public LegacyBlockItem(LegacyBlock block, Object legacyItem) {
        super(block, new Properties());
        this.legacyItem = legacyItem;
        this.hasSubtypes = legacyItem != null && LegacyBridge.itemInfo(legacyItem)[2] != 0;
    }

    @Override
    public Object legacyItem() {
        return legacyItem;
    }

    @Override
    public boolean hasSubtypes() {
        return hasSubtypes;
    }

    @Override
    public int meta(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return hasSubtypes && tag != null ? tag.getInt(LegacyItem.META_TAG) & 15 : 0;
    }

    @Override
    public ItemStack stackOf(int meta) {
        ItemStack stack = new ItemStack(this);
        if (hasSubtypes && meta != 0) {
            stack.getOrCreateTag().putInt(LegacyItem.META_TAG, meta);
        }
        return stack;
    }

    @Override
    protected BlockState getPlacementState(BlockPlaceContext context) {
        BlockState state = super.getPlacementState(context);
        return state == null || !hasSubtypes ? state : state.setValue(LegacyContent.META, meta(context.getItemInHand()));
    }

    @Override
    public String getDescriptionId(ItemStack stack) {
        if (legacyItem == null) {
            return super.getDescriptionId(stack);
        }
        int meta = meta(stack);
        return translationKeys.computeIfAbsent(meta, m -> LegacyBridge.itemTranslationKey(legacyItem, m));
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(LegacyItem.CLIENT_EXTENSIONS.get());
    }
}
