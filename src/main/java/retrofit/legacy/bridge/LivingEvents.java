package retrofit.legacy.bridge;

import net.minecraft.block.Block;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.DamageSource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * Forge's living events for players, fired on the player's mirror when the host player is
 * attacked, hurt, killed, falls, jumps or heals, and when it breaks blocks (BreakSpeed). Mods hook
 * these for armour, shields, buffs, curses and transformations. Results go back to the host:
 * cancelled, a new amount, a new fall distance or speed. Legacy creatures fire them themselves.
 */
public final class LivingEvents {
    public static final int PROJECTILE = 1;
    public static final int FIRE = 2;
    public static final int MAGIC = 4;
    public static final int EXPLOSION = 8;
    public static final int BYPASSES_ARMOR = 16;

    private static final Map<String, DamageSource> STATIC_SOURCES = new HashMap<String, DamageSource>();
    private static Constructor<DamageSource> newSource;
    private static Integer wanted;

    private LivingEvents() {
    }

    /** Which of these events any legacy mod listens to, as bits: attack, hurt, death, fall, jump, heal, break speed. */
    public static int livingWanted() {
        if (wanted == null) {
            Class<?>[] events = {LivingAttackEvent.class, LivingHurtEvent.class, LivingDeathEvent.class, LivingFallEvent.class,
                    LivingEvent.LivingJumpEvent.class, LivingHealEvent.class, PlayerEvent.BreakSpeed.class};
            int bits = 0;
            for (int i = 0; i < events.length; i++) {
                bits |= Events.listenedTo(events[i]) ? 1 << i : 0;
            }
            wanted = bits;
        }
        return wanted;
    }

    /** The legacy damage source for a host one: its type, the (legacy) attacker if any, and its properties. */
    static DamageSource source(String type, Object attacker, int flags) throws ReflectiveOperationException {
        DamageSource source;
        if (attacker instanceof EntityPlayer) {
            source = DamageSource.func_76365_a((EntityPlayer) attacker); // causePlayerDamage
        } else if (attacker instanceof EntityLivingBase) {
            source = DamageSource.func_76358_a((EntityLivingBase) attacker); // causeMobDamage
        } else {
            source = staticSource(type);
            if (source != null) {
                return source; // the shared vanilla sources already have their properties
            }
            if (newSource == null) {
                newSource = DamageSource.class.getDeclaredConstructor(String.class);
                newSource.setAccessible(true);
            }
            source = newSource.newInstance(type);
        }
        if ((flags & PROJECTILE) != 0) {
            source.func_76349_b(); // setProjectile
        }
        if ((flags & FIRE) != 0) {
            source.func_76361_j(); // setFireDamage
        }
        if ((flags & MAGIC) != 0) {
            source.func_82726_p(); // setMagicDamage
        }
        if ((flags & EXPLOSION) != 0) {
            source.func_94540_d(); // setExplosion
        }
        if ((flags & BYPASSES_ARMOR) != 0) {
            source.func_76348_h(); // setDamageBypassesArmor
        }
        return source;
    }

    private static synchronized DamageSource staticSource(String type) throws IllegalAccessException {
        if (STATIC_SOURCES.isEmpty()) {
            for (Field field : DamageSource.class.getFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == DamageSource.class) {
                    DamageSource source = (DamageSource) field.get(null);
                    STATIC_SOURCES.put(source.field_76373_n, source); // damageType
                }
            }
        }
        return STATIC_SOURCES.get(type);
    }

    /** LivingAttackEvent; true if a mod cancelled the attack. */
    public static boolean livingAttack(Object player, String type, Object attacker, int flags, float amount) throws ReflectiveOperationException {
        return MinecraftForge.EVENT_BUS.post(new LivingAttackEvent((EntityLivingBase) player, source(type, attacker, flags), amount));
    }

    /** LivingHurtEvent; the amount after the mods (NaN if cancelled). */
    public static float livingHurt(Object player, String type, Object attacker, int flags, float amount) throws ReflectiveOperationException {
        LivingHurtEvent event = new LivingHurtEvent((EntityLivingBase) player, source(type, attacker, flags), amount);
        return MinecraftForge.EVENT_BUS.post(event) ? Float.NaN : event.ammount;
    }

    /** LivingDeathEvent; true if a mod cancelled the death (and, typically, healed the player). */
    public static boolean livingDeath(Object player, String type, Object attacker, int flags) throws ReflectiveOperationException {
        return MinecraftForge.EVENT_BUS.post(new LivingDeathEvent((EntityLivingBase) player, source(type, attacker, flags)));
    }

    /** LivingFallEvent; the fall distance after the mods (NaN if cancelled). */
    public static float livingFall(Object player, float distance) {
        LivingFallEvent event = new LivingFallEvent((EntityLivingBase) player, distance);
        return MinecraftForge.EVENT_BUS.post(event) ? Float.NaN : event.distance;
    }

    public static void livingJump(Object player) {
        MinecraftForge.EVENT_BUS.post(new LivingEvent.LivingJumpEvent((EntityLivingBase) player));
    }

    /** LivingHealEvent; the amount after the mods (NaN if cancelled). */
    public static float livingHeal(Object player, float amount) {
        LivingHealEvent event = new LivingHealEvent((EntityLivingBase) player, amount);
        return MinecraftForge.EVENT_BUS.post(event) ? Float.NaN : event.amount;
    }

    /** PlayerEvent.BreakSpeed; the speed after the mods (NaN if cancelled). */
    public static float livingBreakSpeed(Object player, Object block, int meta, float speed, int x, int y, int z) {
        PlayerEvent.BreakSpeed event = new PlayerEvent.BreakSpeed((EntityPlayer) player, (Block) block, meta, speed, x, y, z);
        return MinecraftForge.EVENT_BUS.post(event) ? Float.NaN : event.newSpeed;
    }
}
