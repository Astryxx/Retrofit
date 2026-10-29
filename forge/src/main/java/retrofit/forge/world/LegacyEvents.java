package retrofit.forge.world;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import retrofit.forge.RetrofitMod;

import java.util.HashMap;
import java.util.Map;

/**
 * Fires the legacy FML and Forge events at the matching 1.20.1 moments (see the legacy bridge's
 * Events): server, world and player ticks, logins, respawns and dimension changes, the server
 * lifecycle, PlayerInteractEvent, and world load, save and unload. Commands mods register in
 * FMLServerStartingEvent become 1.20.1 commands taking the rest of the line as arguments.
 * Client ticks are fired by the client's own subscriber (retrofit.forge.client.LegacyClientEvents).
 *
 * A legacy handler that throws is reported once and never stops the server.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID)
public final class LegacyEvents {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static String[] commands = new String[0];

    private LegacyEvents() {
    }

    private static boolean active() {
        return RetrofitMod.runtime() != null;
    }

    private static void fire(String what, Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            LegacyErrors.report("event handler", what, e);
        }
    }

    // ---- Server lifecycle ----

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        if (active()) {
            java.io.File directory = event.getServer().getWorldPath(LevelResource.ROOT).resolve("retrofit").toFile();
            fire("server about to start", () -> LegacyBridge.call("serverAboutToStart", directory));
        }
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        if (!active()) {
            return;
        }
        fire("server starting", () -> LegacyBridge.call("serverStarting"));
        try {
            commands = LegacyBridge.call("modCommands");
        } catch (RuntimeException e) {
            LegacyErrors.report("commands", "being listed", e);
        }
        registerCommands(event.getServer().getCommands().getDispatcher());
        if (commands.length > 0) {
            LOGGER.info("Legacy commands: {}", String.join(", ", commands));
        }
    }

    /** /reload rebuilds the command tree: put the legacy commands back. */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        if (active()) {
            registerCommands(event.getDispatcher());
        }
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String name : commands) {
            if (dispatcher.getRoot().getChild(name) != null) {
                continue; // 1.20.1 (or another mod) already has it
            }
            dispatcher.register(Commands.literal(name)
                    .executes(context -> run(context.getSource(), name))
                    .then(Commands.argument("arguments", StringArgumentType.greedyString())
                            .suggests((context, builder) -> suggest(context.getSource(), name, builder))
                            .executes(context -> run(context.getSource(), name + " " + StringArgumentType.getString(context, "arguments")))));
        }
    }

    /** Tab completion: the legacy command's own suggestions (addTabCompletionOptions) for the word being typed. */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggest(CommandSourceStack source, String name,
                                                                                                      com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String typed = builder.getRemaining();
        try {
            ServerPlayer player = source.getPlayer();
            Object mirror = player == null ? null : LegacyPlayers.mirror(player, false);
            String[] options = LegacyBridge.call("commandCompletions", mirror, name + " " + typed);
            com.mojang.brigadier.suggestion.SuggestionsBuilder word = builder.createOffset(builder.getStart() + typed.lastIndexOf(' ') + 1);
            for (String option : options) {
                word.suggest(option);
            }
            return word.buildFuture();
        } catch (RuntimeException e) {
            return builder.buildFuture();
        }
    }

    private static int run(CommandSourceStack source, String line) {
        ServerPlayer player = source.getPlayer();
        try {
            Object mirror = player == null ? null : LegacyPlayers.mirror(player, true);
            int result = LegacyBridge.call("runCommand", mirror, line);
            if (player != null) {
                LegacyPlayers.writeBack(player, mirror);
            }
            return result;
        } catch (RuntimeException e) {
            LegacyErrors.report("command /" + line.split(" ")[0], "running", e);
            return 0;
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (active()) {
            fire("server started", () -> LegacyBridge.call("serverStarted"));
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (active()) {
            fire("server stopping", () -> LegacyBridge.call("serverStopping"));
            for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
                fire("player state save", () -> LegacyPlayers.saveState(player)); // the players are saved after this
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        if (active()) {
            fire("server stopped", () -> LegacyBridge.call("serverStopped"));
        }
        LegacyDeferredBlocks.clear(); // queued writes belong to one running server
    }

    // ---- Ticks ----

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (active()) {
            boolean start = event.phase == TickEvent.Phase.START;
            if (start) {
                fire("removed-carrier sweep", () -> LegacyEntityCore.sweepRemovedCarriers(false)); // other mods' removal tools skip remove()
            }
            fire("server tick", () -> LegacyBridge.call("serverTick", start));
        }
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (active() && !event.level.isClientSide && LegacyContent.hasLegacyWorld(event.level)) {
            boolean start = event.phase == TickEvent.Phase.START;
            Object world = LegacyContent.legacyWorld(event.level);
            int difficulty = event.level.getDifficulty().getId();
            MinecraftServer server = event.level.getServer();
            boolean hostiles = difficulty != 0 && (server == null || server.isSpawningMonsters());
            boolean animals = server == null || server.isSpawningAnimals();
            if (start) {
                fire("teleported players", () -> LegacyPlayers.placeTeleported((net.minecraft.server.level.ServerLevel) event.level));
                fire("deferred blocks", () -> LegacyDeferredBlocks.drain((net.minecraft.server.level.ServerLevel) event.level));
            }
            fire("world tick", () -> LegacyBridge.call("worldTick", world, start, difficulty, hostiles, animals));
        }
    }

    /** Both sides (clients for the local player), as 1.7.10's PlayerTickEvent; the mirror is synced first. */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        Player player = event.player;
        if (!active() || player.level().isClientSide && player != localPlayer()) {
            return;
        }
        boolean start = event.phase == TickEvent.Phase.START;
        fire("player tick", () -> {
            Object mirror = LegacyPlayers.mirror(player, true);
            LegacyBridge.call("playerTick", mirror, start);
            LegacyPlayers.writeBack(player, mirror);
        });
        if (!start) {
            if (player instanceof ServerPlayer server) {
                fire("player state", () -> LegacyPlayers.tickServer(server));
                if (server.tickCount % 600 == 0) {
                    fire("player state save", () -> LegacyPlayers.saveState(server)); // in case the server stops abruptly
                }
            } else {
                fire("player update", () -> {
                    Object mirror = LegacyPlayers.mirror(player, false);
                    LegacyBridge.call("tickClientPlayer", mirror);
                    LegacyPlayers.applyMotion(player, mirror);
                });
            }
        }
        // carried and worn items tick on both sides, as on 1.7.10 (jetpacks and flying armor push the client's player)
        if (!start && hasTickingItems(player)) {
            fire("inventory tick", () -> {
                Object mirror = LegacyPlayers.mirror(player, true);
                LegacyBridge.call("inventoryTick", mirror, LegacyContent.legacyWorld(player.level()));
                LegacyPlayers.writeBack(player, mirror);
            });
        }
    }

    /** Whether any carried or worn legacy item wants its tick (onUpdate, onArmorTick); most don't. */
    private static boolean hasTickingItems(Player player) {
        var inventory = player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inventory.getItem(i).getItem() instanceof LegacyStackItem item && (item.tickFlags() & 1) != 0) {
                return true;
            }
        }
        for (int i = 36; i < 40; i++) {
            if (inventory.getItem(i).getItem() instanceof LegacyStackItem item && (item.tickFlags() & 2) != 0) {
                return true;
            }
        }
        return false;
    }

    private static Player localPlayer() {
        return net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient() ? retrofit.forge.client.ClientHooks.localPlayer() : null;
    }

    // ---- Players ----

    /** Channel name of the legacy id map sent to clients of dedicated servers (not a legacy channel). */
    public static final String IDS_CHANNEL = "retrofit:ids";

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (active() && event.getEntity() instanceof ServerPlayer player && player.server.isDedicatedServer()) {
            LegacyModCheck.sendServerMods(player);
            try {
                LegacyNetwork.sendTo(player, IDS_CHANNEL, LegacyBridge.call("idData"));
            } catch (RuntimeException e) {
                LegacyErrors.report("legacy ids", "being sent to a player", e);
            }
            LegacyConfigSync.sendList(player);
        }
        if (active()) {
            if (event.getEntity() instanceof ServerPlayer player) {
                fire("player state load", () -> LegacyPlayers.loadState(player));
                fire("recipe unlocking", () -> LegacyRecipes.unlock(player));
            }
            fire("player login", () -> LegacyBridge.call("playerLoggedIn", LegacyPlayers.mirror(event.getEntity(), true)));
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (active()) {
            fire("player logout", () -> LegacyBridge.call("playerLoggedOut", LegacyPlayers.mirror(event.getEntity(), true)));
            if (event.getEntity() instanceof ServerPlayer player) {
                fire("player state save", () -> LegacyPlayers.saveState(player)); // before the player is saved
            }
            LegacyPlayers.forget(event.getEntity(), true);
            fire("player state", () -> LegacyBridge.call("forgetPlayer", event.getEntity().getUUID().toString()));
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (active()) {
            fire("player respawn", () -> LegacyBridge.call("playerRespawned", LegacyPlayers.mirror(event.getEntity(), true)));
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!active()) {
            return;
        }
        LegacyPlayers.forget(event.getEntity(), false);
        MinecraftServer server = event.getEntity().getServer();
        ServerLevel from = server == null ? null : server.getLevel(event.getFrom());
        int fromId = from == null ? 0 : LegacyContent.legacyDimension(from);
        int toId = LegacyContent.legacyDimension(event.getEntity().level());
        fire("player dimension change", () -> LegacyBridge.call("playerChangedDimension", LegacyPlayers.mirror(event.getEntity(), true), fromId, toId));
    }

    // ---- PlayerInteractEvent ----

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (active() && event.getHand() == InteractionHand.MAIN_HAND) {
            interact(event, 1, event.getPos().getX(), event.getPos().getY(), event.getPos().getZ(), event.getFace() == null ? -1 : event.getFace().get3DDataValue());
        }
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (active() && event.getHand() == InteractionHand.MAIN_HAND) {
            interact(event, 0, 0, 0, 0, -1);
        }
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (active() && event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START) {
            interact(event, 2, event.getPos().getX(), event.getPos().getY(), event.getPos().getZ(), event.getFace() == null ? -1 : event.getFace().get3DDataValue());
        }
    }

    /**
     * A player's attack: 1.7.10's AttackEntityEvent and the held item's onLeftClickEntity, either of
     * which ends it (Mob Battle's sticks pick mobs instead of hitting them).
     */
    @SubscribeEvent
    public static void onAttackEntity(net.minecraftforge.event.entity.player.AttackEntityEvent event) {
        Player player = event.getEntity();
        if (!active() || !LegacyContent.hasLegacyWorld(player.level())) {
            return;
        }
        try {
            Object target = LegacyEntityCore.legacyOf(player.level(), event.getTarget());
            if (target == null) {
                return;
            }
            Object mirror = LegacyPlayers.mirror(player, true);
            boolean stop = LegacyBridge.call("attackEntity", mirror, target);
            LegacyPlayers.writeBack(player, mirror);
            if (stop) {
                event.setCanceled(true);
            }
        } catch (RuntimeException e) {
            LegacyErrors.report("event handler", "AttackEntityEvent", e);
        }
    }

    /** 1.7.10's EntityInteractEvent for 1.20.1 creatures; legacy creatures fire it themselves, with the rest of the right click. */
    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!active() || event.getHand() != InteractionHand.MAIN_HAND || event.getTarget() instanceof LegacyEntityCarrier
                || !LegacyContent.hasLegacyWorld(event.getLevel())) {
            return;
        }
        try {
            Object target = LegacyEntityCore.legacyOf(event.getLevel(), event.getTarget());
            if (target == null) {
                return;
            }
            Object mirror = LegacyPlayers.mirror(event.getEntity(), true);
            boolean cancelled = LegacyBridge.call("interactEntity", mirror, target);
            LegacyPlayers.writeBack(event.getEntity(), mirror);
            if (cancelled) {
                event.setCanceled(true);
                event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
            }
        } catch (RuntimeException e) {
            LegacyErrors.report("event handler", "EntityInteractEvent", e);
        }
    }

    private static void interact(PlayerInteractEvent event, int action, int x, int y, int z, int face) {
        Player player = event.getEntity();
        Level level = event.getLevel();
        try {
            Object mirror = LegacyPlayers.mirror(player, true);
            int[] result = LegacyBridge.call("interact", mirror, action, x, y, z, face, LegacyContent.legacyWorld(level));
            LegacyPlayers.writeBack(player, mirror);
            if (result[0] != 0 && event.isCancelable()) {
                event.setCanceled(true);
            }
            if (event instanceof PlayerInteractEvent.RightClickBlock block) {
                if (result[1] != 0) {
                    block.setUseBlock(Event.Result.DENY);
                }
                if (result[2] != 0) {
                    block.setUseItem(Event.Result.DENY);
                }
            }
        } catch (RuntimeException e) {
            LegacyErrors.report("event handler", "PlayerInteractEvent", e);
        }
    }

    // ---- Worlds ----

    @SubscribeEvent
    public static void onBreakBlock(net.minecraftforge.event.level.BlockEvent.BreakEvent event) {
        if (!active() || event.getPlayer() == null || event.getPlayer().level().isClientSide) return;
        fire("BlockEvent.BreakEvent", () -> {
            Player player = event.getPlayer();
            Object mirror = LegacyPlayers.mirror(player, true);
            var pos = event.getPos();
            int[] result = LegacyBridge.call("playerBreakBlock", mirror, LegacyContent.legacyWorld(player.level()),
                    pos.getX(), pos.getY(), pos.getZ(), event.getExpToDrop());
            LegacyPlayers.writeBack(player, mirror);
            if (result[0] != 0) event.setCanceled(true);
            event.setExpToDrop(result[1]);
        });
    }

    @SubscribeEvent
    public static void onPickupItem(net.minecraftforge.event.entity.player.EntityItemPickupEvent event) {
        if (!active() || event.getEntity().level().isClientSide) return;
        fire("EntityItemPickupEvent", () -> {
            var drop = event.getItem();
            Object stack = LegacyStacks.toLegacy(drop.getItem());
            if (stack == null) return;
            Object mirror = LegacyPlayers.mirror(event.getEntity(), true);
            Object[] result = LegacyBridge.call("playerPickupItem", mirror, stack, drop.getX(), drop.getY(), drop.getZ());
            LegacyPlayers.writeBack(event.getEntity(), mirror);
            var remaining = LegacyStacks.toModern(result[3]);
            drop.setItem(remaining);
            if ((Boolean) result[2] || remaining.isEmpty()) {
                drop.discard();
                event.setCanceled(true);
            } else {
                if ((Boolean) result[0]) event.setCanceled(true);
                event.setResult(Event.Result.values()[(Integer) result[1]]);
            }
        });
    }

    @SubscribeEvent
    public static void onLevelSave(LevelEvent.Save event) {
        if (active() && event.getLevel() instanceof Level level && !level.isClientSide && LegacyContent.hasLegacyWorld(level)) {
            Object world = LegacyContent.legacyWorld(level);
            fire("world save", () -> LegacyBridge.call("worldSaved", world));
        }
    }

    // ---- Chunks (server levels; only when a legacy mod listens) ----

    private static final String CHUNK_DATA_KEY = "RetrofitLegacy";
    private static Boolean chunkEventsWanted;
    /** Legacy data read with a chunk (ChunkDataEvent.Load may run off the server thread), until its ChunkEvent.Load. */
    private static final Map<Level, Map<net.minecraft.world.level.ChunkPos, byte[]>> CHUNK_DATA = new java.util.WeakHashMap<>();

    private static boolean chunkEvents() {
        if (chunkEventsWanted == null) {
            try {
                chunkEventsWanted = LegacyBridge.<Boolean>call("chunkEventsWanted");
            } catch (RuntimeException e) {
                chunkEventsWanted = false;
            }
        }
        return chunkEventsWanted;
    }

    @SubscribeEvent
    public static void onChunkDataLoad(net.minecraftforge.event.level.ChunkDataEvent.Load event) {
        if (active() && event.getLevel() instanceof net.minecraft.server.level.ServerLevel level && event.getData().contains(CHUNK_DATA_KEY)
                && chunkEvents()) {
            synchronized (CHUNK_DATA) {
                CHUNK_DATA.computeIfAbsent(level, l -> new HashMap<>()).put(event.getChunk().getPos(), event.getData().getByteArray(CHUNK_DATA_KEY));
            }
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(net.minecraftforge.event.level.ChunkEvent.Load event) {
        if (active() && event.getLevel() instanceof net.minecraft.server.level.ServerLevel level
                && event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk && chunkEvents()) {
            net.minecraft.world.level.ChunkPos pos = event.getChunk().getPos();
            byte[] data;
            synchronized (CHUNK_DATA) {
                Map<net.minecraft.world.level.ChunkPos, byte[]> pending = CHUNK_DATA.get(level);
                data = pending == null ? null : pending.remove(pos);
            }
            Object world = LegacyContent.legacyWorld(level);
            fire("chunk load", () -> LegacyBridge.call("chunkLoaded", world, pos.x, pos.z, data));
        }
    }

    @SubscribeEvent
    public static void onChunkDataSave(net.minecraftforge.event.level.ChunkDataEvent.Save event) {
        // chunks still generating (proto chunks) were never loaded for legacy code: nothing of theirs to save
        if (active() && event.getLevel() instanceof net.minecraft.server.level.ServerLevel level && chunkEvents()
                && event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk && LegacyContent.hasLegacyWorld(level)) {
            net.minecraft.world.level.ChunkPos pos = event.getChunk().getPos();
            Object world = LegacyContent.legacyWorld(level);
            fire("chunk save", () -> {
                byte[] data = LegacyBridge.call("chunkSaved", world, pos.x, pos.z);
                if (data != null) {
                    event.getData().putByteArray(CHUNK_DATA_KEY, data);
                }
            });
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(net.minecraftforge.event.level.ChunkEvent.Unload event) {
        if (active() && event.getLevel() instanceof net.minecraft.server.level.ServerLevel level && chunkEvents()
                && LegacyContent.hasLegacyWorld(level)) {
            net.minecraft.world.level.ChunkPos pos = event.getChunk().getPos();
            Object world = LegacyContent.legacyWorld(level);
            fire("chunk unload", () -> LegacyBridge.call("chunkUnloaded", world, pos.x, pos.z));
        }
    }

    private static Boolean chunkWatchWanted;

    private static boolean chunkWatch() {
        if (chunkWatchWanted == null) {
            try {
                chunkWatchWanted = LegacyBridge.<Boolean>call("chunkWatchWanted");
            } catch (RuntimeException e) {
                chunkWatchWanted = false;
            }
        }
        return chunkWatchWanted;
    }

    @SubscribeEvent
    public static void onChunkWatch(net.minecraftforge.event.level.ChunkWatchEvent.Watch event) {
        chunkWatched(event.getPlayer(), event.getLevel(), event.getPos(), true);
    }

    @SubscribeEvent
    public static void onChunkUnWatch(net.minecraftforge.event.level.ChunkWatchEvent.UnWatch event) {
        chunkWatched(event.getPlayer(), event.getLevel(), event.getPos(), false);
    }

    private static void chunkWatched(net.minecraft.server.level.ServerPlayer player, net.minecraft.server.level.ServerLevel level,
                                     net.minecraft.world.level.ChunkPos pos, boolean watched) {
        if (active() && chunkWatch() && player.level() == level && LegacyContent.hasLegacyWorld(level)) {
            fire(watched ? "chunk watch" : "chunk unwatch",
                    () -> LegacyBridge.call("chunkWatched", LegacyPlayers.mirror(player, false), pos.x, pos.z, watched));
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (active() && event.getLevel() instanceof Level level && LegacyContent.hasLegacyWorld(level)) {
            Object world = LegacyContent.legacyWorld(level);
            fire("world unload", () -> LegacyBridge.call("worldUnloaded", world));
        }
    }
}
