package retrofit.legacy.bridge;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
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

    /**
     * 1.7.10's own key bindings (forward, left, back, right, jump, sneak, sprint, attack, use, drop,
     * in that order) follow the host's: held state, presses since the last tick and key code
     * (LWJGL 2's; 0 if bound to something 1.7.10 can't name). Mods read them directly: Galacticraft
     * launches a rocket on jump, jetpacks and flying armor rise on jump and sink on sneak. The local
     * player's movementInput gets the host's too (forward and strafe impulse, jump, sneak).
     */
    public static void setVanillaKeys(boolean[] down, int[] presses, int[] codes, float forward, float strafe, boolean jump, boolean sneak)
            throws ReflectiveOperationException {
        Minecraft minecraft = Minecraft.func_71410_x();
        if (minecraft.field_71439_g != null && minecraft.field_71439_g.field_71158_b != null) { // thePlayer.movementInput
            net.minecraft.util.MovementInput input = minecraft.field_71439_g.field_71158_b;
            input.field_78900_b = forward; // moveForward
            input.field_78902_a = strafe;  // moveStrafe
            input.field_78901_c = jump;
            input.field_78899_d = sneak;
        }
        net.minecraft.client.settings.GameSettings settings = minecraft.field_71474_y; // gameSettings
        KeyBinding[] keys = {settings.field_74351_w, settings.field_74370_x, settings.field_74368_y, settings.field_74366_z,
                settings.field_74314_A, settings.field_74311_E, settings.field_151444_V, settings.field_74312_F, settings.field_74313_G,
                settings.field_74316_C};
        if (pressed == null) {
            pressed = KeyBinding.class.getDeclaredField("field_74513_e");
            pressed.setAccessible(true);
            pressTime = KeyBinding.class.getDeclaredField("field_151474_i");
            pressTime.setAccessible(true);
        }
        for (int i = 0; i < keys.length && i < down.length; i++) {
            pressed.setBoolean(keys[i], down[i]);
            if (presses[i] > 0) {
                pressTime.setInt(keys[i], pressTime.getInt(keys[i]) + presses[i]);
            }
            if (keys[i].func_151463_i() != codes[i]) { // getKeyCode
                keys[i].func_151462_b(codes[i]); // setKeyCode (GameSettings.isKeyDown reads the keyboard by it)
                KeyBinding.func_74508_b(); // resetKeyBindingArrayAndHash
            }
        }
    }

    /** FML's KeyInputEvent: mods check their key bindings' isPressed here. */
    public static void fireKeyInput() {
        FMLCommonHandler.instance().fireKeyInput();
    }

    // ---- Tooltips ----

    private static Boolean tooltipListened;

    /**
     * Tooltip lines legacy mods add to a stack: the item's own addInformation (for mod items) and
     * ItemTooltipEvent handlers (for every item; Thaumcraft adds lines to vanilla items too), after
     * the name line the host already shows. Formatting codes are kept.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static String[] tooltipLines(Object legacyStack, Object legacyPlayer, boolean advanced, boolean modItem) {
        ItemStack stack = (ItemStack) legacyStack;
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        if (tooltipListened == null) {
            tooltipListened = Events.listenedTo(ItemTooltipEvent.class);
        }
        if (!modItem && !tooltipListened) {
            return new String[0];
        }
        List lines = new ArrayList();
        lines.add(stack.func_82833_r()); // getDisplayName
        if (modItem) {
            stack.func_77973_b().func_77624_a(stack, player, lines, advanced); // getItem().addInformation
        }
        if (tooltipListened) {
            MinecraftForge.EVENT_BUS.post(new ItemTooltipEvent(stack, player, lines, advanced));
        }
        String[] result = new String[Math.max(0, lines.size() - 1)];
        for (int i = 1; i < lines.size(); i++) {
            result[i - 1] = String.valueOf(lines.get(i));
        }
        return result;
    }

    // ---- What the player looks at ----

    /** Minecraft.objectMouseOver (and pointedEntity) from the host: 0 nothing, 1 a block, 2 an entity. */
    public static void setMouseOver(int type, int x, int y, int z, int side, double hitX, double hitY, double hitZ, Object entity) {
        Minecraft minecraft = Minecraft.func_71410_x();
        net.minecraft.util.Vec3 hit = net.minecraft.util.Vec3.func_72443_a(hitX, hitY, hitZ); // createVectorHelper
        if (type == 1) {
            minecraft.field_71476_x = new net.minecraft.util.MovingObjectPosition(x, y, z, side, hit); // objectMouseOver
            minecraft.field_147125_j = null; // pointedEntity
        } else if (type == 2) {
            net.minecraft.util.MovingObjectPosition position = new net.minecraft.util.MovingObjectPosition((net.minecraft.entity.Entity) entity, hit);
            minecraft.field_71476_x = position;
            minecraft.field_147125_j = (net.minecraft.entity.Entity) entity;
        } else {
            minecraft.field_71476_x = null;
            minecraft.field_147125_j = null;
        }
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
        if (minecraft.field_71439_g == null || minecraft.field_71441_e == null) { // thePlayer, theWorld: HUD code reads both (none while changing worlds)
            return 0;
        }
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

    /**
     * FML's RenderTickEvent end, captured: on 1.7.10 it came after the HUD and screens were drawn,
     * in their screen space, and mods draw there (Thaumcraft's aspect animations and research
     * popups). Returns the captured vertex count; the host draws it as it draws the HUD. Hold Bridge.LOCK.
     */
    public static int drawRenderTickEnd(float partialTicks, int windowWidth, int windowHeight, int guiScale) {
        Minecraft minecraft = Minecraft.func_71410_x();
        LegacyRendering.ensureEnvironment();
        minecraft.field_71443_c = windowWidth;
        minecraft.field_71440_d = windowHeight;
        minecraft.field_71474_y.field_74335_Z = guiScale; // guiScale: ScaledResolution matches the host
        Capture.begin();
        try {
            Events.renderTick(false, partialTicks);
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

    // ---- DrawBlockHighlightEvent ----

    /** Whether any mod listens for DrawBlockHighlightEvent. */
    public static boolean listensForBlockHighlight() {
        return Events.listenedTo(net.minecraftforge.client.event.DrawBlockHighlightEvent.class);
    }

    /**
     * DrawBlockHighlightEvent for the block the player looks at, in camera space as 1.7.10 fired it
     * (mods draw their own outlines and cancel the vanilla one). Returns {vertices, cancelled}.
     * Hold Bridge.LOCK.
     */
    public static Object[] drawBlockHighlight(int x, int y, int z, int side, double hitX, double hitY, double hitZ, float partialTicks) {
        LegacyRendering.ensureEnvironment();
        Minecraft minecraft = Minecraft.func_71410_x();
        EntityPlayer player = minecraft.field_71439_g; // thePlayer
        if (player == null) {
            return new Object[]{0, false};
        }
        net.minecraft.util.MovingObjectPosition target = new net.minecraft.util.MovingObjectPosition(x, y, z, side,
                net.minecraft.util.Vec3.func_72443_a(hitX, hitY, hitZ)); // createVectorHelper
        Capture.begin();
        boolean cancelled;
        try {
            cancelled = MinecraftForge.EVENT_BUS.post(new net.minecraftforge.client.event.DrawBlockHighlightEvent(
                    minecraft.field_71438_f, player, target, 0, player.func_71045_bC(), partialTicks)); // renderGlobal, getCurrentEquippedItem
        } finally {
            LegacyRendering.resetTessellatorIfDrawing();
        }
        return new Object[]{Capture.vertexCount(), cancelled};
    }

    // ---- Leashes ----

    /**
     * A client's copy of a legacy creature leashed to the local player or let go (the host tracks
     * leashes; the legacy renderer draws the lead from its own leash state).
     */
    public static void setClientLeash(Object entity, boolean toLocalPlayer) {
        if (!(entity instanceof net.minecraft.entity.EntityLiving)) {
            return;
        }
        net.minecraft.entity.EntityLiving living = (net.minecraft.entity.EntityLiving) entity;
        EntityPlayer player = Minecraft.func_71410_x().field_71439_g; // thePlayer
        if (toLocalPlayer && player != null) {
            if (living.func_110166_bE() != player) { // getLeashedToEntity
                living.func_110162_b(player, false); // setLeashedToEntity, no packet
            }
        } else if (living.func_110167_bD()) { // getLeashed
            living.func_110160_i(false, false); // clearLeashed: no packet, no lead
        }
    }

    // ---- RenderPlayerEvent ----

    /** The GL matrices RenderPlayerEvent.Pre's listeners left, for Post (mods push in Pre and pop in Post). */
    private static Object playerMatrices;

    /** Whether any mod listens for RenderPlayerEvent (Pre, Post or Specials). */
    public static boolean listensForPlayerRendering() {
        return Events.listenedTo(RenderPlayerEvent.Pre.class) || Events.listenedTo(RenderPlayerEvent.Post.class)
                || Events.listenedTo(RenderPlayerEvent.Specials.Pre.class) || Events.listenedTo(RenderPlayerEvent.Specials.Post.class);
    }

    /**
     * RenderPlayerEvent around the host drawing the local player (Minecraft.thePlayer). Hold Bridge.LOCK.
     * Stage 0, Pre: in camera space, as 1.7.10 fired it; returns {vertices, cancelled, the modelview
     * matrix its listeners left (column-major, camera space)}. Stage 1, Specials.Pre and .Post: in
     * the player model's space, with {@code parts} the host model's pose (see posePlayerModel).
     * Stage 2, Post: in camera space with the matrices Pre left. Stages 1 and 2 return {vertices}.
     */
    public static Object[] renderPlayer(int stage, float partialTicks, float[] parts) {
        LegacyRendering.ensureEnvironment();
        Minecraft minecraft = Minecraft.func_71410_x();
        EntityPlayer player = minecraft.field_71439_g; // thePlayer
        if (player == null) {
            return new Object[]{0, false, null};
        }
        RenderPlayer renderer = (RenderPlayer) RenderManager.field_78727_a.func_78713_a(player); // getEntityRenderObject
        Capture.begin();
        try {
            if (stage == 0) {
                RenderPlayerEvent.Pre pre = new RenderPlayerEvent.Pre(player, renderer, partialTicks);
                boolean cancelled = MinecraftForge.EVENT_BUS.post(pre);
                playerMatrices = retrofit.legacy.gl.GL11.saveMatrices();
                return new Object[]{Capture.vertexCount(), cancelled, retrofit.legacy.gl.GL11.modelviewMatrix()};
            }
            if (stage == 1) {
                posePlayerModel(renderer.field_77109_a, parts); // modelBipedMain
                MinecraftForge.EVENT_BUS.post(new RenderPlayerEvent.Specials.Pre(player, renderer, partialTicks));
                MinecraftForge.EVENT_BUS.post(new RenderPlayerEvent.Specials.Post(player, renderer, partialTicks));
            } else {
                if (playerMatrices != null) {
                    retrofit.legacy.gl.GL11.restoreMatrices(playerMatrices);
                    playerMatrices = null;
                }
                MinecraftForge.EVENT_BUS.post(new RenderPlayerEvent.Post(player, renderer, partialTicks));
            }
        } finally {
            LegacyRendering.resetTessellatorIfDrawing();
        }
        return new Object[]{Capture.vertexCount()};
    }

    /**
     * The legacy player model posed as the host's: {@code parts} holds {xRot, yRot, zRot, x, y, z}
     * for the head, hat, body, right arm, left arm, right leg and left leg, then sneaking (1/0).
     * Mods attach things to the parts (ModelRenderer.postRender) and read their angles.
     */
    private static void posePlayerModel(ModelBiped model, float[] parts) {
        if (parts == null || parts.length < 43) {
            return;
        }
        ModelRenderer[] renderers = {model.field_78116_c, model.field_78114_d, model.field_78115_e, model.field_78112_f,
                model.field_78113_g, model.field_78123_h, model.field_78124_i}; // head, headwear, body, arms, legs
        for (int i = 0; i < renderers.length; i++) {
            ModelRenderer part = renderers[i];
            part.field_78795_f = parts[i * 6];     // rotateAngleX
            part.field_78796_g = parts[i * 6 + 1]; // rotateAngleY
            part.field_78808_h = parts[i * 6 + 2]; // rotateAngleZ
            part.field_78800_c = parts[i * 6 + 3]; // rotationPointX
            part.field_78797_d = parts[i * 6 + 4]; // rotationPointY
            part.field_78798_e = parts[i * 6 + 5]; // rotationPointZ
        }
        model.field_78117_n = parts[42] != 0; // isSneak
    }
}
