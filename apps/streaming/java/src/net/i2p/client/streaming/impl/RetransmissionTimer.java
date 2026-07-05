package net.i2p.client.streaming.impl;

import net.i2p.I2PAppContext;
import net.i2p.util.SimpleTimer2;

/**
 * Per-destination timer for scheduling packet retransmissions.
 * Each connection gets its own timer instance to avoid contention.
 */
public class RetransmissionTimer extends SimpleTimer2 {

    /**
     *  @deprecated Don't use this to prestart threads, this is no longer a static instance
     *  @return a new instance as of 0.9
     */
    @Deprecated
    public static final RetransmissionTimer getInstance() {
        return new RetransmissionTimer(I2PAppContext.getGlobalContext(), "RetransmissionTimer");
    }


    /**
     * Creates a new retransmission timer.
     *
     * @param ctx the application context
     * @param name the timer thread name
     * @since 0.9
     */
    RetransmissionTimer(I2PAppContext ctx, String name) {
        super(ctx, name, false);
    }
}
