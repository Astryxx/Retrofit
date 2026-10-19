package retrofit.forge.client;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import retrofit.forge.RetrofitMod;
import retrofit.forge.world.LegacyBridge;

/**
 * Mods' DrawBlockHighlightEvent for the block the player looks at: what they draw (their own
 * outlines, previews of what a tool will change) shows, and cancelling it hides the 1.20.1 outline.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, value = Dist.CLIENT)
public final class LegacyBlockHighlight {
    private static Boolean listened;
    private static boolean failed;

    private LegacyBlockHighlight() {
    }

    @SubscribeEvent
    public static void onHighlight(RenderHighlightEvent.Block event) {
        if (failed || RetrofitMod.runtime() == null) {
            return;
        }
        if (listened == null) {
            try {
                listened = LegacyBridge.<Boolean>call("listensForBlockHighlight");
            } catch (RuntimeException e) {
                listened = false;
            }
        }
        if (!listened) {
            return;
        }
        BlockHitResult hit = event.getTarget();
        BlockPos pos = hit.getBlockPos();
        int side = hit.getDirection().get3DDataValue(); // 0 down, 1 up, 2 north, 3 south, 4 west, 5 east: 1.7.10's side numbers
        try {
            synchronized (LegacyBridge.renderLock()) {
                Object[] result = LegacyBridge.call("drawBlockHighlight", pos.getX(), pos.getY(), pos.getZ(), side,
                        hit.getLocation().x, hit.getLocation().y, hit.getLocation().z, event.getPartialTick());
                // 1.7.10 drew it around the camera, as the event's pose stack stands
                LegacyCapture.draw(event.getPoseStack().last(), event.getMultiBufferSource(), (Integer) result[0], LightTexture.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY);
                if ((Boolean) result[1]) {
                    event.setCanceled(true);
                }
            }
        } catch (Throwable e) {
            failed = true;
            LegacyBridge.reportClientFailure("block highlight drawing", e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e));
        }
    }
}
