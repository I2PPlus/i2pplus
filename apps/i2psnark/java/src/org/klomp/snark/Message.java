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

    /** The message type */
    // Not all fields are used for every message.
    // KEEP_ALIVE doesn't have a real wire representation
    final byte type;

    /** The piece index */
    // Used for HAVE, REQUEST, PIECE and CANCEL messages.
    // Also SUGGEST, REJECT, ALLOWED_FAST
    // low byte used for EXTENSION message
    // low two bytes used for PORT message
    final int piece;

    /** The offset within the piece */
    // Used for REQUEST, PIECE and CANCEL messages.
    // Also REJECT
    final int begin;
    /** The data length */
    final int length;

    /** The data payload */
    // Used for PIECE and BITFIELD and EXTENSION messages
    byte[] data;
    /** The data offset */
    final int off;
    /** The payload length */
    final int len;

    // Used to do deferred fetch of data
    private final DataLoader dataLoader;

    // Prefetch coordination for PIECE messages with a deferred loader.
    // The buffer may be loaded off the sender thread by PeerConnectionOut's
    // prefetch executor; all state transitions below are guarded by 'this'
    // so the sender thread, the executor, and purge paths agree.
    /** Cache-backed buffer owned by this message, non-null between successful load and release. */
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

    private static final int BUFSIZE = PeerState.PARTSIZE;
    private static final ByteCache _cache = ByteCache.getInstance(16, BUFSIZE);

    /**
     * For types KEEP_ALIVE, CHOKE, UNCHOKE, INTERESTED, UNINTERESTED, HAVE_ALL, HAVE_NONE
     *
     * @since 0.9.32
     */
    Message(byte type) {
        this(type, 0, 0, 0, null, 0, 0, null);
    }

    /**
     * For types HAVE, PORT, SUGGEST, ALLOWED_FAST
     *
     * @since 0.9.32
     */
    Message(byte type, int piece) {
        this(type, piece, 0, 0, null, 0, 0, null);
    }

    /**
     * For types REQUEST, REJECT, CANCEL
     *
     * @since 0.9.32
     */
    Message(byte type, int piece, int begin, int length) {
        this(type, piece, begin, length, null, 0, 0, null);
    }

    /**
     * For type BITFIELD
     *
     * @since 0.9.32
     */
    Message(byte[] data) {
        this(BITFIELD, 0, 0, 0, data, 0, data.length, null);
    }

    /**
     * For type EXTENSION
     *
     * @since 0.9.32
     */
    Message(int id, byte[] data) {
        this(EXTENSION, id, 0, 0, data, 0, data.length, null);
    }

    /**
     * For type PIECE with deferred data
     *
     * @since 0.9.32
     */
    Message(int piece, int begin, int length, DataLoader loader) {
        this(PIECE, piece, begin, length, null, 0, length, loader);
    }

    /**
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
     * Sends this message through the given DataOutputStream.
     *
     * @param dos the output stream to write to
     * @return true if the message was written, false if the deferred data could not be loaded and
     *     the message was dropped
     * @throws IOException if an I/O error occurs
     */
    boolean sendMessage(DataOutputStream dos) throws IOException {
        // KEEP_ALIVE is special.
        if (type == KEEP_ALIVE) {
            dos.writeInt(0);
            return true;
        }

        // Get deferred data. Usually already prefetched by PeerConnectionOut;
        // otherwise await the in-flight load, or load inline as a fallback.
        if (data == null && dataLoader != null) {
            loadDataForSend();
            if (data == null) return false; // dropped, caller must not count it as sent
        }

        // Calculate the total length in bytes

        // Type is one byte.
        int datalen = 1;

        // piece is 4 bytes.
        if (type == HAVE
                || type == REQUEST
                || type == PIECE
                || type == CANCEL
                || type == SUGGEST
                || type == REJECT
                || type == ALLOWED_FAST) datalen += 4;

        // begin/offset is 4 bytes
        if (type == REQUEST || type == PIECE || type == CANCEL || type == REJECT) datalen += 4;

        // length is 4 bytes
        if (type == REQUEST || type == CANCEL || type == REJECT) datalen += 4;

        // msg type is 1 byte
        else if (type == EXTENSION) datalen += 1;
        else if (type == PORT) datalen += 2;

        // add length of data for piece or bitfield array.
        if (type == BITFIELD || type == PIECE || type == EXTENSION) datalen += len;

        // Send length
        dos.writeInt(datalen);
        dos.writeByte(type & 0xFF);

        // Send additional info (piece number)
        if (type == HAVE
                || type == REQUEST
                || type == PIECE
                || type == CANCEL
                || type == SUGGEST
                || type == REJECT
                || type == ALLOWED_FAST) dos.writeInt(piece);

        // Send additional info (begin/offset)
        if (type == REQUEST || type == PIECE || type == CANCEL || type == REJECT)
            dos.writeInt(begin);

        // Send additional info (length); for PIECE this is implicit.
        if (type == REQUEST || type == CANCEL || type == REJECT) dos.writeInt(length);
        else if (type == EXTENSION) dos.writeByte((byte) piece & 0xff);
        else if (type == PORT) dos.writeShort(piece & 0xffff);

        // Send actual data
        if (type == BITFIELD || type == PIECE || type == EXTENSION) dos.write(data, off, len);

        // Buffer was pulled from cache in Storage.getPiece() via dataLoader
        releaseOwned();
        return true;
    }

    /**
     * Ensure the deferred PIECE data is available before sending.
     *
     * <p>Exactly one execution site runs the loader, decided by {@link #claimLoad()}: either this
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
     * Invoke the deferred loader. Must be called only after winning {@link #claimLoad()};
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
     * Deliver the result of a loader run started after {@link #claimLoad()} returned true.
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
     * Return a cache-acquired buffer to the pool, mirroring Storage.getPiece()'s acquire
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
     * @return a string representation of the message
     */
    @Override
    public String toString() {
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
                return "HAVE(" + piece + ')';
            case BITFIELD:
                return "BITFIELD";
            case REQUEST:
                return "REQUEST(" + piece + ',' + begin + ',' + length + ')';
            case PIECE:
                return "PIECE(" + piece + ',' + begin + ',' + length + ')';
            case CANCEL:
                return "CANCEL(" + piece + ',' + begin + ',' + length + ')';
            case PORT:
                return "PORT(" + piece + ')';
            case EXTENSION:
                return "EXTENSION(" + piece + ',' + data.length + ')';
            // fast extensions below here
            case SUGGEST:
                return "SUGGEST(" + piece + ')';
            case HAVE_ALL:
                return "HAVE_ALL";
            case HAVE_NONE:
                return "HAVE_NONE";
            case REJECT:
                return "REJECT(" + piece + ',' + begin + ',' + length + ')';
            case ALLOWED_FAST:
                return "ALLOWED_FAST(" + piece + ')';
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
}
