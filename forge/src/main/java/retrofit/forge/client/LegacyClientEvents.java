package retrofit.forge.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import retrofit.forge.RetrofitMod;
import retrofit.forge.world.LegacyBridge;
import retrofit.forge.world.LegacyPlayers;

/**
 * Client ticks and render ticks for legacy mods (FML's ClientTickEvent and RenderTickEvent),
 * with the local player's mirror current, since client tick handlers read Minecraft.thePlayer.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, value = Dist.CLIENT)
public final class LegacyClientEvents {

    private LegacyClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (RetrofitMod.runtime() == null) {
            return;
        }
        boolean start = event.phase == TickEvent.Phase.START;
        run(() -> {
            if (start && Minecraft.getInstance().player != null) {
                LegacyPlayers.mirror(Minecraft.getInstance().player, false);
            }
            LegacyBridge.call("clientTick", start);
        });
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (RetrofitMod.runtime() == null) {
            return;
        }
        boolean start = event.phase == TickEvent.Phase.START;
        float partialTicks = event.renderTickTime;
        run(() -> LegacyBridge.call("renderTick", start, partialTicks));
    }

    private static void run(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            // A failing legacy tick handler would fail every frame; report it and keep the game running.
            retrofit.forge.world.LegacyBridge.reportClientFailure("client tick handler", e);
        }
    }
}
