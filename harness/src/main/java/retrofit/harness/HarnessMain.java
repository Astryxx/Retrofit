package retrofit.harness;

import retrofit.host.LegacyHost;
import retrofit.host.LegacyInstall;
import retrofit.host.LegacyRuntime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Boot test entry point: boots the player's 1.7.10 + Forge on Java 17 without a
 * window, outside of 1.20.1. Fast to iterate on; the Forge mod uses the same host code.
 */
public final class HarnessMain {
    public static void main(String[] args) throws Exception {
        List<Path> retrofitJars = new ArrayList<>();
        Path dumpSrg = null;
        boolean server = false;
        Path gameDir = Path.of("").toAbsolutePath();
        Path minecraftHome = LegacyInstall.defaultMinecraftHome();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--boot-jar", "--bridge-jar" -> retrofitJars.add(Path.of(args[++i]));
                case "--dump-srg" -> dumpSrg = Path.of(args[++i]);
                case "--server" -> server = true;
                case "--game-dir" -> gameDir = Path.of(args[++i]);
                case "--minecraft-home" -> minecraftHome = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("Unknown argument " + args[i]);
            }
        }
        if (retrofitJars.isEmpty()) {
            throw new IllegalArgumentException("--boot-jar is required");
        }
        LegacyRuntime runtime = LegacyHost.boot(minecraftHome, gameDir, retrofitJars, true, server);
        if (dumpSrg != null) {
            runtime.rootLoader().loadClass("retrofit.legacy.boot.SrgJarDumper").getMethod("dump", java.io.File.class)
                    .invoke(null, dumpSrg.toFile());
            return;
        }
        if (!server) {
            renderTest(runtime); // client-only: models and GL
        }
        WorldBridgeSmokeTest.run(runtime);
        if (!server) {
            CompatReport.run(runtime);
        }
    }

    /** Renders the legacy pig model once through the GL emulation and prints its bounds. */
    private static void renderTest(LegacyRuntime runtime) throws Exception {
        ClassLoader root = runtime.rootLoader();
        int vertices = (int) root.loadClass("retrofit.legacy.gl.ModelTest").getMethod("renderPig", float.class).invoke(null, 0f);
        float[] data = (float[]) root.loadClass("retrofit.legacy.gl.Capture").getMethod("data").invoke(null);
        int stride = root.loadClass("retrofit.legacy.gl.Capture").getField("FLOATS_PER_VERTEX").getInt(null);
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int v = 0; v < vertices; v++) {
            for (int axis = 0; axis < 3; axis++) {
                min[axis] = Math.min(min[axis], data[v * stride + axis]);
                max[axis] = Math.max(max[axis], data[v * stride + axis]);
            }
        }
        System.out.printf("[Retrofit] Render test: legacy ModelPig produced %d vertices (%d quads), bounds x %.3f..%.3f y %.3f..%.3f z %.3f..%.3f%n",
                vertices, vertices / 4, min[0], max[0], min[1], max[1], min[2], max[2]);
    }
}
