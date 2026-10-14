package retrofit.legacy.bridge;

import com.google.common.collect.Multimap;
import net.minecraft.block.Block;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Block and item behaviour the host calls: the legacy code behind using, placing, breaking,
 * ticking and powering blocks, and using items. The player is always a mirror from
 * {@link LegacyPlayers}, synced by the host just before; items in use are the player's current
 * item, so whatever legacy code does to the stack ends up in the mirror's inventory, where the
 * host picks it up.
 */
public final class Interactions {
    private static Method createStackedBlock;

    private Interactions() {
    }

    // ---- Block properties ----

    /**
     * {hardness, resistance, step sound name, harvest tool or null, harvest level, needs a tool
     * (1/0), ticks randomly (1/0), emits redstone (1/0), comparator output (1/0), light opacity}
     * for metadata 0.
     */
    public static Object[] blockInfo(Object legacyBlock) {
        Block block = (Block) legacyBlock;
        float hardness = readFloat(block, "field_149782_v"); // blockHardness
        float resistance = block.func_149638_a(null); // getExplosionResistance
        String sound = block.field_149762_H == null ? null : block.field_149762_H.field_150501_a; // stepSound.soundName
        return new Object[]{
                hardness, resistance, sound, block.getHarvestTool(0), block.getHarvestLevel(0),
                block.func_149688_o().func_76229_l() ? 0 : 1, // getMaterial().isToolNotRequired
                block.func_149653_t() ? 1 : 0, // getTickRandomly
                block.func_149744_f() ? 1 : 0, // canProvidePower
                block.func_149740_M() ? 1 : 0, // hasComparatorInputOverride
                block.func_149717_k(), // getLightOpacity
        };
    }

    private static float readFloat(Block block, String name) {
        try {
            java.lang.reflect.Field field = Block.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getFloat(block);
        } catch (ReflectiveOperationException e) {
            return 1.5f;
        }
    }

    // ---- Blocks ----

    /** onBlockActivated, after Forge's sneak rule (sneaking with an item skips the block unless the item says otherwise). */
    public static boolean activate(Object legacyBlock, Object world, int x, int y, int z, Object legacyPlayer,
                                   int side, float hitX, float hitY, float hitZ) {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        ItemStack held = player.func_71045_bC(); // getCurrentEquippedItem
        if (player.func_70093_af() && held != null // isSneaking
                && !held.func_77973_b().doesSneakBypassUse((World) world, x, y, z, player)) {
            return false;
        }
        return ((Block) legacyBlock).func_149727_a((World) world, x, y, z, player, side, hitX, hitY, hitZ);
    }

    /** onBlockClicked (left click) */
    public static void clicked(Object legacyBlock, Object world, int x, int y, int z, Object legacyPlayer) {
        ((Block) legacyBlock).func_149699_a((World) world, x, y, z, (EntityPlayer) legacyPlayer);
    }

    /** onBlockHarvested: just before a player breaks the block. */
    public static void harvested(Object legacyBlock, Object world, int x, int y, int z, int meta, Object legacyPlayer) {
        ((Block) legacyBlock).func_149681_a((World) world, x, y, z, meta, (EntityPlayer) legacyPlayer);
    }

    /** breakBlock: the block was replaced (its tile entity is still there). */
    public static void breakBlock(Object legacyBlock, Object world, int x, int y, int z, int meta) {
        ((Block) legacyBlock).func_149749_a((World) world, x, y, z, (Block) legacyBlock, meta);
    }

    /** onBlockAdded */
    public static void added(Object legacyBlock, Object world, int x, int y, int z) {
        ((Block) legacyBlock).func_149726_b((World) world, x, y, z);
    }

    /** onBlockPlacedBy, for blocks placed by 1.20.1 code on a player's behalf. */
    public static void placedBy(Object legacyBlock, Object world, int x, int y, int z, Object legacyPlayer) {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        ((Block) legacyBlock).func_149689_a((World) world, x, y, z, player, player.func_71045_bC());
    }

    /** onNeighborBlockChange */
    public static void neighborChanged(Object legacyBlock, Object world, int x, int y, int z, Object neighbor) {
        ((Block) legacyBlock).func_149695_a((World) world, x, y, z, neighbor instanceof Block ? (Block) neighbor : (Block) legacyBlock);
    }

    /** updateTick (scheduled and random ticks) */
    public static void updateTick(Object legacyBlock, Object world, int x, int y, int z) {
        ((Block) legacyBlock).func_149674_a((World) world, x, y, z, ((World) world).field_73012_v); // rand
    }

    /** Client only: randomDisplayTick (particles, sounds). */
    public static void displayTick(Object legacyBlock, Object world, int x, int y, int z) {
        ((Block) legacyBlock).func_149734_b((World) world, x, y, z, ((World) world).field_73012_v);
    }

    /** isProvidingWeakPower */
    public static int weakPower(Object legacyBlock, Object world, int x, int y, int z, int side) {
        return ((Block) legacyBlock).func_149709_b((World) world, x, y, z, side);
    }

    /** isProvidingStrongPower */
    public static int strongPower(Object legacyBlock, Object world, int x, int y, int z, int side) {
        return ((Block) legacyBlock).func_149748_c((World) world, x, y, z, side);
    }

    /** getComparatorInputOverride */
    public static int comparatorOutput(Object legacyBlock, Object world, int x, int y, int z, int side) {
        return ((Block) legacyBlock).func_149736_g((World) world, x, y, z, side);
    }

    /** Forge getDrops, as legacy stacks. */
    public static Object[] drops(Object legacyBlock, Object world, int x, int y, int z, int meta, int fortune) {
        List<ItemStack> drops = ((Block) legacyBlock).getDrops((World) world, x, y, z, meta, fortune);
        return drops == null ? new Object[0] : new ArrayList<ItemStack>(drops).toArray();
    }

    /** The silk touch drop (createStackedBlock) if the block allows it, else null. */
    public static Object silkDrop(Object legacyBlock, Object world, int x, int y, int z, int meta, Object legacyPlayer) throws ReflectiveOperationException {
        Block block = (Block) legacyBlock;
        if (!block.canSilkHarvest((World) world, (EntityPlayer) legacyPlayer, x, y, z, meta)) {
            return null;
        }
        if (createStackedBlock == null) {
            Method method = Block.class.getDeclaredMethod("func_149644_j", int.class);
            method.setAccessible(true);
            createStackedBlock = method;
        }
        return createStackedBlock.invoke(block, meta);
    }

    /** Forge getExpDrop */
    public static int expDrop(Object legacyBlock, Object world, int meta, int fortune) {
        return ((Block) legacyBlock).getExpDrop((World) world, meta, fortune);
    }

    /** {item, damage} picked with the middle mouse button (getItem(world, x, y, z) and getDamageValue). */
    public static Object pickStack(Object legacyBlock, Object world, int x, int y, int z) {
        Block block = (Block) legacyBlock;
        try {
            Item item = block.func_149694_d((World) world, x, y, z); // getItem (client-only in 1.7.10)
            return item == null ? null : new ItemStack(item, 1, block.func_149643_k((World) world, x, y, z)); // getDamageValue
        } catch (NoSuchMethodError dedicatedServer) {
            return null;
        }
    }

    // ---- Items (always the player's current item) ----

    /** Forge onItemUseFirst then onItemUse (the latter also places legacy ItemBlocks). */
    public static boolean useOn(Object legacyPlayer, Object world, int x, int y, int z, int side, float hitX, float hitY, float hitZ) {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        ItemStack stack = player.func_71045_bC();
        if (stack == null) {
            return false;
        }
        Item item = stack.func_77973_b();
        if (item.onItemUseFirst(stack, player, (World) world, x, y, z, side, hitX, hitY, hitZ)) {
            return true;
        }
        return item.func_77648_a(stack, player, (World) world, x, y, z, side, hitX, hitY, hitZ); // onItemUse
    }

    /** onItemRightClick; the result replaces the held stack, as ItemInWorldManager did. */
    public static void rightClick(Object legacyPlayer, Object world) {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        ItemStack stack = player.func_71045_bC();
        if (stack == null) {
            return;
        }
        int size = stack.field_77994_a;
        ItemStack result = stack.func_77973_b().func_77659_a(stack, (World) world, player);
        if (result != stack || result.field_77994_a != size) {
            player.field_71071_by.field_70462_a[player.field_71071_by.field_70461_c] =
                    result != null && result.field_77994_a > 0 ? result : null;
        }
    }

    /** {max use duration, use action: 0 none, 1 eat, 2 drink, 3 block, 4 bow} of the current item. */
    public static int[] useInfo(Object legacyStack) {
        ItemStack stack = (ItemStack) legacyStack;
        EnumAction action = stack.func_77973_b().func_77661_b(stack);
        return new int[]{stack.func_77973_b().func_77626_a(stack), action == null ? 0 : action.ordinal()};
    }

    /** onUsingTick */
    public static void usingTick(Object legacyPlayer, int count) {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        ItemStack stack = player.func_71045_bC();
        if (stack != null) {
            stack.func_77973_b().onUsingTick(stack, player, count);
        }
    }

    /** onPlayerStoppedUsing (bows fire here) */
    public static void stoppedUsing(Object legacyPlayer, Object world, int timeLeft) {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        ItemStack stack = player.func_71045_bC();
        if (stack != null) {
            stack.func_77973_b().func_77615_a(stack, (World) world, player, timeLeft);
        }
    }

    /** onEaten (food and potions finish here); the result replaces the held stack. */
    public static void finishUsing(Object legacyPlayer, Object world) {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        ItemStack stack = player.func_71045_bC();
        if (stack == null) {
            return;
        }
        ItemStack result = stack.func_77973_b().func_77654_b(stack, (World) world, player);
        player.field_71071_by.field_70462_a[player.field_71071_by.field_70461_c] =
                result != null && result.field_77994_a > 0 ? result : null;
    }

    /** Forge getDigSpeed of a stack against a block. */
    public static float digSpeed(Object legacyStack, Object legacyBlock, int meta) {
        ItemStack stack = (ItemStack) legacyStack;
        return stack.func_77973_b().getDigSpeed(stack, (Block) legacyBlock, meta);
    }

    /** Forge canHarvestBlock of a stack. */
    public static boolean canHarvest(Object legacyStack, Object legacyBlock) {
        ItemStack stack = (ItemStack) legacyStack;
        return stack.func_77973_b().canHarvestBlock((Block) legacyBlock, stack);
    }

    /** Forge getHarvestLevel of a stack for a tool class ("pickaxe", "axe", "shovel"); -1 if it isn't one. */
    public static int harvestLevel(Object legacyStack, String toolClass) {
        ItemStack stack = (ItemStack) legacyStack;
        return stack.func_77973_b().getHarvestLevel(stack, toolClass);
    }

    /** onBlockDestroyed with the current item (tools take damage here). */
    public static void blockDestroyed(Object legacyPlayer, Object world, Object legacyBlock, int x, int y, int z) {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        ItemStack stack = player.func_71045_bC();
        if (stack != null) {
            stack.func_77973_b().func_150894_a(stack, (World) world, (Block) legacyBlock, x, y, z, player);
            if (stack.field_77994_a <= 0) {
                player.field_71071_by.field_70462_a[player.field_71071_by.field_70461_c] = null;
            }
        }
    }

    // ---- Inventories of tile entities (for 1.20.1 hoppers and pipes) ----

    /** The slots reachable from a side (ISidedInventory), all slots for plain inventories, null if not an inventory. */
    public static int[] inventorySlots(Object tile, int side) {
        if (tile instanceof net.minecraft.inventory.ISidedInventory && side >= 0) {
            return ((net.minecraft.inventory.ISidedInventory) tile).func_94128_d(side); // getAccessibleSlotsFromSide
        }
        if (!(tile instanceof net.minecraft.inventory.IInventory)) {
            return null;
        }
        int size = ((net.minecraft.inventory.IInventory) tile).func_70302_i_(); // getSizeInventory
        int[] slots = new int[size];
        for (int i = 0; i < size; i++) {
            slots[i] = i;
        }
        return slots;
    }

    public static Object inventoryGet(Object tile, int slot) {
        return ((net.minecraft.inventory.IInventory) tile).func_70301_a(slot); // getStackInSlot
    }

    public static void inventorySet(Object tile, int slot, Object stack) {
        net.minecraft.inventory.IInventory inventory = (net.minecraft.inventory.IInventory) tile;
        inventory.func_70299_a(slot, (ItemStack) stack); // setInventorySlotContents
        inventory.func_70296_d(); // markDirty
    }

    public static int inventoryLimit(Object tile) {
        return ((net.minecraft.inventory.IInventory) tile).func_70297_j_(); // getInventoryStackLimit
    }

    /** isItemValidForSlot, and canInsertItem for sided inventories. */
    public static boolean inventoryCanInsert(Object tile, int slot, Object stack, int side) {
        if (!((net.minecraft.inventory.IInventory) tile).func_94041_b(slot, (ItemStack) stack)) {
            return false;
        }
        return !(tile instanceof net.minecraft.inventory.ISidedInventory) || side < 0
                || ((net.minecraft.inventory.ISidedInventory) tile).func_102007_a(slot, (ItemStack) stack, side); // canInsertItem
    }

    public static boolean inventoryCanExtract(Object tile, int slot, Object stack, int side) {
        return !(tile instanceof net.minecraft.inventory.ISidedInventory) || side < 0
                || ((net.minecraft.inventory.ISidedInventory) tile).func_102008_b(slot, (ItemStack) stack, side); // canExtractItem
    }

    // ---- Armor and items that tick in inventories ----

    private static final String[] ARMOR_PREFIXES = {"leather", "chainmail", "iron", "diamond", "gold"};
    private static final java.util.Map<Class<?>, Integer> TICK_FLAGS = new java.util.HashMap<Class<?>, Integer>();

    /** {armor type (0 head, 1 chest, 2 legs, 3 feet) or -1 if not armor, armor points, enchantability, render index}. */
    public static int[] armorInfo(Object legacyItem) {
        if (!(legacyItem instanceof net.minecraft.item.ItemArmor)) {
            return new int[]{-1, 0, 0, 0};
        }
        net.minecraft.item.ItemArmor armor = (net.minecraft.item.ItemArmor) legacyItem;
        return new int[]{armor.field_77881_a, armor.field_77879_b, armor.func_77619_b(), armor.field_77880_c}; // armorType, damageReduceAmount, renderIndex
    }

    /** Forge getArmorTexture, or the vanilla texture 1.7.10 used by render index. */
    public static String armorTexture(Object legacyStack, int slot, String type) {
        ItemStack stack = (ItemStack) legacyStack;
        String texture = stack.func_77973_b().getArmorTexture(stack, null, slot, type);
        if (texture == null && stack.func_77973_b() instanceof net.minecraft.item.ItemArmor) {
            int index = ((net.minecraft.item.ItemArmor) stack.func_77973_b()).field_77880_c;
            String prefix = ARMOR_PREFIXES[Math.max(0, Math.min(index, ARMOR_PREFIXES.length - 1))];
            texture = "minecraft:textures/models/armor/" + prefix + "_layer_" + (slot == 2 ? 2 : 1) + (type == null ? "" : "_" + type) + ".png";
        }
        return texture;
    }

    /** 1 if the item overrides onUpdate (ticks in inventories), 2 if it overrides onArmorTick; cached per class. */
    public static int tickFlags(Object legacyItem) {
        Class<?> type = legacyItem.getClass();
        Integer known = TICK_FLAGS.get(type);
        if (known == null) {
            // Method handles resolve just the one method: Class.getMethod would resolve every signature of
            // the class, and mods' client-only methods name classes dedicated servers don't have.
            int flags = 0;
            if (declarer(type, "func_77663_a", java.lang.invoke.MethodType.methodType(void.class, ItemStack.class, World.class,
                    net.minecraft.entity.Entity.class, int.class, boolean.class)) != Item.class) {
                flags |= 1;
            }
            if (declarer(type, "onArmorTick", java.lang.invoke.MethodType.methodType(void.class, World.class, EntityPlayer.class,
                    ItemStack.class)) != Item.class) {
                flags |= 2;
            }
            known = flags;
            TICK_FLAGS.put(type, known);
        }
        return known;
    }

    private static Class<?> declarer(Class<?> type, String name, java.lang.invoke.MethodType signature) {
        try {
            java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.publicLookup();
            return lookup.revealDirect(lookup.findVirtual(type, name, signature)).getDeclaringClass();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Item.class;
        }
    }

    /** What InventoryPlayer and EntityPlayer did each tick: onUpdate for carried items, onArmorTick for worn ones. */
    public static void inventoryTick(Object legacyPlayer, Object world) {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        ItemStack[] main = player.field_71071_by.field_70462_a;
        for (int i = 0; i < main.length; i++) {
            ItemStack stack = main[i];
            if (stack != null && (tickFlags(stack.func_77973_b()) & 1) != 0) {
                stack.func_77973_b().func_77663_a(stack, (World) world, player, i, i == player.field_71071_by.field_70461_c); // onUpdate
                if (stack.field_77994_a <= 0) {
                    main[i] = null;
                }
            }
        }
        for (ItemStack stack : player.field_71071_by.field_70460_b) { // armorInventory
            if (stack != null && (tickFlags(stack.func_77973_b()) & 2) != 0) {
                stack.func_77973_b().onArmorTick((World) world, player, stack);
            }
        }
    }

    /** The attack damage bonus a stack's attribute modifiers give ("generic.attackDamage", additive), or NaN. */
    @SuppressWarnings("unchecked")
    public static double attackDamage(Object legacyStack) {
        ItemStack stack = (ItemStack) legacyStack;
        Multimap<String, AttributeModifier> modifiers = stack.func_77973_b().getAttributeModifiers(stack);
        if (modifiers == null || !modifiers.containsKey("generic.attackDamage")) {
            return Double.NaN;
        }
        double total = 0;
        for (AttributeModifier modifier : modifiers.get("generic.attackDamage")) {
            if (modifier.func_111169_c() == 0) { // getOperation: add
                total += modifier.func_111164_d(); // getAmount
            }
        }
        return total;
    }
}
