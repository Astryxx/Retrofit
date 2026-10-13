package retrofit.testmods.hello;

import cpw.mods.fml.common.network.IGuiHandler;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/** A block that opens a container GUI with FML's openGui, as nearly every 1.7.10 machine does. */
public class CrateBlock extends BlockContainer {
    public CrateBlock() {
        super(Material.field_151575_d); // Material.wood
        func_149663_c("crate"); // setBlockName
        func_149658_d("planks_oak"); // setBlockTextureName
        func_149647_a(CreativeTabs.field_78031_c); // setCreativeTab(tabDecorations)
        func_149711_c(1.5f); // setHardness
        setHarvestLevel("axe", 0);
    }

    /** createNewTileEntity */
    @Override
    public TileEntity func_149915_a(World world, int meta) {
        return new CrateTile();
    }

    /** onBlockActivated */
    @Override
    public boolean func_149727_a(World world, int x, int y, int z, EntityPlayer player, int side, float hitX, float hitY, float hitZ) {
        if (!world.field_72995_K) {
            player.openGui(Hello1710.instance, 0, world, x, y, z);
        }
        return true;
    }

    /** The GUI handler: a container on the server, its GUI on the client. */
    public static class Guis implements IGuiHandler {
        @Override
        public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
            TileEntity tile = world.func_147438_o(x, y, z);
            return tile instanceof CrateTile ? new CrateContainer(player.field_71071_by, (CrateTile) tile) : null;
        }

        @Override
        public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
            TileEntity tile = world.func_147438_o(x, y, z);
            return new CrateGui(player.field_71071_by, tile instanceof CrateTile ? (CrateTile) tile : new CrateTile());
        }
    }
}
