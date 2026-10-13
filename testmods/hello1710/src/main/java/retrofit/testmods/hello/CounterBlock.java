package retrofit.testmods.hello;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * A 1.7.10 block with a tile entity, exercising interaction the way mods do it:
 * - right-click: tells the player the tick count (chat) and gives a diamond (inventory);
 * - placing: faces the player (metadata from yaw in onBlockPlacedBy);
 * - breaking: drops a ruby gem from breakBlock (spawned EntityItem), and needs a stone pickaxe.
 */
public class CounterBlock extends BlockContainer {
    public CounterBlock() {
        super(Material.field_151576_e); // Material.rock
        func_149663_c("counter_block"); // setBlockName
        func_149658_d("redstone_lamp_on"); // setBlockTextureName: a vanilla 1.7.10 texture
        func_149647_a(CreativeTabs.field_78030_b); // setCreativeTab(tabBlock)
        func_149711_c(2.0f); // setHardness
        setHarvestLevel("pickaxe", 1);
    }

    /** createNewTileEntity */
    @Override
    public TileEntity func_149915_a(World world, int meta) {
        return new CounterTile();
    }

    /** onBlockActivated */
    @Override
    public boolean func_149727_a(World world, int x, int y, int z, EntityPlayer player, int side, float hitX, float hitY, float hitZ) {
        if (!world.field_72995_K) { // isRemote
            TileEntity tile = world.func_147438_o(x, y, z); // getTileEntity
            int ticks = tile instanceof CounterTile ? ((CounterTile) tile).ticks() : -1;
            player.func_145747_a(new ChatComponentText("[hello1710] Counter at " + ticks + " ticks, facing "
                    + world.func_72805_g(x, y, z) + "; have a diamond")); // addChatMessage, getBlockMetadata
            player.field_71071_by.func_70441_a(new ItemStack(Items.field_151045_i)); // inventory.addItemStackToInventory(diamond)
            System.out.println("[hello1710] Activated by " + player.func_70005_c_() + " (" + player.getClass().getSimpleName() + ")");
        }
        return true;
    }

    /** onBlockPlacedBy: metadata 0-3 from the direction the player faces */
    @Override
    public void func_149689_a(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        int facing = MathHelper.func_76128_c(placer.field_70177_z * 4.0f / 360.0f + 0.5) & 3; // floor_double(rotationYaw...)
        world.func_72921_c(x, y, z, facing, 2); // setBlockMetadataWithNotify
        System.out.println("[hello1710] Placed by " + placer.func_70005_c_() + ", facing " + facing);
    }

    /** breakBlock */
    @Override
    public void func_149749_a(World world, int x, int y, int z, Block block, int meta) {
        world.func_72838_d(new EntityItem(world, x + 0.5, y + 0.5, z + 0.5, new ItemStack(Hello1710.gem, 1, 0))); // spawnEntityInWorld
        System.out.println("[hello1710] Broken (meta " + meta + "), dropped a ruby");
        super.func_149749_a(world, x, y, z, block, meta);
    }
}
