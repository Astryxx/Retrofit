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
            renderInPasses((Block) block, 0, 0, 0);
            tessellator.func_78381_a(); // draw
        } catch (RuntimeException e) {
            resetTessellator();
            throw e;
        }
        return Capture.vertexCount();
    }

    /**
     * A block as 1.7.10 drew it as an item (RenderBlocks.renderBlockAsItem: custom renderers'
     * renderInventoryBlock), centred on the origin; returns the vertex count. Hold Bridge.LOCK.
     */
    public static int renderBlockItem(Object block, int meta) {
        World world = IsolatedHost.with(block, meta);
        if (renderBlocks == null) {
            renderBlocks = new RenderBlocks(world);
        }
        renderBlocks.field_147845_a = world; // blockAccess
        Capture.begin();
        GL11.bindLegacyTexture(BLOCK_ATLAS);
        try {
            renderBlocks.func_147800_a((Block) block, meta, 1.0f); // renderBlockAsItem
        } catch (RuntimeException e) {
            resetTessellator();
            throw e;
        }
        return Capture.vertexCount();
    }

    /** Whether 1.7.10 drew a block's item as a 3D block (RenderBlocks.renderItemIn3d), not as a flat icon. */
    public static boolean blockItemIn3d(Object block) {
        return RenderBlocks.func_147739_a(((Block) block).func_149645_b()); // renderItemIn3d(getRenderType)
    }

    /** Which IItemRenderer helpers a renderer asks for: bit n = ItemRendererHelper.values()[n]; bit 30 isFull3D, bit 29 shouldRotateAroundWhenRendering. */
    public static int itemRendererHelpers(Object item, int meta, String type) {
        ItemStack stack = new ItemStack((Item) item, 1, meta);
        IItemRenderer.ItemRenderType renderType = IItemRenderer.ItemRenderType.valueOf(type);
        IItemRenderer renderer = MinecraftForgeClient.getItemRenderer(stack, renderType);
        int helpers = 0;
        if (renderer != null) {
            for (IItemRenderer.ItemRendererHelper helper : IItemRenderer.ItemRendererHelper.values()) {
                try {
                    if (renderer.shouldUseRenderHelper(renderType, stack, helper)) {
                        helpers |= 1 << helper.ordinal();
                    }
                } catch (RuntimeException e) {
                    // asks nothing
                }
            }
        }
        Item legacy = (Item) item;
        if (legacy.func_77662_d()) { // isFull3D: held like a tool
            helpers |= 1 << 30;
        }
        if (legacy.func_77629_n_()) { // shouldRotateAroundWhenRendering
            helpers |= 1 << 29;
        }
        return helpers;
    }

    private static BridgeWorld regionWorld;
    private static Field worldRenderPass;

    /**
     * Forge's render passes (ForgeHooksClient.worldRenderPass and renderPass), which 1.7.10's chunk renderer set while
     * it drew blocks: the block's own pass. Renderers that draw per pass (Carpenter's Blocks) draw nothing
     * outside one.
     */
    private static void renderPass(int pass) {
        try {
            if (worldRenderPass == null) {
                worldRenderPass = net.minecraftforge.client.ForgeHooksClient.class.getDeclaredField("worldRenderPass");
                worldRenderPass.setAccessible(true);
            }
            worldRenderPass.setInt(null, pass);
        } catch (ReflectiveOperationException e) {
            // keep the default
        }
        net.minecraftforge.client.ForgeHooksClient.setRenderPass(pass); // MinecraftForgeClient.getRenderPass
    }

    /** As 1.7.10's chunk renderer: the block once for each pass it renders in (Forge's canRenderInPass). */
    private static void renderInPasses(Block block, int x, int y, int z) {
        for (int pass = 0; pass < 2; pass++) {
            if (block.canRenderInPass(pass)) {
                renderPass(pass);
                renderBlocks.func_147805_b(block, x, y, z); // renderBlockByRenderType
            }
        }
        renderPass(-1);
    }

    /**
     * Renders the block at x, y, z of a host region (a chunk mesh being built) the way 1.7.10 did in
     * the world, so renderers that look at neighbours and tile entities draw what they should;
     * vertices are relative to the block. Returns the vertex count. Hold Bridge.LOCK.
     */
    public static int renderBlockAt(retrofit.api.HostWorld region, int x, int y, int z) {
        if (regionWorld == null) {
            regionWorld = new BridgeWorld(region, 0);
        } else {
            regionWorld.setHost(region);
        }
        Block block = regionWorld.func_147439_a(x, y, z); // getBlock
        if (renderBlocks == null) {
            renderBlocks = new RenderBlocks(regionWorld);
        }
        renderBlocks.field_147845_a = regionWorld; // blockAccess
        Capture.begin();
        GL11.bindLegacyTexture(BLOCK_ATLAS);
        Tessellator tessellator = Tessellator.field_78398_a;
        try {
            tessellator.func_78382_b(); // startDrawingQuads
            tessellator.func_78373_b(-x, -y, -z); // setTranslation: relative to the block
            renderInPasses(block, x, y, z);
            tessellator.func_78381_a(); // draw
        } catch (RuntimeException e) {
            resetTessellator();
            throw e;
        } finally {
            tessellator.func_78373_b(0, 0, 0);
            regionWorld.setHost(null);
        }
        return Capture.vertexCount();
    }

    /**
     * The colour of the block at x, y, z of a host region (colorMultiplier: biome-coloured leaves
     * and grass), for plain blocks drawn from models with a tint. Hold Bridge.LOCK.
     */
    public static int blockColorAt(retrofit.api.HostWorld region, int x, int y, int z) {
        if (regionWorld == null) {
            regionWorld = new BridgeWorld(region, 0);
        } else {
            regionWorld.setHost(region);
        }
        try {
            return regionWorld.func_147439_a(x, y, z).func_149720_d(regionWorld, x, y, z); // getBlock, colorMultiplier
        } finally {
            regionWorld.setHost(null);
        }
    }

    /**
     * The sky of a legacy client world's own renderer (WorldProvider.getSkyRenderer), drawn as
     * 1.7.10 drew it, around the camera; -1 if the world has none. Hold Bridge.LOCK.
     */
    public static int renderSky(Object legacyWorld, float partialTicks) {
        if (!(legacyWorld instanceof net.minecraft.client.multiplayer.WorldClient)) {
            return -1;
        }
        net.minecraft.client.multiplayer.WorldClient world = (net.minecraft.client.multiplayer.WorldClient) legacyWorld;
        net.minecraftforge.client.IRenderHandler sky = world.field_73011_w.getSkyRenderer(); // provider
        if (sky == null) {
            return -1;
        }
        environment();
        Capture.begin();
        try {
            sky.render(partialTicks, world, Minecraft.func_71410_x());
        } catch (RuntimeException e) {
            resetTessellator();
            throw e;
        }
        return Capture.vertexCount();
    }

    /**
     * The grass and foliage colour maps (256 x 256, as the host loaded them from its resource
     * packs): 1.7.10 filled them in a resource reload listener of its own, and biome colours
     * (grass, leaves, mods' biome-coloured blocks) were black without them.
     */
    public static void setColorizers(int[] grass, int[] foliage) {
        net.minecraft.world.ColorizerGrass.func_77479_a(grass); // setGrassBiomeColorizer
        net.minecraft.world.ColorizerFoliage.func_77467_a(foliage); // setFoliageBiomeColorizer
    }

    /** Whether a block has colours of its own (overrides colorMultiplier or getRenderColor, as leaves and grass do). */
    public static boolean blockTinted(Object block) {
        for (Class<?> type = block.getClass(); type != null && type != Block.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                String name = method.getName();
                if (name.equals("func_149720_d") && method.getParameterTypes().length == 4
                        || name.equals("func_149741_i") && method.getParameterTypes().length == 1) {
                    return true;
                }
            }
        }
        return false;
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

    /** Runs an entity's legacy renderer (RenderManager) at the origin; returns the number of captured vertices. */
    public static int renderEntity(Object entity, float yaw, float partialTicks) {
        environment();
        Capture.begin();
        try {
            RenderManager.field_78727_a.func_147939_a((net.minecraft.entity.Entity) entity, 0, 0, 0, yaw, partialTicks, false); // func_147939_a: renderEntityWithPosYaw, no hitbox
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
        // EQUIPPED renderers get the holder (the host's own holder is not known here: the local player)
        Object holder = renderType == IItemRenderer.ItemRenderType.EQUIPPED || renderType == IItemRenderer.ItemRenderType.EQUIPPED_FIRST_PERSON
                ? net.minecraft.client.Minecraft.func_71410_x().field_71439_g : null; // thePlayer
        Object[] data = renderType == IItemRenderer.ItemRenderType.INVENTORY
                ? new Object[]{renderBlocks} : new Object[]{renderBlocks, holder};
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
        if (minecraftTimer != null) {
            minecraftTimer.field_74281_c = partialTicks; // renderPartialTicks
        }
        camera.func_70012_b(x, y, z, yaw, pitch); // setLocationAndAngles
        camera.field_70126_B = yaw;   // prevRotationYaw
        camera.field_70127_C = pitch; // prevRotationPitch
        Minecraft.func_71410_x().field_71451_h = camera; // getMinecraft().renderViewEntity
        RenderManager.field_78727_a.func_147938_a(world, textures, font, camera, null, settings, partialTicks); // cacheActiveRenderInfo
        TileEntityRendererDispatcher.field_147556_a.func_147542_a(world, textures, font, camera, partialTicks);
    }

    /** For GUIs: the same environment renderers get. */
    private static net.minecraft.util.Timer minecraftTimer;

    static void ensureEnvironment() {
        environment();
    }

    /** After a failed or aborted draw, so the next one can start. */
    static void resetTessellatorIfDrawing() {
        resetTessellator();
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
            textures = new TextureManager(minecraft.func_110442_L()); // getResourceManager; loadTexture is ClientTextures
            Field timer = Minecraft.class.getDeclaredField("field_71428_T"); // timer (mods read renderPartialTicks)
            timer.setAccessible(true);
            if (timer.get(minecraft) == null) {
                retrofit.legacy.compat.FinalFields.set(timer, minecraft, new net.minecraft.util.Timer(20f));
            }
            minecraftTimer = (net.minecraft.util.Timer) timer.get(minecraft);
            LegacyIcons.registerAtlases(textures);
            settings = minecraft.field_71474_y != null ? minecraft.field_71474_y : (GameSettings) allocate(GameSettings.class);
            minecraft.field_71446_o = textures; // renderEngine
            TileEntityRendererDispatcher.field_147556_a.field_147553_e = textures;
            RenderManager.field_78727_a.field_78724_e = textures;
            Field skins = Minecraft.class.getDeclaredField("field_152350_aA"); // skinManager
            skins.setAccessible(true);
            skins.set(minecraft, allocate(SkinManager.class));
            font = new FontRenderer(settings, new ResourceLocation("textures/font/ascii.png"), textures, false);
            font.func_110549_a(minecraft.func_110442_L()); // onResourceManagerReload: glyph widths from the font texture
            minecraft.field_71466_p = font; // fontRenderer
            minecraft.field_71464_q = font; // standardGalacticFontRenderer
            Field languages = Minecraft.class.getDeclaredField("field_135017_as"); // mcLanguageManager (ScaledResolution asks it about Unicode)
            languages.setAccessible(true);
            if (languages.get(minecraft) == null) {
                languages.set(minecraft, new net.minecraft.client.resources.LanguageManager(
                        new net.minecraft.client.resources.data.IMetadataSerializer(), System.getProperty("retrofit.language", "en_US")));
            }
            // Mods ask which resource packs are active (DragonAPI's textures) and toggle the lightmap through the
            // entity renderer. An empty repository (the legacy packs are the host's, see Hooks), and an entity
            // renderer created without its constructor: the lightmap calls it has need no state.
            Field repository = Minecraft.class.getDeclaredField("field_110448_aq"); // mcResourcePackRepository
            repository.setAccessible(true);
            if (repository.get(minecraft) == null) {
                Object packs = allocate(net.minecraft.client.resources.ResourcePackRepository.class);
                for (Field field : net.minecraft.client.resources.ResourcePackRepository.class.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    field.setAccessible(true);
                    if (field.getType() == java.util.List.class) {
                        field.set(packs, new java.util.ArrayList<Object>());
                    } else if (field.getType() == java.io.File.class) {
                        // The resource pack folders (mods list them, e.g. BiblioCraft's paintings): <game dir>/retrofit/resourcepacks.
                        java.io.File dir = new java.io.File(minecraft.field_71412_D, "resourcepacks"); // mcDataDir
                        dir.mkdirs();
                        retrofit.legacy.compat.FinalFields.set(field, packs, dir);
                    }
                }
                retrofit.legacy.compat.FinalFields.set(repository, minecraft, packs);
            }
            if (minecraft.field_71424_I == null) { // mcProfiler (mods profile their client ticks and drawing)
                retrofit.legacy.compat.FinalFields.set(Minecraft.class.getDeclaredField("field_71424_I"), minecraft, new net.minecraft.profiler.Profiler());
            }
            if (RenderManager.field_78727_a.field_78721_f == null) { // itemRenderer: held items of mobs (RenderBiped)
                net.minecraft.client.renderer.ItemRenderer items = new net.minecraft.client.renderer.ItemRenderer(minecraft);
                RenderManager.field_78727_a.field_78721_f = items;
            }
            if (minecraft.field_71438_f == null) { // renderGlobal
                minecraft.field_71438_f = (net.minecraft.client.renderer.RenderGlobal) allocate(BridgeRenderGlobal.class);
            }
            if (minecraft.field_71460_t == null) { // entityRenderer
                minecraft.field_71460_t = (net.minecraft.client.renderer.EntityRenderer) allocate(net.minecraft.client.renderer.EntityRenderer.class);
                Field mc = net.minecraft.client.renderer.EntityRenderer.class.getDeclaredField("field_78531_r"); // mc (enableLightmap)
                mc.setAccessible(true);
                retrofit.legacy.compat.FinalFields.set(mc, minecraft.field_71460_t, minecraft);
                Field entityRendererItems = net.minecraft.client.renderer.EntityRenderer.class.getDeclaredField("field_78516_c"); // itemRenderer
                entityRendererItems.setAccessible(true);
                retrofit.legacy.compat.FinalFields.set(entityRendererItems, minecraft.field_71460_t, RenderManager.field_78727_a.field_78721_f);
            }
            if (minecraft.field_71456_v == null) { // ingameGUI: chat for mods that print to it
                minecraft.field_71456_v = new BridgeGuiIngame(minecraft);
            }
            Field sounds = Minecraft.class.getDeclaredField("field_147127_av"); // mcSoundHandler
            sounds.setAccessible(true);
            sounds.set(minecraft, allocate(BridgeSoundHandler.class));
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
