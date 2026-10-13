package retrofit.legacy.bridge;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.util.IIcon;

import java.util.HashMap;
import java.util.Map;

/**
 * Finds out which textures legacy blocks use, without 1.7.10's texture atlas: blocks register
 * their icons with a recording {@link IIconRegister}, and {@code getIcon(side, meta)} then hands
 * back recorded icons whose names identify the texture files. The host turns those into 1.20.1
 * block models.
 *
 * Client only: IIcon and IIconRegister don't exist on a dedicated 1.7.10 server.
 */
public final class LegacyIcons {
    private static final Recorder RECORDER = new Recorder();

    private LegacyIcons() {
    }

    /** Lets every given block register its icons; returns the number of blocks that failed. */
    public static int registerIcons(Object[] blocks) {
        int failed = 0;
        for (Object block : blocks) {
            try {
                ((Block) block).func_149651_a(RECORDER); // registerBlockIcons
            } catch (Throwable t) {
                failed++;
                System.out.println("[Retrofit] Block " + block + " failed to register icons: " + t);
            }
        }
        return failed;
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

    private static final class Recorder implements IIconRegister {
        private final Map<String, IIcon> icons = new HashMap<String, IIcon>();

        /** registerIcon */
        @Override
        public IIcon func_94245_a(String name) {
            IIcon icon = icons.get(name);
            if (icon == null) {
                icon = new RecordedIcon(name);
                icons.put(name, icon);
            }
            return icon;
        }
    }

    /** An icon that only knows its name; UVs span the whole (separate) texture. */
    private static final class RecordedIcon implements IIcon {
        private final String name;

        RecordedIcon(String name) {
            this.name = name;
        }

        /** getIconWidth */
        @Override
        public int func_94211_a() {
            return 16;
        }

        /** getIconHeight */
        @Override
        public int func_94216_b() {
            return 16;
        }

        /** getMinU */
        @Override
        public float func_94209_e() {
            return 0;
        }

        /** getMaxU */
        @Override
        public float func_94212_f() {
            return 1;
        }

        /** getInterpolatedU */
        @Override
        public float func_94214_a(double u) {
            return (float) (u / 16);
        }

        /** getMinV */
        @Override
        public float func_94206_g() {
            return 0;
        }

        /** getMaxV */
        @Override
        public float func_94210_h() {
            return 1;
        }

        /** getInterpolatedV */
        @Override
        public float func_94207_b(double v) {
            return (float) (v / 16);
        }

        /** getIconName */
        @Override
        public String func_94215_i() {
            return name;
        }
    }
}
