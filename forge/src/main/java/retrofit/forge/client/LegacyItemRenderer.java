package retrofit.forge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import retrofit.forge.world.LegacyBridge;
import retrofit.forge.world.LegacyStackItem;
import retrofit.forge.world.LegacyBlock;
import retrofit.forge.world.LegacyBlockItem;

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
        // the host's pose so far: the poses below are 1.7.10's, and their normals are worked out (see exactNormals)
        org.joml.Matrix4f hostPose = new org.joml.Matrix4f(pose.last().pose());
        org.joml.Matrix3f hostNormal = new org.joml.Matrix3f(pose.last().normal());
        synchronized (LegacyBridge.renderLock()) {
            try {
                boolean custom = LegacyBridge.hasItemRenderer(item.legacyItem(), meta, type);
                Object block = !custom && stack.getItem() instanceof LegacyBlockItem blockItem ? ((LegacyBlock) blockItem.getBlock()).legacyBlock() : null;
                if (custom || block != null) {
                    int helpers = helpers(item, meta, type);
                    boolean left = context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
                    if (type.equals("INVENTORY")) {
                        // 1.7.10's GUI: 16 pixels from the slot's top left, y down (2D renderers draw there).
                        pose.translate(0, 1, 0);
                        pose.scale(1 / 16f, -1 / 16f, 1 / 16f);
                        if (!custom || (helpers & INVENTORY_BLOCK) != 0) {
                            // ForgeHooksClient.renderInventoryItem with INVENTORY_BLOCK, and RenderItem for 3D blocks
                            pose.translate(-2, 3, -3);
                            pose.scale(10, 10, 10);
                            pose.translate(1, 0.5f, 1);
                            pose.scale(1, 1, -1);
                            pose.mulPose(Axis.XP.rotationDegrees(210));
                            pose.mulPose(Axis.YP.rotationDegrees(45));
                            pose.mulPose(Axis.YP.rotationDegrees(-90)); // RenderItem and renderInventoryItem turn the block a quarter before drawing
                        }
                    } else if (type.equals("EQUIPPED_FIRST_PERSON")) {
                        // ItemInHandRenderer has made 1.7.10's first translation; then ItemRenderer.renderItemInFirstPerson's
                        pose.translate(0.5, 0.5, 0.5);
                        pose.mulPose(Axis.YP.rotationDegrees(left ? -45 : 45));
                        pose.scale(0.4f, 0.4f, 0.4f);
                        equippedBlock(pose, custom, helpers);
                    } else if (type.equals("EQUIPPED") && context != ItemDisplayContext.HEAD) {
                        // back to the arm (ItemInHandLayer's own transform undone), then RenderPlayer.renderEquippedItems'
                        pose.translate(0.5, 0.5, 0.5);
                        pose.translate(left ? 1 / 16f : -1 / 16f, -0.125f, 0.625f);
                        pose.mulPose(Axis.YP.rotationDegrees(-180));
                        pose.mulPose(Axis.XP.rotationDegrees(90));
                        pose.translate(-0.0625f, 0.4375f, 0.0625f);
                        // RenderPlayer.renderEquippedItems: the block pose for renderers asking for BLOCK_3D
                        // (whatever the item) and for item blocks drawn in 3D
                        if (!custom || (helpers & BLOCK_3D) != 0) {
                            pose.translate(0, 0.1875f, -0.3125f);
                            pose.mulPose(Axis.XP.rotationDegrees(20));
                            pose.mulPose(Axis.YP.rotationDegrees(45));
                            pose.scale(-0.375f, -0.375f, 0.375f);
                        } else if ((helpers & FULL_3D) != 0) {
                            if ((helpers & ROTATE_AROUND) != 0) {
                                pose.mulPose(Axis.ZP.rotationDegrees(180));
                                pose.translate(0, -0.125f, 0);
                            }
                            pose.translate(0, 0.1875f, 0);
                            pose.scale(0.625f, -0.625f, 0.625f);
                            pose.mulPose(Axis.XP.rotationDegrees(-100));
                            pose.mulPose(Axis.YP.rotationDegrees(45));
                        } else {
                            pose.translate(0.25f, 0.1875f, -0.1875f);
                            pose.scale(0.375f, 0.375f, 0.375f);
                            pose.mulPose(Axis.ZP.rotationDegrees(60));
                            pose.mulPose(Axis.XP.rotationDegrees(-90));
                            pose.mulPose(Axis.ZP.rotationDegrees(20));
                        }
                        equippedBlock(pose, custom, helpers);
                    } else if (type.equals("ENTITY") || !custom) {
                        pose.translate(0.5, 0.5, 0.5);
                    }
                    if (custom) {
                        LegacyBridge.call("itemLight", type.equals("INVENTORY") ? net.minecraft.client.renderer.LightTexture.FULL_BRIGHT : light);
                    }
                    Object legacy = custom && stack.hasTag() ? legacyStack(stack, item, meta) : null;
                    int vertices = legacy != null ? LegacyBridge.call("renderItemStack", legacy, type)
                            : custom ? LegacyBridge.renderItem(item.legacyItem(), meta, type)
                            : LegacyBridge.call("renderBlockItem", block, blockMeta(item, meta), meta);
                    if (DEBUG_ITEM != null && key.contains(DEBUG_ITEM) && type.equals("INVENTORY")) {
                        debugDump(key, vertices);
                    }
                    exactNormals(pose.last(), hostPose, hostNormal);
                    LegacyCapture.draw(pose.last(), buffers, vertices, light, overlay, custom && !type.equals("INVENTORY")); // block items: light of the isolated world
                }
            } catch (Throwable t) {
                broken.add(key);
                LOGGER.error("Legacy item renderer for {} ({}) failed and has been disabled", key, type, t);
            }
        }
        pose.popPose();
    }

    /**
     * The normal matrix for the 1.7.10 poses applied since the host's ({@code hostPose}), worked out
     * exactly: the inverse transpose of those transforms, at unit length, after the host's own normals.
     * 1.7.10's poses mirror (its GUI's y points down, and RenderItem flips z), and PoseStack.scale
     * only approximates the normals of mirroring scales: legacy items came out dark.
     */
    private static void exactNormals(PoseStack.Pose pose, org.joml.Matrix4f hostPose, org.joml.Matrix3f hostNormal) {
        org.joml.Matrix3f legacy = new org.joml.Matrix3f(new org.joml.Matrix4f(hostPose).invert().mul(pose.pose())).invert().transpose();
        float length = legacy.getScale(new org.joml.Vector3f()).x; // the poses scale evenly: every column has this length
        pose.normal().set(new org.joml.Matrix3f(hostNormal).mul(legacy.scale(1 / length)));
    }

    /** -Dretrofit.debugItem=<part of an item's id>: logs what the legacy renderer drew for such items in GUIs, once each. */
    private static final String DEBUG_ITEM = System.getProperty("retrofit.debugItem");
    private static final Set<String> DUMPED = Collections.synchronizedSet(new HashSet<>());

    private static void debugDump(String key, int vertices) throws Throwable {
        if (!DUMPED.add(key)) {
            return;
        }
        float[] d = LegacyCapture.data();
        int[] states = LegacyCapture.states();
        StringBuilder out = new StringBuilder();
        for (int v = 0; v < Math.min(vertices, 24); v += 4) {
            int o = v * LegacyCapture.FLOATS_PER_VERTEX;
            out.append(String.format(" [n %.2f %.2f %.2f rgba %.2f %.2f %.2f %.2f state %x]", d[o + 5], d[o + 6], d[o + 7],
                    d[o + 8], d[o + 9], d[o + 10], d[o + 11], states[v] >>> 16));
        }
        LOGGER.info("[Debug] item {}: {} vertices{}", key, vertices, out);
    }

    // IItemRenderer.ItemRendererHelper bits, and the item's own (see the legacy bridge's LegacyRendering.itemRendererHelpers)
    private static final int EQUIPPED_BLOCK = 1 << 2, BLOCK_3D = 1 << 3, INVENTORY_BLOCK = 1 << 4, FULL_3D = 1 << 30, ROTATE_AROUND = 1 << 29;

    /**
     * ForgeHooksClient.renderEquippedItem, for renderers held in a hand: those asking for
     * EQUIPPED_BLOCK draw a block from 0 to 1, the others get the pose of a flat held item. Block
     * items (not custom) are centred already.
     */
    private static void equippedBlock(PoseStack pose, boolean custom, int helpers) {
        if (!custom) {
            return;
        }
        if ((helpers & EQUIPPED_BLOCK) != 0) {
            pose.translate(-0.5, -0.5, -0.5);
        } else {
            pose.translate(0, -0.3, 0);
            pose.scale(1.5f, 1.5f, 1.5f);
            pose.mulPose(Axis.YP.rotationDegrees(50));
            pose.mulPose(Axis.ZP.rotationDegrees(335));
            pose.translate(-0.9375, -0.0625, 0);
        }
    }
    private static final java.util.Map<String, Integer> HELPERS = new java.util.concurrent.ConcurrentHashMap<>();

    private static int helpers(LegacyStackItem item, int meta, String type) {
        return HELPERS.computeIfAbsent(System.identityHashCode(item) + "/" + meta + "/" + type,
                k -> LegacyBridge.<Integer>call("itemRendererHelpers", item.legacyItem(), meta, type));
    }

    private record StackKey(Object item, int meta, int tag) {
    }

    private static final int MAX_CACHED = 1024;
    private static final java.util.Map<StackKey, Object> LEGACY_STACKS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The legacy form of a stack with NBT, kept per item, damage and tag (converting its NBT each frame would cost). */
    private static Object legacyStack(ItemStack stack, LegacyStackItem item, int meta) {
        StackKey key = new StackKey(item, meta, stack.getTag().hashCode());
        Object known = LEGACY_STACKS.get(key);
        if (known == null) {
            known = retrofit.forge.world.LegacyStacks.toLegacy(stack);
            if (known == null) {
                return null; // drawn as its item and damage
            }
            if (LEGACY_STACKS.size() > MAX_CACHED) {
                LEGACY_STACKS.clear();
            }
            LEGACY_STACKS.put(key, known);
        }
        return known;
    }

    private static int blockMeta(LegacyStackItem item, int meta) {
        try {
            return LegacyBridge.<Integer>call("itemBlockMeta", item.legacyItem(), meta) & 15;
        } catch (RuntimeException e) {
            return meta & 15;
        }
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
