package net.i2p.i2ptunnel.streamr;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.udp.*;
import net.i2p.util.Log;

/**
 * Multi-source data distributor that forwards data to multiple sink destinations
 */
public class MultiSource implements Source, Sink {
    private Sink sink;
    private final List<MSink> sinks;
    private final Log log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());

    /**
     *  Creates a new multi-source distributor.
     *  @since 0.9.53
     */
    public MultiSource() {
        this.sinks = new CopyOnWriteArrayList<>();
    }

    /**
     *  Sets the destination sink for all outgoing data.
     *  @param sink the sink to receive distributed data
     *  @since 0.9.53
     */
    @Override
    public void setSink(Sink sink) {
        this.sink = sink;
    }

    /**
     *  Initializes the multi-source. No-op for this implementation.
     *  @since 0.9.53
     */
    @Override
    public void start() { /* no-op */ }

    /**
     *  Stops the multi-source and clears all registered sinks.
     *  @since 0.9.53
     */
    public void stop() {
        this.sinks.clear();
    }

    /**
     *  May throw RuntimeException from underlying sinks
     *  @throws RuntimeException if one of the registered sinks fails to send the data
     *  @since 0.9.53 added fromPort and toPort parameters
     */
    public void send(Destination ignoredFrom, int ignoredFromPort, int ignoredToPort, byte[] data) {
        if (sinks.isEmpty()) {
            if (log.shouldDebug())
                log.debug("No subscribers to send " + data.length + " bytes to");
            return;
        }
        if (log.shouldDebug())
            log.debug("Sending " + data.length + " bytes to " + sinks.size() + " subscribers");

        for(MSink ms : this.sinks) {
            this.sink.send(ms.dest, ms.fromPort, ms.toPort, data);
        }
    }

    /**
     * range that receive each copy of the data
     *
     * @param ms the subscriber to deliver to, naming the destination and the port
     * @since 0.9.53 changed to MSink parameter
     */
    public void add(MSink ms) {
        sinks.add(ms);
    }

    /**
     * object identity
     *
     * @param ms the subscriber to drop; matched against the registered sinks by
     * @since 0.9.53 changed to MSink parameter
     */
    public void remove(MSink ms) {
        sinks.remove(ms);
    }

    /**
     *  Sink wrapper for multi-source destinations.
     *  @since 0.9.53
     */
    static class MSink {
        /** Destination the message is forwarded to. */
        public final Destination dest;
        /** Local port the stream arrived on. */
        public final int fromPort;
        /** Local port the stream should be forwarded from. */
        public final int toPort;

        /**
         * Create a forwarding entry for one destination.
         *
         * @param dest the destination to forward to
         * @param fromPort the local port the stream arrived on
         * @param toPort the local port to forward it from
         */
        public MSink(Destination dest, int fromPort, int toPort) {
            this.dest = dest; this.fromPort = fromPort; this.toPort = toPort;
        }

        /**
         * @return whether h code is present
         */
        @Override
        public int hashCode() {
            return dest.hashCode() | fromPort | (toPort << 16);
        }

        /**
         * equals.
         */
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof MSink))
                return false;
            MSink s = (MSink) o;
            return dest.equals(s.dest) && fromPort == s.fromPort && toPort == s.toPort;
        }

        /**
         * toString.
         */
        @Override
        public String toString() {
            return "from port " + fromPort + " to " + dest.toBase32() + ':' + toPort;

        }
    }
}
