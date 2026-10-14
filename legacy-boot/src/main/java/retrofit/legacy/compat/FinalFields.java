package retrofit.legacy.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Java 17 replacements for the Java 8 reflection tricks FML and Forge use to write
 * {@code final} fields and to create new enum constants at runtime
 * ({@code Field.modifiers} hacking and {@code sun.reflect.ReflectionFactory}).
 *
 * Writes go through {@code sun.misc.Unsafe}; enum constructors are invoked through a
 * private {@link MethodHandles.Lookup}, which Java still permits for enums.
 */
public final class FinalFields {
    private static final Object UNSAFE;
    private static final Method STATIC_FIELD_BASE;
    private static final Method STATIC_FIELD_OFFSET;
    private static final Method OBJECT_FIELD_OFFSET;
    private static final Method PUT_OBJECT_VOLATILE;
    private static final Method PRIVATE_LOOKUP_IN;

    static {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            UNSAFE = theUnsafe.get(null);
            STATIC_FIELD_BASE = unsafeClass.getMethod("staticFieldBase", Field.class);
            STATIC_FIELD_OFFSET = unsafeClass.getMethod("staticFieldOffset", Field.class);
            OBJECT_FIELD_OFFSET = unsafeClass.getMethod("objectFieldOffset", Field.class);
            PUT_OBJECT_VOLATILE = unsafeClass.getMethod("putObjectVolatile", Object.class, long.class, Object.class);
            // Java 9+ API; looked up reflectively because this module compiles against Java 8.
            PRIVATE_LOOKUP_IN = MethodHandles.class.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private FinalFields() {
    }

    /** Writes {@code value} into {@code field}, even if it is final. Replaces EnumHelper.setFailsafeFieldValue. */
    public static void set(Field field, Object target, Object value) throws Exception {
        Object base;
        long offset;
        if (Modifier.isStatic(field.getModifiers())) {
            // Static fields live in the class's mirror once it is initialized (Unsafe.ensureClassInitialized is gone in Java 22+).
            Class<?> owner = field.getDeclaringClass();
            Class.forName(owner.getName(), true, owner.getClassLoader());
            base = STATIC_FIELD_BASE.invoke(UNSAFE, field);
            offset = (Long) STATIC_FIELD_OFFSET.invoke(UNSAFE, field);
        } else {
            base = target;
            offset = (Long) OBJECT_FIELD_OFFSET.invoke(UNSAFE, field);
        }
        Class<?> type = field.getType();
        if (!type.isPrimitive()) {
            PUT_OBJECT_VOLATILE.invoke(UNSAFE, base, offset, value);
            return;
        }
        String suffix = Character.toUpperCase(type.getName().charAt(0)) + type.getName().substring(1);
        UNSAFE.getClass().getMethod("put" + suffix + "Volatile", Object.class, long.class, type).invoke(UNSAFE, base, offset, value);
    }

    /** Creates a new constant of {@code enumClass}. Replaces EnumHelper.makeEnum. */
    public static Object makeEnum(Class<?> enumClass, String name, int ordinal, Class<?>[] paramTypes, Object[] paramValues) throws Throwable {
        int extra = paramTypes == null ? 0 : paramTypes.length;
        Class<?>[] types = new Class<?>[extra + 2];
        Object[] args = new Object[extra + 2];
        types[0] = String.class;
        types[1] = int.class;
        args[0] = name;
        args[1] = ordinal;
        for (int i = 0; i < extra; i++) {
            types[i + 2] = paramTypes[i];
            args[i + 2] = paramValues[i];
        }
        MethodHandles.Lookup lookup = (MethodHandles.Lookup) PRIVATE_LOOKUP_IN.invoke(null, enumClass, MethodHandles.lookup());
        MethodHandle constructor = lookup.findConstructor(enumClass, MethodType.methodType(void.class, types));
        return constructor.invokeWithArguments(args);
    }

    /** Drops the JDK's cached enum constants so values()/valueOf() see added constants. Replaces EnumHelper.cleanEnumCache. */
    public static void cleanEnumCache(Class<?> enumClass) throws Exception {
        for (String cache : new String[]{"enumConstants", "enumConstantDirectory"}) {
            Field field = Class.class.getDeclaredField(cache);
            long offset = (Long) OBJECT_FIELD_OFFSET.invoke(UNSAFE, field);
            PUT_OBJECT_VOLATILE.invoke(UNSAFE, enumClass, offset, null);
        }
    }

    /**
     * Replaces ObjectHolderRef.makeWritable. Instead of stripping FINAL from the field, it
     * points ObjectHolderRef's cached ReflectionFactory handles at {@link #newFieldAccessor},
     * so ObjectHolderRef.apply() writes through {@link #set} unchanged.
     */
    public static void prepareObjectHolderRef(Class<?> objectHolderRef, Field ignored) throws Exception {
        Field factory = objectHolderRef.getDeclaredField("reflectionFactory");
        factory.setAccessible(true);
        if (factory.get(null) != null) {
            return;
        }
        Field newAccessor = objectHolderRef.getDeclaredField("newFieldAccessor");
        Field accessorSet = objectHolderRef.getDeclaredField("fieldAccessorSet");
        newAccessor.setAccessible(true);
        accessorSet.setAccessible(true);
        newAccessor.set(null, FinalFields.class.getMethod("newFieldAccessor", Field.class, boolean.class));
        accessorSet.set(null, Accessor.class.getMethod("set", Object.class, Object.class));
        factory.set(null, FinalFields.class); // receiver is ignored: newFieldAccessor is static
    }

    public static Accessor newFieldAccessor(Field field, boolean override) {
        return new Accessor(field);
    }

    /** Stand-in for sun.reflect.FieldAccessor. */
    public static final class Accessor {
        private final Field field;

        Accessor(Field field) {
            this.field = field;
        }

        public void set(Object target, Object value) throws Exception {
            FinalFields.set(field, target, value);
        }
    }
}
