package retrofit.forge.world;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.AbstractGolem;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 1.20.1 entities as legacy code sees them (the host half; the legacy bridge's HostCreatures makes
 * the stand-ins): legacy mobs target 1.20.1 creatures, legacy weapons, spells and traps hurt
 * them, legacy magnets and vacuum hoppers pick up their items.
 */
final class HostCreatures {
    private HostCreatures() {
    }

    private static boolean visible(Entity entity) {
        return entity.isAlive() && !(entity instanceof LegacyEntityCarrier) && !(entity instanceof Player)
                && (entity instanceof LivingEntity || entity instanceof ItemEntity || entity instanceof ExperienceOrb);
    }

    static int category(Entity entity) {
        if (entity instanceof ItemEntity) {
            return 7;
        }
        if (entity instanceof ExperienceOrb) {
            return 8;
        }
        if (entity instanceof Enemy) {
            return 0;
        }
        if (entity instanceof AbstractVillager) {
            return 4;
        }
        if (entity instanceof AbstractGolem) {
            return 5;
        }
        if (entity instanceof WaterAnimal) {
            return 2;
        }
        if (entity instanceof AmbientCreature) {
            return 3;
        }
        if (entity instanceof Animal) {
            return 1;
        }
        return 6;
    }

    static Object[][] inBox(Level level, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        List<Entity> found = level.getEntities((Entity) null, new AABB(minX, minY, minZ, maxX, maxY, maxZ), HostCreatures::visible);
        if (found.isEmpty()) {
            return new Object[0][];
        }
        List<Object[]> rows = new ArrayList<>(found.size());
        for (Entity entity : found) {
            Object stack = entity instanceof ItemEntity item ? LegacyStacks.toLegacy(item.getItem()) : null;
            if (entity instanceof ItemEntity && stack == null) {
                continue; // a 1.20.1-only item: legacy code can't hold it
            }
            rows.add(new Object[]{entity, BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), category(entity), state(entity), stack});
        }
        return rows.toArray(new Object[0][]);
    }

    static double[] state(Object handle) {
        if (!(handle instanceof Entity entity) || !entity.isAlive() || entity.isRemoved()) {
            return null;
        }
        Vec3 motion = entity.getDeltaMovement();
        int flags = (entity.onGround() ? 1 : 0) | (entity instanceof LivingEntity living && living.isBaby() ? 2 : 0)
                | (entity.isOnFire() ? 4 : 0) | (entity.isCrouching() ? 8 : 0);
        double health = 0;
        double maxHealth = 0;
        double extra = 0;
        if (entity instanceof LivingEntity living) {
            health = living.getHealth();
            maxHealth = living.getMaxHealth();
        } else if (entity instanceof ExperienceOrb orb) {
            extra = orb.getValue();
        } else if (entity instanceof ItemEntity item) {
            extra = item.getAge();
        }
        return new double[]{entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), entity.getXRot(), entity.getYHeadRot(),
                motion.x, motion.y, motion.z, entity.getBbWidth(), entity.getBbHeight(), health, maxHealth, flags, extra};
    }

    /** A 1.20.1 damage source for a legacy damage type and attacker (a legacy entity, a player's mirror or a stand-in). */
    static DamageSource damageSource(LivingEntity target, String kind, Object legacyAttacker) {
        DamageSources sources = target.damageSources();
        Entity attacker = LegacyEntityCore.hostOf(legacyAttacker);
        if (attacker instanceof Player player) {
            return sources.playerAttack(player);
        }
        if (attacker instanceof LivingEntity living && (kind == null || kind.equals("mob") || kind.equals("player"))) {
            return sources.mobAttack(living);
        }
        return switch (kind == null ? "generic" : kind) {
            case "fall" -> sources.fall();
            case "inFire" -> sources.inFire();
            case "onFire" -> sources.onFire();
            case "lava" -> sources.lava();
            case "drown" -> sources.drown();
            case "cactus" -> sources.cactus();
            case "magic", "indirectMagic" -> attacker != null ? sources.indirectMagic(attacker, attacker) : sources.magic();
            case "wither" -> sources.wither();
            case "outOfWorld" -> sources.fellOutOfWorld();
            case "inWall" -> sources.inWall();
            case "starve" -> sources.starve();
            case "anvil" -> sources.anvil(attacker);
            case "fallingBlock" -> sources.fallingBlock(attacker);
            case "thorns" -> attacker != null ? sources.thorns(attacker) : sources.generic();
            case "arrow" -> attacker != null ? sources.arrow(null, attacker) : sources.generic();
            case "explosion", "explosion.player" -> sources.explosion(attacker, attacker);
            default -> attacker instanceof LivingEntity living ? sources.mobAttack(living)
                    : attacker != null ? sources.mobProjectile(attacker, null) : sources.generic();
        };
    }

    static boolean hurt(Object handle, String kind, Object legacyAttacker, float amount) {
        if (!(handle instanceof LivingEntity target) || !target.isAlive() || target.level().isClientSide) {
            return false;
        }
        return target.hurt(damageSource(target, kind, legacyAttacker), amount);
    }

    static void act(Object handle, int action, double a, double b, double c) {
        if (!(handle instanceof Entity entity) || entity.level().isClientSide) {
            return;
        }
        switch (action) {
            case 0 -> entity.discard();
            case 1 -> {
                entity.push(a, b, c);
                entity.hurtMarked = true;
            }
            case 2 -> {
                entity.setDeltaMovement(a, b, c);
                entity.hurtMarked = true;
            }
            case 3 -> entity.setSecondsOnFire((int) a);
            case 4 -> entity.teleportTo(a, b, c);
            case 5 -> {
                if (entity instanceof LivingEntity living) {
                    int id = (int) a;
                    MobEffect effect = id >= 1 && id <= 23 ? MobEffect.byId(id) : LegacyMobEffects.byLegacyId(id);
                    if (effect != null) {
                        living.addEffect(new MobEffectInstance(effect, (int) b, (int) c));
                    }
                }
            }
            case 6 -> {
                if (entity instanceof LivingEntity living) {
                    living.setHealth((float) a);
                }
            }
            default -> {
            }
        }
    }

    static void setItemStack(Object handle, Object legacyStack) {
        if (handle instanceof ItemEntity item && !item.level().isClientSide) {
            net.minecraft.world.item.ItemStack stack = LegacyStacks.toModern(legacyStack);
            if (stack.isEmpty()) {
                item.discard();
            } else {
                item.setItem(stack);
            }
        }
    }

    /** Babies of 1.20.1 creatures stay babies in legacy code's eyes (AgeableMob covers animals and villagers). */
    static boolean isBaby(Entity entity) {
        return entity instanceof AgeableMob ageable ? ageable.isBaby() : entity instanceof LivingEntity living && living.isBaby();
    }
}
