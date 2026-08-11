package net.i2p.i2ptunnel.udp;

import net.i2p.data.Destination;

/**
 * Sink interface for streaming data destinations.
 */
public interface Sink {
    /**
     *  Sends a datagram to the given destination.
     *
     *  @param fromPort I2CP source port, 0-65535
     *  @param toPort I2CP destination port, 0-65535
     *  @param src some implementations may ignore, may be null in some implementations
     *  @throws RuntimeException in some implementations
     *  @since 0.9.53 added fromPort and toPort parameters, breaking change, sorry
     */
    public void send(Destination src, int fromPort, int toPort, byte[] data);
}
