package retrofit.legacy.bridge;

import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.client.IModGuiFactory;
import cpw.mods.fml.client.config.DummyConfigElement;
import cpw.mods.fml.client.config.GuiConfig;
import cpw.mods.fml.client.config.IConfigElement;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.ModMetadata;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Configuration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client only. The 1.7.10 mods for the host's list of them (its own page, as Forge's mod list):
 * what each mod's mcmod.info says, whether it loaded (and why not), and its configuration screen,
 * the mod's own (IModGuiFactory) or FML's generic one over the mod's .cfg files.
 */
public final class ClientMods {
    private static final List<String> CORE = java.util.Arrays.asList("mcp", "FML", "Forge", "Minecraft");

    private ClientMods() {
    }

    /**
     * The 1.7.10 mods, loaded or left out, as {mod id, name, version, authors, url, credits, description,
     * logo path in its jar, its jar, why it was left out (null if it loaded), whether it can be configured}.
     */
    public static Object[][] legacyMods() {
        Map<String, String> leftOut = retrofit.legacy.boot.ModFailures.leftOut();
        List<Object[]> rows = new ArrayList<Object[]>();
        for (ModContainer mod : Loader.instance().getModList()) {
            if (CORE.contains(mod.getModId())) {
                continue;
            }
            ModMetadata info = mod.getMetadata();
            String reason = leftOut.remove(mod.getModId());
            if (reason == null && !Loader.isModLoaded(mod.getModId())) {
                reason = "not loaded";
            }
            rows.add(new Object[]{mod.getModId(), mod.getName(), mod.getDisplayVersion(),
                    info == null ? "" : joined(info.authorList), info == null ? "" : nonNull(info.url),
                    info == null ? "" : nonNull(info.credits), info == null ? "" : nonNull(info.description),
                    info == null ? "" : nonNull(info.logoFile), mod.getSource() == null ? null : mod.getSource().getPath(),
                    reason, reason == null && (guiFactory(mod) != null || !configFiles(mod).isEmpty())});
        }
        for (Map.Entry<String, String> mod : leftOut.entrySet()) { // dropped before FML listed them
            rows.add(new Object[]{mod.getKey(), mod.getKey(), "", "", "", "", "", "", null, mod.getValue(), false});
        }
        return rows.toArray(new Object[0][]);
    }

    /**
     * A mod's configuration screen: its own, or FML's over its .cfg files; null if it has neither.
     * Closing it calls {@code back} (it shows its parent, which stands for the host's list); the
     * generic screen's files are saved first. Most 1.7.10 mods read their configs only at startup.
     */
    public static Object modConfigScreen(String modId, Runnable back) throws ReflectiveOperationException {
        ModContainer mod = Loader.instance().getIndexedModList().get(modId);
        if (mod == null) {
            return null;
        }
        IModGuiFactory factory = guiFactory(mod);
        if (factory != null && factory.mainConfigGuiClass() != null) {
            return factory.mainConfigGuiClass().getConstructor(GuiScreen.class).newInstance(new BackToHost(back, null));
        }
        List<File> files = configFiles(mod);
        if (files.isEmpty()) {
            return null;
        }
        List<Configuration> configs = new ArrayList<Configuration>();
        List<IConfigElement> elements = new ArrayList<IConfigElement>();
        File configDir = Loader.instance().getConfigDir();
        for (File file : files) {
            Configuration config = new Configuration(file);
            configs.add(config);
            List<IConfigElement> categories = new ArrayList<IConfigElement>();
            for (String name : config.getCategoryNames()) {
                if (!config.getCategory(name).isChild()) {
                    categories.add(new ConfigElement(config.getCategory(name)));
                }
            }
            if (files.size() == 1) {
                elements = categories;
            } else {
                String path = configDir.toURI().relativize(file.toURI()).getPath();
                elements.add(new DummyConfigElement.DummyCategoryElement(path, path, categories));
            }
        }
        return new GuiConfig(new BackToHost(back, configs), elements, modId, false, false, mod.getName());
    }

    private static IModGuiFactory guiFactory(ModContainer mod) {
        try {
            return FMLClientHandler.instance().getGuiFactoryFor(mod);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The mod's own .cfg files: those whose path (in retrofit/config) names its id or name. */
    private static List<File> configFiles(ModContainer mod) {
        File dir = Loader.instance().getConfigDir();
        String id = letters(mod.getModId());
        String name = letters(mod.getName());
        List<File> found = new ArrayList<File>();
        collect(dir, dir, id.length() >= 3 ? id : null, name.length() >= 3 ? name : null, found);
        Collections.sort(found);
        return found;
    }

    private static void collect(File dir, File root, String id, String name, List<File> found) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collect(file, root, id, name, found);
            } else if (file.getName().endsWith(".cfg")) {
                String path = letters(root.toURI().relativize(file.toURI()).getPath());
                if (id != null && path.contains(id) || name != null && path.contains(name)) {
                    found.add(file);
                }
            }
        }
    }

    private static String letters(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String joined(List<String> list) {
        StringBuilder text = new StringBuilder();
        for (String item : list) {
            if (item != null && !item.trim().isEmpty()) {
                text.append(text.length() == 0 ? "" : ", ").append(item.trim());
            }
        }
        return text.toString();
    }

    private static String nonNull(String text) {
        return text == null ? "" : text;
    }

    /**
     * The parent a configuration screen returns to: showing it (Minecraft.displayGuiScreen, see
     * ClientGuis.displayScreen) saves the generic screen's configs and goes back to the host's list.
     */
    static final class BackToHost extends GuiScreen {
        private final Runnable back;
        private final List<Configuration> configs;

        BackToHost(Runnable back, List<Configuration> configs) {
            this.back = back;
            this.configs = configs;
        }

        void back() {
            if (configs != null) {
                for (Configuration config : configs) {
                    if (config.hasChanged()) {
                        config.save();
                    }
                }
            }
            back.run();
        }
    }
}
