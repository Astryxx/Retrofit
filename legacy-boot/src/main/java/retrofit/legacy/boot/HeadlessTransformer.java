package retrofit.legacy.boot;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Boot-test stubs for parts of the 1.7.10 client that expect to own a window and
 * resource system. Registered last, so it sees deobfuscated (SRG) names.
 * In 1.20.1 the host owns the window, so most of these become real bridges later.
 */
public class HeadlessTransformer implements IClassTransformer {
    /** Marker for "replace the body with an empty return". */
    private static final String NO_HOOK = "";
    /** Hook name prefix: pass {@code this} instead of the first argument. */
    private static final String SELF = "this:";

    /** class -> (method name + descriptor -> Hooks method taking the first argument, or NO_HOOK). */
    private static final Map<String, Map<String, String>> STUBS = new HashMap<String, Map<String, String>>();

    static {
        stub("net.minecraft.client.Minecraft", "func_110436_a()V", SELF + "refreshResources"); // refreshResources
        stub("net.minecraft.client.Minecraft", "func_71377_b(Lnet/minecraft/crash/CrashReport;)V", "crash"); // displayCrashReport
        stub("net.minecraft.client.settings.GameSettings", "func_74300_a()V", NO_HOOK); // loadOptions
        stub("cpw.mods.fml.client.FMLClientHandler", "processWindowMessages()V", NO_HOOK); // pumps LWJGL Display
        stub("net.minecraftforge.common.ForgeVersion", "startVersionCheck()V", NO_HOOK); // calls a long-dead URL
    }

    private static void stub(String className, String method, String hook) {
        Map<String, String> methods = STUBS.get(className);
        if (methods == null) {
            methods = new HashMap<String, String>();
            STUBS.put(className, methods);
        }
        methods.put(method, hook);
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        Map<String, String> methods = STUBS.get(transformedName);
        if (basicClass == null || methods == null) {
            return basicClass;
        }
        ClassNode node = new ClassNode();
        new ClassReader(basicClass).accept(node, 0);
        for (Object element : node.methods) { // ASM 5 uses raw lists
            MethodNode method = (MethodNode) element;
            String hook = methods.get(method.name + method.desc);
            if (hook != null) {
                replaceBody(method, hook);
                System.out.println("[Retrofit] Stubbed " + transformedName + "." + method.name + method.desc);
            }
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void replaceBody(MethodNode method, String hook) {
        InsnList body = new InsnList();
        if (!hook.equals(NO_HOOK)) {
            boolean self = hook.startsWith(SELF);
            body.add(new VarInsnNode(Opcodes.ALOAD, self ? 0 : 1));
            String name = self ? hook.substring(SELF.length()) : hook;
            body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "retrofit/legacy/boot/Hooks", name, "(Ljava/lang/Object;)V", false));
        }
        body.add(new InsnNode(Opcodes.RETURN));
        method.instructions = body;
        method.tryCatchBlocks.clear();
        method.localVariables = null;
    }
}
