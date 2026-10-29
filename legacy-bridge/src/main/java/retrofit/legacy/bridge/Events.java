package retrofit.legacy.bridge;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.FMLContainer;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.registry.GameData;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import cpw.mods.fml.common.eventhandler.Event;
import net.minecraft.command.ICommand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.WorldEvent;
import retrofit.legacy.gl.Capture;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
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
            HostTiles.flushAll();
            HostSpawner.flushAll();
            LegacyPlayers.tickPortalCooldowns();
            HostCreatures.tickEnd();
        }
    }

    /** The allowed spawn types last given to each server world (see worldTick): 1 hostiles, 2 animals. */
    private static final Map<World, Integer> SPAWN_TYPES = new java.util.WeakHashMap<World, Integer>();

    /**
     * difficulty: the host level's (0 peaceful ... 3 hard), kept in World.difficultySetting where mods read it.
     * hostiles, animals: what the server spawns, given to the world as MinecraftServer did for every world
     * (setAllowedSpawnTypes, at load and when the difficulty changed): Forge passes them on to the world's
     * provider, where dimension mods keep their own (Erebus' spawner spawns nothing while its provider says no).
     */
    public static void worldTick(Object world, boolean start, int difficulty, boolean hostiles, boolean animals) {
        World legacy = (World) world;
        legacy.field_73013_u = net.minecraft.world.EnumDifficulty.func_151523_a(difficulty); // difficultySetting, getDifficultyEnum
        int types = (hostiles ? 1 : 0) | (animals ? 2 : 0);
        if (start && !legacy.field_72995_K && !Integer.valueOf(types).equals(SPAWN_TYPES.put(legacy, types))) { // isRemote
            legacy.func_72891_a(hostiles, animals); // setAllowedSpawnTypes
        }
        if (start) {
            FMLCommonHandler.instance().onPreWorldTick((World) world);
        } else {
            FMLCommonHandler.instance().onPostWorldTick((World) world);
            // A client-side handler drawing here draws into the capture nothing reads (drawing
            // belongs to the render tick): clear it so it can't pile up. Server threads must not
            // clear a capture the render thread is about to draw.
            if (((World) world).field_72995_K) { // isRemote
                Capture.begin();
            }
        }
    }

    public static void playerTick(Object player, boolean start) {
        if (start) {
            FMLCommonHandler.instance().onPlayerPreTick((EntityPlayer) player);
        } else {
            FMLCommonHandler.instance().onPlayerPostTick((EntityPlayer) player);
            // As worldTick: nothing reads what a client-side tick handler drew.
            if (((EntityPlayer) player).field_70170_p.field_72995_K) { // worldObj.isRemote
                Capture.begin();
            }
        }
    }

    /** Client only. */
    public static void clientTick(boolean start) {
        ClientPlayers.viewFromPlayer();
        if (start) {
            FMLCommonHandler.instance().onPreClientTick();
        } else {
            FMLCommonHandler.instance().onPostClientTick();
            // Nothing reads what a tick handler drew (ticks aren't drawn; the frame is drawn after
            // them, as on 1.7.10): clear it, or a handler drawing every tick piles up vertices.
            Capture.begin();
        }
    }

    /** Client only. Hold Bridge.LOCK (handlers draw with the shared Tessellator). */
    public static void renderTick(boolean start, float partialTicks) {
        ClientPlayers.viewFromPlayer();
        // 1.7.10 never reached a render tick mid-draw; a mod's draw left unfinished elsewhere must not break these
        LegacyRendering.resetTessellatorIfDrawing();
        if (start) {
            FMLCommonHandler.instance().onRenderTickStart(partialTicks);
            // Nothing reads what a start handler drew (the host's own frame is drawn over it, as on
            // 1.7.10; only the tick end's drawing is captured, see ClientEvents.drawRenderTickEnd):
            // clear it, or a handler drawing every frame (AoA's notice board) piles up vertices
            // until the heap runs out.
            Capture.begin();
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

    /**
     * A player's left click on an entity (a legacy one, a player's mirror or a 1.20.1 creature's
     * stand-in), as attackTargetEntityWithCurrentItem began it: AttackEntityEvent, then the held
     * item's onLeftClickEntity (Mob Battle's sticks pick mobs with it). Whether the attack ends there.
     */
    public static boolean attackEntity(Object player, Object target) {
        EntityPlayer attacker = (EntityPlayer) player;
        if (MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.entity.player.AttackEntityEvent(attacker, (net.minecraft.entity.Entity) target))) {
            return true;
        }
        if (!ManaMetalCompatibility.canUseWeapon(attacker)) return true;
        net.minecraft.item.ItemStack stack = attacker.func_71045_bC(); // getCurrentEquippedItem
        return stack != null && stack.func_77973_b().onLeftClickEntity(stack, attacker, (net.minecraft.entity.Entity) target);
    }

    /** EntityInteractEvent for a right click on a 1.20.1 creature's stand-in (legacy creatures get all of interactWith); whether it was cancelled. */
    public static boolean interactEntity(Object player, Object target) {
        return MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.entity.player.EntityInteractEvent((EntityPlayer) player, (net.minecraft.entity.Entity) target));
    }

    // ---- Server lifecycle ----

    /** The cancellable pre-harvest event, with the block still present in the world. */
    public static int[] playerBreakBlock(Object player, Object world, int x, int y, int z, int experience) {
        World level = (World) world;
        net.minecraftforge.event.world.BlockEvent.BreakEvent event = new net.minecraftforge.event.world.BlockEvent.BreakEvent(
                x, y, z, level, level.func_147439_a(x, y, z), level.func_72805_g(x, y, z), (EntityPlayer) player);
        event.setExpToDrop(experience);
        MinecraftForge.EVENT_BUS.post(event);
        return new int[]{event.isCanceled() ? 1 : 0, event.getExpToDrop()};
    }

    /** Item pickup handlers may consume the drop (currency), change it, or refuse pickup. */
    public static Object[] playerPickupItem(Object player, Object stack, double x, double y, double z) {
        EntityPlayer owner = (EntityPlayer) player;
        net.minecraft.entity.item.EntityItem drop = new net.minecraft.entity.item.EntityItem(owner.field_70170_p, x, y, z,
                (net.minecraft.item.ItemStack) stack);
        net.minecraftforge.event.entity.player.EntityItemPickupEvent event = new net.minecraftforge.event.entity.player.EntityItemPickupEvent(owner, drop);
        MinecraftForge.EVENT_BUS.post(event);
        return new Object[]{event.isCanceled(), event.getResult().ordinal(), drop.field_70128_L, drop.func_92059_d()};
    }

    /**
     * Before the host server starts: where legacy world data lives, the world's legacy numeric
     * ids (as 1.7.10 kept them in level.dat, so saved legacy data keeps meaning the same items when
     * mods are added), then FMLServerAboutToStartEvent.
     */
    public static void serverAboutToStart(File saveDirectory) throws Exception {
        ChunkLoading.register();
        LegacyWorlds.setSaveDirectory(saveDirectory);
        try {
            loadIds(new File(saveDirectory, "fml-ids.dat"));
        } catch (Exception e) {
            System.out.println("[Retrofit] Could not restore this world's legacy ids (the mods' own are used): " + e);
        }
        Field shared = World.class.getDeclaredField("s_mapStorage"); // Forge's global map storage, from the last world
        shared.setAccessible(true);
        shared.set(null, null);
        BridgeWorldServer.resetSharedMapStorage();
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
        GameData.revertToFrozen(); // the next world (singleplayer) starts from the mods' own ids
    }

    /** FML reads and writes its id data alongside a WorldInfo (it only uses the name). */
    private static net.minecraft.world.storage.WorldInfo worldInfo() {
        return new net.minecraft.world.storage.WorldInfo(LegacyWorlds.survivalSettings(), "Retrofit");
    }

    private static FMLContainer fml() {
        Object container = Loader.instance().getIndexedModList().get("FML");
        if (container instanceof cpw.mods.fml.common.InjectedModContainer) {
            container = ((cpw.mods.fml.common.InjectedModContainer) container).wrappedContainer;
        }
        return (FMLContainer) container;
    }

    private static void loadIds(File file) throws Exception {
        if (file.isFile()) {
            NBTTagCompound tag = CompressedStreamTools.func_74797_a(file); // read
            fml().readData(null, worldInfo(), new HashMap<String, NBTBase>(), tag);
            System.out.println("[Retrofit] Restored this world's legacy ids from " + file);
        }
        saveIds(file);
    }

    /** The id map for a connecting client (1.7.10 sent it in FML's handshake). */
    public static byte[] idData() throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        CompressedStreamTools.func_74799_a(fml().getDataForWriting(null, worldInfo()), bytes); // writeCompressed
        return bytes.toByteArray();
    }

    /**
     * Client: use the server's id map for this connection, as FML 1.7.10's client handshake did: the map is read as
     * FMLContainer.readData reads a save's, but injected as a remote world's. As a local world's, ids needing a remap
     * made FML back up the world, which a client joining a server hasn't, and the server's ids were never taken.
     */
    public static void applyIds(byte[] data) throws Exception {
        NBTTagCompound tag = CompressedStreamTools.func_74796_a(new java.io.ByteArrayInputStream(data)); // readCompressed
        if (!tag.func_74764_b("ItemData")) { // hasKey: an older format, read as FML reads it
            fml().readData(null, worldInfo(), new HashMap<String, NBTBase>(), tag);
            return;
        }
        Map<String, Integer> ids = new java.util.LinkedHashMap<String, Integer>();
        for (NBTTagCompound entry : compounds(tag, "ItemData")) {
            ids.put(entry.func_74779_i("K"), entry.func_74762_e("V")); // getString, getInteger
        }
        java.util.Set<Integer> blocked = new java.util.HashSet<Integer>();
        for (int id : tag.func_74759_k("BlockedItemIds")) { // getIntArray
            blocked.add(id);
        }
        Map<String, String> blockAliases = new HashMap<String, String>();
        for (NBTTagCompound entry : compounds(tag, "BlockAliases")) {
            blockAliases.put(entry.func_74779_i("K"), entry.func_74779_i("V"));
        }
        Map<String, String> itemAliases = new HashMap<String, String>();
        for (NBTTagCompound entry : compounds(tag, "ItemAliases")) {
            itemAliases.put(entry.func_74779_i("K"), entry.func_74779_i("V"));
        }
        java.util.Set<String> blockSubstitutions = new java.util.HashSet<String>();
        for (NBTTagCompound entry : compounds(tag, "BlockSubstitutions")) {
            blockSubstitutions.add(entry.func_74779_i("K"));
        }
        java.util.Set<String> itemSubstitutions = new java.util.HashSet<String>();
        for (NBTTagCompound entry : compounds(tag, "ItemSubstitutions")) {
            itemSubstitutions.add(entry.func_74779_i("K"));
        }
        List<String> missing = GameData.injectWorldIDMap(ids, blocked, blockAliases, itemAliases, blockSubstitutions, itemSubstitutions,
                true, false); // injectFrozenData, isLocalWorld
        if (missing != null && !missing.isEmpty()) {
            System.out.println("[Retrofit] The server has blocks and items this client hasn't: " + missing);
        }
    }

    private static List<NBTTagCompound> compounds(NBTTagCompound tag, String key) {
        List<NBTTagCompound> result = new ArrayList<NBTTagCompound>();
        net.minecraft.nbt.NBTTagList list = tag.func_150295_c(key, 10); // getTagList (compounds)
        for (int i = 0; i < list.func_74745_c(); i++) { // tagCount
            result.add(list.func_150305_b(i)); // getCompoundTagAt
        }
        return result;
    }

    /** Client, after leaving a server: back to the mods' own ids. */
    public static void revertIds() {
        GameData.revertToFrozen();
    }

    /** Writes the legacy id map (FML's level.dat data) for this world. */
    public static void saveIds(File file) throws Exception {
        file.getParentFile().mkdirs();
        NBTTagCompound tag = fml().getDataForWriting(null, worldInfo());
        CompressedStreamTools.func_74793_a(tag, file); // safeWrite
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
        if (!legacy.field_72995_K && legacy.field_73011_w.field_76574_g == 0) { // the overworld's save carries the id map
            try {
                saveIds(new File(LegacyWorlds.saveDirectory(), "fml-ids.dat"));
            } catch (Exception e) {
                System.out.println("[Retrofit] Could not save legacy ids: " + e);
            }
        }
    }

    public static void worldUnloaded(Object world) {
        MinecraftForge.EVENT_BUS.post(new WorldEvent.Unload((World) world));
        synchronized (CHUNKS) {
            CHUNKS.remove(world);
            UNLOADED.remove(world);
        }
    }

    // ---- Chunks ----

    /**
     * Legacy worlds keep no chunks (the host level holds the blocks), so chunk events carry a chunk
     * view (BridgeChunk) per loaded host chunk: the one the world's chunk provider hands out while the
     * chunk is loaded.
     */
    private static final Map<Object, Map<Long, net.minecraft.world.chunk.Chunk>> CHUNKS = new java.util.IdentityHashMap<Object, Map<Long, net.minecraft.world.chunk.Chunk>>();
    private static int busId = -1;

    private static net.minecraft.world.chunk.Chunk chunk(Object world, int x, int z, boolean create) {
        net.minecraft.world.chunk.Chunk chunk = loadedChunk(world, x, z);
        if (chunk != null || !create) {
            return chunk;
        }
        // the view mods already have (asked outside the lock: the provider asks loadedChunk under its own)
        net.minecraft.world.chunk.Chunk provided = ((World) world).func_72863_F().func_73154_d(x, z); // getChunkProvider().provideChunk
        synchronized (CHUNKS) {
            Map<Long, net.minecraft.world.chunk.Chunk> chunks = CHUNKS.get(world);
            if (chunks == null) {
                chunks = new HashMap<Long, net.minecraft.world.chunk.Chunk>();
                CHUNKS.put(world, chunks);
            }
            long key = (long) x << 32 | (z & 0xFFFFFFFFL);
            chunk = chunks.get(key);
            if (chunk == null) {
                chunk = provided;
                chunks.put(key, chunk);
                Map<Long, net.minecraft.world.chunk.Chunk> unloaded = UNLOADED.get(world);
                if (unloaded != null) {
                    unloaded.remove(key); // loaded again before it was saved
                }
            }
            return chunk;
        }
    }

    /** The chunk view chunk events carried for a loaded chunk, or null (see BridgeChunkProvider.provideChunk). */
    static net.minecraft.world.chunk.Chunk loadedChunk(Object world, int x, int z) {
        synchronized (CHUNKS) {
            Map<Long, net.minecraft.world.chunk.Chunk> chunks = CHUNKS.get(world);
            return chunks == null ? null : chunks.get((long) x << 32 | (z & 0xFFFFFFFFL));
        }
    }

    /** Whether any legacy mod listens to an event class (FML keeps its listeners in a static LISTENER_LIST). */
    static boolean listenedTo(Class<?> eventClass) {
        try {
            if (busId < 0) {
                Field bus = cpw.mods.fml.common.eventhandler.EventBus.class.getDeclaredField("busID");
                bus.setAccessible(true);
                busId = bus.getInt(MinecraftForge.EVENT_BUS);
            }
            Field list = eventClass.getDeclaredField("LISTENER_LIST");
            list.setAccessible(true);
            Object listeners = list.get(null);
            return listeners != null && ((cpw.mods.fml.common.eventhandler.ListenerList) listeners).getListeners(busId).length > 0;
        } catch (ReflectiveOperationException e) {
            return true; // can't tell: fire it
        }
    }

    /** Whether any legacy mod listens to chunk load, unload or data events (the host skips them otherwise). */
    public static boolean chunkEventsWanted() {
        return listenedTo(net.minecraftforge.event.world.ChunkEvent.Load.class) || listenedTo(net.minecraftforge.event.world.ChunkEvent.Unload.class)
                || listenedTo(net.minecraftforge.event.world.ChunkDataEvent.Load.class) || listenedTo(net.minecraftforge.event.world.ChunkDataEvent.Save.class);
    }

    /** Whether any legacy mod listens to players starting or stopping to watch chunks. */
    public static boolean chunkWatchWanted() {
        return listenedTo(net.minecraftforge.event.world.ChunkWatchEvent.Watch.class)
                || listenedTo(net.minecraftforge.event.world.ChunkWatchEvent.UnWatch.class);
    }

    /**
     * The host sent a player a chunk (watched) or had it forget one: ChunkWatchEvent.Watch or UnWatch with the
     * player's mirror, as 1.7.10's server fired them after sending a chunk. Mods send what they keep in chunks to
     * the players watching them (Forge Multipart's parts, CodeChickenLib's world extensions).
     */
    public static void chunkWatched(Object player, int x, int z, boolean watched) {
        net.minecraft.world.ChunkCoordIntPair chunk = new net.minecraft.world.ChunkCoordIntPair(x, z);
        net.minecraft.entity.player.EntityPlayerMP mirror = (net.minecraft.entity.player.EntityPlayerMP) player;
        MinecraftForge.EVENT_BUS.post(watched ? new net.minecraftforge.event.world.ChunkWatchEvent.Watch(chunk, mirror)
                : new net.minecraftforge.event.world.ChunkWatchEvent.UnWatch(chunk, mirror));
    }

    /** A host chunk was loaded: ChunkDataEvent.Load with what mods saved in it (if anything), then ChunkEvent.Load. */
    public static void chunkLoaded(Object world, int x, int z, byte[] data) throws Exception {
        net.minecraft.world.chunk.Chunk chunk = chunk(world, x, z, true);
        chunk.field_76636_d = true; // isChunkLoaded (a view handed out before may have been unloaded since)
        if (data != null) {
            MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.world.ChunkDataEvent.Load(chunk, Bridge.fromBytes(data)));
        }
        MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.world.ChunkEvent.Load(chunk));
    }

    /**
     * A host chunk is being saved: ChunkDataEvent.Save; returns the data mods put in (null if none). A chunk saved
     * as it unloads is the one its unload event carried (no longer loaded), as on 1.7.10, which unloaded and then
     * saved it: mods drop what they keep for it there (CodeChickenLib).
     */
    public static byte[] chunkSaved(Object world, int x, int z) throws Exception {
        net.minecraft.world.chunk.Chunk chunk;
        synchronized (CHUNKS) {
            Map<Long, net.minecraft.world.chunk.Chunk> unloaded = UNLOADED.get(world);
            chunk = unloaded == null ? null : unloaded.remove((long) x << 32 | (z & 0xFFFFFFFFL));
        }
        if (chunk == null) {
            chunk = chunk(world, x, z, true);
        }
        NBTTagCompound tag = new NBTTagCompound();
        MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.world.ChunkDataEvent.Save(chunk, tag));
        return tag.func_82582_d() ? null : Bridge.toBytes(tag); // hasNoTags
    }

    /** Chunks unloaded and not saved yet, by world (see chunkSaved); unchanged chunks are never saved, so few are kept. */
    private static final Map<Object, Map<Long, net.minecraft.world.chunk.Chunk>> UNLOADED = new java.util.WeakHashMap<Object, Map<Long, net.minecraft.world.chunk.Chunk>>();

    public static void chunkUnloaded(Object world, int x, int z) {
        net.minecraft.world.chunk.Chunk chunk = chunk(world, x, z, false);
        if (chunk == null) {
            return;
        }
        chunk.field_76636_d = false;
        MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.world.ChunkEvent.Unload(chunk));
        synchronized (CHUNKS) {
            long key = (long) x << 32 | (z & 0xFFFFFFFFL);
            Map<Long, net.minecraft.world.chunk.Chunk> chunks = CHUNKS.get(world);
            if (chunks != null) {
                chunks.remove(key);
            }
            Map<Long, net.minecraft.world.chunk.Chunk> unloaded = UNLOADED.get(world);
            if (unloaded == null) {
                unloaded = new java.util.LinkedHashMap<Long, net.minecraft.world.chunk.Chunk>() {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Long, net.minecraft.world.chunk.Chunk> eldest) {
                        return size() > 1024;
                    }
                };
                UNLOADED.put(world, unloaded);
            }
            unloaded.put(key, chunk);
        }
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

    /** Tab completions for the last word of a legacy command line ("name args..."). */
    @SuppressWarnings("unchecked")
    public static String[] commandCompletions(Object player, String line) {
        Object sender = player != null ? player : LegacyWorlds.server();
        List<String> options = LegacyWorlds.server().func_71187_D().func_71558_b((net.minecraft.command.ICommandSender) sender, line); // getPossibleCommands
        return options == null ? new String[0] : options.toArray(new String[0]);
    }

    /** Runs a legacy command line ("name args") for a player's mirror, or the server console (null). */
    public static int runCommand(Object player, String line) {
        Object sender = player != null ? player : LegacyWorlds.server();
        return LegacyWorlds.server().func_71187_D().func_71556_a((net.minecraft.command.ICommandSender) sender, line); // executeCommand
    }
}
