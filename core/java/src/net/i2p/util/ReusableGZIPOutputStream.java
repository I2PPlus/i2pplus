package net.i2p.util;
import net.i2p.data.DataHelper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.Deflater;

/**
 * Provide a cache of reusable GZIP streams, each handling up to 40 KB output without
 * expansion.
 * <p>
 * This compresses to memory only. Retrieve the compressed data with {@link #getData()}.
 * There is no facility to compress to an output stream.
 * <p>
 * Do NOT use this for compression of unlimited-size data, as it will
 * expand, but never release, the BAOS memory buffer.
 */
public class ReusableGZIPOutputStream extends ResettableGZIPOutputStream {
        /**
     * On Apache Harmony 5.0M13 the Deflater does not work after reset(), and
     * neither does Android's - on those platforms every instance is destroyed
     * on release rather than reset and pooled, so caching is disabled there.
     */
    private static final boolean ENABLE_CACHING = !(SystemVersion.isApache() ||
                                                   SystemVersion.isAndroid());
    /** Streams ready for reuse. */
    private static final LinkedBlockingQueue<ReusableGZIPOutputStream> _available;

    static {
        if (ENABLE_CACHING) _available = new LinkedBlockingQueue<>(16);
        else _available = null;
    }

    /**
     * Pull a cached instance.
     * @return a stream from the pool, or a newly allocated one when the pool is empty or caching is disabled
     */
    public static ReusableGZIPOutputStream acquire() {
        ReusableGZIPOutputStream rv = null;
        if (ENABLE_CACHING) rv = _available.poll();
        if (rv == null) {
            rv = new ReusableGZIPOutputStream();
        }
        return rv;
    }

    /**
     * Release an instance back into the cache (this will discard any
     * state).
     * @param out the stream to return to the pool; it is destroyed instead if the pool is disabled or full
     */
    public static void release(ReusableGZIPOutputStream out) {
        boolean cached;
        if (ENABLE_CACHING) {
            out.reset();
            cached = _available.offer(out);
        } else {
            cached = false;
        }
        if (!cached) {
            try {
                out.destroy();
            } catch (IOException ioe) { /* ignored */ }
        }
    }

    /** Compressed bytes waiting to be written out. */
    private final ByteArrayOutputStream _buffer;

    /** Makes a stream with an empty buffer. */
    private ReusableGZIPOutputStream() {
        super(new ByteArrayOutputStream(DataHelper.MAX_UNCOMPRESSED));
        _buffer = (ByteArrayOutputStream) out;
    }

    /** Clear the data so we can start again afresh. */
    @Override
    public void reset() {
        super.reset();
        _buffer.reset();
        def.setLevel(Deflater.BEST_COMPRESSION);
    }

    /**
     * Configure the compression level.
     * @param level a {@link Deflater} constant from NO_COMPRESSION to BEST_COMPRESSION
     */
    public void setLevel(int level) {
        def.setLevel(level);
    }

    /**
     * Pull the contents of the stream written.
     * @return the compressed bytes accumulated since the last {@link #reset()}
     */
    public byte[] getData() {
        return _buffer.toByteArray();
    }

    /**
     * Clear the cache.
     *
     * @since 0.9.21
     */
    public static void clearCache() {
        if (ENABLE_CACHING) _available.clear();
    }

}
