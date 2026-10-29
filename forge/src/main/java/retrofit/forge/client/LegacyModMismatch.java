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
        } else {
            // Forge assumes every registry namespace has a modern ModInfo. Legacy namespaces
            // have none; its mismatch panel dereferences null while explaining an incomplete pack.
            try {
                java.lang.reflect.Field field = ModMismatchDisconnectedScreen.class.getDeclaredField("presentModData");
                field.setAccessible(true);
                java.util.Map<?, ?> present = (java.util.Map<?, ?>) field.get(event.getNewScreen());
                java.util.Set<Object> keys = new java.util.HashSet<>();
                for (String name : new String[]{"missingModData", "mismatchedModData"}) {
                    java.lang.reflect.Field data = ModMismatchDisconnectedScreen.class.getDeclaredField(name);
                    data.setAccessible(true);
                    Object value = data.get(event.getNewScreen());
                    if (value instanceof java.util.Map<?, ?> map) keys.addAll(map.keySet());
                    if (value instanceof java.util.Collection<?> list) keys.addAll(list);
                }
                if (keys.stream().anyMatch(key -> !present.containsKey(key) || key instanceof net.minecraft.resources.ResourceLocation id
                        && !net.minecraftforge.fml.ModList.get().isLoaded(id.getNamespace()))) {
                    event.setNewScreen(new DisconnectedScreen(new JoinMultiplayerScreen(new TitleScreen()),
                            Component.translatable("retrofit.mismatch.title"), Component.literal(
                            "The server and client loaded different legacy content. Check retrofit/mods and retrofit/duplicates "
                                    + "on both computers, and logs/latest.log for mods that failed to load. Both sides need the same legacy pack.")));
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                com.mojang.logging.LogUtils.getLogger().debug("Could not inspect Forge's mismatch data", e);
            }
        }
    }
}
