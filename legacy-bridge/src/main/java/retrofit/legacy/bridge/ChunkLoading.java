package retrofit.legacy.bridge;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.MinecraftForge;
import retrofit.api.HostWorld;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Mods' chunk loading (quarries, chunk loaders, ...): 1.7.10's ForgeChunkManager keeps its tickets
 * as it always did (saved with the legacy world data, given back to the mods' callbacks when the
 * world loads); the chunks its tickets force are forced in the host level. Several tickets may force
 * one chunk, so the host's is held while any is.
 */
public final class ChunkLoading {
    private static final ChunkLoading INSTANCE = new ChunkLoading();
    private static boolean registered;
    private final Map<World, Map<Long, Integer>> forced = new WeakHashMap<World, Map<Long, Integer>>();

    private ChunkLoading() {
    }

    /** Called when the legacy server starts (see Events). */
    static synchronized void register() {
        if (!registered) {
            registered = true;
            // FML wants an active mod for every listener: register as Minecraft's (no "registrant" error).
            Object controller = null;
            java.lang.reflect.Field active = null;
            try {
                java.lang.reflect.Field field = cpw.mods.fml.common.Loader.class.getDeclaredField("modController");
                field.setAccessible(true);
                controller = field.get(cpw.mods.fml.common.Loader.instance());
                active = controller.getClass().getDeclaredField("activeContainer");
                active.setAccessible(true);
                if (active.get(controller) == null) {
                    active.set(controller, cpw.mods.fml.common.Loader.instance().getMinecraftModContainer());
                } else {
                    active = null;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                active = null;
            }
            MinecraftForge.EVENT_BUS.register(INSTANCE);
            if (active != null) {
                try {
                    active.set(controller, null);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // left as is
                }
            }
        }
    }

    private static HostWorld host(World world) {
        if (world instanceof BridgeWorldServer) {
            return ((BridgeWorldServer) world).host();
        }
        return null;
    }

    @SubscribeEvent
    public void onForce(ForgeChunkManager.ForceChunkEvent event) {
        change(event.ticket.world, event.location.field_77276_a, event.location.field_77275_b, 1); // chunkXPos, chunkZPos
    }

    @SubscribeEvent
    public void onUnforce(ForgeChunkManager.UnforceChunkEvent event) {
        change(event.ticket.world, event.location.field_77276_a, event.location.field_77275_b, -1);
    }

    private synchronized void change(World world, int x, int z, int delta) {
        HostWorld host = host(world);
        if (host == null) {
            return;
        }
        Map<Long, Integer> chunks = forced.get(world);
        if (chunks == null) {
            chunks = new HashMap<Long, Integer>();
            forced.put(world, chunks);
        }
        long key = (long) x << 32 | (z & 0xFFFFFFFFL);
        Integer before = chunks.get(key);
        int count = Math.max(0, (before == null ? 0 : before) + delta);
        if (count == 0) {
            chunks.remove(key);
        } else {
            chunks.put(key, count);
        }
        if ((before == null || before == 0) && count > 0) {
            host.forceChunk(x, z, true);
        } else if (before != null && before > 0 && count == 0) {
            host.forceChunk(x, z, false);
        }
    }
}
