package retrofit.legacy.boot;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates stand-ins for LWJGL 2 classes. Legacy code expects LWJGL 2 with its own OpenGL
 * context, window, input and sound; in Retrofit the host (1.20.1, LWJGL 3) owns all of that.
 * References to LWJGL 2 classes are rewritten to {@code retrofit.legacy.lwjgl.*}, and each such
 * class is generated on demand from the real LWJGL 2 class definition:
 * <ul>
 *   <li>every public member exists, so legacy code never hits a missing method or class;</li>
 *   <li>static methods Retrofit implements (same name and descriptor in
 *       {@code retrofit.legacy.gl.<SimpleName>}) forward there, e.g. the GL11 emulation;</li>
 *   <li>everything else returns a neutral default: 0, false, "", null, or a shared instance of
 *       the stand-in type (so e.g. GLContext.getCapabilities() returns all-false capabilities).</li>
 * </ul>
 * Nothing here touches native code, so it works the same on every platform.
 */
public final class LwjglShims {
    public static final String SHIM_PACKAGE = "retrofit/legacy/lwjgl/";
    private static final String IMPL_PACKAGE = "retrofit.legacy.gl.";
    private static final String[] SHIMMED = {"org/lwjgl/opengl/", "org/lwjgl/input/", "org/lwjgl/util/glu/", "org/lwjgl/openal/"};
    private static final String SYS = "org/lwjgl/Sys";
    private static final Map<Class<?>, Object> INSTANCES = new ConcurrentHashMap<Class<?>, Object>();

    public static final Remapper REMAPPER = new Remapper() {
        @Override
        public String map(String typeName) {
            String shim = shimName(typeName);
            return shim != null ? shim : typeName;
        }
    };

    private LwjglShims() {
    }

    /** The stand-in for an LWJGL 2 class ("org/lwjgl/opengl/GL11" -> "retrofit/legacy/lwjgl/opengl/GL11"), or null. */
    public static String shimName(String internalName) {
        if (internalName.equals(SYS)) {
            return SHIM_PACKAGE + "Sys";
        }
        for (String prefix : SHIMMED) {
            if (internalName.startsWith(prefix)) {
                return SHIM_PACKAGE + internalName.substring("org/lwjgl/".length());
            }
        }
        return null;
    }

    /** Called by the legacy root loader for classes in the shim package. */
    public static byte[] generate(String shimClassName, ClassLoader loader) throws IOException, ClassNotFoundException {
        String shim = shimClassName.replace('.', '/');
        String original = "org/lwjgl/" + shim.substring(SHIM_PACKAGE.length());
        byte[] originalBytes = read(loader, original + ".class");
        if (originalBytes == null && (original.equals("org/lwjgl/input/Keyboard") || original.equals("org/lwjgl/input/Mouse"))) {
            // Dedicated servers have no LWJGL 2 library. Some common mod handlers still ask for
            // key names there (More Health Enhanced on login). Our own input API is sufficient
            // to generate the neutral shim; no window, native library or client jar is loaded.
            originalBytes = read(loader, "retrofit/legacy/gl/" + original.substring(original.lastIndexOf('/') + 1) + ".class");
        }
        if (originalBytes == null) {
            throw new ClassNotFoundException(shimClassName + " (no LWJGL 2 class " + original + ")");
        }
        ClassNode source = new ClassNode();
        new ClassReader(originalBytes).accept(source, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        Class<?> impl = implementation(original, loader);

        ClassNode out = new ClassNode();
        boolean isInterface = (source.access & Opcodes.ACC_INTERFACE) != 0;
        out.version = Opcodes.V1_6; // no stack map frames needed
        out.access = Opcodes.ACC_PUBLIC | (source.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ENUM));
        out.name = shim;
        out.superName = source.superName == null ? null : REMAPPER.map(source.superName);
        if (out.superName != null && !out.superName.startsWith(SHIM_PACKAGE) && !out.superName.startsWith("java/")) {
            out.superName = "java/lang/Object";
        }
        out.interfaces = new ArrayList<String>();
        for (Object name : source.interfaces) {
            String mapped = REMAPPER.map((String) name);
            if (mapped.startsWith(SHIM_PACKAGE) || mapped.startsWith("java/")) {
                out.interfaces.add(mapped);
            }
        }

        for (Object element : source.fields) {
            FieldNode field = (FieldNode) element;
            if ((field.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0) {
                continue;
            }
            int access = field.access;
            if (field.value == null) {
                access &= ~Opcodes.ACC_FINAL; // no initializer: must stay assignable
            }
            out.fields.add(new FieldNode(access, field.name, REMAPPER.mapDesc(field.desc), null, field.value));
        }

        boolean hasNoArgConstructor = false;
        for (Object element : source.methods) {
            MethodNode method = (MethodNode) element;
            if ((method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0 || method.name.equals("<clinit>")
                    || (method.access & Opcodes.ACC_SYNTHETIC) != 0) {
                continue;
            }
            String desc = REMAPPER.mapMethodDesc(method.desc);
            int access = method.access & ~(Opcodes.ACC_NATIVE | Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_FINAL);
            MethodNode stub = new MethodNode(access, method.name, desc, null, null);
            if ((access & Opcodes.ACC_ABSTRACT) == 0) {
                stub.instructions = body(out.superName, method.name, desc, (access & Opcodes.ACC_STATIC) != 0, impl);
            }
            hasNoArgConstructor |= method.name.equals("<init>") && desc.equals("()V");
            out.methods.add(stub);
        }
        if (!isInterface && !hasNoArgConstructor) {
            MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            constructor.instructions = body(out.superName, "<init>", "()V", false, null);
            out.methods.add(constructor);
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        out.accept(writer);
        return writer.toByteArray();
    }

    private static InsnList body(String superName, String name, String desc, boolean isStatic, Class<?> impl) {
        InsnList body = new InsnList();
        Type returnType = Type.getReturnType(desc);
        if (name.equals("<init>")) {
            body.add(new VarInsnNode(Opcodes.ALOAD, 0));
            body.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false));
            body.add(new InsnNode(Opcodes.RETURN));
            return body;
        }
        if (isStatic && impl != null && implements_(impl, name, desc)) {
            int slot = 0;
            for (Type arg : Type.getArgumentTypes(desc)) {
                body.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot));
                slot += arg.getSize();
            }
            body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, impl.getName().replace('.', '/'), name, desc, false));
            body.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
            return body;
        }
        switch (returnType.getSort()) {
            case Type.VOID:
                break;
            case Type.LONG:
                body.add(new InsnNode(Opcodes.LCONST_0));
                break;
            case Type.FLOAT:
                body.add(new InsnNode(Opcodes.FCONST_0));
                break;
            case Type.DOUBLE:
                body.add(new InsnNode(Opcodes.DCONST_0));
                break;
            case Type.OBJECT:
            case Type.ARRAY:
                body.add(new LdcInsnNode(returnType));
                body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "retrofit/legacy/boot/LwjglShims", "defaultValue",
                        "(Ljava/lang/Class;)Ljava/lang/Object;", false));
                body.add(new TypeInsnNode(Opcodes.CHECKCAST, returnType.getInternalName()));
                break;
            default:
                body.add(new InsnNode(Opcodes.ICONST_0));
        }
        body.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
        return body;
    }

    /** Default for object-returning stand-in methods. */
    public static Object defaultValue(Class<?> type) {
        if (type == String.class) {
            return "";
        }
        if (type.isArray()) {
            return java.lang.reflect.Array.newInstance(type.getComponentType(), 0);
        }
        if (!type.getName().startsWith(SHIM_PACKAGE.replace('/', '.')) || type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            return null;
        }
        Object instance = INSTANCES.get(type);
        if (instance == null) {
            try {
                instance = type.getConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                return null;
            }
            INSTANCES.put(type, instance);
        }
        return instance;
    }

    private static Class<?> implementation(String original, ClassLoader loader) {
        String simpleName = original.substring(original.lastIndexOf('/') + 1);
        try {
            return Class.forName(IMPL_PACKAGE + simpleName, false, loader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static boolean implements_(Class<?> impl, String name, String desc) {
        for (Method method : impl.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getName().equals(name) && Type.getMethodDescriptor(method).equals(desc)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] read(ClassLoader loader, String path) throws IOException {
        InputStream in = loader.getResourceAsStream(path);
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
