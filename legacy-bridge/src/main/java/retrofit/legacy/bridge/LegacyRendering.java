package retrofit.legacy.bridge;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.client.IItemRenderer;
import net.minecraftforge.client.MinecraftForgeClient;
import retrofit.legacy.gl.Capture;
import retrofit.legacy.gl.GL11;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs legacy rendering code into the GL emulation's {@link Capture}: static block models through
 * RenderBlocks (vanilla render types and mods' block render handlers), tile entity special
 * renderers, and Forge item renderers. The host then draws the captured quads.
 *
 * Callers hold {@link Bridge#LOCK} from the call until they have read the capture. Client only.
 */
public final class LegacyRendering {
    private static final String BLOCK_ATLAS = "minecraft:textures/atlas/blocks.png";
    private static final Map<Class<?>, Boolean> HAS_TESR = new HashMap<Class<?>, Boolean>();

    private static RenderBlocks renderBlocks;
    private static boolean environmentReady;
    private static TextureManager textures;
    private static FontRenderer font;
    private static GameSettings settings;
    private static CameraEntity camera;

    private LegacyRendering() {
    }

    /**
     * Renders a block as 1.7.10's chunk renderer would, at the origin of an otherwise empty
     * world; returns the number of captured vertices (positions in block space, UVs in the
     * block atlas).
     */
    public static int bakeBlock(Object block, int meta) {
        World world = IsolatedHost.with(block, meta);
        if (renderBlocks == null) {
            renderBlocks = new RenderBlocks(world);
        }
        renderBlocks.field_147845_a = world; // blockAccess
        Capture.begin();
        GL11.bindLegacyTexture(BLOCK_ATLAS);
        Tessellator tessellator = Tessellator.field_78398_a;
        try {
            tessellator.func_78382_b(); // startDrawingQuads
            renderBlocks.func_147805_b((Block) block, 0, 0, 0); // renderBlockByRenderType
            tessellator.func_78381_a(); // draw
        } catch (RuntimeException e) {
            resetTessellator();
            throw e;
        }
        return Capture.vertexCount();
    }

    /** Whether a tile entity has a special renderer (cached per class). */
    public static boolean hasSpecialRenderer(Object tileEntity) {
        Class<?> type = tileEntity.getClass();
        Boolean known = HAS_TESR.get(type);
        if (known == null) {
            known = dispatcher().func_147547_b((TileEntity) tileEntity) != null; // getSpecialRenderer
            HAS_TESR.put(type, known);
        }
        return known;
    }

    /** Runs a tile entity's special renderer at the origin; returns the number of captured vertices. */
    public static int renderTileEntity(Object tileEntity, float partialTicks) {
        TileEntitySpecialRenderer renderer = dispatcher().func_147547_b((TileEntity) tileEntity);
        Capture.begin();
        if (renderer == null) {
            return 0;
        }
        GL11.bindLegacyTexture(BLOCK_ATLAS);
        try {
            renderer.func_147500_a((TileEntity) tileEntity, 0, 0, 0, partialTicks); // renderTileEntityAt
        } catch (RuntimeException e) {
            resetTessellator();
            throw e;
        }
        return Capture.vertexCount();
    }

    /** Whether a Forge item renderer handles this item for a render type (INVENTORY, ENTITY, EQUIPPED, EQUIPPED_FIRST_PERSON). */
    public static boolean hasItemRenderer(Object item, int meta, String type) {
        ItemStack stack = new ItemStack((Item) item, 1, meta);
        IItemRenderer.ItemRenderType renderType = IItemRenderer.ItemRenderType.valueOf(type);
        return MinecraftForgeClient.getItemRenderer(stack, renderType) != null;
    }

    /** Runs an item's Forge item renderer; returns the number of captured vertices. */
    public static int renderItem(Object item, int meta, String type) {
        ItemStack stack = new ItemStack((Item) item, 1, meta);
        IItemRenderer.ItemRenderType renderType = IItemRenderer.ItemRenderType.valueOf(type);
        IItemRenderer renderer = MinecraftForgeClient.getItemRenderer(stack, renderType);
        Capture.begin();
        if (renderer == null) {
            return 0;
        }
        GL11.bindLegacyTexture(BLOCK_ATLAS);
        if (renderBlocks == null) {
            renderBlocks = new RenderBlocks();
        }
        Object[] data = renderType == IItemRenderer.ItemRenderType.INVENTORY
                ? new Object[]{renderBlocks} : new Object[]{renderBlocks, null};
        try {
            renderer.renderItem(renderType, stack, data);
        } catch (RuntimeException e) {
            resetTessellator();
            throw e;
        }
        return Capture.vertexCount();
    }

    /**
     * Places the legacy viewer at the host camera, as 1.7.10 did at the start of each frame
     * (RenderManager and TileEntityRendererDispatcher.cacheActiveRenderInfo). Renderers use it to
     * face text and sprites towards the player and to cull by distance.
     */
    public static void prepareFrame(Object legacyWorld, double x, double y, double z, float yaw, float pitch, float partialTicks) {
        environment();
        World world = legacyWorld != null ? (World) legacyWorld : IsolatedHost.with(net.minecraft.init.Blocks.field_150350_a, 0);
        if (camera == null || camera.field_70170_p != world) { // worldObj
            camera = new CameraEntity(world);
        }
        camera.func_70012_b(x, y, z, yaw, pitch); // setLocationAndAngles
        camera.field_70126_B = yaw;   // prevRotationYaw
        camera.field_70127_C = pitch; // prevRotationPitch
        Minecraft.func_71410_x().field_71451_h = camera; // getMinecraft().renderViewEntity
        RenderManager.field_78727_a.func_147938_a(world, textures, font, camera, null, settings, partialTicks); // cacheActiveRenderInfo
        TileEntityRendererDispatcher.field_147556_a.func_147542_a(world, textures, font, camera, partialTicks);
    }

    private static TileEntityRendererDispatcher dispatcher() {
        environment();
        return TileEntityRendererDispatcher.field_147556_a; // instance
    }

    /**
     * What legacy renderers expect from the client: a texture manager (its bindTexture feeds
     * the GL emulation), a font renderer (drawing through the emulation), a skin manager (its
     * network fetch is disabled; see HeadlessTransformer) and a viewer at the origin until the
     * host reports its camera.
     */
    private static void environment() {
        if (environmentReady) {
            return;
        }
        environmentReady = true;
        try {
            Minecraft minecraft = Minecraft.func_71410_x(); // getMinecraft
            textures = (TextureManager) allocate(TextureManager.class);
            settings = minecraft.field_71474_y != null ? minecraft.field_71474_y : (GameSettings) allocate(GameSettings.class);
            minecraft.field_71446_o = textures; // renderEngine
            TileEntityRendererDispatcher.field_147556_a.field_147553_e = textures;
            RenderManager.field_78727_a.field_78724_e = textures;
            Field skins = Minecraft.class.getDeclaredField("field_152350_aA"); // skinManager
            skins.setAccessible(true);
            skins.set(minecraft, allocate(SkinManager.class));
            font = new FontRenderer(settings, new ResourceLocation("textures/font/ascii.png"), textures, false);
            minecraft.field_71466_p = font; // fontRenderer
            minecraft.field_71464_q = font; // standardGalacticFontRenderer
            Field fontField = TileEntityRendererDispatcher.class.getDeclaredField("field_147557_n"); // fontRenderer
            fontField.setAccessible(true);
            fontField.set(TileEntityRendererDispatcher.field_147556_a, font);
        } catch (Throwable t) {
            System.out.println("[Retrofit] Legacy render environment incomplete: " + t);
        }
        prepareFrame(null, 0, 0, 0, 0, 0, 0);
    }

    /** A failed renderer can leave the shared Tessellator mid-draw, which would break every later draw. */
    private static void resetTessellator() {
        try {
            Field drawing = Tessellator.class.getDeclaredField("field_78415_z"); // isDrawing
            drawing.setAccessible(true);
            drawing.setBoolean(Tessellator.field_78398_a, false);
        } catch (ReflectiveOperationException ignored) {
            // Nothing more can be done; the next startDrawing will report it.
        }
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
    }
}
