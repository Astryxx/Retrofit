package retrofit.legacy.boot;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Each mod's own view of dimension ids. 1.7.10 mods number their dimensions (and provider types) in their
 * configs, and two often pick the same number (Atum and Advent of Ascension both 17): on 1.7.10 the player
 * had to renumber one. Here the mod registering a taken id gets a free one (retrofit.legacy.bridge.DimensionIds),
 * and inside that mod's classes every dimension id is seen through its view: the id it asked for stands for
 * the one it got, and the other way round, so its config's number, its portals' "is the player there"
 * checks and its teleports all agree with each other, while the rest of the game sees the real ids.
 *
 * In mod classes (never vanilla, FML, Forge, libraries or Retrofit's own), reads and writes of
 * Entity.dimension, WorldProvider.dimensionId and the dimension fields of FML's TargetPoint and
 * PlayerChangedDimensionEvent, and the dimension arguments and results of the methods mods move players and
 * look up dimensions with, pass through the bridge's DimensionIds with the class's name (which names its mod).
 * Ids on top of the stack are converted in place; calls whose id is not the last argument go to a wrapper of
 * the same shape. Nothing adds a branch, so stack map frames stay valid. With no id moved (most packs), the
 * converters return what they get.
 */
final class DimensionViews {
    private static final String IDS = "retrofit/legacy/bridge/DimensionIds";
    private static final String OWNER_ARG = "Ljava/lang/String;";

    /** Fields holding a dimension id: name + desc (SRG names are unique) -> true for those mods may write too. */
    private static final Map<String, Boolean> FIELDS = new HashMap<String, Boolean>();
    /** "owner.name desc" (owner "*" for any) of calls whose last argument is a dimension id -> "dim" or "type". */
    private static final Map<String, String> LAST_ARGUMENT = new HashMap<String, String>();
    /** Calls whose result is a dimension id (or ids, or a provider type) -> the converter. */
    private static final Map<String, String> RESULTS = new HashMap<String, String>();
    /** Calls replaced by a wrapper in DimensionIds taking the same arguments (receiver first) and the class's name. */
    private static final Map<String, String> WRAPPED = new HashMap<String, String>();
    /** Constructors whose object is corrected right after it is made: owner -> fixer. */
    private static final Map<String, String> CONSTRUCTED = new HashMap<String, String>();

    private static final String DIMENSIONS = "net/minecraftforge/common/DimensionManager";
    /**
     * The biome array lookups ("name desc" on BiomeGenBase) -> the bridge's Biomes method taking the same arguments
     * and the class's name: each mod's own view of biome ids, in its classes, also outside its loading (its world
     * generators look its biomes up by the ids its config names).
     */
    private static final Map<String, String> BIOME_LOOKUPS = new HashMap<String, String>();
    private static final String BIOME = "net/minecraft/world/biome/BiomeGenBase";
    private static final String CONFIGURATION = "net/minecraft/server/management/ServerConfigurationManager";

    static {
        BIOME_LOOKUPS.put("func_150565_n ()[L" + BIOME + ";", "viewedArrayFor"); // getBiomeGenArray
        BIOME_LOOKUPS.put("func_150568_d (I)L" + BIOME + ";", "biomeFor"); // getBiome
        FIELDS.put("field_71093_bK I", true); // Entity.dimension
        FIELDS.put("field_76574_g I", true); // WorldProvider.dimensionId
        FIELDS.put("dimension I", false); // NetworkRegistry.TargetPoint.dimension (final)
        FIELDS.put("fromDim I", false); // PlayerChangedDimensionEvent
        FIELDS.put("toDim I", false);

        for (String method : new String[]{"getWorld (I)Lnet/minecraft/world/WorldServer;", "initDimension (I)V", "unregisterDimension (I)V",
                "isDimensionRegistered (I)Z", "createProviderFor (I)Lnet/minecraft/world/WorldProvider;", "unloadWorld (I)V",
                "getProviderType (I)I"}) {
            LAST_ARGUMENT.put(DIMENSIONS + "." + method, "dim");
        }
        LAST_ARGUMENT.put(DIMENSIONS + ".unregisterProviderType (I)[I", "type");
        LAST_ARGUMENT.put("net/minecraft/world/WorldProvider.func_76570_a (I)Lnet/minecraft/world/WorldProvider;", "dim"); // getProviderForDimension
        LAST_ARGUMENT.put("net/minecraft/server/MinecraftServer.func_71218_a (I)Lnet/minecraft/world/WorldServer;", "dim"); // worldServerForDimension
        LAST_ARGUMENT.put("*.func_71027_c (I)V", "dim"); // Entity.travelToDimension, on any entity
        LAST_ARGUMENT.put(CONFIGURATION + ".func_72356_a (Lnet/minecraft/entity/player/EntityPlayerMP;I)V", "dim"); // transferPlayerToDimension
        LAST_ARGUMENT.put(CONFIGURATION + ".func_148537_a (Lnet/minecraft/network/Packet;I)V", "dim"); // sendPacketToAllPlayersInDimension
        LAST_ARGUMENT.put("cpw/mods/fml/common/network/simpleimpl/SimpleNetworkWrapper.sendToDimension"
                + " (Lcpw/mods/fml/common/network/simpleimpl/IMessage;I)V", "dim");
        LAST_ARGUMENT.put("cpw/mods/fml/common/network/FMLEventChannel.sendToDimension"
                + " (Lcpw/mods/fml/common/network/internal/FMLProxyPacket;I)V", "dim");
        LAST_ARGUMENT.put("*.getBedLocation (I)Lnet/minecraft/util/ChunkCoordinates;", "dim"); // EntityPlayer (Forge)
        LAST_ARGUMENT.put("*.isSpawnForced (I)Z", "dim");
        LAST_ARGUMENT.put("*.setSpawnChunk (Lnet/minecraft/util/ChunkCoordinates;ZI)V", "dim");

        RESULTS.put(DIMENSIONS + ".getProviderType (I)I", "viewType");
        RESULTS.put(DIMENSIONS + ".getIDs ()[Ljava/lang/Integer;", "viewIds");
        RESULTS.put(DIMENSIONS + ".getIDs (Z)[Ljava/lang/Integer;", "viewIds");
        RESULTS.put(DIMENSIONS + ".getStaticDimensionIDs ()[Ljava/lang/Integer;", "viewIds");
        RESULTS.put(DIMENSIONS + ".getNextFreeDimId ()I", "view");

        WRAPPED.put(DIMENSIONS + ".registerDimension (II)V", "registerDimension");
        WRAPPED.put(DIMENSIONS + ".registerProviderType (ILjava/lang/Class;Z)Z", "registerProviderType");
        WRAPPED.put(DIMENSIONS + ".setWorld (ILnet/minecraft/world/WorldServer;)V", "setWorld");
        WRAPPED.put(CONFIGURATION + ".transferPlayerToDimension"
                + " (Lnet/minecraft/entity/player/EntityPlayerMP;ILnet/minecraft/world/Teleporter;)V", "transferPlayerToDimension");
        WRAPPED.put(CONFIGURATION + ".func_82448_a (Lnet/minecraft/entity/Entity;ILnet/minecraft/world/WorldServer;"
                + "Lnet/minecraft/world/WorldServer;)V", "transferEntityToWorld");
        WRAPPED.put(CONFIGURATION + ".transferEntityToWorld (Lnet/minecraft/entity/Entity;ILnet/minecraft/world/WorldServer;"
                + "Lnet/minecraft/world/WorldServer;Lnet/minecraft/world/Teleporter;)V", "transferEntityToWorldWith");
        WRAPPED.put(CONFIGURATION + ".func_148541_a (DDDDILnet/minecraft/network/Packet;)V", "sendToAllNear");
        WRAPPED.put(CONFIGURATION + ".func_148543_a (Lnet/minecraft/entity/player/EntityPlayer;DDDDILnet/minecraft/network/Packet;)V",
                "sendToAllNearExcept");

        CONSTRUCTED.put("cpw/mods/fml/common/network/NetworkRegistry$TargetPoint", "fixTargetPoint");
        CONSTRUCTED.put("net/minecraft/network/play/server/S07PacketRespawn", "fixRespawnPacket");
    }

    private static final byte[][] MARKERS = {
            bytes("field_71093_bK"), bytes("field_76574_g"), bytes("TargetPoint"), bytes("PlayerChangedDimensionEvent"),
            bytes("func_150565_n"), bytes("func_150568_d"), // BiomeGenBase.getBiomeGenArray, getBiome (see BIOME_LOOKUPS)
            bytes("DimensionManager"), bytes("func_71218_a"), bytes("func_71027_c"), bytes("ServerConfigurationManager"),
            bytes("sendToDimension"), bytes("getBedLocation"), bytes("isSpawnForced"), bytes("setSpawnChunk"),
            bytes("S07PacketRespawn"), bytes("getRespawnDimension"), bytes("func_76570_a"),
    };

    private static final ByteMarkers MARKER_SET = new ByteMarkers(MARKERS);

    private static final String[] NOT_MODS = {"net/minecraft/", "net/minecraftforge/", "cpw/mods/fml/", "retrofit/", "java/",
            "javax/", "sun/", "com/google/", "com/mojang/", "org/apache/", "org/lwjgl/", "io/netty/", "paulscode/", "joptsimple/",
            "org/objectweb/", "scala/", "kotlin/", "LZMA/", "gnu/trove/", "org/spongepowered/"};

    private DimensionViews() {
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] apply(String transformedName, byte[] bytes) {
        String internal = transformedName.replace('.', '/');
        for (String prefix : NOT_MODS) {
            if (internal.startsWith(prefix)) {
                return bytes;
            }
        }
        if (!MARKER_SET.anyIn(bytes)) {
            return bytes;
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int changed = 0;
        for (Object element : node.methods) { // ASM 5 uses raw lists
            MethodNode method = (MethodNode) element;
            changed += rewrite(node, method);
        }
        if (changed == 0) {
            return bytes;
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static int rewrite(ClassNode node, MethodNode method) {
        String owner = node.name;
        int changed = 0;
        boolean superCalled = !method.name.equals("<init>");
        boolean respawnDimension = method.name.equals("getRespawnDimension") && method.desc.equals("(Lnet/minecraft/entity/player/EntityPlayerMP;)I");
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            if (insn instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) insn;
                Boolean writable = FIELDS.get(field.name + " " + field.desc);
                if (writable == null || !dimensionFieldOwner(field)) {
                    continue;
                }
                if (opcode == Opcodes.GETFIELD) {
                    method.instructions.insert(insn, convert(owner, "view"));
                    changed++;
                } else if (opcode == Opcodes.PUTFIELD && writable) {
                    method.instructions.insertBefore(insn, convert(owner, "real"));
                    changed++;
                }
            } else if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                String key = call.owner + "." + call.name + " " + call.desc;
                String anyKey = "*." + call.name + " " + call.desc;
                String biomeLookup = call.owner.equals(BIOME) && opcode == Opcodes.INVOKESTATIC ? BIOME_LOOKUPS.get(call.name + " " + call.desc) : null;
                if (biomeLookup != null) {
                    int close = call.desc.indexOf(')');
                    MethodInsnNode replacement = new MethodInsnNode(Opcodes.INVOKESTATIC, "retrofit/legacy/bridge/Biomes", biomeLookup,
                            call.desc.substring(0, close) + OWNER_ARG + call.desc.substring(close), false);
                    method.instructions.insertBefore(call, new LdcInsnNode(owner));
                    method.instructions.set(call, replacement);
                    insn = replacement;
                    changed++;
                    continue;
                }
                String wrapper = WRAPPED.get(key);
                if (wrapper != null) {
                    String desc = call.desc;
                    if (opcode != Opcodes.INVOKESTATIC) {
                        desc = "(L" + call.owner + ";" + desc.substring(1);
                    }
                    int close = desc.indexOf(')');
                    MethodInsnNode replacement = new MethodInsnNode(Opcodes.INVOKESTATIC, IDS, wrapper,
                            desc.substring(0, close) + OWNER_ARG + desc.substring(close), false);
                    method.instructions.insertBefore(call, new LdcInsnNode(owner));
                    method.instructions.set(call, replacement);
                    insn = replacement; // (the replaced node is out of the list)
                    changed++;
                    continue;
                }
                String argument = LAST_ARGUMENT.get(key);
                if (argument == null) {
                    argument = LAST_ARGUMENT.get(anyKey);
                }
                if (argument != null) {
                    method.instructions.insertBefore(call, convert(owner, argument.equals("type") ? "realType" : "real"));
                    changed++;
                }
                String result = RESULTS.get(key);
                if (result != null) {
                    InsnList after = new InsnList();
                    after.add(new LdcInsnNode(owner));
                    String type = result.equals("viewIds") ? "[Ljava/lang/Integer;" : "I";
                    after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, IDS, result, "(" + type + OWNER_ARG + ")" + type, false));
                    method.instructions.insert(call, after);
                    changed++;
                }
                boolean superCall = false;
                if (!superCalled && opcode == Opcodes.INVOKESPECIAL && call.name.equals("<init>")
                        && (call.owner.equals(node.superName) || call.owner.equals(node.name))) {
                    superCalled = true; // a constructor's own super(...) or this(...): no new object on the stack
                    superCall = true;
                }
                String fixer = opcode == Opcodes.INVOKESPECIAL && call.name.equals("<init>") ? CONSTRUCTED.get(call.owner) : null;
                if (fixer != null && !superCall) {
                    InsnList after = new InsnList();
                    after.add(new InsnNode(Opcodes.DUP)); // new X; dup; args; <init>: the object is on the stack
                    after.add(new LdcInsnNode(owner));
                    after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, IDS, fixer, "(Ljava/lang/Object;" + OWNER_ARG + ")V", false));
                    method.instructions.insert(call, after);
                    changed++;
                }
            } else if (respawnDimension && opcode == Opcodes.IRETURN) {
                // a mod's provider says where players respawn, in its own view; the game wants the real id
                method.instructions.insertBefore(insn, convert(owner, "real"));
                changed++;
            }
        }
        return changed;
    }

    /** Only fields of entities, providers and the two FML classes (a mod's own field named "dimension" is its own). */
    private static boolean dimensionFieldOwner(FieldInsnNode field) {
        if (field.name.equals("dimension")) {
            return field.owner.equals("cpw/mods/fml/common/network/NetworkRegistry$TargetPoint");
        }
        if (field.name.equals("fromDim") || field.name.equals("toDim")) {
            return field.owner.equals("cpw/mods/fml/common/gameevent/PlayerEvent$PlayerChangedDimensionEvent");
        }
        return true; // SRG names: only Entity's and WorldProvider's (and their subclasses')
    }

    /** The id on top of the stack, through a converter: "view" (real to the class's mod's), "real", "realType", "viewType". */
    private static InsnList convert(String owner, String converter) {
        InsnList list = new InsnList();
        list.add(new LdcInsnNode(owner));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, IDS, converter, "(I" + OWNER_ARG + ")I", false));
        return list;
    }
}
