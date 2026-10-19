package retrofit.legacy.boot;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.RemappingClassAdapter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Java 17 compatibility fixes and API redirects applied to legacy classes. It only targets
 * JDK, library, FML and Forge symbols, never obfuscated vanilla names.
 *
 * The base class runs on classes defined by the host's root loader. Inside the
 * LaunchClassLoader it must never touch a vanilla class before Forge's binary patches are
 * applied (the patches expect the original bytes, and patching modified bytes silently
 * regenerates the original code). So it runs there in two stages: {@link Early} first in the
 * chain while FML's own startup classes load, then {@link Late} last in the chain, after
 * patching and deobfuscation, for everything loaded once the tweakers are done.
 */
public class RetrofitTransformer implements IClassTransformer {
    private static volatile boolean lateStage;

    /** Switches LaunchClassLoader transformation from {@link Early} to {@link Late}. */
    static void enterLateStage() {
        lateStage = true;
    }

    /** First in the LaunchClassLoader chain; active until the tweakers have run. */
    public static class Early extends RetrofitTransformer {
        @Override
        public byte[] transform(String name, String transformedName, byte[] basicClass) {
            return lateStage ? basicClass : super.transform(name, transformedName, basicClass);
        }
    }

    /** Last in the LaunchClassLoader chain; active after the tweakers have run. */
    public static class Late extends RetrofitTransformer {
        @Override
        public byte[] transform(String name, String transformedName, byte[] basicClass) {
            return lateStage ? super.transform(name, transformedName, basicClass) : basicClass;
        }
    }

    private static final String FINAL_FIELDS = "retrofit/legacy/compat/FinalFields";
    /** Helpers from log4j 2.0-beta9's core jar, which Retrofit replaces (Log4Shell). */
    private static final String LOG4J_HELPERS = "org/apache/logging/log4j/core/helpers/";
    /** Legacy code must get SLF4J loggers from the host; see LegacyLoggerFactory. */
    private static final String SLF4J_FACTORY = "org/slf4j/LoggerFactory";
    /** LWJGL 2 classes, replaced by generated stand-ins (see LwjglShims). */
    private static final String LWJGL = "org/lwjgl/";

    private static final byte[][] MARKERS = {
        "java/util/jar/Pack200".getBytes(StandardCharsets.UTF_8),
        "setSecurityManager".getBytes(StandardCharsets.UTF_8),
        "setOut".getBytes(StandardCharsets.UTF_8),
        "setErr".getBytes(StandardCharsets.UTF_8),
        "Property".getBytes(StandardCharsets.UTF_8),
        "getBoolean".getBytes(StandardCharsets.UTF_8),
        "getInteger".getBytes(StandardCharsets.UTF_8),
        "getLong".getBytes(StandardCharsets.UTF_8),
        LOG4J_HELPERS.getBytes(StandardCharsets.UTF_8),
        SLF4J_FACTORY.getBytes(StandardCharsets.UTF_8),
        LWJGL.getBytes(StandardCharsets.UTF_8),
        "setAccessible".getBytes(StandardCharsets.UTF_8),
        "getDeclaredField".getBytes(StandardCharsets.UTF_8),
        "java/lang/reflect/Field".getBytes(StandardCharsets.UTF_8),
        "openStream".getBytes(StandardCharsets.UTF_8),
        "openConnection".getBytes(StandardCharsets.UTF_8),
        "java/lang/Thread".getBytes(StandardCharsets.UTF_8),
        "toArray".getBytes(StandardCharsets.UTF_8),
    };

    /**
     * JDK static methods legacy code must not use directly in a shared JVM; redirected to
     * LegacySystem (same name and descriptor): no security manager, no console takeover, and
     * system properties kept local to the legacy world.
     */
    private static final String[] SYSTEM_REDIRECTS = {"setSecurityManager", "setOut", "setErr",
            "getProperty", "setProperty", "clearProperty"};
    private static final String[] PROPERTY_READERS = {"java/lang/Boolean.getBoolean", "java/lang/Integer.getInteger",
            "java/lang/Long.getLong"};

    private static final Remapper REMAPPER = new Remapper() {
        @Override
        public String map(String typeName) {
            if (typeName.equals("java/util/jar/Pack200")) {
                return "retrofit/legacy/compat/Pack200";
            }
            if (typeName.equals("java/util/jar/Pack200$Unpacker")) {
                return "retrofit/legacy/compat/Pack200$Unpacker";
            }
            String lwjglShim = LwjglShims.shimName(typeName);
            if (lwjglShim != null) {
                return lwjglShim;
            }
            if (typeName.equals(SLF4J_FACTORY)) {
                return "retrofit/legacy/compat/log4j/LegacyLoggerFactory";
            }
            if (typeName.startsWith(LOG4J_HELPERS)) {
                return "retrofit/legacy/compat/log4j/" + typeName.substring(LOG4J_HELPERS.length());
            }
            return typeName;
        }
    };

    /** class -> (method name + descriptor -> replacement). Bodies are replaced by a call to a static helper. */
    private static final Map<String, Map<String, Delegate>> DELEGATES = new HashMap<String, Map<String, Delegate>>();

    static {
        // Writes to static final @ObjectHolder fields (vanilla Blocks/Items included).
        delegate("cpw.mods.fml.common.registry.ObjectHolderRef", "makeWritable(Ljava/lang/reflect/Field;)V",
                new Delegate("prepareObjectHolderRef", "(Ljava/lang/Class;Ljava/lang/reflect/Field;)V", true));
        // Runtime enum extension, used heavily by mods (tool/armor materials, creature types, ...).
        String enumHelper = "net.minecraftforge.common.util.EnumHelper";
        delegate(enumHelper, "setup()V", Delegate.EMPTY);
        delegate(enumHelper, "setFailsafeFieldValue(Ljava/lang/reflect/Field;Ljava/lang/Object;Ljava/lang/Object;)V",
                new Delegate("set", "(Ljava/lang/reflect/Field;Ljava/lang/Object;Ljava/lang/Object;)V", false));
        delegate(enumHelper, "makeEnum(Ljava/lang/Class;Ljava/lang/String;I[Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Enum;",
                new Delegate("makeEnum", "(Ljava/lang/Class;Ljava/lang/String;I[Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Object;", false));
        delegate(enumHelper, "cleanEnumCache(Ljava/lang/Class;)V",
                new Delegate("cleanEnumCache", "(Ljava/lang/Class;)V", false));
    }

    private static void delegate(String className, String method, Delegate target) {
        Map<String, Delegate> methods = DELEGATES.get(className);
        if (methods == null) {
            methods = new HashMap<String, Delegate>();
            DELEGATES.put(className, methods);
        }
        methods.put(method, target);
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }
        Map<String, Delegate> delegates = DELEGATES.get(transformedName);
        if (delegates != null) {
            basicClass = applyDelegates(basicClass, delegates);
        }
        if (containsMarker(basicClass)) {
            basicClass = redirectRemovedApis(basicClass);
        }
        return basicClass;
    }

    /**
     * Pack200 -> retrofit.legacy.compat.Pack200; System.setSecurityManager/setOut/setErr -> LegacySystem;
     * setAccessible and getDeclaredField -> Reflection (Java 8 behaviour for JDK members); URL connections -> LegacyNet
     * (timeouts); Thread.start -> LegacyThreads (daemon threads); List.toArray() -> LegacyCollections (Java 8 array types).
     */
    private static byte[] redirectRemovedApis(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0);
        final boolean retrofitClass = reader.getClassName().startsWith("retrofit/");
        ClassVisitor redirects = new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5, super.visitMethod(access, methodName, desc, signature, exceptions)) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String insnName, String insnDesc, boolean itf) {
                        if (opcode == Opcodes.INVOKESTATIC && (owner.equals("java/lang/System") && isSystemRedirect(insnName)
                                || isPropertyReader(owner + "." + insnName))) {
                            owner = "retrofit/legacy/compat/LegacySystem";
                        } else if (!retrofitClass && insnName.equals("setAccessible") && isReflectionMember(owner)) {
                            if (opcode == Opcodes.INVOKEVIRTUAL && insnDesc.equals("(Z)V")) {
                                opcode = Opcodes.INVOKESTATIC;
                                insnDesc = "(Ljava/lang/reflect/AccessibleObject;Z)V";
                                owner = "retrofit/legacy/compat/Reflection";
                            } else if (opcode == Opcodes.INVOKESTATIC) {
                                owner = "retrofit/legacy/compat/Reflection";
                            }
                        } else if (!retrofitClass && opcode == Opcodes.INVOKEVIRTUAL && owner.equals("java/lang/reflect/Field")
                                && isFieldSetter(insnName)) {
                            // field.set(target, value) -> Reflection.set(field, target, value): final fields on Java 18+
                            opcode = Opcodes.INVOKESTATIC;
                            insnDesc = "(Ljava/lang/reflect/Field;" + insnDesc.substring(1);
                            owner = "retrofit/legacy/compat/Reflection";
                        } else if (!retrofitClass && opcode == Opcodes.INVOKEVIRTUAL && owner.equals("java/net/URL")
                                && (insnName.equals("openStream") || insnName.equals("openConnection")) && insnDesc.startsWith("()")) {
                            opcode = Opcodes.INVOKESTATIC;
                            insnDesc = "(Ljava/net/URL;" + insnDesc.substring(1);
                            owner = "retrofit/legacy/compat/LegacyNet";
                        } else if (!retrofitClass && opcode == Opcodes.INVOKEINTERFACE && (owner.equals("java/util/List") || owner.equals("java/util/Collection"))
                                && insnName.equals("toArray") && insnDesc.equals("()[Ljava/lang/Object;")) {
                            // list.toArray() -> LegacyCollections.toArray(list): Arrays.asList keeps its array type, as on Java 8
                            opcode = Opcodes.INVOKESTATIC;
                            insnDesc = "(Ljava/util/Collection;)[Ljava/lang/Object;";
                            owner = "retrofit/legacy/compat/LegacyCollections";
                            itf = false;
                        } else if (!retrofitClass && opcode == Opcodes.INVOKEVIRTUAL && owner.equals("java/lang/Thread")
                                && insnName.equals("start") && insnDesc.equals("()V")) {
                            // Thread.start -> LegacyThreads.start: legacy threads are daemons (1.7.10 ended with System.exit)
                            opcode = Opcodes.INVOKESTATIC;
                            insnDesc = "(Ljava/lang/Thread;)V";
                            owner = "retrofit/legacy/compat/LegacyThreads";
                        } else if (!retrofitClass && opcode == Opcodes.INVOKEVIRTUAL && owner.equals("java/lang/Class")
                                && insnName.equals("getDeclaredField")) {
                            opcode = Opcodes.INVOKESTATIC;
                            insnDesc = "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Field;";
                            owner = "retrofit/legacy/compat/Reflection";
                        }
                        super.visitMethodInsn(opcode, owner, insnName, insnDesc, itf);
                    }
                };
            }
        };
        reader.accept(new RemappingClassAdapter(redirects, REMAPPER), ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] applyDelegates(byte[] bytes, Map<String, Delegate> delegates) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (Object element : node.methods) { // ASM 5 uses raw lists
            MethodNode method = (MethodNode) element;
            Delegate delegate = delegates.get(method.name + method.desc);
            if (delegate != null) {
                method.instructions = delegate.body(node.name, method);
                method.tryCatchBlocks.clear();
                method.localVariables = null;
            }
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean isSystemRedirect(String method) {
        for (String redirect : SYSTEM_REDIRECTS) {
            if (redirect.equals(method)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFieldSetter(String name) {
        return name.equals("set") || name.equals("setBoolean") || name.equals("setByte") || name.equals("setChar") || name.equals("setShort")
                || name.equals("setInt") || name.equals("setLong") || name.equals("setFloat") || name.equals("setDouble");
    }

    private static boolean isReflectionMember(String owner) {
        return owner.equals("java/lang/reflect/AccessibleObject") || owner.equals("java/lang/reflect/Field")
                || owner.equals("java/lang/reflect/Method") || owner.equals("java/lang/reflect/Constructor");
    }

    private static boolean isPropertyReader(String method) {
        for (String reader : PROPERTY_READERS) {
            if (reader.equals(method)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsMarker(byte[] bytes) {
        for (byte[] marker : MARKERS) {
            if (indexOf(bytes, marker) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0, end = haystack.length - needle.length; i <= end; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** A static method body that forwards every argument to a {@code FinalFields} helper. */
    private static final class Delegate {
        static final Delegate EMPTY = new Delegate(null, null, false);

        private final String helper;
        private final String helperDesc;
        private final boolean passOwnerClass;

        Delegate(String helper, String helperDesc, boolean passOwnerClass) {
            this.helper = helper;
            this.helperDesc = helperDesc;
            this.passOwnerClass = passOwnerClass;
        }

        InsnList body(String owner, MethodNode method) {
            InsnList body = new InsnList();
            Type returnType = Type.getReturnType(method.desc);
            if (helper != null) {
                if (passOwnerClass) {
                    body.add(new LdcInsnNode(Type.getObjectType(owner)));
                }
                int slot = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
                for (Type arg : Type.getArgumentTypes(method.desc)) {
                    body.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot));
                    slot += arg.getSize();
                }
                body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FINAL_FIELDS, helper, helperDesc, false));
                if (!returnType.equals(Type.getReturnType(helperDesc))) {
                    body.add(new TypeInsnNode(Opcodes.CHECKCAST, returnType.getInternalName()));
                }
            }
            body.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
            return body;
        }
    }
}
