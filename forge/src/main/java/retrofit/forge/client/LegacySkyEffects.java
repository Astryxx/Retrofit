package retrofit.forge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import retrofit.forge.RetrofitMod;
import retrofit.forge.world.LegacyBridge;
import retrofit.forge.world.LegacyContent;

/**
 * Mods' dimensions look like the overworld or the nether (their dimension types name these
 * effects; see LegacyDimensions), except for the sky when the mod's world provider draws its own
 * (WorldProvider.getSkyRenderer: Witchery's Spirit World, Mo' Creatures' Wyvern Lair...).
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class LegacySkyEffects {
    public static final ResourceLocation OVERWORLD = new ResourceLocation(RetrofitMod.MOD_ID, "legacy_overworld");
    public static final ResourceLocation NETHER = new ResourceLocation(RetrofitMod.MOD_ID, "legacy_nether");
    private static boolean failed;
    private static final java.util.Set<ResourceLocation> LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private LegacySkyEffects() {
    }

    @SubscribeEvent
    public static void onRegisterEffects(RegisterDimensionSpecialEffectsEvent event) {
        event.register(OVERWORLD, new Overworld());
        event.register(NETHER, new Nether());
    }

    /** Draws the legacy sky if the level's legacy world has a sky renderer; false lets 1.20.1 draw its own. */
    private static boolean renderLegacySky(ClientLevel level, float partialTick, PoseStack pose) {
        if (failed || RetrofitMod.runtime() == null) {
            return false;
        }
        try {
            synchronized (LegacyBridge.renderLock()) {
                int vertices = LegacyBridge.call("renderSky", LegacyContent.legacyWorld(level), partialTick);
                if (vertices < 0) {
                    return false;
                }
                MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
                LegacyCapture.drawSky(pose.last(), buffers, vertices);
                buffers.endBatch();
                if (LOGGED.add(level.dimension().location())) {
                    float[] d = LegacyCapture.data();
                    LOGGER.info("Drawing the legacy sky of {}: {} vertices{}", level.dimension().location(), vertices, vertices == 0 ? ""
                            : String.format(" (first at %.1f %.1f %.1f, rgba %.2f %.2f %.2f %.2f, state %x)", d[0], d[1], d[2], d[8], d[9], d[10], d[11],
                            LegacyCapture.states()[0]));
                }
            }
            return true;
        } catch (Throwable e) {
            failed = true;
            LegacyBridge.reportClientFailure("sky drawing", e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e));
            return false;
        }
    }

    private static boolean atmosphereFailed;

    /**
     * Draws the legacy clouds (kind 0) or rain and snow (kind 1) if the level's legacy world has a renderer for them
     * (WorldProvider.getCloudRenderer, getWeatherRenderer: drawn relative to the camera, as 1.7.10's RenderGlobal
     * did); false lets 1.20.1 draw its own.
     */
    static boolean renderLegacyAtmosphere(ClientLevel level, int kind, float partialTick, PoseStack pose) {
        if (atmosphereFailed || RetrofitMod.runtime() == null) {
            return false;
        }
        try {
            synchronized (LegacyBridge.renderLock()) {
                int vertices = LegacyBridge.call("renderAtmosphere", LegacyContent.legacyWorld(level), kind, partialTick);
                if (vertices < 0) {
                    return false;
                }
                MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
                int light = kind == 0 ? net.minecraft.client.renderer.LightTexture.FULL_BRIGHT
                        : net.minecraft.client.renderer.LevelRenderer.getLightColor(level, Minecraft.getInstance().gameRenderer.getMainCamera().getBlockPosition());
                LegacyCapture.draw(pose.last(), buffers, vertices, light, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
                buffers.endBatch();
            }
            return true;
        } catch (Throwable e) {
            atmosphereFailed = true;
            LegacyBridge.reportClientFailure(kind == 0 ? "cloud drawing" : "weather drawing", e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e));
            return false;
        }
    }

    static final class Overworld extends DimensionSpecialEffects.OverworldEffects {
        @Override
        public boolean renderSky(ClientLevel level, int ticks, float partialTick, PoseStack pose, Camera camera, Matrix4f projection,
                                 boolean foggy, Runnable setupFog) {
            return renderLegacySky(level, partialTick, pose);
        }

        @Override
        public boolean renderClouds(ClientLevel level, int ticks, float partialTick, PoseStack pose, double camX, double camY, double camZ, Matrix4f projection) {
            return renderLegacyAtmosphere(level, 0, partialTick, pose);
        }

        @Override
        public boolean renderSnowAndRain(ClientLevel level, int ticks, float partialTick, net.minecraft.client.renderer.LightTexture lightTexture,
                                         double camX, double camY, double camZ) {
            return renderLegacyAtmosphere(level, 1, partialTick, new PoseStack());
        }
    }

    static final class Nether extends DimensionSpecialEffects.NetherEffects {
        @Override
        public boolean renderSky(ClientLevel level, int ticks, float partialTick, PoseStack pose, Camera camera, Matrix4f projection,
                                 boolean foggy, Runnable setupFog) {
            return renderLegacySky(level, partialTick, pose);
        }

        @Override
        public boolean renderClouds(ClientLevel level, int ticks, float partialTick, PoseStack pose, double camX, double camY, double camZ, Matrix4f projection) {
            return renderLegacyAtmosphere(level, 0, partialTick, pose);
        }

        @Override
        public boolean renderSnowAndRain(ClientLevel level, int ticks, float partialTick, net.minecraft.client.renderer.LightTexture lightTexture,
                                         double camX, double camY, double camZ) {
            return renderLegacyAtmosphere(level, 1, partialTick, new PoseStack());
        }
    }
}
