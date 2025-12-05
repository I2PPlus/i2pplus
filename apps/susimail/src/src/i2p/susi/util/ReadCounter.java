package i2p.susi.util;

/**
 * Interface for counting bytes read or skipped.
 *
 * @since 0.9.34
 */
public interface ReadCounter {

    /**
     *  The total number of bytes that have been read or skipped
     */
    public long getRead();
}
