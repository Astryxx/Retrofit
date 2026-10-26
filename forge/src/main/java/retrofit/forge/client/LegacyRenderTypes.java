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
    /** How a quad is shaded and blended. */
    enum Shading {
        /** Lit and blended as entityTranslucent. */
        LIT,
        /** GL_LIGHTING off: the texture and colour as they are, with the light map (no shading); blended or not. */
        UNLIT,
        /** glBlendFunc(GL_SRC_ALPHA, GL_ONE): glows add their colour, unshaded and at full brightness, without hiding what is behind. */
        ADDITIVE
    }

    /**
     * {@code culled}: GL_CULL_FACE on. {@code depthEqual}: glDepthFunc(GL_EQUAL), drawn only over
     * what was drawn at the same depth (GregTech's fluids inside cells, mods' own glints).
     */
    private record Look(ResourceLocation texture, Shading shading, boolean culled, boolean depthEqual) {
    }

    private static final Function<Look, RenderType> TYPES = Util.memoize(LegacyRenderTypes::create);

    /** Skies (WorldProvider.getSkyRenderer): behind everything, unshaded; blended or added (stars, glows). */
    private static final Function<ResourceLocation, RenderType> SKY = Util.memoize(texture -> sky("retrofit_legacy_sky", texture, TRANSLUCENT_TRANSPARENCY));
    private static final Function<ResourceLocation, RenderType> SKY_ADDITIVE = Util.memoize(texture -> sky("retrofit_legacy_sky_additive", texture, LIGHTNING_TRANSPARENCY));

    private static RenderType create(Look look) {
        String name = "retrofit_legacy_" + look.shading().name().toLowerCase(java.util.Locale.ROOT)
                + (look.culled() ? "_culled" : "") + (look.depthEqual() ? "_depth_equal" : "");
        CompositeState.CompositeStateBuilder state = CompositeState.builder()
                .setTextureState(new TextureStateShard(look.texture(), false, false))
                .setCullState(look.culled() ? CULL : NO_CULL)
                .setDepthTestState(look.depthEqual() ? EQUAL_DEPTH_TEST : LEQUAL_DEPTH_TEST);
        return switch (look.shading()) {
            case LIT -> create(name, DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, true, true, state
                    .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .setWriteMaskState(look.depthEqual() ? COLOR_WRITE : COLOR_DEPTH_WRITE)
                    .createCompositeState(true));
            case UNLIT -> create(name, DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, VertexFormat.Mode.QUADS, 256, false, true, state
                    .setShaderState(RENDERTYPE_TEXT_SHADER)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setLightmapState(LIGHTMAP)
                    .setWriteMaskState(look.depthEqual() ? COLOR_WRITE : COLOR_DEPTH_WRITE)
                    .createCompositeState(false));
            case ADDITIVE -> create(name, DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, false, true, state
                    .setShaderState(RENDERTYPE_EYES_SHADER)
                    .setTransparencyState(LIGHTNING_TRANSPARENCY)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));
        };
    }

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

    static RenderType of(ResourceLocation texture, Shading shading, boolean culled, boolean depthEqual) {
        return TYPES.apply(new Look(texture, shading, culled, depthEqual));
    }

    static RenderType sky(ResourceLocation texture, boolean additive) {
        return (additive ? SKY_ADDITIVE : SKY).apply(texture);
    }
}
