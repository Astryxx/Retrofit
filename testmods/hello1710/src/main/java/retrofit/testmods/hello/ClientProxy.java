package retrofit.testmods.hello;

import cpw.mods.fml.client.registry.RenderingRegistry;
import net.minecraft.client.model.ModelZombie;
import net.minecraft.client.renderer.entity.RenderBiped;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

/** Client side: the golem is drawn as a biped with the 1.7.10 zombie texture. */
public class ClientProxy extends CommonProxy {
    private static final ResourceLocation TEXTURE = new ResourceLocation("textures/entity/zombie/zombie.png");

    @Override
    public void registerRenderers() {
        HelloClientEvents events = new HelloClientEvents();
        cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(events);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(events);
        // Zombie skins use a 64x64 layout. The generic 64x32 biped sampled transparent regions,
        // leaving a floating head and scraps of torso during Mob Battle tests.
        RenderingRegistry.registerEntityRenderingHandler(RubyGolem.class, new RenderBiped(new ModelZombie(), 0.5f) {
            @Override
            protected ResourceLocation func_110775_a(Entity entity) { // getEntityTexture
                return TEXTURE;
            }
        });
    }
}
