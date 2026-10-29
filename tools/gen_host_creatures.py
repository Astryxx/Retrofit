"""Generates legacy-bridge/.../HostCreatureProxies.java: stand-ins for 1.20.1 entities in legacy worlds.

Each stand-in is the 1.7.10 class of the same creature (mods check instanceof EntityZombie,
EntityAnimal, IMob...), never ticks, and sends what legacy code does to it (damage, knockback,
fire, potions, teleports, removal) to the 1.20.1 entity. 1.20.1 creatures with no 1.7.10 class
get one of the general classes by category.

Run from the repository root: python tools/gen_host_creatures.py
"""

# (class name, 1.7.10 superclass, constructor extras, 1.20.1 type ids)
VANILLA = [
    ('Zombie', 'net.minecraft.entity.monster.EntityZombie', '', ['minecraft:zombie', 'minecraft:husk', 'minecraft:drowned']),
    ('ZombieVillager', 'net.minecraft.entity.monster.EntityZombie', 'func_82229_g(true); // setVillager', ['minecraft:zombie_villager']),
    ('Giant', 'net.minecraft.entity.monster.EntityGiantZombie', '', ['minecraft:giant']),
    ('PigZombie', 'net.minecraft.entity.monster.EntityPigZombie', '', ['minecraft:zombified_piglin']),
    ('Skeleton', 'net.minecraft.entity.monster.EntitySkeleton', '', ['minecraft:skeleton', 'minecraft:stray']),
    ('WitherSkeleton', 'net.minecraft.entity.monster.EntitySkeleton', 'func_82201_a(1); // setSkeletonType: wither', ['minecraft:wither_skeleton']),
    ('Creeper', 'net.minecraft.entity.monster.EntityCreeper', '', ['minecraft:creeper']),
    ('Spider', 'net.minecraft.entity.monster.EntitySpider', '', ['minecraft:spider']),
    ('CaveSpider', 'net.minecraft.entity.monster.EntityCaveSpider', '', ['minecraft:cave_spider']),
    ('Enderman', 'net.minecraft.entity.monster.EntityEnderman', '', ['minecraft:enderman']),
    ('Silverfish', 'net.minecraft.entity.monster.EntitySilverfish', '', ['minecraft:silverfish', 'minecraft:endermite']),
    ('Blaze', 'net.minecraft.entity.monster.EntityBlaze', '', ['minecraft:blaze']),
    ('Ghast', 'net.minecraft.entity.monster.EntityGhast', '', ['minecraft:ghast']),
    ('MagmaCube', 'net.minecraft.entity.monster.EntityMagmaCube', '', ['minecraft:magma_cube']),
    ('Slime', 'net.minecraft.entity.monster.EntitySlime', '', ['minecraft:slime']),
    ('Witch', 'net.minecraft.entity.monster.EntityWitch', '', ['minecraft:witch']),
    ('Wither', 'net.minecraft.entity.boss.EntityWither', '', ['minecraft:wither']),
    ('Pig', 'net.minecraft.entity.passive.EntityPig', '', ['minecraft:pig']),
    ('Cow', 'net.minecraft.entity.passive.EntityCow', '', ['minecraft:cow']),
    ('Mooshroom', 'net.minecraft.entity.passive.EntityMooshroom', '', ['minecraft:mooshroom']),
    ('Sheep', 'net.minecraft.entity.passive.EntitySheep', '', ['minecraft:sheep']),
    ('Chicken', 'net.minecraft.entity.passive.EntityChicken', '', ['minecraft:chicken']),
    ('Wolf', 'net.minecraft.entity.passive.EntityWolf', '', ['minecraft:wolf']),
    ('Ocelot', 'net.minecraft.entity.passive.EntityOcelot', '', ['minecraft:ocelot', 'minecraft:cat']),
    ('Horse', 'net.minecraft.entity.passive.EntityHorse', '', ['minecraft:horse']),
    ('Donkey', 'net.minecraft.entity.passive.EntityHorse', 'func_110214_p(1); // setHorseType', ['minecraft:donkey', 'minecraft:llama', 'minecraft:trader_llama']),
    ('Mule', 'net.minecraft.entity.passive.EntityHorse', 'func_110214_p(2);', ['minecraft:mule']),
    ('ZombieHorse', 'net.minecraft.entity.passive.EntityHorse', 'func_110214_p(3);', ['minecraft:zombie_horse']),
    ('SkeletonHorse', 'net.minecraft.entity.passive.EntityHorse', 'func_110214_p(4);', ['minecraft:skeleton_horse']),
    ('Villager', 'net.minecraft.entity.passive.EntityVillager', '', ['minecraft:villager', 'minecraft:wandering_trader']),
    ('IronGolem', 'net.minecraft.entity.monster.EntityIronGolem', '', ['minecraft:iron_golem']),
    ('SnowGolem', 'net.minecraft.entity.monster.EntitySnowman', '', ['minecraft:snow_golem']),
    ('Squid', 'net.minecraft.entity.passive.EntitySquid', '', ['minecraft:squid', 'minecraft:glow_squid']),
    ('Bat', 'net.minecraft.entity.passive.EntityBat', '', ['minecraft:bat']),
]

# By category (HostWorld.hostEntities): 1.20.1 creatures with no 1.7.10 class.
GENERAL = [
    (0, 'Monster', 'net.minecraft.entity.monster.EntityMob', ''),
    (1, 'Animal', 'net.minecraft.entity.passive.EntityAnimal', '''
        @Override
        public net.minecraft.entity.EntityAgeable func_90011_a(net.minecraft.entity.EntityAgeable mate) { // createChild: the 1.20.1 creature breeds itself
            return null;
        }
'''),
    (2, 'WaterCreature', 'net.minecraft.entity.passive.EntityWaterMob', ''),
    (3, 'Ambient', 'net.minecraft.entity.passive.EntityAmbientCreature', ''),
    (4, 'Trader', 'net.minecraft.entity.passive.EntityVillager', ''),
    (5, 'Golem', 'net.minecraft.entity.monster.EntityGolem', ''),
    (6, 'Creature', 'net.minecraft.entity.EntityCreature', ''),
]

LIVING = '''
        private Core core;

        /** Lazily: the 1.7.10 constructors call overridden methods (setHealth) before fields are set. */
        @Override
        public Core core() {
            if (core == null) {
                core = new Core(this);
            }
            return core;
        }

        @Override
        public void resize(float width, float height) {
            func_70105_a(width, height); // setSize
        }

        @Override
        public void func_70071_h_() { // onUpdate: the 1.20.1 entity lives on its own
        }

        @Override
        public boolean func_70097_a(DamageSource source, float amount) { // attackEntityFrom
            return core().hurt(source, amount);
        }

        @Override
        public void func_70106_y() { // setDead
            core().remove();
            super.func_70106_y();
        }

        @Override
        public void func_70024_g(double x, double y, double z) { // addVelocity
            core().act(1, x, y, z);
        }

        @Override
        public void func_70653_a(Entity attacker, float strength, double x, double z) { // knockBack
            super.func_70653_a(attacker, strength, x, z);
            core().forwardMotion(); // the motion the knockback gave
        }

        @Override
        public void func_70015_d(int seconds) { // setFire
            core().act(3, seconds, 0, 0);
        }

        @Override
        public void func_70690_d(PotionEffect effect) { // addPotionEffect
            if (core().syncing) {
                super.func_70690_d(effect);
            } else {
                core().act(5, effect.func_76456_a(), effect.func_76459_b(), effect.func_76458_c()); // getPotionID, getDuration, getAmplifier
            }
        }

        @Override
        public void func_70634_a(double x, double y, double z) { // setPositionAndUpdate
            core().act(4, x, y, z);
        }

        @Override
        public void func_70624_b(EntityLivingBase target) { // setAttackTarget: the 1.20.1 creature takes it (Mob Battle's sticks)
            super.func_70624_b(target);
            core().target(target);
        }

        @Override
        public void func_70674_bp() { // clearActivePotions
            super.func_70674_bp();
            core().act(7, 0, 0, 0);
        }

        @Override
        public void func_70606_j(float health) { // setHealth
            super.func_70606_j(health);
            if (!core().syncing) {
                core().act(6, health, 0, 0);
            }
        }
'''

ITEM = '''
        private Core core;

        /** Lazily: the 1.7.10 constructors call overridden methods (setHealth) before fields are set. */
        @Override
        public Core core() {
            if (core == null) {
                core = new Core(this);
            }
            return core;
        }

        @Override
        public void resize(float width, float height) {
            func_70105_a(width, height);
        }

        @Override
        public void func_70071_h_() {
        }

        @Override
        public boolean func_70097_a(DamageSource source, float amount) {
            return false;
        }

        @Override
        public void func_70106_y() { // setDead: picked up (magnets, vacuum hoppers)
            core().remove();
            super.func_70106_y();
        }

        @Override
        public void func_92058_a(ItemStack stack) { // setEntityItemStack
            super.func_92058_a(stack);
            if (!core().syncing) {
                core().setStack(stack);
            }
        }

        @Override
        public void func_70024_g(double x, double y, double z) {
            core().act(1, x, y, z);
        }
'''

ORB = '''
        private Core core;

        /** Lazily: the 1.7.10 constructors call overridden methods (setHealth) before fields are set. */
        @Override
        public Core core() {
            if (core == null) {
                core = new Core(this);
            }
            return core;
        }

        @Override
        public void resize(float width, float height) {
            func_70105_a(width, height);
        }

        @Override
        public void func_70071_h_() {
        }

        @Override
        public void func_70106_y() {
            core().remove();
            super.func_70106_y();
        }

        @Override
        public void func_70024_g(double x, double y, double z) {
            core().act(1, x, y, z);
        }
'''

out = ['''package retrofit.legacy.bridge;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;
import retrofit.api.HostWorld;

/**
 * Stand-ins for 1.20.1 entities in legacy worlds (see {@link HostCreatures}). Each is the 1.7.10
 * class of the same creature, or a general one by category (monster, animal, water creature...),
 * never ticks, and sends what legacy code does to it to the 1.20.1 entity.
 *
 * The creature classes and their (World) constructors are public, as 1.7.10's are: mods copy a
 * creature with getClass().getConstructor(World.class) (Pistronics' statues).
 *
 * GENERATED by tools/gen_host_creatures.py.
 */
public final class HostCreatureProxies {
    private HostCreatureProxies() {
    }

    interface Proxy {
        Core core();

        void resize(float width, float height);
    }

    /** What every stand-in shares: the host entity it stands for, and the way to it. */
    static final class Core {
        final Entity entity;
        HostWorld host;
        Object handle;
        String type;
        boolean syncing;
        boolean removed;
        private ItemStack given;
        private ItemStack snapshot;
        private double[] syncedMotion; // the motion sync last put on the stand-in

        Core(Entity entity) {
            this.entity = entity;
        }

        boolean hurt(DamageSource source, float amount) {
            if (removed || host == null || source == null) {
                return false;
            }
            boolean hurt = host.hurtHostEntity(handle, source.field_76373_n, source.func_76346_g(), amount); // damageType, getEntity
            if (hurt) {
                // motion legacy code gave the stand-in before the hit (some blast code pushes first and
                // hurts second) travels with it: the state read next would otherwise overwrite the write
                flushForeignMotion();
                // the hit shows at once, as it did on 1.7.10, not at the end of the tick: mods read the health
                // right after (Pistronics' petrify arrow makes a statue of what it killed)
                double[] state = host.hostEntityState(handle);
                try {
                    if (state != null) {
                        sync(state, null);
                    } else if (entity instanceof EntityLivingBase) {
                        syncing = true;
                        ((EntityLivingBase) entity).func_70606_j(0); // setHealth: the host entity is gone
                    }
                } catch (RuntimeException e) {
                    // the stand-in follows at the end of the tick instead
                } finally {
                    syncing = false;
                }
            }
            return hurt;
        }

        void act(int action, double a, double b, double c) {
            if (!removed && host != null && !syncing) {
                host.hostEntityAction(handle, action, a, b, c);
            }
        }

        void target(EntityLivingBase target) {
            if (!removed && host != null && !syncing) {
                host.setHostEntityTarget(handle, target);
            }
        }

        /**
         * The motion vanilla knockback (knockBack) left on the stand-in's own fields: sent as the
         * motion the host entity now has, and recorded as given, so the next foreign-motion check
         * ({@link #flushForeignMotion}) reads no change in it.
         */
        void forwardMotion() {
            act(2, entity.field_70159_w, entity.field_70181_x, entity.field_70179_y);
            syncedMotion = new double[]{entity.field_70159_w, entity.field_70181_x, entity.field_70179_y};
        }

        /**
         * Motion legacy code wrote on the stand-in's motion fields since the last sync, sent to the
         * host entity as the push it was. 1.7.10 code moves creatures it doesn't own by writing
         * motionX/Y/Z directly (Rival Rebels' plasma explosion knocks entities back that way, and so
         * do its own explosions - the nukes, the tsar - as well as magnets, grinders and blast
         * knockback generally), which no overridden method sees: the write used to sit on the
         * stand-in's own fields until the next sync copied the host's motion back over it, so a
         * blast left nothing on the 1.20.1 creature but the damage. Spotted against the motion the
         * last sync put here (the health writes' rule, mirrored), and pushed as the difference - the
         * components legacy code left alone keep the host's own motion (its gravity, drag and other
         * knockbacks ride through), and a component legacy code zeroed arrives as the reverse push
         * that zeroes it. A non-finite write is left for sync to erase (1.7.10 tolerated it; 1.20.1
         * discards entities it cannot move). Runs at the end of every server tick, when the stand-in
         * is about to follow its entity again - the same place in the tick 1.7.10's own knockback
         * took effect from.
         */
        void flushForeignMotion() {
            if (syncing || syncedMotion == null || removed || host == null) {
                return;
            }
            double x = entity.field_70159_w - syncedMotion[0];
            double y = entity.field_70181_x - syncedMotion[1];
            double z = entity.field_70179_y - syncedMotion[2];
            if ((x != 0.0 || y != 0.0 || z != 0.0) && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) {
                act(1, x, y, z); // addVelocity: the change legacy code gave, host-ward
                syncedMotion = new double[]{entity.field_70159_w, entity.field_70181_x, entity.field_70179_y};
            }
        }

        void remove() {
            if (!removed && !syncing && host != null) {
                flushStack();
                host.hostEntityAction(handle, 0, 0, 0, 0);
            }
            removed = true;
        }

        void setStack(ItemStack stack) {
            if (!removed && host != null) {
                host.setHostItemStack(handle, stack);
                track(stack);
            }
        }

        private void track(ItemStack stack) {
            given = stack;
            snapshot = stack == null ? null : stack.func_77946_l(); // copy
        }

        /** An item stand-in's stack changed in place (stackSize -= n): the host item follows. */
        void flushStack() {
            if (given != null && host != null && !ItemStack.func_77989_b(given, snapshot)) { // areItemStacksEqual
                host.setHostItemStack(handle, given.field_77994_a > 0 ? given : null); // stackSize
                track(given);
            }
        }

        /** The host entity's state (HostWorld.hostEntityState) onto the stand-in. */
        void sync(double[] state, Object legacyStack) {
            syncing = true;
            try {
                Entity e = entity;
                if ((float) state[9] != e.field_70130_N || (float) state[10] != e.field_70131_O) { // width, height
                    // The Titans' titans refuse damage from anything as wide as themselves (EntityTitan
                    // and EntityWitherzilla: attacker width >= 6 - that rule is for titanic LEGACY
                    // creatures), and a stand-in mirrors its 1.20.1 entity's bounding box (OmniMobs'
                    // MetapotentFlashfur is registered 12.5 wide). Cap the mirror under that line, so
                    // modern mega bosses can still fight in legacy worlds; the damage they deal still
                    // comes from their real attacks.
                    ((Proxy) e).resize(Math.min((float) state[9], 5.0F), (float) state[10]);
                }
                e.field_70169_q = e.field_70142_S = state[0]; // prevPosX, lastTickPosX
                e.field_70167_r = e.field_70137_T = state[1];
                e.field_70166_s = e.field_70136_U = state[2];
                e.func_70107_b(state[0], state[1], state[2]); // setPosition (and the bounding box)
                e.field_70126_B = e.field_70177_z = (float) state[3]; // prevRotationYaw, rotationYaw
                e.field_70127_C = e.field_70125_A = (float) state[4]; // prevRotationPitch, rotationPitch
                e.field_70159_w = state[6]; // motionX
                e.field_70181_x = state[7];
                e.field_70179_y = state[8];
                syncedMotion = new double[]{state[6], state[7], state[8]};
                int flags = (int) state[13];
                e.field_70122_E = (flags & 1) != 0; // onGround
                if (e instanceof EntityLivingBase) {
                    EntityLivingBase living = (EntityLivingBase) e;
                    living.field_70759_as = living.field_70758_at = (float) state[5]; // rotationYawHead, prevRotationYawHead
                    if (state[12] > 0) {
                        living.func_110148_a(net.minecraft.entity.SharedMonsterAttributes.field_111267_a).func_111128_a(state[12]); // maxHealth.setBaseValue
                    }
                    living.func_70606_j((float) state[11]); // setHealth
                    boolean baby = (flags & 2) != 0;
                    if (e instanceof net.minecraft.entity.EntityAgeable) {
                        if (((net.minecraft.entity.EntityAgeable) e).func_70631_g_() != baby) { // isChild
                            ((net.minecraft.entity.EntityAgeable) e).func_70873_a(baby ? -24000 : 0); // setGrowingAge
                        }
                    } else if (e instanceof net.minecraft.entity.monster.EntityZombie && ((net.minecraft.entity.monster.EntityZombie) e).func_70631_g_() != baby) {
                        ((net.minecraft.entity.monster.EntityZombie) e).func_82227_f(baby); // setChild
                    }
                } else if (e instanceof net.minecraft.entity.item.EntityItem) {
                    flushStack();
                    if (legacyStack != null) {
                        ((net.minecraft.entity.item.EntityItem) e).func_92058_a((ItemStack) legacyStack); // setEntityItemStack
                        track((ItemStack) legacyStack);
                    }
                    ((net.minecraft.entity.item.EntityItem) e).field_70292_b = (int) state[14]; // age
                } else if (e instanceof net.minecraft.entity.item.EntityXPOrb) {
                    ((net.minecraft.entity.item.EntityXPOrb) e).field_70530_e = (int) state[14]; // xpValue
                }
            } finally {
                syncing = false;
            }
        }
    }

    /** A new stand-in for a host entity of a type (and category, for types with no 1.7.10 class). */
    static Entity create(String type, int category, World world) {
''']
for name, sup, extra, ids in VANILLA:
    cond = ' || '.join('type.equals("%s")' % i for i in ids)
    out.append('        if (%s) {\n            return new %s(world);\n        }\n' % (cond, name))
out.append('''        switch (category) {
''')
for cat, name, sup, extra in GENERAL:
    out.append('            case %d:\n                return new %s(world);\n' % (cat, name))
out.append('''            case 7:
                return new Item(world);
            case 8:
                return new Orb(world);
            default:
                return new Creature(world);
        }
    }
''')

for name, sup, extra, ids in VANILLA:
    out.append('''
    public static final class %s extends %s implements Proxy {
        public %s(World world) {
            super(world);
%s        }
%s    }
''' % (name, sup, name, ('            ' + extra + '\n') if extra else '', LIVING))
for cat, name, sup, extra in GENERAL:
    out.append('''
    public static final class %s extends %s implements Proxy {
        public %s(World world) {
            super(world);
        }
%s%s    }
''' % (name, sup, name, LIVING, extra))
out.append('''
    static final class Item extends net.minecraft.entity.item.EntityItem implements Proxy {
        Item(World world) {
            super(world);
        }
%s    }

    static final class Orb extends net.minecraft.entity.item.EntityXPOrb implements Proxy {
        Orb(World world) {
            super(world);
        }
%s    }
}
''' % (ITEM, ORB))

open('legacy-bridge/src/main/java/retrofit/legacy/bridge/HostCreatureProxies.java', 'w', encoding='utf-8').write(''.join(out))
print('ok')
