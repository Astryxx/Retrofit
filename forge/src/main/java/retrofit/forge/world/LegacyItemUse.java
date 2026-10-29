package retrofit.forge.world;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * The legacy behaviour of legacy items, shared by {@link LegacyItem} and {@link LegacyBlockItem}:
 * using them on blocks (which for item blocks is placing, through the legacy ItemBlock code, so
 * mods orient and initialise their blocks as they expect), using them in the air, using over
 * time (food, drinks, bows), mining and attacking. All of it runs on the player's mirror with
 * the held stack, and the mirror's changes are written back.
 */
final class LegacyItemUse {
    /** The same id 1.20.1 uses for a weapon's attack damage (Item.BASE_ATTACK_DAMAGE_UUID). */
    private static final UUID ATTACK_DAMAGE = UUID.fromString("CB3F55D3-645C-4F38-A497-9C13A33DB5CF");
    /** The same id 1.20.1 uses for a weapon's attack speed (Item.BASE_ATTACK_SPEED_UUID). */
    private static final UUID ATTACK_SPEED = UUID.fromString("FA233E1C-4180-4865-B01B-BCCE9785ACA3");
    private static final UseAnim[] ANIMATIONS = {UseAnim.NONE, UseAnim.EAT, UseAnim.DRINK, UseAnim.BLOCK, UseAnim.BOW};

    private LegacyItemUse() {
    }

    /**
     * 1.7.10 had one hand: legacy code uses "the held item". For the off hand, the two hands swap
     * while legacy code runs (it sees the off-hand item as held), then swap back with whatever
     * legacy code made of them.
     */
    private static <T> T inOffHand(Player player, java.util.function.Supplier<T> action) {
        ItemStack main = player.getMainHandItem();
        player.setItemInHand(InteractionHand.MAIN_HAND, player.getOffhandItem());
        player.setItemInHand(InteractionHand.OFF_HAND, main);
        try {
            return action.get();
        } finally {
            ItemStack used = player.getMainHandItem();
            player.setItemInHand(InteractionHand.MAIN_HAND, player.getOffhandItem());
            player.setItemInHand(InteractionHand.OFF_HAND, used);
        }
    }

    static InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (context.getHand() != InteractionHand.MAIN_HAND) {
            return inOffHand(player, () -> useOnHeld(context));
        }
        return useOnHeld(context);
    }

    private static InteractionResult useOnHeld(UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Vec3 at = context.getClickLocation();
        try {
            Object mirror = LegacyPlayers.mirror(player, true);
            boolean used = LegacyBridge.call("useOn", mirror, LegacyContent.legacyWorld(level), pos.getX(), pos.getY(), pos.getZ(),
                    context.getClickedFace().get3DDataValue(), (float) (at.x - pos.getX()), (float) (at.y - pos.getY()),
                    (float) (at.z - pos.getZ()));
            LegacyPlayers.writeBack(player, mirror);
            return used ? InteractionResult.sidedSuccess(level.isClientSide) : InteractionResult.PASS;
        } catch (RuntimeException e) {
            LegacyErrors.report(context.getItemInHand().getItem(), "being used on a block", e);
            return InteractionResult.FAIL;
        }
    }

    static InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        boolean[] start = new boolean[1];
        InteractionResultHolder<ItemStack> result = hand == InteractionHand.MAIN_HAND ? useHeld(level, player, start)
                : inOffHand(player, () -> useHeld(level, player, start));
        if (start[0]) { // used over time (bows, food): in the hand it is in
            player.startUsingItem(hand);
            return InteractionResultHolder.consume(player.getItemInHand(hand));
        }
        return new InteractionResultHolder<>(result.getResult(), player.getItemInHand(hand));
    }

    /** The held item's legacy right click; {@code start} set when it begins being used over time. */
    private static InteractionResultHolder<ItemStack> useHeld(Level level, Player player, boolean[] start) {
        ItemStack held = player.getMainHandItem();
        try {
            Object mirror = LegacyPlayers.mirror(player, true);
            LegacyBridge.call("rightClick", mirror, LegacyContent.legacyWorld(level));
            LegacyPlayers.writeBack(player, mirror);
            ItemStack now = player.getMainHandItem();
            start[0] = LegacyPlayers.useState(mirror)[0] != 0 && !now.isEmpty() && useDuration(now) > 0;
            return now == held ? InteractionResultHolder.pass(now) : InteractionResultHolder.sidedSuccess(now, level.isClientSide);
        } catch (RuntimeException e) {
            LegacyErrors.report(held.getItem(), "being used", e);
            return InteractionResultHolder.fail(held);
        }
    }

    /** Runs a phase of use over time with the item in use held where legacy code looks (see inOffHand). */
    private static <T> T inUsedHand(Player player, java.util.function.Supplier<T> action) {
        return player.isUsingItem() && player.getUsedItemHand() == InteractionHand.OFF_HAND ? inOffHand(player, action) : action.get();
    }

    /** {duration, animation} of a stack; {0, 0} if it has no legacy form. */
    private static int[] useInfo(ItemStack stack) {
        Object legacy = LegacyStacks.toLegacy(stack);
        if (legacy == null) {
            return new int[2];
        }
        try {
            return LegacyBridge.call("useInfo", legacy);
        } catch (RuntimeException e) {
            LegacyErrors.report(stack.getItem(), "reporting how it is used", e);
            return new int[2];
        }
    }

    static int useDuration(ItemStack stack) {
        return useInfo(stack)[0];
    }

    static UseAnim useAnimation(ItemStack stack) {
        int action = useInfo(stack)[1];
        return action >= 0 && action < ANIMATIONS.length ? ANIMATIONS[action] : UseAnim.NONE;
    }

    static void onUseTick(LivingEntity entity, ItemStack stack, int remaining) {
        if (entity instanceof Player player) {
            inUsedHand(player, () -> {
                try {
                    LegacyBridge.call("usingTick", LegacyPlayers.mirror(player, false), remaining);
                } catch (RuntimeException e) {
                    LegacyErrors.report(stack.getItem(), "being used over time", e);
                }
                return null;
            });
        }
    }

    static void releaseUsing(Level level, LivingEntity entity, ItemStack stack, int timeLeft) {
        if (entity instanceof Player player) {
            inUsedHand(player, () -> {
                try {
                    Object mirror = LegacyPlayers.mirror(player, true);
                    LegacyBridge.call("stoppedUsing", mirror, LegacyContent.legacyWorld(level), timeLeft);
                    LegacyPlayers.writeBack(player, mirror);
                } catch (RuntimeException e) {
                    LegacyErrors.report(stack.getItem(), "being released", e);
                }
                return null;
            });
        }
    }

    static ItemStack finishUsing(Level level, LivingEntity entity, ItemStack stack) {
        if (!(entity instanceof Player player)) {
            return stack;
        }
        InteractionHand hand = player.getUsedItemHand();
        inUsedHand(player, () -> {
            try {
                Object mirror = LegacyPlayers.mirror(player, true);
                LegacyBridge.call("finishUsing", mirror, LegacyContent.legacyWorld(level));
                LegacyPlayers.writeBack(player, mirror);
            } catch (RuntimeException e) {
                LegacyErrors.report(stack.getItem(), "finishing being used", e);
            }
            return null;
        });
        return player.getItemInHand(hand);
    }

    /** The held legacy item used on a 1.20.1 creature (legacy creatures take it in their own right click, see LegacyEntityCore.interact). */
    static InteractionResult useOnEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || target instanceof LegacyEntityCarrier) {
            return InteractionResult.PASS;
        }
        try {
            Object legacy = LegacyEntityCore.legacyOf(player.level(), target);
            if (legacy == null) {
                return InteractionResult.PASS;
            }
            Object mirror = LegacyPlayers.mirror(player, true);
            boolean used = LegacyBridge.call("useOnEntity", mirror, legacy);
            LegacyPlayers.writeBack(player, mirror);
            return used ? InteractionResult.SUCCESS : InteractionResult.PASS;
        } catch (RuntimeException e) {
            LegacyErrors.report(stack.getItem(), "being used on a creature", e);
            return InteractionResult.PASS;
        }
    }

    // ---- Mining and fighting ----

    /** Item.onEntitySwing, including weapons that create their attack when the swing starts. */
    static boolean swing(ItemStack stack, LivingEntity entity) {
        if (!(entity instanceof Player player) || player.getMainHandItem() != stack) return false;
        try {
            Object mirror = LegacyPlayers.mirror(player, true);
            boolean canceled = LegacyBridge.call("swingItem", mirror);
            LegacyPlayers.writeBack(player, mirror);
            return canceled;
        } catch (RuntimeException e) {
            LegacyErrors.report(stack.getItem(), "swinging", e);
            return false;
        }
    }

    /** A landed hit: the original item's hitEntity applies wear, poison, fire, and other effects. */
    static boolean hitEntity(ItemStack stack, LivingEntity target, Player player) {
        try {
            Object legacy = LegacyEntityCore.legacyOf(player.level(), target);
            if (legacy == null) {
                return false;
            }
            Object mirror = LegacyPlayers.mirror(player, true);
            LegacyBridge.call("hitEntity", mirror, legacy);
            LegacyPlayers.writeBack(player, mirror);
        } catch (RuntimeException e) {
            LegacyErrors.report(stack.getItem(), "hitting a creature", e);
        }
        return true;
    }

    /** The legacy block and metadata standing for a 1.20.1 state: {block, meta}. */
    private static Object[] legacyOf(BlockState state) {
        if (state.getBlock() instanceof LegacyBlock block) {
            return new Object[]{block.legacyBlock(), state.getValue(LegacyContent.META)};
        }
        LegacyBlockTable table = LegacyBlockTable.get();
        int combined = table.legacyOf(state);
        return new Object[]{table.legacyBlock(combined >> 4), combined & 15};
    }

    static float destroySpeed(ItemStack stack, BlockState state, float fallback) {
        Object legacy = LegacyStacks.toLegacy(stack);
        if (legacy == null) {
            return fallback;
        }
        try {
            Object[] block = legacyOf(state);
            return LegacyBridge.call("digSpeed", legacy, block[0], block[1]);
        } catch (RuntimeException e) {
            LegacyErrors.report(stack.getItem(), "mining", e);
            return fallback;
        }
    }

    /** Legacy canHarvestBlock, or the legacy harvest level for the tool class the block's tags ask for. */
    static boolean isCorrectTool(ItemStack stack, BlockState state) {
        Object legacy = LegacyStacks.toLegacy(stack);
        if (legacy == null) {
            return false;
        }
        try {
            if (LegacyBridge.<Boolean>call("canHarvest", legacy, legacyOf(state)[0])) {
                return true;
            }
            String toolClass = state.is(BlockTags.MINEABLE_WITH_PICKAXE) ? "pickaxe"
                    : state.is(BlockTags.MINEABLE_WITH_AXE) ? "axe"
                    : state.is(BlockTags.MINEABLE_WITH_SHOVEL) ? "shovel" : null;
            if (toolClass == null) {
                return false;
            }
            int needed = state.is(BlockTags.NEEDS_DIAMOND_TOOL) ? 3 : state.is(BlockTags.NEEDS_IRON_TOOL) ? 2
                    : state.is(BlockTags.NEEDS_STONE_TOOL) ? 1 : 0;
            return LegacyBridge.<Integer>call("harvestLevel", legacy, toolClass) >= needed;
        } catch (RuntimeException e) {
            LegacyErrors.report(stack.getItem(), "checking what it can harvest", e);
            return false;
        }
    }

    static void mineBlock(Level level, BlockState state, BlockPos pos, LivingEntity entity) {
        if (entity instanceof Player player) {
            try {
                Object mirror = LegacyPlayers.mirror(player, true);
                LegacyBridge.call("blockDestroyed", mirror, LegacyContent.legacyWorld(level), legacyOf(state)[0],
                        pos.getX(), pos.getY(), pos.getZ());
                LegacyPlayers.writeBack(player, mirror);
            } catch (RuntimeException e) {
                LegacyErrors.report(player.getMainHandItem().getItem(), "breaking a block", e);
            }
        }
    }

    /** Weapons: the legacy attack damage modifier, in the main hand, and the attack speed of their kind (LegacyGearAttributes). */
    static Multimap<Attribute, AttributeModifier> attributes(EquipmentSlot slot, ItemStack stack, Multimap<Attribute, AttributeModifier> fallback) {
        if (slot != EquipmentSlot.MAINHAND) {
            return fallback;
        }
        double damage = attackDamage(stack);
        // (any item may have an attack speed set by hand; weapons also get their kind's)
        double speed = LegacyGearAttributes.attackSpeed(stack.getItem(), Double.isNaN(damage) ? "" : weaponKind(stack));
        if (Double.isNaN(damage) && Double.isNaN(speed)) {
            return fallback;
        }
        ImmutableMultimap.Builder<Attribute, AttributeModifier> attributes = ImmutableMultimap.builder();
        if (!Double.isNaN(damage)) {
            attributes.put(Attributes.ATTACK_DAMAGE, new AttributeModifier(ATTACK_DAMAGE, "Legacy weapon modifier", damage, AttributeModifier.Operation.ADDITION));
        }
        if (!Double.isNaN(speed)) {
            attributes.put(Attributes.ATTACK_SPEED, new AttributeModifier(ATTACK_SPEED, "Legacy weapon modifier", speed, AttributeModifier.Operation.ADDITION));
        }
        return attributes.build();
    }

    private static String weaponKind(ItemStack stack) {
        Object legacy = LegacyStacks.toLegacy(stack);
        try {
            return legacy == null ? "" : LegacyBridge.call("weaponKind", legacy);
        } catch (RuntimeException e) {
            LegacyErrors.report(stack.getItem(), "telling what weapon it is", e);
            return "";
        }
    }

    /** NaN if the stack isn't a legacy weapon. */
    static double attackDamage(ItemStack stack) {
        Object legacy = LegacyStacks.toLegacy(stack);
        if (legacy == null) {
            return Double.NaN;
        }
        try {
            return LegacyBridge.call("attackDamage", legacy);
        } catch (RuntimeException e) {
            LegacyErrors.report(stack.getItem(), "reporting its attack damage", e);
            return Double.NaN;
        }
    }
}
