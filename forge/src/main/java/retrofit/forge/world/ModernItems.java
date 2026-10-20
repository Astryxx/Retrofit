package retrofit.forge.world;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 1.20.1 items with no 1.7.10 form, carried through legacy code as "retrofit:modern_item" (see
 * the legacy bridge's ModernContent). Each item has a number (the legacy damage value), kept in
 * retrofit/modern-items.txt so it never changes: legacy saves and recipes rely on it.
 */
public final class ModernItems {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String LEGACY_NAME = "retrofit:modern_item";
    private static final String TAG = "RetrofitModern";
    private static final Map<String, Integer> INDEX = new HashMap<>();
    private static final List<String> IDS = new ArrayList<>();
    private static boolean loaded;

    private ModernItems() {
    }

    private static Path file() {
        return FMLPaths.GAMEDIR.get().resolve("retrofit").resolve("modern-items.txt");
    }

    private static synchronized int index(String id) {
        if (!loaded) {
            loaded = true;
            try {
                if (Files.exists(file())) {
                    for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                        if (!line.isBlank() && !INDEX.containsKey(line.trim())) {
                            INDEX.put(line.trim(), IDS.size());
                            IDS.add(line.trim());
                        }
                    }
                }
            } catch (IOException e) {
                LOGGER.warn("Could not read {}", file(), e);
            }
        }
        Integer known = INDEX.get(id);
        if (known != null) {
            return known;
        }
        int index = IDS.size();
        if (index >= 32767) {
            return 32766; // the legacy damage range is full: these share a number (still told apart by their data)
        }
        INDEX.put(id, index);
        IDS.add(id);
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), id + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.warn("Could not record 1.20.1 item {} in {}", id, file(), e);
        }
        return index;
    }

    /** The legacy stack carrying a 1.20.1 item, or null (no carrier: the item has nothing legacy code could hold it as). */
    static Object toLegacy(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return null;
        }
        try {
            byte[] tag = stack.getTag() == null ? null : LegacyStacks.toBytes(stack.getTag());
            return LegacyBridge.call("makeModernStack", id.toString(), stack.getCount(), index(id.toString()), tag,
                    stack.getHoverName().getString(), stack.getMaxStackSize());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The 1.20.1 item a carrier stack holds: {@code tag} is the carrier's NBT. */
    static ItemStack toModern(CompoundTag tag, int count) {
        if (tag == null || !tag.contains(TAG)) {
            return ItemStack.EMPTY;
        }
        CompoundTag data = tag.getCompound(TAG);
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(data.getString("id")));
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item, count);
        if (data.contains("tag")) {
            stack.setTag(data.getCompound("tag").copy());
        }
        return stack;
    }

    /** The legacy damage value a 1.20.1 item is carried with (for Ore Dictionary entries). */
    static int indexOf(Item item) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        return id == null ? 0 : index(id.toString());
    }
}
