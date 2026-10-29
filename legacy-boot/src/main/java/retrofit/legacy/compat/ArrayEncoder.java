package retrofit.legacy.compat;

/** Stands for Java 8's sun.nio.cs.ArrayEncoder (see ArrayDecoder). */
public interface ArrayEncoder {
    int encode(char[] src, int offset, int length, byte[] dst);
}
