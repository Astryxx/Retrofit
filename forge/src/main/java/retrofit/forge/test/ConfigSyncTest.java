package retrofit.forge.test;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import retrofit.forge.RetrofitMod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * The 1.7.10 config sync, as a player meets it (-PconfigSyncTest=host:port|config file|the server's copy): joins
 * a dedicated server whose config differs, agrees to use the server's, and checks that the client's file is the
 * server's once it is sent back to the title screen (LegacyConfigSync).
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, value = Dist.CLIENT)
public final class ConfigSyncTest {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SETTING = System.getProperty("retrofit.configSyncTest");
    private static int ticks;
    /** Ticks since the last attempt to connect, and the attempts so far. */
    private static int connectingTicks;
    private static int attempts;
    private static boolean connecting;
    private static boolean agreed;
    private static boolean done;

    private ConfigSyncTest() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (SETTING == null || done || event.phase != TickEvent.Phase.END) {
            return;
        }
        String[] parts = SETTING.split("\\|");
        Minecraft minecraft = Minecraft.getInstance();
        ticks++;
        // A client connecting soon after it started sometimes stalls at "Logging in..." (see the README's
        // known issues): as a player would, give up on it and connect again.
        if (connecting && !agreed && attempts < 3 && (minecraft.screen instanceof DisconnectedScreen || ++connectingTicks > 900)) {
            LOGGER.info("[ConfigSyncTest] Attempt {} stalled or was refused ({}); connecting again", attempts, minecraft.screen);
            connecting = false;
            if (minecraft.getConnection() != null) {
                minecraft.getConnection().getConnection().disconnect(net.minecraft.network.chat.Component.literal("retrying"));
            }
            minecraft.setScreen(new TitleScreen());
            return;
        }
        if (!connecting && minecraft.screen instanceof TitleScreen && ticks > 600) {
            connecting = true;
            connectingTicks = 0;
            attempts++;
            LOGGER.info("[ConfigSyncTest] Connecting to {} (attempt {})", parts[0], attempts);
            ConnectScreen.startConnecting(minecraft.screen, minecraft, ServerAddress.parseString(parts[0]),
                    new ServerData("Retrofit config sync test", parts[0], false), false);
            return;
        }
        if (!agreed && minecraft.screen instanceof ConfirmScreen confirm) {
            for (var child : confirm.children()) {
                if (child instanceof Button button && button.getMessage().getString().equals("Use the server's")) {
                    agreed = true;
                    LOGGER.info("[ConfigSyncTest] Asked: {}", confirm.getNarrationMessage().getString());
                    button.onPress();
                    return;
                }
            }
        }
        if (agreed && minecraft.screen instanceof DisconnectedScreen) {
            done = true;
            try {
                Path mine = FMLPaths.GAMEDIR.get().resolve("retrofit").resolve(parts[1]);
                boolean same = Files.isRegularFile(mine) && Arrays.equals(Files.readAllBytes(mine), Files.readAllBytes(Path.of(parts[2])));
                LOGGER.info("[ConfigSyncTest] {}: the client's {} {} the server's", same ? "PASSED" : "FAILED", parts[1], same ? "is" : "is not");
            } catch (java.io.IOException e) {
                LOGGER.info("[ConfigSyncTest] FAILED: {}", e.toString());
            }
            minecraft.stop();
        }
        if (ticks > 8000) {
            done = true;
            LOGGER.info("[ConfigSyncTest] FAILED: never asked (agreed {})", agreed);
            minecraft.stop();
        }
    }
}
