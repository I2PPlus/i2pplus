package net.i2p.i2ptunnel.access;

import net.i2p.data.Hash;

/**
 * Tracks connection attempts for a specific remote destination.
 *
 * @since 0.9.40
 */
class DestTracker {

    private final Hash hash;
    private final Threshold threshold;
    private final AccessCounter counter;

    /**
     * Create a tracker for one remote destination with an empty access history.
     *
     * @param hash hash of the remote destination
     * @param threshold threshold defined in the access rule
     */
    DestTracker(Hash hash, Threshold threshold) {
        this.hash = hash;
        this.threshold = threshold;
        this.counter = new AccessCounter();
    }

    /**
     * The destination this tracker counts attempts for.
     *
     * @return the destination hash
     */
    Hash getHash() {
        return hash;
    }

    /**
     * The recorded access history, for callers that need to inspect it.
     *
     * @return the access counter
     */
    AccessCounter getCounter() {
        return counter;
    }

    /**
     * Test the recorded attempts against a threshold, ageing out any that
     * fall outside the rule's window.
     *
     * @param threshold the rule threshold tested against the recorded attempts
     * @param now current time, used to age out attempts outside the window
     * @return true if the counter has breached the given threshold
     * @since 0.9.70+
     */
    synchronized boolean isBreached(Threshold threshold, long now) {
        return counter.isBreached(threshold, now);
    }

    /**
     * Record a connection attempt at this destination and test the result.
     *
     * @param now the time of the connection attempt being recorded
     * @return true if this access causes threshold breach
     */
    synchronized boolean recordAccess(long now) {
        counter.recordAccess(now);
        return counter.isBreached(threshold,now);
    }

    /**
     * Drop access records that have aged out of the rule's window.
     *
     * @param olderThan purge entries older than this timestamp
     * @return true if nothing is left in the access history
     */
    synchronized boolean purge(long olderThan) {
        return counter.purge(olderThan);
    }
}
