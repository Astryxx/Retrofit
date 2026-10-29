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

    /**
     * Whether the legacy bridge is on this side's classpath. The dump runs (harness dumpSrgJar) boot
     * without it, and their classes must stay untouched, while vanilla itself calls some redirected
     * methods while loading (BiomeGenBase.getBiomeGenArray in Forge's init) - so without the bridge,
     * only stubs apply, whose targets live in this jar.
     */
    private static final boolean BRIDGE_PRESENT = HeadlessTransformer.class.getClassLoader()
            .getResource("retrofit/legacy/bridge/Biomes.class") != null;

    /** class -> (method name + descriptor -> Hooks method taking the first argument, or NO_HOOK). */
    private static final Map<String, Map<String, String>> STUBS = new HashMap<String, Map<String, String>>();
    /** class -> (method name + descriptor -> "owner/internal/Name.method" of the static target). */
    private static final Map<String, Map<String, String>> REDIRECTS = new HashMap<String, Map<String, String>>();
    /** class -> (constructor descriptor -> "owner/internal/Name.method" called at its end, with the new object). */
    private static final Map<String, Map<String, String>> CONSTRUCTORS = new HashMap<String, Map<String, String>>();
    private static final String GUIS = "retrofit/legacy/bridge/Guis.";
    /** Client-only targets live apart, so dedicated servers never load classes that name client types. */
    private static final String CLIENT_GUIS = "retrofit/legacy/bridge/ClientGuis.";
    private static final String RENDER_TOOLTIP = "func_146285_a(Lnet/minecraft/item/ItemStack;II)V"; // GuiScreen.renderToolTip
    private static final String NETWORK = "retrofit/legacy/bridge/Network.";
    private static final String BIOMES = "retrofit/legacy/bridge/Biomes.";

    static {
        stub("net.minecraft.client.Minecraft", "func_110436_a()V", SELF + "refreshResources"); // refreshResources
        stub("net.minecraft.client.Minecraft", "func_71377_b(Lnet/minecraft/crash/CrashReport;)V", "crash"); // displayCrashReport
        stub("net.minecraft.client.settings.GameSettings", "func_74300_a()V", NO_HOOK); // loadOptions
        stub("cpw.mods.fml.client.FMLClientHandler", "processWindowMessages()V", NO_HOOK); // pumps LWJGL Display
        stub("net.minecraftforge.common.ForgeVersion", "startVersionCheck()V", NO_HOOK); // calls a long-dead URL
        // bindTexture: the GL emulation tags captured quads with the texture; the host loads and binds it.
        stub("net.minecraft.client.renderer.texture.TextureManager", "func_110577_a(Lnet/minecraft/util/ResourceLocation;)V", "bindTexture");
        // loadTexture: registered without a GL upload, under an id that binds the texture in the emulation.
        redirect("net.minecraft.client.renderer.texture.TextureManager",
                "func_110579_a(Lnet/minecraft/util/ResourceLocation;Lnet/minecraft/client/renderer/texture/ITextureObject;)Z",
                "retrofit/legacy/bridge/ClientTextures.loadTexture");
        // getEffectiveSide: by thread, as FML did, for this game's threads (see LegacyWorlds.effectiveSide).
        redirect("cpw.mods.fml.common.FMLCommonHandler", "getEffectiveSide()Lcpw/mods/fml/relauncher/Side;",
                "retrofit/legacy/bridge/LegacyWorlds.effectiveSide");
        // getMinecraftServerInstance: the legacy server (see LegacyWorlds.serverInstance).
        redirect("cpw.mods.fml.common.FMLCommonHandler", "getMinecraftServerInstance()Lnet/minecraft/server/MinecraftServer;",
                "retrofit/legacy/bridge/LegacyWorlds.serverInstance");
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
        redirect("net.minecraft.client.renderer.culling.ClippingHelperImpl", "func_78558_a()Lnet/minecraft/client/renderer/culling/ClippingHelper;",
                "retrofit/legacy/bridge/ClientEvents.clippingHelper"); // getInstance: the host camera's planes, not read back from GL
        redirect("net.minecraft.client.gui.GuiScreen", RENDER_TOOLTIP, CLIENT_GUIS + "itemTooltip"); // unless a coremod draws them (modTooltips)
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
        // ChunkCache (pathfinding's block snapshot) reads its world: bridged worlds have no legacy chunks.
        String cache = "net.minecraft.world.ChunkCache";
        String glue = "retrofit/legacy/bridge/ChunkCacheGlue.";
        redirect(cache, "func_72806_N()Z", glue + "extendedLevels");
        redirect(cache, "func_147439_a(III)Lnet/minecraft/block/Block;", glue + "getBlock");
        redirect(cache, "func_147438_o(III)Lnet/minecraft/tileentity/TileEntity;", glue + "getTileEntity");
        redirect(cache, "func_72802_i(IIII)I", glue + "getLightBrightnessForSkyBlocks");
        redirect(cache, "func_72805_g(III)I", glue + "getBlockMetadata");
        redirect(cache, "func_72879_k(IIII)I", glue + "isBlockProvidingPowerTo");
        redirect(cache, "func_72807_a(II)Lnet/minecraft/world/biome/BiomeGenBase;", glue + "getBiomeGenForCoords");
        redirect(cache, "func_147437_c(III)Z", glue + "isAirBlock");
        redirect(cache, "func_72810_a(Lnet/minecraft/world/EnumSkyBlock;III)I", glue + "getSkyBlockTypeBrightness");
        redirect(cache, "func_72812_b(Lnet/minecraft/world/EnumSkyBlock;III)I", glue + "getSpecialBlockBrightness");
        redirect(cache, "func_72800_K()I", glue + "getHeight");
        redirect(cache, "isSideSolid(IIILnet/minecraftforge/common/util/ForgeDirection;Z)Z", glue + "isSideSolid");
        // A dimension's world, asked for before anyone went there: its 1.20.1 level's (see the bridge's Dimensions).
        redirect("net.minecraftforge.common.DimensionManager", "initDimension(I)V", "retrofit/legacy/bridge/Dimensions.initDimension");
        // A mod registering a dimension id another mod registered first (1.7.10 mods often shipped clashing
        // default ids, and this threw and left the whole mod out): the bridge warns and skips the clash instead.
        redirect("net.minecraftforge.common.DimensionManager", "registerDimension(II)V", "retrofit/legacy/bridge/Dimensions.registerDimension");
        // Mod packets: FML's outbound handler hands each packet to the host's channel.
        redirect("cpw.mods.fml.common.network.FMLOutboundHandler",
                "write(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Object;Lio/netty/channel/ChannelPromise;)V", NETWORK + "outbound");

        // Mods that number their own biomes check the shared biome array for their ids and refuse to
        // load when another mod took one (Erebus and Advent of Ascension both default to 100; on
        // 1.7.10 the player renumbered a config): the bridge shows each mod the biomes it registered
        // itself, and every biome construction reports itself to it (Biomes.record).
        redirect("net.minecraft.world.biome.BiomeGenBase", "func_150565_n()[Lnet/minecraft/world/biome/BiomeGenBase;", BIOMES + "viewedArray"); // getBiomeGenArray
        constructorHook("net.minecraft.world.biome.BiomeGenBase", "<init>(I)V", BIOMES + "record");
        constructorHook("net.minecraft.world.biome.BiomeGenBase", "<init>(IZ)V", BIOMES + "record"); // Forge's, which (I)V calls into
        // Creatures wearing 1.20.1 armor (carried as one ItemArmor per slot) are protected by its own armor points.
        redirect("net.minecraft.entity.EntityLivingBase", "func_70658_aO()I", "retrofit/legacy/bridge/ModernContent.totalArmorValue"); // getTotalArmorValue
    }

    /** Whether a coremod replaced GuiScreen.renderToolTip with its own tooltips (ManaMetal's): see noteFinalClass. */
    private static volatile boolean modTooltips;
    /**
     * A class as the coremods left it (RetrofitTransformer.Late, just before this transformer). A
     * renderToolTip that no longer ends in Forge's drawHoveringText draws a mod's own tooltips: it
     * keeps its body instead of the redirect, and the host asks it for tooltips in its screens too.
     */
    static void noteFinalClass(String transformedName, byte[] basicClass) {
        if (!BRIDGE_PRESENT || basicClass == null || !transformedName.equals("net.minecraft.client.gui.GuiScreen")) {
            return;
        }
        ClassNode node = new ClassNode();
        new ClassReader(basicClass).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (Object element : node.methods) {
            MethodNode method = (MethodNode) element;
            if (!(method.name + method.desc).equals(RENDER_TOOLTIP)) {
                continue;
            }
            boolean vanilla = false;
            for (org.objectweb.asm.tree.AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                vanilla |= insn instanceof MethodInsnNode && ((MethodInsnNode) insn).name.equals("drawHoveringText");
            }
            modTooltips = !vanilla;
            if (modTooltips) {
                System.out.println("[Retrofit] A coremod draws item tooltips itself (GuiScreen.renderToolTip); 1.20.1 screens show them too");
            }
        }
    }

    public static boolean modTooltips() {
        return modTooltips;
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

    private static void constructorHook(String className, String constructor, String target) {
        Map<String, String> constructors = CONSTRUCTORS.get(className);
        if (constructors == null) {
            constructors = new HashMap<String, String>();
            CONSTRUCTORS.put(className, constructors);
        }
        constructors.put(constructor, target);
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        Map<String, String> methods = STUBS.get(transformedName);
        Map<String, String> redirects = BRIDGE_PRESENT ? REDIRECTS.get(transformedName) : null;
        Map<String, String> constructors = BRIDGE_PRESENT ? CONSTRUCTORS.get(transformedName) : null;
        if (basicClass == null || methods == null && redirects == null && constructors == null) {
            return basicClass;
        }
        ClassNode node = new ClassNode();
        new ClassReader(basicClass).accept(node, 0);
        for (Object element : node.methods) { // ASM 5 uses raw lists
            MethodNode method = (MethodNode) element;
            String hook = methods == null ? null : methods.get(method.name + method.desc);
            String target = redirects == null ? null : redirects.get(method.name + method.desc);
            String constructor = constructors == null ? null : constructors.get(method.name + method.desc);
            if (hook != null) {
                replaceBody(method, hook);
                System.out.println("[Retrofit] Stubbed " + transformedName + "." + method.name + method.desc);
            } else if (target != null && !(modTooltips && (method.name + method.desc).equals(RENDER_TOOLTIP))) {
                redirectBody(node.name, method, target);
            } else if (constructor != null) {
                hookConstructorBody(node.name, method, constructor);
            }
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** The constructor ends with "Target.method(this)" before each return (the object is ready). */
    private static void hookConstructorBody(String owner, MethodNode method, String target) {
        int dot = target.lastIndexOf('.');
        String ownerName = target.substring(0, dot);
        String methodName = target.substring(dot + 1);
        String desc = "(L" + owner + ";)V";
        boolean hooked = false;
        for (org.objectweb.asm.tree.AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.RETURN) { // constructors are void, so RETURN is their only exit
                InsnList call = new InsnList(); // a fresh copy per return: lists belong to one place
                call.add(new VarInsnNode(Opcodes.ALOAD, 0));
                call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ownerName, methodName, desc, false));
                method.instructions.insertBefore(insn, call);
                hooked = true;
            }
        }
        if (hooked) {
            System.out.println("[Retrofit] Hooked " + owner + "." + method.name + method.desc + " -> " + target);
        }
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
