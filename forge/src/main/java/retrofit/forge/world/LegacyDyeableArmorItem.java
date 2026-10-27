package retrofit.forge.world;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.DyeableLeatherItem;
import net.minecraft.world.item.ItemStack;

/**
 * A legacy armor piece 1.7.10 drew in a colour (leather's material, or its own getColor): 1.20.1 tints
 * DyeableLeatherItems, and both versions keep the dye in the stack's display.color, so dyeing (in crafting,
 * in a cauldron) and the mod's own colours both show. Only these pieces are dyeable: the crafting dye
 * recipe takes any DyeableLeatherItem.
 */
public final class LegacyDyeableArmorItem extends LegacyArmorItem implements DyeableLeatherItem {
    /** No overlay of the mod's own: nothing is drawn over the tinted base (1.20.1 always draws one for dyeable armor). */
    private static final String NO_OVERLAY = "retrofit:textures/models/armor/none.png";

    public LegacyDyeableArmorItem(Object legacyItem, int[] info, int[] armor) {
        super(legacyItem, info, armor);
    }

    /** The legacy colour (ItemArmor.getColor); -1, which 1.7.10 drew untinted, as white. */
    @Override
    public int getColor(ItemStack stack) {
        try {
            Object legacy = LegacyStacks.toLegacy(stack);
            int colour = legacy == null ? -1 : LegacyBridge.<Integer>call("armorColor", legacy);
            return colour == -1 ? 0xFFFFFF : colour & 0xFFFFFF;
        } catch (RuntimeException e) {
            LegacyErrors.report(getDescriptionId(), "giving its armor colour", e);
            return 0xFFFFFF;
        }
    }

    @Override
    public String getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot, String type) {
        String texture = super.getArmorTexture(stack, entity, slot, type);
        return texture == null && "overlay".equals(type) ? NO_OVERLAY : texture;
    }
}
