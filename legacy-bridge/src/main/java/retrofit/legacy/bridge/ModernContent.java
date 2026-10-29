package retrofit.legacy.bridge;

import cpw.mods.fml.common.DummyModContainer;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.ModMetadata;
import cpw.mods.fml.common.registry.GameRegistry;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.block.Block;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraftforge.common.ISpecialArmor;
import net.minecraftforge.oredict.OreDictionary;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 1.20.1 content in the legacy world. A 1.20.1 item with no 1.7.10 form (copper, other mods'
 * items) travels through legacy code as "retrofit:modern_item": its id and data ride in the
 * stack's NBT, and the damage value is the item's number in Retrofit's table (stable across
 * restarts), so legacy recipes and the Ore Dictionary tell items apart. Legacy chests, pipes,
 * machines and recipes keep and move them; they turn back into themselves on the 1.20.1 side.
 * Full-cube blocks travel as "retrofit:modern_block" instead, an ItemBlock of the solid stand-in
 * block, whose icons for that damage are the 1.20.1 block's textures: mods that take blocks as
 * covers or facades (Carpenter's Blocks) accept and draw them. Armor, swords and tools travel as
 * an ItemArmor for their slot, an ItemSword or an ItemTool, with their armor points, attack
 * damage and tool kinds in the data: mobs that put on armor or take up weapons (Better Dungeons'
 * NPCs) and code that asks what a tool is take them for what they are.
 *
 * In the world, blocks 1.7.10 never had are the stand-in blocks, whose tile entity
 * ({@link ModernTile}) carries the real block: mods that move blocks with their tile entities
 * (Pistronics' pistons, frames, block movers) move the real block.
 */
public final class ModernContent {
    static final String ITEM_NAME = "retrofit:modern_item";
    static final String BLOCK_ITEM_NAME = "retrofit:modern_block";
    static final String TAG = "RetrofitModern";
    private static ModernItem item;
    /** Carriers of armor (helmet, chestplate, leggings, boots), swords and tools; see makeModernStack's kind. */
    private static final Item[] GEAR = new Item[6];
    private static final String[] GEAR_NAMES = {"modern_helmet", "modern_chestplate", "modern_leggings", "modern_boots", "modern_sword", "modern_tool"};
    private static ModernBlock solidBlock;
    private static ModernBlock openBlock;
    private static Item blockItem;

    private ModernContent() {
    }

    /** Called by the legacy boot between FML's preInit and init, when mods register their content. */
    public static void register() {
        if (item != null) {
            return;
        }
        item = new ModernItem();
        ModMetadata metadata = new ModMetadata();
        metadata.modId = "retrofit";
        metadata.name = "Retrofit";
        metadata.version = "1";
        ModContainer retrofit = new DummyModContainer(metadata);
        try {
            Field controllerField = Loader.class.getDeclaredField("modController");
            controllerField.setAccessible(true);
            Object controller = controllerField.get(Loader.instance());
            Field active = controller.getClass().getDeclaredField("activeContainer");
            active.setAccessible(true);
            Object before = active.get(controller);
            active.set(controller, retrofit); // names registered now are "retrofit:..."
            try {
                GameRegistry.registerItem(item, "modern_item");
                for (int slot = 0; slot < 4; slot++) {
                    GEAR[slot] = new ModernArmor(slot);
                }
                GEAR[4] = new ModernSword();
                GEAR[5] = new ModernTool();
                for (int i = 0; i < GEAR.length; i++) {
                    GameRegistry.registerItem(GEAR[i], GEAR_NAMES[i]);
                }
                solidBlock = new ModernBlock(true);
                openBlock = new ModernBlock(false);
                // the solid one's item carries 1.20.1 blocks (Block.getBlockFromItem goes by the shared id)
                GameRegistry.registerBlock(solidBlock, ModernBlockItem.class, "modern_block");
                GameRegistry.registerBlock(openBlock, null, "modern_open_block"); // no item: it stands for blocks of the 1.20.1 world
                GameRegistry.registerTileEntity(ModernTile.class, "retrofit:modern_block"); // carried by mods that save tile entities to move them
                blockItem = Item.func_150898_a(solidBlock); // getItemFromBlock
                HostCreatures.nameStandIns();
            } finally {
                active.set(controller, before);
            }
        } catch (ReflectiveOperationException e) {
            System.out.println("[Retrofit] Could not register the item that carries 1.20.1 items through legacy code: " + e);
            item = null;
        }
    }

    static boolean registered() {
        return item != null;
    }

    /** {solid stand-in block id, non-solid one}; 0 if not registered. */
    public static int[] modernBlockIds() {
        return new int[]{solidBlock == null ? 0 : net.minecraft.block.Block.func_149682_b(solidBlock), // getIdFromBlock
                openBlock == null ? 0 : net.minecraft.block.Block.func_149682_b(openBlock)};
    }

    /**
     * The legacy stack carrying a 1.20.1 item: {@code tag} is its 1.20.1 NBT (as legacy NBT bytes),
     * or null; {@code cube} for a full-cube block, carried as a block. {@code kind}: 0 any item,
     * 1 to 4 armor for the head, chest, legs or feet, 5 a sword, 6 a tool, with {@code gear} its
     * {armor points, attack damage, harvest level, mining speed}, {@code tools} its tool kinds and
     * {@code armorTexture} the texture armor is drawn on a body with.
     */
    public static Object makeModernStack(String id, int count, int index, byte[] tag, String name, int maxStack, boolean cube,
                                         int kind, float[] gear, String[] tools, String armorTexture) throws java.io.IOException {
        if (item == null) {
            return null;
        }
        Item carrier = kind >= 1 && kind <= GEAR.length ? GEAR[kind - 1] : cube && blockItem != null ? blockItem : item;
        ItemStack stack = new ItemStack(carrier, count, index);
        NBTTagCompound data = new NBTTagCompound();
        data.func_74778_a("id", id); // setString
        data.func_74778_a("Name", name == null ? id : name);
        data.func_74768_a("Max", maxStack); // setInteger
        if (kind != 0 && gear != null) {
            data.func_74776_a("Armor", gear[0]); // setFloat
            data.func_74776_a("Damage", gear[1]);
            data.func_74776_a("Level", gear[2]);
            data.func_74776_a("Speed", gear[3]);
            data.func_74778_a("Tools", tools == null ? "" : String.join(",", tools));
            if (armorTexture != null) {
                data.func_74778_a("Texture", armorTexture);
            }
        }
        if (tag != null) {
            data.func_74782_a("tag", Bridge.fromBytes(tag)); // setTag
        }
        NBTTagCompound root = new NBTTagCompound();
        root.func_74782_a(TAG, data);
        stack.func_77982_d(root); // setTagCompound
        return stack;
    }

    /** The Ore Dictionary learns a 1.20.1 item (from its tags), so legacy recipes asking for "ingotCopper" take it. */
    public static void registerModernOre(String oreName, Object legacyStack) {
        if (legacyStack instanceof ItemStack) {
            ItemStack stack = ((ItemStack) legacyStack).func_77946_l(); // copy
            stack.func_77982_d(null); // the ore entry matches by item and damage; the data stays on real stacks
            for (ItemStack known : OreDictionary.getOres(oreName)) {
                if (known.func_77973_b() == stack.func_77973_b() && known.func_77960_j() == stack.func_77960_j()) {
                    return;
                }
            }
            OreDictionary.registerOre(oreName, stack);
        }
    }

    /** {ore name, legacy stacks} of every Ore Dictionary entry (the host turns them into tags). */
    public static Object[][] oreDictionary() {
        String[] names = OreDictionary.getOreNames();
        Object[][] result = new Object[names.length][];
        for (int i = 0; i < names.length; i++) {
            result[i] = new Object[]{names[i], OreDictionary.getOres(names[i]).toArray()};
        }
        return result;
    }

    /**
     * A block of a 1.20.1 mod as legacy code sees it: what it drops (quarries, miners), how hard and
     * bright it is and whether it counts as stone come from the real block at the position. One
     * solid and one non-solid stand-in (plants, rails, torches: no collision, not opaque).
     */
    static final class ModernBlock extends net.minecraft.block.Block {
        private final boolean solid;

        ModernBlock(boolean solid) {
            super(solid ? net.minecraft.block.material.Material.field_151576_e : net.minecraft.block.material.Material.field_151585_k); // rock, plants
            this.solid = solid;
            func_149663_c(solid ? "retrofit.modern_block" : "retrofit.modern_open_block"); // setBlockName
            func_149711_c(1.5f); // setHardness
            if (!solid) {
                func_149713_g(0); // setLightOpacity
            }
        }

        private static retrofit.api.HostWorld host(Object world) {
            return world instanceof net.minecraft.world.World ? WorldGlue.hostOf((net.minecraft.world.World) world) : null;
        }

        @Override
        public boolean func_149662_c() { // isOpaqueCube
            return solid;
        }

        @Override
        public boolean func_149686_d() { // renderAsNormalBlock
            return solid;
        }

        @Override
        public int func_149645_b() { // getRenderType: drawn by the host, never by legacy renderers
            return -1;
        }

        @Override
        public net.minecraft.util.AxisAlignedBB func_149668_a(net.minecraft.world.World world, int x, int y, int z) { // getCollisionBoundingBoxFromPool
            return solid ? super.func_149668_a(world, x, y, z) : null;
        }

        @Override
        public float func_149712_f(net.minecraft.world.World world, int x, int y, int z) { // getBlockHardness
            retrofit.api.HostWorld host = host(world);
            return host == null ? 1.5f : host.hostBlockHardness(x, y, z);
        }

        @Override
        public int getLightValue(net.minecraft.world.IBlockAccess world, int x, int y, int z) {
            retrofit.api.HostWorld host = host(world);
            return host == null ? 0 : host.hostBlockLight(x, y, z);
        }

        @Override
        public boolean canHarvestBlock(net.minecraft.entity.player.EntityPlayer player, int meta) {
            return true;
        }

        @Override
        public boolean isReplaceableOreGen(net.minecraft.world.World world, int x, int y, int z, net.minecraft.block.Block target) {
            retrofit.api.HostWorld host = host(world);
            return target == net.minecraft.init.Blocks.field_150348_b && host != null && host.hostBlockIsStone(x, y, z); // stone
        }

        @Override
        public java.util.ArrayList<ItemStack> getDrops(net.minecraft.world.World world, int x, int y, int z, int metadata, int fortune) {
            java.util.ArrayList<ItemStack> drops = new java.util.ArrayList<ItemStack>();
            retrofit.api.HostWorld host = host(world);
            Object[] stacks = host == null ? null : host.hostBlockDrops(x, y, z, fortune);
            if (stacks != null) {
                for (Object stack : stacks) {
                    if (stack instanceof ItemStack) {
                        drops.add((ItemStack) stack);
                    }
                }
            }
            return drops;
        }

        @Override
        public String func_149732_F() { // getLocalizedName
            return solid ? "1.20.1 block" : "1.20.1 block (open)";
        }

        /** registerBlockIcons: stone's, for mods that list every block (BuildCraft's facades) and show it. */
        @Override
        public void func_149651_a(net.minecraft.client.renderer.texture.IIconRegister register) {
            field_149761_L = register.func_94245_a("stone"); // blockIcon = registerIcon
        }

        /**
         * getIcon in the world: the stand-in's own (stone's). Its metadata there is no carrier number,
         * so the carrier's icons below don't apply.
         */
        @Override
        public net.minecraft.util.IIcon func_149673_e(net.minecraft.world.IBlockAccess world, int x, int y, int z, int side) {
            return field_149761_L; // blockIcon
        }

        /** getIcon: for the block carrier's damage, the 1.20.1 block's texture (client only). */
        @Override
        public net.minecraft.util.IIcon func_149691_a(int side, int meta) {
            net.minecraft.util.IIcon icon = solid ? ModernIcons.icon(meta, side) : null;
            return icon != null ? icon : super.func_149691_a(side, meta);
        }
    }

    static boolean isStandIn(net.minecraft.block.Block block) {
        return block != null && (block == solidBlock || block == openBlock);
    }

    /**
     * The tile entity of a stand-in in the world: the host block it stands for, as the host gave it
     * ({@link retrofit.api.HostWorld#hostBlockData}). Placed again (World.setTileEntity), wherever
     * that is, it puts the real block there.
     */
    public static final class ModernTile extends net.minecraft.tileentity.TileEntity {
        private NBTTagCompound block;

        public ModernTile() {
        }

        ModernTile(NBTTagCompound block) {
            this.block = block;
        }

        NBTTagCompound block() {
            return block;
        }

        @Override
        public void func_145839_a(NBTTagCompound tag) { // readFromNBT
            super.func_145839_a(tag);
            block = tag.func_74764_b(TAG) ? tag.func_74775_l(TAG) : null; // hasKey, getCompoundTag
        }

        @Override
        public void func_145841_b(NBTTagCompound tag) { // writeToNBT
            super.func_145841_b(tag);
            if (block != null) {
                tag.func_74782_a(TAG, block); // setTag
            }
        }

        @Override
        public boolean canUpdate() {
            return false;
        }
    }

    /** The carrier of 1.20.1 full-cube blocks: the solid stand-in's item; damage and data as {@link ModernItem}'s. */
    public static final class ModernBlockItem extends net.minecraft.item.ItemBlock {
        public ModernBlockItem(net.minecraft.block.Block block) {
            super(block);
            func_77627_a(true); // setHasSubtypes
            func_77655_b("retrofit.modern"); // setUnlocalizedName
        }

        @Override
        public String func_77653_i(ItemStack stack) { // getItemStackDisplayName
            return displayName(stack, "1.20.1 block");
        }

        @Override
        public int getItemStackLimit(ItemStack stack) {
            return stackLimit(stack);
        }
    }

    /** The item itself: a stand-in that knows its name and stack size from the stack. */
    static final class ModernItem extends Item {
        ModernItem() {
            func_77627_a(true); // setHasSubtypes
            func_77655_b("retrofit.modern"); // setUnlocalizedName
        }

        static NBTTagCompound data(ItemStack stack) {
            NBTTagCompound tag = stack == null ? null : stack.func_77978_p(); // getTagCompound
            return tag == null ? null : tag.func_74775_l(TAG); // getCompoundTag
        }

        /** getItemStackDisplayName: the 1.20.1 item's name. */
        @Override
        public String func_77653_i(ItemStack stack) {
            return displayName(stack, "1.20.1 item");
        }

        @Override
        public int getItemStackLimit(ItemStack stack) {
            return stackLimit(stack);
        }
    }

    static String displayName(ItemStack stack, String fallback) {
        NBTTagCompound data = ModernItem.data(stack);
        return data == null || data.func_74779_i("Name").isEmpty() ? fallback : data.func_74779_i("Name"); // getString
    }

    static int stackLimit(ItemStack stack) {
        NBTTagCompound data = ModernItem.data(stack);
        int max = data == null ? 0 : data.func_74762_e("Max"); // getInteger
        return max > 0 ? max : 64;
    }

    private static float gear(ItemStack stack, String key) {
        NBTTagCompound data = ModernItem.data(stack);
        return data == null ? 0 : data.func_74760_g(key); // getFloat
    }

    /** The carried item's attack damage, as 1.7.10 weapons give it (mobs holding one hit with it). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Multimap attackModifiers(ItemStack stack) {
        Multimap modifiers = HashMultimap.create();
        float damage = gear(stack, "Damage");
        if (damage != 0) {
            modifiers.put(SharedMonsterAttributes.field_111264_e.func_111108_a(), // attackDamage
                    new AttributeModifier(ModernSword.MODIFIER, "Weapon modifier", damage, 0));
        }
        return modifiers;
    }

    /**
     * Replaces EntityLivingBase.getTotalArmorValue (HeadlessTransformer) with 1.7.10's count, except
     * that a carried 1.20.1 piece counts its own armor points (ItemArmor has one number per item).
     * Mobs' armor protects them through this; creatures that count armor their own way still do.
     */
    public static int totalArmorValue(EntityLivingBase entity) {
        int total = 0;
        for (ItemStack stack : entity.func_70035_c()) { // getLastActiveItems
            if (stack == null) {
                continue;
            }
            Item item = stack.func_77973_b(); // getItem
            if (item instanceof ModernArmor) {
                total += Math.round(gear(stack, "Armor"));
            } else if (item instanceof ItemArmor) {
                total += ((ItemArmor) item).field_77879_b; // damageReduceAmount
            }
        }
        return total;
    }

    /**
     * Self-test (PlayTest): a carried 1.20.1 stack as legacy code sees it, worn by a legacy creature
     * in an equipment slot (1 feet to 4 head; 0 held): its item's class, the creature's armor and
     * attack damage, and its tool kinds and pickaxe level.
     */
    public static String testGear(Object entity, Object legacyStack, int slot) {
        ItemStack stack = (ItemStack) legacyStack;
        net.minecraft.entity.EntityLiving living = (net.minecraft.entity.EntityLiving) entity;
        living.func_70062_b(slot, stack); // setCurrentItemOrArmor
        Item item = stack.func_77973_b();
        double damage = 0;
        for (Object modifier : stack.func_111283_C().get(SharedMonsterAttributes.field_111264_e.func_111108_a())) { // getAttributeModifiers
            damage += ((AttributeModifier) modifier).func_111164_d(); // getAmount
        }
        return item.getClass().getSuperclass().getSimpleName() + ", armor " + living.func_70658_aO() + ", damage " + damage // getTotalArmorValue
                + ", tools " + item.getToolClasses(stack) + ", pickaxe level " + item.getHarvestLevel(stack, "pickaxe");
    }

    /** A 1.20.1 helmet, chestplate, leggings or boots: an ItemArmor for its slot, protecting by the item's own armor points. */
    static final class ModernArmor extends ItemArmor implements ISpecialArmor {
        ModernArmor(int slot) {
            super(ItemArmor.ArmorMaterial.IRON, 0, slot);
            func_77627_a(true); // setHasSubtypes: the damage value is the carried item's number
            func_77656_e(0); // setMaxDamage: never worn down here (the 1.20.1 item keeps its own durability)
            func_77655_b("retrofit.modern"); // setUnlocalizedName
        }

        @Override
        public String func_77653_i(ItemStack stack) { // getItemStackDisplayName
            return displayName(stack, "1.20.1 armor");
        }

        /** Drawn on a body as the 1.20.1 piece is (renderers ask this before the material's texture). */
        @Override
        public String getArmorTexture(ItemStack stack, net.minecraft.entity.Entity entity, int slot, String type) {
            NBTTagCompound data = ModernItem.data(stack);
            return data == null || type != null || data.func_74779_i("Texture").isEmpty() ? null : data.func_74779_i("Texture");
        }

        @Override
        public int getItemStackLimit(ItemStack stack) {
            return stackLimit(stack);
        }

        /** Players' armor (ForgeHooks): the item's armor points. */
        @Override
        public ArmorProperties getProperties(EntityLivingBase entity, ItemStack stack, DamageSource source, double damage, int slot) {
            return new ArmorProperties(0, source.func_76363_c() ? 0 : gear(stack, "Armor") / 25.0, Integer.MAX_VALUE); // isUnblockable
        }

        @Override
        public int getArmorDisplay(EntityPlayer player, ItemStack stack, int slot) {
            return Math.round(gear(stack, "Armor"));
        }

        @Override
        public void damageArmor(EntityLivingBase entity, ItemStack stack, DamageSource source, int damage, int slot) {
        }
    }

    /** A 1.20.1 sword: an ItemSword hitting with the item's own attack damage. */
    static final class ModernSword extends ItemSword {
        static final java.util.UUID MODIFIER = field_111210_e; // the weapon modifier's id (Item.itemModifierUUID)

        ModernSword() {
            super(Item.ToolMaterial.IRON);
            func_77627_a(true);
            func_77656_e(0);
            func_77655_b("retrofit.modern");
        }

        @Override
        public String func_77653_i(ItemStack stack) {
            return displayName(stack, "1.20.1 sword");
        }

        @Override
        public int getItemStackLimit(ItemStack stack) {
            return stackLimit(stack);
        }

        @Override
        public Multimap getAttributeModifiers(ItemStack stack) {
            return attackModifiers(stack);
        }
    }

    /** A 1.20.1 pickaxe, axe or shovel: an ItemTool of its kinds, harvest level and speed. */
    static final class ModernTool extends ItemTool {
        ModernTool() {
            super(0, Item.ToolMaterial.IRON, Collections.emptySet());
            func_77627_a(true);
            func_77656_e(0);
            func_77655_b("retrofit.modern");
        }

        @Override
        public String func_77653_i(ItemStack stack) {
            return displayName(stack, "1.20.1 tool");
        }

        @Override
        public int getItemStackLimit(ItemStack stack) {
            return stackLimit(stack);
        }

        @Override
        public Set<String> getToolClasses(ItemStack stack) {
            NBTTagCompound data = ModernItem.data(stack);
            String tools = data == null ? "" : data.func_74779_i("Tools");
            return tools.isEmpty() ? Collections.<String>emptySet() : new HashSet<String>(Arrays.asList(tools.split(",")));
        }

        @Override
        public int getHarvestLevel(ItemStack stack, String toolClass) {
            return getToolClasses(stack).contains(toolClass) ? Math.round(gear(stack, "Level")) : -1;
        }

        @Override
        public float getDigSpeed(ItemStack stack, Block block, int meta) {
            for (String tool : getToolClasses(stack)) {
                if (block.isToolEffective(tool, meta)) {
                    return gear(stack, "Speed");
                }
            }
            return 1;
        }

        @Override
        public float func_150893_a(ItemStack stack, Block block) { // getDigSpeed, without metadata
            return getDigSpeed(stack, block, 0);
        }

        @Override
        public Multimap getAttributeModifiers(ItemStack stack) {
            return attackModifiers(stack);
        }
    }
}
