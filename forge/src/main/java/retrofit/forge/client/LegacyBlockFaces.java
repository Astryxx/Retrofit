package retrofit.forge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which faces of a legacy block's model hide whatever is behind them: a face covering the whole
 * side of the block with a texture without a transparent pixel. Mods' isOpaqueCube may answer
 * differently where the block is (OreSpawn's Crystal dimension stone is opaque only while the
 * player is in that dimension), and 1.20.1 fixes occlusion once for the block; but a face behind
 * such a face can't be seen either way, so it is left out of the chunk mesh (see
 * LegacyBlock.skipRendering). Lighting and collision don't change.
 */
public final class LegacyBlockFaces {
    /** Per block state: a bit per direction (Direction.get3DDataValue) whose face hides what is behind it. */
    private static final Map<BlockState, Integer> COVERING = new ConcurrentHashMap<>();

    private LegacyBlockFaces() {
    }

    private static retrofit.forge.world.LegacyBlock[] notOpaque;
    private static Object[] notOpaqueLegacy;

    /**
     * Client tick: the blocks that aren't opaque cubes for 1.20.1 are asked whether they are now;
     * if an answer changed, the chunk meshes are built again with it (see LegacyBlock.skipRendering).
     */
    public static void refreshOpacity() {
        if (notOpaque == null) {
            notOpaque = retrofit.forge.world.LegacyContent.blocks().stream().filter(block -> !block.isOpaque())
                    .toArray(retrofit.forge.world.LegacyBlock[]::new);
            notOpaqueLegacy = java.util.Arrays.stream(notOpaque).map(retrofit.forge.world.LegacyBlock::legacyBlock).toArray();
        }
        if (notOpaque.length == 0) {
            return;
        }
        boolean[] now = retrofit.forge.world.LegacyBridge.call("opaqueCubesNow", (Object) notOpaqueLegacy);
        boolean changed = false;
        for (int i = 0; i < notOpaque.length; i++) {
            if (notOpaque[i].opaqueNow() != now[i]) {
                notOpaque[i].setOpaqueNow(now[i]);
                changed = true;
            }
        }
        if (changed && Minecraft.getInstance().level != null) {
            Minecraft.getInstance().levelRenderer.allChanged();
        }
    }

    /** Models were baked again (resource reload): the faces are worked out again. */
    static void clear() {
        COVERING.clear();
    }

    /** Whether the state's model has a face on its {@code side} that hides what is behind it. */
    public static boolean covers(BlockState state, Direction side) {
        return (COVERING.computeIfAbsent(state, LegacyBlockFaces::covering) & 1 << side.get3DDataValue()) != 0;
    }

    private static int covering(BlockState state) {
        if (state.getBlock() instanceof retrofit.forge.world.LegacyBlock block && block.renderPass() == 1) {
            return 0; // translucent: blended, so its faces show what is behind them
        }
        var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        if (model instanceof LegacyBakedModel) {
            return 0; // drawn by RenderBlocks, which asks the neighbours itself
        }
        int sides = 0;
        for (Direction side : Direction.values()) {
            for (BakedQuad quad : model.getQuads(state, side, RandomSource.create(42L), ModelData.EMPTY, null)) {
                if (fullFace(quad, side) && opaque(quad)) {
                    sides |= 1 << side.get3DDataValue();
                    break;
                }
            }
        }
        return sides;
    }

    /** Whether the quad lies on the block's side and spans all of it. */
    private static boolean fullFace(BakedQuad quad, Direction side) {
        int[] data = quad.getVertices();
        int stride = data.length / 4;
        int axis = side.getAxis().ordinal();
        float plane = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
        float[] min = {1, 1, 1};
        float[] max = {0, 0, 0};
        for (int v = 0; v < 4; v++) {
            for (int i = 0; i < 3; i++) {
                float position = Float.intBitsToFloat(data[v * stride + i]);
                if (i == axis && Math.abs(position - plane) > 1.0E-4F) {
                    return false;
                }
                min[i] = Math.min(min[i], position);
                max[i] = Math.max(max[i], position);
            }
        }
        for (int i = 0; i < 3; i++) {
            if (i != axis && (min[i] > 1.0E-4F || max[i] < 1 - 1.0E-4F)) {
                return false;
            }
        }
        return true;
    }

    /** Whether no pixel of the quad's sprite is transparent, in any animation frame (cutout draws the rest solid). */
    private static boolean opaque(BakedQuad quad) {
        TextureAtlasSprite sprite = quad.getSprite();
        var contents = sprite.contents();
        int frames = (int) contents.getUniqueFrames().count();
        for (int frame = 0; frame < Math.max(1, frames); frame++) {
            for (int y = 0; y < contents.height(); y++) {
                for (int x = 0; x < contents.width(); x++) {
                    if (contents.isTransparent(frame, x, y)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
