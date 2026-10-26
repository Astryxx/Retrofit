package retrofit.legacy.boot;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Changes to FML classes so that one 1.7.10 mod can't stop the others (see {@link ModFailures}).
 * Applied by RetrofitTransformer in both of its stages: FML's Loader loads while the tweakers
 * run, before the later transformers are registered.
 */
final class FmlFixes {
    private static final String MOD_FAILURES = "retrofit/legacy/boot/ModFailures";

    private FmlFixes() {
    }

    static byte[] apply(String transformedName, byte[] bytes) {
        if (transformedName.equals("cpw.mods.fml.common.Loader")) {
            return change(bytes, "sortModList", "()V", Change.PREPEND_DROP_UNSATISFIED);
        }
        if (transformedName.equals("cpw.mods.fml.common.LoadController")) {
            return change(bytes, "transition", "(Lcpw/mods/fml/common/LoaderState;Z)V", Change.PREPEND_DROP_ERRORED);
        }
        if (transformedName.equals("cpw.mods.fml.common.eventhandler.EventBus")) {
            return change(bytes, "post", "(Lcpw/mods/fml/common/eventhandler/Event;)Z", Change.ISOLATE_LISTENERS);
        }
        return bytes;
    }

    private enum Change {
        /** Loader.sortModList first leaves out mods whose required mods are missing (ModFailures.dropUnsatisfied). */
        PREPEND_DROP_UNSATISFIED,
        /** LoadController.transition first leaves out mods that threw in the phase just run (ModFailures.dropErrored). */
        PREPEND_DROP_ERRORED,
        /** EventBus.post runs each listener in its own try (ModFailures.listenerFailed). */
        ISOLATE_LISTENERS
    }

    private static byte[] change(byte[] bytes, String name, String desc, Change change) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        // Mixins rewrite FML's classes as newer bytecode (LoadController, with UniMixins). A call put first
        // leaves the method's stack map frames as they were; only the replaced EventBus.post body has none.
        if (change == Change.ISOLATE_LISTENERS && node.version > Opcodes.V1_6) {
            System.out.println("[Retrofit] " + node.name + " is newer bytecode than FML 1.7.10's; not changed");
            return bytes; // the replaced body has no stack map frames, which Java 7+ classes need
        }
        boolean changed = false;
        for (Object element : node.methods) { // ASM 5 uses raw lists
            MethodNode method = (MethodNode) element;
            if (!method.name.equals(name) || !method.desc.equals(desc)) {
                continue;
            }
            if (change == Change.ISOLATE_LISTENERS) {
                isolateListeners(method);
            } else {
                InsnList call = new InsnList();
                call.add(new VarInsnNode(Opcodes.ALOAD, 0));
                call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MOD_FAILURES,
                        change == Change.PREPEND_DROP_UNSATISFIED ? "dropUnsatisfied" : "dropErrored", "(Ljava/lang/Object;)V", false));
                method.instructions.insert(call);
            }
            changed = true;
        }
        if (!changed) {
            System.out.println("[Retrofit] " + node.name + "." + name + desc + " not found; not changed");
            return bytes;
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * EventBus.post, with each listener in its own try:
     * <pre>
     * IEventListener[] listeners = event.getListenerList().getListeners(busID);
     * for (int i = 0; i < listeners.length; i++) {
     *     try { listeners[i].invoke(event); } catch (Throwable t) { ModFailures.listenerFailed(event, listeners[i], t); }
     * }
     * return event.isCancelable() ? event.isCanceled() : false;
     * </pre>
     */
    private static void isolateListeners(MethodNode method) {
        String event = "cpw/mods/fml/common/eventhandler/Event";
        String listener = "cpw/mods/fml/common/eventhandler/IEventListener";
        String list = "cpw/mods/fml/common/eventhandler/ListenerList";
        InsnList body = new InsnList();
        LabelNode loop = new LabelNode();
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode next = new LabelNode();
        LabelNode done = new LabelNode();
        LabelNode notCancelable = new LabelNode();
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, event, "getListenerList", "()L" + list + ";", false));
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new FieldInsnNode(Opcodes.GETFIELD, "cpw/mods/fml/common/eventhandler/EventBus", "busID", "I"));
        body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, list, "getListeners", "(I)[L" + listener + ";", false));
        body.add(new VarInsnNode(Opcodes.ASTORE, 2));
        body.add(new InsnNode(Opcodes.ICONST_0));
        body.add(new VarInsnNode(Opcodes.ISTORE, 3));
        body.add(loop);
        body.add(new VarInsnNode(Opcodes.ILOAD, 3));
        body.add(new VarInsnNode(Opcodes.ALOAD, 2));
        body.add(new InsnNode(Opcodes.ARRAYLENGTH));
        body.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        body.add(tryStart);
        body.add(new VarInsnNode(Opcodes.ALOAD, 2));
        body.add(new VarInsnNode(Opcodes.ILOAD, 3));
        body.add(new InsnNode(Opcodes.AALOAD));
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, listener, "invoke", "(L" + event + ";)V", true));
        body.add(tryEnd);
        body.add(new JumpInsnNode(Opcodes.GOTO, next));
        body.add(handler);
        body.add(new VarInsnNode(Opcodes.ASTORE, 4));
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new VarInsnNode(Opcodes.ALOAD, 2));
        body.add(new VarInsnNode(Opcodes.ILOAD, 3));
        body.add(new InsnNode(Opcodes.AALOAD));
        body.add(new VarInsnNode(Opcodes.ALOAD, 4));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MOD_FAILURES, "listenerFailed",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Throwable;)V", false));
        body.add(next);
        body.add(new IincInsnNode(3, 1));
        body.add(new JumpInsnNode(Opcodes.GOTO, loop));
        body.add(done);
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, event, "isCancelable", "()Z", false));
        body.add(new JumpInsnNode(Opcodes.IFEQ, notCancelable));
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, event, "isCanceled", "()Z", false));
        body.add(new InsnNode(Opcodes.IRETURN));
        body.add(notCancelable);
        body.add(new InsnNode(Opcodes.ICONST_0));
        body.add(new InsnNode(Opcodes.IRETURN));
        method.instructions = body;
        method.tryCatchBlocks.clear();
        method.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, handler, "java/lang/Throwable"));
        method.localVariables = null;
    }
}
