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

    // ---- Types ----

    /**
     * Mod entity types: {name (as registered: "modid.Name" or a global name), living 1/0, category
     * (0 monster, 1 creature, 2 water, 3 ambient, 4 misc), width, height, egg background, egg
     * foreground (-1: none), mod id}.
     */
    @SuppressWarnings("unchecked")
    public static Object[][] modEntities() {
        List<Object[]> types = new ArrayList<Object[]>();
        // classToStringMapping: one name per class (mods often register a class under two names).
        for (Map.Entry<Class<?>, String> entry : ((Map<Class<?>, String>) EntityList.field_75626_c).entrySet()) {
            Class<?> type = entry.getKey();
            if (type.getName().startsWith("net.minecraft.")) {
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
                    egg[0], egg[1], modId(type)});
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
        return name == null || entity.getClass().getName().startsWith("net.minecraft.") ? null : name;
    }

    // ---- Life ----

    public static Object entityCreate(String name, Object world, double x, double y, double z) {
        Entity entity = EntityList.func_75620_a(name, (World) world); // createEntityByName
        if (entity != null) {
            entity.func_70012_b(x, y, z, 0, 0); // setLocationAndAngles
        }
        return entity;
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
    public static void entityJoin(Object entity, Object world) {
        Entity legacy = (Entity) entity;
        World target = (World) world;
        legacy.func_70029_a(target); // setWorld
        if (!target.field_72996_f.contains(legacy)) {
            target.field_72996_f.add(legacy); // loadedEntityList
        }
    }

    public static void entityLeave(Object entity) {
        Entity legacy = (Entity) entity;
        legacy.field_70170_p.field_72996_f.remove(legacy);
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
        };
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

    public static int entityId(Object entity) {
        return ((Entity) entity).func_145782_y();
    }

    /** Clients use the server's legacy id, as 1.7.10 did: mods send entity ids in their packets. */
    public static void entitySetId(Object entity, int id) {
        ((Entity) entity).func_145769_d(id);
    }

    // ---- Sync ----

    /** DataWatcher entries changed since the last call, as 1.7.10 sent them; null if none. */
    public static byte[] entityWatcherChanges(Object entity) throws IOException {
        List<?> changed = ((Entity) entity).func_70096_w().func_75688_b(); // getChanged
        if (changed == null || changed.isEmpty()) {
            return null;
        }
        ByteBuf buffer = Unpooled.buffer();
        net.minecraft.entity.DataWatcher.func_151507_a(changed, new PacketBuffer(buffer)); // writeWatchedListToPacketBuffer
        return bytes(buffer);
    }

    /** All DataWatcher entries (for spawning on a client). */
    public static byte[] entityWatcherAll(Object entity) throws IOException {
        ByteBuf buffer = Unpooled.buffer();
        ((Entity) entity).func_70096_w().func_151509_a(new PacketBuffer(buffer)); // writeTo
        return bytes(buffer);
    }

    /** Client */
    @SuppressWarnings("rawtypes")
    public static void entityApplyWatcher(Object entity, byte[] data) throws IOException {
        List entries = net.minecraft.entity.DataWatcher.func_151508_b(new PacketBuffer(Unpooled.wrappedBuffer(data))); // readWatchedListFromPacketBuffer
        if (entries != null) {
            ((Entity) entity).func_70096_w().func_75687_a(entries); // updateWatchedObjectsFromList
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
                    if (name != null && !entry.field_76300_b.getName().startsWith("net.minecraft.")) {
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
