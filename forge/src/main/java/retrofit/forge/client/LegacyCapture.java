package retrofit.forge.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.LightTexture;
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
    public static final int BLEND = 1 << 16;
    /** Drawn with texturing off (GUI rectangles, gradients, untextured renderer parts). */
    public static final int UNTEXTURED = 1 << 17;
    /** See the legacy Capture: glBlendFunc(..., GL_ONE). */
    public static final int ADDITIVE = 1 << 18;
    /** See the legacy Capture: GL_LIGHTING off. */
    public static final int UNLIT = 1 << 19;
    /** See the legacy Capture: a line segment a, b as the quad a, b, b, a. */
    public static final int LINES = 1 << 20;
    /** See the legacy Capture: GL_CULL_FACE on, and culling the other way round. */
    public static final int CULLED = 1 << 21, REVERSED = 1 << 22;
    /** See the legacy Capture: glDepthFunc(GL_EQUAL). */
    public static final int DEPTH_EQUAL = 1 << 23;
    /** See the legacy Capture: GL_DEPTH_TEST on, glDepthMask(false), and glDepthFunc - GL_NEVER in DEPTH_FUNC. */
    public static final int DEPTH_TEST = 1 << 24, NO_DEPTH_WRITE = 1 << 25;
    public static final int DEPTH_FUNC_SHIFT = 26, DEPTH_FUNC = 7 << DEPTH_FUNC_SHIFT;
    public static final int TEXTURE_MASK = 0xFFFF;
    private static final Logger LOGGER = LogUtils.getLogger();

    private static MethodHandle data;
    private static MethodHandle states;
    private static MethodHandle lights;
    private static MethodHandle textureName;
    private static MethodHandle uploadedVersion;
    private static MethodHandle uploadedPixels;
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
        Class<?> gl = RetrofitMod.runtime().rootLoader().loadClass("retrofit.legacy.gl.GL11");
        uploadedVersion = lookup.findStatic(gl, "uploadedVersion", MethodType.methodType(int.class, String.class));
        uploadedPixels = lookup.findStatic(gl, "uploadedPixels", MethodType.methodType(Object[].class, String.class));
    }

    public static float[] data() throws Throwable {
        init();
        return (float[]) data.invokeExact();
    }

    /** Tests: what the first {@code vertices} captured vertices are (extent, alpha, textures, blending). */
    public static String describe(int vertices) throws Throwable {
        init();
        float[] d = (float[]) data.invokeExact();
        int[] state = (int[]) states.invokeExact();
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        float minAlpha = Float.MAX_VALUE, maxAlpha = -Float.MAX_VALUE;
        java.util.Set<String> textures = new java.util.TreeSet<>();
        int blended = 0;
        for (int v = 0; v < vertices; v++) {
            int o = v * FLOATS_PER_VERTEX;
            for (int axis = 0; axis < 3; axis++) {
                min[axis] = Math.min(min[axis], d[o + axis]);
                max[axis] = Math.max(max[axis], d[o + axis]);
            }
            minAlpha = Math.min(minAlpha, d[o + 11]);
            maxAlpha = Math.max(maxAlpha, d[o + 11]);
            blended += (state[v] & BLEND) != 0 ? 1 : 0;
            textures.add((state[v] & UNTEXTURED) != 0 ? "untextured" : (String) textureName.invokeExact(state[v] & TEXTURE_MASK));
        }
        return vertices + " vertices from " + java.util.Arrays.toString(min) + " to " + java.util.Arrays.toString(max) + ", alpha "
                + minAlpha + ".." + maxAlpha + ", " + blended + " blended, textures " + textures;
    }

    /**
     * Draws the first {@code vertices} captured vertices (quads) with the pose, grouping runs of
     * the same texture and blend state into one render type each.
     */
    public static void draw(PoseStack.Pose pose, MultiBufferSource buffers, int vertices, int packedLight, int overlay) throws Throwable {
        draw(pose, buffers, vertices, packedLight, overlay, true);
    }

    /**
     * As {@link #draw(PoseStack.Pose, MultiBufferSource, int, int, int)}; without {@code ownLight}
     * the light legacy code set is ignored (1.7.10 drew GUIs with the lightmap off).
     */
    public static void draw(PoseStack.Pose pose, MultiBufferSource buffers, int vertices, int packedLight, int overlay, boolean ownLight) throws Throwable {
        draw(pose, buffers, vertices, packedLight, overlay, ownLight, false);
    }

    /** A sky (WorldProvider.getSkyRenderer): behind everything, unshaded. */
    public static void drawSky(PoseStack.Pose pose, MultiBufferSource buffers, int vertices) throws Throwable {
        draw(pose, buffers, vertices, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, false, true);
    }

    private static void draw(PoseStack.Pose pose, MultiBufferSource buffers, int vertices, int packedLight, int overlay, boolean ownLight, boolean sky) throws Throwable {
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
        java.util.Set<RenderType> drawn = new java.util.LinkedHashSet<>();
        for (int v = 0; v + 3 < vertices; v += 4) {
            if (state[v] != current || out == null) {
                current = state[v];
                RenderType type = (current & LINES) != 0 ? RenderType.lines() : sky ? skyType(current) : renderType(current);
                if ((current & DEPTH_EQUAL) != 0 && !sky && buffers instanceof MultiBufferSource.BufferSource source) {
                    // drawn over what came before it only: that has to be in the depth buffer first
                    for (RenderType before : drawn) {
                        source.endBatch(before);
                    }
                    drawn.clear();
                }
                drawn.add(type);
                out = buffers.getBuffer(type);
            }
            if ((current & LINES) != 0) { // one segment: its two ends, the normal along it (as LevelRenderer's outlines)
                int a = v * FLOATS_PER_VERTEX, b = (v + 1) * FLOATS_PER_VERTEX;
                float dx = d[b] - d[a], dy = d[b + 1] - d[a + 1], dz = d[b + 2] - d[a + 2];
                float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (length > 0) {
                    for (int o : new int[]{a, b}) {
                        out.vertex(position, d[o], d[o + 1], d[o + 2]).color(d[o + 8], d[o + 9], d[o + 10], d[o + 11])
                                .normal(normal, dx / length, dy / length, dz / length).endVertex();
                    }
                }
                continue;
            }
            boolean reversed = !sky && (current & REVERSED) != 0;
            for (int corner = 0; corner < 4; corner++) {
                int i = v + (reversed ? 3 - corner : corner);
                int o = i * FLOATS_PER_VERTEX;
                out.vertex(position, d[o], d[o + 1], d[o + 2])
                        .color(d[o + 8], d[o + 9], d[o + 10], d[o + 11])
                        .uv(d[o + 3], d[o + 4])
                        .overlayCoords(overlay)
                        .uv2(ownLight && light[i] >= 0 ? light[i] : packedLight)
                        .normal(normal, d[o + 5], d[o + 6], d[o + 7])
                        .endVertex();
            }
        }
    }

    /** Per-vertex light the legacy renderer set (Tessellator.setBrightness), or -1. */
    public static int[] lights() throws Throwable {
        init();
        return (int[]) lights.invokeExact();
    }

    /** Per-vertex states of the capture (see retrofit.legacy.gl.Capture). */
    public static int[] states() throws Throwable {
        init();
        return (int[]) states.invokeExact();
    }

    private static ResourceLocation white;

    /** A plain white texture, for quads legacy code drew with texturing off. */
    public static ResourceLocation white() {
        if (white == null) {
            NativeImage image = new NativeImage(1, 1, false);
            image.setPixelRGBA(0, 0, 0xFFFFFFFF);
            white = new ResourceLocation(RetrofitMod.MOD_ID, "legacy_texture/white");
            Minecraft.getInstance().getTextureManager().register(white, new DynamicTexture(image));
        }
        return white;
    }

    private static RenderType skyType(int state) throws Throwable {
        ResourceLocation texture = (state & UNTEXTURED) != 0 ? white() : texture(state & TEXTURE_MASK);
        return LegacyRenderTypes.sky(texture, (state & ADDITIVE) != 0);
    }

    private static RenderType renderType(int state) throws Throwable {
        ResourceLocation texture = (state & UNTEXTURED) != 0 ? white() : texture(state & TEXTURE_MASK);
        boolean culled = (state & CULLED) != 0, depthEqual = (state & DEPTH_EQUAL) != 0;
        if ((state & ADDITIVE) != 0) {
            return LegacyRenderTypes.of(texture, LegacyRenderTypes.Shading.ADDITIVE, culled, depthEqual);
        }
        if ((state & UNLIT) != 0) {
            return LegacyRenderTypes.of(texture, LegacyRenderTypes.Shading.UNLIT, culled, depthEqual);
        }
        if (depthEqual) {
            return LegacyRenderTypes.of(texture, LegacyRenderTypes.Shading.LIT, culled, true);
        }
        if ((state & BLEND) != 0) {
            return culled ? RenderType.entityTranslucentCull(texture) : RenderType.entityTranslucent(texture);
        }
        return culled ? RenderType.entityCutout(texture) : RenderType.entityCutoutNoCull(texture);
    }

    private static final String HOST_SKIN = "retrofit:host_skin";
    /** 1.20.1 skin -> its copy in the 64x32 layout. */
    private static final Map<ResourceLocation, ResourceLocation> LEGACY_SKINS = new HashMap<>();

    /**
     * A skin in 1.7.10's 64x32 layout: 1.20.1 skins are 64x64, and their top half is the old layout
     * that 1.7.10's models map (otherwise their arms and legs come out stretched). Copied from the
     * skin texture once it has loaded; the skin itself until then.
     */
    private static ResourceLocation legacySkin(ResourceLocation skin) {
        ResourceLocation known = LEGACY_SKINS.get(skin);
        if (known != null) {
            return known;
        }
        var texture = Minecraft.getInstance().getTextureManager().getTexture(skin, null);
        if (texture == null) {
            return skin;
        }
        com.mojang.blaze3d.systems.RenderSystem.bindTexture(texture.getId());
        int width = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH);
        int height = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT);
        if (width <= 0 || height != width) {
            return skin; // not loaded yet, or already the old layout
        }
        NativeImage full = new NativeImage(width, height, false);
        full.downloadTexture(0, false);
        NativeImage half = new NativeImage(width, height / 2, false);
        full.copyRect(half, 0, 0, 0, 0, width, height / 2, false, false);
        full.close();
        ResourceLocation id = new ResourceLocation(RetrofitMod.MOD_ID, "legacy_skin/" + skin.getNamespace() + "/" + skin.getPath());
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(half));
        LEGACY_SKINS.put(skin, id);
        return id;
    }

    /**
     * The skin of a player by name, as 1.7.10's "skins/<name>" texture (other players, and heads drawn
     * by mods): that player's 1.20.1 skin if they are on the server, else the default one.
     */
    private static ResourceLocation namedSkin(String name) {
        var connection = Minecraft.getInstance().getConnection();
        var info = connection == null || name.isEmpty() ? null : connection.getPlayerInfo(name);
        return legacySkin(info != null ? info.getSkinLocation() : net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkin());
    }

    /** The legacy name of a captured texture index ("domain:path"), from any thread. */
    public static String textureName(int index) throws Throwable {
        init();
        return (String) textureName.invokeExact(index);
    }

    /** The 1.20.1 texture for a captured legacy texture index (render thread). */
    public static ResourceLocation texture(int index) throws Throwable {
        init();
        String legacy = (String) textureName.invokeExact(index);
        if (legacy.equals(HOST_SKIN)) {
            // the local player's skin (BridgeClientPlayer.getLocationSkin): looked up each time, as it may still be loading
            var player = Minecraft.getInstance().player;
            return legacySkin(player != null ? player.getSkinTextureLocation() : net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkin());
        }
        if (legacy.startsWith("minecraft:skins/")) {
            return namedSkin(legacy.substring("minecraft:skins/".length())); // looked up each time too
        }
        int version = (int) uploadedVersion.invokeExact(legacy);
        if (version >= 0) {
            return uploaded(legacy, version); // made from pixels: looked up each time, as it may change
        }
        ResourceLocation known = TEXTURES.get(index);
        if (known != null) return known;
        ResourceLocation texture = load(legacy);
        TEXTURES.put(index, texture);
        return texture;
    }

    /** A texture legacy code made from pixels, as the host has it, and the upload it has. */
    private record Uploaded(ResourceLocation id, DynamicTexture texture, int version) {
    }

    private static final Map<String, Uploaded> UPLOADS = new HashMap<>();

    /**
     * A texture legacy code made from pixels (glTexImage2D: DragonAPI's sprite sheets, 1.7.10's
     * DynamicTexture), as a DynamicTexture of the host, uploaded again whenever legacy code changes it.
     */
    private static ResourceLocation uploaded(String legacy, int version) throws Throwable {
        Uploaded known = UPLOADS.get(legacy);
        if (known != null && known.version() == version) {
            return known.id();
        }
        Object[] pixels = (Object[]) uploadedPixels.invokeExact(legacy);
        int width = (Integer) pixels[0], height = (Integer) pixels[1];
        int[] argb = (int[]) pixels[2];
        DynamicTexture texture = known != null && known.texture().getPixels() != null && known.texture().getPixels().getWidth() == width
                && known.texture().getPixels().getHeight() == height ? known.texture() : null;
        ResourceLocation id = known != null ? known.id() : new ResourceLocation(RetrofitMod.MOD_ID, "legacy_gl/" + legacy.replaceAll("[^a-z0-9_./-]", "_"));
        if (texture == null) {
            texture = new DynamicTexture(width, height, false);
            Minecraft.getInstance().getTextureManager().register(id, texture);
        }
        NativeImage image = texture.getPixels();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int c = argb[y * width + x];
                image.setPixelRGBA(x, y, (c & 0xFF00FF00) | ((c >> 16) & 0xFF) | ((c & 0xFF) << 16)); // NativeImage stores ABGR
            }
        }
        texture.upload();
        UPLOADS.put(legacy, new Uploaded(id, texture, (Integer) pixels[3]));
        return id;
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
                boolean[] blurClamp = textureMetadata(jar, entryName);
                Minecraft.getInstance().getTextureManager().register(id, blurClamp[0] || blurClamp[1]
                        ? new LegacyTexture(NativeImage.read(in), blurClamp[0], blurClamp[1]) : new DynamicTexture(NativeImage.read(in)));
                return id;
            } catch (IOException e) {
                LOGGER.warn("Could not read legacy texture {}", legacy, e);
            }
        }
        // Resource packs added by mods may synthesize an image when asked (CustomNPCs' wood
        // furniture cache). Such a texture has no zip entry to copy into the generated pack.
        byte[] generated = retrofit.forge.world.LegacyBridge.call("legacyResource", domain, path);
        if (generated != null) {
            try (InputStream in = new java.io.ByteArrayInputStream(generated)) {
                ResourceLocation id = new ResourceLocation(RetrofitMod.MOD_ID, "legacy_resource/" + domain + "/"
                        + path.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_"));
                Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(NativeImage.read(in)));
                return id;
            } catch (IOException invalidImage) {
                LOGGER.debug("Legacy resource {} was not an image", legacy, invalidImage);
            }
        }
        LOGGER.warn("Legacy texture {} not found in any mod; drawn as the missing texture", legacy);
        return MissingTextureAtlasSprite.getLocation();
    }

    /** {blur, clamp} from the texture's .mcmeta, as 1.7.10's SimpleTexture read them. */
    private static boolean[] textureMetadata(ZipFile jar, String entryName) {
        ZipEntry entry = jar.getEntry(entryName + ".mcmeta");
        if (entry == null) {
            return new boolean[2];
        }
        try (InputStream in = jar.getInputStream(entry)) {
            com.google.gson.JsonObject texture = net.minecraft.util.GsonHelper.getAsJsonObject(
                    com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
                            .getAsJsonObject(), "texture", new com.google.gson.JsonObject());
            return new boolean[]{net.minecraft.util.GsonHelper.getAsBoolean(texture, "blur", false),
                    net.minecraft.util.GsonHelper.getAsBoolean(texture, "clamp", false)};
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read {}.mcmeta", entryName, e);
            return new boolean[2];
        }
    }

    /**
     * A legacy texture uploaded as its .mcmeta asks: clamped at its edges (entity shadows give
     * shadow.png coordinates past them, and would tile otherwise) or blurred.
     */
    private static final class LegacyTexture extends DynamicTexture {
        private final boolean blur;
        private final boolean clamp;

        LegacyTexture(NativeImage image, boolean blur, boolean clamp) {
            super(image); // uploads before the fields are set
            this.blur = blur;
            this.clamp = clamp;
            upload();
        }

        @Override
        public void upload() {
            NativeImage pixels = getPixels();
            if (pixels != null) {
                bind();
                pixels.upload(0, 0, 0, 0, 0, pixels.getWidth(), pixels.getHeight(), blur, clamp, false, false);
            }
        }
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
