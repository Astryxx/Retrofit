package retrofit.legacy.boot;

import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Development tool: writes the 1.7.10 game and Forge classes exactly as the running legacy
 * world sees them (binary-patched, deobfuscated to SRG names) into a jar, so Retrofit's
 * legacy-side code and test mods can be compiled against them.
 *
 * The output is derived from the player's own Minecraft jar; it is for local builds only and
 * must never be published or committed.
 */
public final class SrgJarDumper {
    private SrgJarDumper() {
    }

    public static void dump(File output) throws Exception {
        LaunchClassLoader lcl = Launch.classLoader;
        Method transformName = LaunchClassLoader.class.getDeclaredMethod("transformName", String.class);
        Method runTransformers = LaunchClassLoader.class.getDeclaredMethod("runTransformers", String.class, String.class, byte[].class);
        transformName.setAccessible(true);
        runTransformers.setAccessible(true);

        int written = 0;
        int failed = 0;
        output.getParentFile().mkdirs();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(output))) {
            for (URL source : lcl.getSources()) {
                String fileName = new File(source.toURI()).getName();
                if (!fileName.equals("1.7.10.jar") && !fileName.startsWith("forge-")) {
                    continue;
                }
                try (ZipFile jar = new ZipFile(new File(source.toURI()))) {
                    for (Enumeration<? extends ZipEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
                        ZipEntry entry = entries.nextElement();
                        String path = entry.getName();
                        if (!path.endsWith(".class")) {
                            continue;
                        }
                        String name = path.substring(0, path.length() - 6).replace('/', '.');
                        try {
                            String deobfName = (String) transformName.invoke(lcl, name);
                            byte[] bytes = (byte[]) runTransformers.invoke(lcl, name, deobfName, lcl.getClassBytes(name));
                            if (bytes == null) {
                                continue;
                            }
                            out.putNextEntry(new ZipEntry(deobfName.replace('.', '/') + ".class"));
                            out.write(bytes);
                            out.closeEntry();
                            written++;
                        } catch (Exception e) {
                            failed++;
                        }
                    }
                }
            }
        }
        System.out.println("[Retrofit] Wrote " + written + " deobfuscated legacy classes to " + output
                + (failed > 0 ? " (" + failed + " skipped)" : ""));
    }
}
