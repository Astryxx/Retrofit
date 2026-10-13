package retrofit.legacy.boot;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Replaces method bodies of 1.7.10 and FML classes that would talk to a window, a sound system
 * or a network connection of their own. Registered last, so it sees deobfuscated (SRG) names.
 *
 * - Stubs: the body becomes an empty return, optionally calling a {@link Hooks} method first.
 * - Redirects: the body becomes a call to a static method of the legacy bridge with the same
 *   arguments ({@code this} first for instance methods), returning its result. That is how
 *   GUIs, containers and mod packets reach the host (see retrofit.legacy.bridge.Guis and
 *   Network).
 */
public class HeadlessTransformer implements IClassTransformer {
    /** Marker for "replace the body with an empty return". */
    private static final String NO_HOOK = "";
    /** Hook name prefix: pass {@code this} instead of the first argument. */
    private static final String SELF = "this:";

    /** class -> (method name + descriptor -> Hooks method taking the first argument, or NO_HOOK). */
    private static final Map<String, Map<String, String>> STUBS = new HashMap<String, Map<String, String>>();
    /** class -> (method name + descriptor -> "owner/internal/Name.method" of the static target). */
    private static final Map<String, Map<String, String>> REDIRECTS = new HashMap<String, Map<String, String>>();
    private static final String GUIS = "retrofit/legacy/bridge/Guis.";
    /** Client-only targets live apart, so dedicated servers never load classes that name client types. */
    private static final String CLIENT_GUIS = "retrofit/legacy/bridge/ClientGuis.";
    private static final String NETWORK = "retrofit/legacy/bridge/Network.";

    static {
        stub("net.minecraft.client.Minecraft", "func_110436_a()V", SELF + "refreshResources"); // refreshResources
        stub("net.minecraft.client.Minecraft", "func_71377_b(Lnet/minecraft/crash/CrashReport;)V", "crash"); // displayCrashReport
        stub("net.minecraft.client.settings.GameSettings", "func_74300_a()V", NO_HOOK); // loadOptions
        stub("cpw.mods.fml.client.FMLClientHandler", "processWindowMessages()V", NO_HOOK); // pumps LWJGL Display
        stub("net.minecraftforge.common.ForgeVersion", "startVersionCheck()V", NO_HOOK); // calls a long-dead URL
        // bindTexture: the GL emulation tags captured quads with the texture; the host loads and binds it.
        stub("net.minecraft.client.renderer.texture.TextureManager", "func_110577_a(Lnet/minecraft/util/ResourceLocation;)V", "bindTexture");
        // loadProfileTextures: mods build fake players (armor stands); never fetch skins from the network for them.
        stub("net.minecraft.client.resources.SkinManager",
                "func_152790_a(Lcom/mojang/authlib/GameProfile;Lnet/minecraft/client/resources/SkinManager$SkinAvailableCallback;Z)V", NO_HOOK);

        // setIngameFocus / setIngameNotInFocus: legacy screens call them when closing; the host owns the mouse.
        stub("net.minecraft.client.Minecraft", "func_71381_h()V", NO_HOOK);
        stub("net.minecraft.client.Minecraft", "func_71364_i()V", NO_HOOK);
        // GUIs and containers: FML's openGui, and the client screen machinery, go to the host.
        redirect("cpw.mods.fml.common.network.internal.FMLNetworkHandler",
                "openGui(Lnet/minecraft/entity/player/EntityPlayer;Ljava/lang/Object;ILnet/minecraft/world/World;III)V", GUIS + "openGui");
        redirect("net.minecraft.client.Minecraft", "func_147108_a(Lnet/minecraft/client/gui/GuiScreen;)V", CLIENT_GUIS + "displayScreen");
        redirect("net.minecraft.client.gui.inventory.GuiContainer", "func_146984_a(Lnet/minecraft/inventory/Slot;III)V", CLIENT_GUIS + "handleMouseClick");
        redirect("net.minecraft.client.gui.inventory.GuiContainer", "func_146977_a(Lnet/minecraft/inventory/Slot;)V", CLIENT_GUIS + "drawSlot");
        stub("net.minecraft.client.gui.inventory.GuiContainer", "func_146982_a(Lnet/minecraft/item/ItemStack;IILjava/lang/String;)V", NO_HOOK); // drawItemStack: the host draws the cursor stack
        redirect("net.minecraft.client.gui.GuiScreen", "func_146285_a(Lnet/minecraft/item/ItemStack;II)V", CLIENT_GUIS + "itemTooltip");
        String itemInGui = "(Lnet/minecraft/client/gui/FontRenderer;Lnet/minecraft/client/renderer/texture/TextureManager;Lnet/minecraft/item/ItemStack;II)V";
        redirect("net.minecraft.client.renderer.entity.RenderItem", "func_82406_b" + itemInGui, CLIENT_GUIS + "drawItem"); // renderItemAndEffectIntoGUI
        redirect("net.minecraft.client.renderer.entity.RenderItem", "func_77015_a" + itemInGui, CLIENT_GUIS + "drawItem"); // renderItemIntoGUI
        redirect("net.minecraft.client.renderer.entity.RenderItem", "func_77021_b" + itemInGui, CLIENT_GUIS + "drawItemOverlay"); // renderItemOverlayIntoGUI
        redirect("net.minecraft.client.renderer.entity.RenderItem",
                "func_94148_a(Lnet/minecraft/client/gui/FontRenderer;Lnet/minecraft/client/renderer/texture/TextureManager;Lnet/minecraft/item/ItemStack;IILjava/lang/String;)V",
                CLIENT_GUIS + "drawItemOverlayText");
        // FML asks the player (a window, or the server console) when a world's saved IDs name missing
        // blocks or items; there is neither here: it is logged and answered "continue".
        redirect("cpw.mods.fml.common.StartupQuery", "confirm(Ljava/lang/String;)Z", "retrofit/legacy/boot/Hooks.startupConfirm");
        redirect("cpw.mods.fml.common.StartupQuery", "notify(Ljava/lang/String;)V", "retrofit/legacy/boot/Hooks.startupNotify");
        // Mod packets: FML's outbound handler hands each packet to the host's channel.
        redirect("cpw.mods.fml.common.network.FMLOutboundHandler",
                "write(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Object;Lio/netty/channel/ChannelPromise;)V", NETWORK + "outbound");
    }

    private static void redirect(String className, String method, String target) {
        Map<String, String> methods = REDIRECTS.get(className);
        if (methods == null) {
            methods = new HashMap<String, String>();
            REDIRECTS.put(className, methods);
        }
        methods.put(method, target);
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
        Map<String, String> redirects = REDIRECTS.get(transformedName);
        if (basicClass == null || methods == null && redirects == null) {
            return basicClass;
        }
        ClassNode node = new ClassNode();
        new ClassReader(basicClass).accept(node, 0);
        for (Object element : node.methods) { // ASM 5 uses raw lists
            MethodNode method = (MethodNode) element;
            String hook = methods == null ? null : methods.get(method.name + method.desc);
            String target = redirects == null ? null : redirects.get(method.name + method.desc);
            if (hook != null) {
                replaceBody(method, hook);
                System.out.println("[Retrofit] Stubbed " + transformedName + "." + method.name + method.desc);
            } else if (target != null) {
                redirectBody(node.name, method, target);
            }
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** The body becomes "return Target.method(this?, args...)". */
    private static void redirectBody(String owner, MethodNode method, String target) {
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        Type type = Type.getMethodType(method.desc);
        InsnList body = new InsnList();
        int local = 0;
        StringBuilder desc = new StringBuilder("(");
        if (!isStatic) {
            body.add(new VarInsnNode(Opcodes.ALOAD, local++));
            desc.append('L').append(owner).append(';');
        }
        for (Type argument : type.getArgumentTypes()) {
            body.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
            local += argument.getSize();
            desc.append(argument.getDescriptor());
        }
        desc.append(')').append(type.getReturnType().getDescriptor());
        int dot = target.lastIndexOf('.');
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, target.substring(0, dot), target.substring(dot + 1), desc.toString(), false));
        body.add(new InsnNode(type.getReturnType().getOpcode(Opcodes.IRETURN)));
        method.instructions = body;
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.access &= ~Opcodes.ACC_ABSTRACT;
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
