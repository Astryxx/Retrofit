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
    /** The carrier of full-cube blocks (an ItemBlock to legacy code). */
    public static final String LEGACY_BLOCK_NAME = "retrofit:modern_block";
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

    /** Carriers of armor, swords and tools (an ItemArmor per slot, an ItemSword, an ItemTool to legacy code). */
    private static final java.util.Set<String> GEAR_NAMES = java.util.Set.of("retrofit:modern_helmet", "retrofit:modern_chestplate",
            "retrofit:modern_leggings", "retrofit:modern_boots", "retrofit:modern_sword", "retrofit:modern_tool");

    /** Whether a legacy item name is one of the carriers of 1.20.1 items. */
    public static boolean isCarrier(String legacyName) {
        return legacyName.equals(LEGACY_NAME) || legacyName.equals(LEGACY_BLOCK_NAME) || GEAR_NAMES.contains(legacyName);
    }

    /** The legacy stack carrying a 1.20.1 item, or null (no carrier: the item has nothing legacy code could hold it as). */
    static Object toLegacy(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return null;
        }
        try {
            byte[] tag = stack.getTag() == null ? null : LegacyStacks.toBytes(stack.getTag());
            List<String> tools = toolKinds(stack);
            int kind = gearKind(stack, tools);
            return LegacyBridge.call("makeModernStack", id.toString(), stack.getCount(), index(id.toString()), tag,
                    stack.getHoverName().getString(), stack.getMaxStackSize(), isCube(stack.getItem()),
                    kind, kind == 0 ? null : gear(stack), tools.toArray(new String[0]), armorTexture(stack));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** What legacy code should take an item for: 0 any item, 1 to 4 armor (head, chest, legs, feet), 5 a sword, 6 a tool. */
    private static int gearKind(ItemStack stack, List<String> tools) {
        if (stack.getItem() instanceof net.minecraft.world.item.ArmorItem armor) {
            return switch (armor.getType()) {
                case HELMET -> 1;
                case CHESTPLATE -> 2;
                case LEGGINGS -> 3;
                case BOOTS -> 4;
            };
        }
        if (stack.getItem() instanceof net.minecraft.world.item.SwordItem || stack.canPerformAction(net.minecraftforge.common.ToolActions.SWORD_SWEEP)) {
            return 5;
        }
        return tools.isEmpty() ? 0 : 6;
    }

    /** The 1.7.10 tool classes an item works as (Forge's tool actions, so other mods' tools count too). */
    private static List<String> toolKinds(ItemStack stack) {
        List<String> tools = new ArrayList<>(3);
        if (stack.canPerformAction(net.minecraftforge.common.ToolActions.PICKAXE_DIG)) {
            tools.add("pickaxe");
        }
        if (stack.canPerformAction(net.minecraftforge.common.ToolActions.AXE_DIG)) {
            tools.add("axe");
        }
        if (stack.canPerformAction(net.minecraftforge.common.ToolActions.SHOVEL_DIG)) {
            tools.add("shovel");
        }
        return tools;
    }

    /** The texture an armor piece is drawn on a body with (the item's own, else 1.20.1's for its material), or null. */
    private static String armorTexture(ItemStack stack) {
        if (!(stack.getItem() instanceof net.minecraft.world.item.ArmorItem armor)) {
            return null;
        }
        net.minecraft.world.entity.EquipmentSlot slot = armor.getEquipmentSlot();
        try {
            String own = armor.getArmorTexture(stack, null, slot, null);
            if (own != null) {
                return own;
            }
        } catch (RuntimeException e) {
            // an item that wants the wearer to choose: the material's texture
        }
        ResourceLocation material = new ResourceLocation(armor.getMaterial().getName());
        return material.getNamespace() + ":textures/models/armor/" + material.getPath()
                + "_layer_" + (slot == net.minecraft.world.entity.EquipmentSlot.LEGS ? 2 : 1) + ".png";
    }

    /** {armor points, attack damage, harvest level, mining speed} of armor, weapons and tools. */
    private static float[] gear(ItemStack stack) {
        float armor = stack.getItem() instanceof net.minecraft.world.item.ArmorItem item ? item.getDefense() : 0;
        double damage = 0;
        for (var modifier : stack.getAttributeModifiers(net.minecraft.world.entity.EquipmentSlot.MAINHAND)
                .get(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)) {
            if (modifier.getOperation() == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADDITION) {
                damage += modifier.getAmount();
            }
        }
        float level = 0;
        float speed = 1;
        if (stack.getItem() instanceof net.minecraft.world.item.TieredItem tiered) {
            level = tiered.getTier().getLevel();
            speed = tiered.getTier().getSpeed();
        }
        return new float[]{armor, (float) damage, level, speed};
    }

    private static final Map<Item, Boolean> CUBES = new java.util.concurrent.ConcurrentHashMap<>();

    /** Whether an item places a full, opaque cube (carried as a block legacy code can use as a cover). */
    private static boolean isCube(Item item) {
        if (!(item instanceof net.minecraft.world.item.BlockItem blockItem)) {
            return false;
        }
        return CUBES.computeIfAbsent(item, key -> {
            try {
                net.minecraft.world.level.block.state.BlockState state = blockItem.getBlock().defaultBlockState();
                return state.canOcclude() && net.minecraft.world.level.block.Block.isShapeFullBlock(
                        state.getShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO));
            } catch (RuntimeException e) {
                return false; // a block that needs a real world to say
            }
        });
    }

    /** The 1.20.1 block a carrier damage value stands for, or null. */
    public static net.minecraft.world.level.block.Block blockOf(int index) {
        String id;
        synchronized (ModernItems.class) {
            id = index >= 0 && index < IDS.size() ? IDS.get(index) : null;
        }
        Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        return item instanceof net.minecraft.world.item.BlockItem blockItem ? blockItem.getBlock() : null;
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
