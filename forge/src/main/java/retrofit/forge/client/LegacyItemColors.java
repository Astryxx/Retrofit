package retrofit.forge.client;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import retrofit.forge.RetrofitMod;
import retrofit.forge.world.LegacyBridge;
import retrofit.forge.world.LegacyStackItem;
import retrofit.forge.world.LegacyStacks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Legacy items' colours (getColorFromItemStack) for each render pass: the item models have a
 * layer per pass, tinted with tint index = pass (Thaumcraft's aspect-coloured phials and wisps,
 * potions, dyed things). Colours often come from the stack's NBT, so they are cached per item,
 * damage and tag.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class LegacyItemColors {
    private static final int MAX_CACHED = 4096;
    private static final Map<Key, Integer> COLORS = new ConcurrentHashMap<>();
    private static boolean failed;

    private LegacyItemColors() {
    }

    private record Key(Item item, int meta, int tag, int pass) {
    }

    @SubscribeEvent
    public static void onItemColors(RegisterColorHandlersEvent.Item event) {
        if (RetrofitMod.runtime() == null) {
            return;
        }
        Item[] items = ForgeRegistries.ITEMS.getValues().stream()
                .filter(item -> item instanceof LegacyStackItem legacy && legacy.legacyItem() != null).toArray(Item[]::new);
        event.register(LegacyItemColors::color, items);
    }

    private static int color(ItemStack stack, int pass) {
        if (failed || pass < 0 || !(stack.getItem() instanceof LegacyStackItem item)) {
            return -1;
        }
        Key key = new Key(stack.getItem(), item.meta(stack), stack.hasTag() ? stack.getTag().hashCode() : 0, pass);
        Integer cached = COLORS.get(key);
        if (cached != null) {
            return cached;
        }
        int color;
        try {
            int legacy = LegacyBridge.<Integer>call("itemColor", LegacyStacks.toLegacy(stack), pass);
            color = (legacy & 0xFFFFFF) == 0xFFFFFF ? -1 : legacy | 0xFF000000;
        } catch (RuntimeException e) {
            failed = true;
            LegacyBridge.reportClientFailure("item colours", e);
            return -1;
        }
        if (COLORS.size() > MAX_CACHED) {
            COLORS.clear();
        }
        COLORS.put(key, color);
        return color;
    }
}
