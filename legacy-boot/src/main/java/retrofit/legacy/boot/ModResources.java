package retrofit.legacy.boot;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import net.minecraft.launchwrapper.Launch;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** File-based content loaders need a directory even when their mod was installed as an archive. */
public final class ModResources {
    private static final Map<Class<?>, String> PATHS = new HashMap<Class<?>, String>();

    private ModResources() { }

    static byte[] transform(String name, byte[] bytes) {
        if (bytes == null || !name.startsWith("mcheli.")) return bytes;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean changed = false;
        for (Object element : node.methods) {
            MethodNode method = (MethodNode) element;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
                AbstractInsnNode next = insn.getNext();
                if (insn.getOpcode() == Opcodes.GETSTATIC) {
                    FieldInsnNode field = (FieldInsnNode) insn;
                    if (field.owner.equals("mcheli/MCH_MOD") && field.name.equals("sourcePath") && field.desc.equals("Ljava/lang/String;")) {
                        InsnList replacement = new InsnList();
                        replacement.add(new LdcInsnNode(Type.getObjectType(field.owner)));
                        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "retrofit/legacy/boot/ModResources", "contentPath",
                                "(Ljava/lang/Class;)Ljava/lang/String;", false));
                        method.instructions.insertBefore(insn, replacement);
                        method.instructions.remove(insn);
                        changed = true;
                    }
                }
                insn = next;
            }
        }
        if (!changed) return bytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Private runtime copy of assets only; original mod files stay untouched. */
    public static synchronized String contentPath(Class<?> owner) {
        String known = PATHS.get(owner);
        if (known != null) return known;
        try {
            java.net.URL location = owner.getProtectionDomain().getCodeSource().getLocation();
            if ("jar".equals(location.getProtocol())) location = ((java.net.JarURLConnection) location.openConnection()).getJarFileURL();
            File source = new File(location.toURI());
            if (source.isDirectory()) return source.getAbsolutePath();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(source)) {
                byte[] buffer = new byte[65536];
                for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n);
            }
            StringBuilder hash = new StringBuilder();
            for (byte b : digest.digest()) hash.append(String.format("%02x", b & 255));
            Path root = new File(Launch.minecraftHome, ".retrofit/mod-resources/" + hash).toPath().toAbsolutePath().normalize();
            Path complete = root.resolve(".complete");
            if (!Files.exists(complete)) {
                Files.createDirectories(root);
                try (ZipFile zip = new ZipFile(source)) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (entry.isDirectory() || !entry.getName().startsWith("assets/")) continue;
                        Path target = root.resolve(entry.getName()).normalize();
                        if (!target.startsWith(root)) throw new IOException("Invalid asset path: " + entry.getName());
                        Files.createDirectories(target.getParent());
                        try (InputStream in = zip.getInputStream(entry)) {
                            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
                Files.write(complete, Collections.singletonList(source.getName()), java.nio.charset.StandardCharsets.UTF_8);
            }
            known = root.toString();
            PATHS.put(owner, known);
            System.out.println("[Retrofit] File-based mod resources: " + source.getName() + " -> " + root);
            return known;
        } catch (Exception e) {
            throw new IllegalStateException("Could not prepare file-based resources for " + owner.getName(), e);
        }
    }
}
