package retrofit.testmods.hello;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.common.MinecraftForge;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.EnumHelper;

/** Retrofit's 1.7.10 test mod: lifecycle, enum extension, and a block with a ticking tile entity. */
@Mod(modid = "hello1710", name = "Hello 1.7.10", version = "1.0")
public class Hello1710 {
    @cpw.mods.fml.common.SidedProxy(clientSide = "retrofit.testmods.hello.ClientProxy", serverSide = "retrofit.testmods.hello.CommonProxy")
    public static CommonProxy proxy;

    @Mod.Instance("hello1710")
    public static Hello1710 instance;
    static TestGem gem;
    static SimpleNetworkWrapper network;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) throws Exception {
        System.out.println("[hello1710] preInit, config file: " + event.getSuggestedConfigurationFile());
        testEnumExtension();
        GameRegistry.registerBlock(new CounterBlock(), "counter_block");
        GameRegistry.registerTileEntity(CounterTile.class, "hello1710:counter");
        GameRegistry.registerBlock(new CrateBlock(), "crate");
        GameRegistry.registerTileEntity(CrateTile.class, "hello1710:crate");
        network = NetworkRegistry.INSTANCE.newSimpleChannel("hello1710");
        network.registerMessage(PingMessage.Handler.class, PingMessage.class, 0, Side.SERVER);
        network.registerMessage(PongMessage.Handler.class, PongMessage.class, 1, Side.CLIENT);
        HelloWorldGen.rubyOre = new HelloWorldGen.RubyOre();
        GameRegistry.registerBlock(HelloWorldGen.rubyOre, "ruby_ore");
        GameRegistry.registerWorldGenerator(new HelloWorldGen(), 10);
        GameRegistry.registerItem(new RubyHelmet(), "ruby_helmet");
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
        NetworkRegistry.INSTANCE.registerGuiHandler(instance, new CrateBlock.Guis());
        // Both registrations, as many mods did: a mod entity (tracking) and a global id (spawn egg).
        cpw.mods.fml.common.registry.EntityRegistry.registerGlobalEntityID(RubyGolem.class, "RubyGolem",
                cpw.mods.fml.common.registry.EntityRegistry.findGlobalUniqueEntityId(), 0xB01030, 0x401010);
        cpw.mods.fml.common.registry.EntityRegistry.registerModEntity(RubyGolem.class, "RubyGolem", 0, instance, 64, 3, true);
        cpw.mods.fml.common.registry.EntityRegistry.addSpawn(RubyGolem.class, 40, 1, 2, net.minecraft.entity.EnumCreatureType.monster,
                net.minecraft.world.biome.BiomeGenBase.field_76772_c); // plains
        proxy.registerRenderers();
        HelloEvents events = new HelloEvents();
        FMLCommonHandler.instance().bus().register(events);
        MinecraftForge.EVENT_BUS.register(events);
        // Two diamonds side by side make a sapphire; a ruby smelts into a diamond.
        GameRegistry.addRecipe(new ItemStack(gem, 1, 1), "DD", 'D', Items.field_151045_i);
        GameRegistry.addSmelting(new ItemStack(gem, 1, 0), new ItemStack(Items.field_151045_i), 1.0f);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new HelloEvents.HelloCommand());
        System.out.println("[hello1710] FMLServerStartingEvent: registered /hello");
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        System.out.println("[hello1710] postInit");
    }
}
