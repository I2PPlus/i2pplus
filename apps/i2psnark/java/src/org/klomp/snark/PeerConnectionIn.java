/* PeerConnectionIn - Handles incomming messages and hands them to PeerState.
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
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

    /**
     * Flood guard: bytes of message data accepted from one peer per window. The bound used to
     * be a message count alone, which only implies a volume at one block size: a peer that
     * splits its replies into smaller pieces than we asked for - every client caps its own
     * piece size, and the chunk size is configurable down to 1 KiB - sends proportionally more
     * messages for the same bytes, and the old count cut off a peer streaming 200 KB/s in 1 KiB
     * blocks. This is the volume that count implied at the default chunk size: two thousand
     * blocks. This is the volume that count implied at the default chunk size: two thousand
     * messages of 16 KiB, each carrying a four-byte length prefix and a one-byte id.
     *
     * @since 0.9.72+
     */
    static final long MAX_BYTES_PER_WINDOW = 2000L * (16 * 1024 + 5);

    /**
     * Flood guard: messages accepted from one peer per window whatever they carry. Well above
     * the count the byte bound allows at the default chunk size, so a peer sending normal-sized
     * blocks is bounded by bytes; this is the guard for a peer that sends only messages with no
     * payload, which never moves the byte counter.
     *
     * @since 0.9.72+
     */
    static final int MAX_MESSAGES_PER_WINDOW = 20000;

    /**
     * Whether one window's worth of inbound bytes is a flood.
     *
     * @param bytes bytes counted for the window, including length prefixes
     * @return true if the peer has sent more than the window allows
     * @since 0.9.72+
     */
    static boolean floodByBytes(long bytes) {
        return bytes > MAX_BYTES_PER_WINDOW;
    }

    /**
     * Whether one window's worth of inbound messages is a flood.
     *
     * @param count messages counted for the window, keep-alives included
     * @return true if the peer has sent more than the window allows
     * @since 0.9.72+
     */
    static boolean floodByCount(int count) {
        return count > MAX_MESSAGES_PER_WINDOW;
    }

    /** Chunk size used to consume a message body we did not ask for, in bytes. */
    private static final int DRAIN_CHUNK = 16 * 1024;

    /**
     * Scratch space for consuming a message body we did not ask for, allocated once per
     * connection and reused for every discarded block on it.
     */
    private byte[] _drainBuf;

    /**
     * Consumes the body of a message we did not ask for so the stream stays aligned on the
     * next one.
     *
     * @param len the number of bytes to consume
     * @throws IOException if the stream ends before that many bytes are read
     */
    void drain(int len) throws IOException {
        byte[] buf = _drainBuf;
        if (buf == null) {
            buf = new byte[DRAIN_CHUNK];
            _drainBuf = buf;
        }
        drain(din, buf, len);
    }

    /**
     * The same, with the stream and the scratch space supplied, so the alignment it protects
     * can be pinned against a stream that cannot skip.
     *
     * @param in the stream positioned at the body
     * @param buf the scratch space to read into
     * @param len the number of bytes to consume
     * @throws IOException if the stream ends before that many bytes are read
     */
    static void drain(InputStream in, byte[] buf, int len) throws IOException {
        int left = len;
        while (left > 0) {
            int n = in.read(buf, 0, Math.min(left, buf.length));
            if (n < 0) {
                throw new IOException("Unwanted message body ended early -> Bytes: " + left);
            }
            left -= n;
        }
    }

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
        long windowBytes = 0;
        // The length prefix and the fixed header fields are each taken in a single read. A
        // readInt() on a DataInputStream is four single-byte reads on the stream underneath,
        // and on an I2P stream each of those is a lock acquisition and a copy, so a PIECE
        // header used to cost thirteen of them before a byte of payload was seen.
        byte[] lenBuf = new byte[4];
        byte[] fixedBuf = new byte[Message.MAX_FIXED_BYTES - 1];
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
                din.readFully(lenBuf);
                int i = Message.readInt(lenBuf, 0);
                lastRcvd = System.currentTimeMillis();
                // Count all messages including keep-alives; disconnect on flood
                long now = System.currentTimeMillis();
                if (now - windowStart >= FLOOD_WINDOW_MS) {
                    windowStart = now;
                    windowCount = 0;
                    windowBytes = 0;
                }
                // the length prefix counts the id and the payload, not itself
                windowBytes += i + 4L;
                windowCount++;
                if (floodByBytes(windowBytes)) {
                    throw new IOException("Message flood from ["
                                          + peer
                                          + "] -> Too many bytes in the window"
                                          + " * Bytes: "
                                          + windowBytes);
                }
                if (floodByCount(windowCount)) {
                    throw new IOException("Message flood from ["
                                          + peer
                                          + "] -> Too many messages in the window"
                                          + " * Messages: "
                                          + windowCount);
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
                int fixed = Message.fixedBytes(b);
                if (i < fixed) {
                    throw new IOException("Unexpected length prefix: " + i + " for type " + b);
                }
                // Take whatever fields follow the id in one read too. They always arrive in
                // the order fixedBytes() counts them, and the switch below parses them out of
                // fixedBuf rather than reading each one from the stream on its own.
                if (fixed > 1) {
                    din.readFully(fixedBuf, 0, fixed - 1);
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
                        piece = Message.readInt(fixedBuf, 0);
                        if (_log.shouldDebug()) {
                            _log.debug("Received a Have message -> Piece: " + piece +
                                       " * Peer: " + SnarkLogFormatter.peer(peer));
                        }
                        ps.haveMessage(piece);
                        break;

                    case Message.BITFIELD:
                        byte[] bitmap = new byte[i - fixed];
                        din.readFully(bitmap);
                        if (_log.shouldDebug()) {
                            _log.debug("Received a bitmap from " + SnarkLogFormatter.peer(peer)
                                       + " -> Size: "
                                       + (i - 1) + " bytes");
                        }
                        ps.bitfieldMessage(bitmap);
                        break;

                    case Message.REQUEST:
                        piece = Message.readInt(fixedBuf, 0);
                        begin = Message.readInt(fixedBuf, 4);
                        len = Message.readInt(fixedBuf, 8);
                        if (_log.shouldDebug()) {
                            _log.debug("Received a request -> Piece: " + piece + " * Begin: "
                                       + begin + " * Length: " + len + " bytes * Peer: "
                                       + SnarkLogFormatter.peer(peer));
                        }
                        ps.requestMessage(piece, begin, len);
                        break;

                    case Message.PIECE:
                        piece = Message.readInt(fixedBuf, 0);
                        begin = Message.readInt(fixedBuf, 4);
                        len = i - fixed;
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
                            // Read the body and throw it away. Skipping is not an option: the
                            // socket stream inherits InputStream.skip(), which reports that it
                            // skipped nothing, so a buffered skip only consumes whatever is
                            // already in the buffer and leaves the rest of this body to be
                            // parsed as the next message's header.
                            drain(len);
                            if (_log.shouldDebug()) {
                                _log.debug("Received piece data -> No request matched it"
                                           + " * Piece: " + piece + " * Offset: " + begin
                                           + " bytes * Peer: " + SnarkLogFormatter.peer(peer));
                            }
                        }
                        break;

                    case Message.CANCEL:
                        piece = Message.readInt(fixedBuf, 0);
                        begin = Message.readInt(fixedBuf, 4);
                        len = Message.readInt(fixedBuf, 8);
                        if (_log.shouldDebug()) {
                            _log.debug("Received a cancel -> Dropped the request"
                                       + " * Piece: " + piece + " * Offset: " + begin
                                       + " bytes * Peer: " + SnarkLogFormatter.peer(peer));
                        }
                        ps.cancelMessage(piece, begin, len);
                        break;

                    case Message.PORT:
                        int port = Message.readUnsignedShort(fixedBuf, 0);
                        if (_log.shouldDebug()) {
                            _log.debug("Received port message from " + SnarkLogFormatter.peer(peer));
                        }
                        ps.portMessage(port);
                        break;

                    case Message.EXTENSION:
                        int id = Message.readUnsignedByte(fixedBuf, 0);
                        byte[] payload = new byte[i - fixed];
                        din.readFully(payload);
                        if (_log.shouldDebug()) {
                            _log.debug("Received extension message from "
                                       + SnarkLogFormatter.peer(peer));
                        }
                        ps.extensionMessage(id, payload);
                        break;

                    // fast extensions below here
                    case Message.SUGGEST:
                        piece = Message.readInt(fixedBuf, 0);
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
                        piece = Message.readInt(fixedBuf, 0);
                        begin = Message.readInt(fixedBuf, 4);
                        len = Message.readInt(fixedBuf, 8);
                        ps.rejectMessage(piece, begin, len);
                        if (_log.shouldDebug()) {
                            _log.debug("Received a reject -> Piece: " + piece + " * Begin: "
                                       + begin + " * Length: " + len + " bytes * Peer: "
                                       + SnarkLogFormatter.peer(peer));
                        }
                        break;

                    case Message.ALLOWED_FAST:
                        piece = Message.readInt(fixedBuf, 0);
                        ps.allowedFastMessage(piece);
                        if (_log.shouldDebug()) {
                            _log.debug("Received allowed_fast -> Piece: " + piece
                                       + " * Peer: " + SnarkLogFormatter.peer(peer));
                        }
                        break;

                    default:
                        byte[] bs = new byte[i - fixed];
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
