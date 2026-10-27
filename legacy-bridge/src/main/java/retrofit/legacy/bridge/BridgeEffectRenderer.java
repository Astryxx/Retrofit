package retrofit.legacy.bridge;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.Entity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import retrofit.legacy.gl.GL11;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client only. Minecraft.effectRenderer for legacy code: mods often create particles themselves
 * (new EntityFlameFX(...), added with addEffect) instead of calling World.spawnParticle. Each
 * one becomes the matching 1.20.1 particle at its position and motion, found by its class or the
 * closest vanilla class it extends. Particles with their own rendering (custom EntityFX classes:
 * ManaMetal's magic, the trails of its scythes and guns) are kept here as 1.7.10 kept them, in
 * four layers, updated each client tick and drawn each frame through the GL emulation.
 */
final class BridgeEffectRenderer extends EffectRenderer {
    private static final Map<String, String> NAMES = new HashMap<String, String>();
    private static final Set<String> REPORTED = new HashSet<String>();
    private static final ResourceLocation PARTICLES = new ResourceLocation("textures/particle/particles.png");
    /** As 1.7.10: at most this many particles per layer, the oldest dropped first. */
    private static final int LAYER_LIMIT = 4000;
    private static final int GL_BLEND = 0x0BE2;
    private static final int GL_SRC_ALPHA = 0x0302;
    private static final int GL_ONE_MINUS_SRC_ALPHA = 0x0303;
    private static final int GL_GREATER = 0x0204;
    private static final int GL_TEXTURE_2D = 0x0DE1;

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

    @SuppressWarnings("unchecked")
    private final List<EntityFX>[] layers = new List[]{new ArrayList<EntityFX>(), new ArrayList<EntityFX>(),
            new ArrayList<EntityFX>(), new ArrayList<EntityFX>()};

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
            List<EntityFX> layer = layers[Math.max(0, Math.min(3, particle.func_70537_b()))]; // getFXLayer
            if (layer.size() >= LAYER_LIMIT) {
                layer.remove(0);
            }
            layer.add(particle);
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

    /**
     * updateEffects: the particles kept here (1.20.1 updates its own); those of another world are
     * dropped. By index, as 1.7.10 did: particles add particles while they update.
     */
    @Override
    public void func_78868_a() {
        World world = Minecraft.func_71410_x().field_71441_e; // theWorld
        for (List<EntityFX> layer : layers) {
            for (int i = 0; i < layer.size(); i++) {
                EntityFX particle = layer.get(i);
                if (particle.field_70170_p != world || !update(particle)) { // worldObj
                    layer.remove(i--);
                }
            }
        }
    }

    /** One particle's onUpdate; false once it is dead or failed. */
    private static boolean update(EntityFX particle) {
        try {
            particle.func_70071_h_(); // onUpdate
        } catch (RuntimeException e) {
            report(particle, "updating", e);
            return false;
        }
        return !particle.field_70128_L; // isDead
    }

    /** A failing particle is left out; each class is reported once. */
    private static void report(EntityFX particle, String doing, RuntimeException e) {
        synchronized (REPORTED) {
            if (REPORTED.add(particle.getClass().getName())) {
                System.out.println("[Retrofit] Legacy particle " + particle.getClass().getName() + " failed " + doing + "; left out: " + e);
            }
        }
    }

    /** The state 1.7.10's EntityRenderer drew particles in, whatever legacy drawing before left (textures on). */
    static void prepareState() {
        GL11.glEnable(GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** Whether any particle is kept here (only then is there anything to draw). */
    boolean hasParticles() {
        for (List<EntityFX> layer : layers) {
            if (!layer.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * renderParticles: layers 0 to 2 as 1.7.10's EntityRenderer drew them (the vanilla particle
     * sheet, the block and the item icons; blended, no depth writes), relative to the viewer.
     */
    @Override
    public void func_78874_a(Entity viewer, float partialTicks) {
        EntityFX.field_70556_an = viewer.field_70142_S + (viewer.field_70165_t - viewer.field_70142_S) * partialTicks; // interpPosX: lastTickPosX, posX
        EntityFX.field_70554_ao = viewer.field_70137_T + (viewer.field_70163_u - viewer.field_70137_T) * partialTicks;
        EntityFX.field_70555_ap = viewer.field_70136_U + (viewer.field_70161_v - viewer.field_70136_U) * partialTicks;
        ResourceLocation[] textures = {PARTICLES, TextureMap.field_110575_b, TextureMap.field_110576_c}; // locationBlocksTexture, locationItemsTexture
        Tessellator tessellator = Tessellator.field_78398_a; // instance
        for (int i = 0; i < 3; i++) {
            if (layers[i].isEmpty()) {
                continue;
            }
            Minecraft.func_71410_x().field_71446_o.func_110577_a(textures[i]); // renderEngine.bindTexture
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glDepthMask(false);
            GL11.glEnable(GL_BLEND);
            GL11.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            GL11.glAlphaFunc(GL_GREATER, 0.003921569F);
            tessellator.func_78382_b(); // startDrawingQuads
            List<EntityFX> layer = layers[i];
            for (int j = 0; j < layer.size(); j++) {
                EntityFX particle = layer.get(j);
                tessellator.func_78380_c(particle.func_70070_b(partialTicks)); // setBrightness(getBrightnessForRender)
                if (!render(particle, tessellator, partialTicks, ActiveRenderInfo.field_74588_d, ActiveRenderInfo.field_74589_e,
                        ActiveRenderInfo.field_74586_f, ActiveRenderInfo.field_74587_g, ActiveRenderInfo.field_74596_h)) {
                    layer.remove(j--);
                }
            }
            tessellator.func_78381_a(); // draw
            GL11.glDisable(GL_BLEND);
            GL11.glDepthMask(true);
            GL11.glAlphaFunc(GL_GREATER, 0.1F);
        }
    }

    /** renderLitParticles: layer 3, whose particles set up their own drawing. */
    @Override
    public void func_78872_b(Entity viewer, float partialTicks) {
        if (layers[3].isEmpty()) {
            return;
        }
        float yaw = viewer.field_70177_z * 0.017453292F, pitch = viewer.field_70125_A * 0.017453292F; // rotationYaw, rotationPitch
        float cosYaw = (float) Math.cos(yaw), sinYaw = (float) Math.sin(yaw);
        Tessellator tessellator = Tessellator.field_78398_a;
        List<EntityFX> layer = layers[3];
        for (int i = 0; i < layer.size(); i++) {
            EntityFX particle = layer.get(i);
            tessellator.func_78380_c(particle.func_70070_b(partialTicks));
            if (!render(particle, tessellator, partialTicks, cosYaw, (float) Math.cos(pitch), sinYaw,
                    -sinYaw * (float) Math.sin(pitch), cosYaw * (float) Math.sin(pitch))) {
                layer.remove(i--);
            }
        }
    }

    private static boolean render(EntityFX particle, Tessellator tessellator, float partialTicks,
                                  float rotationX, float rotationXZ, float rotationZ, float rotationYZ, float rotationXY) {
        try {
            particle.func_70539_a(tessellator, partialTicks, rotationX, rotationXZ, rotationZ, rotationYZ, rotationXY); // renderParticle
            return true;
        } catch (RuntimeException e) {
            report(particle, "drawing", e);
            return false;
        }
    }

    /** clearEffects */
    @Override
    public void func_78870_a(World world) {
        for (List<EntityFX> layer : layers) {
            layer.clear();
        }
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
