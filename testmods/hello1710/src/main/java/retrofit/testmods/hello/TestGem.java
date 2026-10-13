package retrofit.testmods.hello;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;

import java.util.List;

/**
 * A 1.7.10 item with subtypes (damage values), each with its own texture and name. Rubies are
 * edible (use over time, food stats, the stack shrinking), the way 1.7.10 food items work.
 */
public class TestGem extends Item {
    private static final String[] VARIANTS = {"ruby", "sapphire"};
    private static final String[] TEXTURES = {"emerald", "diamond"};
    private IIcon[] icons;

    public TestGem() {
        func_77627_a(true);                        // setHasSubtypes
        func_77655_b("test_gem");                  // setUnlocalizedName
        func_77637_a(CreativeTabs.field_78035_l);  // setCreativeTab(tabMaterials)
    }

    private static int variant(int meta) {
        return Math.max(0, Math.min(meta, VARIANTS.length - 1));
    }

    /** registerIcons */
    @Override
    public void func_94581_a(IIconRegister register) {
        icons = new IIcon[VARIANTS.length];
        for (int i = 0; i < VARIANTS.length; i++) {
            icons[i] = register.func_94245_a(TEXTURES[i]); // vanilla 1.7.10 item textures
        }
    }

    /** getIconFromDamage */
    @Override
    public IIcon func_77617_a(int meta) {
        return icons[variant(meta)];
    }

    /** getUnlocalizedName(ItemStack) */
    @Override
    public String func_77667_c(ItemStack stack) {
        return "item.test_gem." + VARIANTS[variant(stack.func_77960_j())];
    }

    /** getSubItems */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void func_150895_a(Item item, CreativeTabs tab, List list) {
        for (int i = 0; i < VARIANTS.length; i++) {
            list.add(new ItemStack(item, 1, i));
        }
    }
    /** getMaxItemUseDuration */
    @Override
    public int func_77626_a(ItemStack stack) {
        return stack.func_77960_j() == 0 ? 32 : 0;
    }

    /** getItemUseAction */
    @Override
    public EnumAction func_77661_b(ItemStack stack) {
        return stack.func_77960_j() == 0 ? EnumAction.eat : EnumAction.none;
    }

    /** onItemRightClick */
    @Override
    public ItemStack func_77659_a(ItemStack stack, World world, EntityPlayer player) {
        if (stack.func_77960_j() == 0 && player.func_71043_e(false)) { // canEat
            player.func_71008_a(stack, func_77626_a(stack)); // setItemInUse
        }
        return stack;
    }

    /** onEaten */
    @Override
    public ItemStack func_77654_b(ItemStack stack, World world, EntityPlayer player) {
        stack.field_77994_a--; // stackSize
        player.func_71024_bL().func_75122_a(4, 0.3f); // getFoodStats().addStats
        if (!world.field_72995_K) {
            System.out.println("[hello1710] " + player.func_70005_c_() + " ate a ruby, food now " + player.func_71024_bL().func_75116_a());
        }
        return stack;
    }
}
