/* Message - A protocol message which can be send through a DataOutputStream.
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

import java.io.DataOutputStream;
import java.io.IOException;
import net.i2p.data.ByteArray;
import net.i2p.util.ByteCache;

/**
 * Represents a BitTorrent protocol message for peer communication.
 *
 * <p>This class encapsulates all BitTorrent protocol message types including:
 *
 * <ul>
 *   <li>Control messages: keep-alive, choke, unchoke, interested, uninterested</li>
 *   <li>Piece messages: have, bitfield, request, piece, cancel</li>
 *   <li>Extension messages for protocol extensions</li>
 *   <li>Fast peer extension messages (BEP 6)</li>
 *   <li>DHT messages (BEP 5, BEP 52)</li>
 * </ul>
 *
 * <p>Messages are created and queued for sending through a DataOutputStream.
 * The sendMessage() method translates messages to wire format.
 *
 * <p>Message structure includes:
 * <ul>
 *   <li>type - The message type byte</li>
 *   <li>piece - The piece index (for have, request, piece, cancel)</li>
 *   <li>begin - The offset within the piece</li>
 *   <li>length - The data length</li>
 *   <li>data - Optional payload data</li>
 * </ul>
 *
 * @see PeerState
 * @since 0.1.0
 */
class Message {
    /** No-op message sent periodically to keep the connection alive. */
    static final byte KEEP_ALIVE = -1;
    /** Stop sending piece data to the peer. */
    static final byte CHOKE = 0;
    /** Resume sending piece data to the peer. */
    static final byte UNCHOKE = 1;
    /** Signal that the peer has pieces we want. */
    static final byte INTERESTED = 2;
    /** Signal that we no longer want pieces from the peer. */
    static final byte UNINTERESTED = 3;
    /** Announce possession of a single piece. */
    static final byte HAVE = 4;
    /** Bitfield of pieces the peer has. */
    static final byte BITFIELD = 5;
    /** Request a chunk of a piece. */
    static final byte REQUEST = 6;
    /** Chunk of a piece in response to a request. */
    static final byte PIECE = 7;
    /** Cancel a previously sent request. */
    static final byte CANCEL = 8;
    /** Announce the DHT listening port (BEP 5). */
    static final byte PORT = 9;
    /** Suggest a piece the peer may want (BEP 6). */
    static final byte SUGGEST = 13;
    /** Announce possession of every piece (BEP 6). */
    static final byte HAVE_ALL = 14;
    /** Announce possession of no pieces (BEP 6). */
    static final byte HAVE_NONE = 15;
    /** Reject a previously accepted request (BEP 6). */
    static final byte REJECT = 16;
    /** Allow request of a piece without an unchoke (BEP 6). */
    static final byte ALLOWED_FAST = 17;
    /** BEP 10 extended message, sub-type in the low byte. */
    static final byte EXTENSION = 20;
    /** Request merkle hash tree data (BEP 52). */
    static final byte HASH_REQUEST = 21;
    /** Merkle hash tree data (BEP 52). */
    static final byte HASHES = 22;
    /** Reject a hash request (BEP 52). */
    static final byte HASH_REJECT = 23;

    /**
     * The message type. Not all fields are used for every message; KEEP_ALIVE has no real
     * wire representation.
     */
    final byte type;

    /**
     * The piece index. Used for HAVE, REQUEST, PIECE and CANCEL messages, and also for
     * SUGGEST, REJECT and ALLOWED_FAST; the low byte for EXTENSION messages and the low
     * two bytes for PORT messages.
     */
    final int piece;

    /**
     * The offset within the piece. Used for REQUEST, PIECE, CANCEL and REJECT messages.
     */
    final int begin;
    /** The data length. */
    final int length;

    /** The data payload. Used for PIECE, BITFIELD and EXTENSION messages. */
    byte[] data;
    /** The data offset. */
    final int off;
    /** The payload length. */
    final int len;

    /** The loader for a deferred fetch of the message data. */
    private final DataLoader dataLoader;

    /**
     * Cache-backed buffer owned by this message, non-null between
     * successful load and release. It may be loaded off the sender
     * thread by the prefetch executor, so the state transitions below
     * are guarded by this, keeping the sender, the executor and the
     * purge paths in agreement.
     */
    private ByteArray _ba;
    /** Set exactly once by the winner of the race to execute the loader. */
    private boolean _loadClaimed;
    /** True while the loader is executing somewhere; cleared by completeLoad(). */
    private boolean _pendingLoad;
    /** Loader completed and returned no data. */
    private boolean _failed;
    /** Message dropped unsent; the buffer must be released and not stored late. */
    private boolean _discarded;
    /** Max time the sender thread will wait for an in-flight prefetch before falling back to a drop. */
    private static final int AWAIT_PREFETCH_TIMEOUT = 30 * 1000;

    /** Size of a chunk part. */
    private static final int BUFSIZE = PeerState.PARTSIZE;
    /** Cache supplying the message buffers. */
    private static final ByteCache _cache = ByteCache.getInstance(16, BUFSIZE);

    /**
     * The whole of a keep-alive on the wire: a length prefix of zero, which is the message,
     * and no id. Shared because it never changes and is only ever read.
     */
    private static final byte[] KEEP_ALIVE_BYTES = {0, 0, 0, 0};

    /**
     * For types KEEP_ALIVE, CHOKE, UNCHOKE, INTERESTED, UNINTERESTED, HAVE_ALL, HAVE_NONE.
     *
     * @param type the message type
     * @since 0.9.32
     */
    Message(byte type) {
        this(type, 0, 0, 0, null, 0, 0, null);
    }

    /**
     * For types HAVE, PORT, SUGGEST, ALLOWED_FAST.
     *
     * @param type the message type
     * @param piece the zero-based index of the piece the message refers to
     * @since 0.9.32
     */
    Message(byte type, int piece) {
        this(type, piece, 0, 0, null, 0, 0, null);
    }

    /**
     * For types REQUEST, REJECT, CANCEL.
     *
     * @param type the message type
     * @param piece the zero-based index of the piece requested, rejected or cancelled
     * @param begin the offset within the piece, in bytes
     * @param length the number of bytes requested, rejected or cancelled
     * @since 0.9.32
     */
    Message(byte type, int piece, int begin, int length) {
        this(type, piece, begin, length, null, 0, 0, null);
    }

    /**
     * For type BITFIELD.
     *
     * @param data the encoded bitfield, sent as the message payload
     * @since 0.9.32
     */
    Message(byte[] data) {
        this(BITFIELD, 0, 0, 0, data, 0, data.length, null);
    }

    /**
     * For type EXTENSION.
     *
     * @param id the extended message id carried in the extension header
     * @param data the extension payload
     * @since 0.9.32
     */
    Message(int id, byte[] data) {
        this(EXTENSION, id, 0, 0, data, 0, data.length, null);
    }

    /**
     * For type PIECE with deferred data.
     *
     * @param piece the zero-based index of the piece being sent
     * @param begin the offset within the piece where the data starts, in bytes
     * @param length the number of bytes of piece data to send
     * @param loader the loader that supplies the piece data when it is sent
     * @since 0.9.32
     */
    Message(int piece, int begin, int length, DataLoader loader) {
        this(PIECE, piece, begin, length, null, 0, length, loader);
    }

    /**
     * Creates a peer protocol message from its fields.
     *
     * @param type the protocol message type
     * @param piece the piece number
     * @param begin the offset within the piece
     * @param length the length of the message data
     * @param data the data bytes, null for a deferred load
     * @param off the offset within the data array
     * @param len the length of the data in the array
     * @param loader the loader supplying the data
     * @since 0.9.32
     */
    private Message(
            byte type,
            int piece,
            int begin,
            int length,
            byte[] data,
            int off,
            int len,
            DataLoader loader) {
        this.type = type;
        this.piece = piece;
        this.begin = begin;
        this.length = length;
        this.data = data;
        this.off = off;
        this.len = len;
        dataLoader = loader;
    }

    /**
     * Whether this message id is followed by a 4-byte piece number on the wire.
     *
     * @param type the message id
     * @return true if a piece number follows this id
     */
    static boolean hasPieceNumber(byte type) {
        return type == HAVE
                || type == REQUEST
                || type == PIECE
                || type == CANCEL
                || type == SUGGEST
                || type == REJECT
                || type == ALLOWED_FAST;
    }

    /**
     * Whether this message id is followed by a 4-byte offset within the piece.
     *
     * @param type the message id
     * @return true if a byte offset follows this id
     */
    static boolean hasOffset(byte type) {
        return type == REQUEST || type == PIECE || type == CANCEL || type == REJECT;
    }

    /**
     * Whether this message id is followed by a 4-byte transfer length.
     *
     * @param type the message id
     * @return true if a transfer length follows this id
     */
    static boolean hasTransferLength(byte type) {
        return type == REQUEST || type == CANCEL || type == REJECT;
    }

    /**
     * Whether this message id is followed by the 1-byte BEP 10 sub-type.
     *
     * @param type the message id
     * @return true if an extended message id follows this id
     */
    static boolean hasSubType(byte type) {
        return type == EXTENSION;
    }

    /**
     * Whether this message id is followed by the 2-byte BEP 5 port.
     *
     * @param type the message id
     * @return true if a listening port follows this id
     */
    static boolean hasPort(byte type) {
        return type == PORT;
    }

    /**
     * Whether a byte-array payload follows this message's fixed header.
     *
     * @param type the message id
     * @return true if this id carries payload bytes after its header
     */
    static boolean hasPayload(byte type) {
        return type == BITFIELD || type == PIECE || type == EXTENSION;
    }

    /**
     * How many bytes a message of this id holds before its payload: the id itself plus
     * whichever of the piece number, offset, transfer length, sub-type or port fields that
     * id uses. These are the only fields any message id uses, so this is also the number of
     * bytes a reader must take before it knows what the message is.
     *
     * @param type the message id
     * @return the fixed byte count after the 4-byte length prefix, at least one
     * @since 0.9.72+
     */
    static int fixedBytes(byte type) {
        int rv = 1;
        if (hasPieceNumber(type)) rv += 4;
        if (hasOffset(type)) rv += 4;
        if (hasTransferLength(type)) rv += 4;
        if (hasSubType(type)) rv += 1;
        if (hasPort(type)) rv += 2;
        return rv;
    }

    /**
     * Writes a big-endian int into a byte array.
     *
     * @param buf the array to write into
     * @param off where in the array to write
     * @param val the value to write
     * @return off plus four, the position after what was written
     */
    private static int putInt(byte[] buf, int off, int val) {
        buf[off] = (byte) (val >>> 24);
        buf[off + 1] = (byte) (val >>> 16);
        buf[off + 2] = (byte) (val >>> 8);
        buf[off + 3] = (byte) val;
        return off + 4;
    }

    /**
     * Reads back what {@link #putInt} wrote, so the reader and the writer cannot disagree
     * about the byte order of a protocol field.
     *
     * @param buf the bytes to read from
     * @param off where in the array to start
     * @return the 4-byte big-endian value at that position
     * @since 0.9.72+
     */
    static int readInt(byte[] buf, int off) {
        return ((buf[off] & 0xff) << 24) | ((buf[off + 1] & 0xff) << 16) |
               ((buf[off + 2] & 0xff) << 8) | (buf[off + 3] & 0xff);
    }

    /**
     * Reads a 2-byte big-endian field, the BEP 5 port.
     *
     * @param buf the bytes to read from
     * @param off where in the array to start
     * @return the unsigned value at that position, 0-65535
     * @since 0.9.72+
     */
    static int readUnsignedShort(byte[] buf, int off) {
        return ((buf[off] & 0xff) << 8) | (buf[off + 1] & 0xff);
    }

    /**
     * Reads a 1-byte field, the BEP 10 extended message id.
     *
     * @param buf the bytes to read from
     * @param off where in the array to start
     * @return the unsigned value at that position, 0-255
     * @since 0.9.72+
     */
    static int readUnsignedByte(byte[] buf, int off) {
        return buf[off] & 0xff;
    }

    /**
     * The widest fixed header any message id has: the id itself plus the piece number, offset
     * and transfer length a REQUEST carries. A reader sizes its header buffer from this.
     */
    static final int MAX_FIXED_BYTES = 1 + 4 + 4 + 4;

    /**
     * Assembles this message's fixed header - the length prefix, the id, and the numeric
     * fields that id uses - into one array.
     *
     * @return the header bytes, ready to hand to the socket in a single write
     */
    private byte[] header() {
        byte[] buf = new byte[4 + fixedBytes(type)];
        int p = putInt(buf, 0, fixedBytes(type) + (hasPayload(type) ? len : 0));
        buf[p++] = type;
        if (hasPieceNumber(type)) p = putInt(buf, p, piece);
        if (hasOffset(type)) p = putInt(buf, p, begin);
        if (hasTransferLength(type)) p = putInt(buf, p, length);
        else if (hasSubType(type)) buf[p++] = (byte) (piece & 0xff);
        else if (hasPort(type)) {
            buf[p++] = (byte) (piece >>> 8);
            buf[p++] = (byte) (piece & 0xff);
        }
        return buf;
    }

    /**
     * Sends this message through the given DataOutputStream. The fixed header goes out as one
     * array write and the payload, when there is one, as a second, so a REQUEST - the most
     * common message by a wide margin, and nothing but fixed bytes - is a single write. It
     * used to be five: each write on the streaming MessageOutputStream underneath is its own
     * lock acquisition and its own copy into the I2P stream.
     *
     * @param dos the output stream to write to
     * @return true if the message was written, false if the deferred data could not be loaded and
     *         the message was dropped
     * @throws IOException if an I/O error occurs
     */
    boolean sendMessage(DataOutputStream dos) throws IOException {
        // KEEP_ALIVE is special: a length of zero and no id at all.
        if (type == KEEP_ALIVE) {
            dos.write(KEEP_ALIVE_BYTES, 0, KEEP_ALIVE_BYTES.length);
            return true;
        }

        // Get deferred data. Usually already prefetched by PeerConnectionOut;
        // otherwise await the in-flight load, or load inline as a fallback.
        if (data == null && dataLoader != null) {
            loadDataForSend();
            if (data == null) return false; // dropped, caller must not count it as sent
        }

        // Send the fixed header as one write, then the payload as another.
        byte[] hdr = header();
        dos.write(hdr, 0, hdr.length);

        // Send actual data
        if (hasPayload(type)) dos.write(data, off, len);

        // Buffer was pulled from cache in Storage.getPiece() via dataLoader
        releaseOwned();
        return true;
    }

    /**
     * Ensure the deferred PIECE data is available before sending.
     *
     * <p>Exactly one execution site runs the loader, decided by {@link #claimLoad}: either this
     * sender thread (inline fallback, the pre-prefetch behavior) or PeerConnectionOut's prefetch
     * task. If a prefetch is in flight, wait briefly for it rather than duplicating the disk read;
     * on timeout or discard the message is dropped and the peer will re-request. The loader always
     * runs outside the message monitor so purge paths are never blocked by disk I/O.
     *
     * @since 0.9.71+
     */
    private void loadDataForSend() {
        long start = System.currentTimeMillis();
        if (claimLoad()) {
            ByteArray ba = runLoader();
            PeerConnectionOut.recordLoad(System.currentTimeMillis() - start);
            completeLoad(ba);
        } else {
            // prefetch won the claim; wait for its result instead of re-reading from disk
            awaitLoad();
        }
    }

    /**
     * Invoke the deferred loader. Must be called only after winning {@link #claimLoad};
     * executes with no locks held so slow reads never block purge paths.
     *
     * @return the loaded buffer, or null on error (a REJECT has already been queued upstream)
     * @since 0.9.71+
     */
    ByteArray runLoader() {
        return dataLoader.loadData(piece, begin, length);
    }

    /**
     * Claim exclusive right to execute the loader for this message.
     *
     * @return true if the caller must run the loader and later call {@link #completeLoad(ByteArray)};
     *         false if already claimed, completed, failed, discarded, or not loader-backed
     * @since 0.9.71+
     */
    synchronized boolean claimLoad() {
        if (dataLoader == null || _loadClaimed || _discarded || _failed || data != null) {
            return false;
        }
        _loadClaimed = true;
        _pendingLoad = true;
        return true;
    }

    /**
     * Abort a claimed-but-not-yet-started prefetch because the message was purged unsent,
     * saving the disk read entirely.
     *
     * @return true if the caller should skip loading; pending state is cleared either way
     * @since 0.9.71+
     */
    synchronized boolean abortIfDiscarded() {
        if (_discarded) {
            _pendingLoad = false;
            notifyAll();
            return true;
        }
        return false;
    }

    /**
     * Deliver the result of a loader run started after {@link #claimLoad} returned true.
     * Stores the buffer unless the message was discarded meanwhile, in which case the buffer
     * is released immediately; failure is recorded so the sender drops the message.
     *
     * @param ba the loaded buffer, or null on load failure
     * @since 0.9.71+
     */
    synchronized void completeLoad(ByteArray ba) {
        _pendingLoad = false;
        if (ba == null) {
            _failed = true;
        } else if (_discarded || data != null) {
            // lost the race with a purge or a duplicate completion: release, don't store
            releaseBuffer(ba);
        } else {
            _ba = ba;
            data = ba.getData();
        }
        notifyAll();
    }

    /**
     * Wait for an in-flight prefetch to finish. Bounded so a wedged executor degrades to a
     * dropped block instead of stalling the sender thread indefinitely.
     *
     * @since 0.9.71+
     */
    private void awaitLoad() {
        long deadline = System.currentTimeMillis() + AWAIT_PREFETCH_TIMEOUT;
        synchronized (this) {
            while (_pendingLoad && !_discarded) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) break;
                try {
                    wait(remain);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
    }

    /**
     * Mark this message as dropped without sending and release any loaded buffer exactly once.
     * Called from all purge paths (choke, cancel, disconnect, queue overflow); safe to call
     * repeatedly and concurrently with an in-flight prefetch.
     *
     * @since 0.9.71+
     */
    synchronized void discard() {
        if (!_discarded) {
            _discarded = true;
            releaseOwnedInLock();
        }
        notifyAll();
    }

    /**
     * Release this message's buffer after a successful send. Exactly once; a no-op for
     * messages without loader-loaded data.
     *
     * @since 0.9.71+
     */
    private synchronized void releaseOwned() {
        releaseOwnedInLock();
    }

    /** Caller must hold the monitor. */
    private void releaseOwnedInLock() {
        ByteArray ba = _ba;
        if (ba != null) {
            _ba = null;
            data = null;
            releaseBuffer(ba);
        }
    }

    /**
     * Return a cache-acquired buffer to the pool, mirroring {@link org.klomp.snark.Storage#getPiece}'s acquire
     * condition; non-cache-backed partial-read buffers are simply dropped for GC.
     *
     * @param ba the buffer to release
     */
    private static void releaseBuffer(ByteArray ba) {
        if (ba.getData().length == BUFSIZE) _cache.release(ba, false);
    }

    /**
     * Whether the deferred data has been loaded and not yet released.
     *
     * @return true if ready to send without further loading
     * @since 0.9.71+
     */
    synchronized boolean isDataReady() {
        return data != null;
    }

    /**
     * Whether this message was dropped without sending.
     *
     * @return true if discarded
     * @since 0.9.71+
     */
    synchronized boolean isDiscarded() {
        return _discarded;
    }

    /**
     * A string representation of the message.
     *
     * @return the type name, with fields in parentheses for data messages
     */
    @Override
    public String toString() {
        String name = typeName();
        switch (type) {
            case HAVE:
            case PORT:
            case SUGGEST:
            case ALLOWED_FAST:
                return name + '(' + piece + ')';
            case REQUEST:
            case PIECE:
            case CANCEL:
            case REJECT:
                return name + '(' + piece + ',' + begin + ',' + length + ')';
            case EXTENSION:
                // data is null for a queued-but-unsent extension message
                return name + '(' + piece + ',' + (data != null ? data.length : 0) + ')';
            default:
                return name;
        }
    }

    /**
     * The protocol name of the message type, on its own, so a log line can put the
     * fields somewhere other than immediately after the name.
     *
     * @return the upper-case protocol name, e.g. {@code REQUEST}
     * @since 0.9.72+
     */
    String typeName() {
        switch (type) {
            case KEEP_ALIVE:
                return "KEEP_ALIVE";
            case CHOKE:
                return "CHOKE";
            case UNCHOKE:
                return "UNCHOKE";
            case INTERESTED:
                return "INTERESTED";
            case UNINTERESTED:
                return "UNINTERESTED";
            case HAVE:
                return "HAVE";
            case BITFIELD:
                return "BITFIELD";
            case REQUEST:
                return "REQUEST";
            case PIECE:
                return "PIECE";
            case CANCEL:
                return "CANCEL";
            case PORT:
                return "PORT";
            case EXTENSION:
                return "EXTENSION";
            // fast extensions below here
            case SUGGEST:
                return "SUGGEST";
            case HAVE_ALL:
                return "HAVE_ALL";
            case HAVE_NONE:
                return "HAVE_NONE";
            case REJECT:
                return "REJECT";
            case ALLOWED_FAST:
                return "ALLOWED_FAST";
            // BEP 52 below here
            case HASH_REQUEST:
                return "HASH_REQUEST";
            case HASHES:
                return "HASHES";
            case HASH_REJECT:
                return "HASH_REJECT";
            default:
                return "UNKNOWN (" + type + ')';
        }
    }

    /**
     * What the message carried, in the {@code key: value} form the logging guide
     * asks for, led by the arrow that separates the event from its details. A comma
     * tuple such as {@code REQUEST(27,0,16384)} names nothing a reader can act on,
     * and the brackets of the {@code toString()} form are the form the console log
     * viewer parses as a destination.
     *
     * @return {@code " -> Piece: 27 * Offset: 0 bytes * Size: 16384 bytes"} for a
     *         message with fields, or an empty string for one that carries none
     * @since 0.9.72+
     */
    String logArgs() {
        switch (type) {
            case HAVE:
            case SUGGEST:
            case ALLOWED_FAST:
                return " -> Piece: " + piece;
            case PORT:
                // BEP 6 keeps the UDP port in the field the other messages use for
                // the piece index
                return " -> Port: " + piece;
            case REQUEST:
            case PIECE:
            case CANCEL:
            case REJECT:
                return " -> Piece: " + piece + " * Offset: " + begin + " bytes * Size: "
                       + length + " bytes";
            case EXTENSION:
                // data is null for a queued-but-unsent extension message
                return " -> Size: " + (data != null ? data.length : 0) + " bytes";
            default:
                return "";
        }
    }
}
