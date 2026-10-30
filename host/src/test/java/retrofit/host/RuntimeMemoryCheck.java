package retrofit.host;
import java.nio.ByteBuffer;
import java.lang.ref.Reference;
public final class RuntimeMemoryCheck {
    public static void main(String[] args) {
        var before=RuntimeMemory.capture();
        var retained=ByteBuffer.allocateDirect(2*1024*1024);
        var after=RuntimeMemory.capture();
        if(after.directBytes()-before.directBytes()<retained.capacity()) throw new AssertionError("direct allocation not observed");
        if(after.directCount()<=before.directCount()) throw new AssertionError("buffer count not observed");
        if(after.maxHeapBytes()<=0 || after.heapBytes()<0 || after.collections()<before.collections()) throw new AssertionError("invalid snapshot");
        Reference.reachabilityFence(retained);
        System.out.println("Memory telemetry observes retained NIO allocation: "+before+" -> "+after);
    }
}
