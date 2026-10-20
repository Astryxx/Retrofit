package retrofit.forge.world;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.advancements.Advancement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Mods' achievements as advancements: a tab per achievement page (Thaumcraft, Witchery...), each
 * achievement an advancement under its parent with its icon, title and description, a challenge
 * frame for special ones. Mods unlock them as on 1.7.10; the player gets the advancement.
 */
final class LegacyAchievements {
    private static final Logger LOGGER = LogUtils.getLogger();

    private LegacyAchievements() {
    }

    private static String path(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
    }

    static ResourceLocation idOf(String statId) {
        return new ResourceLocation("retrofit", "legacy/" + path(statId));
    }

    private static ResourceLocation pageId(String page) {
        return new ResourceLocation("retrofit", "legacy/page_" + path(page));
    }

    static void addTo(LegacyDataPack pack) {
        Object[][] achievements;
        try {
            achievements = LegacyBridge.call("legacyAchievements");
        } catch (RuntimeException e) {
            LOGGER.error("Could not list the legacy achievements", e);
            return;
        }
        Set<String> pages = new HashSet<>();
        for (Object[] achievement : achievements) {
            String statId = (String) achievement[0];
            String page = (String) achievement[1];
            String parent = (String) achievement[2];
            ItemStack icon = LegacyStacks.toModern(achievement[4]);
            if (icon.isEmpty()) {
                icon = new ItemStack(net.minecraft.world.item.Items.BOOK);
            }
            if (pages.add(page)) {
                JsonObject root = new JsonObject();
                JsonObject display = display(icon, page, page, false);
                display.addProperty("background", "minecraft:textures/block/bookshelf.png");
                display.addProperty("show_toast", false);
                display.addProperty("announce_to_chat", false);
                root.add("display", display);
                root.add("criteria", criterion("minecraft:tick"));
                pack.put("retrofit", "advancements/" + pageId(page).getPath() + ".json", root.toString());
            }
            JsonObject json = new JsonObject();
            json.addProperty("parent", (parent != null ? idOf(parent) : pageId(page)).toString());
            json.add("display", display(icon, statId, statId + ".desc", (Integer) achievement[3] == 1));
            json.add("criteria", criterion("minecraft:impossible"));
            pack.put("retrofit", "advancements/" + idOf(statId).getPath() + ".json", json.toString());
        }
        LOGGER.info("Legacy achievements: {} as advancements in {} tabs", achievements.length, pages.size());
    }

    private static JsonObject display(ItemStack icon, String title, String description, boolean special) {
        JsonObject display = new JsonObject();
        JsonObject item = new JsonObject();
        item.addProperty("item", ForgeRegistries.ITEMS.getKey(icon.getItem()).toString());
        if (icon.hasTag()) {
            item.addProperty("nbt", icon.getTag().toString());
        }
        display.add("icon", item);
        JsonObject titleText = new JsonObject();
        titleText.addProperty("translate", title);
        display.add("title", titleText);
        JsonObject descriptionText = new JsonObject();
        descriptionText.addProperty("translate", description);
        display.add("description", descriptionText);
        display.addProperty("frame", special ? "challenge" : "task");
        return display;
    }

    private static JsonObject criterion(String trigger) {
        JsonObject criteria = new JsonObject();
        JsonObject legacy = new JsonObject();
        legacy.addProperty("trigger", trigger);
        criteria.add("legacy", legacy);
        return criteria;
    }

    /** A mod unlocked an achievement for a player. */
    static void grant(ServerPlayer player, String statId) {
        Advancement advancement = player.server.getAdvancements().getAdvancement(idOf(statId));
        if (advancement != null && !player.getAdvancements().getOrStartProgress(advancement).isDone()) {
            player.server.execute(() -> player.getAdvancements().award(advancement, "legacy"));
        }
    }
}
