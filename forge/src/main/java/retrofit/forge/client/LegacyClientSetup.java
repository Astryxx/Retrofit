package retrofit.forge.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import retrofit.forge.RetrofitMod;
import retrofit.forge.world.LegacyBlock;
import retrofit.forge.world.LegacyBridge;
import retrofit.forge.world.LegacyContent;

import java.util.Map;

/**
 * Hooks legacy rendering into 1.20.1's client: the tile entity renderer, baked models for legacy
 * blocks with custom render types, legacy icon coordinates in the stitched atlas, and the legacy
 * viewer's position each frame.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class LegacyClientSetup {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String[] ICON_KINDS = {"blocks", "items"};
    private static boolean frameHookRegistered;

    private LegacyClientSetup() {
    }

    /** Legacy container GUIs open as LegacyScreens over LegacyMenus. */
    @SubscribeEvent
    public static void onClientSetup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        if (RetrofitMod.runtime() != null) {
            event.enqueueWork(() -> net.minecraft.client.gui.screens.MenuScreens.register(
                    retrofit.forge.world.LegacyMenu.type(), LegacyScreen::new));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends net.minecraft.world.entity.Entity> void registerEntity(EntityRenderersEvent.RegisterRenderers event,
                                                                                     net.minecraft.world.entity.EntityType<?> type) {
        event.registerEntityRenderer((net.minecraft.world.entity.EntityType<T>) type, LegacyEntityRenderer::new);
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        for (net.minecraft.world.entity.EntityType<?> type : retrofit.forge.world.LegacyEntityTypes.all().values()) {
            registerEntity(event, type);
        }
        if (LegacyContent.blockEntityType() != null) {
            event.registerBlockEntityRenderer(LegacyContent.blockEntityType(), LegacyBlockEntityRenderer::new);
        }
        if (!frameHookRegistered) {
            frameHookRegistered = true;
            MinecraftForge.EVENT_BUS.addListener(LegacyClientSetup::onRenderLevel);
        }
    }

    /**
     * Blocks with a custom render type get a model baked from 1.7.10's RenderBlocks; their items too.
     * Plain cubes whose icons depend on the world (machines facing their placer) keep their JSON
     * model, but are drawn in the world as RenderBlocks drew them.
     */
    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<ResourceLocation, BakedModel> models = event.getModels();
        int replaced = 0;
        for (LegacyBlock block : LegacyContent.blocks()) {
            boolean plainCube = block.renderType() == 0;
            if (plainCube && !block.worldIcons() || block.renderType() == -1) {
                continue; // plain cubes keep their JSON model; -1 is drawn by its tile entity renderer
            }
            LegacyBakedModel[] byMeta = new LegacyBakedModel[16];
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                ModelResourceLocation location = BlockModelShaper.stateToModelLocation(state);
                BakedModel base = models.get(location);
                if (base == null) {
                    continue;
                }
                int meta = state.getValue(LegacyContent.META);
                byMeta[meta] = new LegacyBakedModel(block, meta, base, plainCube);
                models.put(location, byMeta[meta]);
                replaced++;
            }
            if (plainCube) {
                continue; // its item keeps the JSON model, which shows the metadata's icons
            }
            ModelResourceLocation itemLocation = new ModelResourceLocation(ForgeRegistries.ITEMS.getKey(block.asItem()), "inventory");
            BakedModel itemBase = models.get(itemLocation);
            // item blocks 1.7.10 drew flat (render types not drawn in 3D) keep their flat item model
            if (itemBase != null && !itemBase.isCustomRenderer() && byMeta[0] != null && LegacyBridge.<Boolean>call("blockItemIn3d", block.legacyBlock())) {
                models.put(itemLocation, new LegacyBakedModel(block, 0, itemBase, false).forItems(byMeta));
            }
        }
        LOGGER.info("Legacy blocks drawn by RenderBlocks: {} block state models", replaced);
        int stackIcons = 0;
        for (net.minecraft.world.item.Item item : ForgeRegistries.ITEMS) {
            if (!(item instanceof retrofit.forge.world.LegacyStackItem legacy) || legacy.legacyItem() == null) {
                continue;
            }
            ModelResourceLocation location = new ModelResourceLocation(ForgeRegistries.ITEMS.getKey(item), "inventory");
            BakedModel base = models.get(location);
            // the flat generated models only: items drawn by their own renderers or as blocks ask for their icons themselves
            if (base instanceof net.minecraft.client.resources.model.SimpleBakedModel && LegacyBridge.<Boolean>call("stackIcons", legacy.legacyItem())) {
                models.put(location, new LegacyStackIconModel(base));
                stackIcons++;
            }
        }
        LOGGER.info("Legacy items whose icons depend on the whole stack: {}", stackIcons);
    }

    /**
     * After the block atlas (which holds every legacy block and item icon) is stitched, tell each
     * legacy icon where its sprite is, so legacy code drawing with icon UVs hits it.
     */
    @SubscribeEvent
    public static void onTextureStitch(TextureStitchEvent.Post event) {
        TextureAtlas atlas = event.getAtlas();
        if (!atlas.location().equals(TextureAtlas.LOCATION_BLOCKS) || RetrofitMod.runtime() == null) {
            return;
        }
        int mapped = 0;
        int missing = 0;
        java.util.List<String> missingNames = new java.util.ArrayList<>();
        java.util.Map<String, java.util.List<String>> missingByMod = new java.util.TreeMap<>();
        for (int kind = 0; kind < 2; kind++) {
            for (String name : LegacyBridge.iconNames(kind)) {
                TextureAtlasSprite sprite = atlas.getSprite(new ResourceLocation(LegacyAssetPack.spriteId(ICON_KINDS[kind], name)));
                if (sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
                    missing++;
                    missingByMod.computeIfAbsent(name.indexOf(':') > 0 ? name.substring(0, name.indexOf(':')).toLowerCase(java.util.Locale.ROOT) : "minecraft",
                            mod -> new java.util.ArrayList<>()).add(ICON_KINDS[kind] + "/" + name);
                    if (missingNames.size() < 40) {
                        missingNames.add(ICON_KINDS[kind] + "/" + name);
                    }
                }
                LegacyBridge.setIconUV(kind, name, sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1(),
                        sprite.contents().width(), sprite.contents().height());
                mapped++;
            }
        }
        LOGGER.info("Legacy icons placed in the block atlas: {} ({} without a texture file{})", mapped, missing,
                missingNames.isEmpty() ? "" : ": " + String.join(", ", missingNames) + (missing > missingNames.size() ? ", ..." : ""));
        if (!missingByMod.isEmpty()) {
            StringBuilder byMod = new StringBuilder();
            missingByMod.forEach((mod, names) -> byMod.append("\n  ").append(mod).append(": ").append(names.size()).append(", e.g. ")
                    .append(String.join(", ", names.subList(0, Math.min(3, names.size())))));
            LOGGER.info("Legacy icons without a texture file, by mod:{}", byMod);
        }
        LegacyBridge.call("iconsPlaced"); // mods' icons made from these at stitch time take their real places
        LegacyStackIconModel.clear();
        LegacyBridge.call("setModernIconSource", (java.util.function.BiFunction<Integer, Integer, float[]>) LegacyClientSetup::modernBlockSprite);
    }

    /**
     * The sprite of a side of a 1.20.1 block carried through legacy code (by its carrier's damage):
     * the first quad its model has on that side, as {u0, u1, v0, v1, width, height}; null if none.
     */
    private static float[] modernBlockSprite(int index, int side) {
        net.minecraft.world.level.block.Block block = retrofit.forge.world.ModernItems.blockOf(index);
        if (block == null) {
            return null;
        }
        BlockState state = block.defaultBlockState();
        BakedModel model = net.minecraft.client.Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        net.minecraft.core.Direction direction = net.minecraft.core.Direction.from3DDataValue(side);
        var quads = model.getQuads(state, direction, net.minecraft.util.RandomSource.create(42L));
        TextureAtlasSprite sprite = quads.isEmpty() ? model.getParticleIcon() : quads.get(0).getSprite();
        return new float[]{sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1(), sprite.contents().width(), sprite.contents().height()};
    }

    /** Once per frame, before anything renders: put the legacy viewer at the camera. */
    /** The graphics mode legacy code was last told (see LegacyRendering.setFancyGraphics): it starts fancy. */
    private static boolean legacyFancy = true;

    private static void onRenderLevel(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || minecraft.level == null || RetrofitMod.runtime() == null) {
            return;
        }
        boolean fancy = minecraft.options.graphicsMode().get() != net.minecraft.client.GraphicsStatus.FAST;
        if (fancy != legacyFancy) {
            legacyFancy = fancy;
            LegacyBridge.call("setFancyGraphics", fancy);
            minecraft.levelRenderer.allChanged(); // legacy blocks drawn again with the new leaves
        }
        Camera camera = event.getCamera();
        synchronized (LegacyBridge.renderLock()) {
            try {
                LegacyBridge.prepareFrame(LegacyContent.legacyWorld(minecraft.level), camera.getPosition().x, camera.getPosition().y,
                        camera.getPosition().z, camera.getYRot(), camera.getXRot(), event.getPartialTick());
            } catch (RuntimeException e) {
                LOGGER.debug("Could not update the legacy viewer", e);
            }
        }
    }
}
