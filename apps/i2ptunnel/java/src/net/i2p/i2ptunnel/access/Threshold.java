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

    /** ignored */
    private final int connections;
    /** ignored */
    private final int seconds;

    /** Threshold */
    Threshold(int connections, int seconds) {
        if (seconds < 1)
            throw new IllegalArgumentException("Threshold must be defined over at least 1 second");
        if (connections < 0)
            throw new IllegalArgumentException("Accesses cannot be negative");
        this.connections = connections;
        this.seconds = seconds;
    }

    /** @return the max connections */
    int getConnections() {
        return connections;
    }

    /** @return the time window seconds */
    int getSeconds() {
        return seconds;
    }
}
