package retrofit.testmods.hello;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;

import java.util.List;

/** A 1.7.10 item with subtypes (damage values), each with its own texture and name. */
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
}
