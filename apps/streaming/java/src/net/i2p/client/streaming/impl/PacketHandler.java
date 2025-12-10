package net.i2p.client.streaming.impl;

import java.text.SimpleDateFormat;
import java.util.Date;
import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.data.ByteArray;
import net.i2p.data.Destination;
import net.i2p.data.SigningPublicKey;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/**
 * Handles incoming packets by dispatching them to the appropriate connection,
 * server socket, or responding with a reset (RST) packet if no valid connection is found.
 * <p>
 * Packet flow:
 * I2PSession -&gt; MessageHandler -&gt; PacketHandler -&gt; ConnectionPacketHandler -&gt; MessageInputStream.
 */
class PacketHandler {
    private final ConnectionManager manager;
    private final I2PAppContext context;
    private final Log log;
    private final ByteCache cache = ByteCache.getInstance(128, 32 * 1024);

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS");

    public PacketHandler(I2PAppContext ctx, ConnectionManager mgr) {
        this.manager = mgr;
        this.context = ctx;
        this.log = ctx.logManager().getLog(PacketHandler.class);
    }

    /**
     * Receives a packet and dispatches it to the appropriate handler.
     *
     * @param packet the packet to process
     */
    void receivePacket(Packet packet) {
        receivePacketDirect(packet, true);
    }

    /**
     * Processes the packet immediately, optionally queuing if no connection is found.
     *
     * @param packet          the packet to process
     * @param queueIfNoConn   queue the packet if no matching connection exists
     */
    void receivePacketDirect(Packet packet, boolean queueIfNoConn) {
        long sendId = packet.getSendStreamId();
        Connection con = (sendId > 0) ? manager.getConnectionByInboundId(sendId) : null;

        if (con != null) {
            if (log.shouldDebug())
                displayPacket(packet, "RECV", "WSIZE " + con.getOptions().getWindowSize() + "; RTO " + con.getOptions().getRTO());
            receiveKnownConnection(con, packet);
        } else {
            if (log.shouldDebug())
                displayPacket(packet, "UNKN", null);
            receiveUnknownConnection(packet, sendId, queueIfNoConn);
        }
    }

    /**
     * Logs the provided packet at debug level with a timestamp.
     *
     * @param packet the packet to log
     * @param prefix text prefix (e.g., "RECV" or "UNKN")
     * @param suffix additional info appended after the packet string, may be null
     */
    void displayPacket(Packet packet, String prefix, String suffix) {
        StringBuilder buf = new StringBuilder(256);
        synchronized (DATE_FORMAT) {
            buf.append(DATE_FORMAT.format(new Date()));
        }
        buf.append(": ").append(prefix).append(" ").append(packet.toString());
        if (suffix != null) {
            buf.append(" ").append(suffix);
        }
        log.debug(buf.toString());
    }

    /**
     * Process a packet that matches a known connection.
     *
     * @param con    the connection this packet belongs to
     * @param packet the packet to process
     */
    private void receiveKnownConnection(Connection con, Packet packet) {
        if (I2PSocketManagerFull.pcapWriter != null &&
            context.getBooleanProperty(I2PSocketManagerFull.PROP_PCAP)) {
            packet.logTCPDump(con);
        }

        if (packet.isFlagSet(Packet.FLAG_ECHO)) {
            handleEchoPacket(con, packet);
            return;
        }

        if (isValidMatch(con.getSendStreamId(), packet.getReceiveStreamId())) {
            try {
                con.getPacketHandler().receivePacket(packet, con);
            } catch (I2PException ie) {
                if (log.shouldWarn())
                    log.warn("Received forged packet for " + con, ie);
            }
            return;
        }

        if (packet.isFlagSet(Packet.FLAG_RESET)) {
            try {
                con.getPacketHandler().receivePacket(packet, con);
            } catch (I2PException ie) {
                if (log.shouldWarn())
                    log.warn("Received forged reset for " + con, ie);
            }
            return;
        }

        handleMisroutedPacket(con, packet);
    }

    /**
     * Handles echo packets on known connections.
     */
    private void handleEchoPacket(Connection con, Packet packet) {
        if (packet == null || con == null) {return;}
        if (packet.getSendStreamId() > 0) {
            if (con.getOptions().getAnswerPings()) {receivePing(con, packet);}
            else if (log.shouldWarn()) {
                log.warn("Dropping ECHO packet for [" + con + "] -> " + packet);
            }
        } else if (packet.getReceiveStreamId() > 0) {receivePong(packet);}
        else if (log.shouldWarn()) {
            log.warn("Received ECHO packet " + " with no StreamIDs for [" + con + "] -> " + packet);
        }
        packet.releasePayload();
    }

    /**
     * Handles a packet that appears on a known connection, but with stream ID mismatches or other anomalies.
     */
    private void handleMisroutedPacket(Connection con, Packet packet) {
        if (packet == null || con == null) {return;}
        long oldId = con.getSendStreamId();

        if ((con.getSendStreamId() <= 0) ||
            (con.getSendStreamId() == packet.getReceiveStreamId()) ||
            (packet.getSequenceNum() <= ConnectionOptions.MIN_WINDOW_SIZE)) {

            if (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                if (oldId <= 0) {
                    con.setSendStreamId(packet.getReceiveStreamId());
                    SigningPublicKey spk = packet.getTransientSPK();
                    if (spk != null)
                        con.setRemoteTransientSPK(spk);
                } else if (oldId != packet.getReceiveStreamId()) {
                    if (log.shouldWarn())
                        log.warn("Received SYN packet with wrong IDs for [" + con + "]");
                    sendReset(packet);
                    packet.releasePayload();
                    return;
                }
            }

            try {
                con.getPacketHandler().receivePacket(packet, con);
            } catch (I2PException ie) {
                if (log.shouldWarn()) {
                    log.warn("Signature verification failed for " + con + " / " + oldId + " -> " + packet, ie);
                }
                if (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                    sendResetUnverified(packet);
                }
            }
        } else if (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
            if (log.shouldWarn())
                log.warn("Received SYN packet with wrong IDs -> Sending RESET...");
            sendReset(packet);
            packet.releasePayload();
        } else {
            if (!con.getResetSent()) {
                if (log.shouldWarn()) {
                    StringBuilder buf = new StringBuilder(512);
                    buf.append("Received packet on the wrong stream: ").append(packet).append("\nConnection:\n").append(con)
                       .append("\nAll connections:");
                    for (Connection c : manager.listConnections()) {
                        buf.append('\n').append(c);
                    }
                    log.warn(buf.toString(), new Exception("Wrong stream"));
                }
            }
            packet.releasePayload();
        }
    }

    /**
     * Sends a reset packet back to the sender if the incoming packet is verified.
     *
     * @param packet the packet to respond to with a reset
     */
    private void sendReset(Packet packet) {
        if (packet == null) {return;}
        Destination from = packet.getOptionalFrom();
        if (from == null) {return;}

        ByteArray ba = cache.acquire();
        boolean verified = packet.verifySignature(context, ba.getData());
        cache.release(ba);

        if (!verified) {
            if (log.shouldWarn())
                log.warn("Cannot send reset due to spoofed packet -> " + packet);
            return;
        }
        sendResetUnverified(packet);
    }

    /**
     * Sends a reset packet back to the sender without verifying the packet signature.
     * Packet MUST have a FROM option.
     *
     * @param packet the packet to respond to with a reset
     * @since 0.9.39
     */
    private void sendResetUnverified(Packet packet) {
        PacketLocal reply = new PacketLocal(context, packet.getOptionalFrom(), packet.getSession());
        reply.setFlag(Packet.FLAG_RESET);
        reply.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
        reply.setSendStreamId(packet.getReceiveStreamId());
        reply.setReceiveStreamId(packet.getSendStreamId());
        reply.setLocalPort(packet.getLocalPort());
        reply.setRemotePort(packet.getRemotePort());

        manager.getPacketQueue().enqueue(reply);
    }

    /**
     * Processes a packet that does not match any known connection.
     *
     * @param packet        the unmatched packet
     * @param sendId        stream ID of the sender
     * @param queueIfNoConn whether to queue the packet if connection is not found
     */
    private void receiveUnknownConnection(Packet packet, long sendId, boolean queueIfNoConn) {
        if (packet.isFlagSet(Packet.FLAG_ECHO)) {
            handleEchoOnUnknown(packet);
            return;
        }

        if (log.shouldInfo() && !packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
            log.info("Received packet on unknown stream (not ECHO/SYN) -> " + packet);
        }

        if (sendId <= 0) {
            Connection con = manager.getConnectionByOutboundId(packet.getReceiveStreamId());
            if (con != null) {
                if ((con.getHighestAckedThrough() <= 5) && (packet.getSequenceNum() <= 5)) {
                    if (log.shouldInfo())
                        log.info("Received additional packet without SendStreamID after the SYN -> " + packet + "\n* " + con);
                } else if (log.shouldWarn()) {
                    log.warn("Received while ACK of SYN was in flight\n* " + con + ": " + packet + " ACKed: " + con.getAckedPackets());
                }
                receiveKnownConnection(con, packet);
                return;
            }
        } else {
            if (log.shouldDebug()) {
                boolean recent = manager.wasRecentlyClosed(packet.getSendStreamId());
                log.debug("Dropping packet with SendStreamID but no connection" + (recent ? " - Recently disconnected" : ""));
            }
            packet.releasePayload();
            return;
        }

        if (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
            manager.getConnectionHandler().receiveNewSyn(packet);
        } else if (queueIfNoConn) {
            if (log.shouldWarn())
                log.warn("Packet " + packet + " belongs to no connections, putting on SYN queue...");
            if (log.shouldDebug()) {
                StringBuilder buf = new StringBuilder(128);
                for (Connection c : manager.listConnections()) {
                    buf.append(c.toString()).append(" ");
                }
                log.debug("Connections: " + buf + " SendID: " + Packet.toId(sendId));
            }
            manager.getConnectionHandler().receiveNewSyn(packet);
        } else {
            if (I2PSocketManagerFull.pcapWriter != null &&
                context.getBooleanProperty(I2PSocketManagerFull.PROP_PCAP)) {
                packet.logTCPDump(null);
            }
            sendReset(packet);
            packet.releasePayload();
        }
    }

    /**
     * Handles an echo packet received on an unknown connection.
     *
     * @param packet the echo packet
     */
    private void handleEchoOnUnknown(Packet packet) {
        if (packet.getSendStreamId() > 0) {
            if (manager.answerPings()) {
                receivePing(null, packet);
            } else if (log.shouldWarn()) {
                log.warn("Dropping ECHO packet on unknown connection -> " + packet);
            }
        } else if (packet.getReceiveStreamId() > 0) {
            receivePong(packet);
        } else if (log.shouldWarn()) {
            log.warn("Received ECHO packet " + packet + " without StreamIDs");
        }
        packet.releasePayload();
    }

    /**
     * Verifies and processes an incoming ping packet.
     *
     * @param con    the connection from which ping originated, or null if unknown
     * @param packet the ping packet
     */
    private void receivePing(Connection con, Packet packet) {
        SigningPublicKey spk = (con != null) ? con.getRemoteSPK() : null;
        ByteArray ba = cache.acquire();
        boolean verified = packet.verifySignature(context, spk, ba.getData());
        cache.release(ba);

        if (!verified) {
            if (log.shouldWarn())
                log.warn("BAD ping, signature verification failed -> Dropping " + packet);
            return;
        }

        manager.receivePing(con, packet);
    }

    /**
     * Processes an incoming pong packet.
     *
     * @param packet the pong packet
     */
    private void receivePong(Packet packet) {
        manager.receivePong(packet.getReceiveStreamId(), packet.getPayload());
    }

    /**
     * Validates that the connection's stream ID matches the packet's stream ID and is non-zero.
     *
     * @param conStreamId    the connection's send stream ID
     * @param packetStreamId the packet's receive stream ID
     * @return true if stream IDs match and are non-zero, false otherwise
     */
    private static boolean isValidMatch(long conStreamId, long packetStreamId) {
        return (conStreamId == packetStreamId) && (conStreamId != 0);
    }
}
