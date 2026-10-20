package retrofit.legacy.bridge;

import net.minecraft.block.Block;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.entity.Entity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Client only. Minecraft.effectRenderer for legacy code: mods often create particles themselves
 * (new EntityFlameFX(...), added with addEffect) instead of calling World.spawnParticle. Each
 * one becomes the matching 1.20.1 particle at its position and motion, found by its class or the
 * closest vanilla class it extends. Particles with their own rendering (custom EntityFX classes)
 * aren't drawn; each such class is reported once.
 */
final class BridgeEffectRenderer extends EffectRenderer {
    private static final Map<String, String> NAMES = new HashMap<String, String>();
    private static final Set<String> REPORTED = new HashSet<String>();

    static {
        String[][] names = {
                {"EntityFlameFX", "flame"}, {"EntitySmokeFX", "smoke"}, {"EntityReddustFX", "reddust"},
                {"EntityPortalFX", "portal"}, {"EntityHeartFX", "heart"}, {"EntityCritFX", "crit"},
                {"EntityEnchantmentTableParticleFX", "enchantmenttable"}, {"EntityNoteFX", "note"}, {"EntityLavaFX", "lava"},
                {"EntitySpellParticleFX", "spell"}, {"EntityCloudFX", "cloud"}, {"EntityDropParticleFX", "dripWater"},
                {"EntitySplashFX", "splash"}, {"EntityRainFX", "splash"}, {"EntityBubbleFX", "bubble"},
                {"EntityExplodeFX", "explode"}, {"EntityAuraFX", "townaura"}, {"EntitySuspendFX", "suspended"},
                {"EntityFireworkSparkFX", "fireworksSpark"}, {"EntitySnowShovelFX", "snowshovel"},
                {"EntityLargeExplodeFX", "largeexplode"}, {"EntityHugeExplodeFX", "hugeexplosion"},
                {"EntityFishWakeFX", "wake"},
        };
        for (String[] name : names) {
            NAMES.put("net.minecraft.client.particle." + name[0], name[1]);
        }
    }

    BridgeEffectRenderer(World world) {
        super(world, null);
    }

    /** addEffect */
    @Override
    public void func_78873_a(EntityFX particle) {
        if (particle == null || !(particle.field_70170_p instanceof BridgeWorldClient)) {
            return;
        }
        String name = null;
        for (Class<?> type = particle.getClass(); type != null && name == null; type = type.getSuperclass()) {
            name = NAMES.get(type.getName());
        }
        if (name == null) {
            synchronized (REPORTED) {
                if (REPORTED.add(particle.getClass().getName())) {
                    System.out.println("[Retrofit] Legacy particle " + particle.getClass().getName() + " has its own rendering; not shown");
                }
            }
            return;
        }
        BridgeWorldClient world = (BridgeWorldClient) particle.field_70170_p;
        if (world.host() == null) {
            return;
        }
        if (name.equals("reddust") || name.equals("spell")) { // colour particles: 1.7.10 passed the colour as motion
            world.host().spawnParticle(name, particle.field_70165_t, particle.field_70163_u, particle.field_70161_v,
                    particle.func_70534_d(), particle.func_70542_f(), particle.func_70535_g());
        } else {
            world.host().spawnParticle(name, particle.field_70165_t, particle.field_70163_u, particle.field_70161_v,
                    particle.field_70159_w, particle.field_70181_x, particle.field_70179_y);
        }
    }

    /** updateEffects: 1.20.1 updates its own particles. */
    @Override
    public void func_78868_a() {
    }

    @Override
    public void func_78874_a(Entity viewer, float partialTicks) {
    }

    @Override
    public void func_78872_b(Entity viewer, float partialTicks) {
    }

    @Override
    public void func_78870_a(World world) {
    }

    /** addBlockDestroyEffects: 1.20.1 shows its own. */
    @Override
    public void func_147215_a(int x, int y, int z, Block block, int meta) {
    }

    @Override
    public void func_78867_a(int x, int y, int z, int side) {
    }

    @Override
    public void addBlockHitEffects(int x, int y, int z, MovingObjectPosition target) {
    }
}
