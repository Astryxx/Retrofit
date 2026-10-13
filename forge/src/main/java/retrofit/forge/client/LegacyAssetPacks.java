package retrofit.forge.client;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import retrofit.forge.RetrofitMod;
import retrofit.host.LegacyRuntime;

/**
 * Adds {@link LegacyAssetPack} as an always-on built-in client resource pack, at the bottom of
 * the pack order: 1.7.10 translations reuse keys 1.20.1 still has (e.g. "gui.done"), and
 * anything vanilla, another mod or the player's own resource packs define must win. That also
 * lets players' resource packs re-texture legacy content.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class LegacyAssetPacks {
    private LegacyAssetPacks() {
    }

    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event) {
        LegacyRuntime runtime = RetrofitMod.runtime();
        if (event.getPackType() != PackType.CLIENT_RESOURCES || runtime == null) {
            return;
        }
        event.addRepositorySource(packs -> {
            Pack pack = Pack.readMetaAndCreate("retrofit_legacy_assets", Component.literal("Retrofit: 1.7.10 mod assets"),
                    true, id -> LegacyAssetPack.create(id, runtime), PackType.CLIENT_RESOURCES, Pack.Position.BOTTOM,
                    PackSource.BUILT_IN);
            if (pack != null) {
                packs.accept(pack);
            }
        });
    }
}
