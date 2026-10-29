package retrofit.forge.client;

import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.gui.ModMismatchDisconnectedScreen;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import retrofit.forge.RetrofitMod;
import retrofit.forge.world.LegacyNetwork;

/**
 * A server turned this client away because it runs another Retrofit version: instead of Forge's list
 * of channels (one of them Retrofit's, with no word on why), a screen saying so. Different 1.7.10 mods
 * are told after joining (see LegacyModCheck).
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, value = Dist.CLIENT)
public final class LegacyModMismatch {
    private LegacyModMismatch() {
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof ModMismatchDisconnectedScreen)) {
            return;
        }
        if (LegacyNetwork.takeMismatch()) {
            event.setNewScreen(new DisconnectedScreen(new JoinMultiplayerScreen(new TitleScreen()),
                    Component.translatable("retrofit.mismatch.title"), Component.translatable("retrofit.mismatch.version")));
        }
    }
}
