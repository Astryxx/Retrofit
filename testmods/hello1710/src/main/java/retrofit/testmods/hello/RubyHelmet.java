package retrofit.testmods.hello;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/** 1.7.10 armor with its own texture and a worn effect (onArmorTick), as mod armor sets do. */
public class RubyHelmet extends ItemArmor {
    private int wornTicks;

    public RubyHelmet() {
        super(ItemArmor.ArmorMaterial.DIAMOND, 3, 0); // render index 3 (diamond), helmet
        func_77655_b("ruby_helmet"); // setUnlocalizedName
        func_111206_d("diamond_helmet"); // setTextureName
        func_77637_a(CreativeTabs.field_78037_j); // tabCombat
    }

    @Override
    public String getArmorTexture(ItemStack stack, Entity entity, int slot, String type) {
        return "hello1710:textures/models/armor/Ruby_Layer_1.png"; // mixed case on purpose: 1.7.10 allowed it
    }

    @Override
    public void onArmorTick(World world, EntityPlayer player, ItemStack stack) {
        if (!world.field_72995_K && ++wornTicks == 40) {
            System.out.println("[hello1710] Ruby helmet worn by " + player.func_70005_c_() + " for 40 ticks");
        }
    }
}
