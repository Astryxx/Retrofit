package retrofit.legacy.bridge;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.texture.IIconRegister;
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
    private static final Recorder BLOCKS = new Recorder();
    private static final Recorder ITEMS = new Recorder();
    private static boolean registered;

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
        return failed;
    }

    /** Names of all recorded block (kind 0) or item (kind 1) icons. */
    public static String[] iconNames(int kind) {
        return (kind == 0 ? BLOCKS : ITEMS).icons.keySet().toArray(new String[0]);
    }

    /** Gives a recorded icon the coordinates of its sprite in the host's atlas. */
    public static void setIconUV(int kind, String name, float minU, float maxU, float minV, float maxV, int width, int height) {
        RecordedIcon icon = (kind == 0 ? BLOCKS : ITEMS).icons.get(name);
        if (icon != null) {
            icon.minU = minU;
            icon.maxU = maxU;
            icon.minV = minV;
            icon.maxV = maxV;
            icon.width = width;
            icon.height = height;
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

    private static final class Recorder implements IIconRegister {
        private final Map<String, RecordedIcon> icons = new LinkedHashMap<String, RecordedIcon>();

        /** registerIcon */
        @Override
        public IIcon func_94245_a(String name) {
            RecordedIcon icon = icons.get(name);
            if (icon == null) {
                icon = new RecordedIcon(name);
                icons.put(name, icon);
            }
            return icon;
        }
    }

    /** An icon that knows its name and, once the host's atlas exists, its place in it. */
    private static final class RecordedIcon implements IIcon {
        private final String name;
        private volatile float minU, minV;
        private volatile float maxU = 1, maxV = 1;
        private volatile int width = 16, height = 16;

        RecordedIcon(String name) {
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
