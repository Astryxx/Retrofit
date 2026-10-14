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
            addBlock(block, blockTextures);
        }
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

    private void addBlock(LegacyBlock block, Map<String, String> textureIds) {
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        String[] models = new String[16];
        String fallbackModel = null;
        for (int meta = 0; meta < 16; meta++) {
            String[] faces = LegacyBridge.faceTextures(block.legacyBlock(), meta);
            if (faces == null) {
                continue;
            }
            String modelPath = "block/legacy/" + key.getNamespace() + "/" + key.getPath() + "/m" + meta;
            JsonObject model = blockModel(block, faces, textureIds);
            if (block.renderType() == -1) {
                model = emptyModel(model); // drawn entirely by its tile entity renderer
            }
            putJson("retrofit", "models/" + modelPath + ".json", model);
            models[meta] = "retrofit:" + modelPath;
            if (fallbackModel == null) {
                fallbackModel = models[meta];
            }
        }
        if (fallbackModel == null) {
            return; // no textures at all: leave it as a missing model
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

        String baseModel = models[0] != null ? models[0] : fallbackModel;
        JsonObject itemModel;
        Object legacyItem = block.asItem() instanceof LegacyBlockItem item ? item.legacyItem() : null;
        if (legacyItem != null && LegacyBridge.hasItemRenderer(legacyItem, 0, "INVENTORY")) {
            itemModel = customRendererItemModel();
        } else {
            itemModel = new JsonObject();
            itemModel.addProperty("parent", baseModel);
            if (block.asItem() instanceof LegacyBlockItem item && item.hasSubtypes()) {
                JsonArray overrides = new JsonArray();
                for (int meta : LegacyBridge.itemSubtypes(legacyItem)) {
                    if (meta >= 0 && meta < 16 && models[meta] != null) {
                        overrides.add(metaOverride(meta, models[meta]));
                    }
                }
                itemModel.add("overrides", overrides);
            }
        }
        putJson(key.getNamespace(), "models/item/" + key.getPath() + ".json", itemModel);
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
        display.add("gui", transform(new float[]{30, 225, 0}, new float[]{0, 0, 0}, 0.625f));
        display.add("ground", transform(new float[]{0, 0, 0}, new float[]{0, 3, 0}, 0.25f));
        display.add("fixed", transform(new float[]{0, 0, 0}, new float[]{0, 0, 0}, 0.5f));
        display.add("thirdperson_righthand", transform(new float[]{75, 45, 0}, new float[]{0, 2.5f, 0}, 0.375f));
        display.add("thirdperson_lefthand", transform(new float[]{75, 45, 0}, new float[]{0, 2.5f, 0}, 0.375f));
        display.add("firstperson_righthand", transform(new float[]{0, 45, 0}, new float[]{0, 0, 0}, 0.4f));
        display.add("firstperson_lefthand", transform(new float[]{0, 225, 0}, new float[]{0, 0, 0}, 0.4f));
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

    private JsonObject blockModel(LegacyBlock block, String[] faces, Map<String, String> textureIds) {
        String[] sprites = new String[6];
        for (int i = 0; i < 6; i++) {
            sprites[i] = textureIds.computeIfAbsent(faces[i], name -> spriteId("blocks", name));
        }
        JsonObject model = new JsonObject();
        JsonObject textures = new JsonObject();
        if (Arrays.stream(sprites).allMatch(sprites[0]::equals)) {
            model.addProperty("parent", "minecraft:block/cube_all");
            textures.addProperty("all", sprites[0]);
        } else {
            model.addProperty("parent", "minecraft:block/cube");
            for (int i = 0; i < 6; i++) {
                textures.addProperty(FACES[i], sprites[i]);
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

    // ---- Items ----

    /** One flat item model per subtype; the item's main model picks one with {@link LegacyItemProperties#META}. */
    private void addItem(retrofit.forge.world.LegacyStackItem item, Map<String, String> textureIds) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey((net.minecraft.world.item.Item) item);
        int[] metas = item.hasSubtypes() ? LegacyBridge.itemSubtypes(item.legacyItem()) : new int[]{0};
        JsonArray overrides = new JsonArray();
        String baseModel = null;
        for (int meta : metas) {
            String texture = LegacyBridge.itemTexture(item.legacyItem(), meta);
            if (texture == null) {
                continue;
            }
            String modelPath = "item/legacy/" + key.getNamespace() + "/" + key.getPath() + "/m" + meta;
            JsonObject model = new JsonObject();
            model.addProperty("parent", "minecraft:item/generated");
            JsonObject textures = new JsonObject();
            textures.addProperty("layer0", textureIds.computeIfAbsent(texture, name -> spriteId("items", name)));
            model.add("textures", textures);
            putJson("retrofit", "models/" + modelPath + ".json", model);
            if (baseModel == null) {
                baseModel = "retrofit:" + modelPath;
            }
            if (item.hasSubtypes()) {
                overrides.add(metaOverride(meta, "retrofit:" + modelPath));
            }
        }
        if (baseModel == null) {
            return;
        }
        if (LegacyBridge.hasItemRenderer(item.legacyItem(), metas[0], "INVENTORY")) {
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

    // ---- Textures ----

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
        String legacyPath = "assets/" + namespace + "/textures/" + kind + "/" + path + ".png";
        String target = "textures/" + spriteId.substring("retrofit:".length()) + ".png";
        for (ZipFile jar : sources) {
            ZipEntry png = jar.getEntry(legacyPath);
            if (png == null) {
                continue;
            }
            put("retrofit", target, () -> jar.getInputStream(png));
            ZipEntry animation = jar.getEntry(legacyPath + ".mcmeta");
            if (animation != null) {
                put("retrofit", target + ".mcmeta", () -> jar.getInputStream(animation));
            }
            return true;
        }
        LOGGER.debug("Legacy texture {} not found (looked for {})", iconName, legacyPath);
        return false;
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
                if (namespace.equals("minecraft") || namespace.equals(retrofit.forge.RetrofitMod.MOD_ID)) {
                    continue;
                }
                if (path.equals("sounds.json")) {
                    putJson(namespace, "sounds.json", soundsJson(namespace, jar, entry));
                    count++;
                } else if (path.startsWith("sounds/") || path.startsWith("textures/")) {
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
