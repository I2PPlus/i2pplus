package net.i2p.time;

/**
 * Dummy. Real thing moved to net.i2p.router.time.RouterTimestamper.
 * What remains here is essentially an interface,
 * containing only what is needed to keep external apps
 * compiled with old libs from breaking, since
 * net.i2p.util.Clock returns a Timestamper in getTimestamper()
 *
 * Deprecated outside of the router.
 */
public class Timestamper implements Runnable {

    /** No-op constructor for source compatibility. */
    public Timestamper() { /* nop */ }

    /** No-op; initialization is handled by RouterTimestamper. */
    public void waitForInitialization() { /* nop */ }

    /**
     *  Update the time immediately.
     *  Dummy
     *
     *  @since 0.8.8
     */
    public void timestampNow() { /* nop */ }

    /** No-op run loop; the router handles time updates. */
    @Override
    public void run() { /* nop */ }

    /**
     * Interface to receive update notifications for when we query the time
     * Only used by Clock.
     * stratum parameter added in 0.7.12.
     * If there were any users outside of the tree, this broke compatibility, sorry.
     */
    public interface UpdateListener {
        /**
         * Current time as updated, with its stratum, for Clock.
         *
         * @param now the current time
         * @param stratum 1-15, 1 being the best (added in 0.7.12)
         */
        public void setNow(long now, int stratum);
    }
}
