package retrofit.legacy.compat;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;

/**
 * Collection.toArray() as Java 8 had it (RetrofitTransformer redirects legacy calls here). Up to
 * Java 8, Arrays.asList(array).toArray() returned an array of the original's type, and 1.7.10
 * mods relied on it: GregTech reads IC2's machine recipes with (ItemStack[]) list.toArray().
 * Java 9 made it return Object[] (JDK-6260652), which turns those casts into exceptions.
 */
public final class LegacyCollections {
    private static final Class<?> ARRAYS_LIST = Arrays.asList(new Object[0]).getClass();
    private static Field backing;
    private static boolean unavailable;

    private LegacyCollections() {
    }

    public static Object[] toArray(Collection<?> collection) {
        if (collection.getClass() == ARRAYS_LIST && !unavailable) {
            try {
                if (backing == null) {
                    Field field = ARRAYS_LIST.getDeclaredField("a");
                    Reflection.setAccessible(field, true);
                    backing = field;
                }
                Object[] array = (Object[]) backing.get(collection);
                return Arrays.copyOf(array, array.length, array.getClass());
            } catch (ReflectiveOperationException | RuntimeException e) {
                unavailable = true; // Java 9+ behaviour from now on
            }
        }
        return collection.toArray();
    }
}
