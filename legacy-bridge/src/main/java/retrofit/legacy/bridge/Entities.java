package retrofit.legacy.bridge;

import cpw.mods.fml.common.registry.IEntityAdditionalSpawnData;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityAgeable;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.passive.EntityAmbientCreature;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityWaterMob;
import net.minecraft.entity.monster.IMob;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Legacy entities for the host. Each legacy mod entity type becomes a 1.20.1 entity type; the
 * host entity owns a legacy entity in its level's legacy world, which runs its own update (AI,
 * movement, attacks) on the server, while clients keep a copy of it, positioned from the host and
 * synced through its DataWatcher and spawn data, for its renderer.
 */
public final class Entities {
    private Entities() {
    }

    /** Every name 1.7.10's EntityList registers its own entity classes under. */
    private static final java.util.Set<String> VANILLA_NAMES = new java.util.HashSet<String>(java.util.Arrays.asList(
            "Item", "XPOrb", "LeashKnot", "Painting", "Arrow", "Snowball", "Fireball", "SmallFireball", "ThrownEnderpearl",
            "EyeOfEnderSignal", "ThrownPotion", "ThrownExpBottle", "ThrownEgg", "ItemFrame", "WitherSkull", "PrimedTnt",
            "FallingSand", "FireworksRocketEntity", "Boat", "Minecart", "MinecartRideable", "MinecartChest", "MinecartFurnace",
            "MinecartTNT", "MinecartHopper", "MinecartSpawner", "MinecartCommandBlock", "Mob", "Monster", "Creeper", "Skeleton",
            "Spider", "Giant", "Zombie", "Slime", "Ghast", "PigZombie", "Enderman", "CaveSpider", "Silverfish", "Blaze",
            "LavaSlime", "EnderDragon", "WitherBoss", "Bat", "Witch", "Pig", "Sheep", "Cow", "Chicken", "Squid", "Wolf",
            "MushroomCow", "SnowMan", "Ozelot", "VillagerGolem", "EntityHorse", "Villager", "EnderCrystal"));

    private static final Map<Class<?>, Boolean> VANILLA_CLASSES = new java.util.concurrent.ConcurrentHashMap<Class<?>, Boolean>();

    /**
     * True for 1.7.10's own entity classes. Package names can't tell them from a mod's: mods put their
     * classes under net.minecraft.* too (The Titans: net.minecraft.theTitans.* and, for its entities,
     * net.minecraft.entity.titan.*, .misc, .projectile...), and those are mod entities all the same, with
     * names like "thetitans.WitherBossTitan" that 1.20.1 cannot use as an entity id (upper case is
     * forbidden). A class is vanilla only if it sits in net.minecraft.entity.* and is the very class one of
     * vanilla's own EntityList names (Zombie, Boat...) stands for.
     */
    @SuppressWarnings("unchecked")
    static boolean vanillaEntityClass(Class<?> type) {
        if (!type.getName().startsWith("net.minecraft.entity.")) {
            return false;
        }
        Boolean known = VANILLA_CLASSES.get(type);
        if (known == null) {
            known = Boolean.FALSE;
            Map<String, Class<?>> byName = (Map<String, Class<?>>) EntityList.field_75625_b; // stringToClassMapping
            for (String name : VANILLA_NAMES) {
                if (byName.get(name) == type) {
                    known = Boolean.TRUE;
                    break;
                }
            }
            VANILLA_CLASSES.put(type, known);
        }
        return known;
    }

    // ---- Types ----

    /**
     * Mod entity types: {name (as registered: "modid.Name" or a global name), living 1/0, category
     * (0 monster, 1 creature, 2 water, 3 ambient, 4 misc), width, height, egg background, egg
     * foreground (-1: none), mod id, class name}.
     */
    @SuppressWarnings("unchecked")
    public static Object[][] modEntities() {
        List<Object[]> types = new ArrayList<Object[]>();
        // classToStringMapping: one name per class (mods often register a class under two names).
        for (Map.Entry<Class<?>, String> entry : ((Map<Class<?>, String>) EntityList.field_75626_c).entrySet()) {
            Class<?> type = entry.getKey();
            if (vanillaEntityClass(type)) {
                continue;
            }
            float width = 0.6f;
            float height = 1.8f;
            try {
                Entity sample = (Entity) type.getConstructor(World.class).newInstance(IsolatedHost.with(net.minecraft.init.Blocks.field_150350_a, 0));
                width = sample.field_70130_N;
                height = sample.field_70131_O;
            } catch (Throwable ignored) {
                // Not constructible without a real world; the host's defaults will do until it exists.
            }
            int[] egg = eggColors(type);
            types.add(new Object[]{entry.getValue(), EntityLivingBase.class.isAssignableFrom(type) ? 1 : 0, category(type), width, height,
                    egg[0], egg[1], modId(type), type.getName()});
        }
        return types.toArray(new Object[0][]);
    }

    /** The mod that registered an entity class (EntityRegistry), "legacy" if none says so. */
    @SuppressWarnings("unchecked")
    private static String modId(Class<?> type) {
        cpw.mods.fml.common.registry.EntityRegistry.EntityRegistration registration =
                cpw.mods.fml.common.registry.EntityRegistry.instance().lookupModSpawn((Class<? extends Entity>) type, true);
        return registration != null ? registration.getContainer().getModId() : "legacy";
    }

    @SuppressWarnings("unchecked")
    private static int[] eggColors(Class<?> type) {
        Object id;
        try {
            java.lang.reflect.Field classToId = EntityList.class.getDeclaredField("field_75624_e"); // classToIDMapping
            classToId.setAccessible(true);
            id = ((Map<Class<?>, Integer>) classToId.get(null)).get(type);
        } catch (ReflectiveOperationException e) {
            id = null;
        }
        if (id != null) {
            Object egg = EntityList.field_75627_a.get(id); // entityEggs
            if (egg instanceof EntityList.EntityEggInfo) {
                return new int[]{((EntityList.EntityEggInfo) egg).field_75611_b, ((EntityList.EntityEggInfo) egg).field_75612_c};
            }
        }
        return new int[]{-1, -1};
    }

    private static int category(Class<?> type) {
        if (IMob.class.isAssignableFrom(type)) {
            return 0;
        }
        if (EntityWaterMob.class.isAssignableFrom(type)) {
            return 2;
        }
        if (EntityAmbientCreature.class.isAssignableFrom(type)) {
            return 3;
        }
        if (EntityAnimal.class.isAssignableFrom(type) || EntityAgeable.class.isAssignableFrom(type)) {
            return 1;
        }
        return EntityLiving.class.isAssignableFrom(type) ? 1 : 4;
    }

    /** The registered name of a legacy entity ("modid.Name"), or null for vanilla classes. */
    public static String entityName(Object entity) {
        String name = EntityList.func_75621_b((Entity) entity); // getEntityString
        return name == null || vanillaEntityClass(entity.getClass()) || entity instanceof HostCreatureProxies.Proxy ? null : name;
    }

    // ---- Life ----

    public static Object entityCreate(String name, Object world, double x, double y, double z) {
        Entity entity = EntityList.func_75620_a(name, (World) world); // createEntityByName
        if (entity != null) {
            entity.func_70012_b(x, y, z, 0, 0); // setLocationAndAngles
        }
        return entity;
    }

    /**
     * A new mob made by the host (natural spawning, spawn eggs, /summon), set up as 1.7.10 did:
     * SpecialSpawn first, then onSpawnWithEgg, where mobs pick their equipment, variant and size.
     */
    public static void entityFinalizeSpawn(Object entity) {
        if (!(entity instanceof net.minecraft.entity.EntityLiving)) {
            return;
        }
        net.minecraft.entity.EntityLiving living = (net.minecraft.entity.EntityLiving) entity;
        try {
            if (!net.minecraftforge.event.ForgeEventFactory.doSpecialSpawn(living, living.field_70170_p,
                    (float) living.field_70165_t, (float) living.field_70163_u, (float) living.field_70161_v)) {
                living.func_110161_a(null); // onSpawnWithEgg
            }
        } catch (Throwable t) {
            System.out.println("[Retrofit] " + EntityList.func_75621_b(living) + " failed setting itself up for spawning: " + t);
        }
    }

    public static Object entityLoad(byte[] nbt, Object world) throws IOException {
        return EntityList.func_75615_a(fromBytes(nbt), (World) world); // createEntityFromNBT
    }

    /** writeToNBTOptional's content: the entity's data with its id. */
    public static byte[] entitySave(Object entity) throws IOException {
        NBTTagCompound tag = new NBTTagCompound();
        ((Entity) entity).func_70039_c(tag); // writeToNBTOptional (adds "id")
        return toBytes(tag);
    }

    /** Joins a legacy world's entity list (where legacy code looks for entities). */
    /**
     * The entity joins a legacy world, as World.spawnEntityInWorld did: EntityJoinWorldEvent first (mods
     * set entities up there); false if a mod cancelled it, and then the entity isn't spawned.
     */
    public static boolean entityJoin(Object entity, Object world) {
        Entity legacy = (Entity) entity;
        World target = (World) world;
        legacy.func_70029_a(target); // setWorld
        if (!target.field_72996_f.contains(legacy)) {
            try {
                if (net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.entity.EntityJoinWorldEvent(legacy, target))) {
                    return false;
                }
            } catch (Throwable t) {
                System.out.println("[Retrofit] An EntityJoinWorldEvent handler failed for " + legacy + ": " + t);
            }
            target.field_72996_f.add(legacy); // loadedEntityList
        }
        return true;
    }

    public static void entityLeave(Object entity) {
        Entity legacy = (Entity) entity;
        legacy.func_70106_y(); // setDead: mods watching entities themselves (boss bars, spawn caches) let go of a dead one
        if (legacy.field_70170_p != null) {
            legacy.field_70170_p.field_72996_f.remove(legacy); // loadedEntityList
        }
    }

    /**
     * One server tick, as World.updateEntity did, then the state the host copies:
     * {x, y, z, yaw, pitch, head yaw, body yaw, motion x, y, z, health, max health, width, height,
     * dead 1/0, on ground 1/0}.
     */
    public static double[] entityTick(Object entity) {
        Entity legacy = (Entity) entity;
        legacy.field_70142_S = legacy.field_70165_t; // lastTickPos
        legacy.field_70137_T = legacy.field_70163_u;
        legacy.field_70136_U = legacy.field_70161_v;
        legacy.field_70126_B = legacy.field_70177_z;
        legacy.field_70127_C = legacy.field_70125_A;
        legacy.field_70173_aa++; // ticksExisted
        boolean wasDead = legacy.field_70128_L;
        legacy.func_70071_h_(); // onUpdate
        if (!wasDead && legacy.field_70128_L && Boolean.getBoolean("retrofit.debugEntities")) {
            net.minecraft.entity.player.EntityPlayer closest = legacy.field_70170_p.func_72890_a(legacy, -1.0); // getClosestPlayerToEntity
            System.out.println("[Retrofit] " + legacy + " set itself dead: y " + legacy.field_70163_u + ", difficulty "
                    + legacy.field_70170_p.field_73013_u + ", closest player " + closest + ", players " + legacy.field_70170_p.field_73010_i.size());
        }
        return entityState(legacy);
    }

    /** Where riders sit, above the entity's feet: getMountedYOffset, which is from posY, yOffset above them. */
    public static double mountedOffset(Object entity) {
        Entity legacy = (Entity) entity;
        return legacy.func_70042_X() + legacy.field_70129_M;
    }

    public static double[] entityState(Object entity) {
        Entity legacy = (Entity) entity;
        boolean living = legacy instanceof EntityLivingBase;
        EntityLivingBase body = living ? (EntityLivingBase) legacy : null;
        return new double[]{
                legacy.field_70165_t, legacy.field_70163_u - legacy.field_70129_M, legacy.field_70161_v, // feet: posY - yOffset
                legacy.field_70177_z, legacy.field_70125_A,
                living ? body.field_70759_as : legacy.field_70177_z, living ? body.field_70761_aq : legacy.field_70177_z,
                legacy.field_70159_w, legacy.field_70181_x, legacy.field_70179_y,
                living ? body.func_110143_aJ() : 0, living ? body.func_110138_aP() : 0,
                legacy.field_70130_N, legacy.field_70131_O,
                legacy.field_70128_L ? 1 : 0, legacy.field_70122_E ? 1 : 0,
                mountedOffset(legacy),
        };
    }

    // ---- Riding ----

    private static java.lang.reflect.Field jumping;

    /** What an entity (or a player's mirror) rides, or null. */
    public static Object entityRiding(Object entity) {
        return ((Entity) entity).field_70154_o; // ridingEntity
    }

    /** What rides an entity, or null. */
    public static Object entityRider(Object entity) {
        return ((Entity) entity).field_70153_n; // riddenByEntity
    }

    /** Makes legacy riding match the host: the rider on {@code vehicle}, or off what it rides if null. */
    public static void setRiding(Object rider, Object vehicle) {
        Entity entity = (Entity) rider;
        if (entity.field_70154_o == vehicle) {
            return;
        }
        if (vehicle == null) {
            entity.func_70078_a(null); // mountEntity(null): dismount
        } else {
            entity.func_70078_a((Entity) vehicle);
        }
    }

    /** The rider's movement keys, which 1.7.10 vehicles steer by (moveForward, moveStrafing, isJumping). */
    public static void setRiderInput(Object rider, float forward, float strafe, boolean jump) throws ReflectiveOperationException {
        EntityLivingBase entity = (EntityLivingBase) rider;
        entity.field_70701_bs = forward; // moveForward
        entity.field_70702_br = strafe;  // moveStrafing
        if (jumping == null) {
            jumping = EntityLivingBase.class.getDeclaredField("field_70703_bu"); // isJumping
            jumping.setAccessible(true);
        }
        jumping.setBoolean(entity, jump);
    }

    /** Client: where the server's copy is now; the copy interpolates there and runs its client update (animations). */
    public static void entityClientTick(Object entity, double x, double y, double z, float yaw, float pitch, float headYaw) {
        Entity legacy = (Entity) entity;
        legacy.field_70142_S = legacy.field_70165_t;
        legacy.field_70137_T = legacy.field_70163_u;
        legacy.field_70136_U = legacy.field_70161_v;
        legacy.field_70173_aa++;
        legacy.func_70056_a(x, y + legacy.field_70129_M, z, yaw, pitch, 3); // setPositionAndRotation2
        if (legacy instanceof EntityLivingBase) {
            legacy.func_70034_d(headYaw); // setRotationYawHead
        }
        legacy.func_70071_h_(); // onUpdate
    }

    /** Client: put the copy exactly somewhere (spawn). */
    public static void entityPlace(Object entity, double x, double y, double z, float yaw, float pitch) {
        Entity legacy = (Entity) entity;
        legacy.func_70080_a(x, y + legacy.field_70129_M, z, yaw, pitch); // setPositionAndRotation
        legacy.field_70169_q = legacy.field_70142_S = legacy.field_70165_t;
        legacy.field_70167_r = legacy.field_70137_T = legacy.field_70163_u;
        legacy.field_70166_s = legacy.field_70136_U = legacy.field_70161_v;
    }

    /** getCreatureAttribute: 0 undefined, 1 undead, 2 arthropod. */
    public static int entityCreatureAttribute(Object entity) {
        if (!(entity instanceof net.minecraft.entity.EntityLivingBase)) {
            return 0;
        }
        net.minecraft.entity.EnumCreatureAttribute attribute = ((net.minecraft.entity.EntityLivingBase) entity).func_70668_bt();
        return attribute == net.minecraft.entity.EnumCreatureAttribute.UNDEAD ? 1 : attribute == net.minecraft.entity.EnumCreatureAttribute.ARTHROPOD ? 2 : 0;
    }

    public static int entityId(Object entity) {
        return ((Entity) entity).func_145782_y();
    }

    /** Clients use the server's legacy id, as 1.7.10 did: mods send entity ids in their packets. */
    public static void entitySetId(Object entity, int id) {
        ((Entity) entity).func_145769_d(id);
    }

    // ---- Sync ----

    /** Equipment last sent to clients, per entity (1.7.10's EntityTracker sent changes as S04PacketEntityEquipment). */
    private static final java.util.Map<Entity, net.minecraft.item.ItemStack[]> SENT_EQUIPMENT =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Entity, net.minecraft.item.ItemStack[]>());

    /**
     * Sync data for clients: the DataWatcher entries (changed ones, or all), the equipment slots
     * that changed (held item and armor: mobs holding swords, wearing pumpkins), and the watchable
     * attributes that changed (1.7.10 sent those as S20PacketEntityProperties: mods keep boss
     * health and the like in custom attributes marked with setShouldWatch(true) and read them on
     * the client, so without them a client copy's attributes stay at their constructor values);
     * null if nothing changed.
     */
    private static byte[] syncData(Entity entity, boolean all) throws IOException {
        List<?> entries = all ? entity.func_70096_w().func_75685_c() : entity.func_70096_w().func_75688_b(); // getAllWatched, getChanged
        int changedSlots = 0;
        net.minecraft.item.ItemStack[] now = null;
        java.util.Collection<?> attributeInstances = null;
        boolean clearAttributes = false;
        if (entity instanceof net.minecraft.entity.EntityLivingBase) {
            net.minecraft.item.ItemStack[] sent = SENT_EQUIPMENT.get(entity);
            now = new net.minecraft.item.ItemStack[5];
            for (int slot = 0; slot < 5; slot++) {
                net.minecraft.item.ItemStack stack = ((net.minecraft.entity.EntityLivingBase) entity).func_71124_b(slot); // getEquipmentInSlot
                now[slot] = stack == null ? null : stack.func_77946_l(); // copy
                if (all ? stack != null : !net.minecraft.item.ItemStack.func_77989_b(stack, sent == null ? null : sent[slot])) { // areItemStacksEqual
                    changedSlots |= 1 << slot;
                }
            }
            SENT_EQUIPMENT.put(entity, now);
            net.minecraft.entity.ai.attributes.BaseAttributeMap attributes = ((net.minecraft.entity.EntityLivingBase) entity).func_110140_aT(); // getAttributeMap
            if (all) {
                attributeInstances = attributes.func_111146_a(); // getAllAttributes (the watchable ones are picked when writing)
            } else if (attributes instanceof net.minecraft.entity.ai.attributes.ServersideAttributeMap) {
                // The instances that changed since the last send, as 1.7.10's EntityTrackerEntry read them.
                attributeInstances = ((net.minecraft.entity.ai.attributes.ServersideAttributeMap) attributes).func_111161_b(); // getAttributeInstanceSet
                clearAttributes = true;
            }
        }
        if ((entries == null || entries.isEmpty()) && changedSlots == 0
                && (attributeInstances == null || attributeInstances.isEmpty())) {
            return null;
        }
        ByteBuf buffer = Unpooled.buffer();
        PacketBuffer packet = new PacketBuffer(buffer);
        packet.writeBoolean(entries != null && !entries.isEmpty());
        if (entries != null && !entries.isEmpty()) {
            net.minecraft.entity.DataWatcher.func_151507_a(entries, packet); // writeWatchedListToPacketBuffer
        }
        packet.writeByte(changedSlots);
        for (int slot = 0; slot < 5; slot++) {
            if ((changedSlots & 1 << slot) != 0) {
                packet.func_150788_a(now[slot]); // writeItemStackToBuffer
            }
        }
        writeAttributes(packet, attributeInstances);
        if (clearAttributes && attributeInstances != null) {
            attributeInstances.clear(); // the set is the live one; 1.7.10's EntityTrackerEntry cleared it after sending
        }
        return bytes(buffer);
    }

    /** Watchable attribute base values and modifiers, in S20PacketEntityProperties' shape. */
    private static void writeAttributes(PacketBuffer packet, java.util.Collection<?> instances) throws IOException {
        int count = 0;
        if (instances != null) {
            for (Object object : instances) {
                net.minecraft.entity.ai.attributes.IAttributeInstance instance = (net.minecraft.entity.ai.attributes.IAttributeInstance) object;
                if (instance.func_111123_a().func_111111_c()) { // getAttribute().getShouldWatch()
                    count++;
                }
            }
        }
        packet.writeInt(count);
        if (instances == null) {
            return;
        }
        for (Object object : instances) {
            net.minecraft.entity.ai.attributes.IAttributeInstance instance = (net.minecraft.entity.ai.attributes.IAttributeInstance) object;
            if (!instance.func_111123_a().func_111111_c()) { // not one the client should follow
                continue;
            }
            packet.func_150785_a(instance.func_111123_a().func_111108_a()); // getAttribute().getAttributeUnlocalizedName()
            packet.writeDouble(instance.func_111125_b()); // getBaseValue
            java.util.Collection<?> modifiers = instance.func_111122_c(); // getModifiers
            packet.writeShort(modifiers.size());
            for (Object object2 : modifiers) {
                net.minecraft.entity.ai.attributes.AttributeModifier modifier = (net.minecraft.entity.ai.attributes.AttributeModifier) object2;
                packet.writeLong(modifier.func_111167_a().getMostSignificantBits()); // getID
                packet.writeLong(modifier.func_111167_a().getLeastSignificantBits());
                packet.writeDouble(modifier.func_111164_d()); // getAmount
                packet.writeByte(modifier.func_111169_c()); // getOperation
            }
        }
    }

    /** DataWatcher entries, equipment and watchable attributes changed since the last call; null if none. */
    public static byte[] entityWatcherChanges(Object entity) throws IOException {
        return syncData((Entity) entity, false);
    }

    /** All DataWatcher entries, equipment and watchable attributes (for spawning on a client). */
    public static byte[] entityWatcherAll(Object entity) throws IOException {
        return syncData((Entity) entity, true);
    }

    /** Client */
    @SuppressWarnings("rawtypes")
    public static void entityApplyWatcher(Object entity, byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            return;
        }
        PacketBuffer packet = new PacketBuffer(Unpooled.wrappedBuffer(data));
        if (packet.readBoolean()) {
            List entries = net.minecraft.entity.DataWatcher.func_151508_b(packet); // readWatchedListFromPacketBuffer
            if (entries != null) {
                ((Entity) entity).func_70096_w().func_75687_a(entries); // updateWatchedObjectsFromList
            }
        }
        int changedSlots = packet.readByte();
        for (int slot = 0; slot < 5; slot++) {
            if ((changedSlots & 1 << slot) != 0) {
                net.minecraft.item.ItemStack stack = packet.func_150791_c(); // readItemStackFromBuffer
                ((Entity) entity).func_70062_b(slot, stack); // setCurrentItemOrArmor
            }
        }
        int attributeCount = packet.readInt();
        if (attributeCount > 0 && entity instanceof net.minecraft.entity.EntityLivingBase) {
            net.minecraft.entity.ai.attributes.BaseAttributeMap attributes = ((net.minecraft.entity.EntityLivingBase) entity).func_110140_aT(); // getAttributeMap
            for (int i = 0; i < attributeCount; i++) {
                String name = packet.func_150789_c(64); // readStringFromBuffer
                double value = packet.readDouble();
                int modifierCount = packet.readShort();
                java.util.UUID[] ids = new java.util.UUID[modifierCount];
                double[] amounts = new double[modifierCount];
                int[] operations = new int[modifierCount];
                for (int m = 0; m < modifierCount; m++) {
                    ids[m] = new java.util.UUID(packet.readLong(), packet.readLong());
                    amounts[m] = packet.readDouble();
                    operations[m] = packet.readByte();
                }
                net.minecraft.entity.ai.attributes.IAttributeInstance instance = attributes.func_111152_a(name); // getAttributeInstanceByName
                if (instance == null) {
                    continue; // not registered on the client
                }
                instance.func_142049_d(); // removeAllModifiers
                instance.func_111128_a(value); // setBaseValue
                for (int m = 0; m < modifierCount; m++) {
                    instance.func_111121_a(new net.minecraft.entity.ai.attributes.AttributeModifier(ids[m], "retrofit.sync", amounts[m], operations[m])); // applyModifier
                }
            }
        }
    }

    /** IEntityAdditionalSpawnData.writeSpawnData, or null. */
    public static byte[] entitySpawnData(Object entity) {
        if (!(entity instanceof IEntityAdditionalSpawnData)) {
            return null;
        }
        ByteBuf buffer = Unpooled.buffer();
        ((IEntityAdditionalSpawnData) entity).writeSpawnData(buffer);
        return bytes(buffer);
    }

    public static void entityApplySpawnData(Object entity, byte[] data) {
        if (entity instanceof IEntityAdditionalSpawnData && data != null) {
            ((IEntityAdditionalSpawnData) entity).readSpawnData(Unpooled.wrappedBuffer(data));
        }
    }

    /** Client: World.setEntityState's effect (hurt flash, death, taming hearts...). */
    public static void entityEvent(Object entity, int event) {
        ((Entity) entity).func_70103_a((byte) event); // handleHealthUpdate
    }

    // ---- Interaction ----

    /**
     * Damage from the host: kind is the 1.7.10 damage type ("player", "mob", "arrow", "fall",
     * "inFire", "lava", "explosion", "magic", "generic"...), attacker a legacy entity or mirror (or null).
     */
    public static boolean entityHurt(Object entity, String kind, Object attacker, float amount) {
        DamageSource source;
        if (attacker instanceof EntityPlayer) {
            source = DamageSource.func_76365_a((EntityPlayer) attacker); // causePlayerDamage
        } else if (attacker instanceof EntityLivingBase) {
            source = DamageSource.func_76358_a((EntityLivingBase) attacker); // causeMobDamage
        } else {
            source = sourceOf(kind);
        }
        return ((Entity) entity).func_70097_a(source, amount); // attackEntityFrom
    }

    static DamageSource sourceOf(String kind) {
        if ("inFire".equals(kind)) {
            return DamageSource.field_76372_a;
        } else if ("onFire".equals(kind)) {
            return DamageSource.field_76370_b;
        } else if ("lava".equals(kind)) {
            return DamageSource.field_76371_c;
        } else if ("inWall".equals(kind)) {
            return DamageSource.field_76368_d;
        } else if ("drown".equals(kind)) {
            return DamageSource.field_76369_e;
        } else if ("cactus".equals(kind)) {
            return DamageSource.field_76367_g;
        } else if ("fall".equals(kind)) {
            return DamageSource.field_76379_h;
        } else if ("outOfWorld".equals(kind)) {
            return DamageSource.field_76380_i;
        } else if ("magic".equals(kind)) {
            return DamageSource.field_76376_m;
        } else if ("wither".equals(kind)) {
            return DamageSource.field_82727_n;
        }
        return DamageSource.field_76377_j; // generic
    }

    /** {can be leashed (allowLeashing), leashed, leashed to a player} of a legacy creature. */
    public static boolean[] leashState(Object entity) {
        if (!(entity instanceof net.minecraft.entity.EntityLiving)) {
            return new boolean[3];
        }
        net.minecraft.entity.EntityLiving living = (net.minecraft.entity.EntityLiving) entity;
        return new boolean[]{living.func_110164_bC(), living.func_110167_bD(), living.func_110166_bE() instanceof EntityPlayer}; // allowLeashing, getLeashed, getLeashedToEntity
    }

    /** Development: legacy code throwing a splash potion (as Witchery's witches do); whether the world took it. */
    public static boolean testThrowPotion(Object world, double x, double y, double z, int damage) {
        net.minecraft.entity.projectile.EntityPotion potion = new net.minecraft.entity.projectile.EntityPotion((net.minecraft.world.World) world, x, y, z, damage);
        return ((net.minecraft.world.World) world).func_72838_d(potion); // spawnEntityInWorld
    }

    /** What a legacy creature is leashed to (getLeashedToEntity), or null. */
    public static Object leashHolder(Object entity) {
        return entity instanceof net.minecraft.entity.EntityLiving ? ((net.minecraft.entity.EntityLiving) entity).func_110166_bE() : null;
    }

    /** Lets a legacy creature go, as 1.7.10 did when its holder right-clicked it (clearLeashed: tells clients, maybe drops a lead). */
    public static void clearLeash(Object entity, boolean dropLead) {
        if (entity instanceof net.minecraft.entity.EntityLiving && ((net.minecraft.entity.EntityLiving) entity).func_110167_bD()) {
            ((net.minecraft.entity.EntityLiving) entity).func_110160_i(true, dropLead);
        }
    }

    /** interactFirst (right-click on the entity) */
    public static boolean entityInteract(Object entity, Object player) {
        return ((Entity) entity).func_130002_c((EntityPlayer) player);
    }

    /** getCanSpawnHere, for natural spawning (a temporary entity at the spot). */
    public static boolean entityCanSpawnHere(String name, Object world, double x, double y, double z) {
        Entity entity = EntityList.func_75620_a(name, (World) world);
        if (!(entity instanceof EntityLiving)) {
            return entity != null;
        }
        entity.func_70012_b(x, y, z, 0, 0);
        return ((EntityLiving) entity).func_70601_bi();
    }

    /**
     * Natural spawns of mod entities for a 1.20.1 biome id, from the closest 1.7.10 biome's spawn
     * lists (where mods add them with EntityRegistry.addSpawn): {name, category, weight, min, max}.
     * Categories are 1.7.10's: 0 monster, 1 creature, 2 ambient, 3 water creature.
     */
    @SuppressWarnings("unchecked")
    public static Object[][] entitySpawns(String modernBiome) {
        List<Object[]> spawns = new ArrayList<Object[]>();
        String path = modernBiome.substring(modernBiome.indexOf(':') + 1);
        if (!modernBiome.startsWith("minecraft:") && !path.isEmpty()) {
            return new Object[0][]; // biomes of 1.20.1 mods have no 1.7.10 counterpart
        }
        BiomeGenBase match = WorldGlue.biomeFor(modernBiome);
        for (BiomeGenBase biome : new BiomeGenBase[]{match}) {
            if (biome == null) {
                continue;
            }
            for (EnumCreatureType type : EnumCreatureType.values()) {
                for (Object element : biome.func_76747_a(type)) { // getSpawnableList
                    BiomeGenBase.SpawnListEntry entry = (BiomeGenBase.SpawnListEntry) element;
                    String name = (String) ((Map<Class<?>, String>) EntityList.field_75626_c).get(entry.field_76300_b); // classToStringMapping
                    if (name != null && !vanillaEntityClass(entry.field_76300_b)) {
                        spawns.add(new Object[]{name, type.ordinal(), entry.field_76292_a, entry.field_76301_c, entry.field_76299_d});
                    }
                }
            }
        }
        return spawns.toArray(new Object[0][]);
    }

    /** 1.7.10 biome names, for mapping 1.20.1 biomes to legacy spawn lists. */
    public static String[] biomeNames() {
        List<String> names = new ArrayList<String>();
        for (BiomeGenBase biome : BiomeGenBase.func_150565_n()) {
            if (biome != null) {
                names.add(biome.field_76791_y);
            }
        }
        return names.toArray(new String[0]);
    }

    // ---- Vanilla entities spawned by legacy code ----

    /** The legacy save data of a vanilla entity legacy code spawned, for the host to convert ({id, ...}). */
    public static byte[] vanillaData(Object entity) throws IOException {
        Entity legacy = (Entity) entity;
        NBTTagCompound tag = new NBTTagCompound();
        if (!legacy.func_70039_c(tag)) { // writeToNBTOptional: false for entities that are never saved (lightning, players)
            String name = EntityList.func_75621_b(legacy);
            if (name == null) {
                return null;
            }
            legacy.func_70109_d(tag);
            tag.func_74778_a("id", name); // setString
        }
        return toBytes(tag);
    }

    /** The UUID of the player who shot or threw a vanilla projectile, or null. */
    static String ownerUuid(Object entity) {
        Entity owner = null;
        if (entity instanceof net.minecraft.entity.projectile.EntityArrow) {
            owner = ((net.minecraft.entity.projectile.EntityArrow) entity).field_70250_c; // shootingEntity
        } else if (entity instanceof net.minecraft.entity.projectile.EntityThrowable) {
            owner = ((net.minecraft.entity.projectile.EntityThrowable) entity).func_85052_h(); // getThrower
        } else if (entity instanceof net.minecraft.entity.projectile.EntityFireball) {
            owner = ((net.minecraft.entity.projectile.EntityFireball) entity).field_70235_a; // shootingEntity
        }
        return owner instanceof EntityPlayer ? owner.func_110124_au().toString() : null; // getUniqueID
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] data = new byte[buffer.readableBytes()];
        buffer.readBytes(data);
        return data;
    }

    private static byte[] toBytes(NBTTagCompound tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompressedStreamTools.func_74800_a(tag, new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    private static NBTTagCompound fromBytes(byte[] bytes) throws IOException {
        return CompressedStreamTools.func_74794_a(new DataInputStream(new ByteArrayInputStream(bytes)));
    }
}
