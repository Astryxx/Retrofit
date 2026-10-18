package retrofit.forge.test;

import com.mojang.logging.LogUtils;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import retrofit.forge.RetrofitMod;

/**
 * Development test runs (mod sweep, world test) that stay out of the way of whoever uses the PC:
 * the game never captures the mouse, keeps running while unfocused, and on Windows hands the
 * keyboard focus back to the window that had it and sits behind it. Screenshots still work
 * (the window keeps rendering). On with -Dretrofit.background=true.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, value = Dist.CLIENT)
public final class BackgroundWindow {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final boolean ENABLED = Boolean.getBoolean("retrofit.background");
    private static int ticks;

    private BackgroundWindow() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.options.pauseOnLostFocus = false;
        if (minecraft.mouseHandler.isMouseGrabbed()) {
            minecraft.mouseHandler.releaseMouse(); // never take the cursor
        }
        // Give the focus back once the window is up, and again shortly after (world loading can raise it).
        if (++ticks == 5 || ticks == 200) {
            sendToBack(minecraft);
        }
    }

    /** During loading already (the window exists from the start): give the focus back right away. */
    @Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Loading {
        private Loading() {
        }

        @SubscribeEvent
        public static void onClientSetup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
            if (ENABLED) {
                event.enqueueWork(() -> sendToBack(Minecraft.getInstance()));
            }
        }
    }

    private static void sendToBack(Minecraft minecraft) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        try {
            long glfw = minecraft.getWindow().getWindow();
            WinDef.HWND ours = new WinDef.HWND(new com.sun.jna.Pointer(org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(glfw)));
            // The window that had the focus before: the next visible, not minimized, titled one below ours.
            WinDef.HWND next = User32.INSTANCE.GetWindow(ours, new WinDef.DWORD(WinUser.GW_HWNDNEXT));
            while (next != null && !(User32.INSTANCE.IsWindowVisible(next) && !isMinimized(next) && User32.INSTANCE.GetWindowTextLength(next) > 0)) {
                next = User32.INSTANCE.GetWindow(next, new WinDef.DWORD(WinUser.GW_HWNDNEXT));
            }
            User32.INSTANCE.SetWindowPos(ours, new WinDef.HWND(new com.sun.jna.Pointer(1)), 0, 0, 0, 0, // HWND_BOTTOM
                    WinUser.SWP_NOMOVE | WinUser.SWP_NOSIZE | 0x0010); // SWP_NOACTIVATE
            if (next != null) {
                User32.INSTANCE.SetForegroundWindow(next);
            }
        } catch (Throwable t) {
            LOGGER.info("Could not move the test window to the background: {}", t.toString());
        }
    }

    private static boolean isMinimized(WinDef.HWND window) {
        return (User32.INSTANCE.GetWindowLong(window, WinUser.GWL_STYLE) & 0x20000000) != 0; // WS_MINIMIZE
    }
}
