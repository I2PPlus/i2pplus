package net.i2p.client.streaming.impl;

import net.i2p.I2PAppContext;
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
     * @param ctx the application context
     * @param name unused, kept for API compat
     * @since 0.9
     */
    RetransmissionTimer(I2PAppContext ctx, String name) {
        _shared = ctx.simpleTimer2();
    }

    /**
     * @return the shared SimpleTimer2 instance
     * @since 0.9
     */
    public SimpleTimer2 getSharedTimer() {
        return _shared;
    }

    /**
     * Schedule an event via the shared timer.
     * @param event the event to schedule
     * @param timeoutMs delay in ms
     * @since 0.9.71
     */
    public void addEvent(final SimpleTimer2.TimedEvent event, final long timeoutMs) {
        _shared.addEvent(event, timeoutMs);
    }

    /**
     * Schedule a periodic event via the shared timer.
     * @param event the event to schedule
     * @param timeoutMs period in ms
     * @since 0.9.71
     */
    public void addPeriodicEvent(final SimpleTimer2.TimedEvent event, final long timeoutMs) {
        _shared.addPeriodicEvent(event, timeoutMs);
    }

    /**
     * Schedule a periodic event via the shared timer.
     * @param event the event to schedule
     * @param delay first execution delay in ms
     * @param timeoutMs period in ms
     * @since 0.9.71
     */
    public void addPeriodicEvent(final SimpleTimer2.TimedEvent event, final long delay, final long timeoutMs) {
        _shared.addPeriodicEvent(event, delay, timeoutMs);
    }

    /**
     * No-op — the shared timer lives for the router lifetime.
     * @since 0.9
     */
    public void stop() {
    }
}
