package org.klomp.snark;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionMuxedListener;
import net.i2p.client.SendMessageOptions;
import net.i2p.client.datagram.Datagram2;
import net.i2p.client.datagram.Datagram3;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import org.klomp.snark.dht.DatagramSender;

/**
 * One of these for all trackers and info hashes. Ref: BEP 15, proposal 160.
 *
 * <p>The main difference from BEP 15 is that the announce response contains a 32-byte hash instead
 * of a 4-byte IP and a 2-byte port.
 *
 * <p>We send both repliable and raw datagrams, but we only receive raw datagrams, as follows:
 *
 * <pre>
 *    client      tracker          type
 *    ----------+----------------+--------------------
 *    conn req  | --&gt;            | repliable Datagram2
 *              | &lt;-- conn resp  | raw
 *    announce  | --&gt;            | repliable Datagram3
 *              | &lt;-- ann resp   | raw
 *              | &lt;-- error      | raw
 *    ----------+-----------------+-------------------
 * </pre>
 *
 * @since 0.9.53, unused until protocol finalized in 0.9.67
 */
class UDPTrackerClient implements I2PSessionMuxedListener {
    /** The app context. */
    private final I2PAppContext _context;
    /** Logger for UDP tracker traffic. */
    private final Log _log;

    /** Hook to inject and receive datagrams. */
    private final I2PSession _session;

    /** The webapp's configuration helper. */
    private final I2PSnarkUtil _util;
    /** Hash of this client's destination. */
    private final Hash _myHash;

    /** Unsigned datagrams. */
    private final int _rPort;

    /** Dest and port to tracker data. */
    private final ConcurrentHashMap<HostPort, Tracker> _trackers;

    /** Our TID to tracker. */
    private final Map<Integer, ReplyWaiter> _sentQueries;

    /** True while the client thread is running. */
    private boolean _isRunning;

    /** Initial connection id sent in the connect request. */
    private static final long INIT_CONN_ID = 0x41727101980L;

    /**
     * No event (periodic announce).
     */
    public static final int EVENT_NONE = 0;
    /**
     * Torrent download completed.
     */
    public static final int EVENT_COMPLETED = 1;
    /**
     * Torrent download started.
     */
    public static final int EVENT_STARTED = 2;
    /**
     * Torrent stopped.
     */
    public static final int EVENT_STOPPED = 3;
    /**
     * Partial seed (BEP 21), HTTP only; not defined in the UDP protocol.
     *
     * @since 0.9.71+
     */
    public static final int EVENT_PAUSED = 5;

    /** Action value for a connection request. */
    private static final int ACTION_CONNECT = 0;
    /** Action value for an announce request. */
    private static final int ACTION_ANNOUNCE = 1;
    /** Action value for a scrape request. */
    private static final int ACTION_SCRAPE = 2;
    /** Action value marking an error reply. */
    private static final int ACTION_ERROR = 3;


    /** How long a connection ID stays valid, in milliseconds (BEP 15 connection lifetime). */
    private static final long CONN_EXPIRATION = 3 * (long) 60 * 1000;

    /** Query timeout before any backoff, in milliseconds. */
    private static final long DEFAULT_TIMEOUT = (long) 90 * 1000;

    /** Announce interval used until a tracker reports one, in seconds. */
    private static final int DEFAULT_INTERVAL = 60 * 60;

    /** Shortest announce interval accepted from a tracker, in seconds. */
    private static final int MIN_INTERVAL = 15 * 60;

    /** Longest announce interval accepted from a tracker, in seconds. */
    private static final int MAX_INTERVAL = 8 * 60 * 60;

    /**
     * Create a client bound to one torrent's session, listening on the port just below
     * {@link TrackerClient#PORT} so it never collides with the HTTP tracker client.
     *
     * @param ctx the application context supplying the log and timer services
     * @param session the torrent's session, used to send datagrams and to derive our own hash
     * @param util the utility instance supplying the enabled-tracker configuration
     */
    public UDPTrackerClient(I2PAppContext ctx, I2PSession session, I2PSnarkUtil util) {
        _context = ctx;
        _session = session;
        _util = util;
        _log = ctx.logManager().getLog(UDPTrackerClient.class);
        _rPort = TrackerClient.PORT - 1;
        _myHash = session.getMyDestination().calculateHash();
        _trackers = new ConcurrentHashMap<>(8);
        _sentQueries = new ConcurrentHashMap<>(32);
    }

    /** Can't be restarted after stopping? */
    public synchronized void start() {
        if (_isRunning) {
            return;
        }
        _session.addMuxedSessionListener(this, I2PSession.PROTO_DATAGRAM_RAW, _rPort);
        _isRunning = true;
    }

    /** Stop everything. */
    public synchronized void stop() {
        if (!_isRunning) {
            return;
        }
        _isRunning = false;
        _session.removeListener(I2PSession.PROTO_DATAGRAM_RAW, _rPort);
        _trackers.clear();
        for (ReplyWaiter w : _sentQueries.values()) {
            w.cancel();
        }
        _sentQueries.clear();
    }

    /**
     * Announce and get peers for a torrent. Blocking! Caller should run in a thread.
     *
     * @param ih the Info Hash (torrent)
     * @param peerID the peer ID this client announces itself under
     * @param max maximum number of peers to return
     * @param maxWait the maximum time to wait (ms) must be &gt; 0
     * @param toHost the tracker hostname
     * @param toPort the tracker port
     * @param downloaded bytes downloaded so far for this torrent
     * @param left bytes still to download for this torrent
     * @param uploaded bytes uploaded so far for this torrent
     * @param event one of the EVENT_* codes describing why this announce is being made
     * @param fast if true, don't wait for dest, no retx, ...
     * @return null on fail or if fast is true
     */
    public TrackerResponse announce(
            byte[] ih,
            byte[] peerID,
            int max,
            long maxWait,
            String toHost,
            int toPort,
            long downloaded,
            long left,
            long uploaded,
            int event,
            boolean fast) {
        long now = _context.clock().now();
        long end = now + maxWait;
        if (toPort <= 0) {
            throw new IllegalArgumentException();
        }
        Tracker tr = getTracker(toHost, toPort);
        if (tr.getDest(fast) == null) {
            if (_log.shouldInfo()) {
                _log.info("Cannot resolve " + tr);
            }
            return null;
        }
        long toWait = end - now;
        if (!fast) {
            toWait = toWait * 3 / 4;
        }
        if (toWait < 1000) {
            if (_log.shouldInfo()) {
                _log.info("Out of time after resolving: " + tr);
            }
            return null;
        }
        Long cid = getConnection(tr, now + toWait);
        if (cid == null) {
            if (_log.shouldInfo()) {
                _log.info("No connection for: " + tr);
            }
            return null;
        }
        if (fast) {
            toWait = 0;
        } else {
            now = _context.clock().now();
            toWait = end - now;
            if (toWait < 1000) {
                if (_log.shouldInfo()) {
                    _log.info("Out of time after getting conn: " + tr);
                }
                return null;
            }
        }
        ReplyWaiter w =
                sendAnnounce(
                        tr,
                        cid.longValue(),
                        ih,
                        peerID,
                        downloaded,
                        left,
                        uploaded,
                        event,
                        max,
                        toWait);
        if (fast) {
            return null;
        }
        if (w == null) {
            if (_log.shouldInfo()) {
                _log.info("Initial announce to " + tr + " failed");
            }
            return null;
        }
        boolean success = waitAndRetransmit(w, end);
        _sentQueries.remove(w.getID());
        if (success) {
            return w.getReplyObject();
        }
        if (_log.shouldInfo()) {
            _log.info("Announce to " + tr + " failed after retrying");
        }
        return null;
    }


    /**
     * Scrape for swarm composition for a torrent. Blocking! Caller should run in a thread.
     *
     * <p>BEP 15 scrape request, single info hash.
     *
     * @param ih the Info Hash (torrent)
     * @param maxWait the maximum time to wait (ms) must be &gt; 0
     * @param toHost the tracker hostname
     * @param toPort the tracker port
     * @return the response, or null on fail or timeout
     * @since 0.9.71+
     */
    public TrackerResponse scrape(byte[] ih, long maxWait, String toHost, int toPort) {
        long now = _context.clock().now();
        long end = now + maxWait;
        if (toPort <= 0) {
            throw new IllegalArgumentException();
        }
        Tracker tr = getTracker(toHost, toPort);
        if (tr.getDest(false) == null) {
            if (_log.shouldInfo()) {
                _log.info("Cannot resolve " + tr);
            }
            return null;
        }
        long toWait = end - now;
        toWait = toWait * 3 / 4;
        if (toWait < 1000) {
            if (_log.shouldInfo()) {
                _log.info("Out of time after resolving: " + tr);
            }
            return null;
        }
        Long cid = getConnection(tr, now + toWait);
        if (cid == null) {
            if (_log.shouldInfo()) {
                _log.info("No connection for: " + tr);
            }
            return null;
        }
        now = _context.clock().now();
        toWait = end - now;
        if (toWait < 1000) {
            if (_log.shouldInfo()) {
                _log.info("Out of time after getting conn: " + tr);
            }
            return null;
        }
        ReplyWaiter w = sendScrape(tr, cid.longValue(), ih, toWait);
        if (w == null) {
            if (_log.shouldInfo()) {
                _log.info("Initial scrape to " + tr + " failed");
            }
            return null;
        }
        boolean success = waitAndRetransmit(w, end);
        _sentQueries.remove(w.getID());
        if (success) {
            return w.getReplyObject();
        }
        if (_log.shouldInfo()) {
            _log.info("Scrape to " + tr + " failed after retrying");
        }
        return null;
    }

    /**
     * Ask a tracker whether it answers BEP 15 at all, by running the connect
     * handshake and nothing else. The connect carries a protocol id, an action
     * and a transaction id, and no info hash, so the question names no torrent
     * and asks for nothing but a connection id. Blocking, and the destination
     * lookup may take seconds, so call from a thread.
     *
     * @param host the tracker hostname
     * @param port the tracker udp port
     * @param maxWait the maximum time to wait (ms), greater than 2000
     * @return true if the tracker answered the connect
     * @throws IllegalArgumentException if the port is not usable, or the time
     *                                  is too short to resolve and connect
     *                                  within
     * @since 0.9.72+
     */
    boolean probe(String host, int port, long maxWait) {
        if (port <= 0 || maxWait <= 2000) {
            throw new IllegalArgumentException();
        }
        long now = _context.clock().now();
        long end = now + maxWait;
        Tracker tr = getTracker(host, port);
        if (tr.getDest(false) == null) {
            if (_log.shouldInfo()) {
                _log.info("Cannot resolve " + tr + " for a probe");
            }
            return false;
        }
        long toWait = (end - _context.clock().now()) * 3 / 4;
        if (toWait < 1000) {
            if (_log.shouldInfo()) {
                _log.info("Out of time after resolving: " + tr);
            }
            return false;
        }
        // A cached connection id counts as an answer, we spoke BEP 15 there recently
        return getConnection(tr, _context.clock().now() + toWait) != null;
    }

    /**
     * Establish a connection to the tracker.
     *
     * @param tr the tracker to connect to
     * @param untilTime the deadline time in milliseconds
     * @return the connection ID, or null on fail
     */
    private Long getConnection(Tracker tr, long untilTime) {
        boolean shouldConnect = false;
        synchronized (tr) {
            boolean wasInProgress = false;
            while (true) {
                Long conn = tr.getConnection();
                if (conn != null) return conn;
                // don't resend right after somebody else failed
                if (wasInProgress) return null;
                long now = _context.clock().now();
                long toWait = untilTime - now;
                if (toWait <= 0) return null;
                if (tr.isConnInProgress()) {
                    wasInProgress = true;
                    try {
                        tr.wait(toWait);
                    } catch (InterruptedException ie) { /* ignored */ }
                } else {
                    shouldConnect = true;
                    tr.setConnInProgress(true);
                    break;
                }
            }
        }
        if (shouldConnect) {
            long now = _context.clock().now();
            long toWait = untilTime - now;
            if (toWait <= 1000) {
                tr.setConnInProgress(false);
                return null;
            }
            ReplyWaiter w = sendConnReq(tr, toWait);
            if (w == null) {
                tr.setConnInProgress(false);
                return null;
            }
            boolean success = waitAndRetransmit(w, untilTime);
            // announce and scrape remove their waiters; the connect path
            // must too, or timed-out entries pile up in _sentQueries forever
            _sentQueries.remove(w.getID());
            if (success) return tr.getConnection();
        }
        return null;
    }

    /**
     * Create or find a tracker entry.
     *
     * @param host the tracker hostname
     * @param port the tracker port
     * @return non-null tracker entry
     */
    private Tracker getTracker(String host, int port) {
        Tracker ndp = new Tracker(host, port);
        Tracker odp = _trackers.putIfAbsent(ndp, ndp);
        if (odp != null) {
            ndp = odp;
        }
        return ndp;
    }


    /**
     * Send one time with a new tid.
     *
     * @param tr the tracker to send the connect request to
     * @param toWait how long to wait for the reply, in ms, greater than zero
     * @return the waiter to poll for the reply, or null if the request could
     *         not be sent
     * @throws IllegalArgumentException if toWait is zero or negative
     */
    private ReplyWaiter sendConnReq(Tracker tr, long toWait) {
        if (toWait <= 0) throw new IllegalArgumentException();
        int tid = _context.random().nextInt();
        byte[] payload = sendConnReq(tr, tid);
        if (payload != null) {
            ReplyWaiter rv = new ReplyWaiter(tid, tr, ACTION_CONNECT, payload, toWait);
            _sentQueries.put(Integer.valueOf(tid), rv);
            if (_log.shouldInfo()) _log.info("Sent: " + rv + " timeout: " + toWait);
            return rv;
        }
        return null;
    }

    /**
     * Send one time with given tid.
     *
     * @param tr the tracker to send the connect request to
     * @param tid the transaction ID to send, and to match the reply against
     * @return the payload or null on failure
     */
    private byte[] sendConnReq(Tracker tr, int tid) {
        // same as BEP 15
        byte[] payload = new byte[16];
        DataHelper.toLong8(payload, 0, INIT_CONN_ID);
        // next 4 bytes are already zero
        DataHelper.toLong(payload, 12, 4, tid);
        boolean rv = sendMessage(tr.getDest(true), tr.getPort(), payload, true);
        return rv ? payload : null;
    }

    /**
     * Send one time with a new tid.
     *
     * @param tr the tracker to announce to
     * @param connID the connection ID from the connect response
     * @param ih the 20-byte SHA-1 info hash of the torrent
     * @param id the 20-byte peer ID to advertise
     * @param downloaded bytes downloaded so far for this torrent
     * @param left bytes still to download for this torrent
     * @param uploaded bytes uploaded so far for this torrent
     * @param event one of the EVENT_* codes describing why this announce is made
     * @param numWant maximum number of peers to return
     * @param toWait how long to wait for the reply, in ms, 0 or less to skip
     *               registering a waiter
     * @return null on failure or if toWait &lt;= 0
     */
    private ReplyWaiter sendAnnounce(
            Tracker tr,
            long connID,
            byte[] ih,
            byte[] id,
            long downloaded,
            long left,
            long uploaded,
            int event,
            int numWant,
            long toWait) {
        int tid = _context.random().nextInt();
        byte[] payload =
                sendAnnounce(tr, tid, connID, ih, id, downloaded, left, uploaded, event, numWant);
        if (payload != null) {
            if (toWait > 0) {
                ReplyWaiter rv = new ReplyWaiter(tid, tr, ACTION_ANNOUNCE, payload, toWait);
                _sentQueries.put(Integer.valueOf(tid), rv);
                if (_log.shouldInfo()) {
                    _log.info("Sent: " + rv + " timeout: " + toWait);
                }
                return rv;
            }
            if (_log.shouldInfo()) {
                _log.info("Sent announce " + event + " to " + tr + " no wait");
            }
        }
        return null;
    }

    /**
     * Send one time with given tid.
     *
     * @param tr the tracker to announce to
     * @param tid the transaction ID to send, and to match the reply against
     * @param connID the connection ID from the connect response
     * @param ih the 20-byte SHA-1 info hash of the torrent
     * @param id the 20-byte peer ID to advertise
     * @param downloaded bytes downloaded so far for this torrent
     * @param left bytes still to download for this torrent
     * @param uploaded bytes uploaded so far for this torrent
     * @param event one of the EVENT_* codes describing why this announce is made
     * @param numWant maximum number of peers to return
     * @return the payload or null on failure
     */
    private byte[] sendAnnounce(
            Tracker tr,
            int tid,
            long connID,
            byte[] ih,
            byte[] id,
            long downloaded,
            long left,
            long uploaded,
            int event,
            int numWant) {
        byte[] payload = new byte[98];
        DataHelper.toLong8(payload, 0, connID);
        DataHelper.toLong(payload, 8, 4, ACTION_ANNOUNCE);
        DataHelper.toLong(payload, 12, 4, tid);
        System.arraycopy(ih, 0, payload, 16, 20);
        System.arraycopy(id, 0, payload, 36, 20);
        DataHelper.toLong(payload, 56, 8, downloaded);
        DataHelper.toLong(payload, 64, 8, left);
        DataHelper.toLong(payload, 72, 8, uploaded);
        DataHelper.toLong(payload, 80, 4, event);
        DataHelper.toLong(payload, 92, 4, numWant);
        DataHelper.toLong(payload, 96, 2, _rPort);
        boolean rv = sendMessage(tr.getDest(true), tr.getPort(), payload, true);
        return rv ? payload : null;
    }

    /**
     * Send one time with a new tid.
     *
     * @param tr the tracker to scrape from
     * @param connID the connection ID from the connect response
     * @param ih the 20-byte SHA-1 info hash to scrape
     * @param toWait if &lt;= 0 does not register
     * @return null on failure or if toWait &lt;= 0
     */
    private ReplyWaiter sendScrape(Tracker tr, long connID, byte[] ih, long toWait) {
        int tid = _context.random().nextInt();
        byte[] payload = sendScrape(tr, tid, connID, ih);
        if (payload != null) {
            if (toWait > 0) {
                ReplyWaiter rv = new ReplyWaiter(tid, tr, ACTION_SCRAPE, payload, toWait);
                _sentQueries.put(Integer.valueOf(tid), rv);
                if (_log.shouldInfo()) {
                    _log.info("Sent: " + rv + " timeout: " + toWait);
                }
                return rv;
            }
            if (_log.shouldInfo()) {
                _log.info("Sent scrape to " + tr + " no wait");
            }
        }
        return null;
    }

    /**
     * Send one time with given tid.
     *
     * @param tr the tracker to scrape from
     * @param tid the transaction ID to send, and to match the reply against
     * @param connID the connection ID from the connect response
     * @param ih the 20-byte SHA-1 info hash to scrape
     * @return the payload or null on failure
     */
    private byte[] sendScrape(Tracker tr, int tid, long connID, byte[] ih) {
        // BEP 15 scrape request, single info hash
        byte[] payload = new byte[36];
        DataHelper.toLong8(payload, 0, connID);
        DataHelper.toLong(payload, 8, 4, ACTION_SCRAPE);
        DataHelper.toLong(payload, 12, 4, tid);
        System.arraycopy(ih, 0, payload, 16, 20);
        boolean rv = sendMessage(tr.getDest(true), tr.getPort(), payload, true);
        return rv ? payload : null;
    }

    /**
     * Wait after initial send, resend if necessary.
     *
     * @param w the outstanding query to wait for a reply on, locked throughout
     * @param untilTime deadline in ms since epoch to keep retransmitting until
     * @return true if a valid reply was received, false on timeout or resend
     *         failure
     */
    private boolean waitAndRetransmit(ReplyWaiter w, long untilTime) {
        synchronized (w) {
            while (true) {
                try {
                    // add 100 so the ReplyWaiter will fire first on overall timeout, it will
                    // notify()
                    long toWait =
                            Math.min(DEFAULT_TIMEOUT, untilTime + 100 - _context.clock().now());
                    if (toWait <= 0) {
                        return false;
                    }
                    w.wait(toWait);
                } catch (InterruptedException ie) { /* ignored */ }
                switch (w.getState()) {
                    case SUCCESS:
                        return true;

                    case FAIL:
                        return false;

                    case TIMEOUT:
                        return false;

                    case INIT:
                        if (_log.shouldInfo()) {
                            _log.info("Timeout: " + w);
                        }
                        long toWait = untilTime - _context.clock().now();
                        if (toWait <= 1000) {
                            return false;
                        }
                        long reWait = Math.min(toWait, w.getSentTo().getTimeout());
                        boolean ok = resend(w, reWait);
                        if (!ok) {
                            return false;
                        }
                        // re-arm the timer so the retransmit times out in its own right
                        w.schedule(reWait);
                        continue;
                }
            }
        }
    }

    /**
     * Resend the stored payload.
     *
     * @param w the outstanding query whose stored payload is sent again
     * @param toWait the retransmit timeout in ms, used only for logging
     * @return true if the datagram was handed to the session for sending
     */
    private boolean resend(ReplyWaiter w, long toWait) {
        // per the design (see class javadoc), all requests are repliable, responses are raw
        boolean repliable = true;
        Tracker tr = w.getSentTo();
        int port = tr.getPort();
        if (_log.shouldInfo()) {
            _log.info("Resending: " + w + " timeout: " + toWait);
        }
        boolean rv = sendMessage(tr.getDest(true), port, w.getPayload(), repliable);
        return rv;
    }

    /**
     * Lowest-level send message call.
     *
     * @param dest may be null, returns false
     * @param toPort the tracker's UDP port to send the datagram to
     * @param payload the BEP 15 request bytes, wrapped before sending
     * @param repliable true to wrap in a repliable Datagram2, false for a raw
     *                  Datagram3 that does not carry our lease set
     * @return true if the session accepted the datagram; false when not
     *         running, when the payload cannot be wrapped in a Datagram2 or
     *         Datagram3, or when the send is rejected
     */
    private boolean sendMessage(Destination dest, int toPort, byte[] payload, boolean repliable) {
        if (!_isRunning) {
            if (_log.shouldInfo()) {
                _log.info("Send failed, not running");
            }
            return false;
        }
        if (dest == null) {
            if (_log.shouldInfo()) {
                _log.info("Send failed, no dest");
            }
            return false;
        }
        Hash to = dest.calculateHash();
        if (to.equals(_myHash)) {
            throw new IllegalArgumentException("don't send to ourselves");
        }

        if (repliable) {
            try {
                payload = Datagram2.make(_context, _session, payload, to);
            } catch (DataFormatException dfe) {
                if (_log.shouldWarn()) {
                    _log.warn("Datagram2 failure -> " + dfe.getMessage());
                }
                return false;
            }
        } else {
            try {
                payload = Datagram3.make(_context, _session, payload);
            } catch (DataFormatException dfe) {
                if (_log.shouldWarn()) {
                    _log.warn("Datagram3 failure -> " + dfe.getMessage());
                }
                return false;
            }
        }

        SendMessageOptions opts = new SendMessageOptions();
        opts.setDate(_context.clock().now() + 60 * 1000);
        if (!repliable) {
            opts.setSendLeaseSet(false);
        }
        try {
            boolean success =
                    DatagramSender.send(_session, opts, dest, payload, _rPort, toPort, repliable);
            if (!success && _log.shouldWarn()) {
                _log.warn("sendMessage failure");
            }
            return success;
        } catch (I2PSessionException ise) {
            if (_log.shouldWarn()) {
                _log.warn("sendMessage failure -> " + ise.getMessage());
            }
            return false;
        }
    }


    /**
     * Receive a message.
     *
     * @param from dest or null if it didn't come in on signed port
     * @param fromPort the local port the datagram arrived on, unused
     * @param payload the raw tracker response, ignored if shorter than 8 bytes
     */
    private void receiveMessage(Destination from, int fromPort, byte[] payload) {
        BandwidthGraph.datagramReceived(payload.length);
        if (payload.length < 8) {
            if (_log.shouldInfo()) {
                _log.info("Got short message: " + payload.length + " bytes");
            }
            return;
        }

        int action = (int) DataHelper.fromLong(payload, 0, 4);
        int tid = (int) DataHelper.fromLong(payload, 4, 4);
        ReplyWaiter waiter = _sentQueries.get(Integer.valueOf(tid));
        if (waiter == null) {
            if (_log.shouldInfo()) {
                _log.info("Received message with no one waiting: " + tid);
            }
            return;
        }

        int expect = waiter.getExpectedAction();
        if (expect != action && action != ACTION_ERROR) {
            if (_log.shouldInfo()) {
                _log.info("Got action " + action + " but wanted " + expect + " for: " + waiter);
            }
            // spoofed or stale datagram; don't consume the waiter
            return;
        }
        // consume the waiter only after validation, so a bad guess can't kill a pending query
        if (_sentQueries.remove(Integer.valueOf(tid)) != waiter) {
            return; // lost the race to a concurrent reply
        }

        switch (action) {
            case ACTION_CONNECT:
                receiveConnection(waiter, payload);
                break;

            case ACTION_ANNOUNCE:
                receiveAnnounce(waiter, payload);
                break;

            case ACTION_SCRAPE:
                receiveScrape(waiter, payload);
                break;

            case ACTION_ERROR:
                receiveError(waiter, payload, expect);
                break;

            default:
                if (_log.shouldInfo()) {
                    _log.info(
                            "Received message with unknown action: " + action + " for: " + waiter);
                }
                waiter.gotReply(false);
                Tracker tr = waiter.getSentTo();
                tr.gotError();
                break;
        }
    }

    /**
     * Receive a connection reply.
     *
     * @param waiter the outstanding request this reply answers
     * @param payload the connect response: 8-byte action and tid header, an
     *                8-byte connection ID at offset 8, and an optional 2-byte
     *                lifetime in seconds at offset 16
     */
    private void receiveConnection(ReplyWaiter waiter, byte[] payload) {
        Tracker tr = waiter.getSentTo();
        if (payload.length >= 16) {
            long cid = DataHelper.fromLong8(payload, 8);
            long lifetime;
            if (payload.length >= 18) {
                // fromLong is already unsigned (masks 0xFF) and returns a
                // long, so * 1000 cannot overflow or go negative - a 0
                // lifetime is the tracker's own "expire now", per BEP 15
                lifetime = DataHelper.fromLong(payload, 16, 2) * 1000;
            } // extension to BEP 15
            else {
                lifetime = CONN_EXPIRATION;
            }
            if (_log.shouldInfo()) {
                _log.info(
                        "Received a connect response from the tracker"
                                + " * ID: "
                                + cid
                                + " * Lifetime: "
                                + DataHelper.formatDuration(lifetime)
                                + " * Tracker: "
                                + tr);
            }
            tr.setConnection(cid, lifetime);
            waiter.gotReply(true);
        } else {
            waiter.gotReply(false);
            tr.gotError();
        }
    }

    /**
     * Parses an announce reply and hands it to the waiter.
     *
     * @param waiter the request the reply answers
     * @param payload the reply body
     */
    private void receiveAnnounce(ReplyWaiter waiter, byte[] payload) {
        Tracker tr = waiter.getSentTo();
        if (payload.length >= 20) {
            int interval =
                    Math.min(
                            MAX_INTERVAL,
                            Math.max(MIN_INTERVAL, (int) DataHelper.fromLong(payload, 8, 4)));
            int leeches = (int) DataHelper.fromLong(payload, 12, 4);
            int seeds = (int) DataHelper.fromLong(payload, 16, 4);
            int peers = (payload.length - 20) / Hash.HASH_LENGTH;
            if (_log.shouldInfo()) {
                _log.info("Received " + peers + " peers from " + tr);
            }
            Set<Hash> hashes;
            if (peers > 0) {
                hashes = new HashSet<>(peers);
                for (int off = 20;
                        off <= payload.length - Hash.HASH_LENGTH;
                        off += Hash.HASH_LENGTH) {
                    hashes.add(Hash.create(payload, off));
                }
            } else {
                hashes = Collections.emptySet();
            }
            TrackerResponse resp = new TrackerResponse(interval, seeds, leeches, hashes);
            waiter.gotResponse(resp);
        } else {
            waiter.gotReply(false);
            tr.gotError();
        }
    }

    /**
     * Parse a BEP 15 scrape response, single info hash: seeders, completed, leechers.
     *
     * @param waiter the waiter for the scrape request
     * @param payload the raw response
     */
    private void receiveScrape(ReplyWaiter waiter, byte[] payload) {
        Tracker tr = waiter.getSentTo();
        if (payload.length >= 20) {
            int seeds = (int) DataHelper.fromLong(payload, 8, 4);
            int leeches = (int) DataHelper.fromLong(payload, 16, 4);
            if (_log.shouldInfo()) {
                _log.info(
                        "Received scrape from "
                                + tr
                                + " seeds: "
                                + seeds
                                + " leeches: "
                                + leeches);
            }
            TrackerResponse resp = new TrackerResponse(0, seeds, leeches, Collections.emptySet());
            waiter.gotResponse(resp);
        } else {
            waiter.gotReply(false);
            tr.gotError();
        }
    }

    /**
     * Parses an error reply and fails the waiter.
     *
     * @param waiter the request the reply answers
     * @param payload the reply body
     * @param expected the action the reply should have carried
     */
    private void receiveError(ReplyWaiter waiter, byte[] payload, int expected) {
        String msg;
        if (payload.length > 8) {
            msg = DataHelper.getUTF8(payload, 8, payload.length - 8);
        } else {
            msg = "";
        }
        TrackerResponse resp = new TrackerResponse(msg);
        waiter.gotResponse(resp);
        Tracker tr = waiter.getSentTo();
        tr.gotError();
        // TODO if we were waiting for an announce reply, fire off a new connection request
        // No action needed for announce response
    }

    /**
     * Instruct the client that the given session has received a message.
     *
     * <p>Will be called only if you register via addMuxedSessionListener(). Will be called only for
     * the proto(s) and toPort(s) you register for.
     *
     * @param session session to notify
     * @param msgId message number available
     * @param size size of the message - why it's a long and not an int is a mystery
     * @param proto 1-254 or 0 for unspecified
     * @param fromPort 1-65535 or 0 for unspecified
     * @param toPort 1-65535 or 0 for unspecified
     */
    public void messageAvailable(
            I2PSession session, int msgId, long size, int proto, int fromPort, int toPort) {
        // TODO throttle
        try {
            byte[] payload = session.receiveMessage(msgId);
            if (payload == null) {
                return;
            }
            if (toPort == _rPort) {
                receiveMessage(null, fromPort, payload);
            } // raw
            else if (_log.shouldWarn()) {
                _log.warn("Message received on invalid port");
            }
        } catch (I2PSessionException e) {
            if (_log.shouldWarn()) {
                _log.warn("Bad message received");
            }
        }
    }

    /** For non-muxed. */
    @Override
    public void messageAvailable(I2PSession session, int msgId, long size) { /* no-op */ }

    /**
     * Handle a reportAbuse message from the session.
     *
     * @param session the session that reported the abuse
     * @param severity the abuse severity
     */
    @Override
    public void reportAbuse(I2PSession session, int severity) { /* no-op */ }

    /**
     * Handle a session disconnect.
     *
     * @param session the session that dropped
     */
    @Override
    public void disconnected(I2PSession session) {
        if (_log.shouldWarn()) _log.warn("UDPTC disconnected");
        // mirror KRPC.stop() so waiters fail fast instead of hanging to their timers
        _isRunning = false;
        _session.removeListener(I2PSession.PROTO_DATAGRAM_RAW, _rPort);
        _trackers.clear();
        for (ReplyWaiter w : _sentQueries.values()) {
            w.gotReply(false);
        }
        _sentQueries.clear();
    }

    /**
     * Handle a session error.
     *
     * @param session the session that reported the error
     * @param message the error message
     * @param error the cause of the error, non-null
     */
    @Override
    public void errorOccurred(I2PSession session, String message, Throwable error) {
        if (_log.shouldWarn()) _log.warn("UDPTC got error msg: ", error);
    }

    /**
     * Represents a response from a UDP tracker.<br>
     * This class encapsulates the tracker's response including peer lists, interval information,
     * and any error messages.
     * @since 0.9.20
     */
    public static class TrackerResponse {

        /** Seconds until the next announce, set by the tracker. */
        private final int interval;
        /** Peers with the torrent complete. */
        private final int complete;
        /** Peers still downloading. */
        private final int incomplete;
        /** Error message from the tracker, or null. */
        private final String error;
        /** Peers returned by the reply. */
        private final Set<Hash> peers;

        /**
         * Builds a successful response from the tracker's swarm statistics.
         *
         * @param interval seconds the tracker asks us to wait before the next announce
         * @param seeds number of complete peers the tracker reports in the swarm
         * @param leeches number of incomplete peers the tracker reports in the swarm
         * @param peers the peer hashes returned by the tracker
         */
        public TrackerResponse(int interval, int seeds, int leeches, Set<Hash> peers) {
            this.interval = interval;
            complete = seeds;
            incomplete = leeches;
            this.peers = peers;
            error = null;
        }

        /**
         * Builds a response holding only the tracker's error, with no peers.
         *
         * @param errorMsg the tracker supplied reason the announce did not succeed
         */
        public TrackerResponse(String errorMsg) {
            interval = DEFAULT_INTERVAL;
            complete = 0;
            incomplete = 0;
            peers = null;
            error = errorMsg;
        }

        /**
         * The peers.
         *
         * @return the peer hashes, as parsed from the tracker reply
         */
        public Set<Hash> getPeers() {
            return peers;
        }

        /**
         * Peer count from the tracker response.
         * @return the peer count, derived from peers seen plus seed/leech counts
         */
        public int getPeerCount() {
            return TrackerInfo.getPeerCount(peers, complete, incomplete);
        }

        /**
         * The seed count.
         *
         * @return number of seeds from the tracker reply
         */
        public int getSeedCount() {
            return complete;
        }

        /**
         * The leech count.
         *
         * @return number of leeches from the tracker reply
         */
        public int getLeechCount() {
            return incomplete;
        }

        /**
         * The failure reason.
         *
         * @return error message from the tracker, or null on success
         */
        public String getFailureReason() {
            return error;
        }

        /**
         * In seconds.
         *
         * @return seconds to wait before the next announce
         */
        public int getInterval() {
            return interval;
        }
    }

    /** A tracker host and port. */
    private static class HostPort {

        /** The tracker host name. */
        protected final String host;
        /** The tracker port. */
        protected final int port;

        /**
         * Create a host/port pair.
         *
         * @param host the tracker host
         * @param port the announce port
         */
        public HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }

        /**
         * The announce port.
         *
         * @return the port advertised in the announce
         */
        public int getPort() {
            return port;
        }

        /**
         * Whether hash code is present.
         *
         * @return whether h code is present
         */
        @Override
        public int hashCode() {
            return host.hashCode() ^ port;
        }

        /**
         * Whether this equals the other.
         */
        @Override
        public boolean equals(Object o) {
            if (o == null || !(o instanceof HostPort)) {
                return false;
            }
            HostPort dp = (HostPort) o;
            return port == dp.port && host.equals(dp.host);
        }

        /**
         * String form of the host/port pair.
         */
        @Override
        public String toString() {
            return "UDP Tracker " + host + ':' + port;
        }
    }

    /** State of the connection id for one tracker. */
    private enum ConnState {
        /** No valid id held, a connect is needed. */
        INVALID,
        /** A connect request is outstanding. */
        IN_PROGRESS,
        /** A valid id is held. */
        VALID
    }

    /** One UDP tracker and its connection state. */
    private class Tracker extends HostPort {

        /** Lock guarding the tracker destination. */
        private final Object destLock = new Object();
        /** Destination used for the connection to this tracker. */
        private Destination dest;
        /** Connection id from the tracker, null when none is held. */
        private Long cid;
        /** When the connection id expires. */
        private long expires;
        /** Consecutive failed requests to this tracker. */
        private int consecFails;
        /** Current state of the connection id. */
        private ConnState state = ConnState.INVALID;

        /**
         * Prepares to talk to one UDP tracker.
         *
         * @param host the tracker host
         * @param port the tracker port
         */
        public Tracker(String host, int port) {
            super(host, port);
        }

        /**
         * The destination, or null.
         *
         * @param fast if true, do not lookup
         * @return dest or null
         */
        public Destination getDest(boolean fast) {
            synchronized (destLock) {
                if (dest == null && !fast) {
                    dest = _util.getDestination(host);
                }
                return dest;
            }
        }

        /**
         * Store whether a connection to the tracker is in progress.
         *
         * @param yes true while a connection is in progress
         */
        public synchronized void setConnInProgress(boolean yes) {
            if (yes) {
                state = ConnState.IN_PROGRESS;
            } else if (state == ConnState.IN_PROGRESS) {
                state = ConnState.INVALID;
                notifyAll();
            }
        }

        /**
         * Whether a connection is in progress.
         *
         * @return whether conn in progress
         */
        public synchronized boolean isConnInProgress() {
            return state == ConnState.IN_PROGRESS;
        }

        /**
         * Whether the connection is valid.
         *
         * @return whether conn valid
         */
        public synchronized boolean isConnValid() {
            return state == ConnState.VALID && expires > _context.clock().now();
        }

        /**
         * Called when tracker connection attempt fails.
         */
        public synchronized void connFailed() {
            replyTimeout();
            expires = 0;
            state = ConnState.INVALID;
            notifyAll();
        }

        /** Does not change state. */
        public synchronized void replyTimeout() {
            consecFails++;
        }

        /**
         * Store the connection ID and expiration.
         *
         * @param cid the connection ID to store
         * @param lifetime milliseconds from now until the ID expires
         */
        public synchronized void setConnection(long cid, long lifetime) {
            this.cid = Long.valueOf(cid);
            long now = _context.clock().now();
            expires = now + lifetime;
            consecFails = 0;
            state = ConnState.VALID;
            notifyAll();
        }

        /**
         * The connection ID, or null if invalid.
         *
         * @return null if invalid
         */
        public synchronized Long getConnection() {
            if (isConnValid()) {
                return cid;
            }
            return null;
        }

        /** Mark an error and notify waiters. */
        public synchronized void gotError() {
            consecFails++;
            state = ConnState.INVALID;
            cid = null;
            this.notifyAll();
        }

        /**
         * The query timeout for this connection: the default, doubled for each consecutive
         * failure up to eight times the default.
         *
         * @return the timeout in milliseconds
         */
        public synchronized long getTimeout() {
            return DEFAULT_TIMEOUT << Math.min(consecFails, 3);
        }

        /**
         * String form of the waiter.
         */
        @Override
        public String toString() {
            return "UDP Tracker\n* Host: "
                    + host
                    + ':'
                    + port
                    + " hasDest? "
                    + (dest != null)
                    + " Valid? "
                    + isConnValid()
                    + " [ConnectionID "
                    + (cid != null ? cid : "none")
                    + "] "
                    + state;
        }
    }

    /** State of one outstanding tracker request. */
    private enum WaitState {
        /** Waiting for the reply. */
        INIT,
        /** The reply arrived. */
        SUCCESS,
        /** The reply timed out. */
        TIMEOUT,
        /** The request failed. */
        FAIL
    }

    /** Callback for replies. */
    private class ReplyWaiter extends SimpleTimer2.TimedEvent {
        /** Transaction id matching the reply. */
        private final int tid;
        /** The tracker the request was sent to. */
        private final Tracker sentTo;
        /** The action the request carried. */
        private final int action;
        /** The request payload that was sent. */
        private final byte[] data;
        /** The parsed reply, until it arrives. */
        private TrackerResponse replyObject;
        /** Current state of this request. */
        private WaitState state = WaitState.INIT;

        /**
         * Either wait on this object with a timeout, or use non-null Runnables. Any sent data to be
         * remembered may be stored by setSentObject(). Reply object may be in {@link #getReplyObject()}.
         *
         * @param tid the transaction id of the request
         * @param tracker the tracker being queried
         * @param action the tracker action number
         * @param payload the request bytes to send
         * @param toWait the time to wait for the reply, in ms
         */
        public ReplyWaiter(int tid, Tracker tracker, int action, byte[] payload, long toWait) {
            super(SimpleTimer2.getInstance(), toWait);
            this.tid = tid;
            sentTo = tracker;
            this.action = action;
            data = payload;
        }

        /**
         * The ID.
         *
         * @return the i d
         */
        public int getID() {
            return tid;
        }

        /**
         * The sent-to tracker.
         *
         * @return the sent to
         */
        public Tracker getSentTo() {
            return sentTo;
        }

        /**
         * The expected action.
         *
         * @return the BEP 15 action type the reply must carry
         */
        public int getExpectedAction() {
            return action;
        }

        /**
         * The payload.
         *
         * @return the already-built datagram, as sent or as to be resent
         */
        public byte[] getPayload() {
            return data;
        }

        /**
         * The reply object, may be null depending on what happened. Cast to the expected type.
         *
         * @return may be null depending on what happened. Cast to expected type.
         */
        public synchronized TrackerResponse getReplyObject() {
            return replyObject;
        }

        /**
         * The current state of this request.
         *
         * @return the wait state
         */
        public synchronized WaitState getState() {
            return state;
        }

        /**
         * Will notify this. Also removes from _sentQueries and calls heardFrom(). Sets state to
         * SUCCESS or FAIL.
         *
         * @param success true if the tracker replied without an error
         */
        public synchronized void gotReply(boolean success) {
            cancel();
            _sentQueries.remove(Integer.valueOf(tid));
            setState(success ? WaitState.SUCCESS : WaitState.FAIL);
        }

        /**
         * Will notify this and run onReply. Also removes from _sentQueries and calls heardFrom().
         *
         * @param state the new state of this request
         */
        private synchronized void setState(WaitState state) {
            this.state = state;
            this.notifyAll();
        }

        /**
         * Will notify this. Also removes from _sentQueries and calls heardFrom(). Sets state to
         * SUCCESS.
         *
         * @param resp the response received
         */
        public synchronized void gotResponse(TrackerResponse resp) {
            replyObject = resp;
            gotReply(resp.error == null);
        }

        /** Reset to the INIT state. */
        @Override
        public synchronized void schedule(long toWait) {
            state = WaitState.INIT;
            super.schedule(toWait);
        }

        /** Timer callback on timeout. */
        public synchronized void timeReached() {
            // don't trump success or failure
            if (state != WaitState.INIT) {
                return;
            }
            if (action == ACTION_CONNECT) {
                sentTo.connFailed();
            } else {
                sentTo.replyTimeout();
            }
            setState(WaitState.TIMEOUT);
            if (_log.shouldWarn()) {
                _log.warn("Timeout waiting for reply from " + sentTo);
            }
        }

        /**
         * String form of the reply waiter.
         */
        @Override
        public String toString() {
            return "Message type: "
                    + action
                    + " ID: "
                    + tid
                    + " to: "
                    + sentTo
                    + " state: "
                    + state;
        }
    }
}
