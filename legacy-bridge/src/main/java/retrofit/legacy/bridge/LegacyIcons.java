package retrofit.legacy.bridge;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Legacy icons without 1.7.10's texture atlas. Blocks and items register their icons with
 * recording {@link IIconRegister}s (one for block textures, one for item textures, as 1.7.10 had
 * two atlases); {@code getIcon(side, meta)} then hands back recorded icons whose names identify
 * the texture files, and the host turns those into 1.20.1 models and atlas sprites.
 *
 * Once the host has stitched its atlas it gives every icon its sprite's coordinates
 * ({@link #setIconUV}), so legacy code that draws with icon UVs (RenderBlocks, tile entity
 * renderers, item rendering) hits the right part of the 1.20.1 block atlas, which holds both.
 *
 * Client only: IIcon and IIconRegister don't exist on a dedicated 1.7.10 server.
 */
public final class LegacyIcons {
    private static final Recorder BLOCKS = Recorder.create(0);
    private static final Recorder ITEMS = Recorder.create(1);
    private static boolean registered;

    /**
     * The two atlases as the legacy TextureManager knows them (TextureMap.locationBlocksTexture and
     * locationItemsTexture), for mods that look sprites up there or bind them by id.
     */
    static void registerAtlases(net.minecraft.client.renderer.texture.TextureManager textures) {
        textures.func_110579_a(TextureMap.field_110575_b, BLOCKS); // loadTexture(locationBlocksTexture)
        textures.func_110579_a(TextureMap.field_110576_c, ITEMS);  // locationItemsTexture
        try {
            java.lang.reflect.Field blocks = net.minecraft.client.Minecraft.class.getDeclaredField("field_147128_au"); // textureMapBlocks
            blocks.setAccessible(true);
            retrofit.legacy.compat.FinalFields.set(blocks, net.minecraft.client.Minecraft.func_71410_x(), BLOCKS);
        } catch (Exception e) {
            System.out.println("[Retrofit] Could not set Minecraft.textureMapBlocks: " + e);
        }
    }

    private LegacyIcons() {
    }

    /**
     * Lets every block and item, vanilla included, register its icons (once); returns the number
     * that failed. Vanilla icons are needed because mods draw vanilla blocks and items too.
     */
    public static int registerAll() {
        if (registered) {
            return 0;
        }
        registered = true;
        int failed = 0;
        // Forge's TextureStitchEvent.Pre: mods register extra icons there (fluids above all).
        for (Recorder atlas : new Recorder[]{BLOCKS, ITEMS}) {
            try {
                net.minecraftforge.client.ForgeHooksClient.onTextureStitchedPre(atlas); // TextureStitchEvent.Pre
            } catch (Throwable t) {
                failed++;
                System.out.println("[Retrofit] A texture stitch handler failed: " + t);
            }
        }
        for (Object block : Block.field_149771_c) { // blockRegistry
            try {
                ((Block) block).func_149651_a(BLOCKS); // registerBlockIcons
            } catch (Throwable t) {
                failed++;
                System.out.println("[Retrofit] Block " + block + " failed to register icons: " + t);
            }
        }
        for (Object item : Item.field_150901_e) { // itemRegistry
            try {
                if (((Item) item).func_94901_k() == 0) { // getSpriteNumber: 0 = block atlas (ItemBlocks)
                    ((Item) item).func_94581_a(BLOCKS);
                } else {
                    ((Item) item).func_94581_a(ITEMS);    // registerIcons
                }
            } catch (Throwable t) {
                failed++;
                System.out.println("[Retrofit] Item " + item + " failed to register icons: " + t);
            }
        }
        for (Recorder atlas : new Recorder[]{BLOCKS, ITEMS}) {
            try {
                net.minecraftforge.client.ForgeHooksClient.onTextureStitchedPost(atlas); // TextureStitchEvent.Post, and the water and lava fluid icons
            } catch (Throwable t) {
                failed++;
                System.out.println("[Retrofit] A texture stitch handler failed: " + t);
            }
        }
        return failed;
    }

    /**
     * The pixels of a sprite a mod loads itself (Forge's TextureAtlasSprite.hasCustomLoader/load, used
     * for sprite sheets, e.g. IC2's machines): {width, height, ARGB of the first frame}, or null if the
     * icon has no custom loader. The host uses them when there is no texture file of that name.
     */
    private static boolean customSpriteTraced;

    public static Object[] customSprite(int kind, String name) {
        TextureAtlasSprite sprite = (kind == 0 ? BLOCKS : ITEMS).custom.get(name);
        if (sprite == null) {
            return null;
        }
        net.minecraft.util.ResourceLocation base = new net.minecraft.util.ResourceLocation(name);
        net.minecraft.util.ResourceLocation file = new net.minecraft.util.ResourceLocation(base.func_110624_b(), // getResourceDomain
                "textures/" + (kind == 0 ? "blocks" : "items") + "/" + base.func_110623_a() + ".png"); // getResourcePath
        IResourceManager manager = net.minecraft.client.Minecraft.func_71410_x().func_110442_L(); // getResourceManager
        try {
            // As Forge 1.7.10's TextureMap: load returning false means loaded (stitch it); true means skip it.
            if (!sprite.hasCustomLoader(manager, file) || sprite.load(manager, file) || sprite.func_110970_k() == 0) { // getFrameCount
                return null;
            }
            int[][] frame = sprite.func_147965_a(0); // getFrameTextureData
            if (frame == null || frame.length == 0 || frame[0] == null) {
                return null;
            }
            return new Object[]{sprite.func_94211_a(), sprite.func_94216_b(), frame[0]}; // getIconWidth, getIconHeight
        } catch (Throwable t) {
            System.out.println("[Retrofit] Custom sprite " + name + " could not be loaded: " + t);
            if (!customSpriteTraced) {
                customSpriteTraced = true;
                t.printStackTrace(System.out);
            }
            return null;
        }
    }

    /** Names of all recorded block (kind 0) or item (kind 1) icons. */
    public static String[] iconNames(int kind) {
        return (kind == 0 ? BLOCKS : ITEMS).icons.keySet().toArray(new String[0]);
    }

    /** Gives a recorded icon the coordinates of its sprite in the host's atlas. */
    public static void setIconUV(int kind, String name, float minU, float maxU, float minV, float maxV, int width, int height) {
        Recorder atlas = kind == 0 ? BLOCKS : ITEMS;
        RecordedIcon icon = atlas.icons.get(name);
        if (icon != null) {
            icon.minU = minU;
            icon.maxU = maxU;
            icon.minV = minV;
            icon.maxV = maxV;
            icon.width = width;
            icon.height = height;
        }
        TextureAtlasSprite custom = atlas.custom.get(name);
        if (custom != null) {
            placeCustom(custom, minU, maxU, minV, maxV, width, height);
        }
    }

    /**
     * The texture names of the six faces for a metadata value, in legacy side order (down, up,
     * north, south, west, east), or null if the block has no icons for it.
     */
    public static String[] faceTextures(Object block, int meta) {
        String[] faces = new String[6];
        for (int side = 0; side < 6; side++) {
            IIcon icon;
            try {
                icon = ((Block) block).func_149691_a(side, meta); // getIcon
            } catch (Throwable t) {
                return null; // e.g. metadata the block never uses, indexing past its icon array
            }
            if (icon == null) {
                return null;
            }
            faces[side] = icon.func_94215_i(); // getIconName
        }
        return faces;
    }

    /** The texture name of an item for a damage value, or null. */
    public static String itemTexture(Object item, int meta) {
        try {
            IIcon icon = ((Item) item).func_77617_a(meta); // getIconFromDamage
            return icon == null ? null : icon.func_94215_i();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The icon of each render pass an item draws with (requiresMultipleRenderPasses: TC's
     * essentia phials, potions, spawn eggs), at most {@code max}; a single pass otherwise. Entries
     * may be null.
     */
    public static String[] itemPassTextures(Object item, int meta, int max) {
        Item legacy = (Item) item;
        try {
            if (!legacy.func_77623_v()) { // requiresMultipleRenderPasses
                return new String[]{itemTexture(item, meta)};
            }
            int passes = Math.max(1, Math.min(max, legacy.getRenderPasses(meta)));
            String[] textures = new String[passes];
            for (int pass = 0; pass < passes; pass++) {
                IIcon icon = legacy.func_77618_c(meta, pass); // getIconFromDamageForRenderPass
                textures[pass] = icon == null ? null : icon.func_94215_i();
            }
            return textures;
        } catch (Throwable t) {
            return new String[]{itemTexture(item, meta)};
        }
    }

    /** An item stack's colour for a render pass (getColorFromItemStack), 0xFFFFFF for none. */
    public static int itemColor(Object stack, int pass) {
        try {
            ItemStack legacy = (ItemStack) stack;
            return legacy.func_77973_b().func_82790_a(legacy, pass); // getItem().getColorFromItemStack
        } catch (Throwable t) {
            return 0xFFFFFF;
        }
    }

    /** The damage values an item shows in the creative menu (getSubItems), or {0}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int[] itemSubtypes(Object legacyItem) {
        Item item = (Item) legacyItem;
        List stacks = new ArrayList();
        try {
            item.func_150895_a(item, item.func_77640_w(), stacks); // getSubItems(item, getCreativeTab(), list)
        } catch (Throwable t) {
            stacks.clear();
        }
        if (stacks.isEmpty()) {
            return new int[]{0};
        }
        int[] damages = new int[stacks.size()];
        for (int i = 0; i < damages.length; i++) {
            damages[i] = ((ItemStack) stacks.get(i)).func_77960_j(); // getItemDamage
        }
        return damages;
    }

    /** Sprite classes of mods keep their UVs in TextureAtlasSprite's private fields; set them the way stitching did. */
    private static void placeCustom(TextureAtlasSprite sprite, float minU, float maxU, float minV, float maxV, int width, int height) {
        try {
            setField(sprite, "field_110979_l", minU); // minU
            setField(sprite, "field_110980_m", maxU); // maxU
            setField(sprite, "field_110977_n", minV); // minV
            setField(sprite, "field_110978_o", maxV); // maxV
            java.lang.reflect.Field w = TextureAtlasSprite.class.getDeclaredField("field_130223_c"); // width
            w.setAccessible(true);
            w.setInt(sprite, width);
            java.lang.reflect.Field h = TextureAtlasSprite.class.getDeclaredField("field_130224_d"); // height
            h.setAccessible(true);
            h.setInt(sprite, height);
        } catch (ReflectiveOperationException e) {
            System.out.println("[Retrofit] Could not place sprite " + sprite.func_94215_i() + ": " + e);
        }
    }

    private static void setField(TextureAtlasSprite sprite, String name, float value) throws ReflectiveOperationException {
        java.lang.reflect.Field field = TextureAtlasSprite.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setFloat(sprite, value);
    }

    /**
     * Records registered icons. A TextureMap, as 1.7.10's icon registers were (mods cast to it and
     * call its sprite methods), created without its constructor (which would register every icon
     * into a real atlas); every TextureMap method that would stitch or upload does nothing here.
     */
    static final class Recorder extends TextureMap {
        private Map<String, RecordedIcon> icons;
        /** Sprites mods made themselves (setTextureEntry), by name; their files are stitched like the rest. */
        private Map<String, TextureAtlasSprite> custom;
        private int type;

        private Recorder() {
            super(0, null); // never called; see create
        }

        static Recorder create(int type) {
            try {
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                Recorder recorder = (Recorder) unsafeClass.getMethod("allocateInstance", Class.class).invoke(theUnsafe.get(null), Recorder.class);
                recorder.icons = new LinkedHashMap<String, RecordedIcon>();
                recorder.custom = new LinkedHashMap<String, TextureAtlasSprite>();
                recorder.type = type;
                return recorder;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Retrofit: cannot create the legacy icon recorder", e);
            }
        }

        private synchronized RecordedIcon record(String name) {
            RecordedIcon icon = icons.get(name);
            if (icon == null) {
                icon = new RecordedIcon(name);
                icons.put(name, icon);
            }
            return icon;
        }

        /** registerIcon */
        @Override
        public IIcon func_94245_a(String name) {
            if (name == null) {
                throw new IllegalArgumentException("Name cannot be null!"); // as 1.7.10
            }
            TextureAtlasSprite own = custom.get(name);
            return own != null ? own : record(name);
        }

        /** getAtlasSprite */
        @Override
        public TextureAtlasSprite func_110572_b(String name) {
            TextureAtlasSprite own = custom.get(name);
            return own != null ? own : record(name);
        }

        @Override
        public TextureAtlasSprite getTextureExtry(String name) {
            TextureAtlasSprite own = custom.get(name);
            return own != null ? own : icons.get(name);
        }

        @Override
        public synchronized boolean setTextureEntry(String name, TextureAtlasSprite sprite) {
            if (custom.containsKey(name) || icons.containsKey(name)) {
                return false;
            }
            custom.put(name, sprite);
            record(name); // the host stitches the file of that name
            return true;
        }

        /** getTextureType */
        @Override
        public int func_130086_a() {
            return type;
        }

        /** loadTexture */
        @Override
        public void func_110551_a(IResourceManager manager) {
        }

        /** loadTextureAtlas */
        @Override
        public void func_110571_b(IResourceManager manager) {
        }

        /** updateAnimations */
        @Override
        public void func_94248_c() {
        }

        /** tick */
        @Override
        public void func_110550_d() {
        }

        /** setMipmapLevels */
        @Override
        public void func_147633_a(int levels) {
        }

        /** setAnisotropicFiltering */
        @Override
        public void func_147632_b(int level) {
        }
    }

    /** An icon that knows its name and, once the host's atlas exists, its place in it. */
    private static final class RecordedIcon extends TextureAtlasSprite {
        private final String name;
        private volatile float minU, minV;
        private volatile float maxU = 1, maxV = 1;
        private volatile int width = 16, height = 16;

        RecordedIcon(String name) {
            super(name);
            this.name = name;
        }

        /** getIconWidth */
        @Override
        public int func_94211_a() {
            return width;
        }

        /** getIconHeight */
        @Override
        public int func_94216_b() {
            return height;
        }

        /** getMinU */
        @Override
        public float func_94209_e() {
            return minU;
        }

        /** getMaxU */
        @Override
        public float func_94212_f() {
            return maxU;
        }

        /** getInterpolatedU: u in 0..16 across the icon */
        @Override
        public float func_94214_a(double u) {
            return minU + (maxU - minU) * (float) u / 16f;
        }

        /** getMinV */
        @Override
        public float func_94206_g() {
            return minV;
        }

        /** getMaxV */
        @Override
        public float func_94210_h() {
            return maxV;
        }

        /** getInterpolatedV */
        @Override
        public float func_94207_b(double v) {
            return minV + (maxV - minV) * (float) v / 16f;
        }

        /** getIconName */
        @Override
        public String func_94215_i() {
            return name;
        }
    }
}
