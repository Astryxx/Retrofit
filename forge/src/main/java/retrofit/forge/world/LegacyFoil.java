package retrofit.forge.world;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The enchantment glint of legacy items (Item.hasEffect: enchanted stacks, and items that always
 * shimmer, Witchery's urns and spectral stones), for Item.isFoil. Asked per item, damage and tag,
 * as item colours are; 1.7.10 only knew it on the client, so elsewhere it is never asked.
 */
final class LegacyFoil {
    private static final int MAX_CACHED = 4096;
    private static final Map<Key, Boolean> FOIL = new ConcurrentHashMap<>();
    private static volatile boolean failed;

    private record Key(Item item, int meta, int tag) {
    }

    private LegacyFoil() {
    }

    static boolean isFoil(ItemStack stack, LegacyStackItem item) {
        if (failed || item.legacyItem() == null || !net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            return false;
        }
        Key key = new Key(stack.getItem(), item.meta(stack), stack.hasTag() ? stack.getTag().hashCode() : 0);
        Boolean known = FOIL.get(key);
        if (known == null) {
            try {
                known = LegacyBridge.<Boolean>call("itemHasEffect", LegacyStacks.toLegacy(stack));
            } catch (RuntimeException e) {
                failed = true;
                LegacyBridge.reportClientFailure("item glint", e);
                return false;
            }
            if (FOIL.size() > MAX_CACHED) {
                FOIL.clear();
            }
            FOIL.put(key, known);
        }
        return known;
    }
}
