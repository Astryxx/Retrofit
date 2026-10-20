package retrofit.forge.world;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The 1.20.1 item for a legacy armor piece (ItemArmor). 1.20.1 only wears and draws real
 * ArmorItems, so legacy armor is one, with the legacy piece's slot, armor points, durability and
 * enchantability. Its texture is the legacy one (getArmorTexture, from the mod's jar through the
 * asset pack), and onArmorTick runs while it's worn (see LegacyEvents).
 */
public class LegacyArmorItem extends ArmorItem implements LegacyStackItem {
    private static final ArmorItem.Type[] TYPES = {Type.HELMET, Type.CHESTPLATE, Type.LEGGINGS, Type.BOOTS};

    private final Object legacyItem;
    private final int tickFlags;
    private final Map<String, String> textures = new ConcurrentHashMap<>();
    private final String translationKey;

    /** @param armor see the legacy bridge's Interactions.armorInfo; @param info see {@link LegacyBridge#itemInfo} */
    public LegacyArmorItem(Object legacyItem, int[] info, int[] armor) {
        super(material(info, armor), TYPES[Math.max(0, Math.min(3, armor[0]))], new Properties());
        this.legacyItem = legacyItem;
        this.tickFlags = LegacyBridge.call("tickFlags", legacyItem);
        this.translationKey = LegacyBridge.itemTranslationKey(legacyItem, 0);
    }

    private static ArmorMaterial material(int[] info, int[] armor) {
        int durability = Math.max(1, info[1]);
        int defense = armor[1];
        int enchantability = armor[2];
        return new ArmorMaterial() {
            @Override
            public int getDurabilityForType(Type type) {
                return durability;
            }

            @Override
            public int getDefenseForType(Type type) {
                return defense;
            }

            @Override
            public int getEnchantmentValue() {
                return enchantability;
            }

            @Override
            public SoundEvent getEquipSound() {
                return SoundEvents.ARMOR_EQUIP_GENERIC;
            }

            @Override
            public Ingredient getRepairIngredient() {
                return Ingredient.EMPTY;
            }

            @Override
            public String getName() {
                return "retrofit_legacy";
            }

            @Override
            public float getToughness() {
                return 0;
            }

            @Override
            public float getKnockbackResistance() {
                return 0;
            }
        };
    }

    @Override
    public Object legacyItem() {
        return legacyItem;
    }

    /** Clients: pieces with a model of their own draw it (client.LegacyArmorModels). */
    @Override
    public void initializeClient(java.util.function.Consumer<net.minecraftforge.client.extensions.common.IClientItemExtensions> consumer) {
        consumer.accept(retrofit.forge.client.LegacyArmorModels.INSTANCE);
    }

    @Override
    public boolean hasSubtypes() {
        return false;
    }

    @Override
    public int meta(ItemStack stack) {
        return stack.getDamageValue();
    }

    @Override
    public ItemStack stackOf(int meta) {
        ItemStack stack = new ItemStack(this);
        if (meta > 0) {
            stack.setDamageValue(meta);
        }
        return stack;
    }

    @Override
    public int tickFlags() {
        return tickFlags;
    }

    @Override
    public String getDescriptionId(ItemStack stack) {
        return translationKey;
    }

    @Override
    public String getDescriptionId() {
        return translationKey;
    }

    /** The legacy texture, with its namespace and path made valid for 1.20.1 as the asset pack serves them. */
    @Override
    public String getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot, String type) {
        int legacySlot = switch (slot) {
            case HEAD -> 0;
            case CHEST -> 1;
            case LEGS -> 2;
            default -> 3;
        };
        String key = legacySlot + "/" + type;
        return textures.computeIfAbsent(key, k -> {
            try {
                Object legacy = LegacyStacks.toLegacy(stack);
                String texture = legacy == null ? null : LegacyBridge.call("armorTexture", legacy, legacySlot, type);
                if (texture == null) {
                    return null;
                }
                int colon = texture.indexOf(':');
                String namespace = colon < 0 ? "minecraft" : texture.substring(0, colon).toLowerCase(Locale.ROOT);
                String path = (colon < 0 ? texture : texture.substring(colon + 1)).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
                return namespace + ":" + path;
            } catch (RuntimeException e) {
                LegacyErrors.report(translationKey, "naming its armor texture", e);
                return null;
            }
        });
    }

    @Override
    public int getBurnTime(ItemStack stack, @javax.annotation.Nullable net.minecraft.world.item.crafting.RecipeType<?> recipeType) {
        return LegacyRecipes.burnTime(stack);
    }

    /** Legacy electric items (RF, IC2) as Forge Energy: 1.20.1 chargers and machines use them. */
    @Override
    public net.minecraftforge.common.capabilities.ICapabilityProvider initCapabilities(net.minecraft.world.item.ItemStack stack,
                                                                                       @javax.annotation.Nullable net.minecraft.nbt.CompoundTag nbt) {
        return LegacyItemEnergy.of(stack);
    }
}
