package net.i2p.i2ptunnel.access;

/**
 * Connection threshold definition.
 * <p>
 * Defined by maximum connection attempts over a time period in seconds.
 *
 *
 */
class Threshold {

    /** A Threshold that is never breached */
    static final Threshold ALLOW = new Threshold(Integer.MAX_VALUE, 1);
    /** A Threshold that is always breached */
    static final Threshold DENY = new Threshold(0, 1);

    private final int connections;
    private final int seconds;

    /**
     * Threshold
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
}
