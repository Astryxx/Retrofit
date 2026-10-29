package retrofit.legacy.boot;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Starts 1.7.10 + Forge the way {@code net.minecraft.launchwrapper.Launch} does, but
 * without its Java 8-only assumptions (it casts the app class loader to URLClassLoader)
 * and without handing control to Minecraft's main loop.
 *
 * Loaded by the host's isolated root loader, never by the LaunchClassLoader.
 */
public final class LegacyBootstrap {
    private static final String FML_TWEAKER = "cpw.mods.fml.common.launcher.FMLTweaker";
    private static final String FML_SERVER_TWEAKER = "cpw.mods.fml.common.launcher.FMLServerTweaker";

    private LegacyBootstrap() {
    }

    public static void boot(File gameDir, File assetsDir, boolean headless, boolean server) throws Throwable {
        URL[] sources = ((URLClassLoader) LegacyBootstrap.class.getClassLoader()).getURLs();
        LaunchClassLoader classLoader = new RetrofitLaunchClassLoader(sources);
        classLoader.addClassLoaderExclusion("retrofit.legacy.boot.");
        classLoader.addClassLoaderExclusion("retrofit.legacy.compat.");
        classLoader.addClassLoaderExclusion("retrofit.legacy.gl.");
        classLoader.addClassLoaderExclusion("retrofit.legacy.lwjgl.");
        classLoader.addClassLoaderExclusion("org.slf4j.");
        classLoader.addClassLoaderExclusion("retrofit.api.");
        classLoader.addClassLoaderExclusion("org.openjdk.nashorn."); // the JavaScript engine's own loader (LegacyRootLoader)

        Launch.classLoader = classLoader;
        Launch.minecraftHome = gameDir;
        Launch.assetsDir = assetsDir;
        Launch.blackboard = new HashMap<String, Object>();
        Thread.currentThread().setContextClassLoader(classLoader);

        // Fixes FML's own startup classes; hands over to RetrofitTransformer.Late once vanilla classes load.
        classLoader.registerTransformer(RetrofitTransformer.Early.class.getName());

        List<String> tweakClassNames = new ArrayList<String>(Collections.singletonList(server ? FML_SERVER_TWEAKER : FML_TWEAKER));
        List<String> argumentList = new ArrayList<String>();
        Launch.blackboard.put("TweakClasses", tweakClassNames);
        Launch.blackboard.put("ArgumentList", argumentList);

        ModDuplicates.setAside(gameDir); // before FML reads the mods folder: two versions of a mod stop every mod
        runTweakers(classLoader, tweakClassNames, argumentList, gameDir, assetsDir);
        skipLibraryScanning(sources);

        // From here on, vanilla classes load; fixes must apply after Forge's patches and deobfuscation.
        classLoader.registerTransformer(RetrofitTransformer.Late.class.getName());
        RetrofitTransformer.enterLateStage();

        if (headless) {
            // Last in the chain, so it sees deobfuscated SRG names.
            classLoader.registerTransformer(HeadlessTransformer.class.getName());
            if (server) {
                HeadlessServer.run(classLoader, gameDir);
            } else {
                HeadlessClient.run(classLoader, gameDir);
            }
        }
    }

    /**
     * FML scans every classpath jar for mods. Only the game jar and Forge can hold anything it
     * needs, so the libraries are added to its "known library" list (it matches by file name).
     * This saves startup time and avoids ASM 5 errors on Java 9+ classes in modern libraries.
     */
    @SuppressWarnings("unchecked")
    private static void skipLibraryScanning(URL[] sources) throws Exception {
        List<String> known = (List<String>) Class.forName("cpw.mods.fml.relauncher.CoreModManager")
                .getMethod("getLoadedCoremods").invoke(null);
        for (URL source : sources) {
            String name = new File(source.toURI()).getName();
            if (!name.equals("1.7.10.jar") && !name.startsWith("forge-")) {
                known.add(name);
            }
        }
    }

    /**
     * Tweakers that rewrite Minecraft 1.7.10 itself in ways the headless game can't run, by class: their
     * mod is left out (with why), so it doesn't stop the others. OptiFine patches the renderer, which
     * 1.20.1 draws instead.
     */
    private static final Map<String, String> UNSUPPORTED_TWEAKERS = Collections.singletonMap("optifine.OptiFineForgeTweaker",
            "OptiFine: not supported (it patches 1.7.10's renderer, which 1.20.1 replaces; use a 1.20.1 performance or shader mod)");

    /** Mirrors the tweaker loop in Launch.launch: tweakers may queue further tweakers. */
    private static void runTweakers(LaunchClassLoader classLoader, List<String> tweakClassNames, List<String> argumentList,
                                    File gameDir, File assetsDir) throws Exception {
        Set<String> seen = new HashSet<String>();
        List<ITweaker> allTweakers = new ArrayList<ITweaker>();
        List<ITweaker> pending = new ArrayList<ITweaker>();
        Launch.blackboard.put("Tweaks", pending);

        do {
            for (String tweakName : new ArrayList<String>(tweakClassNames)) {
                tweakClassNames.remove(tweakName);
                if (!seen.add(tweakName)) {
                    continue;
                }
                String unsupported = UNSUPPORTED_TWEAKERS.get(tweakName);
                if (unsupported != null) {
                    ModFailures.leaveOut(tweakName, unsupported);
                    System.out.println("[Retrofit] Left out tweaker " + tweakName + ": " + unsupported);
                    continue;
                }
                classLoader.addClassLoaderExclusion(tweakName.substring(0, tweakName.lastIndexOf('.')));
                ITweaker tweaker = (ITweaker) Class.forName(tweakName, true, classLoader).newInstance();
                pending.add(tweaker);
                System.out.println("[Retrofit] Loaded tweaker " + tweakName);
            }
            // Iterates the live list as LaunchWrapper does: FMLInjectionAndSortingTweaker sorts it in place
            // (List.set) while this runs, which is what puts FML's deobfuscation before late coremods.
            for (Iterator<ITweaker> it = pending.iterator(); it.hasNext(); ) {
                ITweaker tweaker = it.next();
                tweaker.acceptOptions(new ArrayList<String>(), gameDir, assetsDir, "1.7.10-Forge");
                tweaker.injectIntoClassLoader(classLoader);
                allTweakers.add(tweaker);
                it.remove();
            }
        } while (!tweakClassNames.isEmpty());

        for (ITweaker tweaker : allTweakers) {
            Collections.addAll(argumentList, tweaker.getLaunchArguments());
        }
    }
}
