package retrofit.forge.world;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts item stacks between 1.7.10 and 1.20.1.
 *
 * - Legacy mod items are their {@link LegacyStackItem}s: the damage value becomes the subtype or
 *   the damage, and the legacy tag is kept as the stack's tag.
 * - Vanilla items go through Minecraft's own data fixer (the one that upgrades old worlds), from
 *   a pre-1.9 stack ({id, Count, Damage, tag}), so colours, wood types, enchantments and names
 *   come out as 1.20.1 has them. The reverse uses a table built from the same fixer.
 * - Items with no 1.7.10 form (other 1.20.1 mods, newer vanilla items) have no legacy stack;
 *   {@link #toLegacy} returns null for them and callers leave such stacks alone.
 */
public final class LegacyStacks {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** The data version of worlds older than 1.9, which is what a 1.7.10 stack is to the fixer. */
    private static final int LEGACY_DATA_VERSION = 99;
    private static final String[] ENCHANTMENTS = new String[63];

    /** Fixed stacks without tags, by "name/damage" (the fixer is slow; most stacks have no tag). */
    private static final Map<String, ItemStack> VANILLA = new ConcurrentHashMap<>();
    private static volatile Map<Item, Object[]> reverse;

    static {
        String[][] ids = {
                {"0", "protection"}, {"1", "fire_protection"}, {"2", "feather_falling"}, {"3", "blast_protection"},
                {"4", "projectile_protection"}, {"5", "respiration"}, {"6", "aqua_affinity"}, {"7", "thorns"},
                {"16", "sharpness"}, {"17", "smite"}, {"18", "bane_of_arthropods"}, {"19", "knockback"},
                {"20", "fire_aspect"}, {"21", "looting"}, {"32", "efficiency"}, {"33", "silk_touch"},
                {"34", "unbreaking"}, {"35", "fortune"}, {"48", "power"}, {"49", "punch"}, {"50", "flame"},
                {"51", "infinity"}, {"61", "luck_of_the_sea"}, {"62", "lure"},
        };
        for (String[] id : ids) {
            ENCHANTMENTS[Integer.parseInt(id[0])] = "minecraft:" + id[1];
        }
    }

    private LegacyStacks() {
    }

    // ---- 1.7.10 -> 1.20.1 ----

    /** The 1.20.1 form of a legacy stack; empty for null, empty or unconvertible stacks. */
    public static ItemStack toModern(Object legacyStack) {
        Object[] read = legacyStack == null ? null : LegacyBridge.readStack(legacyStack);
        if (read == null) {
            return ItemStack.EMPTY;
        }
        String name = (String) read[0];
        int count = (Integer) read[1];
        int damage = (Integer) read[2];
        CompoundTag tag = read[3] == null ? null : fromBytes((byte[]) read[3]);
        if (name.startsWith("minecraft:")) {
            return vanilla(name, count, damage, tag);
        }
        Item item = ForgeRegistries.ITEMS.getValue(LegacyContent.toResourceLocation(name));
        if (!(item instanceof LegacyStackItem legacyItem)) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = legacyItem.stackOf(damage);
        if (!legacyItem.hasSubtypes() && damage > 0 && stack.isDamageableItem()) {
            stack.setDamageValue(damage);
        }
        if (tag != null) {
            stack.getOrCreateTag().merge(tag);
        }
        stack.setCount(count);
        return stack;
    }

    private static ItemStack vanilla(String name, int count, int damage, CompoundTag tag) {
        if (tag == null) {
            ItemStack prototype = VANILLA.computeIfAbsent(name + "/" + damage, key -> fix(name, 1, damage, null));
            ItemStack stack = prototype.copy();
            stack.setCount(count);
            return stack;
        }
        return fix(name, count, damage, tag);
    }

    private static ItemStack fix(String name, int count, int damage, CompoundTag tag) {
        CompoundTag old = new CompoundTag();
        old.putString("id", name);
        old.putByte("Count", (byte) Math.min(127, count));
        old.putShort("Damage", (short) damage);
        if (tag != null) {
            old.put("tag", tag);
        }
        try {
            Dynamic<Tag> fixed = DataFixers.getDataFixer().update(References.ITEM_STACK, new Dynamic<>(NbtOps.INSTANCE, old),
                    LEGACY_DATA_VERSION, SharedConstants.getCurrentVersion().getDataVersion().getVersion());
            ItemStack stack = ItemStack.of((CompoundTag) fixed.getValue());
            if (!stack.isEmpty()) {
                stack.setCount(count);
            }
            return stack;
        } catch (RuntimeException e) {
            LOGGER.warn("Could not convert legacy stack {}:{}", name, damage, e);
            return ItemStack.EMPTY;
        }
    }

    // ---- 1.20.1 -> 1.7.10 ----

    /** The legacy form of a stack, or null if it's empty or has no 1.7.10 equivalent. */
    public static Object toLegacy(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        if (stack.getItem() instanceof LegacyStackItem legacyItem) {
            if (legacyItem.legacyItem() == null) {
                return null;
            }
            CompoundTag tag = stack.getTag() == null ? null : stack.getTag().copy();
            if (tag != null) {
                tag.remove(LegacyItem.META_TAG);
                tag.remove("Damage");
            }
            return LegacyBridge.makeStackOf(legacyItem.legacyItem(), stack.getCount(), legacyItem.meta(stack), bytesOrNull(tag));
        }
        Object[] legacy = reverseTable().get(stack.getItem());
        if (legacy == null) {
            return null;
        }
        int damage = stack.isDamageableItem() ? stack.getDamageValue() : (Integer) legacy[1];
        return LegacyBridge.makeStack((String) legacy[0], stack.getCount(), damage, bytesOrNull(legacyTag(stack.getTag())));
    }

    /** 1.20.1 item -> {legacy name, damage}, built by running every vanilla legacy item through the fixer. */
    private static Map<Item, Object[]> reverseTable() {
        Map<Item, Object[]> table = reverse;
        if (table == null) {
            synchronized (LegacyStacks.class) {
                if (reverse == null) {
                    Map<Item, Object[]> built = new IdentityHashMap<>();
                    for (Object[] item : LegacyBridge.vanillaItems()) {
                        String name = (String) item[0];
                        boolean hasSubtypes = (Integer) item[1] != 0;
                        for (int meta = 0; meta < (hasSubtypes ? 16 : 1); meta++) {
                            ItemStack modern = vanilla(name, 1, meta, null);
                            if (!modern.isEmpty()) {
                                built.putIfAbsent(modern.getItem(), new Object[]{name, meta});
                            }
                        }
                    }
                    LOGGER.info("Mapped {} 1.20.1 items to 1.7.10 items", built.size());
                    reverse = built;
                }
                table = reverse;
            }
        }
        return table;
    }

    /** The parts of a 1.20.1 vanilla tag that 1.7.10 reads differently: enchantments, names, lore. */
    private static CompoundTag legacyTag(CompoundTag modern) {
        if (modern == null) {
            return null;
        }
        CompoundTag tag = modern.copy();
        tag.remove("Damage");
        convertEnchantments(tag, "Enchantments", "ench");
        convertEnchantments(tag, "StoredEnchantments", "StoredEnchantments");
        if (tag.contains("display", Tag.TAG_COMPOUND)) {
            CompoundTag display = tag.getCompound("display");
            if (display.contains("Name", Tag.TAG_STRING)) {
                display.putString("Name", plainText(display.getString("Name")));
            }
            if (display.contains("Lore", Tag.TAG_LIST)) {
                ListTag lore = display.getList("Lore", Tag.TAG_STRING);
                ListTag plain = new ListTag();
                for (int i = 0; i < lore.size(); i++) {
                    plain.add(StringTag.valueOf(plainText(lore.getString(i))));
                }
                display.put("Lore", plain);
            }
        }
        return tag.isEmpty() ? null : tag;
    }

    private static void convertEnchantments(CompoundTag tag, String from, String to) {
        if (!tag.contains(from, Tag.TAG_LIST)) {
            return;
        }
        ListTag modern = tag.getList(from, Tag.TAG_COMPOUND);
        ListTag legacy = new ListTag();
        for (int i = 0; i < modern.size(); i++) {
            CompoundTag enchantment = modern.getCompound(i);
            int id = legacyEnchantmentId(enchantment.getString("id"));
            if (id >= 0) {
                CompoundTag converted = new CompoundTag();
                converted.putShort("id", (short) id);
                converted.putShort("lvl", (short) enchantment.getInt("lvl"));
                legacy.add(converted);
            }
        }
        tag.remove(from);
        if (!legacy.isEmpty()) {
            tag.put(to, legacy);
        }
    }

    private static int legacyEnchantmentId(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        String name = location == null ? id : location.toString();
        for (int i = 0; i < ENCHANTMENTS.length; i++) {
            if (name.equals(ENCHANTMENTS[i])) {
                return i;
            }
        }
        return -1;
    }

    private static String plainText(String json) {
        try {
            Component component = Component.Serializer.fromJson(json);
            return component == null ? json : component.getString();
        } catch (RuntimeException e) {
            return json;
        }
    }

    // ---- NBT across the boundary ----

    private static byte[] bytesOrNull(CompoundTag tag) {
        return tag == null || tag.isEmpty() ? null : toBytes(tag);
    }

    /** Binary NBT is the same format in 1.7.10 and 1.20.1, so tags cross as bytes. */
    static byte[] toBytes(CompoundTag tag) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.write(tag, new DataOutputStream(bytes));
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static CompoundTag fromBytes(byte[] bytes) {
        try {
            return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** For tests and reports: how many vanilla items map back. */
    static int reverseSize() {
        return reverseTable().size();
    }
}
