package retrofit.legacy.boot;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import org.objectweb.asm.*;

/**
 * Two versions of one mod in the mods folder (a library two packs brought along: CodeChickenCore 1.0.7 and
 * 1.4.22): FML found "a duplicate mod" and loaded none of the mods at all. Before FML looks, the jars that
 * are the same mod (the same @Mod id, or the same coremod plugin class) are found, the newest is
 * kept and the others are moved to retrofit/duplicates (moved, not deleted), each reported as left out.
 * FML reads the folder itself, coremods first, so a jar has to be out of it to be out of the game.
 */
final class ModDuplicates {
    private static final Pattern VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private ModDuplicates() {
    }

    /** One mod jar: what makes it the same mod as another, and its version. */
    private static final class Candidate {
        final File file;
        final List<String> keys = new ArrayList<String>();
        String version;

        Candidate(File file) {
            this.file = file;
        }
    }

    static void setAside(File gameDir) {
        File mods = new File(gameDir, "mods");
        List<Candidate> candidates = new ArrayList<Candidate>();
        for (File folder : new File[]{mods, new File(mods, "1.7.10")}) {
            File[] files = folder.listFiles();
            for (File file : files == null ? new File[0] : files) {
                String name = file.getName().toLowerCase();
                if (file.isFile() && (name.endsWith(".jar") || name.endsWith(".zip"))) {
                    Candidate candidate = read(file);
                    if (candidate != null && !candidate.keys.isEmpty()) {
                        candidates.add(candidate);
                    }
                }
            }
        }
        Map<String, Candidate> kept = new HashMap<String, Candidate>();
        List<Candidate> aside = new ArrayList<Candidate>();
        for (Candidate candidate : candidates) {
            Candidate other = null;
            for (String key : candidate.keys) {
                Candidate holder = kept.get(key);
                if (holder != null && !aside.contains(holder)) {
                    other = holder;
                    break;
                }
            }
            if (other == null) {
                for (String key : candidate.keys) {
                    kept.put(key, candidate);
                }
                continue;
            }
            Candidate older = compare(candidate, other) > 0 ? other : candidate;
            Candidate newer = older == candidate ? other : candidate;
            aside.add(older);
            for (String key : newer.keys) {
                kept.put(key, newer);
            }
            setAside(gameDir, older, newer);
        }
    }

    private static void setAside(File gameDir, Candidate older, Candidate newer) {
        File folder = new File(gameDir, "duplicates");
        File target = new File(folder, older.file.getName());
        String why = older.file.getName() + ": another version of the same mod is used (" + newer.file.getName()
                + "); this one was moved to retrofit/duplicates";
        if (!folder.isDirectory() && !folder.mkdirs() || target.exists() && !target.delete() || !older.file.renameTo(target)) {
            System.out.println("[Retrofit] " + older.file.getName() + " and " + newer.file.getName()
                    + " are the same mod, and the older could not be moved out of the mods folder: FML will refuse to load mods");
            return;
        }
        ModFailures.leaveOut(older.file.getName(), why);
        System.out.println("[Retrofit] Left out " + why);
    }

    /** Positive if a is newer: by mcmod.info versions, else by the version in the file names. */
    private static int compare(Candidate a, Candidate b) {
        int byVersion = compareNumbers(numbers(a.version), numbers(b.version));
        return byVersion != 0 ? byVersion : compareNumbers(numbers(fileVersion(a.file)), numbers(fileVersion(b.file)));
    }

    /** The file name without its extension and the Minecraft version ("CodeChickenCore-1.7.10-1.0.7.48" -> 1.0.7.48). */
    private static String fileVersion(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).replace("1.7.10", "");
    }

    private static List<Integer> numbers(String text) {
        List<Integer> numbers = new ArrayList<Integer>();
        if (text != null) {
            Matcher matcher = NUMBER.matcher(text);
            while (matcher.find()) {
                try {
                    numbers.add(Integer.parseInt(matcher.group()));
                } catch (NumberFormatException e) {
                    numbers.add(Integer.MAX_VALUE);
                }
            }
        }
        return numbers;
    }

    private static int compareNumbers(List<Integer> a, List<Integer> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = a.get(i).compareTo(b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return a.size() - b.size();
    }

    /** FML annotation ids and the coremod plugin class; metadata supplies only the version. */
    private static Candidate read(File file) {
        Candidate candidate = new Candidate(file);
        JarFile jar = null;
        try {
            jar = new JarFile(file);
            ZipEntry info = jar.getEntry("mcmod.info");
            if (info != null) {
                String text = readAll(jar.getInputStream(info));
                Matcher version = VERSION.matcher(text);
                if (version.find()) {
                    candidate.version = version.group(1);
                }
            }
            // FML discovers @Mod, not mcmod.info. Some released jars still ship the examplemod
            // metadata, and others (GravityGun, TrailMix) have no metadata at all.
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                try (InputStream in = jar.getInputStream(entry)) {
                    new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM5) {
                        @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            if (!"Lcpw/mods/fml/common/Mod;".equals(descriptor)) return null;
                            return new AnnotationVisitor(Opcodes.ASM5) {
                                @Override public void visit(String name, Object value) {
                                    if ("modid".equals(name) && value instanceof String) {
                                        candidate.keys.add("mod:" + value);
                                    }
                                }
                            };
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                } catch (IllegalArgumentException unsupportedClass) {
                    // A bundled class for a newer JVM is not a legacy FML mod entry point.
                }
            }
            String plugin = jar.getManifest() == null ? null : jar.getManifest().getMainAttributes().getValue("FMLCorePlugin");
            if (plugin != null) {
                candidate.keys.add("coremod:" + plugin.trim());
            }
            return candidate;
        } catch (IOException e) {
            return null;
        } finally {
            if (jar != null) {
                try {
                    jar.close();
                } catch (IOException e) {
                    // read already
                }
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }
}
