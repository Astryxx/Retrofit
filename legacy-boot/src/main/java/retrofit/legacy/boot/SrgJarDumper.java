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

    /**
     * Obfuscation stripped nested-class metadata (InnerClasses), and what is left marks classes
     * like WorldSettings.GameType package-private. They are public at runtime; rebuild the metadata
     * so code compiled against this jar can name them: every nested class gets a public entry in
     * its outer class and in itself. Only affects the compile jar.
     */
    private static byte[] fixInnerClasses(byte[] bytes, java.util.Map<String, Integer> nestedAccess) {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
        node.innerClasses.clear();
        String own = node.name;
        for (java.util.Map.Entry<String, Integer> nested : nestedAccess.entrySet()) {
            String name = nested.getKey();
            int dollar = name.lastIndexOf('$');
            String outer = name.substring(0, dollar);
            if (outer.equals(own) || name.equals(own)) {
                int access = (nested.getValue() & (org.objectweb.asm.Opcodes.ACC_INTERFACE | org.objectweb.asm.Opcodes.ACC_ABSTRACT
                        | org.objectweb.asm.Opcodes.ACC_ENUM | org.objectweb.asm.Opcodes.ACC_FINAL | org.objectweb.asm.Opcodes.ACC_ANNOTATION))
                        | org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC;
                String simple = name.substring(dollar + 1);
                boolean anonymous = !simple.isEmpty() && Character.isDigit(simple.charAt(0));
                node.innerClasses.add(new org.objectweb.asm.tree.InnerClassNode(name, anonymous ? null : outer,
                        anonymous ? null : simple, access));
            }
        }
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    public static void dump(File output) throws Exception {
        LaunchClassLoader lcl = Launch.classLoader;
        Method transformName = LaunchClassLoader.class.getDeclaredMethod("transformName", String.class);
        Method runTransformers = LaunchClassLoader.class.getDeclaredMethod("runTransformers", String.class, String.class, byte[].class);
        transformName.setAccessible(true);
        runTransformers.setAccessible(true);

        int written = 0;
        int failed = 0;
        java.util.Map<String, byte[]> classes = new java.util.LinkedHashMap<String, byte[]>();
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
                            classes.put(deobfName.replace('.', '/'), bytes);
                        } catch (Exception e) {
                            failed++;
                        }
                    }
                }
            }
            java.util.Map<String, Integer> nestedAccess = new java.util.HashMap<String, Integer>();
            for (java.util.Map.Entry<String, byte[]> entry : classes.entrySet()) {
                if (entry.getKey().contains("$")) {
                    nestedAccess.put(entry.getKey(), new org.objectweb.asm.ClassReader(entry.getValue()).getAccess());
                }
            }
            for (java.util.Map.Entry<String, byte[]> entry : classes.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey() + ".class"));
                out.write(fixInnerClasses(entry.getValue(), nestedAccess));
                out.closeEntry();
                written++;
            }
        }
        System.out.println("[Retrofit] Wrote " + written + " deobfuscated legacy classes to " + output
                + (failed > 0 ? " (" + failed + " skipped)" : ""));
    }
}
