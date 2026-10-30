package retrofit.host;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Read-only JVM memory telemetry.
 * No collection requests, background threads, native buffer ownership or game-state changes. */
public final class RuntimeMemory {
    private static final Logger LOGGER = LoggerFactory.getLogger("Retrofit/Memory");
    private static final List<BufferPoolMXBean> POOLS = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
    private static final boolean ENABLED = Boolean.getBoolean("retrofit.memoryLog");
    private static final AtomicLong LAST_LOG = new AtomicLong();
    private RuntimeMemory() {}

    public record Snapshot(long heapBytes, long maxHeapBytes, long directBytes, long directCount,
                           long mappedBytes, long collections, long collectionMillis) {}

    public static Snapshot capture() {
        Runtime runtime = Runtime.getRuntime();
        long direct=0, count=0, mapped=0, collections=0, millis=0;
        for (var pool : POOLS) {
            if (pool.getName().equals("direct")) { direct+=Math.max(0,pool.getMemoryUsed()); count+=Math.max(0,pool.getCount()); }
            else if (pool.getName().startsWith("mapped")) mapped+=Math.max(0,pool.getMemoryUsed());
        }
        for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            collections+=Math.max(0,collector.getCollectionCount()); millis+=Math.max(0,collector.getCollectionTime());
        }
        return new Snapshot(runtime.totalMemory()-runtime.freeMemory(),runtime.maxMemory(),direct,count,mapped,collections,millis);
    }

    /** Opt-in troubleshooting on either side; an integrated server and client share the throttle. */
    public static void logIfDue() {
        if (!ENABLED) return;
        long now=System.nanoTime(), previous=LAST_LOG.get();
        if (previous!=0 && now-previous < 5_000_000_000L) return;
        if (!LAST_LOG.compareAndSet(previous,now)) return;
        LOGGER.info("JVM memory {} (direct/mapped are JVM NIO pools, not all native/GPU memory)",capture());
    }
}
