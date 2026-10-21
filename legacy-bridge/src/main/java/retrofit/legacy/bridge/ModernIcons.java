package retrofit.legacy.bridge;

import net.minecraft.util.IIcon;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Client only. Icons of 1.20.1 blocks carried through legacy code (see ModernContent's block
 * carrier): each is the host block's own sprite in the block atlas, which legacy icons share, so
 * mods drawing a held block with its icons (Carpenter's Blocks covers, facades) draw the real
 * 1.20.1 texture.
 */
public final class ModernIcons {
    /** (carrier damage, side) -> {u0, u1, v0, v1, width, height}, or null; set by the host. */
    private static volatile BiFunction<Integer, Integer, float[]> source;
    private static final Map<Integer, IIcon> ICONS = new ConcurrentHashMap<Integer, IIcon>();

    private ModernIcons() {
    }

    /** The host's sprite lookup; called again (forgetting the icons) when the atlas is stitched. */
    @SuppressWarnings("unchecked")
    public static void setModernIconSource(Object iconSource) {
        source = (BiFunction<Integer, Integer, float[]>) iconSource;
        ICONS.clear();
    }

    /** The icon of a carried 1.20.1 block's side; null when the host has no sprite for it. */
    static IIcon icon(int damage, int side) {
        int key = damage << 3 | (side & 7);
        IIcon icon = ICONS.get(key);
        if (icon == null && source != null) {
            float[] uv = source.apply(damage, side);
            if (uv != null) {
                icon = new Icon("retrofit:modern_" + damage + "_" + side, uv);
                ICONS.put(key, icon);
            }
        }
        return icon;
    }

    private static final class Icon implements IIcon {
        private final String name;
        private final float u0, u1, v0, v1;
        private final int width, height;

        Icon(String name, float[] uv) {
            this.name = name;
            u0 = uv[0];
            u1 = uv[1];
            v0 = uv[2];
            v1 = uv[3];
            width = (int) uv[4];
            height = (int) uv[5];
        }

        @Override
        public int func_94211_a() { // getIconWidth
            return width;
        }

        @Override
        public int func_94216_b() { // getIconHeight
            return height;
        }

        @Override
        public float func_94209_e() { // getMinU
            return u0;
        }

        @Override
        public float func_94212_f() { // getMaxU
            return u1;
        }

        @Override
        public float func_94214_a(double u) { // getInterpolatedU (0..16)
            return u0 + (u1 - u0) * (float) u / 16f;
        }

        @Override
        public float func_94206_g() { // getMinV
            return v0;
        }

        @Override
        public float func_94210_h() { // getMaxV
            return v1;
        }

        @Override
        public float func_94207_b(double v) { // getInterpolatedV (0..16)
            return v0 + (v1 - v0) * (float) v / 16f;
        }

        @Override
        public String func_94215_i() { // getIconName
            return name;
        }
    }
}
