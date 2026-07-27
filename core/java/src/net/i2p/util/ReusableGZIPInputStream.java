package net.i2p.util;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Provide a cache of reusable GZIP unzipper streams.
 * This provides stream output only, and therefore can handle unlimited size.
 */
public class ReusableGZIPInputStream extends ResettableGZIPInputStream {
    // Apache Harmony 5.0M13 Deflater doesn't work after reset()
    // Neither does Android
    private static final boolean ENABLE_CACHING = !(SystemVersion.isApache() || SystemVersion.isAndroid());
    private static final LinkedBlockingQueue<ReusableGZIPInputStream> _available;

    static {
        if (ENABLE_CACHING) _available = new LinkedBlockingQueue<>(8);
        else _available = null;
    }

    /**
     * Pull a cached instance
     */
    public static ReusableGZIPInputStream acquire() {
        ReusableGZIPInputStream rv = null;
        // Apache Harmony 5.0M13 Deflater doesn't work after reset()
        if (ENABLE_CACHING) rv = _available.poll();
        if (rv == null) {
            rv = new ReusableGZIPInputStream();
        }
        return rv;
    }

    /**
     * Release an instance back into the cache (this will reset the
     * state)
     */
    public static void release(ReusableGZIPInputStream released) {
        boolean cached;
        if (ENABLE_CACHING) {
            cached = _available.offer(released);
        } else {
            cached = false;
        }
        if (!cached) {
            try {
                released.destroy();
            } catch (IOException ioe) { /* ignored */ }
        }
    }

    private ReusableGZIPInputStream() {
        super();
    }

    /**
     *  Clear the cache.
     *
     *  @since 0.9.21
     */
    public static void clearCache() {
        if (_available != null) _available.clear();
    }

}
