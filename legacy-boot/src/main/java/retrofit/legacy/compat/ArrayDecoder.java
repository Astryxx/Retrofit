package retrofit.legacy.compat;

/**
 * Stands for Java 8's sun.nio.cs.ArrayDecoder, which Java 17 doesn't export: mods asked whether a
 * charset decoder is one to decode straight into an array (Minecraft Animated's model loader) and
 * failed with IllegalAccessError. No decoder is this, so they take their CharBuffer path.
 */
public interface ArrayDecoder {
    int decode(byte[] src, int offset, int length, char[] dst);
}
