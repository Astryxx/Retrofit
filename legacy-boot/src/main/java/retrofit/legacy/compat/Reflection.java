package retrofit.legacy.compat;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Java 17 replacement for {@code AccessibleObject.setAccessible}, which legacy code calls through
 * (redirected by RetrofitTransformer). Java 8 let mods open any JDK member (coremods define classes
 * through {@code ClassLoader.defineClass}, read {@code URLClassLoader.ucp}, ...); Java 17 refuses
 * members of JDK modules. Legacy code gets the Java 8 behaviour: when the JDK refuses, the
 * object's {@code override} flag (what setAccessible sets) is written directly.
 */
public final class Reflection {
    private static final Object UNSAFE;
    private static final Method PUT_BOOLEAN;
    /** Offset of AccessibleObject.override, found by comparing an opened and an unopened Method. */
    private static final long OVERRIDE;

    static {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            UNSAFE = theUnsafe.get(null);
            PUT_BOOLEAN = unsafeClass.getMethod("putBoolean", Object.class, long.class, boolean.class);
            Method getBoolean = unsafeClass.getMethod("getBoolean", Object.class, long.class);
            Method closed = Reflection.class.getDeclaredMethod("probe");
            Method open = Reflection.class.getDeclaredMethod("probe");
            open.setAccessible(true);
            long found = -1;
            for (long offset = 8; offset < 64; offset++) {
                if ((Boolean) getBoolean.invoke(UNSAFE, open, offset) && !(Boolean) getBoolean.invoke(UNSAFE, closed, offset)) {
                    found = offset;
                    break;
                }
            }
            OVERRIDE = found;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Reflection() {
    }

    @SuppressWarnings("unused")
    private static void probe() {
    }

    /** Replaces {@code object.setAccessible(flag)}. */
    public static void setAccessible(AccessibleObject object, boolean flag) {
        try {
            object.setAccessible(flag);
        } catch (RuntimeException e) {
            // InaccessibleObjectException (Java 9+); anything else, like SecurityException, is passed on.
            if (!e.getClass().getName().equals("java.lang.reflect.InaccessibleObjectException") || OVERRIDE < 0) {
                throw e;
            }
            try {
                PUT_BOOLEAN.invoke(UNSAFE, object, OVERRIDE, flag);
            } catch (ReflectiveOperationException unsafeFailed) {
                throw e;
            }
        }
    }

    /**
     * Replaces {@code type.getDeclaredField(name)}. Java 12+ hides some JDK fields from reflection,
     * above all {@code Field.modifiers}, which 1.7.10 mods clear the final bit of to replace
     * {@code static final} arrays (Potion.potionTypes, BiomeGenBase.biomeList, ...). Hidden fields
     * are still returned by the JVM's own unfiltered list.
     */
    public static Field getDeclaredField(Class<?> type, String name) throws NoSuchFieldException {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            if (type.getClassLoader() != null) { // only JDK classes have hidden fields
                throw e;
            }
            try {
                Method all = Class.class.getDeclaredMethod("getDeclaredFields0", boolean.class);
                setAccessible(all, true);
                for (Field field : (Field[]) all.invoke(type, false)) {
                    if (field.getName().equals(name)) {
                        return field;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                // fall through to the original error
            }
            throw e;
        }
    }

    /**
     * Replaces {@code field.set(target, value)} and the primitive setters (redirected by
     * RetrofitTransformer). Java 18+ refuses writes to final fields through reflection even when a
     * mod has cleared Field.modifiers, as 1.7.10 mods do to replace static final arrays; then the
     * value is written as Java 8 allowed.
     */
    public static void set(Field field, Object target, Object value) throws IllegalAccessException {
        try {
            field.set(target, value);
        } catch (IllegalAccessException e) {
            writeFinal(field, target, value, e);
        } catch (InternalError e) {
            writeFinal(field, target, value, e);
        }
    }

    private static void writeFinal(Field field, Object target, Object value, Throwable refused) throws IllegalAccessException {
        if (!java.lang.reflect.Modifier.isFinal(field.getModifiers()) && !isFinalDeclared(field)) {
            if (refused instanceof IllegalAccessException) {
                throw (IllegalAccessException) refused;
            }
            throw (InternalError) refused;
        }
        try {
            FinalFields.set(field, target, value);
        } catch (Exception e) {
            IllegalAccessException failure = new IllegalAccessException("Retrofit could not write final field " + field);
            failure.initCause(e);
            throw failure;
        }
    }

    /** The field's real modifiers (a mod may have cleared FINAL in the Field object). */
    private static boolean isFinalDeclared(Field field) {
        try {
            for (Field declared : field.getDeclaringClass().getDeclaredFields()) {
                if (declared.getName().equals(field.getName())) {
                    return java.lang.reflect.Modifier.isFinal(declared.getModifiers());
                }
            }
        } catch (SecurityException e) {
            // unknown
        }
        return false;
    }

    public static void setBoolean(Field field, Object target, boolean value) throws IllegalAccessException {
        try {
            field.setBoolean(target, value);
        } catch (IllegalAccessException e) {
            writeFinal(field, target, value, e);
        } catch (InternalError e) {
            writeFinal(field, target, value, e);
        }
    }

    public static void setByte(Field field, Object target, byte value) throws IllegalAccessException {
        try {
            field.setByte(target, value);
        } catch (IllegalAccessException e) {
            writeFinal(field, target, value, e);
        } catch (InternalError e) {
            writeFinal(field, target, value, e);
        }
    }

    public static void setChar(Field field, Object target, char value) throws IllegalAccessException {
        try {
            field.setChar(target, value);
        } catch (IllegalAccessException e) {
            writeFinal(field, target, value, e);
        } catch (InternalError e) {
            writeFinal(field, target, value, e);
        }
    }

    public static void setShort(Field field, Object target, short value) throws IllegalAccessException {
        try {
            field.setShort(target, value);
        } catch (IllegalAccessException e) {
            writeFinal(field, target, value, e);
        } catch (InternalError e) {
            writeFinal(field, target, value, e);
        }
    }

    public static void setInt(Field field, Object target, int value) throws IllegalAccessException {
        try {
            field.setInt(target, value);
        } catch (IllegalAccessException e) {
            writeFinal(field, target, value, e);
        } catch (InternalError e) {
            writeFinal(field, target, value, e);
        }
    }

    public static void setLong(Field field, Object target, long value) throws IllegalAccessException {
        try {
            field.setLong(target, value);
        } catch (IllegalAccessException e) {
            writeFinal(field, target, value, e);
        } catch (InternalError e) {
            writeFinal(field, target, value, e);
        }
    }

    public static void setFloat(Field field, Object target, float value) throws IllegalAccessException {
        try {
            field.setFloat(target, value);
        } catch (IllegalAccessException e) {
            writeFinal(field, target, value, e);
        } catch (InternalError e) {
            writeFinal(field, target, value, e);
        }
    }

    public static void setDouble(Field field, Object target, double value) throws IllegalAccessException {
        try {
            field.setDouble(target, value);
        } catch (IllegalAccessException e) {
            writeFinal(field, target, value, e);
        } catch (InternalError e) {
            writeFinal(field, target, value, e);
        }
    }

    /** Replaces {@code AccessibleObject.setAccessible(objects, flag)}. */
    public static void setAccessible(AccessibleObject[] objects, boolean flag) {
        for (AccessibleObject object : objects) {
            setAccessible(object, flag);
        }
    }
}
