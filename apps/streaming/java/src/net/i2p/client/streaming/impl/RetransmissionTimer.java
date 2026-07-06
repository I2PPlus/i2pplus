package net.i2p.client.streaming.impl;

import net.i2p.I2PAppContext;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SimpleTimer2;

/**
 * Per-destination timer for scheduling packet retransmissions.
 * Delegates to the shared router SimpleTimer2 to avoid creating
 * per-session thread pools (each of which would create 16-24 threads).
 *
 * @since 0.9
 */
public class RetransmissionTimer {

    private final SimpleTimer2 _shared;

    /**
     * Creates a new retransmission timer backed by the shared pool.
     *
     * @param ctx the application context
     * @param name the timer name (unused, kept for API compat)
     * @since 0.9
     */
    RetransmissionTimer(I2PAppContext ctx, String name) {
        _shared = ctx.simpleTimer2();
    }

    /**
     *  @return the shared SimpleTimer2 instance for use in TimedEvent constructors
     *  @since 0.9
     */
    public SimpleTimer2 getSharedTimer() {
        return _shared;
    }

    /**
     *  Delegate to shared timer.
     *  @since 0.9
     */
    public void addEvent(final SimpleTimer.TimedEvent event, final long timeoutMs) {
        _shared.addEvent(event, timeoutMs);
    }

    /**
     *  Delegate to shared timer.
     *  @since 0.9
     */
    public void addPeriodicEvent(final SimpleTimer.TimedEvent event, final long timeoutMs) {
        _shared.addPeriodicEvent(event, timeoutMs);
    }

    /**
     *  Delegate to shared timer.
     *  @since 0.9
     */
    public void addPeriodicEvent(final SimpleTimer.TimedEvent event, final long delay, final long timeoutMs) {
        _shared.addPeriodicEvent(event, delay, timeoutMs);
    }

    /**
     *  No-op — the shared timer lives for the router lifetime.
     *  @since 0.9
     */
    public void stop() {
        // no-op: shared timer cannot be stopped per-session
    }
}
