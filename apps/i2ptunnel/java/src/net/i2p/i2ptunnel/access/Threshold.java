package net.i2p.i2ptunnel.access;

/**
 * Connection threshold definition.
 * <p>
 * Defined by maximum connection attempts over a time period in seconds.
 *
 */
class Threshold {

    /** A Threshold that is never breached. */
    static final Threshold ALLOW = new Threshold(Integer.MAX_VALUE, 1);
    /** A Threshold that is always breached. */
    static final Threshold DENY = new Threshold(0, 1);

    /** Connections allowed in the window. */
    private final int connections;
    /** Length of the window, in seconds. */
    private final int seconds;

    /**
     * Pairs an access count with the sliding window it is measured over,
     * rejecting a negative count or a window under one second.
     *
     * @param connections the number of accesses allowed in the window, not negative
     * @param seconds the length of the sliding window, at least 1
     */
    Threshold(int connections, int seconds) {
        if (seconds < 1)
            throw new IllegalArgumentException("Threshold must be defined over at least 1 second");
        if (connections < 0)
            throw new IllegalArgumentException("Accesses cannot be negative");
        this.connections = connections;
        this.seconds = seconds;
    }

    /**
     * The connection count that defines a breach of this threshold.
     *
     * @return the maximum connections permitted within the window
     */
    int getConnections() {
        return connections;
    }

    /**
     * The sliding window over which connecting attempts are counted.
     *
     * @return the window length in seconds, always at least 1
     */
    int getSeconds() {
        return seconds;
    }

    /**
     * Two thresholds are equal when both the connection count and the window
     * length match, so the access-filter reload can tell whether a listed
     * destination's rule actually changed and rebuild its tracker if so.
     *
     * @param o the object to compare
     * @return true if o is a Threshold with the same count and window
     */
    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof Threshold))
            return false;
        Threshold other = (Threshold) o;
        return connections == other.connections && seconds == other.seconds;
    }

    /**
     * Hash code consistent with {@link #equals}.
     *
     * @return hash of the count and window pair
     */
    @Override
    public int hashCode() {
        return connections * 31 + seconds;
    }
}
