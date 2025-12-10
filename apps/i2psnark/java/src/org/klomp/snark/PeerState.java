/* PeerState - Keeps track of the Peer state through connection callbacks.
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.util.Log;
import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.bencode.InvalidBEncodingException;

/**
 * Manages the state and protocol logic for a BitTorrent peer connection.<br>
 * This class tracks peer interest/choking status, piece availability, request queues, and handles
 * all BitTorrent protocol message processing.
 *
 * @author Mark J. Wielaard
 * @since 0.1
 */
class PeerState implements DataLoader {
    private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(PeerState.class);
    private final Peer peer;

    /** Fixme, used by Peer.disconnect() to get to the coordinator */
    final PeerListener listener;

    private final BandwidthListener bwListener;

    /** Null before we have it. locking: this */
    private MetaInfo metainfo;

    /** Null unless needed. Contains -1 for all. locking: this */
    private List<Integer> havesBeforeMetaInfo;

    // Interesting and choking describes whether we are interested in or
    // are choking the other side.
    volatile boolean interesting;
    volatile boolean choking = true;

    // Interested and choked describes whether the other side is
    // interested in us or choked us.
    volatile boolean interested;
    volatile boolean choked = true;

    /** the pieces the peer has. locking: this */
    BitField bitfield;

    // Package local for use by Peer.
    final PeerConnectionIn in;
    final PeerConnectionOut out;

    // Outstanding request
    private final List<Request> outstandingRequests = new ArrayList<Request>();

    /** the tail (NOT the head) of the request queue */
    private Request lastRequest;

    private int currentMaxPipeline;

    // FIXME if piece size < PARTSIZE, pipeline could be bigger
    /**
     * @since 0.9.47
     */
    public static final int MIN_PIPELINE = 5; // this is for outbound requests

    /**
     * @since public since 0.9.47
     */
    public static final int MAX_PIPELINE = 8; // this is for outbound requests

    public static final int PARTSIZE = 16 * 1024; // outbound request
    private static final int MAX_PIPELINE_BYTES =
            (MAX_PIPELINE + 2) * PARTSIZE; // this is for inbound requests
    private static final int MAX_PARTSIZE = 64 * 1024; // Don't let anybody request more than this
    private static final Integer PIECE_ALL = Integer.valueOf(-1);

    /**
     * @param metainfo null if in magnet mode
     */
    PeerState(
            Peer peer,
            PeerListener listener,
            BandwidthListener bwl,
            MetaInfo metainfo,
            PeerConnectionIn in,
            PeerConnectionOut out) {
        this.peer = peer;
        this.listener = listener;
        bwListener = bwl;
        this.metainfo = metainfo;

        this.in = in;
        this.out = out;
    }

    /**
     * @since 0.9.62
     */
    BandwidthListener getBandwidthListener() {
        return bwListener;
    }

    // NOTE Methods that inspect or change the state synchronize (on this).

    void keepAliveMessage() {
        if (_log.shouldDebug()) _log.debug("Received keepalive request from [" + peer + "]");
        /* XXX - ignored */
    }

    void chokeMessage(boolean choke) {
        if (_log.shouldDebug())
            _log.debug(
                    "Received "
                            + (choke ? "" : "un")
                            + "choked status message from ["
                            + peer
                            + "]");

        boolean resend = choked && !choke;
        choked = choke;

        listener.gotChoke(peer, choke);

        if (interesting && !choked) request(resend);

        if (choked) {
            out.cancelRequestMessages();
            // old Roberts thrash us here, choke+unchoke right together
            // The only problem with returning the partials to the coordinator
            // is that chunks above a missing request are lost.
            // Future enhancements to PartialPiece could keep track of the holes.
            List<Request> pcs = returnPartialPieces();
            if (!pcs.isEmpty()) {
                if (_log.shouldDebug())
                    _log.debug(
                            "["
                                    + peer
                                    + "] got choked, returning partial pieces to the"
                                    + " PeerCoordinator: "
                                    + pcs);
                listener.savePartialPieces(this.peer, pcs);
            }
        }
    }

    void interestedMessage(boolean interest) {
        if (_log.shouldDebug())
            _log.debug("[" + peer + "] rcv " + (interest ? "" : "un") + "interested");
        interested = interest;
        listener.gotInterest(peer, interest);
    }

    void haveMessage(int piece) {
        if (_log.shouldDebug()) _log.debug("[" + peer + "] rcv have(" + piece + ")");
        // Sanity check
        if (piece < 0) {
            if (_log.shouldWarn())
                _log.warn("Received strange 'have: " + piece + "' message from [" + peer + "]");
            return;
        }

        synchronized (this) {
            if (metainfo == null) {
                if (_log.shouldWarn())
                    _log.warn("Received HAVE " + piece + " before metainfo from [" + peer + "]");
                if (bitfield != null) {
                    if (piece < bitfield.size()) bitfield.set(piece);
                } else {
                    // note reception for later
                    if (havesBeforeMetaInfo == null) {
                        havesBeforeMetaInfo = new ArrayList<Integer>(8);
                    } else if (havesBeforeMetaInfo.size() > 1000) {
                        // don't blow up
                        if (_log.shouldWarn())
                            _log.warn(
                                    "Received too many haves before metainfo from [" + peer + "]");
                        return;
                    }
                    havesBeforeMetaInfo.add(Integer.valueOf(piece));
                }
                return;
            }

            // Sanity check
            if (piece >= metainfo.getPieces()) {
                // XXX disconnect?
                if (_log.shouldWarn())
                    _log.warn("Received strange 'have: " + piece + "' message from [" + peer + "]");
                return;
            }

            // Can happen if the other side never send a bitfield message.
            if (bitfield == null) bitfield = new BitField(metainfo.getPieces());
            bitfield.set(piece);
        }

        if (listener.gotHave(peer, piece)) setInteresting(true);
    }

    void bitfieldMessage(byte[] bitmap) {
        bitfieldMessage(bitmap, false);
    }

    /**
     * @param bitmap null to use the isAll param
     * @param isAll only if bitmap == null: true for have_all, false for have_none
     * @since 0.9.21
     */
    private void bitfieldMessage(byte[] bitmap, boolean isAll) {
        if (_log.shouldDebug()) {
            if (bitmap != null) {
                _log.debug("[" + peer + "] rcv bitfield bytes: " + bitmap.length);
            } else if (isAll) {
                _log.debug("[" + peer + "] rcv bitfield HAVE_ALL");
            } else {
                _log.debug("[" + peer + "] rcv bitfield HAVE_NONE");
            }
        }

        synchronized (this) {
            if (bitfield != null) {
                // XXX - Be liberal in what you accept?
                if (_log.shouldWarn()) {
                    _log.warn("Received unexpected bitfield message from [" + peer + "]");
                }
                return;
            }

            // XXX - Check for weird bitfield and disconnect?
            // Will have to regenerate the bitfield after we know exactly
            // how many pieces there are, as we don't know how many spare bits there are.
            // This happens in setMetaInfo() below.
            if (metainfo == null) {
                if (bitmap != null) {
                    bitfield = new BitField(bitmap, bitmap.length * 8);
                } else {
                    if (_log.shouldWarn()) _log.warn("Have_x without metainfo: " + isAll);
                    if (isAll) {
                        // note reception for later
                        if (havesBeforeMetaInfo == null)
                            havesBeforeMetaInfo = new ArrayList<Integer>(1);
                        else havesBeforeMetaInfo.clear();
                        havesBeforeMetaInfo.add(PIECE_ALL);
                    } // else HAVE_NONE, ignore
                }
                return;
            } else {
                if (bitmap != null) {
                    bitfield = new BitField(bitmap, metainfo.getPieces());
                } else {
                    bitfield = new BitField(metainfo.getPieces());
                    if (isAll) bitfield.setAll();
                }
            }
        } // synch

        boolean interest = listener.gotBitField(peer, bitfield);
        if (bitfield.complete() && !interest) {
            // They are seeding and we are seeding,
            // why did they contact us? (robert)
            // Dump them quick before we send our whole bitmap

            // If we both support comments, allow it
            if (listener.getUtil().utCommentsEnabled()) {
                Map<String, BEValue> handshake = peer.getHandshakeMap();
                if (handshake != null) {
                    BEValue bev = handshake.get("m");
                    if (bev != null) {
                        try {
                            if (bev.getMap().get(ExtensionHandler.TYPE_COMMENT) != null) {
                                if (_log.shouldInfo()) {
                                    _log.info(
                                            "Seed ["
                                                    + peer
                                                    + "] connected to check for new comments");
                                }
                                setInteresting(interest);
                                return;
                            }
                        } catch (InvalidBEncodingException ibee) {
                        }
                    }
                }
            }
            if (_log.shouldWarn()) {
                _log.warn("Disconnecting seed that connects to seeds: [" + peer + "]");
            }
            peer.disconnect(true);
        } else {
            setInteresting(interest);
        }
    }

    void requestMessage(int piece, int begin, int length) {
        if (_log.shouldDebug())
            _log.debug(
                    "Request received from ["
                            + peer
                            + "] for ("
                            + piece
                            + ", "
                            + begin
                            + ", "
                            + length
                            + ") ");
        if (metainfo == null) return;
        if (choking) {
            if (peer.supportsFast()) {
                if (_log.shouldDebug())
                    _log.debug("Request received, sending reject to choked [" + peer + "]");
                out.sendReject(piece, begin, length);
            } else {
                if (_log.shouldDebug()) _log.debug("Request received, but choking [" + peer + "]");
            }
            return;
        }

        // Sanity check
        // There is no check here that we actually have the piece;
        // this will be caught in loadData() below
        if (piece < 0
                || piece >= metainfo.getPieces()
                || begin < 0
                || begin > metainfo.getPieceLength(piece)
                || length <= 0
                || length > MAX_PARTSIZE) {
            // XXX - Protocol error -> disconnect?
            if (_log.shouldDebug())
                _log.debug(
                        "Received strange request from ["
                                + peer
                                + "] for ("
                                + piece
                                + ", "
                                + begin
                                + ", "
                                + length
                                + ") - protocol error");
            if (peer.supportsFast()) out.sendReject(piece, begin, length);
            return;
        }

        // Limit total pipelined requests to MAX_PIPELINE bytes
        // to conserve memory and prevent DOS
        // Todo: limit number of requests also? (robert 64 x 4KB)
        if (out.queuedBytes() + length > MAX_PIPELINE_BYTES) {
            if (peer.supportsFast()) {
                if (_log.shouldInfo())
                    _log.info("Rejecting request over pipeline limit from [" + peer + "]");
                out.sendReject(piece, begin, length);
            } else {
                if (_log.shouldInfo())
                    _log.info("Discarding request over pipeline limit from [" + peer + "]");
            }
            return;
        }

        if (_log.shouldDebug())
            _log.debug(
                    "Queueing ("
                            + piece
                            + ", "
                            + begin
                            + ", "
                            + length
                            + ")"
                            + " to ["
                            + peer
                            + "]");

        // don't load the data into mem now, let PeerConnectionOut do it
        out.sendPiece(piece, begin, length, this);
    }

    /**
     * This is the callback that PeerConnectionOut calls
     *
     * @return bytes or null for errors such as not having the piece yet
     * @throws RuntimeException on IOE getting the data
     * @since 0.8.2
     */
    public ByteArray loadData(int piece, int begin, int length) {
        ByteArray pieceBytes = listener.gotRequest(peer, piece, begin, length);
        if (pieceBytes == null) {
            // XXX - Protocol error-> diconnect?
            if (_log.shouldDebug())
                _log.debug("Received request for unknown piece [" + piece + "]");
            if (peer.supportsFast()) out.sendReject(piece, begin, length);
            return null;
        }

        // More sanity checks
        if (length != pieceBytes.getData().length) {
            // XXX - Protocol error-> disconnect?
            if (_log.shouldDebug())
                _log.debug(
                        "Received out of range 'request: "
                                + piece
                                + ", "
                                + begin
                                + ", "
                                + length
                                + "' message from ["
                                + peer
                                + "]");
            if (peer.supportsFast()) out.sendReject(piece, begin, length);
            return null;
        }

        if (_log.shouldDebug())
            _log.debug(
                    "Sending ("
                            + piece
                            + ", "
                            + begin
                            + ", "
                            + length
                            + ")"
                            + " to ["
                            + peer
                            + "]");
        return pieceBytes;
    }

    /**
     * Called when some bytes have left the outgoing connection. XXX - Should indicate whether it
     * was a real piece or overhead.
     */
    void uploaded(int size) {
        peer.uploaded(size);
    }

    // This is used to flag that we have to back up from the firstOutstandingRequest
    // when calculating how far we've gotten
    private Request pendingRequest;

    /**
     * Called when a full chunk (i.e. a piece message) has been received by PeerConnectionIn.
     *
     * <p>This may block quite a while if it is the last chunk for a piece, as it calls the
     * listener, who stores the piece and then calls havePiece for every peer on the torrent
     * (including us).
     */
    void pieceMessage(Request req) {
        int size = req.len;
        // Now reported byte-by-byte in PartialPiece
        // peer.downloaded(size);

        if (_log.shouldDebug())
            _log.debug(
                    "Received end of chunk ("
                            + req.getPiece()
                            + ","
                            + req.off
                            + ","
                            + req.len
                            + ") from ["
                            + peer
                            + "]");

        // Last chunk needed for this piece?
        PartialPiece pp = req.getPartialPiece();
        boolean complete = pp.isComplete();
        if (complete) {
            // warning - may block here for a while
            if (listener.gotPiece(peer, pp)) {
                if (_log.shouldDebug())
                    _log.debug("Received piece [" + req.getPiece() + "] from [" + peer + "]");
            } else {
                if (_log.shouldInfo())
                    _log.info("Received BAD piece [" + req.getPiece() + "] from [" + peer + "]");
                synchronized (this) {
                    // so we don't ask again
                    if (bitfield != null) bitfield.clear(req.getPiece());
                }
            }
        }

        // ok done with this one
        synchronized (this) {
            pendingRequest = null;
        }

        // getOutstandingRequest() was called by PeerConnectionIn at the start of the chunk;
        // if the bandwidth limiter throttled us to zero requests then, try again now
        if (outstandingRequests.isEmpty()) {
            addRequest();
            if (!complete) {
                synchronized (this) {
                    if (outstandingRequests.isEmpty()) {
                        // we MUST return the partial piece to PeerCoordinator,
                        // or else we will lose it and leak the data
                        if (_log.shouldWarn())
                            _log.warn("Throttled, returned to coord. w/ data " + req);
                        List<Request> pcs = Collections.singletonList(req);
                        listener.savePartialPieces(this.peer, pcs);
                        lastRequest = null;
                    }
                }
            }
        }
    }

    /**
     * TODO this is how we tell we got all the chunks in pieceMessage() above.
     *
     * @return index in outstandingRequests or -1
     */
    private synchronized int getFirstOutstandingRequest(int piece) {
        for (int i = 0; i < outstandingRequests.size(); i++) {
            if (outstandingRequests.get(i).getPiece() == piece) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Called when a piece message is being processed by the incoming connection. That is, when the
     * header of the piece message was received. Returns null when there was no such request. It
     * also requeues/sends requests when it thinks that they must have been lost.
     */
    Request getOutstandingRequest(int piece, int begin, int length) {
        if (_log.shouldDebug()) {
            _log.debug(
                    "Received start of chunk ("
                            + piece
                            + ","
                            + begin
                            + ","
                            + length
                            + ") from ["
                            + peer
                            + "]");
        }

        // Lookup the correct piece chunk request from the list.
        Request req;
        synchronized (this) {
            int r = getFirstOutstandingRequest(piece);

            // Unrequested piece number?
            if (r == -1) {
                if (_log.shouldDebug()) {
                    _log.debug(
                            "Unrequested piece: "
                                    + piece
                                    + ", "
                                    + begin
                                    + ", "
                                    + length
                                    + " received from ["
                                    + peer
                                    + "]");
                }
                return null;
            }

            req = outstandingRequests.get(r);
            while (req.getPiece() == piece
                    && req.off != begin
                    && r < outstandingRequests.size() - 1) {
                r++;
                req = outstandingRequests.get(r);
            }

            // Something wrong?
            if (req.getPiece() != piece || req.off != begin || req.len != length) {
                if (_log.shouldDebug()) {
                    _log.debug(
                            "Unrequested or unneeded 'piece: "
                                    + piece
                                    + ", "
                                    + begin
                                    + ", "
                                    + length
                                    + "' from ["
                                    + peer
                                    + "]");
                }
                return null;
            }

            // note that this request is being read
            pendingRequest = req;

            // Report missing requests.
            if (r != 0) {
                if (_log.shouldDebug()) {
                    _log.debug("Some requests dropped, got " + req + ", wanted for [" + peer + "]");
                }
                for (int i = 0; i < r; i++) {
                    Request dropReq = outstandingRequests.remove(0);
                    outstandingRequests.add(dropReq);
                    if (!choked) {
                        out.sendRequest(dropReq);
                    }
                    if (_log.shouldDebug()) {
                        _log.debug("Dropping " + dropReq + " with [" + peer + "]");
                    }
                }
            }
            outstandingRequests.remove(0);
        }

        addRequest(); // Request more if necessary to keep the pipeline filled.
        return req;
    }

    /**
     * @return lowest offset of any request for the piece
     * @since 0.8.2
     */
    private synchronized Request getLowestOutstandingRequest(int piece) {
        Request rv = null;
        int lowest = Integer.MAX_VALUE;
        for (Request r : outstandingRequests) {
            if (r.getPiece() == piece && r.off < lowest) {
                lowest = r.off;
                rv = r;
            }
        }
        if (pendingRequest != null
                && pendingRequest.getPiece() == piece
                && pendingRequest.off < lowest) rv = pendingRequest;

        if (_log.shouldDebug())
            _log.debug(
                    "["
                            + peer
                            + "] lowest for piece ["
                            + piece
                            + "] is "
                            + rv
                            + " out of "
                            + pendingRequest
                            + " and "
                            + outstandingRequests);
        return rv;
    }

    /**
     * Get partial pieces, give them back to PeerCoordinator. Clears the request queue.
     *
     * @return List of PartialPieces, even those with an offset == 0, or empty list
     * @since 0.8.2
     */
    synchronized List<Request> returnPartialPieces() {
        Set<Integer> pcs = getRequestedPieces();
        List<Request> rv = new ArrayList<Request>(pcs.size());
        for (Integer p : pcs) {
            Request req = getLowestOutstandingRequest(p.intValue());
            if (req != null) rv.add(req);
        }
        outstandingRequests.clear();
        pendingRequest = null;
        lastRequest = null;
        return rv;
    }

    /**
     * @return all pieces we are currently requesting, or empty Set
     */
    private synchronized Set<Integer> getRequestedPieces() {
        Set<Integer> rv = new HashSet<Integer>(outstandingRequests.size() + 1);
        for (Request req : outstandingRequests) {
            rv.add(Integer.valueOf(req.getPiece()));
            if (pendingRequest != null) rv.add(Integer.valueOf(pendingRequest.getPiece()));
        }
        return rv;
    }

    void cancelMessage(int piece, int begin, int length) {
        if (_log.shouldDebug())
            _log.debug("Received cancel message (" + piece + ", " + begin + ", " + length + ")");
        out.cancelRequest(piece, begin, length);
    }

    /**
     * @since 0.8.2
     */
    void extensionMessage(int id, byte[] bs) {
        if (metainfo != null
                && metainfo.isPrivate()
                && (id == ExtensionHandler.ID_METADATA || id == ExtensionHandler.ID_PEX)) {
            // shouldn't get this since we didn't advertise it but they could send it anyway
            if (_log.shouldInfo()) _log.info("Private torrent -> Ignoring extension message " + id);
            return;
        }
        ExtensionHandler.handleMessage(peer, listener, id, bs);
        // Peer coord will get metadata from MagnetState,
        // verify, and then call gotMetaInfo()
        listener.gotExtension(peer, id, bs);
    }

    /**
     * Switch from magnet mode to normal mode. If we already have the metainfo, this does nothing.
     *
     * @param meta non-null
     * @since 0.8.4
     */
    public synchronized void setMetaInfo(MetaInfo meta) {
        if (metainfo != null) return;
        if (bitfield != null) {
            if (bitfield.size() != meta.getPieces())
                // fix bitfield, it was too big by 1-7 bits
                bitfield = new BitField(bitfield.getFieldBytes(), meta.getPieces());
            // else no extra
        } else if (havesBeforeMetaInfo != null) {
            // initialize it now
            bitfield = new BitField(meta.getPieces());
        } else {
            // it will be initialized later
            // bitfield = new BitField(meta.getPieces());
        }
        metainfo = meta;
        if (bitfield != null) {
            if (havesBeforeMetaInfo != null) {
                // set all 'haves' we got before the metainfo in the bitfield
                for (Integer i : havesBeforeMetaInfo) {
                    if (i.equals(PIECE_ALL)) {
                        bitfield.setAll();
                        if (_log.shouldDebug()) _log.debug("Setting have_all after rcv metainfo");
                        break;
                    }
                    int piece = i.intValue();
                    if (piece >= 0 && piece < meta.getPieces()) bitfield.set(piece);
                    if (_log.shouldDebug())
                        _log.debug("Setting have " + piece + " after rcv metainfo");
                }
                havesBeforeMetaInfo = null;
            }
            if (bitfield.count() > 0) setInteresting(true);
        }
    }

    /**
     * Unused
     *
     * @since 0.8.4
     */
    void portMessage(int port) {
        // for compatibility with old DHT PORT message
        listener.gotPort(peer, port, port + 1);
    }

    /////////// fast message handlers /////////

    /**
     * BEP 6 Treated as "have" for now
     *
     * @since 0.9.21
     */
    void suggestMessage(int piece) {
        if (_log.shouldDebug())
            _log.debug("Handling suggest as have(" + piece + ") from [" + peer + "]");
        haveMessage(piece);
    }

    /**
     * BEP 6
     *
     * @param isAll true for have_all, false for have_none
     * @since 0.9.21
     */
    void haveMessage(boolean isAll) {
        bitfieldMessage(null, isAll);
    }

    /**
     * BEP 6
     *
     * @since 0.9.21
     */
    void rejectMessage(int piece, int begin, int length) {
        // Duplicates logging message from PeerConnectionIn
        //      if (_log.shouldInfo())
        //           _log.info("Received reject(" + piece + ',' + begin + ',' + length + ") from ["
        // + peer + "]");
        out.cancelRequest(piece, begin, length);
        synchronized (this) {
            Request deletedRequest = null;
            // for this piece only
            boolean haveMoreRequests = false;
            for (Iterator<Request> iter = outstandingRequests.iterator(); iter.hasNext(); ) {
                Request req = iter.next();
                if (req.getPiece() == piece) {
                    if (req.off == begin && req.len == length) {
                        iter.remove();
                        deletedRequest = req;
                    } else {
                        haveMoreRequests = true;
                    }
                }
            }
            if (deletedRequest != null && !haveMoreRequests) {
                List<Request> pcs = Collections.singletonList(deletedRequest);
                listener.savePartialPieces(this.peer, pcs);
                if (_log.shouldDebug())
                    _log.debug(
                            "Returned to coord. with data "
                                    + deletedRequest.getPartialPiece().getDownloaded()
                                    + " due to reject("
                                    + piece
                                    + ','
                                    + begin
                                    + ','
                                    + length
                                    + ") from ["
                                    + peer
                                    + "]");
            }
            if (lastRequest != null
                    && lastRequest.getPiece() == piece
                    && lastRequest.off == begin
                    && lastRequest.len == length) lastRequest = null;
        }
    }

    /**
     * BEP 6 Ignored for now
     *
     * @since 0.9.21
     */
    void allowedFastMessage(int piece) {
        if (_log.shouldDebug())
            _log.debug("Ignoring allowed_fast(" + piece + ") from [" + peer + "]");
    }

    void unknownMessage(int type, byte[] bs) {
        if (_log.shouldDebug())
            _log.debug(
                    "Warning: Ignoring unknown message type: "
                            + type
                            + " length: "
                            + bs.length
                            + " bytes");
    }

    /////////// end message handlers /////////

    /** We now have this piece. Tell the peer and cancel any requests for the piece. */
    void havePiece(int piece) {
        if (_log.shouldDebug()) _log.debug("Notifying [" + peer + "] havePiece(" + piece + ")");

        // Tell the other side that we are no longer interested in any of
        // the outstanding requests for this piece.
        cancelPiece(piece);

        // Tell the other side that we really have this piece.
        out.sendHave(piece);

        // Request something else if necessary.
        addRequest();

        /**** taken care of in addRequest()
         * synchronized(this)
         * {
         * // Is the peer still interesting?
         * if (lastRequest == null)
         * setInteresting(false);
         * }
         ****/
    }

    /**
     * Tell the other side that we are no longer interested in any of the outstanding requests (if
     * any) for this piece.
     *
     * @since 0.8.1
     */
    synchronized void cancelPiece(int piece) {
        if (lastRequest != null && lastRequest.getPiece() == piece) lastRequest = null;

        Iterator<Request> it = outstandingRequests.iterator();
        while (it.hasNext()) {
            Request req = it.next();
            if (req.getPiece() == piece) {
                it.remove();
                // Send cancel even when we are choked to make sure that it is
                // really never ever send.
                out.sendCancel(req);
                req.getPartialPiece().release();
            }
        }
    }

    /**
     * Are we currently requesting the piece?
     *
     * @deprecated deadlocks
     * @since 0.8.1
     */
    @Deprecated
    synchronized boolean isRequesting(int piece) {
        if (pendingRequest != null && pendingRequest.getPiece() == piece) return true;
        for (Request req : outstandingRequests) {
            if (req.getPiece() == piece) return true;
        }
        return false;
    }

    /**
     * Starts or resumes requesting pieces.
     *
     * @param resend should we resend outstanding requests?
     */
    private void request(boolean resend) {
        // Are there outstanding requests that have to be resend?
        if (resend) {
            synchronized (this) {
                if (!outstandingRequests.isEmpty()) {
                    out.sendRequests(outstandingRequests);
                    if (_log.shouldDebug())
                        _log.debug(
                                "Resending requests to [" + peer + "]\n* " + outstandingRequests);
                }
            }
        }

        // Add/Send some more requests if necessary.
        addRequest();
    }

    /**
     * Adds a new request to the outstanding requests list. Then send interested if we weren't. Then
     * send new requests if not choked. If nothing to request, send not interested if we were.
     *
     * <p>This is called from several places:
     *
     * <pre>
     *   By getOutstandingRequest() when the first part of a chunk comes in
     *   By havePiece() when somebody got a new piece completed
     *   By chokeMessage() when we receive an unchoke
     *   By setInteresting() when we are now interested
     *   By PeerCoordinator.updatePiecePriorities()
     * </pre>
     */
    void addRequest() {
        // no bitfield yet? nothing to request then.
        if (bitfield == null) return;
        if (metainfo == null) return;
        // Initial bw check. We do the actual accounting in PeerConnectionOut.
        // Implement a simple AIMD slow-start on the request queue size with
        // currentMaxPipeline counter.
        // Avoid cross-peer deadlocks from PeerCoordinator, call this outside the lock
        if (!bwListener.shouldRequest(peer, 0)) {
            synchronized (this) {
                // Due to changes elsewhere we can let this go down to zero now
                currentMaxPipeline /= 2;
            }
            if (_log.shouldWarn())
                _log.warn(
                        peer
                                + " throttle request, interesting? "
                                + interesting
                                + " choked? "
                                + choked
                                + " reqq: "
                                + outstandingRequests.size()
                                + " maxp: "
                                + currentMaxPipeline);
            return;
        }

        synchronized (this) {
            // adjust currentMaxPipeline
            long rate = bwListener.getDownloadRate();
            long limit = bwListener.getDownBWLimit();
            if (rate < limit * 7 / 10) {
                if (currentMaxPipeline < peer.getMaxPipeline()) {
                    currentMaxPipeline++;
                } else if (rate > limit * 9 / 10) {
                    currentMaxPipeline = 1;
                } else if (currentMaxPipeline < 2) {
                    currentMaxPipeline++;
                }
            }
            boolean more_pieces = true;
            while (more_pieces) {
                more_pieces = outstandingRequests.size() < currentMaxPipeline;
                // We want something and we don't have outstanding requests?
                if (more_pieces && lastRequest == null) {
                    // we have nothing in the queue right now
                    if (!interesting) {
                        // If we need something, set interesting but delay pulling
                        // a request from the PeerCoordinator until unchoked.
                        if (listener.needPiece(this.peer, bitfield)) {
                            setInteresting(true);
                            if (_log.shouldDebug()) {
                                _log.debug(
                                        "["
                                                + peer
                                                + "] addRequest() we need something, setting"
                                                + " interesting, delaying requestNextPiece()");
                            }
                        } else {
                            if (_log.shouldDebug()) {
                                _log.debug("[" + peer + "] addRequest() needs nothing");
                            }
                        }
                        return;
                    }
                    if (choked) {
                        // If choked, delay pulling
                        // a request from the PeerCoordinator until unchoked.
                        if (_log.shouldDebug()) {
                            _log.debug(
                                    "["
                                            + peer
                                            + "] addRequest() we are choked, delaying"
                                            + " requestNextPiece()");
                        }
                        return;
                    }
                    more_pieces = requestNextPiece();
                } else if (more_pieces) { // We want something
                    int pieceLength;
                    boolean isLastChunk;
                    pieceLength = metainfo.getPieceLength(lastRequest.getPiece());
                    isLastChunk = lastRequest.off + lastRequest.len == pieceLength;

                    // Last part of a piece?
                    if (isLastChunk) {
                        more_pieces = requestNextPiece();
                    } else {
                        PartialPiece nextPiece = lastRequest.getPartialPiece();
                        int nextBegin = lastRequest.off + PARTSIZE;
                        while (true) {
                            // don't rerequest chunks we already have
                            if (!nextPiece.hasChunk(nextBegin / PARTSIZE)) {
                                int maxLength = pieceLength - nextBegin;
                                int nextLength = maxLength > PARTSIZE ? PARTSIZE : maxLength;
                                Request req = new Request(nextPiece, nextBegin, nextLength);
                                outstandingRequests.add(req);
                                if (!choked) out.sendRequest(req);
                                lastRequest = req;
                                break;
                            } else {
                                nextBegin += PARTSIZE;
                                if (nextBegin >= pieceLength) {
                                    more_pieces = requestNextPiece();
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }

        // failsafe
        // However this is bad as it thrashes the peer when we change our mind
        // Ticket 691 cause here?
        if (interesting && lastRequest == null && outstandingRequests.isEmpty()) {
            setInteresting(false);
        }

        if (_log.shouldDebug()) {
            _log.debug("Requesting from [" + peer + "]\n* Pieces: " + outstandingRequests);
        }
    }

    /**
     * Starts requesting first chunk of next piece. Returns true if something has been added to the
     * requests, false otherwise. Caller should synchronize.
     */
    private boolean requestNextPiece() {
        // Check that we already know what the other side has.
        if (bitfield != null) {
            // Check for adopting an orphaned partial piece
            PartialPiece pp = listener.getPartialPiece(peer, bitfield);
            if (pp != null) {
                // Double-check that r not already in outstandingRequests
                if (!getRequestedPieces().contains(Integer.valueOf(pp.getPiece()))) {
                    Request r = pp.getRequest();
                    outstandingRequests.add(r);
                    if (!choked) {
                        out.sendRequest(r);
                    }
                    lastRequest = r;
                    return true;
                } else {
                    if (_log.shouldDebug()) {
                        _log.debug("Received duplicate piece from coord: " + pp);
                    }
                    pp.release();
                }
            }
        }

        // failsafe
        // However this is bad as it thrashes the peer when we change our mind
        // Ticket 691 cause here?
        if (outstandingRequests.isEmpty()) {
            lastRequest = null;
        }

        // If we are not in the end game, we may run out of things to request
        // because we are asking other peers. Set not-interesting now rather than
        // wait for those other requests to be satisfied via havePiece()
        if (interesting && lastRequest == null) {
            interesting = false;
            out.sendInterest(false);
            if (_log.shouldDebug()) {
                _log.debug("[" + peer + "] nothing more to request, now uninteresting");
            }
        }
        return false;
    }

    synchronized void setInteresting(boolean interest) {
        if (interest != interesting) {
            if (_log.shouldDebug()) {
                _log.debug("[" + peer + "] setInteresting(" + interest + ")");
            }
            interesting = interest;
            out.sendInterest(interest);

            // we shouldnt have any pending requests, but if we do, resend them
            if (interesting && !choked) {
                request(true);
            }
        }
    }

    private long lastChokeSendTime = 0;

    synchronized void setChoking(boolean choke) {
        long now = System.currentTimeMillis();

        // Only allow sending a choke message if 200ms has passed since last send
        if (choking != choke && (now - lastChokeSendTime >= 200)) {
            if (_log.shouldDebug()) {
                _log.debug("[" + peer + "] setChoking(" + choke + ")");
            }
            choking = choke;
            out.sendChoke(choke);
            lastChokeSendTime = now;
        }
    }

    /*
        synchronized void setChoking(boolean choke) {
            if (choking != choke) {
              if (_log.shouldDebug()) {_log.debug("[" + peer + "] setChoking(" + choke + ")");}
              choking = choke;
              out.sendChoke(choke);
            }
        }
    */

    void keepAlive() {
        out.sendAlive();
    }

    synchronized void retransmitRequests() {
        if (interesting && !choked) {
            out.retransmitRequests(outstandingRequests);
        }
    }

    /**
     * debug
     *
     * @return string or null
     * @since 0.8.1
     */
    synchronized String getRequests() {
        if (outstandingRequests.isEmpty()) {
            return null;
        } else {
            return outstandingRequests.toString();
        }
    }
}
