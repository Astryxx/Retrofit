package retrofit.forge.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import retrofit.forge.world.LegacyBridge;

/**
 * How a legacy fluid looks in 1.20.1 tanks, pipes and GUIs: the legacy fluid's own still and
 * flowing icons (stitched into the block atlas like other legacy textures), tinted with its
 * colour; water's look if it has no icons.
 */
public final class LegacyFluidLook implements IClientFluidTypeExtensions {
    private static final ResourceLocation WATER_STILL = new ResourceLocation("block/water_still");
    private static final ResourceLocation WATER_FLOW = new ResourceLocation("block/water_flow");
    private final String legacyName;
    private final int color;
    private ResourceLocation still;
    private ResourceLocation flowing;

    private LegacyFluidLook(String legacyName, int color) {
        this.legacyName = legacyName;
        this.color = color;
    }

    public static IClientFluidTypeExtensions of(String legacyName, int color) {
        return new LegacyFluidLook(legacyName, color);
    }

    /** The icons are known once legacy textures are registered (after the first resource load). */
    private void resolve() {
        if (still != null) {
            return;
        }
        try {
            String[] icons = LegacyBridge.call("fluidIcons", legacyName);
            if (icons[0] != null) {
                still = new ResourceLocation(LegacyAssetPack.spriteId("blocks", icons[0]));
                flowing = new ResourceLocation(LegacyAssetPack.spriteId("blocks", icons[1] != null ? icons[1] : icons[0]));
            }
        } catch (RuntimeException e) {
            // not yet: water's look meanwhile
        }
    }

    @Override
    public ResourceLocation getStillTexture() {
        resolve();
        return still != null ? still : WATER_STILL;
    }

    @Override
    public ResourceLocation getFlowingTexture() {
        resolve();
        return flowing != null ? flowing : WATER_FLOW;
    }

    @Override
    public int getTintColor() {
        resolve();
        return still != null ? 0xFF000000 | color : 0xFF000000 | (color == 0xFFFFFF ? 0x3F76E4 : color);
    }
}
