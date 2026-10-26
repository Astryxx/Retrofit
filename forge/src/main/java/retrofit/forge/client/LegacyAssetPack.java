package retrofit.forge.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import retrofit.forge.world.LegacyBlock;
import retrofit.forge.world.LegacyBlockItem;
import retrofit.forge.world.LegacyBridge;
import retrofit.forge.world.LegacyContent;
import retrofit.forge.world.LegacyItem;
import retrofit.host.LegacyRuntime;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Built-in client resource pack that makes legacy content ordinary 1.20.1 content to the
 * renderer and the language system:
 * <ul>
 *   <li>a blockstate, block models (one per metadata value) and an item model per legacy block,
 *       generated from the texture each face reports;</li>
 *   <li>item models per legacy item, with an override per subtype (damage value);</li>
 *   <li>translations from the mods' .lang files and from code (FML's LanguageRegistry);</li>
 *   <li>the texture files, served straight from the legacy mod jars (and the player's 1.7.10 jar
 *       for vanilla texture names).</li>
 * </ul>
 * Legacy texture names map to {@code retrofit:<blocks|items>/legacy/<namespace>/<path>}: legacy
 * names may use characters 1.20.1 forbids (e.g. upper case), and this keeps them apart from
 * everything else. Atlas sources add those folders to the 1.20.1 block atlas.
 *
 * Plain cube blocks (render type 0) render exactly; other render types get a cube for now.
 */
final class LegacyAssetPack implements PackResources {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PACK_FORMAT = 15;
    private static final String[] FACES = {"down", "up", "north", "south", "west", "east"};
    private static final Pattern LANG_FILE = Pattern.compile("assets/[^/]+/lang/([A-Za-z_]+)\\.lang");

    private static boolean iconsRegistered;

    private final String id;
    private final List<ZipFile> jars = new ArrayList<>();
    /** namespace -> (path -> contents), client resources only. */
    private final Map<String, Map<String, IoSupplier<InputStream>>> files = new HashMap<>();

    private LegacyAssetPack(String id) {
        this.id = id;
    }

    static LegacyAssetPack create(String id, LegacyRuntime runtime) {
        LegacyAssetPack pack = new LegacyAssetPack(id);
        try {
            pack.build(runtime);
        } catch (Exception e) {
            LOGGER.error("Retrofit could not build the legacy asset pack; legacy content will look like missing models", e);
        }
        return pack;
    }

    private void build(LegacyRuntime runtime) throws IOException {
        List<LegacyBlock> blocks = LegacyContent.blocks();
        List<LegacyItem> items = LegacyContent.items();
        if (!iconsRegistered) {
            iconsRegistered = true;
            int failed = LegacyBridge.registerAllIcons();
            if (failed > 0) {
                LOGGER.warn("{} legacy blocks/items failed to register their textures", failed);
            }
        }

        List<ZipFile> modJars = new ArrayList<>();
        for (String path : LegacyBridge.modSources()) {
            modJars.add(open(new File(path)));
        }
        List<ZipFile> textureSources = new ArrayList<>(modJars);
        textureSources.add(open(runtime.gameJar().toFile())); // vanilla texture names used by mods

        Map<String, String> blockTextures = new TreeMap<>(); // legacy icon name -> 1.20.1 sprite id
        Map<String, String> itemTextures = new TreeMap<>();
        int nonCube = 0;
        for (LegacyBlock block : blocks) {
            if (block.renderType() != 0) {
                nonCube++;
            }
            addBlock(block, blockTextures, itemTextures);
        }
        addWoodAliases();
        for (LegacyItem item : items) {
            addItem(item, itemTextures);
        }
        for (retrofit.forge.world.LegacyArmorItem armor : LegacyContent.armor()) {
            addItem(armor, itemTextures);
        }
        // Every recorded icon goes into the atlas, vanilla ones included: legacy renderers draw
        // vanilla blocks and items too (items on shelves, blocks inside machines).
        for (String name : LegacyBridge.iconNames(0)) {
            blockTextures.computeIfAbsent(name, n -> spriteId("blocks", n));
        }
        for (String name : LegacyBridge.iconNames(1)) {
            itemTextures.computeIfAbsent(name, n -> spriteId("items", n));
        }

        int found = 0;
        for (Map.Entry<String, String> texture : blockTextures.entrySet()) {
            found += addTexture("blocks", texture.getKey(), texture.getValue(), textureSources) ? 1 : 0;
        }
        for (Map.Entry<String, String> texture : itemTextures.entrySet()) {
            found += addTexture("items", texture.getKey(), texture.getValue(), textureSources) ? 1 : 0;
        }

        addPotionIcons();

        JsonArray atlasSources = new JsonArray();
        for (String kind : new String[]{"blocks", "items"}) {
            JsonObject source = new JsonObject();
            source.addProperty("type", "directory");
            source.addProperty("source", kind + "/legacy");
            source.addProperty("prefix", kind + "/legacy/");
            atlasSources.add(source);
        }
        JsonObject atlas = new JsonObject();
        atlas.add("sources", atlasSources);
        putJson("minecraft", "atlases/blocks.json", atlas);

        int translations = addTranslations(modJars);
        for (net.minecraft.world.item.Item egg : retrofit.forge.world.LegacyEntityTypes.eggs()) {
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(egg);
            JsonObject model = new JsonObject();
            model.addProperty("parent", "minecraft:item/template_spawn_egg");
            putJson(key.getNamespace(), "models/item/" + key.getPath() + ".json", model);
        }
        int passedThrough = addModFiles(modJars);

        LOGGER.info("Legacy asset pack: {} blocks, {} items, {}/{} textures found in {} jars, {} translations, {} sound and texture files{}",
                blocks.size(), items.size(), found, blockTextures.size() + itemTextures.size(), textureSources.size(), translations,
                passedThrough,
                nonCube > 0 ? ", " + nonCube + " blocks with custom rendering" : "");
    }

    // ---- Blocks ----

    /** The 1.20.1-named planks and logs of legacy woods (LegacyWoodSets) look like their legacy subtype. */
    private void addWoodAliases() {
        for (net.minecraft.world.level.block.Block block : ForgeRegistries.BLOCKS) {
            if (!(block instanceof retrofit.forge.world.LegacyWoodSets.AliasBlock alias)) {
                continue;
            }
            ResourceLocation key = ForgeRegistries.BLOCKS.getKey(alias);
            ResourceLocation original = ForgeRegistries.BLOCKS.getKey(alias.original());
            String model = "retrofit:block/legacy/" + original.getNamespace() + "/" + original.getPath() + "/m" + alias.meta();
            JsonObject variant = new JsonObject();
            variant.addProperty("model", model);
            JsonObject variants = new JsonObject();
            variants.add("", variant);
            JsonObject blockstate = new JsonObject();
            blockstate.add("variants", variants);
            putJson(key.getNamespace(), "blockstates/" + key.getPath() + ".json", blockstate);
            JsonObject itemModel = new JsonObject();
            itemModel.addProperty("parent", model);
            putJson(key.getNamespace(), "models/item/" + key.getPath() + ".json", itemModel);
        }
    }

    private void addBlock(LegacyBlock block, Map<String, String> textureIds, Map<String, String> itemTextureIds) {
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        String[] models = new String[16];
        String[][] facesByMeta = new String[16][];
        float[][] boundsByMeta = new float[16][];
        String fallbackModel = null;
        for (int meta = 0; meta < 16; meta++) {
            String[] faces = LegacyBridge.faceTextures(block.legacyBlock(), meta);
            if (faces == null) {
                continue;
            }
            facesByMeta[meta] = faces;
            boundsByMeta[meta] = block.renderType() == 0 ? renderBounds(block, meta) : null;
            String modelPath = "block/legacy/" + key.getNamespace() + "/" + key.getPath() + "/m" + meta;
            JsonObject model = blockModel(block, faces, textureIds, boundsByMeta[meta]);
            if (block.renderType() == -1) {
                model = emptyModel(model); // drawn entirely by its tile entity renderer
            }
            putJson("retrofit", "models/" + modelPath + ".json", model);
            models[meta] = "retrofit:" + modelPath;
            if (fallbackModel == null) {
                fallbackModel = models[meta];
            }
        }
        Object legacyItem = block.asItem() instanceof LegacyBlockItem item ? item.legacyItem() : null;
        boolean customBlock = block.renderType() >= 42 && legacyItem != null && LegacyBridge.<Boolean>call("blockItemIn3d", block.legacyBlock());
        boolean ownItemRenderer = legacyItem != null && LegacyBridge.hasItemRenderer(legacyItem, 0, "INVENTORY") || customBlock;
        // RenderItem: an item block whose render type isn't drawn in 3D (plants, rails, blocks drawn
        // by their tile entity) is its item's icon, flat, like any item
        boolean flatItem = legacyItem != null && !ownItemRenderer && !LegacyBridge.<Boolean>call("blockItemIn3d", block.legacyBlock());
        if (flatItem) {
            addItem((retrofit.forge.world.LegacyStackItem) block.asItem(), itemTextureIds);
        }
        if (fallbackModel == null) {
            // no icons at all (GregTech's machines draw only through their own renderer): the item still is the mod's drawing
            if (ownItemRenderer) {
                putJson(key.getNamespace(), "models/item/" + key.getPath() + ".json", customRendererItemModel());
            }
            return;
        }
        JsonObject variants = new JsonObject();
        for (int meta = 0; meta < 16; meta++) {
            JsonObject variant = new JsonObject();
            variant.addProperty("model", models[meta] != null ? models[meta] : fallbackModel);
            variants.add("meta=" + meta, variant);
        }
        JsonObject blockstate = new JsonObject();
        blockstate.add("variants", variants);
        putJson(key.getNamespace(), "blockstates/" + key.getPath() + ".json", blockstate);

        if (flatItem) {
            return; // its item model is written
        }
        // RenderBlocks.renderBlockAsItem drew plain blocks with setBlockBoundsForItemRender's bounds
        // (pressure plates thicker than in the world): those that differ get an item model of their own
        String[] itemModels = models.clone();
        if (block.renderType() == 0) {
            float[] itemBounds = itemBounds(block);
            for (int meta = 0; meta < 16; meta++) {
                if (itemBounds != NOT_SET && models[meta] != null && !Arrays.equals(itemBounds, boundsByMeta[meta])) {
                    String modelPath = "block/legacy/" + key.getNamespace() + "/" + key.getPath() + "/m" + meta + "_item";
                    putJson("retrofit", "models/" + modelPath + ".json", blockModel(block, facesByMeta[meta], textureIds, itemBounds));
                    itemModels[meta] = "retrofit:" + modelPath;
                }
            }
        }
        if (itemModels[0] == null) {
            itemModels[0] = fallbackModel;
        }
        for (int meta = 0; meta < 16 && !ownItemRenderer; meta++) {
            if (itemModels[meta] != null) {
                itemModels[meta] = blockItemModel(key, meta, itemModels[meta]);
            }
        }
        String baseModel = itemModels[0];
        JsonObject itemModel;
        if (ownItemRenderer) {
            // drawn by the legacy item renderer, or (mods' block renderers) as 1.7.10 drew the block as an item
            itemModel = customRendererItemModel();
        } else {
            itemModel = new JsonObject();
            itemModel.addProperty("parent", baseModel);
            if (block.asItem() instanceof LegacyBlockItem item && item.hasSubtypes()) {
                JsonArray overrides = new JsonArray();
                for (int meta : LegacyBridge.itemSubtypes(legacyItem)) {
                    if (meta >= 0 && meta < 16 && itemModels[meta] != null) {
                        overrides.add(metaOverride(meta, itemModels[meta]));
                    }
                }
                itemModel.add("overrides", overrides);
            }
        }
        putJson(key.getNamespace(), "models/item/" + key.getPath() + ".json", itemModel);
    }

    /**
     * 1.7.10's GUI pose of a block item (RenderItem: 210 degrees about x, 45 then -90 about y, at 10
     * of 16 pixels), as a 1.20.1 display transform: vanilla's blocks face the other way round (225).
     */
    private static final JsonObject LEGACY_BLOCK_GUI = transform(new float[]{30, 315, 0}, new float[]{0, 0, 0}, 0.625f);

    /** An item model of a block model for one metadata value, shown in GUIs as 1.7.10 showed it. */
    private String blockItemModel(ResourceLocation key, int meta, String blockModel) {
        String path = "item/legacy/" + key.getNamespace() + "/" + key.getPath() + "/b" + meta;
        JsonObject model = new JsonObject();
        model.addProperty("parent", blockModel);
        JsonObject display = new JsonObject();
        display.add("gui", LEGACY_BLOCK_GUI);
        model.add("display", display);
        putJson("retrofit", "models/" + path + ".json", model);
        return "retrofit:" + path;
    }

    /** A model with no geometry, keeping the textures (for the break particles). */
    private static JsonObject emptyModel(JsonObject model) {
        JsonObject empty = new JsonObject();
        JsonObject textures = new JsonObject();
        textures.add("particle", model.getAsJsonObject("textures").get("particle"));
        empty.add("textures", textures);
        return empty;
    }

    /**
     * Item model for items drawn by a Forge IItemRenderer: "builtin/entity" routes them to
     * {@link LegacyItemRenderer}; the display transforms are the vanilla block ones.
     */
    private static JsonObject customRendererItemModel() {
        JsonObject model = new JsonObject();
        model.addProperty("parent", "minecraft:builtin/entity");
        model.addProperty("gui_light", "side");
        JsonObject display = new JsonObject();
        display.add("gui", transform(new float[]{0, 0, 0}, new float[]{0, 0, 0}, 1f)); // the GUI transform is 1.7.10's (LegacyItemRenderer)
        display.add("ground", transform(new float[]{0, 0, 0}, new float[]{0, 3, 0}, 0.25f));
        display.add("fixed", transform(new float[]{0, 0, 0}, new float[]{0, 0, 0}, 0.5f));
        for (String hand : new String[]{"thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand"}) {
            display.add(hand, transform(new float[]{0, 0, 0}, new float[]{0, 0, 0}, 1f)); // 1.7.10's, in LegacyItemRenderer
        }
        model.add("display", display);
        return model;
    }

    private static JsonObject transform(float[] rotation, float[] translation, float scale) {
        JsonObject transform = new JsonObject();
        transform.add("rotation", array(rotation));
        transform.add("translation", array(translation));
        transform.add("scale", array(new float[]{scale, scale, scale}));
        return transform;
    }

    private static JsonArray array(float[] values) {
        JsonArray array = new JsonArray();
        for (float value : values) {
            array.add(value);
        }
        return array;
    }

    private static JsonObject metaOverride(int meta, String model) {
        JsonObject predicate = new JsonObject();
        predicate.addProperty(LegacyItemProperties.META.toString(), meta);
        JsonObject override = new JsonObject();
        override.add("predicate", predicate);
        override.addProperty("model", model);
        return override;
    }

    /**
     * A block's model from its face textures: a plain block (render type 0) smaller than a cube
     * (cakes, carpets, plates) is a box of its bounds (see renderBounds; null for a whole cube), its
     * textures taken where the box lies, as RenderBlocks.renderStandardBlock took them.
     */
    private JsonObject blockModel(LegacyBlock block, String[] faces, Map<String, String> textureIds, float[] bounds) {
        String[] sprites = new String[6];
        for (int i = 0; i < 6; i++) {
            sprites[i] = textureIds.computeIfAbsent(faces[i], name -> spriteId("blocks", name));
        }
        JsonObject model = new JsonObject();
        JsonObject textures = new JsonObject();
        boolean tinted = tinted(block); // leaves, grass: coloured by LegacyBlockColors
        if (bounds != null) {
            model.addProperty("parent", "minecraft:block/block");
            model.add("elements", box(bounds, tinted));
            for (int i = 0; i < 6; i++) {
                textures.addProperty(FACES[i], sprites[i]);
            }
        } else if (Arrays.stream(sprites).allMatch(sprites[0]::equals)) {
            model.addProperty("parent", tinted ? "minecraft:block/leaves" : "minecraft:block/cube_all"); // leaves: cube_all with tint index 0
            textures.addProperty("all", sprites[0]);
        } else {
            model.addProperty("parent", "minecraft:block/cube");
            for (int i = 0; i < 6; i++) {
                textures.addProperty(FACES[i], sprites[i]);
            }
            if (tinted) {
                model.addProperty("parent", "minecraft:block/block");
                model.add("elements", box(new float[]{0, 0, 0, 16, 16, 16}, true));
            }
        }
        textures.addProperty("particle", sprites[1]);
        model.add("textures", textures);
        if (block.renderPass() == 1) {
            model.addProperty("render_type", "minecraft:translucent");
        } else if (!block.isOpaque()) {
            model.addProperty("render_type", "minecraft:cutout");
        }
        return model;
    }

    private static final Map<Object, Boolean> TINTED = new java.util.concurrent.ConcurrentHashMap<>();

    /** Whether a legacy block has colours of its own (see the legacy bridge's LegacyRendering.blockTinted). */
    static boolean tinted(LegacyBlock block) {
        return TINTED.computeIfAbsent(block.legacyBlock(), legacy -> {
            try {
                return LegacyBridge.<Boolean>call("blockTinted", legacy);
            } catch (RuntimeException e) {
                return false;
            }
        });
    }

    /**
     * The bounds a plain block is drawn with for a metadata value, in model units (min x y z, max x
     * y z out of 16), or null for a whole cube: the selection box once setBlockBoundsBasedOnState has
     * run, which is what RenderBlocks drew.
     */
    private static float[] renderBounds(LegacyBlock block, int meta) {
        double[] shape;
        try {
            shape = LegacyBridge.blockShape(block.legacyBlock(), meta);
        } catch (RuntimeException e) {
            return null;
        }
        return modelBounds(shape);
    }

    /** itemBounds of a block that doesn't set item bounds: its item is drawn with the block's own model. */
    private static final float[] NOT_SET = new float[0];

    /**
     * The bounds plain blocks were drawn with as items (setBlockBoundsForItemRender), as renderBounds
     * gives them, or NOT_SET.
     */
    private static float[] itemBounds(LegacyBlock block) {
        try {
            double[] bounds = LegacyBridge.call("itemRenderBounds", block.legacyBlock());
            return bounds == null ? NOT_SET : modelBounds(bounds);
        } catch (RuntimeException e) {
            return NOT_SET;
        }
    }

    /** Block bounds (min x y z, max x y z, out of 1) in model units, null for a whole cube or nothing. */
    private static float[] modelBounds(double[] shape) {
        float[] bounds = new float[6];
        boolean whole = true;
        for (int i = 0; i < 6; i++) {
            bounds[i] = (float) Math.max(0, Math.min(16, shape[i] * 16));
            whole &= bounds[i] == (i < 3 ? 0 : 16);
        }
        boolean empty = bounds[0] >= bounds[3] || bounds[1] >= bounds[4] || bounds[2] >= bounds[5];
        return whole || empty ? null : bounds;
    }

    /**
     * One element of the given bounds (min x y z, max x y z, out of 16) with each face's own texture;
     * faces on the block's edge are culled by the neighbour there. Tint index 0 on every face if tinted.
     */
    private static JsonArray box(float[] bounds, boolean tinted) {
        boolean[] onEdge = {bounds[1] == 0, bounds[4] == 16, bounds[2] == 0, bounds[5] == 16, bounds[0] == 0, bounds[3] == 16}; // FACES order
        JsonObject faces = new JsonObject();
        for (int i = 0; i < FACES.length; i++) {
            JsonObject entry = new JsonObject();
            entry.addProperty("texture", "#" + FACES[i]);
            if (onEdge[i]) {
                entry.addProperty("cullface", FACES[i]);
            }
            if (tinted) {
                entry.addProperty("tintindex", 0);
            }
            faces.add(FACES[i], entry);
        }
        JsonObject element = new JsonObject();
        element.add("from", array(new float[]{bounds[0], bounds[1], bounds[2]}));
        element.add("to", array(new float[]{bounds[3], bounds[4], bounds[5]}));
        element.add("faces", faces);
        JsonArray elements = new JsonArray();
        elements.add(element);
        return elements;
    }

    // ---- Items ----

    /** One flat item model per subtype; the item's main model picks one with {@link LegacyItemProperties#META}. */
    private void addItem(retrofit.forge.world.LegacyStackItem item, Map<String, String> textureIds) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey((net.minecraft.world.item.Item) item);
        int[] metas = item.hasSubtypes() ? LegacyBridge.itemSubtypes(item.legacyItem()) : new int[]{0};
        JsonArray overrides = new JsonArray();
        String baseModel = null;
        // Forge item renderers can handle some render types only (OreSpawn's big swords: in hand, not in inventories)
        String[] types = {"INVENTORY", "ENTITY", "EQUIPPED", "EQUIPPED_FIRST_PERSON"};
        boolean[] custom = new boolean[types.length];
        boolean anyCustom = false, allCustom = true;
        for (int i = 0; i < types.length; i++) {
            custom[i] = LegacyBridge.hasItemRenderer(item.legacyItem(), metas[0], types[i]);
            anyCustom |= custom[i];
            allCustom &= custom[i];
        }
        boolean handheld = (LegacyBridge.<Integer>call("itemRendererHelpers", item.legacyItem(), metas[0], "EQUIPPED") & 1 << 30) != 0; // isFull3D
        for (int meta : metas) {
            // one layer per render pass; item/generated tints layer n with tint index n (LegacyItemColors: the pass's colour)
            String[] passes = LegacyBridge.call("itemPassTextures", item.legacyItem(), meta, 5);
            if (passes.length == 0 || passes[0] == null) {
                continue;
            }
            String modelPath = "item/legacy/" + key.getNamespace() + "/" + key.getPath() + "/m" + meta;
            JsonObject model = new JsonObject();
            model.addProperty("parent", handheld ? "minecraft:item/handheld" : "minecraft:item/generated"); // tools, swords: held as 1.7.10's full-3D items
            JsonObject textures = new JsonObject();
            for (int pass = 0; pass < passes.length && passes[pass] != null; pass++) {
                String icon = passes[pass];
                // "#name": an icon of the block atlas (every one of those is stitched already)
                textures.addProperty("layer" + pass, icon.startsWith("#") ? spriteId("blocks", icon.substring(1))
                        : textureIds.computeIfAbsent(icon, name -> spriteId("items", name)));
            }
            model.add("textures", textures);
            putJson("retrofit", "models/" + modelPath + ".json", model);
            if (anyCustom && !allCustom) {
                putJson("retrofit", "models/" + modelPath + "_mixed.json", mixedModel("retrofit:" + modelPath, custom));
                modelPath += "_mixed";
            }
            if (baseModel == null) {
                baseModel = "retrofit:" + modelPath;
            }
            if (item.hasSubtypes()) {
                overrides.add(metaOverride(meta, "retrofit:" + modelPath));
            }
        }
        if (baseModel == null && !allCustom) {
            return;
        }
        if (allCustom) {
            putJson(key.getNamespace(), "models/item/" + key.getPath() + ".json", customRendererItemModel());
            return;
        }
        JsonObject itemModel = new JsonObject();
        itemModel.addProperty("parent", baseModel);
        if (!overrides.isEmpty()) {
            itemModel.add("overrides", overrides); // getSubItems lists subtypes in ascending order
        }
        putJson(key.getNamespace(), "models/item/" + key.getPath() + ".json", itemModel);
    }

    /** The legacy item renderer where it handles the render type, the flat model elsewhere (Forge's separate transforms). */
    private static JsonObject mixedModel(String flatModel, boolean[] custom) {
        String[][] contexts = {{"gui"}, {"ground", "fixed"}, {"thirdperson_righthand", "thirdperson_lefthand", "head"},
                {"firstperson_righthand", "firstperson_lefthand"}}; // INVENTORY, ENTITY, EQUIPPED, EQUIPPED_FIRST_PERSON
        JsonObject flat = new JsonObject();
        flat.addProperty("parent", flatModel);
        JsonObject model = new JsonObject();
        model.addProperty("loader", "forge:separate_transforms");
        // lit as the GUI shows it: from the front when that's the flat item (1.7.10 drew those unshaded)
        model.addProperty("gui_light", custom[0] ? "side" : "front");
        model.add("base", flat);
        JsonObject perspectives = new JsonObject();
        for (int i = 0; i < contexts.length; i++) {
            if (custom[i]) {
                for (String context : contexts[i]) {
                    perspectives.add(context, customRendererItemModel());
                }
            }
        }
        model.add("perspectives", perspectives);
        return model;
    }

    // ---- Textures ----

    /**
     * Icons for mods' potion effects (registered by name; see LegacyMobEffects): a disc in the
     * potion's colour. Mods drew their own icons from their own sheets, which 1.20.1 can't call.
     */
    private void addPotionIcons() {
        for (Map.Entry<Integer, Object[]> potion : retrofit.forge.world.LegacyMobEffects.info().entrySet()) {
            int colour = (Integer) potion.getValue()[1];
            byte[] png;
            try (com.mojang.blaze3d.platform.NativeImage image = new com.mojang.blaze3d.platform.NativeImage(18, 18, true)) {
                for (int y = 0; y < 18; y++) {
                    for (int x = 0; x < 18; x++) {
                        double d = Math.hypot(x - 8.5, y - 8.5);
                        int rgb = d <= 6.5 ? colour : d <= 8 ? 0x202020 : -1;
                        // NativeImage stores ABGR
                        image.setPixelRGBA(x, y, rgb < 0 ? 0 : 0xFF000000 | (rgb & 0xFF) << 16 | (rgb & 0xFF00) | (rgb >> 16 & 0xFF));
                    }
                }
                png = image.asByteArray();
            } catch (IOException | RuntimeException e) {
                continue;
            }
            ResourceLocation id = (ResourceLocation) potion.getValue()[2];
            put(id.getNamespace(), "textures/mob_effect/" + id.getPath() + ".png", () -> new ByteArrayInputStream(png));
        }
    }

    /** "ModId:Some/Name" -> "retrofit:blocks/legacy/modid/some/name" (1.7.10 used "minecraft" when no namespace). */
    static String spriteId(String kind, String iconName) {
        int colon = iconName.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : iconName.substring(0, colon);
        String path = colon < 0 ? iconName : iconName.substring(colon + 1);
        return "retrofit:" + kind + "/legacy/" + clean(namespace) + "/" + clean(path);
    }

    private static String clean(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
    }

    /** 1.7.10 lowercased the namespace but kept the path's case: assets/<ns>/textures/<kind>/<Path>.png */
    private boolean addTexture(String kind, String iconName, String spriteId, List<ZipFile> sources) {
        int colon = iconName.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : iconName.substring(0, colon).toLowerCase(Locale.ROOT);
        String path = colon < 0 ? iconName : iconName.substring(colon + 1);
        String folder = LegacyBridge.call("iconFolder", kind.equals("blocks") ? 0 : 1, iconName); // a mod's own atlas, or null
        String legacyPath = "assets/" + namespace + "/" + (folder != null ? folder : "textures/" + kind) + "/" + path + ".png";
        String target = "textures/" + spriteId.substring("retrofit:".length()) + ".png";
        for (ZipFile jar : sources) {
            ZipEntry png = jar.getEntry(legacyPath);
            if (png == null) {
                continue;
            }
            put("retrofit", target, () -> jar.getInputStream(png));
            ZipEntry animation = jar.getEntry(legacyPath + ".mcmeta");
            if (animation != null) {
                put("retrofit", target + ".mcmeta", () -> strictJson(jar.getInputStream(animation)));
            }
            return true;
        }
        if (addSheetTile(kind, iconName, target, sources) || addCustomSprite(kind, iconName, target)) {
            return true;
        }
        // not in any jar: a resource pack a mod adds on the legacy side (Extra Utilities makes Tinkers' Construct
        // parts of its materials that way, drawn when asked for)
        byte[] made = LegacyBridge.call("legacyResource", namespace, legacyPath.substring(("assets/" + namespace + "/").length()));
        if (made != null) {
            put("retrofit", target, () -> new ByteArrayInputStream(made));
            return true;
        }
        LOGGER.debug("Legacy texture {} not found (looked for {})", iconName, legacyPath);
        return false;
    }

    /**
     * "mod:sheet:N" with no file of that name: tile N of sheet.png, a row of square tiles as tall as
     * the image (IC2's block textures: the six sides, then the six sides of the active state). The
     * mod's own loader cuts these out at stitch time in 1.7.10.
     */
    private boolean addSheetTile(String kind, String iconName, String target, List<ZipFile> sources) {
        int last = iconName.lastIndexOf(':');
        int first = iconName.indexOf(':');
        if (last <= first || first < 0) {
            return false;
        }
        int index;
        try {
            index = Integer.parseInt(iconName.substring(last + 1));
        } catch (NumberFormatException e) {
            return false;
        }
        String namespace = iconName.substring(0, first).toLowerCase(Locale.ROOT);
        String sheetPath = "assets/" + namespace + "/textures/" + kind + "/" + iconName.substring(first + 1, last) + ".png";
        for (ZipFile jar : sources) {
            ZipEntry sheet = jar.getEntry(sheetPath);
            if (sheet == null) {
                continue;
            }
            try (InputStream in = jar.getInputStream(sheet);
                 com.mojang.blaze3d.platform.NativeImage image = com.mojang.blaze3d.platform.NativeImage.read(in)) {
                int size = image.getHeight();
                if (size <= 0 || image.getWidth() % size != 0) {
                    return false;
                }
                int tile = index % (image.getWidth() / size);
                try (com.mojang.blaze3d.platform.NativeImage cut = new com.mojang.blaze3d.platform.NativeImage(size, size, false)) {
                    image.copyRect(cut, tile * size, 0, 0, 0, size, size, false, false);
                    byte[] png = cut.asByteArray();
                    put("retrofit", target, () -> new ByteArrayInputStream(png));
                }
                return true;
            } catch (java.io.IOException | RuntimeException e) {
                LOGGER.debug("Legacy sprite sheet {} could not be cut", sheetPath, e);
                return false;
            }
        }
        return false;
    }

    /** A sprite the mod draws itself (no file of its name; see the legacy bridge's LegacyIcons.customSprite), as a PNG. */
    private boolean addCustomSprite(String kind, String iconName, String target) {
        Object[] sprite;
        try {
            sprite = LegacyBridge.call("customSprite", kind.equals("blocks") ? 0 : 1, iconName);
        } catch (RuntimeException e) {
            return false;
        }
        if (sprite == null) {
            return false;
        }
        int width = (Integer) sprite[0];
        int height = (Integer) sprite[1];
        int[] argb = (int[]) sprite[2];
        if (width <= 0 || height <= 0 || argb.length < width * height) {
            return false;
        }
        byte[] png;
        try (com.mojang.blaze3d.platform.NativeImage image = new com.mojang.blaze3d.platform.NativeImage(width, height, false)) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int c = argb[y * width + x];
                    // NativeImage stores ABGR
                    image.setPixelRGBA(x, y, (c & 0xFF00FF00) | ((c >> 16) & 0xFF) | ((c & 0xFF) << 16));
                }
            }
            png = image.asByteArray();
        } catch (java.io.IOException | RuntimeException e) {
            LOGGER.debug("Custom legacy sprite {} could not be encoded", iconName, e);
            return false;
        }
        put("retrofit", target, () -> new ByteArrayInputStream(png));
        return true;
    }

    /**
     * 1.7.10 read texture metadata leniently (trailing commas, unquoted names); 1.20.1 does not.
     * Re-written as strict JSON; passed through unchanged if it cannot be read at all.
     */
    private static java.io.InputStream strictJson(java.io.InputStream in) throws java.io.IOException {
        byte[] bytes;
        try (in) {
            bytes = in.readAllBytes();
        }
        try {
            com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(
                    new java.io.InputStreamReader(new java.io.ByteArrayInputStream(bytes), java.nio.charset.StandardCharsets.UTF_8));
            reader.setLenient(true);
            com.google.gson.JsonElement json = com.google.gson.internal.Streams.parse(reader);
            stripNulls(json);
            return new java.io.ByteArrayInputStream(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return new java.io.ByteArrayInputStream(bytes);
        }
    }

    /** Lenient parsing reads a trailing comma in an array as a null element; drop those. */
    private static void stripNulls(com.google.gson.JsonElement json) {
        if (json.isJsonArray()) {
            var array = json.getAsJsonArray();
            for (int i = array.size() - 1; i >= 0; i--) {
                if (array.get(i).isJsonNull()) {
                    array.remove(i);
                } else {
                    stripNulls(array.get(i));
                }
            }
        } else if (json.isJsonObject()) {
            json.getAsJsonObject().entrySet().forEach(entry -> stripNulls(entry.getValue()));
        }
    }

    // ---- Translations ----

    /**
     * Legacy translations become retrofit:lang/<code>.json. 1.7.10 used "en_US"-style codes and
     * "key=value" .lang files; 1.20.1 uses "en_us" and JSON. Legacy keys are kept as they are,
     * since legacy blocks and items report them as their description ids.
     */
    private int addTranslations(List<ZipFile> modJars) throws IOException {
        Map<String, Map<String, String>> languages = new TreeMap<>();
        for (ZipFile jar : modJars) {
            for (Enumeration<? extends ZipEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();
                Matcher matcher = LANG_FILE.matcher(entry.getName());
                if (!matcher.matches()) {
                    continue;
                }
                Map<String, String> language = languages.computeIfAbsent(matcher.group(1).toLowerCase(Locale.ROOT), code -> new TreeMap<>());
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8))) {
                    for (String line; (line = reader.readLine()) != null; ) {
                        int equals = line.indexOf('=');
                        if (equals > 0 && !line.startsWith("#")) {
                            language.put(line.substring(0, equals).trim(), line.substring(equals + 1));
                        }
                    }
                }
            }
        }
        for (String[] translation : LegacyBridge.codeTranslations()) {
            languages.computeIfAbsent(translation[0].toLowerCase(Locale.ROOT), code -> new TreeMap<>())
                    .putIfAbsent(translation[1], translation[2]);
        }
        // Legacy entities: 1.20.1 names entity types "entity.<ns>.<path>" and spawn eggs by item id.
        for (String legacyName : retrofit.forge.world.LegacyEntityTypes.all().keySet()) {
            ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(retrofit.forge.world.LegacyEntityTypes.all().get(legacyName));
            if (key == null) {
                continue;
            }
            for (Map<String, String> language : languages.values()) {
                String name = language.get("entity." + legacyName + ".name");
                if (name != null) {
                    language.putIfAbsent("entity." + key.getNamespace() + "." + key.getPath(), name);
                    language.putIfAbsent("item." + key.getNamespace() + "." + key.getPath() + "_spawn_egg", name + " Spawn Egg");
                }
            }
        }
        int count = 0;
        for (Map.Entry<String, Map<String, String>> language : languages.entrySet()) {
            JsonObject json = new JsonObject();
            language.getValue().forEach(json::addProperty);
            putJson("retrofit", "lang/" + language.getKey() + ".json", json);
            count += language.getValue().size();
        }
        return count;
    }

    /**
     * Mods' sounds and textures under their own namespace, for what 1.20.1 loads by name: sound
     * events (sounds.json and the .ogg files) and textures named in code (armor, entities). Paths
     * are lowercased as 1.20.1 requires; sounds.json is rewritten to match, with names that had no
     * namespace resolved against the mod's own (1.7.10 did that; 1.20.1 would use minecraft:).
     */
    private int addModFiles(List<ZipFile> modJars) throws IOException {
        int count = 0;
        for (ZipFile jar : modJars) {
            for (Enumeration<? extends ZipEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith("assets/")) {
                    continue;
                }
                int slash = name.indexOf('/', 7);
                if (slash < 0) {
                    continue;
                }
                String namespace = name.substring(7, slash).toLowerCase(Locale.ROOT);
                String path = name.substring(slash + 1);
                if (namespace.equals(retrofit.forge.RetrofitMod.MOD_ID)) {
                    continue;
                }
                if (namespace.equals("minecraft")) {
                    // mods' armor textures for their own render prefixes (vanilla's own are 1.20.1's)
                    String file = path.substring(path.lastIndexOf('/') + 1);
                    if (path.startsWith("textures/models/armor/") && file.endsWith(".png") && !VANILLA_ARMOR.matcher(file).matches()) {
                        String target = sanitize(path);
                        if (!files.getOrDefault(namespace, Map.of()).containsKey(target)) {
                            put(namespace, target, () -> jar.getInputStream(entry));
                            count++;
                        }
                    }
                    continue;
                }
                if (path.equals("sounds.json")) {
                    putJson(namespace, "sounds.json", soundsJson(namespace, jar, entry));
                    count++;
                } else if (path.startsWith("sounds/") || path.startsWith("textures/")
                        || path.indexOf('/') < 0 && (path.endsWith(".png") || path.endsWith(".png.mcmeta"))) {
                    // images at the namespace's top are textures named in code too (OreSpawn's armor: "orespawn:ruby_1.png")
                    String target = sanitize(path);
                    if (!files.getOrDefault(namespace, Map.of()).containsKey(target)) {
                        put(namespace, target, () -> jar.getInputStream(entry));
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static final Pattern VANILLA_ARMOR = Pattern.compile("(leather|chainmail|iron|diamond|gold)_layer_[12](_overlay)?\\.png");

    private static String sanitize(String path) {
        return path.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
    }

    private static JsonObject soundsJson(String namespace, ZipFile jar, ZipEntry entry) throws IOException {
        JsonObject legacy;
        try (Reader reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
            legacy = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException e) {
            LOGGER.warn("Unreadable sounds.json in {}", jar.getName(), e);
            return new JsonObject();
        }
        JsonObject modern = new JsonObject();
        for (Map.Entry<String, com.google.gson.JsonElement> event : legacy.entrySet()) {
            if (!event.getValue().isJsonObject()) {
                continue;
            }
            JsonObject definition = event.getValue().getAsJsonObject().deepCopy();
            definition.remove("category"); // 1.7.10 only
            if (definition.has("sounds")) {
                JsonArray sounds = new JsonArray();
                for (com.google.gson.JsonElement sound : definition.getAsJsonArray("sounds")) {
                    if (sound.isJsonPrimitive()) {
                        sounds.add(soundName(namespace, sound.getAsString()));
                    } else if (sound.isJsonObject()) {
                        JsonObject object = sound.getAsJsonObject().deepCopy();
                        if (object.has("name")) {
                            boolean event2 = object.has("type") && object.get("type").getAsString().equals("event");
                            object.addProperty("name", event2 ? sanitize(object.get("name").getAsString())
                                    : soundName(namespace, object.get("name").getAsString()));
                        }
                        sounds.add(object);
                    }
                }
                definition.add("sounds", sounds);
            }
            modern.add(sanitize(event.getKey()), definition);
        }
        return modern;
    }

    private static String soundName(String namespace, String name) {
        int colon = name.indexOf(':');
        return colon < 0 ? namespace + ":" + sanitize(name) : name.substring(0, colon).toLowerCase(Locale.ROOT) + ":" + sanitize(name.substring(colon + 1));
    }

    // ---- Pack plumbing ----

    private ZipFile open(File file) throws IOException {
        ZipFile jar = new ZipFile(file);
        jars.add(jar);
        return jar;
    }

    private void putJson(String namespace, String path, JsonObject json) {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        put(namespace, path, () -> new ByteArrayInputStream(bytes));
    }

    private void put(String namespace, String path, IoSupplier<InputStream> contents) {
        files.computeIfAbsent(namespace, ns -> new HashMap<>()).put(path, contents);
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... elements) {
        return null;
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
        if (type != PackType.CLIENT_RESOURCES) {
            return null;
        }
        Map<String, IoSupplier<InputStream>> namespace = files.get(location.getNamespace());
        return namespace == null ? null : namespace.get(location.getPath());
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        Map<String, IoSupplier<InputStream>> entries = type == PackType.CLIENT_RESOURCES ? files.get(namespace) : null;
        if (entries == null) {
            return;
        }
        String prefix = path.endsWith("/") ? path : path + "/";
        entries.forEach((file, contents) -> {
            if (file.startsWith(prefix)) {
                output.accept(new ResourceLocation(namespace, file), contents);
            }
        });
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == PackType.CLIENT_RESOURCES ? files.keySet() : Set.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) {
        if (serializer == PackMetadataSection.TYPE) {
            return (T) new PackMetadataSection(Component.literal("Assets of your 1.7.10 mods"), PACK_FORMAT);
        }
        return null;
    }

    @Override
    public String packId() {
        return id;
    }

    @Override
    public boolean isBuiltin() {
        return true;
    }

    @Override
    public void close() {
        for (ZipFile jar : jars) {
            try {
                jar.close();
            } catch (IOException e) {
                LOGGER.debug("Could not close {}", jar.getName(), e);
            }
        }
        jars.clear();
    }
}
