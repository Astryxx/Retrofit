package retrofit.testmods.hello;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.WorldSavedData;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.WorldEvent;

/**
 * The global hooks 1.7.10 mods use: FML tick and player events (FML bus), Forge events (Forge
 * bus), a command registered in FMLServerStartingEvent, and world saved data that persists.
 */
public class HelloEvents {
    private int serverTicks;
    private boolean interactSeen;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && ++serverTicks == 100) {
            System.out.println("[hello1710] FML server tick handler reached 100 ticks");
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        event.player.func_145747_a(new ChatComponentText("[hello1710] Welcome, " + event.player.func_70005_c_() + ", from 1.7.10"));
        System.out.println("[hello1710] FML login event for " + event.player.func_70005_c_());
    }

    @SubscribeEvent
    public void onInteract(PlayerInteractEvent event) {
        if (!interactSeen && !event.world.field_72995_K) {
            interactSeen = true;
            System.out.println("[hello1710] Forge PlayerInteractEvent " + event.action + " at " + event.x + "," + event.y + "," + event.z);
        }
    }

    /** Counts the times the overworld was loaded, in world saved data (saved with the world). */
    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (event.world.field_72995_K || event.world.field_73011_w.field_76574_g != 0) { // isRemote, provider.dimensionId
            return;
        }
        Data data = (Data) event.world.perWorldStorage.func_75742_a(Data.class, Data.NAME); // loadData
        if (data == null) {
            data = new Data(Data.NAME);
            event.world.perWorldStorage.func_75745_a(Data.NAME, data); // setData
        }
        data.loads++;
        data.func_76185_a(); // markDirty
        System.out.println("[hello1710] World data: the overworld has been loaded " + data.loads + " times");
    }

    public static class Data extends WorldSavedData {
        static final String NAME = "hello1710_data";
        int loads;

        public Data(String name) {
            super(name);
        }

        @Override
        public void func_76184_a(NBTTagCompound tag) { // readFromNBT
            loads = tag.func_74762_e("Loads");
        }

        @Override
        public void func_76187_b(NBTTagCompound tag) { // writeToNBT
            tag.func_74768_a("Loads", loads);
        }
    }

    /** /hello [words]: answers in chat. */
    public static class HelloCommand extends CommandBase {
        @Override
        public String func_71517_b() { // getCommandName
            return "hello";
        }

        @Override
        public String func_71518_a(ICommandSender sender) { // getCommandUsage
            return "/hello [words]";
        }

        /** The compile jar lacks CommandBase's compareTo bridge method; 1.7.10 sorts commands by name. */
        @Override
        public int compareTo(Object other) {
            return func_71517_b().compareTo(((net.minecraft.command.ICommand) other).func_71517_b());
        }

        @Override
        public int func_82362_a() { // getRequiredPermissionLevel
            return 0;
        }

        @Override
        public void func_71515_b(ICommandSender sender, String[] args) { // processCommand
            sender.func_145747_a(new ChatComponentText("[hello1710] Hello from a 1.7.10 command, " + sender.func_70005_c_()
                    + "! You said: " + func_82360_a(sender, args, 0))); // func_82360_a: join args
        }
    }
}
