package retrofit.testmods.reference;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/**
 * Retrofit's Pistronics play test scene (see retrofit.forge.test.PlayTest.pistronics), on a real 1.7.10
 * server: a mechanic piston facing west, fitted with a rod and with another behind it, a slime block in
 * front and stone beyond; powered, and 80 ticks later the row of blocks is logged ("[Reference] piston row").
 * Started with -Dretrofit.pistonReference=true.
 */
public final class PistonReference {
    private final WorldServer world;
    private final int x;
    private final int y;
    private final int z;
    private int ticks;

    PistonReference(WorldServer world) {
        this.world = world;
        this.x = world.func_72861_E().field_71574_a; // getSpawnPoint
        this.y = 200;
        this.z = world.func_72861_E().field_71573_c;
    }

    /** The legacy item whose registry name, cleaned as Retrofit cleans it, is {@code cleaned}. */
    private static Item item(String cleaned) {
        for (Object entry : Item.field_150901_e) { // itemRegistry
            String name = Item.field_150901_e.func_148750_c(entry); // getNameForObject
            if (name != null && name.toLowerCase().replaceAll("[^a-z0-9_.:/-]", "_").equals(cleaned)) {
                return (Item) entry;
            }
        }
        throw new IllegalStateException("no item " + cleaned);
    }

    /** The player uses {@code stack} on the top of the block at (bx, by, bz), facing east (the piston then faces west). */
    private void use(FakePlayer player, ItemStack stack, int bx, int by, int bz, int side) {
        player.func_70080_a(x + 3.5, y, z + 0.5, -90, 0); // setPositionAndRotation: east of the scene, looking west
        player.field_71071_by.func_70299_a(0, stack); // inventory.setInventorySlotContents
        player.field_71071_by.field_70461_c = 0; // currentItem
        Block block = world.func_147439_a(bx, by, bz); // getBlock
        if (!block.func_149727_a(world, bx, by, bz, player, side, 0.5f, 0.5f, 0.5f)) { // onBlockActivated
            stack.func_77943_a(player, world, bx, by, bz, side, 0.5f, 1f, 0.5f); // tryPlaceItemIntoWorld
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ticks++;
        FakePlayer player = FakePlayerFactory.getMinecraft(world);
        if (ticks == 20) {
            for (int dx = -4; dx <= 2; dx++) {
                world.func_147449_b(x + dx, y - 1, z, Blocks.field_150348_b); // setBlock: a stone floor
            }
            use(player, new ItemStack(item("pistronics2:mechanic_piston")), x, y - 1, z, 1); // the piston
        } else if (ticks == 30) {
            use(player, new ItemStack(item("pistronics2:rod_block")), x, y, z, 1); // fitted with a rod
        } else if (ticks == 40 && !Boolean.getBoolean("retrofit.pistonNoRodBehind")) {
            use(player, new ItemStack(item("pistronics2:rod_block")), x + 1, y - 1, z, 1); // a rod behind
        } else if (ticks == 50) {
            use(player, new ItemStack(item("pistronics2:slimeblock")), x - 1, y - 1, z, 1); // a slime block in front
            world.func_147449_b(x - 2, y, z, Blocks.field_150348_b); // stone beyond
        } else if (ticks == 60) {
            world.func_147449_b(x, y, z - 1, Blocks.field_150451_bX); // a redstone block powers the piston
        } else if (ticks == 140) {
            StringBuilder row = new StringBuilder();
            for (int dx = 2; dx >= -4; dx--) {
                row.append(dx).append(' ').append(Block.field_149771_c.func_148750_c(world.func_147439_a(x + dx, y, z))).append("; ");
            }
            System.out.println("[Reference] piston row, east to west: " + row);
        }
    }
}
