package retrofit.legacy.boot;

import java.io.InputStream;

/** Proves the dedicated-server path with every original LWJGL resource deliberately absent. */
public final class HeadlessInputCheck {
    private static final class WithoutLwjgl extends ClassLoader {
        WithoutLwjgl() { super(HeadlessInputCheck.class.getClassLoader()); }
        @Override public InputStream getResourceAsStream(String name) {
            return name.startsWith("org/lwjgl/") ? null : super.getResourceAsStream(name);
        }
        Class<?> shim(String name) throws Exception {
            byte[] bytes = LwjglShims.generate(name,this);
            return defineClass(name,bytes,0,bytes.length);
        }
    }

    public static void main(String[] args) throws Exception {
        WithoutLwjgl loader = new WithoutLwjgl();
        Class<?> keyboard = loader.shim("retrofit.legacy.lwjgl.input.Keyboard");
        if (!"NONE".equals(keyboard.getMethod("getKeyName",int.class).invoke(null,0))) throw new AssertionError("unbound key name");
        if (!"SPACE".equals(keyboard.getMethod("getKeyName",int.class).invoke(null,57))) throw new AssertionError("key name");
        if (!Integer.valueOf(57).equals(keyboard.getMethod("getKeyIndex",String.class).invoke(null,"SPACE"))) throw new AssertionError("key index");
        if (!Boolean.FALSE.equals(keyboard.getMethod("isKeyDown",int.class).invoke(null,57))) throw new AssertionError("headless key state");
        Class<?> mouse = loader.shim("retrofit.legacy.lwjgl.input.Mouse");
        if (!Boolean.FALSE.equals(mouse.getMethod("isButtonDown",int.class).invoke(null,0))) throw new AssertionError("headless button state");
        System.out.println("Headless input: key names, indexes and neutral key/button state passed without LWJGL resources");
    }
}
