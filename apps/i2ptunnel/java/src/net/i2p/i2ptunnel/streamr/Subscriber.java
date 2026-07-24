package net.i2p.i2ptunnel.streamr;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.udp.*;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * Server-mode subscriber that manages multiple data sources and handles incoming connections
 */
public class Subscriber implements Sink {

    private final I2PAppContext ctx = I2PAppContext.getGlobalContext();
    private final Log log = ctx.logManager().getLog(getClass());
    private final Map<MultiSource.MSink, Long> subscriptions;
    private final MultiSource multi;
    private final SimpleTimer2.TimedEvent timer;
    private volatile boolean timerRunning;

    private static final int MAX_SUBSCRIPTIONS = 10;
    private static final long EXPIRATION = (long) 60*1000;

    /**
     *  Creates a new subscriber for managing data source connections.
     *  @param multi the multi-source to register subscriptions with
     *  @since 0.9.53
     */
    public Subscriber(MultiSource multi) {
        this.multi = multi;
        // subscriptions
        this.subscriptions = new ConcurrentHashMap<>();
        timer = new Expire();
    }

    /**
     * Handles subscription or unsubscription requests. Does not really "send"
     * anywhere; instead interprets the data byte as a control command.
     *
     * @param dest the source destination to subscribe or unsubscribe
     * @param fromPort the source I2CP port (swapped for replies)
     * @param toPort the destination I2CP port (swapped for replies)
     * @param data must be a single byte: 0 to subscribe, 1 to unsubscribe
     * @since 0.9.53 added fromPort and toPort parameters
     */
    public void send(Destination dest, int fromPort, int toPort, byte[] data) {
        if(dest == null || data.length < 1) {
            // invalid packet
            if (log.shouldWarn())
                log.warn("Bad subscription from " + (dest == null ? "null" : dest.toBase32()) + ':' + fromPort);
        } else {
            // swap fromPort and toPort for the replies
            MultiSource.MSink ms = new MultiSource.MSink(dest, toPort, fromPort);
            int ctrl = data[0] & 0xff;
            if(ctrl == 0) {
                if (this.subscriptions.put(ms, Long.valueOf(ctx.clock().now())) == null) {
                    if (subscriptions.size() > MAX_SUBSCRIPTIONS) {
                        subscriptions.remove(ms);
                        if (log.shouldWarn())
                            log.warn("Too many subscriptions, denying: " + ms);
                        return;
                    }
                    // subscribe
                    if (log.shouldWarn())
                        log.warn("Add subscription: " + ms);
                    this.multi.add(ms);
                    if (!timerRunning) {
                        timer.reschedule(EXPIRATION);
                        timerRunning = true;
                    }
                } else {
                    if (log.shouldInfo())
                        log.info("Continue subscription: " + ms);
                }
            } else if(ctrl == 1) {
                // unsubscribe
                if (log.shouldWarn())
                    log.warn("Remove subscription: " + ms);
                if (subscriptions.remove(ms) != null)
                    multi.remove(ms);
            } else {
                // invalid packet
                if (log.shouldWarn())
                    log.warn("Bad subscription flag " + ctrl + " from " + ms);
            }
        }
    }

    /**
     * Timer event that expires stale subscriptions.
     *
     * @since 0.9.46
     */
    private class Expire extends SimpleTimer2.TimedEvent {

        public Expire() {
            super(ctx.simpleTimer2());
        }

        public void timeReached() {
            if (subscriptions.isEmpty()) {
                timerRunning = false;
                return;
            }
            long exp = ctx.clock().now() - EXPIRATION;
            for (Iterator<Map.Entry<MultiSource.MSink, Long>> iter = subscriptions.entrySet().iterator(); iter.hasNext(); ) {
                Map.Entry<MultiSource.MSink, Long> e = iter.next();
                long then = e.getValue().longValue();
                if (then < exp) {
                    MultiSource.MSink ms = e.getKey();
                    iter.remove();
                    multi.remove(ms);
                    if (log.shouldWarn())
                        log.warn("Expired subscription: " + ms);
                }
            }
            if (!subscriptions.isEmpty()) {
                schedule(EXPIRATION);
                timerRunning = true;
            } else {
                timerRunning = false;
            }
        }
    }
}
