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
    private boolean failed;

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
        } catch (Throwable e) {
            failed = true; // one report, then this type draws nothing rather than failing every frame
            LegacyBridge.reportClientFailure("entity renderer for " + entity.getType().getDescriptionId(),
                    e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e));
        }
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return MissingTextureAtlasSprite.getLocation();
    }
}
