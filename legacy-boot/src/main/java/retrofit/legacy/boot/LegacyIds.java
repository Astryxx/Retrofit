package retrofit.legacy.boot;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 1.7.10's numeric ids without the clashes and limits Retrofit has no use for. Mods pick their
 * enchantment and potion ids by hand (from their configs), so two mods often pick the same one:
 * 1.7.10 then refused the second enchantment ("Duplicate enchantment id!", and the mod failed) and
 * let the second potion replace the first. In 1.20.1 these live in registries by name (see
 * LegacyEnchantments, LegacyMobEffects), so the legacy number only has to be unique: a mod asking
 * for a taken one gets the lowest free one instead, and keeps its own whenever it is free.
 *
 * Other ids were sized for 1.7.10's packets and chunk storage, which 1.20.1's replace, and large
 * modpacks ran out of them: global entity ids (bytes) go up to 32766, and block ids share the item
 * id range up to 31999 instead of stopping at 4095. Ids are still handed out lowest first, so a pack
 * that fit before gets the ids it had. 1.7.10's chunk sections, which mod dimensions' generators fill,
 * keep the bits past 12 beside them (see wideStorage).
 *
 * Applied by RetrofitTransformer (the registries load in either of its stages, vanilla classes in
 * the late one, with deobfuscated names); Enchantment's and Potion's constructors call
 * {@link #enchantmentId} and {@link #potionId}. None of the changes adds a branch, so the methods'
 * stack map frames stay as they are.
 */
public final class LegacyIds {
    private static final String SELF = "retrofit/legacy/boot/LegacyIds";
    private static final int MAX_BLOCK_ID = 4095;
    private static final int MAX_ITEM_ID = 31999;

    private LegacyIds() {
    }

    static byte[] apply(String transformedName, byte[] bytes) {
        if (transformedName.equals("cpw.mods.fml.common.registry.EntityRegistry")) {
            // availableIndicies.set(1, 255): the ids findGlobalUniqueEntityId hands out
            return skipTakenEntityIds(widen(bytes, transformedName, "<init>", 255, Short.MAX_VALUE));
        }
        if (transformedName.equals("net.minecraft.entity.EntityList")) {
            // addMapping: "Attempted to register a entity with invalid ID" above 255
            return widen(bytes, transformedName, "func_75618_a", 255, Short.MAX_VALUE);
        }
        if (transformedName.equals("cpw.mods.fml.common.registry.GameData")
                || transformedName.equals("cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry")) {
            // the block registry's maximum, and the checks that blocks and item blocks stay below it
            return widen(bytes, transformedName, null, MAX_BLOCK_ID, MAX_ITEM_ID);
        }
        if (transformedName.equals("net.minecraft.block.BlockFire") || transformedName.equals("net.minecraft.stats.StatList")) {
            // tables by block id, 4096 long: fire's flammability and spread (Witchery's logs, past id 4095 in a
            // large pack, failed the mod), and the blocks' mining statistics
            return widen(bytes, transformedName, null, MAX_BLOCK_ID + 1, MAX_ITEM_ID + 1);
        }
        if (transformedName.equals("net.minecraft.world.chunk.storage.ExtendedBlockStorage")) {
            return wideStorage(bytes, transformedName);
        }
        if (transformedName.equals("net.minecraft.enchantment.Enchantment")) {
            return claimInConstructor(bytes, transformedName, "(IILnet/minecraft/enchantment/EnumEnchantmentType;)V", "enchantmentId");
        }
        if (transformedName.equals("net.minecraft.potion.Potion")) {
            return claimInConstructor(bytes, transformedName, "(IZI)V", "potionId");
        }
        if (transformedName.equals("net.minecraft.world.biome.BiomeGenBase")) {
            return claimBiome(bytes, transformedName);
        }
        return bytes;
    }

    /** Every {@code from} constant pushed in the method (or, with no name, the class) becomes {@code to}. */
    private static byte[] widen(byte[] bytes, String className, String methodName, int from, int to) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int changed = 0;
        for (Object element : node.methods) { // ASM 5 uses raw lists
            MethodNode method = (MethodNode) element;
            if (methodName != null && !method.name.equals(methodName)) {
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.SIPUSH && ((IntInsnNode) insn).operand == from) {
                    ((IntInsnNode) insn).operand = to;
                    changed++;
                }
            }
        }
        return changed > 0 ? write(node) : unchanged(bytes, className);
    }

    /** The constructor passes its id (local 1) through {@code helper} right after Object's constructor. */
    private static byte[] claimInConstructor(byte[] bytes, String className, String desc, String helper) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (Object element : node.methods) {
            MethodNode method = (MethodNode) element;
            if (!method.name.equals("<init>") || !method.desc.equals(desc)) {
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.INVOKESPECIAL && ((MethodInsnNode) insn).owner.equals("java/lang/Object")) {
                    InsnList call = new InsnList();
                    call.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    call.add(new VarInsnNode(Opcodes.ILOAD, 1));
                    call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, helper, "(Ljava/lang/Object;I)I", false));
                    call.add(new VarInsnNode(Opcodes.ISTORE, 1));
                    method.instructions.insert(insn, call);
                    return write(node);
                }
            }
        }
        return unchanged(bytes, className);
    }

    /**
     * findGlobalUniqueEntityId passes the id it found through {@link #freeEntityId} before returning it.
     * Its bit set only knows the ids that went through EntityRegistry; mods that call EntityList.addMapping
     * themselves take ids it still hands out ("ID is already registered"), which 1.7.10's range of 255 hid.
     */
    private static byte[] skipTakenEntityIds(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (Object element : node.methods) {
            MethodNode method = (MethodNode) element;
            if (!method.name.equals("findGlobalUniqueEntityId") || !method.desc.equals("()I")) {
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.ISTORE && ((VarInsnNode) insn).var == 0) {
                    InsnList call = new InsnList();
                    call.add(new LdcInsnNode(Type.getObjectType(node.name)));
                    call.add(new VarInsnNode(Opcodes.ILOAD, 0));
                    call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "freeEntityId", "(Ljava/lang/Class;I)I", false));
                    call.add(new VarInsnNode(Opcodes.ISTORE, 0));
                    method.instructions.insert(insn, call);
                    return write(node);
                }
            }
        }
        return unchanged(bytes, "cpw.mods.fml.common.registry.EntityRegistry");
    }

    /** The first id from {@code id} on that EntityList doesn't hold yet; taken ones leave EntityRegistry's free set. */
    public static int freeEntityId(Class<?> entityRegistry, int id) {
        try {
            Object registry = entityRegistry.getMethod("instance").invoke(null);
            Field freeField = entityRegistry.getDeclaredField("availableIndicies");
            freeField.setAccessible(true);
            java.util.BitSet free = (java.util.BitSet) freeField.get(registry);
            Field byIdField = Class.forName("net.minecraft.entity.EntityList", false, entityRegistry.getClassLoader())
                    .getDeclaredField("field_75623_d"); // IDtoClassMapping
            byIdField.setAccessible(true);
            java.util.Map<?, ?> byId = (java.util.Map<?, ?>) byIdField.get(null);
            while (id >= 0 && byId.containsKey(id)) {
                free.clear(id);
                id = free.nextSetBit(id + 1);
            }
        } catch (Exception e) {
            System.out.println("[Retrofit] Could not check entity id " + id + " against EntityList: " + e);
        }
        return id;
    }

    /**
     * 1.7.10's chunk sections keep 12 bits of a block id (a byte and a nibble); mods' dimension generators
     * fill them, and with block ids past 4095 (see above) such blocks came out as others. The bits above
     * go in a table beside each section: setExtBlockID records them as it stores the id, and every
     * getBlockById of a stored id (getBlockByExtId, and setExtBlockID's look at the block it replaces)
     * gets them back.
     */
    private static byte[] wideStorage(byte[] bytes, String className) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int reads = 0;
        int records = 0;
        for (Object element : node.methods) {
            MethodNode method = (MethodNode) element;
            boolean set = method.name.equals("func_150818_a") && method.desc.equals("(IIILnet/minecraft/block/Block;)V"); // setExtBlockID
            boolean get = method.name.equals("func_150819_a") && method.desc.equals("(III)Lnet/minecraft/block/Block;"); // getBlockByExtId
            if (!set && !get) {
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() != Opcodes.INVOKESTATIC) {
                    continue;
                }
                String called = ((MethodInsnNode) insn).name;
                if (called.equals("func_149729_e")) { // getBlockById(stored id): the id is on the stack
                    method.instructions.insertBefore(insn, positionCall(Opcodes.NOP, "storageId", "(ILjava/lang/Object;III)I"));
                    reads++;
                } else if (set && called.equals("func_149682_b")) { // getIdFromBlock(the new block): keep its high bits
                    method.instructions.insert(insn, positionCall(Opcodes.DUP, "storageRecord", "(ILjava/lang/Object;III)V"));
                    records++;
                }
            }
        }
        return reads == 2 && records == 1 ? write(node) : unchanged(bytes, className);
    }

    /** [first] this, x, y, z, then the static call: the section and the block's place in it. */
    private static InsnList positionCall(int first, String helper, String desc) {
        InsnList call = new InsnList();
        if (first != Opcodes.NOP) {
            call.add(new org.objectweb.asm.tree.InsnNode(first));
        }
        call.add(new VarInsnNode(Opcodes.ALOAD, 0));
        call.add(new VarInsnNode(Opcodes.ILOAD, 1));
        call.add(new VarInsnNode(Opcodes.ILOAD, 2));
        call.add(new VarInsnNode(Opcodes.ILOAD, 3));
        call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, helper, desc, false));
        return call;
    }

    /** Section -> the bits of each block's id above the 12 its arrays keep, by (y << 8 | z << 4 | x); only sections that need them. */
    private static final java.util.Map<Object, byte[]> HIGH_BITS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, byte[]>());

    /** setExtBlockID: the bits of the stored id above 12, kept beside the section. */
    public static void storageRecord(int id, Object storage, int x, int y, int z) {
        byte[] high = HIGH_BITS.get(storage);
        if (high == null) {
            if (id >> 12 == 0) {
                return; // the usual case: the section's arrays hold it all
            }
            high = new byte[4096];
            HIGH_BITS.put(storage, high);
        }
        high[y << 8 | z << 4 | x] = (byte) (id >> 12);
    }

    /** A stored id with the bits kept beside the section. */
    public static int storageId(int id, Object storage, int x, int y, int z) {
        byte[] high = HIGH_BITS.get(storage);
        return high == null ? id : id | (high[y << 8 | z << 4 | x] & 0xFF) << 12;
    }

    /**
     * BiomeGenBase(int id, boolean register) passes its id (and whether it registers) through {@link #biomeId}
     * right after Object's constructor: 1.7.10 let a biome taking a registered biome's id replace it, and the
     * other mod's world generation then got the wrong biome.
     */
    private static byte[] claimBiome(byte[] bytes, String className) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (Object element : node.methods) {
            MethodNode method = (MethodNode) element;
            if (!method.name.equals("<init>") || !method.desc.equals("(IZ)V")) {
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.INVOKESPECIAL && ((MethodInsnNode) insn).owner.equals("java/lang/Object")) {
                    InsnList call = new InsnList();
                    call.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    call.add(new VarInsnNode(Opcodes.ILOAD, 1));
                    call.add(new VarInsnNode(Opcodes.ILOAD, 2));
                    call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "biomeId", "(Ljava/lang/Object;IZ)I", false));
                    call.add(new VarInsnNode(Opcodes.ISTORE, 1));
                    method.instructions.insert(insn, call);
                    return write(node);
                }
            }
        }
        return unchanged(bytes, className);
    }

    /** BiomeGenBase's constructor: the id to take, for a biome that registers (biomeList; chunks keep biome ids as bytes). */
    public static int biomeId(Object biome, int id, boolean register) {
        if (!register) {
            return id;
        }
        int taken = claim(biome, "net.minecraft.world.biome.BiomeGenBase", "field_76773_a", id, 0, 255, "biome");
        if (taken != id) {
            synchronized (ASKED_BIOME_IDS) {
                ASKED_BIOME_IDS.put(biome, id);
            }
        }
        return taken;
    }

    /** Biomes that got another id than they asked for: the id asked (the mod that made one still looks it up there). */
    private static final Map<Object, Integer> ASKED_BIOME_IDS = new WeakHashMap<Object, Integer>();

    /** The id a biome asked for, if it got another (see biomeId); -1 if it got the one it asked for. */
    public static int askedBiomeId(Object biome) {
        synchronized (ASKED_BIOME_IDS) {
            Integer asked = ASKED_BIOME_IDS.get(biome);
            return asked == null ? -1 : asked;
        }
    }

    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] unchanged(byte[] bytes, String className) {
        System.out.println("[Retrofit] " + className + " is not as expected; its ids are not changed");
        return bytes;
    }

    /** Enchantment's constructor: the id to take (enchantmentsList; stacks save it as a short). */
    public static int enchantmentId(Object enchantment, int id) {
        return claim(enchantment, "net.minecraft.enchantment.Enchantment", "field_77331_b", id, 0, Short.MAX_VALUE, "enchantment");
    }

    /** Potion's constructor: the id to take (potionTypes; entities save effects' ids as a byte). */
    public static int potionId(Object potion, int id) {
        return claim(potion, "net.minecraft.potion.Potion", "field_76425_a", id, 1, Byte.MAX_VALUE, "potion");
    }

    /**
     * The id the constructor asked for if its slot is free (the array grows to reach it, up to
     * {@code max}), else the lowest free one. If none is left, the id asked for, as on 1.7.10.
     */
    private static synchronized int claim(Object entry, String owner, String fieldName, int id, int min, int max, String kind) {
        try {
            Field field = arrayField(entry.getClass(), owner, fieldName);
            Object[] slots = (Object[]) field.get(null);
            if (id >= min && id <= max && (id >= slots.length || slots[id] == null)) {
                if (id >= slots.length) {
                    grow(field, slots, id + 1);
                }
                return id;
            }
            for (int free = min; free <= max; free++) {
                if (free >= slots.length) {
                    slots = grow(field, slots, Math.min(max + 1, Math.max(free + 1, slots.length * 2)));
                }
                if (slots[free] == null) {
                    String holder = id >= 0 && id < slots.length && slots[id] != null ? slots[id].getClass().getName() : null;
                    System.out.println("[Retrofit] The 1.7.10 " + kind + " " + entry.getClass().getName() + " asked for id " + id
                            + (holder != null ? ", which " + holder + " has" : ", which 1.7.10 can't hold") + "; it gets " + free);
                    return free;
                }
            }
        } catch (Exception e) {
            System.out.println("[Retrofit] Could not check the id of the 1.7.10 " + kind + " " + entry.getClass().getName() + ": " + e);
        }
        return id;
    }

    private static Object[] grow(Field field, Object[] slots, int length) throws Exception {
        Object[] grown = (Object[]) java.lang.reflect.Array.newInstance(slots.getClass().getComponentType(), length);
        System.arraycopy(slots, 0, grown, 0, slots.length);
        retrofit.legacy.compat.FinalFields.set(field, null, grown);
        return grown;
    }

    private static Field arrayField(Class<?> type, String owner, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (c.getName().equals(owner)) {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            }
        }
        throw new NoSuchFieldException(owner + "." + name);
    }
}
