package retrofit.forge.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import retrofit.forge.RetrofitMod;
import retrofit.forge.world.LegacyBridge;
import retrofit.host.LegacyRuntime;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the GL emulation's capture (see retrofit.legacy.gl.Capture) and draws it with 1.20.1's
 * render types. Legacy textures are either the block atlas (where all legacy icons are stitched)
 * or a texture file loaded on first use from the legacy mod jars or the 1.7.10 jar. Callers hold
 * {@link LegacyBridge#renderLock()} from the legacy render call until drawing is done.
 */
public final class LegacyCapture {
    public static final int FLOATS_PER_VERTEX = 12;
    private static final int BLEND = 1 << 16;
    private static final int TEXTURE_MASK = 0xFFFF;
    private static final Logger LOGGER = LogUtils.getLogger();

    private static MethodHandle data;
    private static MethodHandle states;
    private static MethodHandle lights;
    private static MethodHandle textureName;
    private static final Map<Integer, ResourceLocation> TEXTURES = new HashMap<>();
    private static List<ZipFile> sources;

    private LegacyCapture() {
    }

    private static void init() throws ReflectiveOperationException {
        if (data != null) {
            return;
        }
        Class<?> capture = RetrofitMod.runtime().rootLoader().loadClass("retrofit.legacy.gl.Capture");
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        data = lookup.findStatic(capture, "data", MethodType.methodType(float[].class));
        states = lookup.findStatic(capture, "states", MethodType.methodType(int[].class));
        lights = lookup.findStatic(capture, "lights", MethodType.methodType(int[].class));
        textureName = lookup.findStatic(capture, "textureName", MethodType.methodType(String.class, int.class));
    }

    public static float[] data() throws Throwable {
        init();
        return (float[]) data.invokeExact();
    }

    /**
     * Draws the first {@code vertices} captured vertices (quads) with the pose, grouping runs of
     * the same texture and blend state into one render type each.
     */
    public static void draw(PoseStack.Pose pose, MultiBufferSource buffers, int vertices, int packedLight, int overlay) throws Throwable {
        if (vertices < 4) {
            return;
        }
        init();
        float[] d = (float[]) data.invokeExact();
        int[] state = (int[]) states.invokeExact();
        int[] light = (int[]) lights.invokeExact();
        Matrix4f position = pose.pose();
        Matrix3f normal = pose.normal();
        VertexConsumer out = null;
        int current = -1;
        for (int v = 0; v + 3 < vertices; v += 4) {
            if (state[v] != current || out == null) {
                current = state[v];
                out = buffers.getBuffer(renderType(current));
            }
            for (int corner = 0; corner < 4; corner++) {
                int i = v + corner;
                int o = i * FLOATS_PER_VERTEX;
                out.vertex(position, d[o], d[o + 1], d[o + 2])
                        .color(d[o + 8], d[o + 9], d[o + 10], d[o + 11])
                        .uv(d[o + 3], d[o + 4])
                        .overlayCoords(overlay)
                        .uv2(light[i] >= 0 ? light[i] : packedLight)
                        .normal(normal, d[o + 5], d[o + 6], d[o + 7])
                        .endVertex();
            }
        }
    }

    private static RenderType renderType(int state) throws Throwable {
        ResourceLocation texture = texture(state & TEXTURE_MASK);
        return (state & BLEND) != 0 ? RenderType.entityTranslucent(texture) : RenderType.entityCutoutNoCull(texture);
    }

    /** The 1.20.1 texture for a captured legacy texture index (render thread). */
    private static ResourceLocation texture(int index) throws Throwable {
        ResourceLocation known = TEXTURES.get(index);
        if (known != null) {
            return known;
        }
        String legacy = (String) textureName.invokeExact(index);
        ResourceLocation texture = load(legacy);
        TEXTURES.put(index, texture);
        return texture;
    }

    /** "domain:path" as 1.7.10 resolved it: assets/<domain>/<path>, from a mod jar or the 1.7.10 jar. */
    private static ResourceLocation load(String legacy) {
        int colon = legacy.indexOf(':');
        String domain = colon < 0 ? "minecraft" : legacy.substring(0, colon).toLowerCase(Locale.ROOT);
        String path = colon < 0 ? legacy : legacy.substring(colon + 1);
        if (path.startsWith("textures/atlas/")) {
            return TextureAtlas.LOCATION_BLOCKS; // all legacy block and item icons are stitched there
        }
        String entryName = "assets/" + domain + "/" + path;
        for (ZipFile jar : sources()) {
            ZipEntry entry = jar.getEntry(entryName);
            if (entry == null) {
                continue;
            }
            try (InputStream in = jar.getInputStream(entry)) {
                ResourceLocation id = new ResourceLocation(RetrofitMod.MOD_ID, "legacy_texture/" + domain + "/"
                        + path.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_"));
                Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(NativeImage.read(in)));
                return id;
            } catch (IOException e) {
                LOGGER.warn("Could not read legacy texture {}", legacy, e);
            }
        }
        LOGGER.debug("Legacy texture {} not found", legacy);
        return MissingTextureAtlasSprite.getLocation();
    }

    private static List<ZipFile> sources() {
        if (sources == null) {
            sources = new ArrayList<>();
            LegacyRuntime runtime = RetrofitMod.runtime();
            List<Path> files = new ArrayList<>();
            for (String mod : LegacyBridge.modSources()) {
                files.add(Path.of(mod));
            }
            files.add(runtime.gameJar());
            for (Path file : files) {
                try {
                    sources.add(new ZipFile(new File(file.toString())));
                } catch (IOException e) {
                    LOGGER.warn("Could not open {} for legacy textures", file, e);
                }
            }
        }
        return sources;
    }
}
