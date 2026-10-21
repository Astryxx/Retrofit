package retrofit.legacy.bridge;

import cpw.mods.fml.common.DummyModContainer;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.ModMetadata;
import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.oredict.OreDictionary;

import java.lang.reflect.Field;

/**
 * 1.20.1 content in the legacy world. A 1.20.1 item with no 1.7.10 form (copper, other mods'
 * items) travels through legacy code as "retrofit:modern_item": its id and data ride in the
 * stack's NBT, and the damage value is the item's number in Retrofit's table (stable across
 * restarts), so legacy recipes and the Ore Dictionary tell items apart. Legacy chests, pipes,
 * machines and recipes keep and move them; they turn back into themselves on the 1.20.1 side.
 * Full-cube blocks travel as "retrofit:modern_block" instead, an ItemBlock of the solid stand-in
 * block, whose icons for that damage are the 1.20.1 block's textures: mods that take blocks as
 * covers or facades (Carpenter's Blocks) accept and draw them.
 */
public final class ModernContent {
    static final String ITEM_NAME = "retrofit:modern_item";
    static final String BLOCK_ITEM_NAME = "retrofit:modern_block";
    static final String TAG = "RetrofitModern";
    private static ModernItem item;
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
                solidBlock = new ModernBlock(true);
                openBlock = new ModernBlock(false);
                // the solid one's item carries 1.20.1 blocks (Block.getBlockFromItem goes by the shared id)
                GameRegistry.registerBlock(solidBlock, ModernBlockItem.class, "modern_block");
                GameRegistry.registerBlock(openBlock, null, "modern_open_block"); // no item: it stands for blocks of the 1.20.1 world
                blockItem = Item.func_150898_a(solidBlock); // getItemFromBlock
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
     * or null; {@code cube} for a full-cube block, carried as a block.
     */
    public static Object makeModernStack(String id, int count, int index, byte[] tag, String name, int maxStack, boolean cube) throws java.io.IOException {
        if (item == null) {
            return null;
        }
        ItemStack stack = new ItemStack(cube && blockItem != null ? blockItem : item, count, index);
        NBTTagCompound data = new NBTTagCompound();
        data.func_74778_a("id", id); // setString
        data.func_74778_a("Name", name == null ? id : name);
        data.func_74768_a("Max", maxStack); // setInteger
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

        /** getIcon: for the block carrier's damage, the 1.20.1 block's texture (client only). */
        @Override
        public net.minecraft.util.IIcon func_149691_a(int side, int meta) {
            net.minecraft.util.IIcon icon = solid ? ModernIcons.icon(meta, side) : null;
            return icon != null ? icon : super.func_149691_a(side, meta);
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
            NBTTagCompound data = ModernItem.data(stack);
            return data == null || data.func_74779_i("Name").isEmpty() ? "1.20.1 block" : data.func_74779_i("Name");
        }

        @Override
        public int getItemStackLimit(ItemStack stack) {
            NBTTagCompound data = ModernItem.data(stack);
            int max = data == null ? 0 : data.func_74762_e("Max");
            return max > 0 ? max : 64;
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
            NBTTagCompound data = data(stack);
            return data == null || data.func_74779_i("Name").isEmpty() ? "1.20.1 item" : data.func_74779_i("Name"); // getString
        }

        @Override
        public int getItemStackLimit(ItemStack stack) {
            NBTTagCompound data = data(stack);
            int max = data == null ? 0 : data.func_74762_e("Max"); // getInteger
            return max > 0 ? max : 64;
        }
    }
}
