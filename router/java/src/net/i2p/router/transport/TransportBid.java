package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * Represents a transport's bid for message delivery cost.
 * Provide a bid for how much it would "cost" to transfer a message of a
 * particular peer
 *
 */
public class TransportBid {
    private int _latencyMs;
    private Transport _transport;
    public static final int TRANSIENT_FAIL = 999999;
    public TransportBid() {_latencyMs = -1;}

    /**
     * How long this transport thinks it would take to send the message
     * This is the actual bid value, lower is better, and it doesn't really have
     * anything to do with latency.
     */
    public int getLatencyMs() {return _latencyMs;}
    public void setLatencyMs(int milliseconds) {_latencyMs = milliseconds;}

    /**
     * Specifies the transport that offered this bid
     */
    public Transport getTransport() {return _transport;}
    public void setTransport(Transport transport) {_transport = transport;}
}
