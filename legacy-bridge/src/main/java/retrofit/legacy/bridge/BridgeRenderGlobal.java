package retrofit.legacy.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.world.World;

/**
 * Client only. Minecraft.renderGlobal for mods that use it directly, mostly to spawn particles
 * (Thaumcraft). The host renders the world, so this is created without RenderGlobal's constructor
 * (which builds render lists and GL state); particles go to the current legacy world, which hands
 * them to the host, and render update requests are dropped (the host world tracks its own).
 */
final class BridgeRenderGlobal extends RenderGlobal {
    private BridgeRenderGlobal() {
        super(null); // never called; see LegacyRendering
    }

    private static World world() {
        return Minecraft.func_71410_x().field_71441_e; // theWorld
    }

    /** spawnParticle */
    @Override
    public void func_72708_a(String name, double x, double y, double z, double vx, double vy, double vz) {
        World world = world();
        if (world != null) {
            world.func_72869_a(name, x, y, z, vx, vy, vz);
        }
    }

    /** doSpawnParticle: spawned through the host, so there is no legacy particle object to return */
    @Override
    public EntityFX func_72726_b(String name, double x, double y, double z, double vx, double vy, double vz) {
        func_72708_a(name, x, y, z, vx, vy, vz);
        return null;
    }

    /** markBlockForUpdate */
    @Override
    public void func_147586_a(int x, int y, int z) {
    }

    /** markBlockForRenderUpdate */
    @Override
    public void func_147588_b(int x, int y, int z) {
    }

    /** markBlockRangeForRenderUpdate */
    @Override
    public void func_147585_a(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }

    /** destroyBlockPartially */
    @Override
    public void func_147587_b(int breakerId, int x, int y, int z, int progress) {
    }
}
