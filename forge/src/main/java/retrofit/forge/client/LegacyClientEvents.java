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
                // with the inventory: client renderers and HUDs read what the player holds and wears (Thaumcraft's goggles)
                Object mirror = LegacyPlayers.mirror(Minecraft.getInstance().player, true);
                LegacyPlayers.syncClientRiding(Minecraft.getInstance().player, mirror);
                syncMouseOver(Minecraft.getInstance());
            }
            LegacyBridge.call("clientTick", start);
        });
    }

    /** What the player looks at, as 1.7.10's Minecraft.objectMouseOver (Thaumcraft's goggles and thaumometer, HUDs). */
    private static void syncMouseOver(Minecraft minecraft) {
        net.minecraft.world.phys.HitResult hit = minecraft.hitResult;
        if (hit instanceof net.minecraft.world.phys.BlockHitResult block && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            var pos = block.getBlockPos();
            LegacyBridge.call("setMouseOver", 1, pos.getX(), pos.getY(), pos.getZ(), block.getDirection().get3DDataValue(),
                    hit.getLocation().x, hit.getLocation().y, hit.getLocation().z, null);
        } else if (hit instanceof net.minecraft.world.phys.EntityHitResult entityHit) {
            net.minecraft.world.entity.Entity entity = entityHit.getEntity();
            Object legacy = entity instanceof retrofit.forge.world.LegacyEntityCarrier carrier ? carrier.clientLegacy()
                    : entity instanceof net.minecraft.world.entity.player.Player player ? LegacyPlayers.mirror(player, false) : null;
            LegacyBridge.call("setMouseOver", legacy == null ? 0 : 2, 0, 0, 0, 0,
                    hit.getLocation().x, hit.getLocation().y, hit.getLocation().z, legacy);
        } else {
            LegacyBridge.call("setMouseOver", 0, 0, 0, 0, 0, 0.0, 0.0, 0.0, null);
        }
    }

    /** Tooltip lines from legacy mods (addInformation, ItemTooltipEvent), for mod items and vanilla ones alike. */
    @SubscribeEvent
    public static void onTooltip(net.minecraftforge.event.entity.player.ItemTooltipEvent event) {
        if (RetrofitMod.runtime() == null || event.getEntity() == null) {
            return;
        }
        run(() -> {
            Object legacy = retrofit.forge.world.LegacyStacks.toLegacy(event.getItemStack());
            if (legacy == null) {
                return;
            }
            boolean modItem = event.getItemStack().getItem() instanceof retrofit.forge.world.LegacyStackItem;
            String[] lines = LegacyBridge.call("tooltipLines", legacy, LegacyPlayers.mirror(event.getEntity(), false),
                    event.getFlags().isAdvanced(), modItem);
            for (String line : lines) {
                event.getToolTip().add(net.minecraft.network.chat.Component.literal(line));
            }
        });
    }

    /** Leaving a dedicated server: its legacy id map no longer applies. */
    @SubscribeEvent
    public static void onLoggingOut(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        if (RetrofitMod.runtime() != null && Minecraft.getInstance().getSingleplayerServer() == null) {
            run(() -> LegacyBridge.call("revertIds"));
        }
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (RetrofitMod.runtime() == null) {
            return;
        }
        boolean start = event.phase == TickEvent.Phase.START;
        float partialTicks = event.renderTickTime;
        if (start) {
            renderTickEnded = false;
        } else if (renderTickEnded) {
            return; // the end ran already, drawn over the HUD or screen (see drawRenderTickEnd)
        }
        // render tick handlers draw (Thaumcraft's research popups) with the one legacy Tessellator chunk builders share
        synchronized (LegacyBridge.renderLock()) {
            run(() -> LegacyBridge.call("renderTick", start, partialTicks));
        }
    }

    /** Whether this frame's render tick end has run (once per frame, as on 1.7.10). */
    private static boolean renderTickEnded;

    /**
     * The render tick end, when the HUD or a screen has just been drawn: what mods draw there is
     * shown on top, in screen space, as on 1.7.10 (Thaumcraft's aspects flying to the corner).
     */
    private static void drawRenderTickEnd(net.minecraft.client.gui.GuiGraphics graphics, float partialTicks) {
        if (RetrofitMod.runtime() == null || renderTickEnded || Minecraft.getInstance().player == null) {
            return;
        }
        renderTickEnded = true;
        var window = Minecraft.getInstance().getWindow();
        try {
            synchronized (LegacyBridge.renderLock()) {
                int vertices = LegacyBridge.call("drawRenderTickEnd", partialTicks, window.getWidth(), window.getHeight(), (int) window.getGuiScale());
                if (vertices > 0) {
                    LegacyGuiRenderer.draw(graphics, vertices, LegacyBridge.call("guiMarkers"), null);
                }
            }
        } catch (Throwable e) {
            retrofit.forge.world.LegacyBridge.reportClientFailure("render tick drawing", e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e));
        }
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onHudDrawn(net.minecraftforge.client.event.RenderGuiEvent.Post event) {
        if (Minecraft.getInstance().screen == null) {
            drawRenderTickEnd(event.getGuiGraphics(), event.getPartialTick());
        }
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onScreenDrawn(net.minecraftforge.client.event.ScreenEvent.Render.Post event) {
        drawRenderTickEnd(event.getGuiGraphics(), event.getPartialTick());
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
