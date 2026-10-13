package retrofit.forge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import retrofit.forge.world.LegacyBridge;
import retrofit.forge.world.LegacyStackItem;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Draws legacy items that have a Forge IItemRenderer (the asset pack gives them a
 * "builtin/entity" model, which routes them here): the legacy renderer runs through the GL
 * emulation for the matching 1.7.10 render type, and the capture is drawn with 1.20.1 render
 * types. 1.7.10 drew INVENTORY and ENTITY renders centred on the origin and EQUIPPED ones in
 * the 0..1 block space, which is what 1.20.1's item transforms expect.
 */
public final class LegacyItemRenderer extends BlockEntityWithoutLevelRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static LegacyItemRenderer instance;
    private final Set<String> broken = Collections.synchronizedSet(new HashSet<>());

    private LegacyItemRenderer(Minecraft minecraft) {
        super(minecraft.getBlockEntityRenderDispatcher(), minecraft.getEntityModels());
    }

    public static LegacyItemRenderer get() {
        if (instance == null) {
            instance = new LegacyItemRenderer(Minecraft.getInstance());
        }
        return instance;
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (!(stack.getItem() instanceof LegacyStackItem item) || item.legacyItem() == null) {
            return;
        }
        String type = renderType(context);
        int meta = item.meta(stack);
        String key = stack.getItem() + "/" + meta + "/" + type;
        if (broken.contains(key)) {
            return;
        }
        pose.pushPose();
        if (type.equals("INVENTORY") || type.equals("ENTITY")) {
            pose.translate(0.5, 0.5, 0.5);
        }
        synchronized (LegacyBridge.renderLock()) {
            try {
                if (LegacyBridge.hasItemRenderer(item.legacyItem(), meta, type)) {
                    int vertices = LegacyBridge.renderItem(item.legacyItem(), meta, type);
                    LegacyCapture.draw(pose.last(), buffers, vertices, light, overlay);
                }
            } catch (Throwable t) {
                broken.add(key);
                LOGGER.error("Legacy item renderer for {} ({}) failed and has been disabled", key, type, t);
            }
        }
        pose.popPose();
    }

    private static String renderType(ItemDisplayContext context) {
        return switch (context) {
            case GUI -> "INVENTORY";
            case FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND -> "EQUIPPED_FIRST_PERSON";
            case THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND, HEAD -> "EQUIPPED";
            default -> "ENTITY";
        };
    }
}
