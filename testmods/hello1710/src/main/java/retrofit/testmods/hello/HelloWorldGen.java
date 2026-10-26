package retrofit.testmods.hello;

import cpw.mods.fml.common.IWorldGenerator;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityMobSpawner;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.feature.WorldGenMinable;

import java.util.Random;

/**
 * A 1.7.10 world generator, written the usual way: ore veins in stone with WorldGenMinable
 * (8 blocks into the next chunks, as 1.7.10 decorated), and every 8th chunk a crate on the
 * surface with a ruby inside (a tile entity a generator fills), with a vanilla chest and spawners
 * beside it (vanilla tile entities a generator sets up); in other dimensions only those.
 */
public class HelloWorldGen implements IWorldGenerator {
    static Block rubyOre;

    /** An ore that drops rubies. */
    public static class RubyOre extends Block {
        public RubyOre() {
            super(Material.field_151576_e); // rock
            func_149663_c("ruby_ore");
            func_149658_d("emerald_ore");
            func_149647_a(CreativeTabs.field_78030_b);
            func_149711_c(3.0f);
            setHarvestLevel("pickaxe", 2);
        }

        /** getItemDropped */
        @Override
        public Item func_149650_a(int meta, Random random, int fortune) {
            return Hello1710.gem;
        }
    }

    @Override
    public void generate(Random random, int chunkX, int chunkZ, World world, IChunkProvider generator, IChunkProvider provider) {
        if (world.field_73011_w.field_76574_g != 0) { // provider.dimensionId: elsewhere only the dungeons
            if (chunkX % 8 == 0 && chunkZ % 8 == 0) {
                int x = chunkX * 16 + 8;
                int z = chunkZ * 16 + 8;
                dungeon(world, x + 2, world.func_72976_f(x, z), z); // getHeightValue
            }
            return;
        }
        WorldGenMinable veins = new WorldGenMinable(rubyOre, 8, Blocks.field_150348_b); // stone
        for (int i = 0; i < 4; i++) {
            int x = chunkX * 16 + random.nextInt(16) + 8;
            int y = 5 + random.nextInt(35);
            int z = chunkZ * 16 + random.nextInt(16) + 8;
            veins.func_76484_a(world, random, x, y, z); // generate
        }
        if (chunkX % 8 == 0 && chunkZ % 8 == 0) {
            int x = chunkX * 16 + 8;
            int z = chunkZ * 16 + 8;
            int y = world.func_72976_f(x, z); // getHeightValue
            Block crate = Block.func_149684_b("hello1710:crate"); // getBlockFromName
            world.func_147465_d(x, y, z, crate, 0, 2); // setBlock
            TileEntity tile = world.func_147438_o(x, y, z); // getTileEntity
            if (tile instanceof CrateTile) {
                ((CrateTile) tile).func_70299_a(0, new ItemStack(Hello1710.gem, 1, 0));
                System.out.println("[hello1710] World generator placed a crate with a ruby at " + x + "," + y + "," + z);
            }
            dungeon(world, x + 2, y, z);
        }
    }

    /**
     * Vanilla blocks set up as 1.7.10 dungeon generators set them up: a chest filled with a ruby and
     * bread, a spawner of the test mod's golem and one of skeletons, through their tile entities.
     */
    private static void dungeon(World world, int x, int y, int z) {
        world.func_147465_d(x, y, z, Blocks.field_150486_ae, 0, 2); // setBlock chest
        TileEntity chest = world.func_147438_o(x, y, z); // getTileEntity
        if (chest instanceof TileEntityChest) {
            ((TileEntityChest) chest).func_70299_a(0, new ItemStack(Hello1710.gem, 2, 0)); // setInventorySlotContents
            ((TileEntityChest) chest).func_70299_a(5, new ItemStack(Items.field_151025_P, 3)); // bread
        }
        String[] mobs = {"RubyGolem", "Skeleton"};
        for (int i = 0; i < mobs.length; i++) {
            world.func_147465_d(x + 2 + 2 * i, y, z, Blocks.field_150474_ac, 0, 2); // setBlock mob_spawner
            TileEntity spawner = world.func_147438_o(x + 2 + 2 * i, y, z);
            if (spawner instanceof TileEntityMobSpawner) {
                ((TileEntityMobSpawner) spawner).func_145881_a().func_98272_a(mobs[i]); // getSpawnerBaseLogic().setEntityName
            }
        }
        System.out.println("[hello1710] World generator set up a chest " + (chest != null ? "" : "(no tile entity) ")
                + "and spawners at " + x + "," + y + "," + z);
    }
}
