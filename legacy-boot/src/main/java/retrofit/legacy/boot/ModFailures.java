package retrofit.legacy.boot;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One 1.7.10 mod that can't load must not take the others with it. 1.7.10's FML stopped the game
 * when a mod lacked a mod it requires, or threw while loading; here that mod (and the mods that
 * require it) is left out, the rest load, and the player is told which and why (see
 * {@link #report()}, shown on the title screen).
 *
 * Called from FML's Loader.sortModList and LoadController.transition (see HeadlessTransformer);
 * everything goes through reflection, as FML is in the game loader.
 */
public final class ModFailures {
    /** FML's own containers: if they fail, 1.7.10 itself can't run. */
    private static final Set<String> CORE = new HashSet<String>(java.util.Arrays.asList("mcp", "FML", "Forge", "Minecraft"));
    /** Mod id -> why it was left out, in order. */
    private static final Map<String, String> DROPPED = new LinkedHashMap<String, String>();
    /** The files of the mods left out: what they registered before failing stays, and needs their textures and names. */
    private static final List<java.io.File> LEFT_OUT_SOURCES = new ArrayList<java.io.File>();
    /** Event listeners that threw, each logged once. */
    private static final Set<String> FAILED_LISTENERS = new HashSet<String>();

    private ModFailures() {
    }

    /** The jars of the mods left out (see {@link #LEFT_OUT_SOURCES}). */
    public static synchronized List<java.io.File> leftOutSources() {
        return new ArrayList<java.io.File>(LEFT_OUT_SOURCES);
    }

    /** Mod id -> why it was left out ("GregTech (gregtech): needs IC2"), in order. */
    public static synchronized Map<String, String> leftOut() {
        return new LinkedHashMap<String, String>(DROPPED);
    }

    /** A mod left out before FML loads mods (a tweaker Retrofit can't run), with why. */
    public static synchronized void leaveOut(String id, String why) {
        DROPPED.put(id, why);
    }

    /** Why mods were left out, one line each ("GregTech (gregtech): needs IC2"). */
    public static synchronized List<String> report() {
        return new ArrayList<String>(DROPPED.values());
    }

    /**
     * Start of Loader.sortModList: mods whose required mods are missing (or have a version they
     * don't accept), or that don't accept Minecraft 1.7.10, are left out before FML would refuse to
     * start. Repeated while leaving one out leaves another without what it requires.
     */
    public static void dropUnsatisfied(Object loader) {
        try {
            Object controller = get(loader, "modController");
            List<?> active = (List<?>) controller.getClass().getMethod("getActiveModList").invoke(controller);
            Class<?> modContainer = Class.forName("cpw.mods.fml.common.ModContainer", false, loader.getClass().getClassLoader());
            Class<?> artifactVersion = Class.forName("cpw.mods.fml.common.versioning.ArtifactVersion", false, loader.getClass().getClassLoader());
            Method getModId = modContainer.getMethod("getModId");
            Method getRequirements = modContainer.getMethod("getRequirements");
            Method getDependencies = modContainer.getMethod("getDependencies");
            Method getDependants = modContainer.getMethod("getDependants");
            Method getProcessedVersion = modContainer.getMethod("getProcessedVersion");
            Method acceptableRange = modContainer.getMethod("acceptableMinecraftVersionRange");
            Method getLabel = artifactVersion.getMethod("getLabel");
            Method containsVersion = artifactVersion.getMethod("containsVersion", artifactVersion);
            Object minecraftVersion = getProcessedVersion.invoke(get(loader, "minecraft"));

            Map<String, Object> present = new LinkedHashMap<String, Object>(); // id -> version
            for (Object mod : active) {
                present.put((String) getModId.invoke(mod), getProcessedVersion.invoke(mod));
            }
            Class<?> apiManager = Class.forName("cpw.mods.fml.common.ModAPIManager", false, loader.getClass().getClassLoader());
            Object apis = apiManager.getField("INSTANCE").get(null);
            for (Object api : (Iterable<?>) apiManager.getMethod("getAPIList").invoke(apis)) {
                present.put((String) getModId.invoke(api), getProcessedVersion.invoke(api));
            }

            boolean changed = true;
            while (changed) {
                changed = false;
                for (Object mod : new ArrayList<Object>(active)) {
                    String id = (String) getModId.invoke(mod);
                    if (CORE.contains(id)) {
                        continue;
                    }
                    String why = null;
                    Object range = acceptableRange.invoke(mod);
                    if (!(Boolean) range.getClass().getMethod("containsVersion", artifactVersion).invoke(range, minecraftVersion)) {
                        why = "is not for Minecraft 1.7.10";
                    }
                    List<String> missing = new ArrayList<String>();
                    for (Object required : (Collection<?>) getRequirements.invoke(mod)) {
                        String label = (String) getLabel.invoke(required);
                        if (!present.containsKey(label)) {
                            missing.add(describe(required));
                        }
                    }
                    for (Collection<?> versions : new Collection<?>[]{(Collection<?>) getDependencies.invoke(mod), (Collection<?>) getDependants.invoke(mod)}) {
                        for (Object wanted : versions) {
                            String label = (String) getLabel.invoke(wanted);
                            if (present.containsKey(label) && !(Boolean) containsVersion.invoke(wanted, present.get(label))) {
                                missing.add(describe(wanted) + " (installed: " + present.get(label) + ")");
                            }
                        }
                    }
                    if (why == null && !missing.isEmpty()) {
                        why = "needs " + join(missing);
                    }
                    if (why != null) {
                        present.remove(id);
                        leaveOut(loader, controller, mod, id, why);
                        changed = true;
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            System.out.println("[Retrofit] Could not check 1.7.10 mods' requirements: " + e);
        }
    }

    /**
     * Start of LoadController.transition: mods that threw in the phase just run (construction,
     * preInit, init...) are left out with the mods requiring them, and their errors cleared, so
     * FML moves on with the others.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void dropErrored(Object controller) {
        try {
            Object errors = get(controller, "errors"); // Multimap<String, Throwable>
            if ((Boolean) errors.getClass().getMethod("isEmpty").invoke(errors)) {
                return;
            }
            Map<String, Collection<Throwable>> byMod = (Map<String, Collection<Throwable>>) errors.getClass().getMethod("asMap").invoke(errors);
            for (String id : byMod.keySet()) {
                if (CORE.contains(id)) {
                    return; // FML itself failed: as on 1.7.10, loading stops
                }
            }
            Object state = get(controller, "state");
            if (((Enum<?>) state).ordinal() >= Enum.valueOf((Class) state.getClass(), "AVAILABLE").ordinal()) {
                // a server starting or stopping: the mods stay (the next world starts them again); only logged
                for (Map.Entry<String, Collection<Throwable>> entry : byMod.entrySet()) {
                    System.out.println("[Retrofit] The 1.7.10 mod " + entry.getKey() + " failed while the server " + state + "; it carries on:\n"
                            + stackTrace(entry.getValue().iterator().next()));
                }
                errors.getClass().getMethod("clear").invoke(errors);
                return;
            }
            Object loader = get(controller, "loader");
            List<?> active = (List<?>) controller.getClass().getMethod("getActiveModList").invoke(controller);
            Class<?> modContainer = Class.forName("cpw.mods.fml.common.ModContainer", false, controller.getClass().getClassLoader());
            Method getModId = modContainer.getMethod("getModId");
            Method getRequirements = modContainer.getMethod("getRequirements");
            Method getLabel = Class.forName("cpw.mods.fml.common.versioning.ArtifactVersion", false, controller.getClass().getClassLoader()).getMethod("getLabel");

            Set<String> failed = new HashSet<String>();
            for (Map.Entry<String, Collection<Throwable>> entry : byMod.entrySet()) {
                Throwable error = entry.getValue().iterator().next();
                System.out.println("[Retrofit] The 1.7.10 mod " + entry.getKey() + " failed while loading; it is left out, and the other mods load:\n" + stackTrace(error));
                for (Object mod : new ArrayList<Object>(active)) {
                    if (entry.getKey().equals(getModId.invoke(mod))) {
                        failed.add(entry.getKey());
                        leaveOut(loader, controller, mod, entry.getKey(), "failed to load: " + message(error));
                    }
                }
            }
            boolean changed = true;
            while (changed) {
                changed = false;
                for (Object mod : new ArrayList<Object>(active)) {
                    String id = (String) getModId.invoke(mod);
                    for (Object required : (Collection<?>) getRequirements.invoke(mod)) {
                        String label = (String) getLabel.invoke(required);
                        if (failed.contains(label) && !CORE.contains(id)) {
                            failed.add(id);
                            leaveOut(loader, controller, mod, id, "needs " + label + ", which failed to load");
                            changed = true;
                            break;
                        }
                    }
                }
            }
            errors.getClass().getMethod("clear").invoke(errors);
        } catch (ReflectiveOperationException e) {
            System.out.println("[Retrofit] Could not leave out the 1.7.10 mods that failed: " + e);
        }
    }

    /** Removes a mod from the active list, the loaded mods and Loader.isModLoaded, as FML's runtimeDisableMod does. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void leaveOut(Object loader, Object controller, Object mod, String id, String why) throws ReflectiveOperationException {
        List active = (List) controller.getClass().getMethod("getActiveModList").invoke(controller);
        active.remove(mod);
        Field modsField = field(loader.getClass(), "mods");
        List before = (List) modsField.get(loader);
        List mods = new ArrayList(before);
        mods.remove(mod);
        // an ArrayList while FML sorts mods, an immutable list once loading runs
        modsField.set(loader, before instanceof ArrayList ? mods : java.util.Collections.unmodifiableList(mods));
        Field namedField = field(loader.getClass(), "namedMods");
        Map named = (Map) namedField.get(loader);
        if (named != null && named.containsKey(id)) { // Loader.isModLoaded: other mods' integrations with it stay off
            Map rest = new LinkedHashMap(named);
            rest.remove(id);
            namedField.set(loader, rest); // an immutable map while FML sorts mods
        }
        Object states = get(controller, "modStates");
        Class<?> modState = Class.forName("cpw.mods.fml.common.LoaderState$ModState", false, controller.getClass().getClassLoader());
        states.getClass().getMethod("put", Object.class, Object.class).invoke(states, id, Enum.valueOf((Class) modState, "DISABLED"));
        String name = (String) mod.getClass().getMethod("getName").invoke(mod);
        String line = (name != null && !name.equals(id) ? name + " (" + id + ")" : id) + ": " + why;
        Object source = mod.getClass().getMethod("getSource").invoke(mod);
        synchronized (ModFailures.class) {
            DROPPED.put(id, line);
            if (source instanceof java.io.File && !LEFT_OUT_SOURCES.contains(source)) {
                LEFT_OUT_SOURCES.add((java.io.File) source);
            }
        }
        System.out.println("[Retrofit] 1.7.10 mod left out: " + line);
    }

    /**
     * EventBus.post: a listener that throws is logged (once per listener and kind of error) and
     * the others still get the event. On 1.7.10 the exception went on to whatever posted the event,
     * so one mod's broken handler could stop, say, every player from being created.
     */
    public static void listenerFailed(Object event, Object listener, Throwable error) {
        if (error instanceof VirtualMachineError) {
            throw (VirtualMachineError) error;
        }
        String key = listener + " " + error.getClass().getName();
        synchronized (FAILED_LISTENERS) {
            if (!FAILED_LISTENERS.add(key)) {
                return;
            }
        }
        System.out.println("[Retrofit] A 1.7.10 event listener failed (" + event.getClass().getName() + " to " + listener
                + "); the other listeners still run, and this is logged once:\n" + stackTrace(error));
    }

    private static String describe(Object version) {
        String text = String.valueOf(version);
        return text.endsWith("@[0,)") || text.endsWith("@") ? text.substring(0, text.indexOf('@')) : text;
    }

    private static String message(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root
                && (root instanceof java.lang.reflect.InvocationTargetException || root.getMessage() == null)) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + (message.length() > 160 ? message.substring(0, 160) + "..." : message));
    }

    private static String join(List<String> parts) {
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            text.append(text.length() == 0 ? "" : ", ").append(part);
        }
        return text.toString();
    }

    private static String stackTrace(Throwable error) {
        StringWriter text = new StringWriter();
        error.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    private static Object get(Object target, String name) throws ReflectiveOperationException {
        return field(target.getClass(), name).get(target);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                // look in the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }
}
