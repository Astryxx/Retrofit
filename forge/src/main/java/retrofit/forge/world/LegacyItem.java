package retrofit.forge.world;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import com.google.common.collect.Multimap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The 1.20.1 item standing in for one legacy mod item. 1.7.10 items used the damage value for
 * subtypes; for items with subtypes that value lives in the stack's {@value #META_TAG} tag (for
 * damageable items it is the ordinary 1.20.1 damage).
 */
public class LegacyItem extends Item implements LegacyStackItem {
    /**
     * Client hook shared by legacy items and block items: a custom renderer for those whose
     * model is "builtin/entity" (the asset pack does that when a Forge IItemRenderer exists).
     * Only invoked on the client, so the client class is only loaded there.
     */
    static final Supplier<IClientItemExtensions> CLIENT_EXTENSIONS = () -> new IClientItemExtensions() {
        @Override
        public BlockEntityWithoutLevelRenderer getCustomRenderer() {
            return retrofit.forge.client.LegacyItemRenderer.get();
        }
    };

    public static final String META_TAG = "LegacyMeta";

    private final Object legacyItem;
    private final boolean hasSubtypes;
    private final int tickFlags;
    private final Map<Integer, String> translationKeys = new ConcurrentHashMap<>();
    private final Map<Integer, Multimap<Attribute, AttributeModifier>> attributes = new ConcurrentHashMap<>();

    /** @param info see {@link LegacyBridge#itemInfo} */
    public LegacyItem(Object legacyItem, int[] info) {
        super(properties(info));
        this.legacyItem = legacyItem;
        this.hasSubtypes = info[2] != 0;
        this.tickFlags = LegacyBridge.call("tickFlags", legacyItem);
    }

    @Override
    public int tickFlags() {
        return tickFlags;
    }

    private static Item.Properties properties(int[] info) {
        Item.Properties properties = new Item.Properties();
        int maxDamage = info[1];
        boolean hasSubtypes = info[2] != 0;
        if (maxDamage > 0 && !hasSubtypes) {
            properties.durability(maxDamage); // implies a stack size of 1, as in 1.7.10
        } else {
            properties.stacksTo(Math.max(1, Math.min(64, info[0])));
        }
        return properties;
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(CLIENT_EXTENSIONS.get());
    }

    @Override
    public Object legacyItem() {
        return legacyItem;
    }

    @Override
    public boolean hasSubtypes() {
        return hasSubtypes;
    }

    /** The legacy damage value of a stack: its subtype, or its damage for damageable items. */
    @Override
    public int meta(ItemStack stack) {
        if (!hasSubtypes) {
            return stack.getDamageValue();
        }
        CompoundTag tag = stack.getTag();
        return tag == null ? 0 : tag.getInt(META_TAG);
    }

    @Override
    public ItemStack stackOf(int meta) {
        ItemStack stack = new ItemStack(this);
        if (hasSubtypes && meta != 0) {
            stack.getOrCreateTag().putInt(META_TAG, meta);
        }
        return stack;
    }

    /** Legacy items name each subtype separately (getUnlocalizedName(ItemStack)). */
    @Override
    public String getDescriptionId(ItemStack stack) {
        int meta = hasSubtypes ? meta(stack) : 0;
        return translationKeys.computeIfAbsent(meta, m -> LegacyBridge.itemTranslationKey(legacyItem, m));
    }

    /** Recent display names, by subtype and tag (tooltips ask every frame). */
    private final java.util.Map<Object, net.minecraft.network.chat.Component> names = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<Object, net.minecraft.network.chat.Component>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Object, net.minecraft.network.chat.Component> eldest) {
                    return size() > 512;
                }
            });

    /** The mod's own name for the stack (see the legacy bridge's itemDisplayName); the translated key if that fails. */
    @Override
    public net.minecraft.network.chat.Component getName(ItemStack stack) {
        Object key = java.util.Arrays.asList(stack.getTag() == null ? null : stack.getTag().toString(), stack.getDamageValue());
        net.minecraft.network.chat.Component cached = names.get(key);
        if (cached != null) {
            return cached;
        }
        net.minecraft.network.chat.Component name;
        try {
            Object legacy = LegacyStacks.toLegacy(stack);
            String text = legacy == null ? null : LegacyBridge.call("itemDisplayName", legacy);
            name = text == null || text.isEmpty() || text.equals(getDescriptionId(stack))
                    ? super.getName(stack) : net.minecraft.network.chat.Component.literal(text);
        } catch (RuntimeException e) {
            name = super.getName(stack);
        }
        names.put(key, name);
        return name;
    }

    // ---- Behaviour (see LegacyItemUse) ----

    @Override
    public InteractionResult useOn(UseOnContext context) {
        return LegacyItemUse.useOn(context);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return LegacyItemUse.use(level, player, hand);
    }

    @Override
    public int getUseDuration(ItemStack stack) {
        return LegacyItemUse.useDuration(stack);
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return LegacyItemUse.useAnimation(stack);
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        LegacyItemUse.onUseTick(entity, stack, remaining);
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        LegacyItemUse.releaseUsing(level, entity, stack, timeLeft);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        return LegacyItemUse.finishUsing(level, entity, stack);
    }

    @Override
    public float getDestroySpeed(ItemStack stack, BlockState state) {
        return LegacyItemUse.destroySpeed(stack, state, super.getDestroySpeed(stack, state));
    }

    @Override
    public boolean isCorrectToolForDrops(ItemStack stack, BlockState state) {
        return LegacyItemUse.isCorrectTool(stack, state);
    }

    @Override
    public boolean mineBlock(ItemStack stack, Level level, BlockState state, BlockPos pos, LivingEntity entity) {
        LegacyItemUse.mineBlock(level, state, pos, entity);
        return true;
    }

    /** Legacy weapon damage; cached per subtype (it comes from the item, not the stack, for nearly every mod). */
    @Override
    public Multimap<Attribute, AttributeModifier> getAttributeModifiers(EquipmentSlot slot, ItemStack stack) {
        Multimap<Attribute, AttributeModifier> fallback = super.getAttributeModifiers(slot, stack);
        if (slot != EquipmentSlot.MAINHAND) {
            return fallback;
        }
        return attributes.computeIfAbsent(hasSubtypes ? meta(stack) : 0, m -> LegacyItemUse.attributes(slot, stack, fallback));
    }

    /** Weapons wear as they did in 1.7.10 (legacy hitEntity needs a legacy entity, which the target isn't). */
    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        if (stack.isDamageableItem() && !Double.isNaN(LegacyItemUse.attackDamage(stack))) {
            stack.hurtAndBreak(1, attacker, entity -> entity.broadcastBreakEvent(EquipmentSlot.MAINHAND));
        }
        return true;
    }

    /** Legacy fuel values (the legacy furnace rules and mods' fuel handlers). */
    @Override
    public int getBurnTime(ItemStack stack, @javax.annotation.Nullable net.minecraft.world.item.crafting.RecipeType<?> type) {
        return LegacyRecipes.burnTime(stack);
    }

    /** Legacy electric items (RF, IC2) as Forge Energy: 1.20.1 chargers and machines use them. */
    @Override
    public net.minecraftforge.common.capabilities.ICapabilityProvider initCapabilities(net.minecraft.world.item.ItemStack stack,
                                                                                       @javax.annotation.Nullable net.minecraft.nbt.CompoundTag nbt) {
        return LegacyItemEnergy.of(stack);
    }
}
