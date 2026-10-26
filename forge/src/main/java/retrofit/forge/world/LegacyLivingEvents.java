package retrofit.forge.world;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.ArrowLooseEvent;
import net.minecraftforge.event.entity.player.ArrowNockEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import retrofit.forge.RetrofitMod;

/**
 * Forge's living events of players, passed to legacy mods (see the legacy bridge's LivingEvents)
 * on the player's mirror, with the results applied. Legacy creatures fire them themselves.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID)
public final class LegacyLivingEvents {
    private static final int ATTACK = 1;
    private static final int HURT = 2;
    private static final int DEATH = 4;
    private static final int FALL = 8;
    private static final int JUMP = 16;
    private static final int HEAL = 32;
    private static final int BREAK_SPEED = 64;
    private static final int ARROW_NOCK = 128;
    private static final int ARROW_LOOSE = 256;
    private static Integer wanted;
    private static final boolean DEBUG_INTERACTIONS = Boolean.getBoolean("retrofit.debugInteractions");
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private LegacyLivingEvents() {
    }

    private static boolean wants(int event) {
        if (RetrofitMod.runtime() == null) {
            return false;
        }
        if (wanted == null) {
            try {
                wanted = LegacyBridge.<Integer>call("livingWanted");
            } catch (RuntimeException e) {
                wanted = 0;
            }
        }
        return (wanted & event) != 0;
    }

    /** The legacy attacker: the mirror of a player, a legacy creature, or nothing (1.20.1 creatures have no legacy form). */
    private static Object attacker(DamageSource source) {
        Entity entity = source.getEntity();
        if (entity instanceof Player player) {
            return LegacyPlayers.mirror(player, false);
        }
        if (entity instanceof LegacyEntityCarrier carrier) {
            return carrier.core().legacy();
        }
        return null;
    }

    private static int flags(DamageSource source) {
        return (source.is(DamageTypeTags.IS_PROJECTILE) ? 1 : 0) | (source.is(DamageTypeTags.IS_FIRE) ? 2 : 0)
                | (source.is(DamageTypeTags.WITCH_RESISTANT_TO) ? 4 : 0) | (source.is(DamageTypeTags.IS_EXPLOSION) ? 8 : 0)
                | (source.is(DamageTypeTags.BYPASSES_ARMOR) ? 16 : 0);
    }

    private static void report(String event, RuntimeException e) {
        LegacyErrors.report("event handler", event, e);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onAttack(LivingAttackEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && wants(ATTACK)) {
            try {
                Object mirror = LegacyPlayers.mirror(player, false);
                if (LegacyBridge.<Boolean>call("livingAttack", mirror, event.getSource().getMsgId(), attacker(event.getSource()),
                        flags(event.getSource()), event.getAmount())) {
                    event.setCanceled(true);
                }
            } catch (RuntimeException e) {
                report("LivingAttackEvent", e);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onHurt(LivingHurtEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && wants(HURT)) {
            try {
                Object mirror = LegacyPlayers.mirror(player, false);
                float amount = LegacyBridge.call("livingHurt", mirror, event.getSource().getMsgId(), attacker(event.getSource()),
                        flags(event.getSource()), event.getAmount());
                if (Float.isNaN(amount)) {
                    event.setCanceled(true);
                } else {
                    event.setAmount(amount);
                }
                LegacyPlayers.writeBack(player, mirror); // shields and the like may have changed health or effects
            } catch (RuntimeException e) {
                report("LivingHurtEvent", e);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && wants(DEATH)) {
            try {
                Object mirror = LegacyPlayers.mirror(player, false);
                if (LegacyBridge.<Boolean>call("livingDeath", mirror, event.getSource().getMsgId(), attacker(event.getSource()),
                        flags(event.getSource()))) {
                    event.setCanceled(true);
                    LegacyPlayers.writeBack(player, mirror); // a mod that saves the player usually heals it
                    if (player.getHealth() <= 0) {
                        player.setHealth(1);
                    }
                }
            } catch (RuntimeException e) {
                report("LivingDeathEvent", e);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onFall(LivingFallEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && wants(FALL)) {
            try {
                float distance = LegacyBridge.call("livingFall", LegacyPlayers.mirror(player, false), event.getDistance());
                if (Float.isNaN(distance)) {
                    event.setCanceled(true);
                } else {
                    event.setDistance(distance);
                }
            } catch (RuntimeException e) {
                report("LivingFallEvent", e);
            }
        }
    }

    /**
     * Both sides, as the bow is drawn on both: a mod that draws it itself (for arrows of its own, with
     * no vanilla ones) has the player use the bow.
     */
    @SubscribeEvent
    public static void onArrowNock(ArrowNockEvent event) {
        if (event.getAction() != null || !wants(ARROW_NOCK)) {
            return;
        }
        try {
            Object bow = LegacyStacks.toLegacy(event.getBow());
            boolean drawn = bow != null && LegacyBridge.<Boolean>call("arrowNock", LegacyPlayers.mirror(event.getEntity(), true), bow);
            if (DEBUG_INTERACTIONS) {
                LOGGER.info("[Interactions] arrow nock on the {}: drawn by a legacy mod {}", event.getLevel().isClientSide ? "client" : "server", drawn);
            }
            if (drawn) {
                event.getEntity().startUsingItem(event.getHand());
                event.setAction(net.minecraft.world.InteractionResultHolder.consume(event.getBow()));
            }
        } catch (RuntimeException e) {
            report("ArrowNockEvent", e);
        }
    }

    /** A mod that fires the bow itself (spawning its own arrow, using up its own ammunition) stops the vanilla arrow. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onArrowLoose(ArrowLooseEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !wants(ARROW_LOOSE)) {
            return;
        }
        try {
            Object bow = LegacyStacks.toLegacy(event.getBow());
            if (bow == null) {
                return;
            }
            Object mirror = LegacyPlayers.mirror(player, true);
            int charge = LegacyBridge.call("arrowLoose", mirror, bow, event.getCharge());
            if (DEBUG_INTERACTIONS) {
                LOGGER.info("[Interactions] arrow loose with charge {}: {}", event.getCharge(), charge < 0 ? "fired by a legacy mod" : "charge " + charge);
            }
            if (charge < 0) {
                event.setCanceled(true);
            } else {
                event.setCharge(charge);
            }
            LegacyPlayers.writeBack(player, mirror); // the arrows it used up
        } catch (RuntimeException e) {
            report("ArrowLooseEvent", e);
        }
    }

    @SubscribeEvent
    public static void onJump(LivingEvent.LivingJumpEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && wants(JUMP)) {
            try {
                Object mirror = LegacyPlayers.mirror(player, false);
                LegacyBridge.call("livingJump", mirror);
                LegacyPlayers.writeBack(player, mirror);
            } catch (RuntimeException e) {
                report("LivingJumpEvent", e);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onHeal(LivingHealEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && wants(HEAL)) {
            try {
                float amount = LegacyBridge.call("livingHeal", LegacyPlayers.mirror(player, false), event.getAmount());
                if (Float.isNaN(amount)) {
                    event.setCanceled(true);
                } else {
                    event.setAmount(amount);
                }
            } catch (RuntimeException e) {
                report("LivingHealEvent", e);
            }
        }
    }

    /** Both sides: clients predict how fast a block breaks. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (!wants(BREAK_SPEED) || event.getPosition().isEmpty()) {
            return;
        }
        try {
            var pos = event.getPosition().get();
            Player player = event.getEntity();
            Object block = LegacyBlockTable.get().legacyBlock(LegacyBlockTable.get().legacyOf(event.getState()) >> 4);
            int meta = event.getState().getBlock() instanceof LegacyBlock ? event.getState().getValue(LegacyContent.META)
                    : LegacyBlockTable.get().legacyOf(event.getState()) & 15;
            if (event.getState().getBlock() instanceof LegacyBlock legacy) {
                block = legacy.legacyBlock();
            }
            if (block == null) {
                return;
            }
            float speed = LegacyBridge.call("livingBreakSpeed", LegacyPlayers.mirror(player, false), block, meta, event.getNewSpeed(),
                    pos.getX(), pos.getY(), pos.getZ());
            if (Float.isNaN(speed)) {
                event.setCanceled(true);
            } else {
                event.setNewSpeed(speed);
            }
        } catch (RuntimeException e) {
            report("PlayerEvent.BreakSpeed", e);
        }
    }
}
