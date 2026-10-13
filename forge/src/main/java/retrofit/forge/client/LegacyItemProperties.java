package retrofit.forge.client;

import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import retrofit.forge.RetrofitMod;
import retrofit.forge.world.LegacyContent;
import retrofit.forge.world.LegacyBlock;
import retrofit.forge.world.LegacyBlockItem;
import retrofit.forge.world.LegacyItem;

/** Item model property {@code retrofit:meta}: a legacy item's subtype, used by its model overrides. */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class LegacyItemProperties {
    public static final ResourceLocation META = new ResourceLocation(RetrofitMod.MOD_ID, "meta");

    private LegacyItemProperties() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            for (LegacyItem item : LegacyContent.items()) {
                if (item.hasSubtypes()) {
                    ItemProperties.register(item, META, (stack, level, entity, seed) -> item.meta(stack));
                }
            }
            for (LegacyBlock block : LegacyContent.blocks()) {
                if (block.asItem() instanceof LegacyBlockItem item && item.hasSubtypes()) {
                    ItemProperties.register(item, META, (stack, level, entity, seed) -> item.meta(stack));
                }
            }
        });
    }
}
