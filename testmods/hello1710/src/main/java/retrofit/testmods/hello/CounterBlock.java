package retrofit.testmods.hello;

import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/** A plain 1.7.10 block with a tile entity. */
public class CounterBlock extends BlockContainer {
    public CounterBlock() {
        super(Material.field_151576_e); // Material.rock
        func_149663_c("counter_block"); // setBlockName
        func_149658_d("hello1710:counter_block"); // setBlockTextureName
        func_149647_a(CreativeTabs.field_78030_b); // setCreativeTab(tabBlock)
    }

    /** createNewTileEntity */
    @Override
    public TileEntity func_149915_a(World world, int meta) {
        return new CounterTile();
    }
}
