package retrofit.legacy.bridge;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;

/** Entity lookups in bridged worlds (spawning is in {@link Entities} and WorldGlue). */
final class LegacyEntities {
    private LegacyEntities() {
    }

    /** getEntityByID over the entity list of the world (bridged players live there). */
    static Entity byId(World world, int id) {
        for (Object element : world.field_72996_f) { // loadedEntityList
            Entity entity = (Entity) element;
            if (entity.func_145782_y() == id) { // getEntityId
                return entity;
            }
        }
        return null;
    }
}
