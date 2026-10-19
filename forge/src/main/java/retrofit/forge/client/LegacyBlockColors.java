package retrofit.forge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import retrofit.forge.RetrofitMod;
import retrofit.forge.world.LegacyBlock;
import retrofit.forge.world.LegacyBridge;
import retrofit.forge.world.LegacyContent;
import retrofit.forge.world.RenderRegionHost;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Colours of plain legacy blocks that have their own (colorMultiplier: leaves and grass in their
 * biome's colour), for the tinted faces of their models (see LegacyAssetPack.tinted). Blocks
 * drawn by their own renderers are coloured by it already. Colours are cached per position.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class LegacyBlockColors {
    private static final int MAX_CACHED = 65536;
    private static final Map<Key, Integer> COLORS = new ConcurrentHashMap<>();
    private static boolean failed;
    /** Development: logs each block state's first colour. */
    private static final boolean DEBUG = Boolean.getBoolean("retrofit.modSweep");
    private static final java.util.Set<BlockState> LOGGED = ConcurrentHashMap.newKeySet();
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private LegacyBlockColors() {
    }

    private record Key(long pos, Block block, int meta) {
    }

    @SubscribeEvent
    public static void onBlockColors(RegisterColorHandlersEvent.Block event) {
        if (RetrofitMod.runtime() == null) {
            return;
        }
        Block[] blocks = ForgeRegistries.BLOCKS.getValues().stream()
                .filter(block -> block instanceof LegacyBlock legacy && LegacyAssetPack.tinted(legacy)).toArray(Block[]::new);
        if (blocks.length > 0) {
            event.register(LegacyBlockColors::color, blocks);
        }
    }

    /** After the host's own colour map loading (listeners registered here run after vanilla's): the legacy colorizers get the maps. */
    @SubscribeEvent
    public static void onReloadListeners(net.minecraftforge.client.event.RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) resources -> {
            if (RetrofitMod.runtime() == null) {
                return;
            }
            try {
                LegacyBridge.call("setColorizers", colorMap(net.minecraft.world.level.GrassColor.class),
                        colorMap(net.minecraft.world.level.FoliageColor.class));
                COLORS.clear();
            } catch (RuntimeException | ReflectiveOperationException e) {
                LOGGER.warn("Biome colour maps not given to legacy code", e);
            }
        });
    }

    /** GrassColor's or FoliageColor's loaded pixels (their one static int[], whatever its name in this environment). */
    private static int[] colorMap(Class<?> owner) throws ReflectiveOperationException {
        for (java.lang.reflect.Field field : owner.getDeclaredFields()) {
            if (field.getType() == int[].class && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                return (int[]) field.get(null);
            }
        }
        throw new NoSuchFieldException(owner.getName() + " pixels");
    }

    private static int color(BlockState state, BlockAndTintGetter level, BlockPos pos, int tint) {
        if (failed || tint != 0 || level == null || pos == null || Minecraft.getInstance().level == null) {
            return -1;
        }
        Key key = new Key(pos.asLong(), state.getBlock(), state.getValue(LegacyContent.META));
        Integer cached = COLORS.get(key);
        if (cached != null) {
            return cached;
        }
        int color;
        try {
            synchronized (LegacyBridge.renderLock()) {
                RenderRegionHost host = new RenderRegionHost(level, Minecraft.getInstance().level.getGameTime());
                color = LegacyBridge.<Integer>call("blockColorAt", host, pos.getX(), pos.getY(), pos.getZ()) | 0xFF000000;
            }
        } catch (RuntimeException e) {
            failed = true;
            LegacyBridge.reportClientFailure("block colours", e);
            return -1;
        }
        if (DEBUG && LOGGED.add(state)) {
            LOGGER.info("Legacy block colour of {} meta {} at {}: {}", ForgeRegistries.BLOCKS.getKey(state.getBlock()), key.meta(), pos,
                    Integer.toHexString(color));
        }
        if (COLORS.size() > MAX_CACHED) {
            COLORS.clear();
        }
        COLORS.put(key, color);
        return color;
    }
}
