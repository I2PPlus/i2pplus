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
 * receive a packet and dispatch it correctly to the connection specified,
 * the server socket, or queue a reply RST packet.
 *<p>
 * I2PSession -&gt; MessageHandler -&gt; PacketHandler -&gt; ConnectionPacketHandler -&gt; MessageInputStream
 */
class PacketHandler {
    private final ConnectionManager _manager;
    private final I2PAppContext _context;
    private final Log _log;
    private final ByteCache _cache = ByteCache.getInstance(32, 4*1024);
    //private int _lastDelay;
    //private int _dropped;

    public PacketHandler(I2PAppContext ctx, ConnectionManager mgr) {
        _manager = mgr;
        _context = ctx;
        //_dropped = 0;
        _log = ctx.logManager().getLog(PacketHandler.class);
        //_lastDelay = _context.random().nextInt(30*1000);
    }

    void receivePacket(Packet packet) {
        receivePacketDirect(packet, true);
    }

    void receivePacketDirect(Packet packet, boolean queueIfNoConn) {
        //if (_log.shouldDebug())
        //    _log.debug("packet received: " + packet);

        long sendId = packet.getSendStreamId();

        Connection con = (sendId > 0 ? _manager.getConnectionByInboundId(sendId) : null);
        if (con != null) {
            if (_log.shouldDebug())
                displayPacket(packet, "RECV", "WSIZE " + con.getOptions().getWindowSize() + "; RTO " + con.getOptions().getRTO());
            receiveKnownCon(con, packet);
        } else {
            if (_log.shouldDebug())
                displayPacket(packet, "UNKN", null);
            receiveUnknownCon(packet, sendId, queueIfNoConn);
        }
        // Don't log here, wait until we have the conn to make the dumps easier to follow
        //((PacketLocal)packet).logTCPDump(true);
    }

    private static final SimpleDateFormat _fmt = new SimpleDateFormat("HH:mm:ss.SSS");

    /** logs to router log at debug level */
    void displayPacket(Packet packet, String prefix, String suffix) {
        StringBuilder buf = new StringBuilder(256);
        synchronized (_fmt) {
            buf.append(_fmt.format(new Date()));
        }
        buf.append(": ").append(prefix).append(" ");
        buf.append(packet.toString());
        if (suffix != null)
            buf.append(" ").append(suffix);
        String str = buf.toString();
        //System.out.println(str);
        _log.debug(str);
    }

    private void receiveKnownCon(Connection con, Packet packet) {
        // is this ok here or does it need to be below each packetHandler().receivePacket() ?
        if (I2PSocketManagerFull.pcapWriter != null &&
            _context.getBooleanProperty(I2PSocketManagerFull.PROP_PCAP))
            packet.logTCPDump(con);
        if (packet.isFlagSet(Packet.FLAG_ECHO)) {
            if (packet.getSendStreamId() > 0) {
                if (con.getOptions().getAnswerPings())
                    receivePing(con, packet);
                else if (_log.shouldWarn())
                    _log.warn("Dropping ECHO packet on existing connection -> " + packet);
            } else if (packet.getReceiveStreamId() > 0) {
                receivePong(packet);
            } else {
                if (_log.shouldWarn())
                    _log.warn("Received ECHO packet " + packet + " with no StreamIDs");
            }
            packet.releasePayload();
            return;
        }

        // the packet is pointed at a stream ID we're receiving on
        if (isValidMatch(con.getSendStreamId(), packet.getReceiveStreamId())) {
            // the packet's receive stream ID also matches what we expect
            //if (_log.shouldDebug())
            //    _log.debug("receive valid: " + packet);
            try {
                con.getPacketHandler().receivePacket(packet, con);
            } catch (I2PException ie) {
                if (_log.shouldWarn())
                    _log.warn("Received FORGED packet for " + con, ie);
            }
        } else {
            if (packet.isFlagSet(Packet.FLAG_RESET)) {
                // refused
                if (_log.shouldDebug())
                    _log.debug("Received reset: " + packet);
                try {
                    con.getPacketHandler().receivePacket(packet, con);
                } catch (I2PException ie) {
                    if (_log.shouldWarn())
                        _log.warn("Received FORGED reset for " + con, ie);
                }
            } else {
                if ( (con.getSendStreamId() <= 0) ||
                     (con.getSendStreamId() == packet.getReceiveStreamId()) ||
                     (packet.getSequenceNum() <= ConnectionOptions.MIN_WINDOW_SIZE) ) { // its in flight from the first batch
                    long oldId = con.getSendStreamId();
                    if (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                        if (oldId <= 0) {
                            // outgoing con now fully established
                            con.setSendStreamId(packet.getReceiveStreamId());
                            SigningPublicKey spk = packet.getTransientSPK();
                            if (spk != null)
                                con.setRemoteTransientSPK(spk);
                        } else if (oldId == packet.getReceiveStreamId()) {
                            // ok, as expected...
                        } else {
                            // Apparently an i2pd bug...
                            if (_log.shouldWarn())
                                _log.warn("Received SYN packet with wrong IDs: [" + con + "]\n* Packet: " + packet);
                            sendReset(packet);
                            packet.releasePayload();
                            return;
                        }
                    }

                    try {
                        con.getPacketHandler().receivePacket(packet, con);
                    } catch (I2PException ie) {
                        if (_log.shouldWarn())
                            _log.warn("Sig verify fail for " + con + "/" + oldId + ": " + packet, ie);
                        // TODO we can't set the stream ID back to 0, throws ISE
                        //con.setSendStreamId(oldId);
                        if (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                            // send a reset, it's a known con, so it's unlikely to be spoofed
                            // don't bother to send reset if it's just a CLOSE
                            sendResetUnverified(packet);
                        }
                    }
                } else if (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                    if (_log.shouldWarn())
                        _log.warn("Received SYN packet " + packet + " with wrong IDs -> Sending RESET...");
                    sendReset(packet);
                    packet.releasePayload();
                } else {
                    if (!con.getResetSent()) {
                        // someone is sending us a packet on the wrong stream
                        // It isn't a SYN so it isn't likely to have a FROM to send a reset back to
                        if (_log.shouldWarn()) {
                            StringBuilder buf = new StringBuilder(512);
                            buf.append("Received packet on the wrong stream: ");
                            buf.append(packet);
                            buf.append("\nthis connection:\n");
                            buf.append(con);
                            buf.append("\nall connections:");
                            for (Connection cur : _manager.listConnections()) {
                                buf.append('\n').append(cur);
                            }
                            _log.warn(buf.toString(), new Exception("Wrong stream"));
                        }
                    }
                    packet.releasePayload();
                }
            }
        }
    }

    /**
     *  This sends a reset back to the place this packet came from.
     *  If the packet has no 'optional from' or valid signature, this does nothing.
     *  This is not associated with a connection, so no con stats are updated.
     *
     *  @param packet incoming packet to be replied to
     */
    private void sendReset(Packet packet) {
        Destination from = packet.getOptionalFrom();
        if (from == null)
            return;
        ByteArray ba = _cache.acquire();
        boolean ok = packet.verifySignature(_context, ba.getData());
        _cache.release(ba);
        if (!ok) {
            if (_log.shouldWarn())
                _log.warn("Can't send reset after receiving spoofed packet " + packet);
            return;
        }
        sendResetUnverified(packet);
    }

    /**
     *  This sends a reset back to the place this packet came from.
     *  Packet MUST have a FROM option.
     *  This is not associated with a connection, so no con stats are updated.
     *
     *  @param packet incoming packet to be replied to, MUST have a FROM option
     *  @since 0.9.39
     */
    private void sendResetUnverified(Packet packet) {
        PacketLocal reply = new PacketLocal(_context, packet.getOptionalFrom(), packet.getSession());
        reply.setFlag(Packet.FLAG_RESET);
        reply.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
        reply.setSendStreamId(packet.getReceiveStreamId());
        reply.setReceiveStreamId(packet.getSendStreamId());
        // As of 0.9.20 we do not require FROM
        // Removed in 0.9.39
        //reply.setOptionalFrom();
        reply.setLocalPort(packet.getLocalPort());
        reply.setRemotePort(packet.getRemotePort());
        // this just sends the packet - no retries or whatnot
        _manager.getPacketQueue().enqueue(reply);
    }

    private void receiveUnknownCon(Packet packet, long sendId, boolean queueIfNoConn) {
        if (packet.isFlagSet(Packet.FLAG_ECHO)) {
            if (packet.getSendStreamId() > 0) {
                if (_manager.answerPings())
                    receivePing(null, packet);
                else if (_log.shouldWarn())
                    _log.warn("Dropping ECHO packet on UNKNOWN connection -> " + packet);
            } else if (packet.getReceiveStreamId() > 0) {
                receivePong(packet);
            } else {
                if (_log.shouldWarn())
                    _log.warn("Received ECHO packet " + packet + " without StreamIDs");
            }
            packet.releasePayload();
        } else {
            // this happens a lot
            if (_log.shouldInfo() && !packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                _log.info("Received packet on UNKNOWN stream (not ECHO / SYN) -> " + packet);
            }
            if (sendId <= 0) {
                Connection con = _manager.getConnectionByOutboundId(packet.getReceiveStreamId());
                if (con != null) {
                    if ( (con.getHighestAckedThrough() <= 5) && (packet.getSequenceNum() <= 5) ) {
                        if (_log.shouldInfo()) {
                            _log.info("Received additional packet without SendStreamID after the SYN -> " + packet + "\n* " + con);
                        }
                    } else {
                        if (_log.shouldWarn()) {
                            _log.warn("hrmph, received while ACK of SYN was in flight\n* " + con + ": " + packet +
                                      " ACKed: " + con.getAckedPackets());
                        }
                        // allow unlimited packets without a SendStreamID for now
                    }
                    receiveKnownCon(con, packet);
                    return;
                }
            } else {
                // if it has a send ID, it's almost certainly for a recently removed connection.
                if (_log.shouldWarn()) {
                    boolean recent = _manager.wasRecentlyClosed(packet.getSendStreamId());
                    _log.warn("Dropping packet " + packet + " with SendStreamID but no connection" +
                    (recent ? " -> Recently disconnected" : ""));
                }
                // don't bother sending reset
                // TODO send reset if recent && has data?
                packet.releasePayload();
                return;
            }

            if (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                // logTCPDump() will be called in ConnectionManager.receiveConnection(),
                // which is called by ConnectionHandler.receiveNewSyn(),
                // after we have a new conn, which makes the logging better.
                _manager.getConnectionHandler().receiveNewSyn(packet);
            } else if (queueIfNoConn) {
                // don't call logTCPDump() here, wait for it to find a conn

                // We can get here on the 2nd+ packet if the 1st (SYN) packet
                // is still on the _synQueue in the ConnectionHandler, and
                // ConnectionManager.receiveConnection() hasn't run yet to put
                // the StreamID on the getConnectionByOutboundId list.
                // Then the 2nd packet gets discarded and has to be retransmitted.
                //
                // We fix this by putting this packet on the syn queue too!
                // Then ConnectionHandler.accept() will check the connection list
                // and call receivePacket() above instead of receiveConnection().
                if (_log.shouldWarn()) {
                    _log.warn("Packet " + packet + " belongs to no other connections, putting on the SYN queue...");
                }
                if (_log.shouldDebug()) {
                    StringBuilder buf = new StringBuilder(128);
                    for (Connection con : _manager.listConnections()) {
                        buf.append(con.toString()).append(" ");
                    }
                    _log.debug("Connections: " + buf.toString() + " SendID: " + (sendId > 0 ? Packet.toId(sendId) : " unknown"));
                }
                //packet.releasePayload();
                _manager.getConnectionHandler().receiveNewSyn(packet);
            } else {
                // log it here, just before we kill it - dest will be unknown
                if (I2PSocketManagerFull.pcapWriter != null &&
                    _context.getBooleanProperty(I2PSocketManagerFull.PROP_PCAP))
                    packet.logTCPDump(null);
                // don't queue again (infinite loop!)
                sendReset(packet);
                packet.releasePayload();
            }
        }
    }

    /**
     *  @param con null if unknown
     */
    private void receivePing(Connection con, Packet packet) {
        SigningPublicKey spk = con != null ? con.getRemoteSPK() : null;
        ByteArray ba = _cache.acquire();
        boolean ok = packet.verifySignature(_context, spk, ba.getData());
        _cache.release(ba);
        if (!ok) {
            if (_log.shouldWarn())
                _log.warn("BAD ping, sig verify failed -> Dropping " + packet);
        } else {
            _manager.receivePing(con, packet);
        }
    }

    private void receivePong(Packet packet) {
        _manager.receivePong(packet.getReceiveStreamId(), packet.getPayload());
    }

    private static final boolean isValidMatch(long conStreamId, long packetStreamId) {
        return ( (conStreamId == packetStreamId) && (conStreamId != 0) );
    }
}
