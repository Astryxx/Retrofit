package retrofit.forge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import org.slf4j.Logger;
import retrofit.forge.world.LegacyBlockEntity;
import retrofit.forge.world.LegacyBridge;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Draws legacy tile entity special renderers (TESRs): the legacy renderer runs through the GL
 * emulation at the block's origin, and the captured quads are drawn here with 1.20.1 render
 * types. A renderer that throws is disabled (per tile entity class) and logged once.
 */
public final class LegacyBlockEntityRenderer implements BlockEntityRenderer<LegacyBlockEntity> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<Class<?>> BROKEN = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<Class<?>, Boolean> HAS_RENDERER = new IdentityHashMap<>();

    /** -Dretrofit.debugBlock=<part of a tile entity class name>: logs what such renderers draw, now and then. */
    private static final String DEBUG_BLOCK = System.getProperty("retrofit.debugBlock");
    private static int debugged;

    public LegacyBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(LegacyBlockEntity blockEntity, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        Object tile = blockEntity.legacyTile();
        if (tile == null || BROKEN.contains(tile.getClass())) {
            return;
        }
        Boolean hasRenderer = HAS_RENDERER.computeIfAbsent(tile.getClass(), c -> LegacyBridge.hasSpecialRenderer(tile));
        if (!hasRenderer) {
            return;
        }
        synchronized (LegacyBridge.renderLock()) {
            try {
                // drawn camera-relative, as 1.7.10 did, so the pose goes back from the block to the camera
                net.minecraft.world.phys.Vec3 camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
                double x = blockEntity.getBlockPos().getX() - camera.x;
                double y = blockEntity.getBlockPos().getY() - camera.y;
                double z = blockEntity.getBlockPos().getZ() - camera.z;
                int vertices = LegacyBridge.renderTileEntity(tile, partialTick, x, y, z);
                if (DEBUG_BLOCK != null && tile.getClass().getName().contains(DEBUG_BLOCK) && debugged++ % 200 == 0) {
                    LOGGER.info("[Debug] {} at {} drew {} vertices", tile.getClass().getName(), blockEntity.getBlockPos(), vertices);
                }
                pose.pushPose();
                pose.translate(-x, -y, -z);
                LegacyCapture.draw(pose.last(), buffers, vertices, light, overlay);
                pose.popPose();
            } catch (Throwable t) {
                BROKEN.add(tile.getClass());
                LOGGER.error("Legacy tile entity renderer for {} failed and has been disabled", tile.getClass().getName(), t);
            }
        }
    }

    /** Legacy renderers often draw past their block (tall armor stands, signs); don't cull them early. */
    @Override
    public boolean shouldRenderOffScreen(LegacyBlockEntity blockEntity) {
        return true;
    }
}
