package retrofit.forge.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import retrofit.forge.RetrofitMod;
import retrofit.forge.world.LegacyBridge;

import java.util.ArrayList;
import java.util.List;

/**
 * Mods' key bindings as 1.20.1 key mappings (rebindable in Controls, under the mod's category),
 * and mods' HUD and world-space drawing (see the legacy bridge's ClientEvents).
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class LegacyClientInput {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<KeyMapping> MAPPINGS = new ArrayList<>();
    private static int frames;
    private static boolean hud;
    private static boolean worldLast;
    private static boolean failed;

    private LegacyClientInput() {
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        if (RetrofitMod.runtime() == null) {
            return;
        }
        try {
            LegacyBridge.init(RetrofitMod.runtime());
            Object[][] keys = LegacyBridge.call("modKeyBindings");
            for (Object[] key : keys) {
                int code = (Integer) key[2];
                if (code < 0 && (code + 100 < 0 || code + 100 > org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LAST)) {
                    code = 0; // a mouse button GLFW doesn't have: unbound (the player can bind it in Controls)
                }
                KeyMapping mapping = code < 0
                        ? new KeyMapping((String) key[0], InputConstants.Type.MOUSE, code + 100, (String) key[1]) // 1.7.10: -100 + button
                        : new KeyMapping((String) key[0], InputConstants.Type.KEYSYM, code == 0 ? -1 : LegacyKeys.toGlfw(code), (String) key[1]);
                event.register(mapping);
                MAPPINGS.add(mapping);
            }
            if (!MAPPINGS.isEmpty()) {
                LOGGER.info("Legacy key bindings: {}", MAPPINGS.size());
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("Legacy key bindings are unavailable", e);
        }
        MinecraftForge.EVENT_BUS.register(Game.class);
    }

    /** Game-bus handlers (registered once the mod-bus setup above ran). */
    public static final class Game {
        private Game() {
        }

        private static boolean keyboardInstalled;

        /** Presses of the vanilla keys 1.7.10 has, counted apart from the host's own (see onKey). */
        private static final int[] VANILLA_PRESSES = new int[10];

        private static KeyMapping[] vanillaKeys() {
            var options = Minecraft.getInstance().options;
            return new KeyMapping[]{options.keyUp, options.keyLeft, options.keyDown, options.keyRight, options.keyJump,
                    options.keyShift, options.keySprint, options.keyAttack, options.keyUse, options.keyDrop};
        }

        /** Counts presses of the vanilla keys without consuming the host's own clicks. */
        @SubscribeEvent
        public static void onKey(net.minecraftforge.client.event.InputEvent.Key event) {
            if (event.getAction() == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
                countPress(InputConstants.Type.KEYSYM.getOrCreate(event.getKey()));
            }
        }

        @SubscribeEvent
        public static void onMouseButton(net.minecraftforge.client.event.InputEvent.MouseButton.Pre event) {
            if (event.getAction() == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
                countPress(InputConstants.Type.MOUSE.getOrCreate(event.getButton()));
            }
        }

        private static void countPress(InputConstants.Key key) {
            if (Minecraft.getInstance().screen != null) {
                return;
            }
            KeyMapping[] vanilla = vanillaKeys();
            for (int i = 0; i < vanilla.length; i++) {
                if (vanilla[i].getKey().equals(key)) {
                    VANILLA_PRESSES[i]++;
                }
            }
        }

        private static void mirrorVanillaKeys() {
            KeyMapping[] vanilla = vanillaKeys();
            boolean[] down = new boolean[vanilla.length];
            int[] presses = new int[vanilla.length];
            int[] codes = new int[vanilla.length];
            for (int i = 0; i < vanilla.length; i++) {
                down[i] = vanilla[i].isDown();
                presses[i] = VANILLA_PRESSES[i];
                VANILLA_PRESSES[i] = 0;
                InputConstants.Key key = vanilla[i].getKey();
                codes[i] = key.getType() == InputConstants.Type.MOUSE ? key.getValue() - 100
                        : key.getType() == InputConstants.Type.KEYSYM ? LegacyKeys.toLegacy(key.getValue()) : 0;
            }
            var input = Minecraft.getInstance().player.input;
            LegacyBridge.call("setVanillaKeys", down, presses, codes, input.forwardImpulse, input.leftImpulse, input.jumping, input.shiftKeyDown);
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.START || Minecraft.getInstance().player == null || !LegacyBridge.isReady()) {
                return;
            }
            if (!keyboardInstalled) {
                keyboardInstalled = true;
                LegacyKeys.install(); // mods poll the keyboard in the world too (jetpacks: GameSettings.isKeyDown), not only in GUIs
            }
            try {
                mirrorVanillaKeys();
            } catch (RuntimeException e) {
                LegacyBridge.reportClientFailure("vanilla key bindings", e);
            }
            if (MAPPINGS.isEmpty()) {
                return;
            }
            try {
                boolean any = false;
                for (int i = 0; i < MAPPINGS.size(); i++) {
                    KeyMapping mapping = MAPPINGS.get(i);
                    int presses = 0;
                    while (mapping.consumeClick()) {
                        presses++;
                    }
                    any |= LegacyBridge.<Boolean>call("setKeyState", i, mapping.isDown(), presses);
                }
                if (any) {
                    LegacyBridge.call("fireKeyInput");
                }
            } catch (RuntimeException e) {
                LegacyBridge.reportClientFailure("key binding handler", e);
            }
        }

        /** Whether mods draw on the HUD or in the world; checked now and then (listeners rarely change). */
        private static void refresh() {
            if (frames++ % 200 == 0) {
                try {
                    hud = LegacyBridge.call("listensFor", 0);
                    worldLast = LegacyBridge.call("listensFor", 1);
                } catch (RuntimeException e) {
                    hud = worldLast = false;
                }
            }
        }

        @SubscribeEvent
        public static void onRenderHud(RenderGuiEvent.Post event) {
            if (RetrofitMod.runtime() == null || failed || Minecraft.getInstance().player == null) {
                return;
            }
            refresh();
            if (!hud) {
                return;
            }
            var window = Minecraft.getInstance().getWindow();
            try {
                synchronized (LegacyBridge.renderLock()) {
                    int vertices = LegacyBridge.call("drawOverlay", event.getPartialTick(), window.getWidth(), window.getHeight(),
                            (int) window.getGuiScale());
                    Object[] markers = LegacyBridge.call("guiMarkers");
                    LegacyGuiRenderer.draw(event.getGuiGraphics(), vertices, markers, null);
                }
            } catch (Throwable e) {
                failed = true;
                LegacyBridge.reportClientFailure("HUD drawing", e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e));
            }
        }

        @SubscribeEvent
        public static void onRenderLevel(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || RetrofitMod.runtime() == null || failed || !worldLast) {
                return;
            }
            try {
                MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
                synchronized (LegacyBridge.renderLock()) {
                    int vertices = LegacyBridge.call("drawWorldLast", event.getPartialTick());
                    LegacyCapture.draw(event.getPoseStack().last(), buffers, vertices, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                }
                buffers.endBatch();
            } catch (Throwable e) {
                failed = true;
                LegacyBridge.reportClientFailure("world drawing", e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e));
            }
        }
    }
}
