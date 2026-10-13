package retrofit.testmods.hello;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.EnumHelper;

/** Retrofit's 1.7.10 test mod: lifecycle, enum extension, and a block with a ticking tile entity. */
@Mod(modid = "hello1710", name = "Hello 1.7.10", version = "1.0")
public class Hello1710 {
    static TestGem gem;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) throws Exception {
        System.out.println("[hello1710] preInit, config file: " + event.getSuggestedConfigurationFile());
        testEnumExtension();
        GameRegistry.registerBlock(new CounterBlock(), "counter_block");
        GameRegistry.registerTileEntity(CounterTile.class, "hello1710:counter");
        gem = new TestGem();
        GameRegistry.registerItem(gem, "test_gem");
        System.out.println("[hello1710] Registered counter_block (with CounterTile) and test_gem");
    }

    /** Mods add enum constants at runtime (tool materials, rarities, creature types); Java 17 broke Forge's way of doing it. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void testEnumExtension() throws Exception {
        Class rarity = Class.forName("net.minecraft.item.EnumRarity");
        Class formatting = Class.forName("net.minecraft.util.EnumChatFormatting");
        Enum added = EnumHelper.addEnum(rarity, "RETROFIT",
                new Class[]{formatting, String.class}, new Object[]{Enum.valueOf(formatting, "GOLD"), "Retrofit"});
        Object[] values = (Object[]) rarity.getMethod("values").invoke(null);
        boolean found = Enum.valueOf(rarity, "RETROFIT") == added;
        System.out.println("[hello1710] EnumHelper added " + added + " (ordinal " + added.ordinal() + "), values() now has "
                + values.length + " entries, valueOf finds it: " + found);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        System.out.println("[hello1710] init");
        // Two diamonds side by side make a sapphire; a ruby smelts into a diamond.
        GameRegistry.addRecipe(new ItemStack(gem, 1, 1), "DD", 'D', Items.field_151045_i);
        GameRegistry.addSmelting(new ItemStack(gem, 1, 0), new ItemStack(Items.field_151045_i), 1.0f);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        System.out.println("[hello1710] postInit");
    }
}
