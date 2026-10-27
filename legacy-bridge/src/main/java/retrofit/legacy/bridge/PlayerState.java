package retrofit.legacy.bridge;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraftforge.common.IExtendedEntityProperties;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * What legacy mods keep on a player beyond what the host owns: Forge's extended entity properties
 * (Thaumcraft's research and warp, Ars Magica 2's mana and skills, Witchery's curses and
 * transformations), the Forge entity data tag, and potion effects of mod potions.
 *
 * Mirrors are per legacy world and come and go (dimension changes, logouts), so this state lives
 * on through them: it moves to the player's next mirror, and the host saves it with the player
 * ({@link #playerData}, {@link #loadPlayerData}).
 *
 * Potion effects: vanilla effects (ids 1-23, the same in both versions) belong to the host player.
 * The mirror is given the host's ({@link #setHostEffects}) so mods see them, and effects legacy code
 * adds or renews are handed to the host ({@link #takeNewEffects}). Mod potions stay on the mirror,
 * which is ticked with the player ({@link #tickServerPlayer}), so they work and run out as in 1.7.10.
 */
public final class PlayerState {
    private static final int LAST_VANILLA_POTION = 23;
    /** Saved state by player UUID, for players without a mirror (or between mirrors). */
    private static final Map<String, NBTTagCompound> SAVED = new HashMap<String, NBTTagCompound>();
    /** Vanilla effects last given to a mirror by the host: id -> {duration, amplifier}. */
    private static final Map<EntityPlayer, Map<Integer, int[]>> HOST_EFFECTS = new WeakHashMap<EntityPlayer, Map<Integer, int[]>>();
    private static Field extendedProperties;
    private static Field customEntityData;
    private static Field activePotions;
    private static Method updatePotionEffects;
    private static Field badEffect;
    private static boolean livingUpdateChecked;
    private static boolean livingUpdateListened;

    private PlayerState() {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, IExtendedEntityProperties> properties(EntityPlayer player) throws ReflectiveOperationException {
        if (extendedProperties == null) {
            extendedProperties = net.minecraft.entity.Entity.class.getDeclaredField("extendedProperties");
            extendedProperties.setAccessible(true);
            customEntityData = net.minecraft.entity.Entity.class.getDeclaredField("customEntityData");
            customEntityData.setAccessible(true);
        }
        Map<String, IExtendedEntityProperties> map = (Map<String, IExtendedEntityProperties>) extendedProperties.get(player);
        return map != null ? map : new HashMap<String, IExtendedEntityProperties>();
    }

    // ---- Saving and restoring ----

    /** The mods' state of a mirror: each extended property's data, the Forge entity data, mod potion effects. */
    static NBTTagCompound save(EntityPlayer player) {
        NBTTagCompound tag = new NBTTagCompound();
        try {
            for (Map.Entry<String, IExtendedEntityProperties> property : properties(player).entrySet()) {
                try {
                    property.getValue().saveNBTData(tag);
                } catch (Throwable t) {
                    System.out.println("[Retrofit] Extended player property " + property.getKey() + " could not be saved: " + t);
                }
            }
            tag.func_74782_a("ForgeData", player.getEntityData().func_74737_b()); // setTag, copy
        } catch (ReflectiveOperationException e) {
            System.out.println("[Retrofit] Legacy player state could not be saved: " + e);
        }
        NBTTagList effects = new NBTTagList();
        for (Object element : player.func_70651_bq()) { // getActivePotionEffects
            PotionEffect effect = (PotionEffect) element;
            if (effect.func_76456_a() > LAST_VANILLA_POTION) { // getPotionID: vanilla effects are saved with the host player
                effects.func_74742_a(effect.func_82719_a(new NBTTagCompound())); // appendTag, writeCustomPotionEffectToNBT
            }
        }
        tag.func_74782_a("RetrofitModEffects", effects);
        // timeUntilPortal: mods' portals set it before sending a player and send none while it lasts; the player
        // arrives standing in the other side's portal, and a mirror without it was sent straight back (Atum, AoA)
        tag.func_74768_a("RetrofitPortalCooldown", player.field_71088_bW); // setInteger
        return tag;
    }

    /** Gives a (new) mirror the mods' state saved earlier. */
    static void load(EntityPlayer player, NBTTagCompound tag) {
        try {
            for (Map.Entry<String, IExtendedEntityProperties> property : properties(player).entrySet()) {
                try {
                    property.getValue().loadNBTData(tag);
                } catch (Throwable t) {
                    System.out.println("[Retrofit] Extended player property " + property.getKey() + " could not be loaded: " + t);
                }
            }
            if (tag.func_74764_b("ForgeData")) { // hasKey
                customEntityData.set(player, tag.func_74775_l("ForgeData")); // getCompoundTag
            }
        } catch (ReflectiveOperationException e) {
            System.out.println("[Retrofit] Legacy player state could not be loaded: " + e);
        }
        NBTTagList effects = tag.func_150295_c("RetrofitModEffects", 10); // getTagList(name, compound)
        for (int i = 0; i < effects.func_74745_c(); i++) { // tagCount
            PotionEffect effect = PotionEffect.func_82722_b(effects.func_150305_b(i)); // readCustomPotionEffectFromNBT, getCompoundTagAt
            if (effect != null && !player.func_82165_m(effect.func_76456_a())) { // isPotionActive
                player.func_70690_d(effect); // addPotionEffect
            }
        }
        player.field_71088_bW = Math.max(player.field_71088_bW, tag.func_74762_e("RetrofitPortalCooldown")); // timeUntilPortal, getInteger
    }

    /** A new server mirror: the state saved for its player, if any. */
    static void restore(String uuid, EntityPlayer mirror) {
        NBTTagCompound saved;
        synchronized (SAVED) {
            saved = SAVED.get(uuid);
        }
        if (saved != null) {
            load(mirror, saved);
        }
    }

    /** A server mirror goes away: keep its state for the next one. */
    static void keep(String uuid, EntityPlayer mirror) {
        NBTTagCompound state = save(mirror);
        synchronized (SAVED) {
            SAVED.put(uuid, state);
        }
    }

    /** Where mods keep their own per-player files (PlayerEvent.LoadFromFile/SaveToFile: Baubles' .baub files). */
    private static java.io.File playerDirectory() {
        java.io.File directory = new java.io.File(LegacyWorlds.saveDirectory(), "playerdata");
        directory.mkdirs();
        return directory;
    }

    /**
     * A player's server mirror changes (login, another dimension, logout): mods save their per-player
     * data from the old one (PlayerEvent.SaveToFile) and load it into the new one (LoadFromFile), as
     * 1.7.10 does when it saves and loads the player. Either may be null.
     */
    static void handOver(EntityPlayer from, EntityPlayer to, String uuid) {
        if (from != null) {
            fileEvent(from, uuid, false);
        }
        if (to != null) {
            fileEvent(to, uuid, true);
        }
    }

    private static void fileEvent(EntityPlayer player, String uuid, boolean load) {
        try {
            if (load) {
                net.minecraftforge.event.ForgeEventFactory.firePlayerLoadingEvent(player, playerDirectory(), uuid);
            } else {
                net.minecraftforge.event.ForgeEventFactory.firePlayerSavingEvent(player, playerDirectory(), uuid);
            }
        } catch (Throwable t) {
            System.out.println("[Retrofit] A mod failed " + (load ? "loading" : "saving") + " its data for player " + uuid + ": " + t);
        }
    }

    /** The player's legacy state, for the host to save with the player (null if there is none). */
    public static byte[] playerData(Object legacyPlayer) throws Exception {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        fileEvent(player, player.func_110124_au().toString(), false); // getUniqueID: mods' own player files too
        NBTTagCompound state = save(player);
        synchronized (SAVED) {
            SAVED.put(player.func_110124_au().toString(), state); // getUniqueID
        }
        return Bridge.toBytes(state);
    }

    /** State the host saved with a player (login): used by the player's mirrors from now on. */
    public static void loadPlayerData(String uuid, byte[] data) throws Exception {
        NBTTagCompound state = Bridge.fromBytes(data);
        synchronized (SAVED) {
            SAVED.put(uuid, state);
        }
        for (EntityPlayer mirror : LegacyPlayers.serverMirrors(uuid)) {
            load(mirror, state);
        }
    }

    /** The player left the server: nothing of theirs stays in memory. */
    public static void forgetPlayer(String uuid) {
        synchronized (SAVED) {
            SAVED.remove(uuid);
        }
    }

    // ---- Potion effects ----

    /** The host player's vanilla effects, as {id, duration, amplifier, ambient (0/1)} quadruples. */
    public static void setHostEffects(Object legacyPlayer, int[] effects) {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        Map<Integer, int[]> given = HOST_EFFECTS.get(player);
        if (given == null) {
            given = new HashMap<Integer, int[]>();
            HOST_EFFECTS.put(player, given);
        }
        Set<Integer> now = new HashSet<Integer>();
        for (int i = 0; i + 3 < effects.length; i += 4) {
            int id = effects[i];
            now.add(id);
            PotionEffect current = player.func_70660_b(Potion.field_76425_a[id]); // getActivePotionEffect
            if (current == null || current.func_76458_c() != effects[i + 2] || Math.abs(current.func_76459_b() - effects[i + 1]) > 1) {
                if (current != null) {
                    removeQuietly(player, id);
                }
                player.func_70690_d(new PotionEffect(id, effects[i + 1], effects[i + 2], effects[i + 3] != 0));
            }
            given.put(id, new int[]{effects[i + 1], effects[i + 2]});
        }
        for (Integer id : new ArrayList<Integer>(given.keySet())) {
            if (!now.contains(id)) {
                given.remove(id);
                removeQuietly(player, id); // ended or removed on the host
            }
        }
    }

    /** Vanilla effects legacy code added or renewed on the mirror since the host's were given, for the host to apply. */
    public static int[] takeNewEffects(Object legacyPlayer) {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        Map<Integer, int[]> given = HOST_EFFECTS.get(player);
        List<Integer> result = new ArrayList<Integer>();
        for (Object element : player.func_70651_bq()) {
            PotionEffect effect = (PotionEffect) element;
            int id = effect.func_76456_a();
            if (id < 1 || id > LAST_VANILLA_POTION) {
                continue;
            }
            int[] host = given == null ? null : given.get(id);
            if (host == null || effect.func_76458_c() != host[1] || effect.func_76459_b() > host[0] + 1) {
                result.add(id);
                result.add(effect.func_76459_b());
                result.add(effect.func_76458_c());
                result.add(effect.func_82720_e() ? 1 : 0); // getIsAmbient
                if (given != null) {
                    given.put(id, new int[]{effect.func_76459_b(), effect.func_76458_c()});
                }
            }
        }
        int[] array = new int[result.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = result.get(i);
        }
        return array;
    }

    /** Mod potion effects on the mirror: {id, duration, amplifier} triples (the host shows them). */
    public static int[] modEffects(Object legacyPlayer) {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        List<Integer> result = new ArrayList<Integer>();
        for (Object element : player.func_70651_bq()) {
            PotionEffect effect = (PotionEffect) element;
            if (effect.func_76456_a() > LAST_VANILLA_POTION) {
                result.add(effect.func_76456_a());
                result.add(effect.func_76459_b());
                result.add(effect.func_76458_c());
            }
        }
        int[] array = new int[result.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = result.get(i);
        }
        return array;
    }

    /**
     * The attribute modifiers mod potions give (Potion.registerPotionAttributeModifier: speed, health,
     * damage...), for the host player: {attribute name, modifier UUID, amount, operation} each.
     */
    @SuppressWarnings("rawtypes")
    public static Object[][] modAttributeModifiers(Object legacyPlayer) {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        List<Object[]> result = new ArrayList<Object[]>();
        for (Object element : player.func_70651_bq()) {
            PotionEffect effect = (PotionEffect) element;
            if (effect.func_76456_a() <= LAST_VANILLA_POTION) {
                continue;
            }
            Potion potion = Potion.field_76425_a[effect.func_76456_a()];
            Map modifiers = potion == null ? null : attributeModifiers(potion);
            if (modifiers == null) {
                continue;
            }
            for (Object entry : modifiers.entrySet()) {
                Map.Entry e = (Map.Entry) entry;
                net.minecraft.entity.ai.attributes.AttributeModifier modifier = (net.minecraft.entity.ai.attributes.AttributeModifier) e.getValue();
                result.add(new Object[]{((net.minecraft.entity.ai.attributes.IAttribute) e.getKey()).func_111108_a(), // getAttributeUnlocalizedName
                        modifier.func_111167_a().toString(), potion.func_111183_a(effect.func_76458_c(), modifier), modifier.func_111169_c()});
            }
        }
        return result.toArray(new Object[0][]);
    }

    private static java.lang.reflect.Field attributeModifierMap;

    /** Potion.attributeModifierMap: the getter (func_111186_k) is client-only in 1.7.10. */
    @SuppressWarnings("rawtypes")
    private static Map attributeModifiers(Potion potion) {
        try {
            if (attributeModifierMap == null) {
                java.lang.reflect.Field field = Potion.class.getDeclaredField("field_111188_I");
                field.setAccessible(true);
                attributeModifierMap = field;
            }
            return (Map) attributeModifierMap.get(potion);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** A mod potion effect was removed on the host (milk, commands): the mirror loses it too. */
    public static void removeModEffect(Object legacyPlayer, int id) {
        ((EntityPlayer) legacyPlayer).func_82170_o(id); // removePotionEffect
    }

    @SuppressWarnings("rawtypes")
    private static void removeQuietly(EntityPlayer player, int id) {
        try {
            if (activePotions == null) {
                activePotions = EntityLivingBase.class.getDeclaredField("field_70713_bf"); // activePotionsMap
                activePotions.setAccessible(true);
            }
            ((Map) activePotions.get(player)).remove(id); // no onFinishedPotionEffect: the host owns the effect
        } catch (ReflectiveOperationException e) {
            player.func_82170_o(id); // removePotionEffect
        }
    }

    /**
     * One server tick of the player's legacy side: mod potions tick (and wear off), and mods'
     * LivingUpdateEvent handlers run for the player, as EntityLivingBase.onUpdate did.
     */
    public static void tickServerPlayer(Object legacyPlayer) throws ReflectiveOperationException {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        if (updatePotionEffects == null) {
            updatePotionEffects = EntityLivingBase.class.getDeclaredMethod("func_70679_bo");
            updatePotionEffects.setAccessible(true);
        }
        updatePotionEffects.invoke(player);
        fireLivingUpdate(player);
    }

    /** LivingUpdateEvent for the local client player (1.7.10 fired it on both sides). */
    public static void tickClientPlayer(Object legacyPlayer) {
        ClientPlayers.tickArm((EntityPlayer) legacyPlayer);
        fireLivingUpdate((EntityPlayer) legacyPlayer);
    }

    private static void fireLivingUpdate(EntityPlayer player) {
        if (!livingUpdateChecked) {
            livingUpdateChecked = true;
            livingUpdateListened = Events.listenedTo(LivingEvent.LivingUpdateEvent.class);
        }
        if (livingUpdateListened) {
            MinecraftForge.EVENT_BUS.post(new LivingEvent.LivingUpdateEvent(player));
        }
    }

    /** Mod potions, for the host's registry: {id, name key, colour, harmful (0/1)} per potion. */
    public static Object[][] modPotions() {
        List<Object[]> result = new ArrayList<Object[]>();
        java.util.Set<Object> held = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
        for (Potion potion : Potion.field_76425_a) {
            if (potion != null && potion.field_76415_H > LAST_VANILLA_POTION) { // id
                held.add(potion);
                result.add(new Object[]{potion.field_76415_H, potion.func_76393_a(), potion.func_76401_j(), harmful(potion) ? 1 : 0,
                        potion.getClass().getName()});
            }
        }
        // those that lost their slot once the ids ran out (id -1): which ones do depends on the order mods made them,
        // which differs between client and server, so both register them all (see LegacyIds.potionsMade)
        for (Object made : retrofit.legacy.boot.LegacyIds.potionsMade()) {
            Potion potion = (Potion) made;
            if (potion.field_76415_H > LAST_VANILLA_POTION && !held.contains(potion)) {
                held.add(potion);
                result.add(new Object[]{-1, potion.func_76393_a(), potion.func_76401_j(), harmful(potion) ? 1 : 0, potion.getClass().getName()});
            }
        }
        return result.toArray(new Object[0][]);
    }

    private static boolean harmful(Potion potion) {
        try {
            if (badEffect == null) {
                badEffect = Potion.class.getDeclaredField("field_76418_K"); // isBadEffect (the getter is client-only)
                badEffect.setAccessible(true);
            }
            return badEffect.getBoolean(potion);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
