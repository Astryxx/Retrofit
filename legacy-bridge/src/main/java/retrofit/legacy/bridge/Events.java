package retrofit.legacy.bridge;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.Event;
import net.minecraft.command.ICommand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.WorldEvent;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * FML and Forge events legacy mods listen to, fired by the host at the matching 1.20.1 moments:
 * ticks (server, world, player, client, render), player login, logout, respawn and dimension
 * changes, the server lifecycle (FMLServerStartingEvent is where mods register commands),
 * PlayerInteractEvent, and world load, save and unload. Also runs legacy commands.
 */
public final class Events {
    private Events() {
    }

    // ---- Ticks ----

    public static void serverTick(boolean start) {
        if (start) {
            FMLCommonHandler.instance().onPreServerTick();
        } else {
            FMLCommonHandler.instance().onPostServerTick();
        }
    }

    public static void worldTick(Object world, boolean start) {
        if (start) {
            FMLCommonHandler.instance().onPreWorldTick((World) world);
        } else {
            FMLCommonHandler.instance().onPostWorldTick((World) world);
        }
    }

    public static void playerTick(Object player, boolean start) {
        if (start) {
            FMLCommonHandler.instance().onPlayerPreTick((EntityPlayer) player);
        } else {
            FMLCommonHandler.instance().onPlayerPostTick((EntityPlayer) player);
        }
    }

    /** Client only. */
    public static void clientTick(boolean start) {
        if (start) {
            FMLCommonHandler.instance().onPreClientTick();
        } else {
            FMLCommonHandler.instance().onPostClientTick();
        }
    }

    /** Client only. */
    public static void renderTick(boolean start, float partialTicks) {
        if (start) {
            FMLCommonHandler.instance().onRenderTickStart(partialTicks);
        } else {
            FMLCommonHandler.instance().onRenderTickEnd(partialTicks);
        }
    }

    // ---- Players ----

    public static void playerLoggedIn(Object player) {
        FMLCommonHandler.instance().firePlayerLoggedIn((EntityPlayer) player);
    }

    public static void playerLoggedOut(Object player) {
        FMLCommonHandler.instance().firePlayerLoggedOut((EntityPlayer) player);
    }

    public static void playerRespawned(Object player) {
        FMLCommonHandler.instance().firePlayerRespawnEvent((EntityPlayer) player);
    }

    public static void playerChangedDimension(Object player, int from, int to) {
        FMLCommonHandler.instance().firePlayerChangedDimensionEvent((EntityPlayer) player, from, to);
    }

    /**
     * PlayerInteractEvent: action 0 right-click air, 1 right-click block, 2 left-click block.
     * Returns {canceled, block use denied, item use denied} as 1/0.
     */
    public static int[] interact(Object player, int action, int x, int y, int z, int face, Object world) {
        PlayerInteractEvent event = ForgeEventFactory.onPlayerInteract((EntityPlayer) player,
                PlayerInteractEvent.Action.values()[action], x, y, z, face, (World) world);
        return new int[]{event.isCanceled() ? 1 : 0, event.useBlock == Event.Result.DENY ? 1 : 0, event.useItem == Event.Result.DENY ? 1 : 0};
    }

    // ---- Server lifecycle ----

    /** Before the host server starts: where legacy world data lives, then FMLServerAboutToStartEvent. */
    public static void serverAboutToStart(File saveDirectory) throws ReflectiveOperationException {
        LegacyWorlds.setSaveDirectory(saveDirectory);
        Field shared = World.class.getDeclaredField("s_mapStorage"); // Forge's global map storage, from the last world
        shared.setAccessible(true);
        shared.set(null, null);
        FMLCommonHandler.instance().handleServerAboutToStart(LegacyWorlds.server());
    }

    public static void serverStarting() {
        FMLCommonHandler.instance().handleServerStarting(LegacyWorlds.server());
    }

    public static void serverStarted() {
        FMLCommonHandler.instance().handleServerStarted();
    }

    public static void serverStopping() {
        FMLCommonHandler.instance().handleServerStopping();
    }

    public static void serverStopped() {
        FMLCommonHandler.instance().handleServerStopped();
    }

    // ---- Worlds ----

    public static void worldLoaded(Object world) {
        MinecraftForge.EVENT_BUS.post(new WorldEvent.Load((World) world));
    }

    /** WorldEvent.Save, then the world's saved data (WorldSavedData: per world, and the shared one). */
    public static void worldSaved(Object world) {
        World legacy = (World) world;
        MinecraftForge.EVENT_BUS.post(new WorldEvent.Save(legacy));
        if (legacy.perWorldStorage != null) {
            legacy.perWorldStorage.func_75744_a(); // saveAllData
        }
        if (legacy.field_72988_C != null) { // mapStorage
            legacy.field_72988_C.func_75744_a();
        }
    }

    public static void worldUnloaded(Object world) {
        MinecraftForge.EVENT_BUS.post(new WorldEvent.Unload((World) world));
    }

    // ---- Commands ----

    /** Names (and aliases) of commands mods registered; vanilla 1.7.10 commands are left to 1.20.1. */
    @SuppressWarnings("unchecked")
    public static String[] modCommands() {
        Map<String, ICommand> commands = LegacyWorlds.server().func_71187_D().func_71555_a(); // getCommandManager().getCommands()
        List<String> names = new ArrayList<String>();
        for (Map.Entry<String, ICommand> command : commands.entrySet()) {
            if (!command.getValue().getClass().getName().startsWith("net.minecraft.")) {
                names.add(command.getKey());
            }
        }
        return new TreeSet<String>(names).toArray(new String[0]);
    }

    /** Runs a legacy command line ("name args") for a player's mirror, or the server console (null). */
    public static int runCommand(Object player, String line) {
        Object sender = player != null ? player : LegacyWorlds.server();
        return LegacyWorlds.server().func_71187_D().func_71556_a((net.minecraft.command.ICommandSender) sender, line); // executeCommand
    }
}
