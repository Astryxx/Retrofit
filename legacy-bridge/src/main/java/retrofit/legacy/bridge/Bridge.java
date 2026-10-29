package retrofit.legacy.bridge;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.registry.LanguageRegistry;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
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

    public static Object createWorld(HostWorld host, int dimension) throws Exception {
        Object world = LegacyWorlds.create(host, dimension);
        Events.worldLoaded(world);
        return world;
    }

    /** Registry names of all blocks added by legacy mods ("modid:name"), but those of mods left out. */
    public static String[] modBlockNames() {
        List<String> names = new ArrayList<String>();
        for (Object key : Block.field_149771_c.func_148742_b()) { // blockRegistry.getKeys()
            String name = String.valueOf(key);
            if (!name.startsWith("minecraft:") && !leftOut(name)) {
                names.add(name);
            }
        }
        return names.toArray(new String[0]);
    }

    /**
     * Whether a registry name ("modid:name") is a left-out mod's. Such a mod keeps what it registered before
     * it failed, but none of it goes to the host: its registries are synced to joining players, and a mod
     * failing on one side only (on a dedicated server, from client-only code) left entries the other side
     * hasn't ("Missing registry data"), with no way to join.
     */
    static boolean leftOut(String registryName) {
        int colon = registryName.indexOf(':');
        if (colon < 0) {
            return false;
        }
        String mod = registryName.substring(0, colon);
        for (String id : retrofit.legacy.boot.ModFailures.leftOut().keySet()) {
            if (id.equalsIgnoreCase(mod)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a class comes from a left-out mod's jar (for what has no registry name: potions, enchantments, fluids). */
    static boolean leftOut(Class<?> type) {
        List<File> sources = retrofit.legacy.boot.ModFailures.leftOutSources();
        if (sources.isEmpty() || type.getClassLoader() == null) {
            return false;
        }
        try {
            // the jar the class file comes from ("jar:file:/.../mod.jar!/a/B.class"): legacy classes have no code source
            java.net.URL url = type.getClassLoader().getResource(type.getName().replace('.', '/') + ".class");
            if (url == null || !"jar".equals(url.getProtocol())) {
                return false;
            }
            String path = url.getPath(); // "file:/.../mod.jar!/a/B.class"
            File jar = new File(new java.net.URI(path.substring(0, path.indexOf("!/")))).getCanonicalFile();
            for (File source : sources) {
                if (source.getCanonicalFile().equals(jar)) {
                    return true;
                }
            }
        } catch (Exception e) {
            // not from a jar: not a left-out mod's
        }
        return false;
    }

    /** getBlockFromName */
    public static Object block(String name) {
        return Block.func_149684_b(name);
    }

    /**
     * How a legacy block wants to be rendered and lit: {render type (0 = plain cube), opaque cube
     * (1/0, see opaqueOnAnyGraphics), render pass (0 solid, 1 translucent), light value (0-15),
     * icons from the world (1/0, see worldIcons)}.
     */
    public static int[] blockRenderInfo(Object legacyBlock) {
        Block block = (Block) legacyBlock;
        int renderType = 0;
        int renderPass = 0;
        try {
            renderType = block.func_149645_b();    // getRenderType
            renderPass = block.func_149701_w();    // getRenderBlockPass
        } catch (RuntimeException | LinkageError clientOnly) {
            // Client-only in 1.7.10, and on servers mods' render state isn't set up (Pistronics' blocks ask
            // their client-side render manager for the render type): a server doesn't render, so the
            // defaults are fine; on a client the block draws as a plain cube.
        }
        return new int[]{
                renderType,
                opaqueOnAnyGraphics(block) ? 1 : 0,
                renderPass,
                block.func_149750_m(),             // getLightValue
                worldIcons(block) ? 1 : 0
        };
    }

    /** Some TESR blocks keep render type 0 but suppress every ordinary face (DarkCore/TARDIS). */
    public static boolean blockRendersFaces(Object legacyBlock, int meta) {
        Block block = (Block) legacyBlock;
        if (!block.hasTileEntity(meta)) return true;
        World world = IsolatedHost.with(block, meta);
        int[][] offsets = {{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};
        for (int side = 0; side < 6; side++) {
            int[] at = offsets[side];
            if (block.func_149646_a(world, at[0], at[1], at[2], side)) return true;
        }
        return false;
    }

    /** Client, each tick: isOpaqueCube of these blocks as they answer it now (some answer by where the player is). */
    public static boolean[] opaqueCubesNow(Object[] legacyBlocks) {
        boolean[] opaque = new boolean[legacyBlocks.length];
        for (int i = 0; i < legacyBlocks.length; i++) {
            try {
                opaque[i] = ((Block) legacyBlocks[i]).func_149662_c(); // isOpaqueCube
            } catch (RuntimeException e) {
                opaque[i] = false;
            }
        }
        return opaque;
    }

    /**
     * isOpaqueCube, with fancy and with fast graphics: leaves (and blocks that follow vanilla's) are
     * opaque only on fast graphics, and count as not opaque, as 1.20.1's own leaves, so the faces
     * seen through them are drawn. Asked the same way on servers, which have no graphics setting, so
     * both sides agree (light goes through what doesn't occlude). The setting is put back as it was.
     */
    private static boolean opaqueOnAnyGraphics(Block block) {
        Block[] leaves = {net.minecraft.init.Blocks.field_150362_t, net.minecraft.init.Blocks.field_150361_u};
        try {
            if (leavesFancy == null) {
                leavesFancy = net.minecraft.block.BlockLeavesBase.class.getDeclaredField("field_150121_P"); // fancy graphics
                leavesFancy.setAccessible(true);
            }
            boolean[] was = new boolean[leaves.length];
            for (int i = 0; i < leaves.length; i++) {
                was[i] = leavesFancy.getBoolean(leaves[i]);
            }
            try {
                return opaqueWith(block, leaves, true) && opaqueWith(block, leaves, false);
            } finally {
                for (int i = 0; i < leaves.length; i++) {
                    leavesFancy.setBoolean(leaves[i], was[i]);
                }
            }
        } catch (ReflectiveOperationException e) {
            return block.func_149662_c(); // isOpaqueCube as it is
        }
    }

    /** BlockLeavesBase's fancy graphics flag, which isOpaqueCube reads (setGraphicsLevel is client-only). */
    private static java.lang.reflect.Field leavesFancy;

    private static boolean opaqueWith(Block block, Block[] leaves, boolean fancy) throws IllegalAccessException {
        for (Block vanilla : leaves) {
            leavesFancy.setBoolean(vanilla, fancy); // only vanilla's leaves were told the setting, in 1.7.10 too
        }
        return block.func_149662_c(); // isOpaqueCube
    }

    /**
     * Whether the block picks its icons from the world (getIcon(IBlockAccess, x, y, z, side)):
     * machines facing where they were placed, connected textures, blocks showing what they hold.
     * The metadata alone doesn't say what they look like.
     */
    private static boolean worldIcons(Block block) {
        try {
            return Overrides.overrides(block.getClass(), Block.class, "func_149673_e", java.lang.invoke.MethodType.methodType(
                    net.minecraft.util.IIcon.class, net.minecraft.world.IBlockAccess.class, int.class, int.class, int.class, int.class));
        } catch (LinkageError dedicatedServer) {
            return false; // IIcon is client-only in 1.7.10
        }
    }

    /**
     * The bounds a block was drawn with as an item (setBlockBoundsForItemRender): min x y z, max x y z;
     * null when the block doesn't set them (Block's own does nothing: the item is drawn as the block).
     */
    public static double[] itemRenderBounds(Object legacyBlock) {
        synchronized (LOCK) {
            Block block = (Block) legacyBlock;
            if (!Overrides.overrides(block.getClass(), Block.class, "func_149683_g", java.lang.invoke.MethodType.methodType(void.class))) {
                return null;
            }
            block.func_149683_g(); // setBlockBoundsForItemRender
            return new double[]{block.func_149704_x(), block.func_149665_z(), block.func_149706_B(),
                    block.func_149753_y(), block.func_149669_A(), block.func_149693_C()}; // min x y z, max x y z
        }
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

    /**
     * Client only: a file as 1.7.10's resource manager has it (every resource pack in it, those mods add at
     * run time too, which may make the file when asked), or null.
     */
    public static byte[] legacyResource(String domain, String path) {
        try {
            java.io.InputStream in = net.minecraft.client.Minecraft.func_71410_x().func_110442_L() // getResourceManager
                    .func_110536_a(new net.minecraft.util.ResourceLocation(domain, path)).func_110527_b(); // getResource, getInputStream
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int read; (read = in.read(buffer)) > 0; ) {
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null; // not there (or the pack failed to make it)
        }
    }

    /** "modid@version" of every loaded legacy mod, sorted. */
    public static String modList() {
        List<String> mods = new ArrayList<String>();
        for (ModContainer mod : Loader.instance().getActiveModList()) {
            mods.add(mod.getModId() + "@" + mod.getVersion());
        }
        java.util.Collections.sort(mods);
        return mods.toString();
    }

    /** The loaded legacy mods, "modid\tversion" per line: what a server and a client tell each other when a player joins. */
    public static String modVersions() {
        StringBuilder lines = new StringBuilder();
        for (ModContainer mod : Loader.instance().getActiveModList()) {
            lines.append(mod.getModId()).append('\t').append(mod.getVersion()).append('\n');
        }
        return lines.toString();
    }

    /**
     * FML 1.7.10's handshake check of the other side's mods (see modVersions): each mod here runs its own network
     * check against them (fromServer: they are a server's, as a client checked; else a client's, as a server
     * checked). A client may have mods the server hasn't (minimaps, Mouse Tweaks); what a server has, its clients
     * need, at versions its mods accept. The mods that refuse, as "Name: server 1.2, client missing"; empty if
     * all accept.
     */
    public static String[] checkRemoteMods(String remote, boolean fromServer) {
        java.util.Map<String, String> versions = new java.util.HashMap<String, String>();
        for (String line : remote.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) {
                versions.put(line.substring(0, tab), line.substring(tab + 1));
            }
        }
        cpw.mods.fml.relauncher.Side side = fromServer ? cpw.mods.fml.relauncher.Side.SERVER : cpw.mods.fml.relauncher.Side.CLIENT;
        List<String> refused = new ArrayList<String>();
        java.util.Set<ModContainer> active = new java.util.HashSet<ModContainer>(Loader.instance().getActiveModList());
        for (java.util.Map.Entry<ModContainer, cpw.mods.fml.common.network.internal.NetworkModHolder> entry
                : cpw.mods.fml.common.network.NetworkRegistry.INSTANCE.registry().entrySet()) {
            if (!active.contains(entry.getKey())) {
                continue; // left out here: it registered its check before it failed, but isn't part of the game
            }
            String id = entry.getKey().getModId();
            boolean accepts;
            try {
                accepts = entry.getValue().check(versions, side);
            } catch (RuntimeException e) {
                accepts = true; // a check that fails refuses nobody
            }
            if (!accepts) {
                String here = entry.getKey().getVersion();
                String there = versions.containsKey(id) ? versions.get(id) : "missing";
                refused.add(entry.getKey().getName() + ": server " + (fromServer ? there : here) + ", client " + (fromServer ? here : there));
            }
        }
        return refused.toArray(new String[0]);
    }

    /** Client only: every block and item registers its icons (see {@link LegacyIcons#registerAll}). */
    public static int registerAllIcons() {
        return LegacyIcons.registerAll();
    }

    /** Client only: recorded icon names, kind 0 = block textures, 1 = item textures. */
    public static String[] iconNames(int kind) {
        return LegacyIcons.iconNames(kind);
    }

    /** Client only; see {@link LegacyIcons#iconsPlaced}. */
    public static void iconsPlaced() {
        synchronized (LOCK) {
            LegacyIcons.iconsPlaced();
        }
    }

    /** Client only: an icon's place in the host's atlas. */
    public static void setIconUV(int kind, String name, float minU, float maxU, float minV, float maxV, int width, int height) {
        LegacyIcons.setIconUV(kind, name, minU, maxU, minV, maxV, width, height);
    }

    /** Client only; see {@link LegacyRendering#renderArmorModel}. */
    public static int renderArmorModel(Object legacyStack, Object wearer, int slot, float limbSwing, float limbSwingAmount, float age,
                                       float headYaw, float headPitch, float partialTicks, int flags) {
        return LegacyRendering.renderArmorModel(legacyStack, wearer, slot, limbSwing, limbSwingAmount, age, headYaw, headPitch, partialTicks, flags);
    }

    /** Client only; see {@link LegacyRendering#renderEntity}. */
    public static int renderEntity(Object entity, float yaw, float partialTicks) {
        return LegacyRendering.renderEntity(entity, yaw, partialTicks);
    }

    /** Client only; see {@link LegacyIcons#faceTextures}. */
    public static String[] faceTextures(Object legacyBlock, int meta) {
        return LegacyIcons.faceTextures(legacyBlock, meta);
    }

    /**
     * Serializes everything that uses shared legacy state: the isolated world and the GL
     * emulation (one global context in 1.7.10). The host holds it around rendering calls until it
     * has read the capture, since chunk meshing runs on several threads.
     */
    public static final Object LOCK = new Object();

    public static Object lock() {
        return LOCK;
    }

    // ---- Shapes ----

    /**
     * A block's shape for a metadata value, in an otherwise empty world: {selection box (6
     * values), then each collision box (6 values each)}. Uses the block's own
     * setBlockBoundsBasedOnState and addCollisionBoxesToList, so multi-box and meta-dependent
     * shapes come out right; shapes that depend on neighbours get their stand-alone form.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static double[] blockShape(Object legacyBlock, int meta) {
        synchronized (LOCK) {
            Block block = (Block) legacyBlock;
            World world = IsolatedHost.with(block, meta);
            block.func_149719_a(world, 0, 0, 0); // setBlockBoundsBasedOnState
            double[] selection = {block.func_149704_x(), block.func_149665_z(), block.func_149706_B(),
                    block.func_149753_y(), block.func_149669_A(), block.func_149693_C()}; // min x y z, max x y z
            if (block.func_149633_g(world, 0, 0, 0) == null) java.util.Arrays.fill(selection, 0);
            List boxes = new ArrayList();
            AxisAlignedBB mask = AxisAlignedBB.func_72330_a(-1, -1, -1, 2, 2, 2); // getBoundingBox
            block.func_149743_a(world, 0, 0, 0, mask, boxes, null); // addCollisionBoxesToList
            double[] shape = new double[6 + boxes.size() * 6];
            System.arraycopy(selection, 0, shape, 0, 6);
            for (int i = 0; i < boxes.size(); i++) {
                AxisAlignedBB box = (AxisAlignedBB) boxes.get(i);
                int o = 6 + i * 6;
                shape[o] = box.field_72340_a;     // minX
                shape[o + 1] = box.field_72338_b; // minY
                shape[o + 2] = box.field_72339_c; // minZ
                shape[o + 3] = box.field_72336_d; // maxX
                shape[o + 4] = box.field_72337_e; // maxY
                shape[o + 5] = box.field_72334_f; // maxZ
            }
            return shape;
        }
    }

    /**
     * Whether a block's shape depends on where it is (its neighbours, its tile entity: fences, panes, cables,
     * covers): it overrides setBlockBoundsBasedOnState, addCollisionBoxesToList or getCollisionBoundingBoxFromPool.
     */
    public static boolean shapeDependsOnPlace(Object legacyBlock) {
        Class<?> type = legacyBlock.getClass();
        return Overrides.overrides(type, Block.class, "func_149719_a", java.lang.invoke.MethodType.methodType(void.class,
                net.minecraft.world.IBlockAccess.class, int.class, int.class, int.class)) // setBlockBoundsBasedOnState
                || Overrides.overrides(type, Block.class, "func_149743_a", java.lang.invoke.MethodType.methodType(void.class, World.class,
                int.class, int.class, int.class, AxisAlignedBB.class, List.class, net.minecraft.entity.Entity.class)) // addCollisionBoxesToList
                || Overrides.overrides(type, Block.class, "func_149668_a", java.lang.invoke.MethodType.methodType(AxisAlignedBB.class, World.class,
                int.class, int.class, int.class)); // getCollisionBoundingBoxFromPool
    }

    /**
     * Shape questions in the world: their own lock, not LOCK, which the client holds while it draws (a server
     * thread's collisions would wait for each frame). 1.7.10's integrated server and client too set a block's
     * bounds from both threads.
     */
    private static final Object SHAPES = new Object();

    /** A block's shape where it is in a world (see blockShape for the layout; coordinates relative to the block). */
    public static double[] blockShapeAt(Object legacyWorld, int x, int y, int z) {
        synchronized (SHAPES) {
            World world = (World) legacyWorld;
            Block block = world.func_147439_a(x, y, z); // getBlock
            block.func_149719_a(world, x, y, z); // setBlockBoundsBasedOnState
            double[] selection = {block.func_149704_x(), block.func_149665_z(), block.func_149706_B(),
                    block.func_149753_y(), block.func_149669_A(), block.func_149693_C()};
            if (block.func_149633_g(world, x, y, z) == null) java.util.Arrays.fill(selection, 0);
            List boxes = new ArrayList();
            AxisAlignedBB mask = AxisAlignedBB.func_72330_a(x - 1, y - 1, z - 1, x + 2, y + 2, z + 2); // getBoundingBox
            block.func_149743_a(world, x, y, z, mask, boxes, null); // addCollisionBoxesToList
            double[] shape = new double[6 + boxes.size() * 6];
            System.arraycopy(selection, 0, shape, 0, 6);
            for (int i = 0; i < boxes.size(); i++) {
                AxisAlignedBB box = (AxisAlignedBB) boxes.get(i);
                int o = 6 + i * 6;
                shape[o] = box.field_72340_a - x;
                shape[o + 1] = box.field_72338_b - y;
                shape[o + 2] = box.field_72339_c - z;
                shape[o + 3] = box.field_72336_d - x;
                shape[o + 4] = box.field_72337_e - y;
                shape[o + 5] = box.field_72334_f - z;
            }
            return shape;
        }
    }

    // ---- Rendering (client only; callers hold LOCK until they've read the capture) ----

    public static void prepareFrame(Object legacyWorld, double x, double y, double z, float yaw, float pitch, float partialTicks) {
        LegacyRendering.prepareFrame(legacyWorld, x, y, z, yaw, pitch, partialTicks);
    }

    public static int bakeBlock(Object legacyBlock, int meta) {
        return LegacyRendering.bakeBlock(legacyBlock, meta);
    }

    public static int renderBlockAt(retrofit.api.HostWorld region, int x, int y, int z) {
        return LegacyRendering.renderBlockAt(region, x, y, z);
    }

    public static boolean hasSpecialRenderer(Object tileEntity) {
        return LegacyRendering.hasSpecialRenderer(tileEntity);
    }

    public static int renderTileEntity(Object tileEntity, float partialTicks, double x, double y, double z) {
        return LegacyRendering.renderTileEntity(tileEntity, partialTicks, x, y, z);
    }

    public static boolean hasItemRenderer(Object legacyItem, int meta, String type) {
        return LegacyRendering.hasItemRenderer(legacyItem, meta, type);
    }

    public static int renderItem(Object legacyItem, int meta, String type) {
        return LegacyRendering.renderItem(legacyItem, meta, type);
    }

    /** Client only; see {@link LegacyRendering#itemLight}. Hold {@link #LOCK}. */
    public static void itemLight(int packedLight) {
        LegacyRendering.itemLight(packedLight);
    }

    /** Client only; see {@link LegacyRendering#renderItemStack}. Hold {@link #LOCK}. */
    public static int renderItemStack(Object legacyStack, String type) {
        return LegacyRendering.renderItemStack(legacyStack, type);
    }

    /** Client: see LegacyRendering.renderBlockItem. */
    public static int renderBlockItem(Object legacyBlock, int meta, int damage) {
        return LegacyRendering.renderBlockItem(legacyBlock, meta, damage);
    }

    /** Client: see LegacyRendering.blockItemIn3d. */
    public static boolean blockItemIn3d(Object legacyBlock) {
        return LegacyRendering.blockItemIn3d(legacyBlock);
    }

    /** Client: see LegacyRendering.renderAtmosphere. */
    public static int renderAtmosphere(Object legacyWorld, int kind, float partialTicks) {
        return LegacyRendering.renderAtmosphere(legacyWorld, kind, partialTicks);
    }

    /** Client: see LegacyRendering.renderSky. */
    public static int renderSky(Object legacyWorld, float partialTicks) {
        return LegacyRendering.renderSky(legacyWorld, partialTicks);
    }

    /** Client: see LegacyRendering.setColorizers. */
    public static void setColorizers(int[] grass, int[] foliage) {
        LegacyRendering.setColorizers(grass, foliage);
    }

    /** Client: see LegacyRendering.blockColorAt. */
    public static int blockColorAt(retrofit.api.HostWorld region, int x, int y, int z) {
        return LegacyRendering.blockColorAt(region, x, y, z);
    }

    /** Client: see LegacyRendering.setFancyGraphics. */
    public static void setFancyGraphics(boolean fancy) {
        synchronized (LOCK) {
            LegacyRendering.setFancyGraphics(fancy);
        }
    }

    /** Client: see LegacyRendering.blockTinted. */
    public static boolean blockTinted(Object legacyBlock) {
        return LegacyRendering.blockTinted(legacyBlock);
    }

    /** Client: see LegacyRendering.itemRendererHelpers. */
    public static int itemRendererHelpers(Object legacyItem, int meta, String type) {
        return LegacyRendering.itemRendererHelpers(legacyItem, meta, type);
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

    /** Registry names of items added by legacy mods, excluding the item forms of blocks and left-out mods' items. */
    public static String[] modItemNames() {
        List<String> names = new ArrayList<String>();
        for (Object key : Item.field_150901_e.func_148742_b()) { // itemRegistry.getKeys()
            String name = String.valueOf(key);
            if (!name.startsWith("minecraft:") && !(Item.field_150901_e.func_82594_a(name) instanceof ItemBlock) && !leftOut(name)) {
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

    /**
     * The name the mod gives a stack (Item.getItemStackDisplayName): many compose it from the stack
     * (Tinkers' tool parts: material and part), which a translation key can't express.
     */
    public static String itemDisplayName(Object legacyStack) {
        ItemStack stack = (ItemStack) legacyStack;
        return stack.func_77973_b().func_77653_i(stack); // getItem().getItemStackDisplayName(stack)
    }

    /** Client only; see {@link LegacyIcons#customSprite}. */
    public static Object[] customSprite(int kind, String name) {
        return LegacyIcons.customSprite(kind, name);
    }

    /** Client only; see {@link LegacyIcons#itemTexture}. */
    public static String itemTexture(Object legacyItem, int meta) {
        return LegacyIcons.itemTexture(legacyItem, meta);
    }

    /** Client only; see {@link LegacyIcons#itemPassTextures}. */
    public static String[] itemPassTextures(Object legacyItem, int meta, int max) {
        return LegacyIcons.itemPassTextures(legacyItem, meta, max);
    }

    /** Client only; see {@link LegacyIcons#iconRegion}. */
    public static Object[] iconRegion(String name) {
        return LegacyIcons.iconRegion(name);
    }

    /** Client only; see {@link LegacyIcons#iconFolder}. */
    public static String iconFolder(int kind, String name) {
        return LegacyIcons.iconFolder(kind, name);
    }

    /** Client only; see {@link LegacyIcons#itemHasEffect}. */
    public static boolean itemHasEffect(Object legacyStack) {
        return LegacyIcons.itemHasEffect(legacyStack);
    }

    /** Client only; see {@link LegacyIcons#stackIcons}. */
    public static boolean stackIcons(Object legacyItem) {
        return LegacyIcons.stackIcons(legacyItem);
    }

    /** Client only; see {@link LegacyIcons#stackPassTextures}. */
    public static String[] stackPassTextures(Object legacyStack, int max) {
        return LegacyIcons.stackPassTextures(legacyStack, max);
    }

    /** Client only; see {@link LegacyIcons#itemColor}. */
    public static int itemColor(Object legacyStack, int pass) {
        return LegacyIcons.itemColor(legacyStack, pass);
    }

    /** Client only; see {@link LegacyIcons#itemSubtypes}. */
    public static int[] itemSubtypes(Object legacyItem) {
        return LegacyIcons.itemSubtypes(legacyItem);
    }

    // ---- Item stacks ----

    /** A legacy ItemStack of a registered item ("modid:name"), or null if no such item exists. */
    public static Object makeStack(String name, int count, int damage, byte[] tag) throws IOException {
        Object item = Item.field_150901_e.func_82594_a(name); // itemRegistry.getObject
        return item instanceof Item ? makeStackOf(item, count, damage, tag) : null;
    }

    public static Object makeStackOf(Object legacyItem, int count, int damage, byte[] tag) throws IOException {
        ItemStack stack = new ItemStack((Item) legacyItem, count, damage);
        if (tag != null) {
            stack.func_77982_d(fromBytes(tag)); // setTagCompound
        }
        return stack;
    }

    /** {registry name, count, damage, tag bytes or null} of a legacy stack; null for none. */
    public static Object[] readStack(Object legacyStack) throws IOException {
        if (!(legacyStack instanceof ItemStack)) {
            return null;
        }
        ItemStack stack = (ItemStack) legacyStack;
        Item item = stack.func_77973_b(); // getItem
        if (item == null || stack.field_77994_a <= 0) { // stackSize
            return null;
        }
        NBTTagCompound tag = stack.func_77978_p(); // getTagCompound
        return new Object[]{Item.field_150901_e.func_148750_c(item), stack.field_77994_a, stack.func_77960_j(), // getNameForObject, getItemDamage
                tag == null ? null : toBytes(tag)};
    }

    /** getItemById; null for unused ids. */
    public static Object itemById(int id) {
        return Item.func_150899_d(id);
    }

    /** Changes when a stack's size, damage or tag changes (the host caches conversions by it). */
    public static int stackFingerprint(Object legacyStack) {
        ItemStack stack = (ItemStack) legacyStack;
        NBTTagCompound tag = stack.func_77978_p(); // getTagCompound
        return (stack.field_77994_a * 31 + stack.func_77960_j()) * 31 + (tag == null ? 0 : tag.hashCode());
    }

    /** The Item of a legacy stack (identity), or null. */
    public static Object stackItem(Object legacyStack) {
        return legacyStack instanceof ItemStack ? ((ItemStack) legacyStack).func_77973_b() : null;
    }

    /** Vanilla item names with {has subtypes 1/0, max damage}, for mapping 1.20.1 items back. */
    public static Object[][] vanillaItems() {
        List<Object[]> items = new ArrayList<Object[]>();
        for (Object key : Item.field_150901_e.func_148742_b()) { // itemRegistry.getKeys()
            String name = String.valueOf(key);
            Object item = Item.field_150901_e.func_82594_a(name);
            if (name.startsWith("minecraft:") && item instanceof Item) {
                items.add(new Object[]{name, ((Item) item).func_77614_k() ? 1 : 0, ((Item) item).func_77612_l()});
            }
        }
        return items.toArray(new Object[0][]);
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
        // Several mods override getTranslatedTabLabel instead of adding a LanguageRegistry key.
        // Ask the tab on the client so its own chosen name also reaches the modern UI.
        for (net.minecraft.creativetab.CreativeTabs tab : net.minecraft.creativetab.CreativeTabs.field_78032_a) {
            if (tab == null) continue;
            String label = tab.func_78013_b(); // getTabLabel
            String name = tab.func_78024_c(); // getTranslatedTabLabel
            if (name != null && !name.equals("itemGroup." + label)) {
                result.add(new String[]{"en_US", "itemGroup." + label, name});
            }
            result.add(new String[]{"en_US", "retrofit.creative_tab." + tab.func_78021_a(),
                    name == null || name.equals("itemGroup." + label) ? label : name});
        }
        return result.toArray(new String[0][]);
    }

    // ---- Client sync (1.7.10 description packets) ----

    /**
     * The tile entity's description packet (getDescriptionPacket), serialized with the packet's
     * own writePacketData exactly as 1.7.10 put it on the wire; null if it sends nothing.
     * (The packet's field getters are client-only, so the server can't take it apart.)
     */
    public static byte[] descriptionTag(Object tileEntity) throws IOException {
        Packet packet = ((TileEntity) tileEntity).func_145844_m(); // getDescriptionPacket
        if (!(packet instanceof S35PacketUpdateTileEntity)) {
            return null;
        }
        ByteBuf buffer = Unpooled.buffer();
        packet.func_148840_b(new PacketBuffer(buffer)); // writePacketData
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        return bytes;
    }

    /** Rebuilds the description packet on the client and hands it to onDataPacket, as 1.7.10's network handler did. */
    public static void applyDescription(Object tileEntity, byte[] description) throws IOException {
        S35PacketUpdateTileEntity packet = new S35PacketUpdateTileEntity();
        packet.func_148837_a(new PacketBuffer(Unpooled.wrappedBuffer(description))); // readPacketData
        ((TileEntity) tileEntity).onDataPacket(null, packet);
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

    /**
     * Whether a block's tile entity (for a metadata) ever ticks: 1.7.10 asked canUpdate() once, when
     * the tile entity joined the world, and never ticked the others (ores, decorations, cables). Asked
     * of a sample tile entity in an empty world; true if it can't be made there.
     */
    public static boolean tileTicks(Object legacyBlock, int meta) {
        try {
            World world = IsolatedHost.with(legacyBlock, meta);
            TileEntity tile = ((Block) legacyBlock).createTileEntity(world, meta);
            return tile == null || tile.canUpdate();
        } catch (Throwable t) {
            return true;
        }
    }

    /** updateEntity */
    public static void tick(Object tileEntity) {
        ((TileEntity) tileEntity).func_145845_h();
    }

    /** invalidate */
    public static void remove(Object tileEntity) {
        WorldGlue.leaveTileList((TileEntity) tileEntity); // out of loadedTileEntityList
        ((TileEntity) tileEntity).func_145843_s(); // invalidate
    }

    /** Self-test (PlayTest): how many tile entities at a position are on the world's loadedTileEntityList. */
    public static int testTilesListed(Object world, int x, int y, int z) {
        int count = 0;
        for (Object tile : ((World) world).field_147482_g) {
            TileEntity tileEntity = (TileEntity) tile;
            if (tileEntity.field_145851_c == x && tileEntity.field_145848_d == y && tileEntity.field_145849_e == z) {
                count++;
            }
        }
        return count;
    }

    /** Its chunk unloads (the tile entity is saved and comes back with the chunk). */
    public static void unloadTile(Object tileEntity) {
        WorldGlue.leaveTileList((TileEntity) tileEntity);
        ((TileEntity) tileEntity).onChunkUnload();
    }

    /** The slots of a tile entity's inventory (legacy stacks, null for empty slots), or null if it has none. */
    public static Object[] inventoryStacks(Object tileEntity) {
        if (!(tileEntity instanceof net.minecraft.inventory.IInventory)) {
            return null;
        }
        net.minecraft.inventory.IInventory inventory = (net.minecraft.inventory.IInventory) tileEntity;
        Object[] stacks = new Object[inventory.func_70302_i_()]; // getSizeInventory
        for (int slot = 0; slot < stacks.length; slot++) {
            stacks[slot] = inventory.func_70301_a(slot); // getStackInSlot
        }
        return stacks;
    }

    /** The entity name a mob spawner's tile entity spawns ("Zombie"), or null if it is no spawner. */
    public static String spawnerEntity(Object tileEntity) {
        if (!(tileEntity instanceof net.minecraft.tileentity.TileEntityMobSpawner)) {
            return null;
        }
        return ((net.minecraft.tileentity.TileEntityMobSpawner) tileEntity).func_145881_a().func_98276_e(); // getSpawnerBaseLogic().getEntityNameToSpawn()
    }

    /**
     * Self-test of host inventories as legacy code sees them (the mod sweep): the host put 10
     * cobblestone in slot 0 of the chest at a position and a furnace east of it. Takes 3 out
     * (decrStackSize), puts 2 diamonds in slot 1, and takes 2 more by changing the stack in place
     * and calling markDirty, as many mods do. The host then expects 5 cobblestone and 2 diamonds.
     */
    public static String checkHostInventory(Object legacyWorld, int x, int y, int z) {
        World world = (World) legacyWorld;
        TileEntity chest = world.func_147438_o(x, y, z); // getTileEntity
        if (!(chest instanceof net.minecraft.tileentity.TileEntityChest)) {
            return "FAILED: chest tile is " + chest;
        }
        net.minecraft.inventory.IInventory inventory = (net.minecraft.inventory.IInventory) chest;
        ItemStack first = inventory.func_70301_a(0);
        if (first == null || first.func_77973_b() != Item.func_150898_a(Blocks.field_150347_e) || first.field_77994_a != 10) { // getItem, getItemFromBlock(cobblestone)
            return "FAILED: slot 0 is " + first;
        }
        ItemStack taken = inventory.func_70298_a(0, 3);
        if (taken == null || taken.field_77994_a != 3) {
            return "FAILED: decrStackSize gave " + taken;
        }
        inventory.func_70299_a(1, new ItemStack(net.minecraft.init.Items.field_151045_i, 2)); // diamond
        ItemStack again = inventory.func_70301_a(0);
        again.field_77994_a -= 2;
        inventory.func_70296_d(); // markDirty
        TileEntity furnace = world.func_147438_o(x + 1, y, z);
        if (!(furnace instanceof net.minecraft.tileentity.TileEntityFurnace)) {
            return "FAILED: furnace tile is " + furnace;
        }
        int[] top = ((net.minecraft.inventory.ISidedInventory) furnace).func_94128_d(1);
        return "ok: chest of " + inventory.func_70302_i_() + " slots, furnace top slots " + java.util.Arrays.toString(top);
    }

    /** Item.getMetadata(damage): the block metadata an item block places for a damage value. */
    public static int itemBlockMeta(Object legacyItem, int damage) {
        return ((Item) legacyItem).func_77647_b(damage); // getMetadata
    }

    // ---- Enchantments ----

    /** {id, translation key, weight, max level, type name} of mods' enchantments (vanilla's are 0 to 62 and 1.20.1's own). */
    public static Object[][] legacyEnchantments() {
        List<Object[]> result = new ArrayList<Object[]>();
        net.minecraft.enchantment.Enchantment[] all = net.minecraft.enchantment.Enchantment.field_77331_b; // enchantmentsList
        for (int id = 0; id < all.length; id++) {
            net.minecraft.enchantment.Enchantment enchantment = all[id];
            if (enchantment == null || enchantment.getClass().getName().startsWith("net.minecraft.") || leftOut(enchantment.getClass())) {
                continue;
            }
            result.add(new Object[]{id, enchantment.func_77320_a(), enchantment.func_77324_c(), enchantment.func_77325_b(), // getName, getWeight, getMaxLevel
                    enchantment.field_77351_y == null ? "all" : enchantment.field_77351_y.name(), enchantment.getClass().getName()}); // type
        }
        return result.toArray(new Object[0][]);
    }

    // ---- Creative tabs ----

    /** {index, label} of the creative tabs mods added (vanilla's are 0 to 11). Fields, not methods: these work on servers too. */
    public static Object[][] legacyCreativeTabs() throws ReflectiveOperationException {
        Field label = net.minecraft.creativetab.CreativeTabs.class.getDeclaredField("field_78034_o"); // tabLabel
        label.setAccessible(true);
        List<Object[]> tabs = new ArrayList<Object[]>();
        net.minecraft.creativetab.CreativeTabs[] all = net.minecraft.creativetab.CreativeTabs.field_78032_a; // creativeTabArray
        for (int i = 12; i < all.length; i++) {
            if (all[i] != null) {
                tabs.add(new Object[]{i, label.get(all[i])});
            }
        }
        return tabs.toArray(new Object[0][]);
    }

    /**
     * Client: the mod items a creative tab shows, as the mod lists them (getSubItems: subtypes,
     * prebuilt tools, filled containers), in registration order.
     */
    @SuppressWarnings("unchecked")
    public static Object[] legacyCreativeTabContents(int index) {
        net.minecraft.creativetab.CreativeTabs[] all = net.minecraft.creativetab.CreativeTabs.field_78032_a;
        if (index < 0 || index >= all.length || all[index] == null) {
            return new Object[0];
        }
        net.minecraft.creativetab.CreativeTabs tab = all[index];
        List<Object> stacks = new ArrayList<Object>();
        // Mods can populate tabs themselves (SecurityCraft, MCHeli); the vanilla implementation
        // already iterates every item's getCreativeTabs/getSubItems for us.
        try {
            tab.func_78018_a((List) stacks); // displayAllRelevantItems
            return stacks.toArray();
        } catch (Throwable failedTab) {
            stacks.clear(); // retain the per-item fallback for one broken tab implementation
        }
        for (Object element : Item.field_150901_e) { // itemRegistry
            Item item = (Item) element;
            String name = Item.field_150901_e.func_148750_c(item); // getNameForObject
            if (name == null || name.startsWith("minecraft:") || name.equals(ModernContent.ITEM_NAME) || name.equals(ModernContent.BLOCK_ITEM_NAME)) {
                continue;
            }
            try {
                for (net.minecraft.creativetab.CreativeTabs shown : item.getCreativeTabs()) {
                    if (shown == tab) {
                        item.func_150895_a(item, tab, stacks); // getSubItems
                    }
                }
            } catch (Throwable t) {
                // an item that can't list itself: left out of the tab
            }
        }
        return stacks.toArray();
    }

    /** Client: the stacks an item lists in its creative tab (getSubItems), NBT and all; none if it can't. */
    @SuppressWarnings("unchecked")
    public static Object[] subItemStacks(Object legacyItem) {
        Item item = (Item) legacyItem;
        List<Object> stacks = new ArrayList<Object>();
        try {
            item.func_150895_a(item, item.func_77640_w(), stacks); // getSubItems(item, getCreativeTab(), list)
        } catch (Throwable t) {
            stacks.clear();
        }
        return stacks.toArray();
    }

    /** Client: a creative tab's icon stack, or null. */
    public static Object legacyCreativeTabIcon(int index) {
        net.minecraft.creativetab.CreativeTabs[] all = net.minecraft.creativetab.CreativeTabs.field_78032_a;
        try {
            return index < 0 || index >= all.length || all[index] == null ? null : all[index].func_151244_d(); // getIconItemStack
        } catch (Throwable t) {
            return null;
        }
    }

    public static String describe(Object legacyObject) {
        return String.valueOf(legacyObject);
    }

    static byte[] toBytes(NBTTagCompound tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompressedStreamTools.func_74800_a(tag, new DataOutputStream(bytes)); // write
        return bytes.toByteArray();
    }

    static NBTTagCompound fromBytes(byte[] bytes) throws IOException {
        return CompressedStreamTools.func_74794_a(new DataInputStream(new ByteArrayInputStream(bytes))); // read
    }

    /** What World.setTileEntity does for a new tile entity. */
    private static void place(TileEntity tileEntity, World world, int x, int y, int z) {
        tileEntity.func_145834_a(world); // setWorldObj
        tileEntity.field_145851_c = x;   // xCoord
        tileEntity.field_145848_d = y;   // yCoord
        tileEntity.field_145849_e = z;   // zCoord
        tileEntity.func_145829_t();      // validate
        WorldGlue.joinTileList(world, tileEntity); // loadedTileEntityList, where mods scan for machines
    }
}
