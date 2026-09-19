package net.i2p.client.streaming.impl;

import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.I2PAppContext;
import net.i2p.util.SimpleTimer2;

/**
 * Per-destination timer for scheduling packet retransmissions.
 * Uses dedicated SimpleTimer2 shards (not the router-global one) to isolate
 * streaming timer events from contention with other subsystems.
 *
 * <p>The events are spread over {@link #SHARDS} independent timers, so a
 * connection whose timer queue is saturated by retransmission/ack events
 * cannot hold up an unrelated connection's handshake or keepalive. Each shard
 * keeps {@code SimpleTimer2}'s two worker threads, so a single long-running
 * event blocks only its own workers.
 *
 * @since 0.9
 */
public class RetransmissionTimer {

    /** Number of independent timer shards. Connections are assigned
     *  round-robin, halving the chance that concurrent connections share a
     *  timer queue. */
    private static final int SHARDS = 2;
    private final SimpleTimer2[] _shards;
    private final AtomicInteger _counter = new AtomicInteger();

    /**
     * New timer with dedicated SimpleTimer2 shards.
     * @param ctx the application context
     * @param name used for the timer thread names
     * @since 0.9
     */
    RetransmissionTimer(I2PAppContext ctx, String name) {
        _shards = new SimpleTimer2[SHARDS];
        for (int i = 0; i < SHARDS; i++)
            _shards[i] = new SimpleTimer2(ctx, name + '-' + i);
    }

    /**
     * A SimpleTimer2 instance for a new consumer.
     * Shards are handed out round-robin; a component that calls this once at
     * construction (a Connection, the packet queue, the accept handler, ...)
     * gets a stable shard for its lifetime. No caller depends on two calls
     * returning the same instance.
     * @return one of the dedicated SimpleTimer2 instances
     * @since 0.9
     */
    public SimpleTimer2 getSharedTimer() {
        return _shards[(_counter.getAndIncrement() & 0x7fffffff) % SHARDS];
    }

    /**
     * Schedule an event via one of the dedicated timers.
     * @param event the event
     * @param timeoutMs delay in ms
     * @since 0.9.70+
     */
    public void addEvent(final SimpleTimer2.TimedEvent event, final long timeoutMs) {
        getSharedTimer().addEvent(event, timeoutMs);
    }

    /**
     * Stop all dedicated timers.
     * @since 0.9
     */
    public void stop() {
        for (SimpleTimer2 t : _shards) {t.stop();}
    }
}
