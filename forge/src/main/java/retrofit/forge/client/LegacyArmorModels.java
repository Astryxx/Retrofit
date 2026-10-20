package retrofit.forge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import retrofit.forge.RetrofitMod;
import retrofit.forge.world.LegacyBridge;
import retrofit.forge.world.LegacyEntityCarrier;
import retrofit.forge.world.LegacyPlayers;
import retrofit.forge.world.LegacyStacks;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Legacy armor pieces with a model of their own (Item.getArmorModel: hero suits, oxygen gear,
 * robes, hats) on 1.20.1 wearers: the piece's model is drawn through the GL emulation where
 * 1.20.1 would draw its standard armor model, posed by the wearer's walk, age and head. Pieces
 * that turn out to have none are remembered and drawn as 1.20.1 armor with their texture.
 */
public final class LegacyArmorModels implements IClientItemExtensions {
    public static final LegacyArmorModels INSTANCE = new LegacyArmorModels();

    private final Set<Item> plain = ConcurrentHashMap.newKeySet();
    private volatile boolean failed;
    /** The local player's legacy mirror: the wearer legacy code sees for 1.20.1 entities without a legacy copy. */
    private Object localMirror;

    private LegacyArmorModels() {
    }

    @Override
    public Model getGenericArmorModel(LivingEntity entity, ItemStack stack, EquipmentSlot slot, HumanoidModel<?> original) {
        if (failed || RetrofitMod.runtime() == null || plain.contains(stack.getItem())) {
            return original;
        }
        return new Piece(entity, stack, slot, original);
    }

    private Object wearer(LivingEntity entity) {
        if (entity instanceof LegacyEntityCarrier carrier && carrier.clientLegacy() != null) {
            return carrier.clientLegacy();
        }
        if (entity == Minecraft.getInstance().player || localMirror == null) {
            localMirror = LegacyPlayers.mirror(Minecraft.getInstance().player, false);
        }
        return localMirror;
    }

    /** One piece on one wearer, for one frame. */
    private final class Piece extends Model {
        private final LivingEntity entity;
        private final ItemStack stack;
        private final EquipmentSlot slot;
        private final HumanoidModel<?> original;

        Piece(LivingEntity entity, ItemStack stack, EquipmentSlot slot, HumanoidModel<?> original) {
            super(RenderType::armorCutoutNoCull);
            this.entity = entity;
            this.stack = stack;
            this.slot = slot;
            this.original = original;
        }

        @Override
        public void renderToBuffer(PoseStack pose, VertexConsumer consumer, int light, int overlay, float red, float green, float blue, float alpha) {
            Object legacyStack = LegacyStacks.toLegacy(stack);
            if (legacyStack == null || Minecraft.getInstance().player == null) {
                original.renderToBuffer(pose, consumer, light, overlay, red, green, blue, alpha);
                return;
            }
            float partialTick = Minecraft.getInstance().getFrameTime();
            float bodyYaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
            float headYaw = Mth.rotLerp(partialTick, entity.yHeadRotO, entity.yHeadRot) - bodyYaw;
            float pitch = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());
            int flags = (entity.isCrouching() ? 1 : 0) | (entity.isPassenger() ? 2 : 0) | (entity.isBaby() ? 4 : 0);
            int legacySlot = switch (slot) {
                case HEAD -> 0;
                case CHEST -> 1;
                case LEGS -> 2;
                default -> 3;
            };
            try {
                synchronized (LegacyBridge.renderLock()) {
                    int vertices = LegacyBridge.call("renderArmorModel", legacyStack, wearer(entity), legacySlot,
                            entity.walkAnimation.position(partialTick), entity.walkAnimation.speed(partialTick), entity.tickCount + partialTick,
                            headYaw, pitch, partialTick, flags);
                    if (vertices < 0) {
                        plain.add(stack.getItem());
                        original.renderToBuffer(pose, consumer, light, overlay, red, green, blue, alpha);
                        return;
                    }
                    LegacyCapture.draw(pose.last(), Minecraft.getInstance().renderBuffers().bufferSource(), vertices, light, overlay);
                }
            } catch (Throwable e) {
                failed = true; // one report; then legacy armor draws as 1.20.1 armor
                LegacyBridge.reportClientFailure("armor model of " + stack.getItem().getDescriptionId(),
                        e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e));
                original.renderToBuffer(pose, consumer, light, overlay, red, green, blue, alpha);
            }
        }
    }
}
