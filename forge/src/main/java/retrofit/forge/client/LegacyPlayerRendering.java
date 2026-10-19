package retrofit.forge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import retrofit.forge.RetrofitMod;
import retrofit.forge.world.LegacyBridge;

/**
 * Mods' RenderPlayerEvent (Pre, Specials, Post) around 1.20.1 drawing the local player: what
 * they draw shows (capes, wings, goggles, a transformation's model), cancelling Pre hides the
 * player, and a matrix they push in Pre to move or scale the player applies to the 1.20.1 model
 * until Post. Specials get the legacy player model posed as the 1.20.1 one, in its space.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, value = Dist.CLIENT)
public final class LegacyPlayerRendering {
    private static Boolean listened;
    private static boolean failed;
    /** Whether Pre pushed the pose stack for a legacy matrix (popped at Post). */
    private static boolean pushed;

    private LegacyPlayerRendering() {
    }

    private static boolean active(AbstractClientPlayer player) {
        if (failed || RetrofitMod.runtime() == null || player != Minecraft.getInstance().player) {
            return false;
        }
        if (listened == null) {
            try {
                listened = LegacyBridge.<Boolean>call("listensForPlayerRendering");
            } catch (RuntimeException e) {
                listened = false;
            }
        }
        return listened;
    }

    /** The player's position relative to the camera: where the event's pose stack stands. */
    private static Vec3 offset(RenderPlayerEvent event) {
        AbstractClientPlayer player = (AbstractClientPlayer) event.getEntity();
        float partialTick = event.getPartialTick();
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        return player.getPosition(partialTick).subtract(camera).add(event.getRenderer().getRenderOffset(player, partialTick));
    }

    private static void fail(Throwable e) {
        failed = true;
        LegacyBridge.reportClientFailure("player rendering events", e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e));
    }

    /** Draws captured vertices drawn in camera space (as 1.7.10 world rendering was). */
    private static void drawInCameraSpace(RenderPlayerEvent event, Vec3 offset, int vertices) throws Throwable {
        if (vertices == 0) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-offset.x, -offset.y, -offset.z);
        LegacyCapture.draw(pose.last(), event.getMultiBufferSource(), vertices, event.getPackedLight(), OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    private static boolean identity(float[] m) {
        for (int i = 0; i < 16; i++) {
            if (Math.abs(m[i] - (i % 5 == 0 ? 1 : 0)) > 1e-6f) {
                return false;
            }
        }
        return true;
    }

    @SubscribeEvent
    public static void onPre(RenderPlayerEvent.Pre event) {
        pushed = false;
        if (!(event.getEntity() instanceof AbstractClientPlayer player) || !active(player)) {
            return;
        }
        Vec3 offset = offset(event);
        try {
            synchronized (LegacyBridge.renderLock()) {
                Object[] result = LegacyBridge.call("renderPlayer", 0, event.getPartialTick(), (Object) null);
                drawInCameraSpace(event, offset, (Integer) result[0]);
                if ((Boolean) result[1]) {
                    event.setCanceled(true);
                    return;
                }
                float[] matrix = (float[]) result[2];
                if (matrix != null && !identity(matrix)) {
                    PoseStack pose = event.getPoseStack();
                    pose.pushPose();
                    pose.translate(-offset.x, -offset.y, -offset.z);
                    pose.mulPoseMatrix(new Matrix4f().set(matrix));
                    pose.translate(offset.x, offset.y, offset.z);
                    pushed = true;
                }
            }
        } catch (Throwable e) {
            fail(e);
        }
    }

    @SubscribeEvent
    public static void onPost(RenderPlayerEvent.Post event) {
        if (!(event.getEntity() instanceof AbstractClientPlayer player) || !active(player)) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        try {
            synchronized (LegacyBridge.renderLock()) {
                PlayerModel<AbstractClientPlayer> model = event.getRenderer().getModel();
                Object[] specials = LegacyBridge.call("renderPlayer", 1, event.getPartialTick(), parts(model));
                if ((Integer) specials[0] > 0) {
                    // RendererLivingEntity's model space: turned with the body, upside down, the player's 0.9375 scale
                    pose.pushPose();
                    pose.mulPose(Axis.YP.rotationDegrees(180 - Mth.rotLerp(event.getPartialTick(), player.yBodyRotO, player.yBodyRot)));
                    pose.scale(-1, -1, 1);
                    pose.scale(0.9375f, 0.9375f, 0.9375f);
                    pose.translate(0, -1.5078125f, 0);
                    LegacyCapture.draw(pose.last(), event.getMultiBufferSource(), (Integer) specials[0], event.getPackedLight(), OverlayTexture.NO_OVERLAY);
                    pose.popPose();
                }
                if (pushed) {
                    pose.popPose();
                    pushed = false;
                }
                Object[] post = LegacyBridge.call("renderPlayer", 2, event.getPartialTick(), (Object) null);
                drawInCameraSpace(event, offset(event), (Integer) post[0]);
            }
        } catch (Throwable e) {
            if (pushed) {
                pose.popPose();
                pushed = false;
            }
            fail(e);
        }
    }

    /** The model's pose for the legacy bridge's ClientEvents.posePlayerModel. */
    private static float[] parts(PlayerModel<?> model) {
        ModelPart[] parts = {model.head, model.hat, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg};
        float[] pose = new float[parts.length * 6 + 1];
        for (int i = 0; i < parts.length; i++) {
            ModelPart part = parts[i];
            pose[i * 6] = part.xRot;
            pose[i * 6 + 1] = part.yRot;
            pose[i * 6 + 2] = part.zRot;
            pose[i * 6 + 3] = part.x;
            pose[i * 6 + 4] = part.y;
            pose[i * 6 + 5] = part.z;
        }
        pose[parts.length * 6] = model.crouching ? 1 : 0;
        return pose;
    }
}
