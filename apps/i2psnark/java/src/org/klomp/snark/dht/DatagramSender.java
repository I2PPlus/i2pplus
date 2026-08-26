package org.klomp.snark.dht;

import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.SendMessageOptions;
import net.i2p.data.Destination;

import org.klomp.snark.BandwidthGraph;

/**
 * Shared low-level datagram send path for the DHT and UDP tracker clients.
 * Wraps the session send call with the common protocol selection (repliable
 * datagrams on PROTO_DATAGRAM vs raw datagrams on PROTO_DATAGRAM_RAW), so the
 * two transports cannot drift apart on port/protocol pairing.
 *
 * @since 0.9.71+
 */
public final class DatagramSender {

    private DatagramSender() {}

    /**
     * Send a pre-wrapped datagram payload through the session.
     *
     * @param session the session to send through
     * @param opts the send options (date, lease set, crypto tags)
     * @param dest the destination to send to
     * @param payload the wrapped datagram payload, sent in full
     * @param fromPort the port to send from
     * @param toPort the port to send to
     * @param repliable true for a repliable datagram (PROTO_DATAGRAM), false for raw
     * @return success
     * @throws I2PSessionException on session failure
     */
    public static boolean send(
            I2PSession session,
            SendMessageOptions opts,
            Destination dest,
            byte[] payload,
            int fromPort,
            int toPort,
            boolean repliable)
            throws I2PSessionException {
        boolean success = session.sendMessage(
                dest,
                payload,
                0,
                payload.length,
                repliable ? I2PSession.PROTO_DATAGRAM : I2PSession.PROTO_DATAGRAM_RAW,
                fromPort,
                toPort,
                opts);
        if (success) {BandwidthGraph.datagramSent(payload.length);}
        return success;
    }
}
