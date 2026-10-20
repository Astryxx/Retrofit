package retrofit.legacy.bridge;

import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.storage.MapStorage;
import retrofit.api.HostWorld;
import retrofit.legacy.compat.FinalFields;

import java.io.File;
import java.lang.reflect.Field;

/** Creates the legacy server and the legacy world for each host level. */
public final class LegacyWorlds {
    private static BridgeServer server;
    private static volatile File saveDirectory;

    private LegacyWorlds() {
    }

    /** A server world (WorldServer) for server levels, a client world (WorldClient) for client levels. */
    static World create(HostWorld host, int dimension) {
        return host.isClientSide() ? ClientWorlds.create(host, dimension) : new BridgeWorldServer(host, dimension);
    }

    /** Where legacy world data of the running server goes: the host world's "retrofit" folder. */
    static void setSaveDirectory(File directory) {
        saveDirectory = directory;
    }

    static File saveDirectory() {
        File directory = saveDirectory;
        return directory != null ? directory : new File(Launch.minecraftHome, "world-data");
    }

    /** A legacy dimension's own folder, as 1.7.10 laid it out: the overworld at the top, others in DIM<n>. */
    static File dimensionDirectory(int dimension) {
        return dimension == 0 ? saveDirectory() : new File(saveDirectory(), "DIM" + dimension);
    }

    static WorldSettings survivalSettings() {
        return new WorldSettings(0L, WorldSettings.GameType.SURVIVAL, false, false, WorldType.field_77137_b); // DEFAULT
    }

    /** The legacy server, created on first use unless the dedicated-server boot already made one. */
    /**
     * Replaces FMLCommonHandler.getMinecraftServerInstance (HeadlessTransformer). On clients FML asks
     * for 1.7.10's integrated server, which never exists here; the legacy server is the one that
     * runs the host's worlds (null on a client of a remote server, as in 1.7.10). Mods use it for
     * FakePlayers, worlds by dimension and the player list.
     */
    public static MinecraftServer serverInstance(cpw.mods.fml.common.FMLCommonHandler handler) {
        return MinecraftServer.func_71276_C(); // getServer
    }

    /** Threads doing the legacy server's work off the server thread (world generation on host worker threads). */
    static final ThreadLocal<Boolean> SERVER_WORK = new ThreadLocal<Boolean>();

    /**
     * Replaces FMLCommonHandler.getEffectiveSide (HeadlessTransformer). FML went by thread: 1.7.10 ran
     * all server work on the "Server thread", mod loading included on dedicated servers. Here a
     * dedicated server is always the server side; on clients, server threads and legacy server work
     * on other threads (world generation) are the server side, everything else the client side.
     */
    public static cpw.mods.fml.relauncher.Side effectiveSide(cpw.mods.fml.common.FMLCommonHandler handler) {
        if (cpw.mods.fml.relauncher.FMLLaunchHandler.side() == cpw.mods.fml.relauncher.Side.SERVER) { // known from launch on
            return cpw.mods.fml.relauncher.Side.SERVER;
        }
        Thread thread = Thread.currentThread();
        if (thread.getName().equals("Server thread") || Boolean.TRUE.equals(SERVER_WORK.get())) {
            return cpw.mods.fml.relauncher.Side.SERVER;
        }
        return cpw.mods.fml.relauncher.Side.CLIENT;
    }

    public static synchronized BridgeServer server() {
        if (server == null) {
            MinecraftServer existing = MinecraftServer.func_71276_C(); // getServer
            server = existing instanceof BridgeServer ? (BridgeServer) existing : create(Launch.minecraftHome, false);
        }
        return server;
    }

    /**
     * Creates the legacy server. 1.7.10 keeps its user cache, ops, bans and whitelist in the working
     * directory, which is the 1.20.1 game folder, under the same names 1.20.1 uses; they are moved
     * into the Retrofit folder first so the host files are never touched.
     */
    public static synchronized BridgeServer create(File directory, boolean dedicated) {
        try {
            redirect(MinecraftServer.class, "field_152367_a", new File(directory, "usercache.json"));
            redirect(ServerConfigurationManager.class, "field_152613_a", new File(directory, "banned-players.json"));
            redirect(ServerConfigurationManager.class, "field_152614_b", new File(directory, "banned-ips.json"));
            redirect(ServerConfigurationManager.class, "field_152615_c", new File(directory, "ops.json"));
            redirect(ServerConfigurationManager.class, "field_152616_d", new File(directory, "whitelist.json"));
        } catch (Exception e) {
            throw new IllegalStateException("Retrofit could not move the files of the legacy server", e);
        }
        server = new BridgeServer(directory, dedicated);
        return server;
    }

    private static void redirect(Class<?> owner, String field, File file) throws Exception {
        FinalFields.set(owner.getDeclaredField(field), null, file);
    }

    private static NetHandlerPlayClient clientNetHandler;

    /**
     * The one client network handler: WorldClient needs it for its map storage, players for their
     * send queue, FML for client message contexts. Mod packets sent through it reach the host
     * (see BridgeClientNetHandler). Named by string so servers never load the client class.
     */
    static synchronized NetHandlerPlayClient clientNetHandler() {
        if (clientNetHandler != null) {
            return clientNetHandler;
        }
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            NetHandlerPlayClient handler = (NetHandlerPlayClient) unsafeClass.getMethod("allocateInstance", Class.class)
                    .invoke(unsafe, Class.forName("retrofit.legacy.bridge.BridgeClientNetHandler"));
            handler.field_147305_a = new MapStorage(null); // mapStorageOrigin
            clientNetHandler = handler;
            return handler;
        } catch (Exception e) {
            throw new IllegalStateException("Retrofit could not create a legacy client network handler", e);
        }
    }
}
