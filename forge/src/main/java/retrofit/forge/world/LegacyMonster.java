package retrofit.forge.world;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;

/**
 * A legacy monster (IMob): an Enemy to 1.20.1, so iron golems, snow golems and 1.20.1 mods'
 * guards and turrets go after it, and peaceful difficulty and mob caps treat it as a monster.
 */
public class LegacyMonster extends LegacyMob implements Enemy {
    public LegacyMonster(EntityType<? extends LegacyMob> type, Level level, String legacyName, float width, float height) {
        super(type, level, legacyName, width, height);
    }
}
