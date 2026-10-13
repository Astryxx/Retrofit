package retrofit.host;

import java.nio.file.Path;

/**
 * Handle to a booted legacy world.
 *
 * @param rootLoader the isolated loader that owns Retrofit's legacy-side classes
 * @param gameJar    the player's Minecraft 1.7.10 jar (legacy assets are read from it)
 */
public record LegacyRuntime(ClassLoader rootLoader, Path gameJar) {
    /** The LaunchClassLoader: loads Minecraft 1.7.10, Forge, legacy mods and Retrofit's legacy bridge. */
    public ClassLoader gameLoader() {
        try {
            return (ClassLoader) rootLoader.loadClass("net.minecraft.launchwrapper.Launch").getField("classLoader").get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Legacy world is not booted", e);
        }
    }
}
