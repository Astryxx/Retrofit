package retrofit.legacy.bridge;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.registry.LanguageRegistry;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import retrofit.api.HostWorld;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Static entry points the host calls into the legacy world. Legacy objects cross the boundary
 * as opaque {@code Object}s and NBT as bytes (the binary NBT format is the same in 1.7.10 and
 * 1.20.1), so the host never needs legacy types.
 */
public final class Bridge {
    private Bridge() {
    }

    public static Object createWorld(HostWorld host, int dimension) {
        return new BridgeWorld(host, dimension);
    }

    /** Registry names of all blocks added by legacy mods ("modid:name"). */
    public static String[] modBlockNames() {
        List<String> names = new ArrayList<String>();
        for (Object key : Block.field_149771_c.func_148742_b()) { // blockRegistry.getKeys()
            String name = String.valueOf(key);
            if (!name.startsWith("minecraft:")) {
                names.add(name);
            }
        }
        return names.toArray(new String[0]);
    }

    /** getBlockFromName */
    public static Object block(String name) {
        return Block.func_149684_b(name);
    }

    /**
     * How a legacy block wants to be rendered and lit: {render type (0 = plain cube), opaque cube
     * (1/0), render pass (0 solid, 1 translucent), light value (0-15)}.
     */
    public static int[] blockRenderInfo(Object legacyBlock) {
        Block block = (Block) legacyBlock;
        int renderType = 0;
        int renderPass = 0;
        try {
            renderType = block.func_149645_b();    // getRenderType
            renderPass = block.func_149701_w();    // getRenderBlockPass
        } catch (NoSuchMethodError dedicatedServer) {
            // Client-only in 1.7.10; a server doesn't render, so the defaults are fine.
        }
        return new int[]{
                renderType,
                block.func_149662_c() ? 1 : 0,     // isOpaqueCube
                renderPass,
                block.func_149750_m()              // getLightValue
        };
    }

    /** Files of all loaded legacy mods (their jars hold the mods' textures and other assets). */
    public static String[] modSources() {
        List<String> sources = new ArrayList<String>();
        for (ModContainer mod : Loader.instance().getActiveModList()) {
            File source = mod.getSource();
            String id = mod.getModId();
            if (source != null && source.isFile() && !id.equals("mcp") && !id.equals("FML") && !id.equals("Forge")) {
                sources.add(source.getAbsolutePath());
            }
        }
        return sources.toArray(new String[0]);
    }

    /** Client only; see {@link LegacyIcons}. */
    public static int registerIcons(Object[] legacyBlocks) {
        return LegacyIcons.registerIcons(legacyBlocks);
    }

    /** Client only; see {@link LegacyIcons#faceTextures}. */
    public static String[] faceTextures(Object legacyBlock, int meta) {
        return LegacyIcons.faceTextures(legacyBlock, meta);
    }

    // ---- Blocks: ids and names ----

    /** getBlockById; null for unused ids. */
    public static Object blockById(int id) {
        Block block = Block.func_149729_e(id);
        return block == Blocks.field_150350_a && id != 0 ? null : block; // unused ids return air
    }

    /** getIdFromBlock */
    public static int blockId(Object block) {
        return Block.func_149682_b((Block) block);
    }

    /** Registry name, e.g. "minecraft:planks". */
    public static String blockName(Object block) {
        return Block.field_149771_c.func_148750_c(block); // blockRegistry.getNameForObject
    }

    /** The legacy translation key of a block, e.g. "tile.counter_block.name". */
    public static String blockTranslationKey(Object block) {
        return ((Block) block).func_149739_a() + ".name"; // getUnlocalizedName
    }

    // ---- Items ----

    /** Registry names of items added by legacy mods, excluding the item forms of blocks. */
    public static String[] modItemNames() {
        List<String> names = new ArrayList<String>();
        for (Object key : Item.field_150901_e.func_148742_b()) { // itemRegistry.getKeys()
            String name = String.valueOf(key);
            if (!name.startsWith("minecraft:") && !(Item.field_150901_e.func_82594_a(name) instanceof ItemBlock)) {
                names.add(name);
            }
        }
        return names.toArray(new String[0]);
    }

    public static Object item(String name) {
        return Item.field_150901_e.func_82594_a(name); // itemRegistry.getObject
    }

    /** {max stack size, max damage, has subtypes (1/0)}. */
    public static int[] itemInfo(Object legacyItem) {
        Item item = (Item) legacyItem;
        return new int[]{
                item.func_77639_j(),           // getItemStackLimit
                item.func_77612_l(),           // getMaxDamage
                item.func_77614_k() ? 1 : 0    // getHasSubtypes
        };
    }

    /** The legacy translation key for an item with a damage value, e.g. "item.gem.ruby.name". */
    public static String itemTranslationKey(Object legacyItem, int meta) {
        return ((Item) legacyItem).func_77667_c(new ItemStack((Item) legacyItem, 1, meta)) + ".name"; // getUnlocalizedName(stack)
    }

    /** Client only; see {@link LegacyIcons}. */
    public static int registerItemIcons(Object[] legacyItems) {
        return LegacyIcons.registerItemIcons(legacyItems);
    }

    /** Client only; see {@link LegacyIcons#itemTexture}. */
    public static String itemTexture(Object legacyItem, int meta) {
        return LegacyIcons.itemTexture(legacyItem, meta);
    }

    /** Client only; see {@link LegacyIcons#itemSubtypes}. */
    public static int[] itemSubtypes(Object legacyItem) {
        return LegacyIcons.itemSubtypes(legacyItem);
    }

    // ---- Translations ----

    /**
     * Translations mods registered in code (FML's LanguageRegistry), as {language, key, value}
     * triples. Translations from .lang files are read by the host straight from the mod jars.
     */
    @SuppressWarnings("unchecked")
    public static String[][] codeTranslations() throws ReflectiveOperationException {
        Field field = LanguageRegistry.class.getDeclaredField("modLanguageData");
        field.setAccessible(true);
        Map<String, Properties> languages = (Map<String, Properties>) field.get(LanguageRegistry.instance());
        List<String[]> result = new ArrayList<String[]>();
        for (Map.Entry<String, Properties> language : languages.entrySet()) {
            for (String key : language.getValue().stringPropertyNames()) {
                result.add(new String[]{language.getKey(), key, language.getValue().getProperty(key)});
            }
        }
        return result.toArray(new String[0][]);
    }

    // ---- Client sync (1.7.10 description packets) ----

    /**
     * What the tile entity's description packet (getDescriptionPacket) would have sent to
     * clients, as NBT bytes {action, data}; null if it sends nothing, as in 1.7.10.
     */
    public static byte[] descriptionTag(Object tileEntity) throws IOException {
        Packet packet = ((TileEntity) tileEntity).func_145844_m(); // getDescriptionPacket
        if (!(packet instanceof S35PacketUpdateTileEntity)) {
            return null;
        }
        S35PacketUpdateTileEntity update = (S35PacketUpdateTileEntity) packet;
        NBTTagCompound tag = new NBTTagCompound();
        tag.func_74768_a("action", update.func_148853_f()); // setInteger(getTileEntityType)
        NBTTagCompound data = update.func_148857_g();      // getNbtCompound
        if (data != null) {
            tag.func_74782_a("data", data);                 // setTag
        }
        return toBytes(tag);
    }

    /** Delivers a description to a client-side tile entity, as the 1.7.10 network handler did. */
    public static void applyDescription(Object tileEntity, byte[] description) throws IOException {
        TileEntity tile = (TileEntity) tileEntity;
        NBTTagCompound tag = fromBytes(description);
        NBTTagCompound data = tag.func_74775_l("data"); // getCompoundTag
        S35PacketUpdateTileEntity packet = new S35PacketUpdateTileEntity(
                tile.field_145851_c, tile.field_145848_d, tile.field_145849_e, tag.func_74762_e("action"), data);
        tile.onDataPacket(null, packet);
    }

    public static boolean hasTileEntity(Object block, int meta) {
        return ((Block) block).hasTileEntity(meta);
    }

    public static Object createTileEntity(Object world, Object block, int meta, int x, int y, int z) {
        TileEntity tileEntity = ((Block) block).createTileEntity((World) world, meta);
        if (tileEntity != null) {
            place(tileEntity, (World) world, x, y, z);
        }
        return tileEntity;
    }

    /** Rebuilds a tile entity from NBT saved by {@link #save}. */
    public static Object load(Object world, byte[] nbt, int x, int y, int z) throws IOException {
        NBTTagCompound tag = fromBytes(nbt);
        TileEntity tileEntity = TileEntity.func_145827_c(tag); // createAndLoadEntity
        if (tileEntity != null) {
            place(tileEntity, (World) world, x, y, z);
        }
        return tileEntity;
    }

    public static byte[] save(Object tileEntity) throws IOException {
        NBTTagCompound tag = new NBTTagCompound();
        ((TileEntity) tileEntity).func_145841_b(tag); // writeToNBT
        return toBytes(tag);
    }

    public static boolean canUpdate(Object tileEntity) {
        return ((TileEntity) tileEntity).canUpdate();
    }

    /** updateEntity */
    public static void tick(Object tileEntity) {
        ((TileEntity) tileEntity).func_145845_h();
    }

    /** invalidate */
    public static void remove(Object tileEntity) {
        ((TileEntity) tileEntity).func_145843_s();
    }

    public static String describe(Object legacyObject) {
        return String.valueOf(legacyObject);
    }

    private static byte[] toBytes(NBTTagCompound tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompressedStreamTools.func_74800_a(tag, new DataOutputStream(bytes)); // write
        return bytes.toByteArray();
    }

    private static NBTTagCompound fromBytes(byte[] bytes) throws IOException {
        return CompressedStreamTools.func_74794_a(new DataInputStream(new ByteArrayInputStream(bytes))); // read
    }

    /** What World.setTileEntity does for a new tile entity. */
    private static void place(TileEntity tileEntity, World world, int x, int y, int z) {
        tileEntity.func_145834_a(world); // setWorldObj
        tileEntity.field_145851_c = x;   // xCoord
        tileEntity.field_145848_d = y;   // yCoord
        tileEntity.field_145849_e = z;   // zCoord
        tileEntity.func_145829_t();      // validate
    }
}
