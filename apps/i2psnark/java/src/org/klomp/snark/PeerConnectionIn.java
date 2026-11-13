/* PeerConnectionIn - Handles incomming messages and hands them to PeerState.
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

import java.io.DataInputStream;
import java.io.IOException;
import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * Handles incoming BitTorrent protocol messages from a peer.<br>
 * This class runs in a separate thread to continuously read and parse incoming messages, delegating
 * them to the appropriate PeerState handlers.
 * @since 0.1
 */
class PeerConnectionIn implements Runnable {
    /** Logger for this class. */
    private final Log _log =
            I2PAppContext.getGlobalContext().logManager().getLog(PeerConnectionIn.class);
    /** The peer being read from. */
    private final Peer peer;
    /** The stream read from the peer. */
    private final DataInputStream din;

    /**
     * Largest complete message body accepted, in bytes: the piece message is
     * the block we asked for, at most {@link PeerState#PARTSIZE}, plus the type
     * byte and the piece and begin fields; the extension message carries a
     * magnet chunk plus its dictionary. A bitfield message exceeds the piece
     * message only if Storage.MAX_PIECES / 8 grows past the current 625.
     * <p>
     * Measured per message rather than frozen at class load: PARTSIZE is a
     * runtime setting read from <code>i2psnark.pipeline.partsize</code>, and a
     * bound left at the 16 KiB default disconnects every peer that correctly
     * answers a request for a larger part - our own request, so a behaving
     * peer pays for it.
     *
     * @return the largest message body accepted, in bytes
     * @since 0.9.72+
     */
    static int maxMessageSize() {
        return Math.max(PeerState.PARTSIZE + 9, MagnetState.CHUNK_SIZE + 100); // 100 for the ext msg dictionary
    }

    /** Flood guard: maximum inbound messages per window, generous to tolerate bursts. */
    private static final int MAX_MESSAGES_PER_WINDOW = 2000;

    /** Flood guard: length of the counting window. */
    private static final long FLOOD_WINDOW_MS = 10 * 1000;

    /** The thread reading messages, null once stopped. */
    private volatile Thread thread;
    /** Set to end the read loop. */
    private volatile boolean quit;
    /** True when running outside the router. */
    private final boolean isStandalone = !I2PAppContext.getGlobalContext().isRouterContext();

    /** Last rcvd. */
    /** Time of the last message received. Volatile - read by the peer checker task. */
    volatile long lastRcvd;

    /**
     * Create a handler for incoming messages from a peer.
     * @param peer the remote peer this connection belongs to
     * @param din the stream positioned at the start of the peer's message stream
     */
    public PeerConnectionIn(Peer peer, DataInputStream din) {
        this.peer = peer;
        this.din = din;
        lastRcvd = System.currentTimeMillis();
    }

    /**
     * The minimum complete message length in bytes for a message type, including the type byte.
     * Messages with variable-length data (PIECE, BITFIELD, EXTENSION) have a minimum of just their
     * fixed header.
     *
     * @param type the message type byte
     * @return the minimum length for the given type
     */
    private static int minLengthFor(byte type) {
        switch (type) {
            case Message.HAVE:
            case Message.SUGGEST:
            case Message.ALLOWED_FAST:
                return 5;
            case Message.PIECE:
                return 9;
            case Message.REQUEST:
            case Message.CANCEL:
            case Message.REJECT:
                return 13;
            case Message.PORT:
                return 3;
            case Message.EXTENSION:
                return 2;
            default:
                return 1;
        }
    }

    /** Disconnect from the peer and release resources. */
    void disconnect() {
        if (quit) {
            return;
        }
        quit = true;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
        if (din != null) {
            try {
                din.close();
            } catch (IOException ioe) { /* ignored */ }
        }
    }

    /**
     * Read and dispatch incoming BitTorrent messages until disconnect.
     */
    @Override
    public void run() {
        thread = Thread.currentThread();
        long windowStart = System.currentTimeMillis();
        int windowCount = 0;
        try {
            while (!quit) {
                final PeerState ps = peer.state;
                if (ps == null) {
                    break;
                }

                // Common variables used for some messages.
                int piece;
                int begin;
                int len;

                // Wait till we hear something...
                int i = din.readInt();
                lastRcvd = System.currentTimeMillis();
                // Count all messages including keep-alives; disconnect on flood
                long now = System.currentTimeMillis();
                if (now - windowStart >= FLOOD_WINDOW_MS) {
                    windowStart = now;
                    windowCount = 0;
                }
                if (++windowCount > MAX_MESSAGES_PER_WINDOW) {
                    throw new IOException(
                            "Message flood from ["
                                    + peer
                                    + "]: "
                                    + windowCount
                                    + " messages in window");
                }
                int maxMsg = maxMessageSize();
                if (i < 0 || i > maxMsg) {
                    throw new IOException("Unexpected length prefix: " + i + " (max: " + maxMsg + ')');
                }

                if (i == 0) {
                    if (_log.shouldDebug()) {
                        _log.debug("Received keepalive from " + SnarkLogFormatter.peer(peer));
                    }
                    ps.keepAliveMessage();
                    continue;
                }

                byte b = din.readByte();
                // Validate the payload length against the minimum for this message type;
                // a short prefix is a protocol error and would desync the stream
                if (i < minLengthFor(b)) {
                    throw new IOException("Unexpected length prefix: " + i + " for type " + b);
                }
                switch (b) {
                    case Message.CHOKE:
                        if (_log.shouldDebug()) {
                            _log.debug("Received choke from " + SnarkLogFormatter.peer(peer));
                        }
                        ps.chokeMessage(true);
                        break;

                    case Message.UNCHOKE:
                        if (_log.shouldDebug()) {
                            _log.debug("Received unchoke from " + SnarkLogFormatter.peer(peer));
                        }
                        ps.chokeMessage(false);
                        break;

                    case Message.INTERESTED:
                        if (_log.shouldDebug()) {
                            _log.debug("Received interested from " + SnarkLogFormatter.peer(peer));
                        }
                        ps.interestedMessage(true);
                        break;

                    case Message.UNINTERESTED:
                        if (_log.shouldDebug()) {
                            _log.debug("Received not interested from "
                                       + SnarkLogFormatter.peer(peer));
                        }
                        ps.interestedMessage(false);
                        break;

                    case Message.HAVE:
                        piece = din.readInt();
                        if (_log.shouldDebug()) {
                            _log.debug("Received a Have message -> Piece: " + piece +
                                       " * Peer: " + SnarkLogFormatter.peer(peer));
                        }
                        ps.haveMessage(piece);
                        break;

                    case Message.BITFIELD:
                        byte[] bitmap = new byte[i - 1];
                        din.readFully(bitmap);
                        if (_log.shouldDebug()) {
                            _log.debug("Received a bitmap from " + SnarkLogFormatter.peer(peer)
                                       + " -> Size: "
                                       + (i - 1) + " bytes");
                        }
                        ps.bitfieldMessage(bitmap);
                        break;

                    case Message.REQUEST:
                        piece = din.readInt();
                        begin = din.readInt();
                        len = din.readInt();
                        if (_log.shouldDebug()) {
                            _log.debug("Received a request -> Piece: " + piece + " * Begin: "
                                       + begin + " * Length: " + len + " bytes * Peer: "
                                       + SnarkLogFormatter.peer(peer));
                        }
                        ps.requestMessage(piece, begin, len);
                        break;

                    case Message.PIECE:
                        piece = din.readInt();
                        begin = din.readInt();
                        len = i - 9;
                        Request req = ps.getOutstandingRequest(piece, begin, len);
                        if (req != null) {
                            req.read(din, peer);
                            if (_log.shouldDebug()) {
                                _log.debug("Received piece data -> Piece: " + piece
                                           + " * Offset: " + begin + " bytes * Size: " + len
                                           + " bytes * Peer: " + SnarkLogFormatter.peer(peer));
                            }
                            ps.pieceMessage(req);
                        } else {
                            // XXX - Consume but throw away afterwards.
                            int rcvd = din.skipBytes(len);
                            if (rcvd != len) {
                                throw new IOException("EOF reading unwanted data");
                            }
                            if (_log.shouldDebug()) {
                                _log.debug("Received piece data -> No request matched it"
                                           + " * Piece: " + piece + " * Offset: " + begin
                                           + " bytes * Peer: " + SnarkLogFormatter.peer(peer));
                            }
                        }
                        break;

                    case Message.CANCEL:
                        piece = din.readInt();
                        begin = din.readInt();
                        len = din.readInt();
                        if (_log.shouldDebug()) {
                            _log.debug("Received a cancel -> Dropped the request"
                                       + " * Piece: " + piece + " * Offset: " + begin
                                       + " bytes * Peer: " + SnarkLogFormatter.peer(peer));
                        }
                        ps.cancelMessage(piece, begin, len);
                        break;

                    case Message.PORT:
                        int port = din.readUnsignedShort();
                        if (_log.shouldDebug()) {
                            _log.debug("Received port message from " + SnarkLogFormatter.peer(peer));
                        }
                        ps.portMessage(port);
                        break;

                    case Message.EXTENSION:
                        int id = din.readUnsignedByte();
                        byte[] payload = new byte[i - 2];
                        din.readFully(payload);
                        if (_log.shouldDebug()) {
                            _log.debug("Received extension message from "
                                       + SnarkLogFormatter.peer(peer));
                        }
                        ps.extensionMessage(id, payload);
                        break;

                    // fast extensions below here
                    case Message.SUGGEST:
                        piece = din.readInt();
                        ps.suggestMessage(piece);
                        if (_log.shouldDebug()) {
                            _log.debug("Received a suggest -> Piece: " + piece
                                       + " * Peer: " + SnarkLogFormatter.peer(peer));
                        }
                        break;

                    case Message.HAVE_ALL:
                        ps.haveMessage(true);
                        if (_log.shouldDebug()) {
                            _log.debug("Received have_all from " + SnarkLogFormatter.peer(peer));
                        }
                        break;

                    case Message.HAVE_NONE:
                        ps.haveMessage(false);
                        if (_log.shouldDebug()) {
                            _log.debug("Received have_none from " + SnarkLogFormatter.peer(peer));
                        }
                        break;

                    case Message.REJECT:
                        piece = din.readInt();
                        begin = din.readInt();
                        len = din.readInt();
                        ps.rejectMessage(piece, begin, len);
                        if (_log.shouldDebug()) {
                            _log.debug("Received a reject -> Piece: " + piece + " * Begin: "
                                       + begin + " * Length: " + len + " bytes * Peer: "
                                       + SnarkLogFormatter.peer(peer));
                        }
                        break;

                    case Message.ALLOWED_FAST:
                        piece = din.readInt();
                        ps.allowedFastMessage(piece);
                        if (_log.shouldDebug()) {
                            _log.debug("Received allowed_fast -> Piece: " + piece
                                       + " * Peer: " + SnarkLogFormatter.peer(peer));
                        }
                        break;

                    default:
                        byte[] bs = new byte[i - 1];
                        din.readFully(bs);
                        ps.unknownMessage(b, bs);
                        if (_log.shouldDebug()) {
                            _log.debug("Received unknown message from "
                                       + SnarkLogFormatter.peer(peer));
                        }
                }
            }
        } catch (IOException ioe) {
            // Ignore, probably the other side closed connection.
            if (_log.shouldDebug()) {
                _log.debug("Peer read failed -> Ended the read loop"
                           + " * Peer: " + SnarkLogFormatter.peer(peer)
                           + SnarkLogFormatter.onOwnLine(SnarkLogFormatter.error(ioe)));
            }
        } catch (RuntimeException t) {
            if (!isStandalone && _log.shouldWarn()) {
                _log.warn("Peer read failed -> Ended the read loop"
                          + " * Peer: " + SnarkLogFormatter.peer(peer), t);
            }
        } finally {
            peer.disconnect();
        }
    }
}
