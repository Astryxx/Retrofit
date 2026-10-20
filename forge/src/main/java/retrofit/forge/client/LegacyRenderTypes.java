package retrofit.forge.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Function;

/**
 * Render types for what 1.7.10 code drew with GL state 1.20.1's entity types lack (extends
 * RenderType only to reach its shards). Vertices are written in the entity format; formats
 * without some of its elements skip them.
 */
final class LegacyRenderTypes extends RenderType {
    /** GL_LIGHTING off: the texture and colour as they are, with the light map (no shading); blended or not. */
    private static final Function<ResourceLocation, RenderType> UNLIT = Util.memoize(texture -> create("retrofit_legacy_unlit",
            DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, VertexFormat.Mode.QUADS, 256, false, true,
            CompositeState.builder()
                    .setShaderState(RENDERTYPE_TEXT_SHADER)
                    .setTextureState(new TextureStateShard(texture, false, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setLightmapState(LIGHTMAP)
                    .createCompositeState(false)));
    /** glBlendFunc(GL_SRC_ALPHA, GL_ONE): glows add their colour, unshaded and at full brightness, without hiding what is behind. */
    private static final Function<ResourceLocation, RenderType> ADDITIVE = Util.memoize(texture -> create("retrofit_legacy_additive",
            DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, false, true,
            CompositeState.builder()
                    .setShaderState(RENDERTYPE_EYES_SHADER)
                    .setTextureState(new TextureStateShard(texture, false, false))
                    .setTransparencyState(LIGHTNING_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false)));

    /** Skies (WorldProvider.getSkyRenderer): behind everything, unshaded; blended or added (stars, glows). */
    private static final Function<ResourceLocation, RenderType> SKY = Util.memoize(texture -> sky("retrofit_legacy_sky", texture, TRANSLUCENT_TRANSPARENCY));
    private static final Function<ResourceLocation, RenderType> SKY_ADDITIVE = Util.memoize(texture -> sky("retrofit_legacy_sky_additive", texture, LIGHTNING_TRANSPARENCY));

    private static RenderType sky(String name, ResourceLocation texture, TransparencyStateShard transparency) {
        return create(name, DefaultVertexFormat.POSITION_COLOR_TEX, VertexFormat.Mode.QUADS, 256, false, false,
                CompositeState.builder()
                        .setShaderState(POSITION_COLOR_TEX_SHADER)
                        .setTextureState(new TextureStateShard(texture, false, false))
                        .setTransparencyState(transparency)
                        .setDepthTestState(NO_DEPTH_TEST)
                        .setCullState(NO_CULL)
                        .setWriteMaskState(COLOR_WRITE)
                        .createCompositeState(false));
    }

    private LegacyRenderTypes() {
        super("retrofit_legacy", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, false, false, () -> { }, () -> { });
    }

    static RenderType unlit(ResourceLocation texture) {
        return UNLIT.apply(texture);
    }

    static RenderType additive(ResourceLocation texture) {
        return ADDITIVE.apply(texture);
    }

    static RenderType sky(ResourceLocation texture, boolean additive) {
        return (additive ? SKY_ADDITIVE : SKY).apply(texture);
    }
}
