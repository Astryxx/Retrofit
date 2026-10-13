package retrofit.legacy.bridge;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import retrofit.api.HostWorld;

import java.util.HashSet;
import java.util.Set;

/**
 * Legacy entities that legacy code spawns into a bridged world (other than dropped items and
 * experience, which become host entities directly). Hosting arbitrary legacy entities as 1.20.1
 * entities is not done yet; each class is reported once and the spawn is refused.
 */
final class LegacyEntities {
    private static final Set<String> REPORTED = new HashSet<String>();

    private LegacyEntities() {
    }

    static boolean spawn(HostWorld host, Entity entity) {
        String type = entity.getClass().getName();
        if (REPORTED.add(type)) {
            System.out.println("[Retrofit] Legacy entity " + type + " was spawned; legacy entities are not supported yet");
        }
        return false;
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
