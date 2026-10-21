package retrofit.harness;

import retrofit.api.HostWorld;
import retrofit.host.LegacyRuntime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Exercises every block, tile entity and item that the loaded legacy mods add, without Minecraft
 * 1.20.1: shapes, static block models (custom render types), tile entity special renderers and
 * Forge item renderers, all through the GL emulation. Prints totals and failures grouped by
 * cause, which is the list of what Retrofit still has to support.
 */
final class CompatReport {
    private static final String[] ITEM_RENDER_TYPES = {"INVENTORY", "ENTITY", "EQUIPPED", "EQUIPPED_FIRST_PERSON"};

    private final Class<?> bridge;
    private final Map<String, Method> methods = new HashMap<>();
    private final Map<String, Map<String, Integer>> failures = new TreeMap<>();
    private final Map<String, int[]> totals = new LinkedHashMap<>(); // check -> {ok, failed}

    private CompatReport(Class<?> bridge) {
        this.bridge = bridge;
        for (Method method : bridge.getMethods()) {
            methods.put(method.getName(), method);
        }
    }

    static void run(LegacyRuntime runtime) throws Exception {
        CompatReport report = new CompatReport(Class.forName("retrofit.legacy.bridge.Bridge", true, runtime.gameLoader()));
        report.runAll();
    }

    private void runAll() throws Exception {
        int iconFailures = (int) call("registerAllIcons");
        System.out.printf("[Retrofit] Compat: icons registered (%d block, %d item textures), %d failures%n",
                ((String[]) call("iconNames", 0)).length, ((String[]) call("iconNames", 1)).length, iconFailures);

        int custom = 0;
        for (String icon : (String[]) call("iconNames", 0)) {
            custom += call("customSprite", 0, icon) != null ? 1 : 0;
        }
        System.out.printf("[Retrofit] Compat: %d block sprites drawn by the mods' own loaders%n", custom);

        Map<Long, Object> blocks = new HashMap<>();
        HostWorld host = new HostWorld() {
            public Object getLegacyBlock(int x, int y, int z) {
                return blocks.get(0L);
            }
            public int getLegacyMeta(int x, int y, int z) {
                return 0;
            }
            public boolean setLegacyBlock(int x, int y, int z, Object b, int m, int f) {
                return false;
            }
            public Object getLegacyTileEntity(int x, int y, int z) {
                return null;
            }
            public void markBlockForUpdate(int x, int y, int z) {
            }
            public void markTileEntityChanged(int x, int y, int z) {
            }
            public long getTotalWorldTime() {
                return 0;
            }
            public boolean isClientSide() {
                return true;
            }
        };
        Object world = call("createWorld", host, 0);

        for (String name : (String[]) call("modBlockNames")) {
            Object block = call("block", name);
            int renderType = ((int[]) call("blockRenderInfo", block))[0];
            for (int m = 0; m < 16; m++) {
                int meta = m;
                Object faces;
                try {
                    faces = meta == 0 ? Boolean.TRUE : call("faceTextures", block, meta);
                } catch (Exception e) {
                    faces = null;
                }
                if (faces == null) {
                    continue; // metadata the block doesn't use
                }
                check("shape", name, () -> call("blockShape", block, meta));
                if (DEBUG_ITEM != null && name.contains(DEBUG_ITEM)) {
                    synchronized (call("lock")) {
                        printBounds(name + " meta " + meta + " as an item block", (int) call("renderBlockItem", block, meta, meta));
                        Object item = call("item", name);
                        for (String type : ITEM_RENDER_TYPES) {
                            if (item != null && (boolean) call("hasItemRenderer", item, meta, type)) {
                                printBounds(name + " meta " + meta + " " + type, (int) call("renderItem", item, meta, type));
                            }
                        }
                    }
                }
                if (renderType != 0 && renderType != -1) {
                    check("static model (render type " + renderType + ")", name, () -> {
                        synchronized (call("lock")) {
                            int vertices = (int) call("bakeBlock", block, meta);
                            if (vertices == 0) {
                                throw new IllegalStateException("the renderer drew nothing");
                            }
                            return vertices;
                        }
                    });
                }
                Object[] created = new Object[1];
                check("tile entity", name, () -> {
                    if ((boolean) call("hasTileEntity", block, meta)) {
                        blocks.put(0L, block);
                        created[0] = call("createTileEntity", world, block, meta, 0, 0, 0);
                    }
                    return null;
                });
                Object tile = created[0];
                {
                    if (tile != null && (boolean) call("hasSpecialRenderer", tile)) {
                        check("tile entity renderer", name, () -> {
                            synchronized (call("lock")) {
                                return call("renderTileEntity", tile, 0f, 0d, 0d, 0d);
                            }
                        });
                    }
                }
            }
        }

        for (String name : (String[]) call("modItemNames")) {
            Object item = call("item", name);
            for (int meta : (int[]) call("itemSubtypes", item)) {
                for (String type : ITEM_RENDER_TYPES) {
                    if ((boolean) call("hasItemRenderer", item, meta, type)) {
                        check("item renderer " + type, name, () -> {
                            synchronized (call("lock")) {
                                int vertices = (int) call("renderItem", item, meta, type);
                                if (DEBUG_ITEM != null && name.contains(DEBUG_ITEM)) {
                                    printBounds(name + " meta " + meta + " " + type, vertices);
                                }
                                return vertices;
                            }
                        });
                    }
                }
            }
        }

        totals.forEach((check, counts) -> System.out.printf("[Retrofit] Compat: %-45s %4d ok, %4d failed%n", check, counts[0], counts[1]));
        failures.forEach((cause, where) -> System.out.printf("[Retrofit] Compat failure: %s%n      in %s%n", cause, where));
    }

    private interface Check {
        Object run() throws Exception;
    }

    private void check(String check, String subject, Check body) {
        int[] counts = totals.computeIfAbsent(check, c -> new int[2]);
        try {
            body.run();
            counts[0]++;
        } catch (Throwable t) {
            counts[1]++;
            Throwable cause = t;
            while ((cause instanceof InvocationTargetException || cause.getCause() != null && cause instanceof IllegalStateException)
                    && cause.getCause() != null) {
                cause = cause.getCause();
            }
            StackTraceElement where = cause.getStackTrace().length > 0 ? cause.getStackTrace()[0] : null;
            String key = check + ": " + cause + (where == null ? "" : " at " + where);
            failures.computeIfAbsent(key, k -> new TreeMap<>()).merge(subject, 1, Integer::sum);
        }
    }

    /** -Dretrofit.debugItem=<part of an item name>: prints where such items' renderers draw, per render type. */
    private static final String DEBUG_ITEM = System.getProperty("retrofit.debugItem");

    /** The box the captured vertices span (in the renderer's own space, before the host's transforms), and their alpha. */
    private void printBounds(String what, int vertices) throws Exception {
        Class<?> capture = bridge.getClassLoader().loadClass("retrofit.legacy.gl.Capture");
        float[] d = (float[]) capture.getMethod("data").invoke(null);
        int stride = capture.getField("FLOATS_PER_VERTEX").getInt(null);
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        float minAlpha = 1, maxAlpha = 0;
        for (int v = 0; v < vertices; v++) {
            for (int axis = 0; axis < 3; axis++) {
                min[axis] = Math.min(min[axis], d[v * stride + axis]);
                max[axis] = Math.max(max[axis], d[v * stride + axis]);
            }
            minAlpha = Math.min(minAlpha, d[v * stride + 11]);
            maxAlpha = Math.max(maxAlpha, d[v * stride + 11]);
        }
        System.out.printf("[Retrofit] Item %s: %d vertices, x %.2f..%.2f y %.2f..%.2f z %.2f..%.2f, alpha %.2f..%.2f%n", what, vertices,
                min[0], max[0], min[1], max[1], min[2], max[2], minAlpha, maxAlpha);
    }

    private Object call(String name, Object... args) throws Exception {
        Method method = methods.get(name);
        if (method == null) {
            throw new NoSuchMethodException("Bridge." + name);
        }
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }
}
