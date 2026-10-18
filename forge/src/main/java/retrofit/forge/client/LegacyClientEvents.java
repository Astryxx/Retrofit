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
                Object mirror = LegacyPlayers.mirror(Minecraft.getInstance().player, false);
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
