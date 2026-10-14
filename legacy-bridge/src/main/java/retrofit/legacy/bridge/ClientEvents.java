package retrofit.legacy.bridge;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import retrofit.legacy.gl.Capture;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Client only. Mods' key bindings, HUD drawing (RenderGameOverlayEvent) and world-space drawing
 * (RenderWorldLastEvent):
 *
 * - Key bindings mods registered (ClientRegistry.registerKeyBinding) become 1.20.1 key mappings;
 *   each client tick their state is copied back and FML's KeyInputEvent fires when one was pressed.
 * - HUD and world drawing run through the GL emulation into the capture, which the host draws
 *   (on the HUD, and in the world relative to the camera). Only done while a mod listens.
 */
public final class ClientEvents {
    private static final Set<String> VANILLA_KEYS = new HashSet<String>(Arrays.asList(
            "key.attack", "key.use", "key.forward", "key.left", "key.back", "key.right", "key.jump", "key.sneak", "key.drop",
            "key.inventory", "key.chat", "key.playerlist", "key.pickItem", "key.command", "key.screenshot",
            "key.togglePerspective", "key.smoothCamera", "key.sprint", "key.streamStartStop", "key.streamPauseUnpause",
            "key.streamCommercial", "key.streamToggleMic", "key.fullscreen"));
    private static List<KeyBinding> modKeys;
    private static Field pressed;
    private static Field pressTime;
    private static int busId = -1;

    private ClientEvents() {
    }

    // ---- Key bindings ----

    private static List<KeyBinding> keys() {
        if (modKeys == null) {
            List<KeyBinding> keys = new ArrayList<KeyBinding>();
            for (KeyBinding key : Minecraft.func_71410_x().field_71474_y.field_74324_K) { // gameSettings.keyBindings
                if (key != null && !VANILLA_KEYS.contains(key.func_151464_g()) && !key.func_151464_g().startsWith("key.hotbar.")) {
                    keys.add(key);
                }
            }
            modKeys = keys;
        }
        return modKeys;
    }

    /** {description key, category key, LWJGL 2 key code} of each mod key binding (codes below 0 are mouse buttons). */
    public static Object[][] modKeyBindings() {
        List<Object[]> result = new ArrayList<Object[]>();
        for (KeyBinding key : keys()) {
            result.add(new Object[]{key.func_151464_g(), key.func_151466_e(), key.func_151463_i()}); // getKeyDescription, getKeyCategory, getKeyCode
        }
        return result.toArray(new Object[0][]);
    }

    /** One client tick of a mod key: held state and presses since the last tick; true if it was pressed. */
    public static boolean setKeyState(int index, boolean down, int presses) throws ReflectiveOperationException {
        KeyBinding key = keys().get(index);
        if (pressed == null) {
            pressed = KeyBinding.class.getDeclaredField("field_74513_e");
            pressed.setAccessible(true);
            pressTime = KeyBinding.class.getDeclaredField("field_151474_i");
            pressTime.setAccessible(true);
        }
        pressed.setBoolean(key, down);
        if (presses > 0) {
            pressTime.setInt(key, pressTime.getInt(key) + presses);
        }
        return presses > 0;
    }

    /** FML's KeyInputEvent: mods check their key bindings' isPressed here. */
    public static void fireKeyInput() {
        FMLCommonHandler.instance().fireKeyInput();
    }

    // ---- Drawing ----

    private static boolean listened(Event event) throws ReflectiveOperationException {
        if (busId < 0) {
            Field field = EventBus.class.getDeclaredField("busID");
            field.setAccessible(true);
            busId = field.getInt(MinecraftForge.EVENT_BUS);
        }
        return event.getListenerList().getListeners(busId).length > 0;
    }

    /** Whether any mod listens for HUD (0) or world-last (1) drawing. */
    public static boolean listensFor(int kind) throws ReflectiveOperationException {
        // Listeners belong to the event class they subscribe to: mods subscribe to RenderGameOverlayEvent.Post.
        return kind == 0 ? listened(new RenderGameOverlayEvent.Post()) || listened(new RenderGameOverlayEvent())
                : listened(new RenderWorldLastEvent());
    }

    /**
     * The HUD as mods draw on it after 1.7.10's own (RenderGameOverlayEvent.Post for ALL, HOTBAR
     * and EXPERIENCE), at the host's GUI scale; returns the captured vertex count. Hold Bridge.LOCK.
     */
    public static int drawOverlay(float partialTicks, int windowWidth, int windowHeight, int guiScale) {
        Minecraft minecraft = Minecraft.func_71410_x();
        LegacyRendering.ensureEnvironment();
        minecraft.field_71443_c = windowWidth;
        minecraft.field_71440_d = windowHeight;
        minecraft.field_71474_y.field_74335_Z = guiScale; // guiScale: ScaledResolution matches the host
        ScaledResolution resolution = new ScaledResolution(minecraft, windowWidth, windowHeight);
        int mouseX = retrofit.legacy.gl.Mouse.getX() * resolution.func_78326_a() / Math.max(1, windowWidth);
        int mouseY = resolution.func_78328_b() - retrofit.legacy.gl.Mouse.getY() * resolution.func_78328_b() / Math.max(1, windowHeight) - 1;
        RenderGameOverlayEvent parent = new RenderGameOverlayEvent(partialTicks, resolution, mouseX, mouseY);
        Capture.begin();
        try {
            for (RenderGameOverlayEvent.ElementType type : new RenderGameOverlayEvent.ElementType[]{
                    RenderGameOverlayEvent.ElementType.HOTBAR, RenderGameOverlayEvent.ElementType.EXPERIENCE, RenderGameOverlayEvent.ElementType.ALL}) {
                MinecraftForge.EVENT_BUS.post(new RenderGameOverlayEvent.Post(parent, type));
            }
        } finally {
            LegacyRendering.resetTessellatorIfDrawing();
        }
        return Capture.vertexCount();
    }

    /** RenderWorldLastEvent, camera-relative as 1.7.10 drew it; returns the captured vertex count. Hold Bridge.LOCK. */
    public static int drawWorldLast(float partialTicks) {
        LegacyRendering.ensureEnvironment();
        Capture.begin();
        try {
            MinecraftForge.EVENT_BUS.post(new RenderWorldLastEvent(Minecraft.func_71410_x().field_71438_f, partialTicks)); // renderGlobal
        } finally {
            LegacyRendering.resetTessellatorIfDrawing();
        }
        return Capture.vertexCount();
    }
}
