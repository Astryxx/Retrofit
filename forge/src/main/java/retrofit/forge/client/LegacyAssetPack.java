package retrofit.forge.client;

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
import retrofit.forge.world.LegacyBridge;
import retrofit.forge.world.LegacyContent;
import retrofit.host.LegacyRuntime;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Built-in client resource pack that makes legacy blocks ordinary 1.20.1 blocks to the renderer:
 * a blockstate, block models and an item model per legacy block, generated from the textures
 * each block reports for each side and metadata, plus the texture files themselves, served
 * straight from the legacy mod jars (and the player's 1.7.10 jar for vanilla texture names).
 *
 * Legacy texture names are mapped to {@code retrofit:blocks/<namespace>/<path>}: legacy names may
 * contain characters 1.20.1 forbids (e.g. upper case), and this keeps them from colliding with
 * anything else. A block atlas source adds that folder to the 1.20.1 block atlas.
 *
 * Plain cube blocks (render type 0) render exactly; other render types get a cube for now.
 */
final class LegacyAssetPack implements PackResources {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PACK_FORMAT = 15;
    private static final String[] FACES = {"down", "up", "north", "south", "west", "east"};

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
            LOGGER.error("Retrofit could not build the legacy asset pack; legacy blocks will look like missing models", e);
        }
        return pack;
    }

    private void build(LegacyRuntime runtime) throws IOException {
        List<LegacyBlock> blocks = LegacyContent.blocks();
        if (!iconsRegistered) {
            iconsRegistered = true;
            int failed = LegacyBridge.registerIcons(blocks.stream().map(LegacyBlock::legacyBlock).toArray());
            if (failed > 0) {
                LOGGER.warn("{} legacy blocks failed to register their textures", failed);
            }
        }

        List<ZipFile> sources = new ArrayList<>();
        for (String path : LegacyBridge.modSources()) {
            sources.add(open(new File(path)));
        }
        sources.add(open(runtime.gameJar().toFile())); // vanilla texture names used by mods

        Map<String, String> textureIds = new TreeMap<>(); // legacy icon name -> 1.20.1 sprite id
        int nonCube = 0;
        for (LegacyBlock block : blocks) {
            ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
            if (LegacyBridge.blockRenderInfo(block.legacyBlock())[0] != 0) {
                nonCube++;
            }
            JsonObject variants = new JsonObject();
            String fallbackModel = null;
            String[] models = new String[16];
            for (int meta = 0; meta < 16; meta++) {
                String[] faces = LegacyBridge.faceTextures(block.legacyBlock(), meta);
                if (faces == null) {
                    continue;
                }
                String modelPath = "block/legacy/" + key.getNamespace() + "/" + key.getPath() + "/m" + meta;
                putJson("retrofit", "models/" + modelPath + ".json", blockModel(block, faces, textureIds));
                models[meta] = "retrofit:" + modelPath;
                if (fallbackModel == null) {
                    fallbackModel = models[meta];
                }
            }
            if (fallbackModel == null) {
                continue; // no textures at all: leave it as a missing model
            }
            for (int meta = 0; meta < 16; meta++) {
                JsonObject variant = new JsonObject();
                variant.addProperty("model", models[meta] != null ? models[meta] : fallbackModel);
                variants.add("meta=" + meta, variant);
            }
            JsonObject blockstate = new JsonObject();
            blockstate.add("variants", variants);
            putJson(key.getNamespace(), "blockstates/" + key.getPath() + ".json", blockstate);

            JsonObject itemModel = new JsonObject();
            itemModel.addProperty("parent", models[0] != null ? models[0] : fallbackModel);
            putJson(key.getNamespace(), "models/item/" + key.getPath() + ".json", itemModel);
        }

        int found = 0;
        for (Map.Entry<String, String> texture : textureIds.entrySet()) {
            if (addTexture(texture.getKey(), texture.getValue(), sources)) {
                found++;
            }
        }

        JsonObject atlas = new JsonObject();
        JsonObject source = new JsonObject();
        source.addProperty("type", "directory");
        source.addProperty("source", "blocks/legacy");
        source.addProperty("prefix", "blocks/legacy/");
        com.google.gson.JsonArray atlasSources = new com.google.gson.JsonArray();
        atlasSources.add(source);
        atlas.add("sources", atlasSources);
        putJson("minecraft", "atlases/blocks.json", atlas);

        LOGGER.info("Legacy asset pack: {} blocks, {}/{} textures found in {} jars{}", blocks.size(), found, textureIds.size(),
                sources.size(), nonCube > 0 ? ", " + nonCube + " blocks with custom rendering shown as cubes for now" : "");
    }

    private JsonObject blockModel(LegacyBlock block, String[] faces, Map<String, String> textureIds) {
        String[] sprites = new String[6];
        for (int i = 0; i < 6; i++) {
            sprites[i] = textureIds.computeIfAbsent(faces[i], LegacyAssetPack::spriteId);
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

    /** "ModId:Some/Name" -> "retrofit:blocks/legacy/modid/some/name" (1.7.10 used "minecraft" when no namespace). */
    private static String spriteId(String iconName) {
        int colon = iconName.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : iconName.substring(0, colon);
        String path = colon < 0 ? iconName : iconName.substring(colon + 1);
        return "retrofit:blocks/legacy/" + clean(namespace) + "/" + clean(path);
    }

    private static String clean(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
    }

    /** 1.7.10 lowercased the namespace but kept the path's case: assets/<ns>/textures/blocks/<Path>.png */
    private boolean addTexture(String iconName, String spriteId, List<ZipFile> sources) {
        int colon = iconName.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : iconName.substring(0, colon).toLowerCase(Locale.ROOT);
        String path = colon < 0 ? iconName : iconName.substring(colon + 1);
        String legacyPath = "assets/" + namespace + "/textures/blocks/" + path + ".png";
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
