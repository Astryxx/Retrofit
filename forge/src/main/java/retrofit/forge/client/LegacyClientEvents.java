package retrofit.forge.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import retrofit.forge.RetrofitMod;
import retrofit.forge.world.LegacyBridge;
import retrofit.forge.world.LegacyEntityCore;
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
        if (start) {
            run(() -> LegacyEntityCore.sweepRemovedCarriers(true)); // carriers removed without remove() (other mods' removal tools)
        }
        run(() -> {
            if (start && Minecraft.getInstance().player != null) {
                // with the inventory: client renderers and HUDs read what the player holds and wears (Thaumcraft's goggles)
                Object mirror = LegacyPlayers.mirror(Minecraft.getInstance().player, true);
                LegacyPlayers.syncClientRiding(Minecraft.getInstance().player, mirror);
                syncMouseOver(Minecraft.getInstance());
            }
            if (!start && Minecraft.getInstance().level != null && !Minecraft.getInstance().isPaused()) {
                LegacyBridge.call("tickParticles"); // as 1.7.10's runTick: before the tick's end handlers, not while paused
            }
            LegacyBridge.call("clientTick", start);
            if (!start) {
                LegacyBlockFaces.refreshOpacity();
            }
        });
    }

    /** What the player looks at, as 1.7.10's Minecraft.objectMouseOver (Thaumcraft's goggles and thaumometer, HUDs). */
    static void syncMouseOver(Minecraft minecraft) {
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

    /** Whether a legacy coremod draws item tooltips itself (ManaMetal); null until the bridge is up. */
    private static Boolean modTooltips;

    /**
     * Tooltips of legacy and vanilla stacks, drawn by the legacy mod that draws tooltips itself
     * (its own frames, separators and icons, as in its 1.7.10 screens) instead of 1.20.1's.
     */
    @SubscribeEvent
    public static void onTooltipDraw(net.minecraftforge.client.event.RenderTooltipEvent.Pre event) {
        net.minecraft.world.item.ItemStack stack = event.getItemStack();
        if (RetrofitMod.runtime() == null || stack.isEmpty() || !LegacyBridge.isReady()) {
            return;
        }
        if (modTooltips == null) {
            modTooltips = LegacyBridge.<Boolean>call("modTooltips");
        }
        if (!modTooltips || !(stack.getItem() instanceof retrofit.forge.world.LegacyStackItem
                || net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("minecraft"))) {
            return;
        }
        run(() -> {
            Object legacy = retrofit.forge.world.LegacyStacks.toLegacy(stack);
            if (legacy == null) {
                return;
            }
            var window = Minecraft.getInstance().getWindow();
            var graphics = event.getGraphics();
            synchronized (LegacyBridge.renderLock()) {
                int vertices = LegacyBridge.call("drawTooltip", legacy, event.getX(), event.getY(), event.getScreenWidth(),
                        event.getScreenHeight(), window.getWidth(), window.getHeight(), (int) window.getGuiScale());
                Object[] markers = LegacyBridge.call("guiMarkers");
                graphics.pose().pushPose();
                graphics.pose().translate(0, 0, 400); // above the screen's items, as 1.20.1 lifts its own tooltips
                try {
                    LegacyGuiRenderer.draw(graphics, vertices, markers, null);
                } catch (Throwable e) {
                    throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
                } finally {
                    graphics.pose().popPose();
                }
            }
            event.setCanceled(true);
        });
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

    /** Joining a server: legacy code sees its address on the connection (NEI keeps its settings per server by it). */
    @SubscribeEvent
    public static void onLoggingIn(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) {
        if (RetrofitMod.runtime() == null) {
            return;
        }
        java.net.SocketAddress remote = event.getConnection().getRemoteAddress();
        String host = null;
        String ip = null;
        int port = 0;
        if (remote instanceof java.net.InetSocketAddress inet) {
            host = inet.getHostString();
            ip = inet.getAddress() != null ? inet.getAddress().getHostAddress() : host;
            port = inet.getPort();
        }
        String[] address = {host, ip};
        int serverPort = port;
        run(() -> LegacyBridge.call("setServerAddress", address[0], address[1], serverPort));
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
            if (start) {
                int[] lightmap = lightmap();
                if (lightmap != null) {
                    run(() -> LegacyBridge.call("setLightmap", (Object) lightmap));
                }
                run(() -> LegacyBridge.call("renderTick", true, partialTicks));
            } else {
                // No HUD or screen ran this frame's end (the host has no player: menus, loading
                // screens), so nothing reads what its handlers draw; fire it with that discarded.
                run(() -> LegacyBridge.call("renderTickEndDiscarded", partialTicks));
            }
        }
    }

    /** LightTexture's pixels (its one NativeImage field), found once; null if there is no such field. */
    private static java.lang.reflect.Field lightPixels;
    private static boolean lightPixelsLooked;
    private static final int[] LIGHTMAP = new int[256];

    /**
     * The lightmap 1.20.1 draws this frame with, as 1.7.10's lightmapColors held it: ARGB, sky level * 16 +
     * block level (1.20.1 keeps block light in x, sky light in y, as ABGR). Null without a level or the field.
     */
    private static int[] lightmap() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        try {
            if (!lightPixelsLooked) {
                lightPixelsLooked = true;
                for (java.lang.reflect.Field field : net.minecraft.client.renderer.LightTexture.class.getDeclaredFields()) {
                    if (field.getType() == com.mojang.blaze3d.platform.NativeImage.class) {
                        field.setAccessible(true);
                        lightPixels = field;
                    }
                }
            }
            if (lightPixels == null) {
                return null;
            }
            com.mojang.blaze3d.platform.NativeImage pixels = (com.mojang.blaze3d.platform.NativeImage) lightPixels.get(minecraft.gameRenderer.lightTexture());
            for (int sky = 0; sky < 16; sky++) {
                for (int block = 0; block < 16; block++) {
                    int abgr = pixels.getPixelRGBA(block, sky);
                    LIGHTMAP[sky * 16 + block] = abgr & 0xFF00FF00 | (abgr & 0xFF) << 16 | (abgr >> 16) & 0xFF;
                }
            }
            return LIGHTMAP;
        } catch (ReflectiveOperationException | RuntimeException e) {
            lightPixels = null;
            return null;
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
