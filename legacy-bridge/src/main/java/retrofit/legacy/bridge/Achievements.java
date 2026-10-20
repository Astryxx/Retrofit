package retrofit.legacy.bridge;

import net.minecraft.stats.Achievement;
import net.minecraft.stats.AchievementList;
import net.minecraftforge.common.AchievementPage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mods' achievements, which 1.20.1 shows as advancements (the host makes one per achievement,
 * under a tab per achievement page; see LegacyAchievements). Mods unlock them as they always did
 * (triggerAchievement on the player): the player's mirror tells the host.
 */
public final class Achievements {
    private Achievements() {
    }

    /** {stat id ("achievement.name"), page name or null, parent stat id or null, special (1/0), icon (legacy stack)} of mods' achievements. */
    public static Object[][] legacyAchievements() {
        Map<Achievement, String> pages = new HashMap<Achievement, String>();
        for (AchievementPage page : AchievementPage.getAchievementPages()) {
            for (Achievement achievement : page.getAchievements()) {
                pages.put(achievement, page.getName());
            }
        }
        List<Object[]> result = new ArrayList<Object[]>();
        for (Object element : AchievementList.field_76007_e) { // achievementList
            Achievement achievement = (Achievement) element;
            String page = pages.get(achievement);
            if (page == null) {
                continue; // vanilla's page: 1.20.1 has its own advancements
            }
            Achievement parent = achievement.field_75992_c; // parentAchievement
            result.add(new Object[]{achievement.field_75975_e, page, parent != null && pages.get(parent) != null ? parent.field_75975_e : null,
                    achievement.func_75984_f() ? 1 : 0, achievement.field_75990_d}); // statId, getSpecial, theItemStack
        }
        return result.toArray(new Object[0][]);
    }
}
