package retrofit.forge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import retrofit.forge.world.LegacyBridge;
import retrofit.forge.world.LegacyEntityCarrier;

/**
 * Draws a legacy entity with its mod's own renderer (RenderingRegistry, or the vanilla renderer it
 * extends), through the GL emulation, at the host entity's place: models, animations, name tags
 * and effects the way 1.7.10 drew them.
 */
public class LegacyEntityRenderer<T extends Entity> extends EntityRenderer<T> {
    /** Failures in a row after which this type is no longer drawn (it fails every frame: drawing nothing is kinder). */
    private static final int GIVE_UP = 200;
    private boolean failed;
    private int failures;

    public LegacyEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0; // legacy renderers draw their own shadows
    }

    @Override
    public void render(T entity, float yaw, float partialTicks, PoseStack pose, MultiBufferSource buffers, int light) {
        if (failed || !(entity instanceof LegacyEntityCarrier carrier)) {
            return;
        }
        Object legacy = carrier.clientLegacy();
        if (legacy == null) {
            return;
        }
        try {
            synchronized (LegacyBridge.renderLock()) {
                int vertices = LegacyBridge.call("renderEntity", legacy, yaw, partialTicks);
                LegacyCapture.draw(pose.last(), buffers, vertices, light, OverlayTexture.NO_OVERLAY);
            }
            failures = 0;
        } catch (Throwable e) {
            // one report; a renderer failing once (a creature whose data hasn't arrived yet) still draws the others
            // and later frames: the whole type stayed invisible for the session
            failed = ++failures >= GIVE_UP;
            LegacyBridge.reportClientFailure("entity renderer for " + entity.getType().getDescriptionId(),
                    e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e));
        }
    }

    /** 1.7.10's tests (see the bridge's entityRenderChecks) in place of 1.20.1's distance; the frustum unless the entity skips it. */
    @Override
    public boolean shouldRender(T entity, net.minecraft.client.renderer.culling.Frustum frustum, double x, double y, double z) {
        Object legacy = !failed && entity instanceof LegacyEntityCarrier carrier ? carrier.clientLegacy() : null;
        if (legacy == null) {
            return super.shouldRender(entity, frustum, x, y, z);
        }
        int checks;
        try {
            // (no render lock: it reads the client entity, which only this thread changes, and draws nothing)
            checks = LegacyBridge.call("entityRenderChecks", legacy, x, y, z);
        } catch (RuntimeException e) {
            return super.shouldRender(entity, frustum, x, y, z);
        }
        if ((checks & 1) == 0) {
            return false;
        }
        return (checks & 2) != 0 || entity.noCulling || frustum.isVisible(entity.getBoundingBoxForCulling().inflate(0.5));
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return MissingTextureAtlasSprite.getLocation();
    }
}
