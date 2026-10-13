package retrofit.forge.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import retrofit.forge.RetrofitMod;
import retrofit.host.LegacyRuntime;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Rendering test: draws vanilla 1.7.10's ModelPig, rendered by real 1.7.10 code through
 * Retrofit's GL emulation, as an animated pig a few blocks in front of the player.
 * Uses the pig texture from the player's own 1.7.10 jar. Enabled with -Dretrofit.renderTest=true
 * (Gradle: -PrenderTest).
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, value = Dist.CLIENT)
public final class LegacyModelTestRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean("retrofit.renderTest");
    private static final ResourceLocation TEXTURE = new ResourceLocation(RetrofitMod.MOD_ID, "legacy/pig");
    private static final String PIG_TEXTURE_ENTRY = "assets/minecraft/textures/entity/pig/pig.png";

    private static MethodHandle renderPig;
    private static MethodHandle captureData;
    private static int floatsPerVertex;
    private static Vec3 anchor;
    private static float yaw;
    private static boolean failed;
    private static boolean announced;
    private static int framesDrawn;
    private static boolean screenshotTaken;

    private LegacyModelTestRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!ENABLED || event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || failed) {
            return;
        }
        LegacyRuntime runtime = RetrofitMod.runtime();
        Minecraft mc = Minecraft.getInstance();
        if (runtime == null || mc.player == null || mc.level == null) {
            return;
        }
        try {
            if (renderPig == null) {
                setUp(runtime, mc);
            }
            if (anchor == null) {
                // Three blocks in front of the player, facing them.
                Vec3 look = mc.player.getLookAngle().multiply(1, 0, 1).normalize();
                anchor = mc.player.position().add(look.scale(3));
                yaw = mc.player.getYRot() + 180;
            }
            draw(event, mc);
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("Retrofit legacy model render test failed; disabling it", t);
        }
    }

    /** Saves one screenshot after the pig has been on screen for a while, as test evidence. */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END || screenshotTaken || framesDrawn < 120) {
            return;
        }
        screenshotTaken = true;
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, "retrofit-render-test.png", mc.getMainRenderTarget(),
                message -> LOGGER.info("Retrofit render test screenshot: {}", message.getString()));
    }

    private static void setUp(LegacyRuntime runtime, Minecraft mc) throws Exception {
        ClassLoader root = runtime.rootLoader();
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        Class<?> modelTest = root.loadClass("retrofit.legacy.gl.ModelTest");
        Class<?> capture = root.loadClass("retrofit.legacy.gl.Capture");
        renderPig = lookup.findStatic(modelTest, "renderPig", MethodType.methodType(int.class, float.class));
        captureData = lookup.findStatic(capture, "data", MethodType.methodType(float[].class));
        floatsPerVertex = capture.getField("FLOATS_PER_VERTEX").getInt(null);

        try (ZipFile jar = new ZipFile(runtime.gameJar().toFile())) {
            ZipEntry entry = jar.getEntry(PIG_TEXTURE_ENTRY);
            if (entry == null) {
                throw new IOException(PIG_TEXTURE_ENTRY + " not found in " + runtime.gameJar());
            }
            try (InputStream in = jar.getInputStream(entry)) {
                mc.getTextureManager().register(TEXTURE, new DynamicTexture(NativeImage.read(in)));
            }
        }
    }

    private static void draw(RenderLevelStageEvent event, Minecraft mc) throws Throwable {
        float age = mc.player.tickCount + event.getPartialTick();
        int vertices = (int) renderPig.invokeExact(age);
        float[] data = (float[]) captureData.invokeExact();
        if (!announced) {
            announced = true;
            LOGGER.info("Rendering legacy 1.7.10 ModelPig in the 1.20.1 world: {} vertices per frame at {}", vertices, anchor);
        }

        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(anchor.x - camera.x, anchor.y - camera.y, anchor.z - camera.z);
        // Same transforms 1.7.10's RendererLivingEntity applies before ModelBase.render.
        pose.mulPose(Axis.YP.rotationDegrees(180 - yaw));
        pose.scale(-1, -1, 1);
        pose.translate(0, -1.5078125, 0);

        Matrix4f position = pose.last().pose();
        Matrix3f normal = pose.last().normal();
        int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(anchor));
        RenderType type = RenderType.entityCutoutNoCull(TEXTURE);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer out = buffers.getBuffer(type);
        for (int v = 0; v < vertices; v++) {
            int o = v * floatsPerVertex;
            out.vertex(position, data[o], data[o + 1], data[o + 2])
                    .color(data[o + 8], data[o + 9], data[o + 10], data[o + 11])
                    .uv(data[o + 3], data[o + 4])
                    .overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(light)
                    .normal(normal, data[o + 5], data[o + 6], data[o + 7])
                    .endVertex();
        }
        buffers.endBatch(type);
        pose.popPose();
        framesDrawn++;
    }
}
