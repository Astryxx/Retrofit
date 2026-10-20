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

    /** Blocks with a custom render type get a model baked from 1.7.10's RenderBlocks; their items too. */
    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<ResourceLocation, BakedModel> models = event.getModels();
        int replaced = 0;
        for (LegacyBlock block : LegacyContent.blocks()) {
            if (block.renderType() == 0 || block.renderType() == -1) {
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
                byMeta[meta] = new LegacyBakedModel(block, meta, base);
                models.put(location, byMeta[meta]);
                replaced++;
            }
            ModelResourceLocation itemLocation = new ModelResourceLocation(ForgeRegistries.ITEMS.getKey(block.asItem()), "inventory");
            BakedModel itemBase = models.get(itemLocation);
            if (itemBase != null && !itemBase.isCustomRenderer() && byMeta[0] != null) {
                models.put(itemLocation, new LegacyBakedModel(block, 0, itemBase).forItems(byMeta));
            }
        }
        LOGGER.info("Legacy blocks with custom render types: {} block state models baked from RenderBlocks", replaced);
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
        for (int kind = 0; kind < 2; kind++) {
            for (String name : LegacyBridge.iconNames(kind)) {
                TextureAtlasSprite sprite = atlas.getSprite(new ResourceLocation(LegacyAssetPack.spriteId(ICON_KINDS[kind], name)));
                if (sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
                    missing++;
                }
                LegacyBridge.setIconUV(kind, name, sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1(),
                        sprite.contents().width(), sprite.contents().height());
                mapped++;
            }
        }
        LOGGER.info("Legacy icons placed in the block atlas: {} ({} without a texture file)", mapped, missing);
    }

    /** Once per frame, before anything renders: put the legacy viewer at the camera. */
    private static void onRenderLevel(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || minecraft.level == null || RetrofitMod.runtime() == null) {
            return;
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
