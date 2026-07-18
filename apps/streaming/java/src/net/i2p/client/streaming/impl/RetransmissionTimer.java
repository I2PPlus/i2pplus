package net.i2p.client.streaming.impl;

import net.i2p.I2PAppContext;
import net.i2p.util.SimpleTimer2;

/**
 * Per-destination timer for scheduling packet retransmissions.
 * Uses a dedicated SimpleTimer2 (not the router-global one) to isolate
 * streaming timer events from contention with other subsystems.
 *
 * @since 0.9
 */
public class RetransmissionTimer {

    private final SimpleTimer2 _dedicated;

    /**
     * @param ctx the application context
     * @param name used for the timer thread name
     * @since 0.9
     */
    RetransmissionTimer(I2PAppContext ctx, String name) {
        _dedicated = new SimpleTimer2(ctx, name);
    }

    /**
     * @return the dedicated SimpleTimer2 instance
     * @since 0.9
     */
    public SimpleTimer2 getSharedTimer() {
        return _dedicated;
    }

    /**
     * Schedule an event via the dedicated timer.
     * @param event the event to schedule
     * @param timeoutMs delay in ms
     * @since 0.9.70+
     */
    public void addEvent(final SimpleTimer2.TimedEvent event, final long timeoutMs) {
        _dedicated.addEvent(event, timeoutMs);
    }

    /**
     * Schedule a periodic event via the dedicated timer.
     * @param event the event to schedule
     * @param timeoutMs period in ms
     * @since 0.9.70+
     */
    public void addPeriodicEvent(final SimpleTimer2.TimedEvent event, final long timeoutMs) {
        _dedicated.addPeriodicEvent(event, timeoutMs);
    }

    /**
     * Schedule a periodic event via the dedicated timer.
     * @param event the event to schedule
     * @param delay first execution delay in ms
     * @param timeoutMs period in ms
     * @since 0.9.70+
     */
    public void addPeriodicEvent(final SimpleTimer2.TimedEvent event, final long delay, final long timeoutMs) {
        _dedicated.addPeriodicEvent(event, delay, timeoutMs);
    }

    /**
     * Stop the dedicated timer.
     * @since 0.9
     */
    public void stop() {
        _dedicated.stop();
    }
}
