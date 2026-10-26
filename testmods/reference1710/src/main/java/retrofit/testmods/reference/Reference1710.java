package retrofit.testmods.reference;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;

import java.io.File;

/**
 * Plays Retrofit's comparison scenes in real 1.7.10 (see {@link ReferenceDriver}) when started with
 * -Dretrofit.reference=<item list>; with -Dretrofit.nameDump=<file>, writes every item's name as real
 * 1.7.10 shows it (registry name, damage 0, display name; Retrofit's play test writes the same) once
 * the server has started; with -Dretrofit.pistonReference=true, plays the Pistronics piston scene
 * (see {@link PistonReference}). Does nothing otherwise. Only for reference runs: never put it in a Retrofit game.
 */
@Mod(modid = "retrofitreference", name = "Retrofit reference", version = "1", acceptableRemoteVersions = "*")
public final class Reference1710 {
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        String list = System.getProperty("retrofit.reference");
        if (list != null && event.getSide().isClient()) {
            ReferenceDriver driver = new ReferenceDriver(new File(list));
            FMLCommonHandler.instance().bus().register(driver);
        }
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) throws java.io.IOException {
        if (Boolean.getBoolean("retrofit.pistonReference")) {
            FMLCommonHandler.instance().bus().register(new PistonReference(net.minecraft.server.MinecraftServer.func_71276_C().func_71218_a(0))); // getServer, worldServerForDimension
        }
        String dump = System.getProperty("retrofit.nameDump");
        if (dump == null) {
            return;
        }
        java.io.PrintWriter out = new java.io.PrintWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(dump), "UTF-8"));
        try {
            for (Object entry : net.minecraft.item.Item.field_150901_e) { // itemRegistry
                net.minecraft.item.Item item = (net.minecraft.item.Item) entry;
                String name;
                try {
                    name = new net.minecraft.item.ItemStack(item, 1, 0).func_82833_r(); // getDisplayName
                } catch (Throwable t) {
                    name = "<" + t.getClass().getSimpleName() + ">";
                }
                out.println(net.minecraft.item.Item.field_150901_e.func_148750_c(item) + "\t0\t" + name); // getNameForObject
            }
        } finally {
            out.close();
        }
    }
}
