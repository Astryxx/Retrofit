package retrofit.legacy.bridge;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import net.minecraft.world.biome.BiomeGenBase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Biome id clashes between legacy mods, resolved per mod. Mods that number their own biomes check
 * the shared 1.7.10 biome array (BiomeGenBase.getBiomeGenArray) for the ids their configs name,
 * and refuse to load when another mod's biome has one (Erebus and Advent of Ascension both default
 * to 100; on 1.7.10 the player had to renumber one mod's config). Here every biome construction
 * reports itself (BiomeGenBase's constructors are hooked, see {@link #record}), and
 * getBiomeGenArray is redirected (HeadlessTransformer) to {@link #viewedArray}, which shows each
 * mod the biomes it registered itself at the ids it asked for, wherever another mod's biome sits.
 * In the real array a biome asking for a taken id gets a free one (LegacyIds.biomeId), so the
 * biomes the mods' world generators look up by id stay their own; outside a mod's loading, the
 * real array is returned, and biomes reach 1.20.1 by name (WorldGlue).
 */
public final class Biomes {
    /** The mods that are part of the game itself; their biomes are everybody's. */
    private static final Set<String> CORE = ModOwners.CORE;

    private static final Object LOCK = new Object();

    /**
     * The shared biome array field (biomeList, private in 1.7.10 - that is why Forge added the
     * getBiomeGenArray accessor this class redirects). Found by type, and read each time, so an
     * array a mod enlarged (through its final field) is seen. Null if nothing like it exists: then
     * nothing is tracked and everything is shared, as on 1.7.10.
     */
    private static final java.lang.reflect.Field ARRAY = arrayField();

    private static java.lang.reflect.Field arrayField() {
        for (java.lang.reflect.Field field : BiomeGenBase.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType() == BiomeGenBase[].class) {
                field.setAccessible(true);
                return field;
            }
        }
        System.out.println("[Retrofit] No legacy biome array found; biome ids stay shared as on 1.7.10");
        return null;
    }

    /** Loader.modController and LoadController.activeContainer (see currentMod); null if FML has no such fields. */
    private static final java.lang.reflect.Field CONTROLLER;
    private static final java.lang.reflect.Field ACTIVE;

    static {
        java.lang.reflect.Field controller = null;
        java.lang.reflect.Field active = null;
        try {
            controller = Loader.class.getDeclaredField("modController");
            controller.setAccessible(true);
            active = controller.getType().getDeclaredField("activeContainer");
            active.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Retrofit] No FML active mod field found; biome ids stay shared as on 1.7.10");
            active = null;
        }
        CONTROLLER = controller;
        ACTIVE = active;
    }

    /** The biome array as it stood when the first mod biome registered: what every mod started from. */
    private static BiomeGenBase[] baseline;

    /** The biomes mods registered: {mod id, biome, the id the mod asked for (its slot in the mod's view)}, in registration order. */
    private static final List<Object[]> REGISTERED = new ArrayList<Object[]>();

    /** Each mod's own view of the array, built on first use and dropped whenever a mod biome registers (see record). */
    private static final Map<String, BiomeGenBase[]> VIEWS = new HashMap<String, BiomeGenBase[]>();

    private Biomes() {
    }

    /**
     * Replaces BiomeGenBase.getBiomeGenArray (func_150565_n). While a mod loads, it sees the
     * vanilla biomes and its own (at the ids it asked for, even where another mod's biome also
     * sits in the real array); the other mods' biomes are not shown, so its "is this id free"
     * checks pass and both mods load. Any other time, the real array, as 1.7.10 shared it.
     */
    public static BiomeGenBase[] viewedArray() {
        BiomeGenBase[] real = realArray();
        if (real == null) {
            return new BiomeGenBase[256]; // no biome array to show (never so far): an empty one
        }
        String mod = currentMod();
        if (mod == null) {
            return real;
        }
        synchronized (LOCK) {
            BiomeGenBase[] view = VIEWS.get(mod);
            if (view == null || view.length != real.length) { // (or a mod enlarged the array)
                view = view(mod, real);
                VIEWS.put(mod, view);
            }
            return view;
        }
    }

    /**
     * Called at the end of every BiomeGenBase constructor (HeadlessTransformer): the new biome is
     * in the real array now (the constructor put it there), so its slot is found and it is
     * remembered as the registering mod's. Vanilla biomes and biomes built outside a mod's loading
     * (register=false ones never enter the array) are left alone: they are in the baseline.
     */
    public static void record(BiomeGenBase biome) {
        if (biome == null) {
            return;
        }
        try {
            String mod = currentMod();
            if (mod == null || vanilla()) {
                return;
            }
            BiomeGenBase[] real = realArray();
            if (real == null) {
                return;
            }
            int slot = -1;
            for (int i = 0; i < real.length; i++) {
                if (real[i] == biome) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) {
                return;
            }
            int asked = retrofit.legacy.boot.LegacyIds.askedBiomeId(biome); // moved off a taken id: the mod still looks for it there
            int shown = asked >= 0 ? asked : slot;
            synchronized (LOCK) {
                for (Object[] earlier : REGISTERED) {
                    if (earlier[1] == biome) {
                        return; // hooked constructors can run twice for one biome ((I)V calls (IZ)V)
                    }
                }
                if (baseline == null) {
                    baseline = real.clone();
                    baseline[slot] = null; // the first mod biome: before it, this slot was empty
                }
                for (Object[] earlier : REGISTERED) {
                    if ((Integer) earlier[2] == shown && !earlier[0].equals(mod)) {
                        System.out.println("[Retrofit] Biome id " + shown + " is asked for by both " + earlier[0]
                                + " (" + name((BiomeGenBase) earlier[1]) + ") and " + mod + " (" + name(biome)
                                + "); each mod sees its own biome there" + (slot != shown ? ", and " + mod + "'s has id " + slot : ""));
                    }
                }
                REGISTERED.add(new Object[]{mod, biome, shown});
                // every view rebuilds: one built before this (the mod's own free-id check) lacked it, and a count
                // shared by all the views let it stand (Erebus found its own biome missing in postInit)
                VIEWS.clear();
            }
        } catch (Throwable t) {
            System.out.println("[Retrofit] Could not follow a biome registration: " + t);
        }
    }

    /**
     * Whether the biome being made is one of vanilla's: BiomeGenBase's static initializer makes them all,
     * whenever a mod first touches the class (GregTech, while it loads), so the loading mod isn't their maker.
     */
    private static boolean vanilla() {
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            if ("<clinit>".equals(frame.getMethodName()) && BiomeGenBase.class.getName().equals(frame.getClassName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The registering mod's id while FML runs its loading events, or the mod Retrofit acts for (see actAs), or
     * null any other time. Read from
     * LoadController's activeContainer field, which FML sets only while it sends a mod its events:
     * Loader.activeModContainer falls back to guessing the mod from the call stack, and world generation
     * would then see a mod's loading view (HBM's generator asking Erebus for a biome got HBM's view,
     * without Erebus's biomes).
     */
    private static String currentMod() {
        try {
            if (ACTIVE == null) {
                return null;
            }
            Object controller = CONTROLLER.get(Loader.instance());
            ModContainer container = controller == null ? null : (ModContainer) ACTIVE.get(controller);
            if (container == null) {
                return ACTING.get();
            }
            String id = container.getModId();
            return CORE.contains(id) ? null : id;
        } catch (Throwable notUpYet) {
            return null; // before FML, or not the FML we expect: the shared array, as on 1.7.10
        }
    }

    /** The mod Retrofit runs code of on this thread outside FML's loading events (see actAs), or null. */
    private static final ThreadLocal<String> ACTING = new ThreadLocal<String>();

    /**
     * Runs {@code work} as the mod whose dimension it makes a world for: a dimension's provider can make its
     * biomes then (HBM's planets do, whenever a world is made for them), and they are that mod's, checked
     * against its own view and moved off another mod's id, as while it loaded.
     */
    static <T> T actAs(int dimension, java.util.concurrent.Callable<T> work) throws Exception {
        String before = ACTING.get();
        ACTING.set(providerMod(dimension));
        try {
            return work.call();
        } finally {
            ACTING.set(before);
        }
    }

    /** The mod whose provider class a dimension has, or null for vanilla's and unknown ones. */
    private static String providerMod(int dimension) {
        try {
            java.lang.reflect.Field providers = net.minecraftforge.common.DimensionManager.class.getDeclaredField("providers");
            providers.setAccessible(true);
            Class<?> provider = (Class<?>) ((Map<?, ?>) providers.get(null)).get(net.minecraftforge.common.DimensionManager.getProviderType(dimension));
            return provider == null ? null : ModOwners.of(provider.getName());
        } catch (Throwable t) {
            return null; // the shared array, as on 1.7.10
        }
    }

    /**
     * A mod's own view of the biome array: the baseline (the array as it stood before mods
     * registered anything, enlarged if a mod enlarged the array since), then the mod's own
     * biomes over it. The other mods' biomes are simply not there.
     */
    private static BiomeGenBase[] view(String mod, BiomeGenBase[] real) {
        BiomeGenBase[] view = baseline == null ? real.clone() : Arrays.copyOf(baseline, real.length);
        for (Object[] entry : REGISTERED) {
            if (((String) entry[0]).equals(mod) && (Integer) entry[2] < view.length) {
                view[(Integer) entry[2]] = (BiomeGenBase) entry[1];
            }
        }
        return view;
    }

    /** The shared biome array, as it is right now (a mod may have enlarged it). */
    private static BiomeGenBase[] realArray() {
        try {
            return ARRAY == null ? null : (BiomeGenBase[]) ARRAY.get(null);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /** The biome's name (biomeName), for the clash log. */
    private static String name(BiomeGenBase biome) {
        try {
            String biomeName = biome.field_76791_y; // biomeName
            return biomeName == null || biomeName.isEmpty() ? biome.getClass().getName() : biomeName;
        } catch (Throwable t) {
            return biome.getClass().getName();
        }
    }
}
