package net.i2p.router.transport.ntcp;

import static net.i2p.router.transport.Transport.AddressSource.*;
import static net.i2p.router.transport.TransportUtil.IPv6Config.*;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyPair;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Banlist;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.Transport;
import net.i2p.router.transport.TransportBid;
import net.i2p.router.transport.TransportImpl;
import net.i2p.router.transport.TransportManager;
import net.i2p.router.transport.TransportUtil;
import net.i2p.router.transport.crypto.X25519KeyFactory;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.router.util.DecayingBloomFilter;
import net.i2p.router.util.DecayingHashSet;
import net.i2p.router.util.EventLog;
import net.i2p.stat.RateConstants;
import net.i2p.util.Addresses;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 * Non-blocking TCP (NTCP) transport implementation for I2P.
 * 
 * This class provides the primary TCP-based transport for I2P
 * communication using Java NIO for non-blocking I/O operations.
 * It supports both NTCP 1 and NTCP 2 protocols with
 * advanced features like connection pooling, bandwidth management,
 * and sophisticated peer management.
 * 
 * <strong>Protocol Features:</strong>
 * <ul>
 *   <li>NTCP 1: Basic encrypted TCP transport</li>
 *   <li>NTCP 2: Enhanced protocol with improved efficiency</li>
 *   <li>Configurable bandwidth limits and throttling</li>
 *   <li>Connection pooling and reuse</li>
 *   <li>IPv4 and IPv6 support</li>
 *   <li>Automatic protocol negotiation</li>
 * </ul>
 * 
 * <strong>Architecture:</strong>
 * <ul>
 *   <li>Non-blocking I/O with NIO channels</li>
 *   <li>Separate reader and writer threads</li>
 *   <li>Event-driven message processing</li>
 *   <li>Connection state machines for establishment</li>
 *   <li>Peer reputation and cost-based routing</li>
 * </ul>
 * 
 * <strong>Connection Management:</strong>
 * <ul>
 *   <li>Multiple bid tiers (fast, slow, high-cost)</li>
 *   <li>Connection pooling and reuse</li>
 *   <li>Automatic failover and retry logic</li>
 *   <li>Peer blacklisting and banlist support</li>
 * </ul>
 *
 * @since 0.9.16
 */
public class NTCPTransport extends TransportImpl {
    private final Log _log;
    private final SharedBid _fastBid;
    private final SharedBid _slowBid;
    private final SharedBid _slowCostBid;
    /** save some conns for inbound */
    private final SharedBid _nearCapacityBid;
    private final SharedBid _nearCapacityCostBid;
    private final SharedBid _transientFail;
    private final Object _conLock;
    private final ConcurrentHashMap<Hash, NTCPConnection> _conByIdent;
    private final EventPumper _pumper;
    private final Reader _reader;
    private net.i2p.router.transport.ntcp.Writer _writer;
    private int _ssuPort;
    /** synch on this */
    private final Set<InetSocketAddress> _endpoints;
    private final int _networkID;

    /**
     * list of NTCPConnection of connections not yet established that we
     * want to remove on establishment or close on timeout
     */
    private final Set<NTCPConnection> _establishing;
    /** "bloom filter" */
    private final DecayingBloomFilter _replayFilter;

    /**
     *  Do we have a public IPv6 address?
     *  TODO periodically update via CSFI.NetMonitor?
     */
    private boolean _haveIPv6Address;
    private long _lastInboundIPv4;
    private long _lastInboundIPv6;

    // note: SSU version is i2np.udp.host, not hostname
    public final static String PROP_I2NP_NTCP_HOSTNAME = "i2np.ntcp.hostname";
    public final static String PROP_I2NP_NTCP_PORT = "i2np.ntcp.port";
    public final static String PROP_I2NP_NTCP_AUTO_PORT = "i2np.ntcp.autoport";
    public final static String PROP_I2NP_NTCP_AUTO_IP = "i2np.ntcp.autoip";
    private static final String PROP_ADVANCED = "routerconsole.advanced";
    private static final int DEFAULT_COST = 10;
    private static final int NTCP2_OUTBOUND_COST = 14;

    /** this is rarely if ever used, default is to bind to wildcard address */
    public static final String PROP_BIND_INTERFACE = "i2np.ntcp.bindInterface";

    private final NTCPSendFinisher _finisher;
    private final X25519KeyFactory _xdhFactory;
    private long _lastBadSkew;
    private static final long[] RATES = RateConstants.SHORT_TERM_RATES;

    private static final int MIN_CONCURRENT_READERS = SystemVersion.isSlow() ? 3 : 6;  // unless < 32MB
    private static final int MIN_CONCURRENT_WRITERS = MIN_CONCURRENT_READERS;
    private static final int MAX_CONCURRENT_READERS = MIN_CONCURRENT_READERS;
    private static final int MAX_CONCURRENT_WRITERS = MIN_CONCURRENT_READERS;

    /**
     *  RI sigtypes supported in 0.9.16
     */
    public static final String MIN_SIGTYPE_VERSION = "0.9.16";

    // NTCP2 stuff
    public static final String STYLE = "NTCP";
    public static final String STYLE2 = "NTCP2";
    static final int NTCP2_INT_VERSION = 2;
    /** "2" */
    static final String NTCP2_VERSION = Integer.toString(NTCP2_INT_VERSION);
    /** "2," */
    static final String NTCP2_VERSION_ALT = NTCP2_VERSION + ',';
    /** b64 static private key */
    public static final String PROP_NTCP2_SP = "i2np.ntcp2.sp";
    /** b64 static IV */
    public static final String PROP_NTCP2_IV = "i2np.ntcp2.iv";
    private static final int NTCP2_IV_LEN = OutboundNTCP2State.IV_SIZE;
    private static final int NTCP2_KEY_LEN = OutboundNTCP2State.KEY_SIZE;
    private static final long MIN_DOWNTIME_TO_REKEY = 30*24*60*60*1000L;
    private static final long MIN_DOWNTIME_TO_REKEY_HIDDEN = 24*60*60*1000L;
    private final byte[] _ntcp2StaticPubkey;
    private final byte[] _ntcp2StaticPrivkey;
    private final byte[] _ntcp2StaticIV;
    private final String _b64Ntcp2StaticPubkey;
    private final String _b64Ntcp2StaticIV;

    /**
     *  @param xdh null to disable NTCP2
     */
    public NTCPTransport(RouterContext ctx, X25519KeyFactory xdh) {
        super(ctx);
        _xdhFactory = xdh;
        _log = ctx.logManager().getLog(getClass());

        _context.statManager().createRateStat("ntcp.accept", "Accepted NTCP requests", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.attemptBanlistedPeer", "Connection attempts to banlisted NTCP peer", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.attemptUnreachablePeer", "Connection attempts to unreachable NTCP peer", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.connectFailedIOE", "Failed connection attempts to NTCP peer (IO error)", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.connectFailedTimeout", "Failed connection attempts to NTCP peer", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.connectFailedTimeoutIOE", "Failed connection attempts to NTCP peer (IO error)", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.connectSuccessful", "Successful connection attempts to NTCP peer", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.corruptI2NPIME", "Corrupt NTCP messages decrypted (IME)", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.corruptSkew", "Corrupt clock skews received from NTCP peer", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.failsafeCloses", "Idle connection to peer closed (failsafe)", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.failsafeInvalid", "Connection to peer closed (JVM bug workaround)", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.failsafeThrottle", "Delay event pumper", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.failsafeWrites", "Extra nio writes added to peer (failsafe)", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.inboundEstablishedDuplicate", "Duplicate established Inbound NTCP connections", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.inboundEstablished", "Established Inbound NTCP connections", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.inboundIPv4Conn", "Inbound IPv4 NTCP connections", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.inboundIPv6Conn", "Inbound IPv6 NTCP connections", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.invalidInboundSignature", "Invalid Inbound signatures received from NTCP peer", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.invalidInboundSkew", "Invalid clockskew reports received from NTCP peer", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.invalidOutboundSkew", "Invalid NTCP Outbound clock skews", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.multipleCloseOnRemove", "Number of NTCP multipleCloseOnRemove events", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.noBidTooLargeI2NP", "NTCP tunnel send size", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.outboundEstablishFailed", "Failed NTCP Outbound Tunnel Establishment events", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.outboundFailedIOEImmediate", "Failed NTCP Outbound Tunnel events (IOerror)", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.queuedRecv", "Number of queued NTCP RECV packets", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.readError", "Number of NTCP read errors", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.read", "Number of NTCP read events", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.receiveCorruptEstablishment", "Corrupt NTCP establishment events received", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.receiveMeta", "Number of NTCP receiveMeta events", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.registerConnect", "Number of NTCP registerConnect events", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.replayHXxorBIH", "Number of NTCP replayHXxorBIH events", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.sendBacklogTime", "Send queue latency when adding new message fails (ms)", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.sendQueueSize", "Messages in queue when new message added", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.sendTime", "Total message lifetime when send complete", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.throttledReadComplete", "Throttled NTCP ReadComplete events", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.throttledWriteComplete", "Throttled NTCP WriteComplete events", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.wantsQueuedWrite", "Number of wanted NTCP QueuedWrite events", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.writeError", "Number of NTCP write errors", "Transport [NTCP]", RATES);

        _endpoints = new HashSet<InetSocketAddress>(4);
        _establishing = new ConcurrentHashSet<NTCPConnection>(64);
        _conLock = new Object();
        _conByIdent = new ConcurrentHashMap<Hash, NTCPConnection>(256);
        _replayFilter = new DecayingHashSet(ctx, 10*60*1000, 8, "NTCP-Hx^HI");
        _finisher = new NTCPSendFinisher(ctx, this);
        _pumper = new EventPumper(ctx, this);
        _reader = new Reader(ctx);
        _writer = new net.i2p.router.transport.ntcp.Writer(ctx);
        _networkID = ctx.router().getNetworkID();
        _fastBid = new SharedBid(25); // best
        _slowBid = new SharedBid(70); // better than ssu unestablished, but not better than ssu established
        _slowCostBid = new SharedBid(85);
        _nearCapacityBid = new SharedBid(90); // not better than ssu - save our conns for inbound
        _nearCapacityCostBid = new SharedBid(105);
        _transientFail = new SharedBid(TransportBid.TRANSIENT_FAIL);

        setupPort();
        if (xdh == null)
            throw new IllegalArgumentException();

            boolean shouldSave = false;
            byte[] priv = null;
            byte[] iv = null;
            String b64IV = null;
            String s = null;
            // try to determine if we've been down for 30 days or more
            long minDowntime = _context.router().isHidden() ? MIN_DOWNTIME_TO_REKEY_HIDDEN : MIN_DOWNTIME_TO_REKEY;
            boolean shouldRekey = !allowLocal() && _context.getEstimatedDowntime() >= minDowntime;
            if (!shouldRekey) {
                s = ctx.getProperty(PROP_NTCP2_SP);
                if (s != null) {
                    priv = Base64.decode(s);
                }
            }
            if (priv == null || priv.length != NTCP2_KEY_LEN) {
                KeyPair keys = xdh.getKeys();
                _ntcp2StaticPrivkey = keys.getPrivate().getData();
                _ntcp2StaticPubkey = keys.getPublic().getData();
                shouldSave = true;
            } else {
                _ntcp2StaticPrivkey = priv;
                _ntcp2StaticPubkey = (new PrivateKey(EncType.ECIES_X25519, priv)).toPublic().getData();
            }
            if (!shouldSave) {
                s = ctx.getProperty(PROP_NTCP2_IV);
                if (s != null) {
                    iv = Base64.decode(s);
                    b64IV = s;
                }
            }
            if (iv == null || iv.length != NTCP2_IV_LEN) {
                iv = new byte[NTCP2_IV_LEN];
                do {
                    ctx.random().nextBytes(iv);
                } while (DataHelper.eq(iv, 0, OutboundNTCP2State.ZEROKEY, 0, NTCP2_IV_LEN));
                shouldSave = true;
            }
            if (shouldSave) {
                Map<String, String> changes = new HashMap<String, String>(2);
                String b64Priv = Base64.encode(_ntcp2StaticPrivkey);
                b64IV = Base64.encode(iv);
                changes.put(PROP_NTCP2_SP, b64Priv);
                changes.put(PROP_NTCP2_IV, b64IV);
                ctx.router().saveConfig(changes, null);
            }
            _ntcp2StaticIV = iv;
            _b64Ntcp2StaticPubkey = Base64.encode(_ntcp2StaticPubkey);
            _b64Ntcp2StaticIV = b64IV;

    }

    /**
     *  Pick a port if not previously configured.
     *  Only if UDP is disabled.
     *
     *  @return the port or -1
     *  @since 0.9.39
     */
    private int setupPort() {
        if (_context.getBooleanPropertyDefaultTrue(TransportManager.PROP_ENABLE_UDP))
            return -1;
        int port = getRequestedPort();
        if (port > 0 && !TransportUtil.isValidPort(port)) {
            TransportUtil.logInvalidPort(_log, STYLE, port);
            port = -1;
        }
        if (port <= 0) {
            // If we previously had a UDP port, use it
            port = _context.getProperty(UDPTransport.PROP_INTERNAL_PORT, -1);
            if (port <= 0)
                port = _context.getProperty(UDPTransport.PROP_EXTERNAL_PORT, -1);
            if (port > 0 && !TransportUtil.isValidPort(port)) {
                TransportUtil.logInvalidPort(_log, STYLE, port);
                port = -1;
            }
            if (port > 0)
                _context.router().saveConfig(PROP_I2NP_NTCP_PORT, Integer.toString(port));
        }
        if (port <= 0) {
            port = TransportUtil.selectRandomPort(_context, STYLE);
            _context.router().saveConfig(PROP_I2NP_NTCP_PORT, Integer.toString(port));
            _log.logAlways(Log.INFO, "NTCP selected random port " + port);
        }
        return port;
    }

    /**
     * Registers a newly established inbound connection, replacing any existing connection
     * for the same peer. Updates reachability status and connection statistics.
     *
     * @param con The newly established NTCPConnection.
     * @return The previous NTCPConnection to the same peer if one existed, or null otherwise.
     */
    NTCPConnection inboundEstablished(NTCPConnection con) {
        _context.statManager().addRateData("ntcp.inboundEstablished", 1);
        Hash peer = con.getRemotePeer().calculateHash();
        markReachable(peer, true);
        NTCPConnection old;
        synchronized (_conLock) {
            old = _conByIdent.put(peer, con);
        }
        updateInboundIPStats(con.isIPv6(), con.getCreated());
        return old;
    }

    /**
     * Updates statistics and reachability status related to inbound connections
     * based on IP version and connection creation time. Triggers address change
     * notification if this is the first inbound connection of the IP family.
     *
     * @param ipv6 True if the connection is IPv6, false if IPv4.
     * @param created The creation timestamp of the inbound connection.
     */
    private synchronized void updateInboundIPStats(boolean ipv6, long created) {
        long last;
        Status oldStatus = null;
        if (ipv6) {
            last = _lastInboundIPv6;
            if (last <= 0)
                oldStatus = getReachabilityStatus();
            _lastInboundIPv6 = created;
            if (last <= 0)
                addressChanged(oldStatus);
            _context.statManager().addRateData("ntcp.inboundIPv6Conn", 1);
        } else {
            last = _lastInboundIPv4;
            if (last <= 0)
                oldStatus = getReachabilityStatus();
            _lastInboundIPv4 = created;
            if (last <= 0)
                addressChanged(oldStatus);
            _context.statManager().addRateData("ntcp.inboundIPv4Conn", 1);
        }
    }

    /**
     * Processes the next outbound message by retrieving or creating a connection,
     * preparing it for sending, and handling related failures or bans.
     */
    protected void outboundMessageReady() {
        OutNetMessage msg = getNextMessage();
        if (msg == null) return;

        RouterInfo target = msg.getTarget();
        RouterIdentity ident = target.getIdentity();
        Hash ih = ident.calculateHash();

        NTCPConnection con = getConnectionOrCreateNew(ih, target);
        if (con == null) {
            banPeerForInvalidAddress(ih, target);
            afterSend(msg, false);
            return;
        }

        try {
            prepareConnectionForSending(con, msg);
        } catch (IOException | IllegalStateException e) {
            logConnectionSetupError(e);
            con.close();
            afterSend(msg, false);
        }
    }

    /**
     * Retrieves an existing NTCPConnection for the given peer hash or creates a new one.
     * Synchronizes minimally on the connection map to avoid race conditions.
     *
     * @param ih The hash identifier of the target router identity.
     * @param target The RouterInfo object representing the connection target.
     * @return An existing or newly created NTCPConnection, or null if creation failed.
     */
    private NTCPConnection getConnectionOrCreateNew(Hash ih, RouterInfo target) {
        NTCPConnection con;
        synchronized (_conLock) {
            con = _conByIdent.get(ih);
            if (con != null) return con;
        }

        RouterAddress addr = getTargetAddress(target);
        if (addr == null) return null;

        int newVersion = getNTCPVersion(addr);
        if (newVersion == 0) return null;

        try {
            NTCPConnection newCon = new NTCPConnection(_context, this, target.getIdentity(), addr, newVersion);
            synchronized (_conLock) {
                // Check again for race
                NTCPConnection existingCon = _conByIdent.get(ih);
                if (existingCon != null) {
                    newCon.close();
                    return existingCon;
                }
                _conByIdent.put(ih, newCon);
            }
            establishing(newCon);
            return newCon;
        } catch (DataFormatException dfe) {
            return null;
        }
    }

    /**
     * Bans a peer router for having an invalid or missing NTCP address.
     * Logs the ban event with appropriate log levels based on configuration.
     *
     * @param ih The hash identifier of the peer router.
     * @param target The RouterInfo of the banned peer.
     */
    private void banPeerForInvalidAddress(Hash ih, RouterInfo target) {
        long now = _context.clock().now();
        if (_log.shouldInfo()) {
            _log.warn("[NTCP] We bid on a peer without a valid NTCP address, banning for 8h\n" + target);
        } else if (_log.shouldWarn()) {
            String shortId = target.getIdentity().toBase64().substring(0,6);
            _log.warn("[NTCP] Router [" + shortId + "] has no valid NTCP address, banning for 8h");
        }
        _context.banlist().banlistRouter(ih, " <b>➜</b> Invalid NTCP address", null, null, now + 8*60*60*1000);
    }

    /**
     * Prepares the given NTCPConnection for sending the specified message,
     * including checking message types and connection version, sending
     * control info if needed, and ensuring the channel is open.
     *
     * @param con The NTCPConnection to prepare and send on.
     * @param msg The outbound message to send.
     * @throws IOException If channel operations fail.
     */
    private void prepareConnectionForSending(NTCPConnection con, OutNetMessage msg) throws IOException {
        int newVersion = con.getVersion();
        I2NPMessage m = msg.getMessage();
        boolean shouldSkipInfo = false;
        boolean shouldFlood = false;

        if (newVersion != 0) {
            if (m.getType() == DatabaseStoreMessage.MESSAGE_TYPE) {
                DatabaseStoreMessage dsm = (DatabaseStoreMessage) m;
                if (dsm.getKey().equals(_context.routerHash())) {
                    shouldSkipInfo = true;
                    shouldFlood = dsm.getReplyToken() != 0;
                }
            }

            if (!shouldSkipInfo || shouldFlood || newVersion == 1) {
                con.send(msg);
            } else if (_log.shouldInfo()) {
                _log.info("SKIPPING INFO message: " + con);
            }

            if (con.getChannel() == null) {
                SocketChannel channel = SocketChannel.open();
                con.setChannel(channel);
                channel.configureBlocking(false);
                _pumper.registerConnect(con);
                // Only call prepareOutbound() if connection is in initial state
                // to avoid IllegalStateException when connection is already in progress
                EstablishState est = con.getEstablishState();
                if (est instanceof OutboundNTCP2State) {
                    OutboundNTCP2State state = (OutboundNTCP2State) est;
                    if (state.isInitialState()) {
                        est.prepareOutbound();
                    } else if (_log.shouldDebug()) {
                        _log.debug("Skipping prepareOutbound() for connection already in progress: " + con);
                    }
                } else {
                    try {
                        est.prepareOutbound();
                    } catch (IllegalStateException ise) {
                        // Connection is already in progress, this is expected in race conditions
                        if (_log.shouldDebug()) {
                            _log.debug("Ignoring IllegalStateException for connection already in progress: " + con + " - " + ise.getMessage());
                        }
                        // Don't log as warning since this is expected behavior
                        return;
                    }
                }
            }
        } else {
            con.send(msg);
        }
    }

    /**
     * Logs errors or warnings related to connection setup failures, differentiating
     * between IOExceptions and IllegalStateExceptions with appropriate log levels.
     *
     * @param e The exception that occurred during connection setup.
     */
    private void logConnectionSetupError(Exception e) {
        if (e instanceof IOException && !shouldSuppressException(e)) {
            if (_log.shouldWarn()) _log.warn("[NTCP] Error opening a channel -> IO Exception" + (e.getMessage() != null ? ":" + e.getMessage() : ""));
            _context.statManager().addRateData("ntcp.outboundFailedIOEImmediate", 1);
        } else if (e instanceof IllegalStateException && !shouldSuppressException(e)) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("Unexpected prepareOutbound()")) {
                // This is an expected race condition, log at debug level only
                if (_log.shouldDebug()) _log.debug("[NTCP] Race condition during channel setup - " + msg);
            } else {
                // Other IllegalStateExceptions are still warnings
                if (_log.shouldWarn()) _log.warn("[NTCP] Failed opening a channel \n* Illegal State Exception" + (msg != null ? ":" + msg : ""));
            }
        }
    }

    @Override
    public void afterSend(OutNetMessage msg, boolean sendSuccessful, boolean allowRequeue, long msToSend) {
        super.afterSend(msg, sendSuccessful, allowRequeue, msToSend);
    }

    public TransportBid bid(RouterInfo toAddress, int dataSize) {
        if (!isAlive()) {return null;}
        // passed in dataSize assumes 16 byte header, if NTCP2 then
        // we have a 9-byte header so there's 7 to spare
        if (dataSize > NTCPConnection.NTCP2_MAX_MSG_SIZE + 7) {
            // Too big for NTCP2
            // Let SSU deal with it
            _context.statManager().addRateData("ntcp.noBidTooLargeI2NP", dataSize);
            return null;
        }
        Hash peer = toAddress.getIdentity().calculateHash();
        if (_context.banlist().isBanlisted(peer, STYLE)) {
            // we aren't banlisted in general (since we are trying to get a bid), but we have
            // recently banlisted the peer on the NTCP transport, so don't try it
            _context.statManager().addRateData("ntcp.attemptBanlistedPeer", 1);
            return null;
        } else if (isUnreachable(peer)) {
            _context.statManager().addRateData("ntcp.attemptUnreachablePeer", 1);
            return null;
        }

        boolean established = isEstablished(peer);
        if (established) {// should we check the queue size?  nah, if its valid, use it
            return _fastBid;
        }
        int nid = toAddress.getNetworkId();
        if (nid != _networkID) {
            if (nid == -1)
                _context.banlist().banlistRouter(peer, " <b>➜</b> No network specified", null, null, _context.clock().now() + Banlist.BANLIST_DURATION_NO_NETWORK);
            else
                _context.banlist().banlistRouterForever(peer, " <b>➜</b> Not in our network: " + nid);
            if (_log.shouldWarn())
                _log.warn("[NTCP] Not in our network: " + toAddress, new Exception());
            markUnreachable(peer);
            return null;
        }

        RouterAddress addr = getTargetAddress(toAddress);
        if (addr == null) {
            markUnreachable(peer);
            return null;
        }

        // Check for supported sig type
        SigType type = toAddress.getIdentity().getSigType();
        if (type == null || !type.isAvailable()) {
            markUnreachable(peer);
            return null;
        }

        // Can we connect to them if we are not DSA?
        RouterInfo us = _context.router().getRouterInfo();
        if (us != null) {
            RouterIdentity id = us.getIdentity();
            if (id.getSigType() != SigType.DSA_SHA1) {
                String v = toAddress.getVersion();
                if (VersionComparator.comp(v, MIN_SIGTYPE_VERSION) < 0) {
                    markUnreachable(peer);
                    return null;
                }
            }
        }

        if (!allowConnection()) {
            //if (_log.shouldWarn())
            //    _log.warn("no bid when trying to send to " + peer + ", max connection limit reached");
            return _transientFail;
        }

        //if ((_myAddress != null) && (_myAddress.equals(addr)))
        //    return null; // Don't talk to yourself

        //if (_log.shouldDebug())
        //    _log.debug("slow bid when trying to send to " + peer);
        if (haveCapacity()) {
            if (addr.getCost() > DEFAULT_COST)
                return _slowCostBid;
            else
                return _slowBid;
        } else {
            if (addr.getCost() > DEFAULT_COST)
                return _nearCapacityCostBid;
            else
                return _nearCapacityBid;
        }
    }

    /**
     *  Get first available address we can use.
     *  @return address or null
     *  @since 0.9.6
     */
    private RouterAddress getTargetAddress(RouterInfo target) {
        List<RouterAddress> addrs = getTargetAddresses(target);
        for (int i = 0; i < addrs.size(); i++) {
            RouterAddress addr = addrs.get(i);
            // use this to skip outbound-only NTCP2,
            // and NTCP1 if disabled
            if (getNTCPVersion(addr) == 0)
                continue;
            byte[] ip = addr.getIP();
            if (!TransportUtil.isValidPort(addr.getPort()) || ip == null) {
                //_context.statManager().addRateData("ntcp.connectFailedInvalidPort", 1);
                //_context.banlist().banlistRouter(toAddress.getIdentity().calculateHash(), " <b>➜</b> Invalid NTCP address", STYLE);
                //if (_log.shouldDebug())
                //    _log.debug("no bid when trying to send to " + peer + " as they don't have a valid ntcp address");
                continue;
            }
            if (!isValid(ip)) {
                if (! allowLocal()) {
                    //_context.statManager().addRateData("ntcp.bidRejectedLocalAddress", 1);
                    //if (_log.shouldDebug())
                    //    _log.debug("no bid when trying to send to " + peer + " as they have a private ntcp address");
                    continue;
                }
            }
            return addr;
        }
        return null;
    }

    /**
     * An IPv6 address is only valid if we are configured to support IPv6
     * AND we have a public IPv6 address.
     *
     * @param addr may be null, returns false
     * @since 0.9.8
     */
    private boolean isValid(byte addr[]) {
        if (addr == null) return false;
        if (isPubliclyRoutable(addr) &&
            (addr.length != 16 || _haveIPv6Address))
            return true;
        return false;
    }

    public boolean allowConnection() {
        return _conByIdent.size() < getMaxConnections();
    }

    /** queue up afterSend call, which can take some time w/ jobs, etc */
    void sendComplete(OutNetMessage msg) {_finisher.add(msg);}

    @Override
    public boolean isEstablished(Hash dest) {
            NTCPConnection con = _conByIdent.get(dest);
            return (con != null) && con.isEstablished() && !con.isClosed();
    }

    @Override
    public boolean isBacklogged(Hash dest) {
            NTCPConnection con = _conByIdent.get(dest);
            return (con != null) && con.isEstablished() && con.tooBacklogged();
    }

    /**
     * Tell the transport that we may disconnect from this peer.
     * This is advisory only.
     *
     * @since 0.9.24
     */
    @Override
    public void mayDisconnect(final Hash peer) {
        final NTCPConnection con = _conByIdent.get(peer);
        if (con != null && con.isEstablished() &&
            con.getMessagesReceived() <= 2 && con.getMessagesSent() <= 1) {
            con.setMayDisconnect();
        }
    }

    /**
     * Tell the transport to disconnect from this peer.
     *
     * @since 0.9.38
     */
    public void forceDisconnect(Hash peer) {
        NTCPConnection con = _conByIdent.remove(peer);
        boolean isBanned = _context.banlist().isBanlisted(peer);
        boolean isBannedHard = _context.banlist().isBanlistedForever(peer);
        boolean isBlocklisted = _context.blocklist().isBlocklisted(peer);
        if (con != null) {
            if (_log.shouldWarn()) {
                _log.warn("[NTCP] Forcing immediate disconnection of " +
                          (isBannedHard ? "permanently banned " : isBanned ? "temp banned " : isBlocklisted ? "blocklisted " : "") +
                          "Router [" + peer.toBase64().substring(0,6) + "]");
            }
            con.close();
        }
    }

    /**
     * @return usually the con passed in, but possibly a second connection with the same peer...
     *         only con or null as of 0.9.37
     */
    NTCPConnection removeCon(NTCPConnection con) {
        NTCPConnection removed = null;
        RouterIdentity ident = con.getRemotePeer();
        if (ident != null) {
            synchronized (_conLock) {
                // only remove the con passed in
                //removed = _conByIdent.remove(ident.calculateHash());
                if (_conByIdent.remove(ident.calculateHash(), con))
                    removed = con;
            }
        }
        return removed;
    }

    public int countPeers() {
            return _conByIdent.size();
    }

    /**
     * @return 8 bytes:
     *         version 1 4 bytes all zeros
     *         version 2 ipv4 in/out, ipv6 in/out
     * @since 0.9.57
     */
    public int[] getPeerCounts() {
        int[] rv = new int[8];
        final long now = _context.clock().now();
        for (NTCPConnection con : _conByIdent.values()) {
            if ((con.getMessagesSent() > 0 && con.getTimeSinceSend(now) <= 60*1000) ||
                (con.getMessagesReceived() > 0 && con.getTimeSinceReceive(now) <= 60*1000)) {
                int idx = 4;
                if (con.isIPv6())
                    idx += 2;
                if (!con.isInbound())
                    idx++;
                rv[idx]++;
            }
        }
        return rv;
    }

    /**
     * For /peers UI only. Not a public API, not for external use.
     *
     * @return not a copy, do not modify
     * @since 0.9.31
     */
    public Collection<NTCPConnection> getPeers() {
        return _conByIdent.values();
    }

    /**
     * Connected peers.
     *
     * @return a copy, modifiable
     * @since 0.9.34
     */
    public List<Hash> getEstablished() {
        List<Hash> rv = new ArrayList<Hash>(_conByIdent.size());
        for (Map.Entry<Hash, NTCPConnection> e : _conByIdent.entrySet()) {
            NTCPConnection con = e.getValue();
            if (con.isEstablished() && !con.isClosed())
                rv.add(e.getKey());
        }
        return rv;
    }

    /**
     * How many peers have we talked to in the last minute?
     * As of 0.9.20, actually returns active peer count, not total.
     */
    public int countActivePeers() {
        final long now = _context.clock().now();
        int active = 0;
        for (NTCPConnection con : _conByIdent.values()) {
            // con initializes times at construction, so check message count also
            if ((con.getMessagesSent() > 0 && con.getTimeSinceSend(now) <= 60*1000) ||
                (con.getMessagesReceived() > 0 && con.getTimeSinceReceive(now) <= 60*1000)) {
                active++;
            }
        }
        return active;
    }

    /**
     * How many peers are we actively sending messages to (this minute)
     */
    public int countActiveSendPeers() {
        final long now = _context.clock().now();
        int active = 0;
        for (NTCPConnection con : _conByIdent.values()) {
            // con initializes times at construction, so check message count also
            if (con.getMessagesSent() > 0 && con.getTimeSinceSend(now) <= 60*1000) {active++;}
        }
        return active;
    }

    /**
     *  A positive number means our clock is ahead of theirs.
     *
     *  @param skew in seconds
     */
    void setLastBadSkew(long skew) {_lastBadSkew = skew;}

    /**
     * Return our peer clock skews on this transport.
     * List composed of Long, each element representing a peer skew in seconds.
     * A positive number means our clock is ahead of theirs.
     */
    @Override
    public List<Long> getClockSkews() {
        List<Long> skews = new ArrayList<Long>(_conByIdent.size());
        // Omit ones established too long ago,
        // since the skew is only set at startup (or after a meta message)
        // and won't include effects of later offset adjustments
        long tooOld = _context.clock().now() - 10*60*1000;

        for (NTCPConnection con : _conByIdent.values()) {
            // TODO skip isEstablished() check?
            if (con.isEstablished() && con.getCreated() > tooOld) {
                skews.add(Long.valueOf(con.getClockSkew()));
            }
        }

        // If we don't have many peers, maybe it is because of a bad clock, so
        // return the last bad skew we got
        if (skews.size() < 5 && _lastBadSkew != 0) {skews.add(Long.valueOf(_lastBadSkew));}
        return skews;
    }

    /**
     *  Incoming connection replay detection.
     *  As there is no timestamp in the first message, we can't detect
     *  something long-delayed. To be fixed in next version of NTCP.
     *
     *  @param hxhi using first 8 bytes only
     *  @return valid
     *  @since 0.9.12
     */
    boolean isHXHIValid(byte[] hxhi) {
        return !_replayFilter.add(hxhi, 0, 8);
    }

    /**
     * Starts the NTCP transport listening process, ensuring only one pumper is running
     * by checking existing state since the caller may not stop the transport correctly.
     * It configures and binds local addresses, handles firewall and network interface
     * settings, and sets fallback addresses as needed. This method is synchronized
     * to prevent concurrent starts.
     */
    public synchronized void startListening() {
        if (_pumper.isAlive()) return;
        if (_log.shouldWarn()) _log.warn("Starting NTCP transport listening...");

        startIt();
        RouterAddress addr = configureLocalAddress();
        boolean ssuDisabled = !_context.getBooleanPropertyDefaultTrue(TransportManager.PROP_ENABLE_UDP);
        boolean isFixedOrForceFirewalled = !"false".equalsIgnoreCase(_context.getProperty(PROP_I2NP_NTCP_AUTO_IP, "true"));

        int port = (addr != null) ? addr.getPort() : (ssuDisabled ? setupPort() : _ssuPort);
        RouterAddress myAddress = bindAddress(port);

        if (myAddress != null) {
            replaceAddress(myAddress);
        } else if (addr != null) {
            replaceAddress(addr);
        } else if (port > 0 && !isFixedOrForceFirewalled) {
            Collection<InetAddress> addrs = getSavedLocalAddresses();
            if (!addrs.isEmpty() && !_context.router().isHidden()) {
                boolean skipv4 = false, skipv6 = false;
                int count = 0;

                boolean ipv6Firewalled = isIPv6Firewalled();
                boolean propIPv6Firewalled = _context.getBooleanProperty(PROP_IPV6_FIREWALLED);
                boolean checkIPv6Skip = ipv6Firewalled || (propIPv6Firewalled && !ssuDisabled);
                for (InetAddress ia : addrs) {
                    boolean ipv6 = ia instanceof Inet6Address;
                    if ((ipv6 && checkIPv6Skip) || (!ipv6 && isIPv4Firewalled())) {
                        if (ipv6) skipv6 = true;
                        else skipv4 = true;
                        if (skipv4 && skipv6) break; // early exit, both skipped
                        continue;
                    }
                    OrderedProperties props = new OrderedProperties();
                    props.setProperty(RouterAddress.PROP_HOST, ia.getHostAddress());
                    props.setProperty(RouterAddress.PROP_PORT, Integer.toString(port));
                    addNTCP2Options(props);
                    int cost = getDefaultCost(ipv6);
                    RouterAddress address = new RouterAddress(getPublishStyle(), props, cost);
                    replaceAddress(address);
                    count++;
                }
                if (count <= 0) {
                    setOutboundNTCP2Address();
                } else if (skipv6) {
                    setOutboundNTCP2Address(true);
                } else if (skipv4) {
                    setOutboundNTCP2Address(false);
                }
            } else {
                setOutboundNTCP2Address();
            }
        } else {
            setOutboundNTCP2Address();
        }

        if (ssuDisabled) {
            long now = _context.clock().now();
            _lastInboundIPv4 = now;
            _lastInboundIPv6 = now;
        }
    }

    /**
     *  Outbound only, both IPv4 and IPv6, NTCP2 with "s" and "v" only
     *  @since 0.9.36
     */
    private void setOutboundNTCP2Address() {
        OrderedProperties props = new OrderedProperties();
        addNTCP2Options(props);
        RouterAddress myAddress = new RouterAddress(STYLE2, props, NTCP2_OUTBOUND_COST);
        replaceAddress(myAddress);
    }

    /**
     *  Outbound only, either IPv4 or IPv6, NTCP2 with "s" and "v" only.
     *  @since 0.9.50
     */
    private void setOutboundNTCP2Address(boolean ipv6) {
        // following is like addNTCP2Options() but adds 4 or 6 only,
        // and returns if not appropriately configured
        String caps;
        TransportUtil.IPv6Config config = getIPv6Config();
        if (ipv6) {
            if (config == IPV6_DISABLED) {return;}
            caps = CAP_IPV6;
        } else {
            if (config == IPV6_ONLY) {return;}
            caps = CAP_IPV4;
        }
        OrderedProperties props = new OrderedProperties();
        props.setProperty("caps", caps);
        props.setProperty("s", _b64Ntcp2StaticPubkey);
        props.setProperty("v", NTCP2_VERSION);
        RouterAddress myAddress = new RouterAddress(STYLE2, props, NTCP2_OUTBOUND_COST);
        replaceAddress(myAddress);
    }

    /**
     *  Only called by externalAddressReceived().
     *  Calls replaceAddress() or removeAddress().
     *  To remove all addresses, call replaceAddress(null) directly.
     *
     *  Doesn't actually restart unless addr is non-null and
     *  the port is different from the current listen port.
     *  If addr is null, removes the addresses specified (v4 or v6)
     *
     *  If we had interface addresses before, we lost them.
     *
     *  @param addr may be null to indicate remove the address
     *  @param ipv6 ignored if addr is non-null
     */
    private synchronized void restartListening(RouterAddress addr, boolean ipv6) {
        if (addr != null) {
            RouterAddress myAddress = bindAddress(addr.getPort());
            if (myAddress != null) {replaceAddress(myAddress);}
            else {replaceAddress(addr);}
            // UDPTransport.rebuildExternalAddress() calls router.rebuildRouterInfo()
        } else {
            removeAddress(ipv6);
            if (ipv6) {_lastInboundIPv6 = 0;}
            else {_lastInboundIPv4 = 0;}
        }
    }

    /**
     *  Start up. Caller must synchronize.
     *  @since 0.8.3
     */
    private void startIt() {
        _finisher.start();
        _pumper.startPumping();
        int threads = SystemVersion.isSlow() ? 3 : 6;
        long maxMemory = SystemVersion.getMaxMemory();
        _reader.startReading(threads);
        _writer.startWriting(threads);
    }

    public boolean isAlive() {return _pumper.isAlive();}

    /**
     *  Only does something if myPort > 0 and myPort != current bound port
     *  (or there's no current port, or the configured interface or hostname changed).
     *  If we are changing the bound port, this restarts everything, which takes a long time.
     *
     *  call from synchronized method
     *
     *  @param myPort does nothing if <= 0
     *  @return new address ONLY if bound to specific address, otherwise null
     */
    private RouterAddress bindAddress(int port) {
        RouterAddress myAddress = null;
        if (port > 0) {
            InetAddress bindToAddr = null;
            String bindTo = _context.getProperty(PROP_BIND_INTERFACE);

            if (bindTo == null) {
                // If we are configured with a fixed IP address,
                // AND it's one of our local interfaces,
                // bind only to that.
                bindTo = getFixedHost();
            }

            if (bindTo != null) {
                try {bindToAddr = InetAddress.getByName(bindTo);}
                catch (UnknownHostException uhe) {_log.error("[NTCP] Invalid bind interface specified [" + bindTo + "]", uhe);}
            }

            try {
                InetSocketAddress addr;
                if (bindToAddr == null) {addr = new InetSocketAddress(port);}
                else {
                    addr = new InetSocketAddress(bindToAddr, port);
                    if (_log.shouldWarn()) {_log.warn("[NTCP] Binding only to " + bindToAddr);}
                    OrderedProperties props = new OrderedProperties();
                    props.setProperty(RouterAddress.PROP_HOST, bindTo);
                    props.setProperty(RouterAddress.PROP_PORT, Integer.toString(port));
                    addNTCP2Options(props);
                    int cost = getDefaultCost(false);
                    myAddress = new RouterAddress(getPublishStyle(), props, cost);
                }
                if (!_endpoints.isEmpty()) {
                    // If we are already bound to the new address, OR
                    // if the host is specified and we are bound to the wildcard on the same port,
                    // do nothing. Changing config from wildcard to a specified host will
                    // require a restart.
                    if (_endpoints.contains(addr) ||
                        (bindToAddr != null && _endpoints.contains(new InetSocketAddress(port)))) {
                        if (_log.shouldWarn()) {_log.warn("[NTCP] Already listening on " + addr);}
                        return null;
                    }
                    // if UDP only changed external port and not internal port,
                    // do not rebind internally and restart, just change the address
                    int eport = _context.getProperty(UDPTransport.PROP_EXTERNAL_PORT, 0);
                    int iport = _context.getProperty(UDPTransport.PROP_INTERNAL_PORT, 0);
                    if (port == eport && iport > 0 && eport != iport) {
                        if (_log.shouldWarn()) {
                            _log.warn("[NTCP] External port changed to " + eport + ", keep listening on internal port " + iport);
                        }
                        return null;
                    }
                    // FIXME support multiple binds
                    // FIXME just close and unregister
                    stopWaitAndRestart();
                }
                if (!TransportUtil.isValidPort(port)) {
                    _log.error("[NTCP] Specified port is " + port + ", ports lower than 1024 not recommended");
                }
                ServerSocketChannel chan = ServerSocketChannel.open();
                chan.configureBlocking(false);
                // TODO retry
                chan.socket().bind(addr);
                _endpoints.add(addr);
                if (_log.shouldInfo()) {_log.info("[NTCP] Listening on " + addr);}
                _pumper.register(chan);
            } catch (IOException ioe) {
                _log.error("[NTCP] Error listening", ioe);
                myAddress = null;
            }
        } else {
            if (_log.shouldInfo()) {
                _log.info("[NTCP] Outbound connections only - no listener configured");
            }
        }
        return myAddress;
    }

    /**
     *  @return configured host (as an IP String) or null. Must be one of our local interfaces.
     *  @since IPv6 moved from bindAddress()
     */
    private String getFixedHost() {
        boolean isFixed = _context.getProperty(PROP_I2NP_NTCP_AUTO_IP, "true")
                          .toLowerCase(Locale.US).equals("false");
        String fixedHost = _context.getProperty(PROP_I2NP_NTCP_HOSTNAME);
        if (isFixed && fixedHost != null) {
            try {
                String testAddr = InetAddress.getByName(fixedHost).getHostAddress();
                // FIXME range of IPv6 addresses
                if (Addresses.getAddresses().contains(testAddr))
                    return testAddr;
            } catch (UnknownHostException uhe) {}
        }
        return null;
    }

    /**
     *  Caller must sync
     *  @since IPv6 moved from externalAddressReceived()
     */
    private void stopWaitAndRestart() {
        if (_log.shouldWarn()) {_log.warn("Halting NTCP to change address...");}
        stopListening();
        // Wait for NTCP Pumper to stop so we don't end up with two...
        while (isAlive()) {
            try {Thread.sleep(5*1000);}
            catch (InterruptedException ie) {}
        }
        if (_log.shouldWarn()) {_log.warn("Restarting NTCP transport listener...");}
        startIt();
    }

    /**
     *  Hook for NTCPConnection
     */
    Reader getReader() {return _reader;}

    /**
     *  Hook for NTCPConnection
     */
    net.i2p.router.transport.ntcp.Writer getWriter() {return _writer;}

    /**
     * @return always "NTCP"
     */
    public String getStyle() {return STYLE;}

    /**
     * An alternate supported style
     * @return "NTCP2" always
     * @since 0.9.35
     */
    @Override
    public String getAltStyle() {return STYLE2;}

    /**
     * @return "NTCP2" always
     * @since 0.9.39
     */
    private String getPublishStyle() {return STYLE2;}

    /**
     *  Hook for NTCPConnection
     */
    EventPumper getPumper() {return _pumper;}

    /**
     *  @return null if not configured for NTCP2
     *  @since 0.9.36
     */
    X25519KeyFactory getXDHFactory() {return _xdhFactory;}

    /**
     * how long from initial connection attempt (accept() or connect()) until
     * the con must be established to avoid premature close()ing
     */
    public static final int ESTABLISH_TIMEOUT = 10*1000;

    /** add us to the establishment timeout process */
    void establishing(NTCPConnection con) {_establishing.add(con);}

    /**
     * called in the EventPumper no more than once a second or so, closing
     * any unconnected/unestablished connections
     */
    void expireTimedOut() {
        int expired = 0;
        final long now = _context.clock().now();

            for (Iterator<NTCPConnection> iter = _establishing.iterator(); iter.hasNext(); ) {
                NTCPConnection con = iter.next();
                if (con.isClosed() || con.isEstablished()) {iter.remove();}
                else if (con.getTimeSinceCreated(now) > ESTABLISH_TIMEOUT) {
                    iter.remove();
                    con.close();
                    expired++;
                }
            }

        if (expired > 0) {
            _context.statManager().addRateData("ntcp.outboundEstablishFailed", expired);
        }
    }

    /**
     *  Generally returns null
     *  caller must synch on this
     *  Note this is only called from startListening()
     *
     *  TODO return a list of one or more
     *  TODO only returns non-null if port is configured
     */
    private RouterAddress configureLocalAddress() {
        // this generally returns null -- see javadoc
        RouterAddress addr = createNTCPAddress();
        if (addr != null) {
            if (addr.getPort() <= 0) {
                addr = null;
                if (_log.shouldError()) {
                    _log.error("NTCP address is outbound only, since the NTCP configuration is INVALID");
                }
            } else {
                if (_log.shouldInfo()) {_log.info("NTCP address configured: " + addr);}
            }
        } else if (_log.shouldInfo()) {_log.info("NTCP address is outbound only");}
        return addr;
    }

    /**
     * This only creates an address if the hostname AND port are set in router.config,
     * which should be rare.
     * Otherwise, notifyReplaceAddress() below takes care of it.
     * Note this is only called from startListening() via configureLocalAddress()
     *
     * TODO return a list of one or more
     * TODO unlike in UDP rebuildExternalAddress(), this only runs once, at startup,
     * so we won't pick up IP changes.
     * TODO only returns non-null if port is configured
     *
     * @since IPv6 moved from CSFI
     */
    private RouterAddress createNTCPAddress() {
        int p = _context.getProperty(PROP_I2NP_NTCP_PORT, -1);
        if (p <= 0 || p >= 64*1024) {return null;}

        String name = getConfiguredIP();
        if (name == null) {return null;}

        OrderedProperties props = new OrderedProperties();
        props.setProperty(RouterAddress.PROP_HOST, name);
        props.setProperty(RouterAddress.PROP_PORT, Integer.toString(p));
        addNTCP2Options(props);
        int cost = getDefaultCost(false);
        RouterAddress addr = new RouterAddress(getPublishStyle(), props, cost);
        return addr;
    }

    /**
     * Add the required options to the properties for a NTCP2 address.
     * Host/port must already be set in props if they are going to be.
     *
     * @since 0.9.35
     */
    private void addNTCP2Options(Properties props) {
        // only set i if we are not firewalled
        if (props.containsKey("host")) {
            props.setProperty("i", _b64Ntcp2StaticIV);
            props.remove("caps");
        } else {
            String caps;
            TransportUtil.IPv6Config config = getIPv6Config();
            if (config == IPV6_ONLY) {caps = CAP_IPV6;}
            else if (config != IPV6_DISABLED && _haveIPv6Address) {caps = CAP_IPV4_IPV6;}
            else {caps = CAP_IPV4;}
            props.setProperty("caps", caps);
        }
        props.setProperty("s", _b64Ntcp2StaticPubkey);
        props.setProperty("v", NTCP2_VERSION);
    }

    /**
     * The static priv key
     *
     * @since 0.9.36
     */
    byte[] getNTCP2StaticPubkey() {return _ntcp2StaticPubkey;}

    /**
     * The static priv key
     *
     * @since 0.9.35
     */
    byte[] getNTCP2StaticPrivkey() {return _ntcp2StaticPrivkey;}

    /**
     * The static IV
     *
     * @since 0.9.36
     */
    byte[] getNTCP2StaticIV() {return _ntcp2StaticIV;}

    /**
     * Get the valid NTCP version of Bob's NTCP address
     * for our outbound connections as Alice.
     *
     * @return the valid version 1 or 2, or 0 if unusable
     * @since 0.9.35
     */
    private int getNTCPVersion(RouterAddress addr) {
        int rv;
        String style = addr.getTransportStyle();
        if (style.equals(STYLE)) {rv = 1;}
        else if (style.equals(STYLE2)) {rv = NTCP2_INT_VERSION;}
        else {return 0;}
        // check version == "2" || version starts with "2,"
        // and static key, and iv
        String v = addr.getOption("v");
        if (v == null ||
            addr.getOption("i") == null ||
            addr.getOption("s") == null ||
            (!v.equals(NTCP2_VERSION) && !v.startsWith(NTCP2_VERSION_ALT))) {
            // his address is NTCP1 or is outbound NTCP2 only
            return 0;
        }
        // his address is NTCP2
        // do not validate the s/i b64, we will just catch it later
        return NTCP2_INT_VERSION;
    }

    /**
     * Return a single configured IP (as a String) or null if not configured or invalid.
     * Resolves a hostname to an IP.
     * Called at startup via createNTCPAddress() and later via externalAddressReceived()
     *
     * TODO return a list of one or more
     *
     * @since 0.9.32
     */
    private String getConfiguredIP() {
        // Fixme doesn't check PROP_BIND_INTERFACE
        String name = _context.getProperty(PROP_I2NP_NTCP_HOSTNAME);
        if ((name == null) || (name.trim().length() <= 0) || ("null".equals(name))) {
            return null;
        }
        String[] hosts = DataHelper.split(name, "[,; \r\n\t]");
        List<String> ipstrings = new ArrayList<String>(2);
        // we only take one each of v4 and v6
        boolean v4 = false;
        boolean v6 = false;
        // prevent adding a type if disabled
        TransportUtil.IPv6Config cfg = getIPv6Config();
        if (cfg == IPV6_DISABLED) {v6 = true;}
        else if (cfg == IPV6_ONLY) {v4 = true;}
        for (int i = 0; i < hosts.length; i++) {
            String h = hosts[i];
            if (h.length() <= 0) {continue;}
            if (Addresses.isIPv4Address(h)) {
                if (v4) {continue;}
                v4 = true;
                ipstrings.add(h);
            } else if (Addresses.isIPv6Address(h)) {
                if (v6) {continue;}
                v6 = true;
                ipstrings.add(h);
            } else {
                int valid = 0;
                List<byte[]> ips = Addresses.getIPs(h);
                if (ips != null) {
                    for (byte[] ip : ips) {
                        if (!isValid(ip)) {
                            if (_log.shouldWarn()) {
                                _log.warn("[NTCP] Skipping invalid " + Addresses.toString(ip) + " for " + h);
                            }
                            continue;
                        }
                        if ((v4 && ip.length == 4) || (v6 && ip.length == 16)) {
                            if (_log.shouldWarn()) {
                                _log.warn("[NTCP] Skipping additional " + Addresses.toString(ip) + " for " + h);
                            }
                            continue;
                        }
                        if (ip.length == 4) {v4 = true;}
                        else if (ip.length == 16) {v6 = true;}
                        valid++;
                        if (_log.shouldDebug()) {
                            _log.debug("[NTCP] Adding " + Addresses.toString(ip) + " for " + h);
                        }
                        ipstrings.add(Addresses.toString(ip));
                    }
                }
                if (valid == 0) {_log.error("[NTCP] No valid IPs for configured hostname " + h);}
                continue;
            }
        }

        if (ipstrings.isEmpty()) {
            _log.error("[NTCP] No valid IPs for configuration: " + name);
            return null;
        }

        // get first IPv4, if none then first IPv6
        // TODO return both
        String ip = null;
        for (String ips : ipstrings) {
            if (ips.contains(".")) {
                ip = ips;
                break;
            }
        }
        if (ip == null) {ip = ipstrings.get(0);}
        return ip;
    }

    private int getDefaultCost(boolean isIPv6) {
        int rv = DEFAULT_COST;
        if (isIPv6) {
            TransportUtil.IPv6Config config = getIPv6Config();
            if (config == IPV6_PREFERRED) {rv--;}
            else if (config == IPV6_NOT_PREFERRED) {rv++;}
        }
        return rv;
    }

    /**
     *  UDP changed addresses, tell NTCP and (possibly) restart
     *
     *  @param ip typ. IPv4 or IPv6 non-local; may be null to indicate IPv4 failure or port info only
     *  @since IPv6 moved from CSFI.notifyReplaceAddress()
     */
    @Override
    public void externalAddressReceived(AddressSource source, byte[] ip, int port) {
        if (_log.shouldWarn()) {
            _log.warn("Received address: " + Addresses.toString(ip, port) + " from: " + source);
        }
        if ((source == SOURCE_INTERFACE || source == SOURCE_SSU)
             && ip != null && ip.length == 16) {_haveIPv6Address = true;} // must be set before isValid() call
        if (ip != null && !isValid(ip) && !allowLocal()) {
            if (_log.shouldWarn()) {
                _log.warn("[NTCP] Invalid address: " + Addresses.toString(ip, port) + " from: " + source);
            }
            return;
        }
        if (!isAlive()) {
            if (source == SOURCE_INTERFACE || source == SOURCE_UPNP) {
                try {
                    InetAddress ia = InetAddress.getByAddress(ip);
                    saveLocalAddress(ia);
                } catch (UnknownHostException uhe) {}
            } else if (source == SOURCE_CONFIG) {synchronized(this) {_ssuPort = port;}} // save for startListening()
            return;
        }
        // ignore UPnP for now, get everything from SSU if it's enabled
        boolean ssuEnabled = _context.getBooleanPropertyDefaultTrue(TransportManager.PROP_ENABLE_UDP);
        if (source != SOURCE_SSU && ssuEnabled) {return;}
        Status old = ssuEnabled ? null : getReachabilityStatus();
        boolean isIPv6 = ip != null && ip.length == 16;
        boolean changed = externalAddressReceived(ip, isIPv6, port);
        if (changed && !ssuEnabled) {addressChanged(old);}
    }

    /**
     *  Notify a transport of an external address change.
     *  This may be from a local interface, UPnP, a config change, etc.
     *  This should not be called if the ip didn't change
     *  (from that source's point of view), or is a local address.
     *  May be called multiple times for IPv4 or IPv6.
     *  The transport should also do its own checking on whether to accept
     *  notifications from this source.
     *
     *  This can be called after the transport is running.
     *
     *  TODO externalAddressRemoved(source, ip, port)
     *
     *  @param source defined in Transport.java
     *  @since 0.9.20
     */
    @Override
    public void externalAddressRemoved(AddressSource source, boolean ipv6) {
        if (_log.shouldWarn()) {
            _log.warn("[NTCP] Removing external address: (IPv6 enabled? " + ipv6 + ") from: " + source);
        }
        // ignore UPnP for now, get everything from SSU if it's enabled
        boolean ssuEnabled = _context.getBooleanPropertyDefaultTrue(TransportManager.PROP_ENABLE_UDP);
        if (source != SOURCE_SSU && ssuEnabled) {return;}
        RouterAddress oldAddr = getCurrentAddress(ipv6);
        if (oldAddr == null) {return;}
        Status old = ssuEnabled ? null : getReachabilityStatus();
        boolean changed = externalAddressReceived(null, ipv6, 0);
        if (changed && !ssuEnabled) {addressChanged(old);}
    }

    /**
     *  Only called if SSU is disabled AND our address changed.
     *  Tell the event log, and tell the router.
     *
     *  @since 0.9.40
     */
    private void addressChanged(Status old) {
        Status status = getReachabilityStatus();
        if (status != old) {
            if (_log.shouldWarn()) {
                _log.warn("[NTCP] Old status: " + old + " New status: " + status +
                          " from: ", new Exception("traceback"));
            }
            if (old != Status.UNKNOWN && _context.router().getUptime() > 5*60*1000) {
                _context.router().eventLog().addEvent(EventLog.REACHABILITY,
                _t(old.toStatusString()) + " ➜ " +  _t(status.toStatusString()));
            }
        }
        _context.router().rebuildRouterInfo();
    }

    /**
     *  UDP changed addresses, tell NTCP and restart.
     *  Port may be set to indicate requested port even if ip is null.
     *
     *  @param ip previously validated; may be null to indicate IPv4 failure or port info only
     *  @return true if our address changed
     *  @since IPv6 moved from CSFI.notifyReplaceAddress()
     */
    private synchronized boolean externalAddressReceived(byte[] ip, boolean isIPv6, int port) {
        // FIXME just take first address for now
        // Warning, this returns null when isIPv6 == true and it's an empty "46" address
        // See below
        RouterAddress oldAddr = getCurrentAddress(isIPv6);
        if (_log.shouldInfo())
            _log.info("Changing NTCP IPv" + (isIPv6 ? '6' : '4') + " Address? was " + oldAddr);

        OrderedProperties newProps = new OrderedProperties();
        int cost;
        if (oldAddr == null) {
            cost = getDefaultCost(isIPv6);
        } else {
            cost = oldAddr.getCost();
            newProps.putAll(oldAddr.getOptionsMap());
        }
        RouterAddress newAddr = new RouterAddress(getPublishStyle(), newProps, cost);

        boolean changed = false;

        // Auto Port Setting
        // old behavior (<= 0.7.3): auto-port defaults to false, and true trumps explicit setting
        // new behavior (>= 0.7.4): auto-port defaults to true, but explicit setting trumps auto
        // TODO rewrite this to operate on ints instead of strings
        String oport = newProps.getProperty(RouterAddress.PROP_PORT);
        String nport = null;
        String cport = _context.getProperty(PROP_I2NP_NTCP_PORT);
        if (cport != null && cport.length() > 0) {
            nport = cport;
            if (port > 0 && !nport.equals(Integer.toString(port)))
                _log.logAlways(Log.WARN, "UDP detected external port is " + port + " but TCP configured port is " + nport);
        } else if (_context.getBooleanPropertyDefaultTrue(PROP_I2NP_NTCP_AUTO_PORT)) {
            // 0.9.6 change
            // This wasn't quite right, as udpAddr is the EXTERNAL port and we really
            // want NTCP to bind to the INTERNAL port the first time,
            // because if they are different, the NAT is changing them, and
            // it probably isn't mapping UDP and TCP the same.
            if (port > 0)
                // should always be true
                nport = Integer.toString(port);
        }
        if (_log.shouldInfo())
            _log.info("Old port: " + oport + " Config: " + cport + " New: " + nport);

        if (oport == null && nport != null && nport.length() > 0) {
            newProps.setProperty(RouterAddress.PROP_PORT, nport);
            changed = true;
        }

        // Auto IP Setting
        // old behavior (<= 0.7.3): auto-ip defaults to false, and trumps configured hostname,
        //                          and ignores reachability status - leading to
        //                          "firewalled with inbound TCP enabled" warnings.
        // new behavior (>= 0.7.4): auto-ip defaults to true, and explicit setting trumps auto,
        //                          and only takes effect if reachability is OK.
        //                          And new "always" setting ignores reachability status, like
        //                          "true" was in 0.7.3
        String ohost = newProps.getProperty(RouterAddress.PROP_HOST);
        String enabled = _context.getProperty(PROP_I2NP_NTCP_AUTO_IP, "true").toLowerCase(Locale.US);
        String name = getConfiguredIP();
        // hostname config trumps auto config
        if (name != null && name.length() > 0)
            enabled = "false";

        // assume SSU is happy if the address is non-null
        // TODO is this sufficient?
        boolean ssuOK = ip != null;
        if (_log.shouldInfo())
            _log.info("Old: " + ohost + " Config: " + name + " Auto: " + enabled + " ssuOK? " + ssuOK);
        if (enabled.equals("always") ||
            (Boolean.parseBoolean(enabled) && ssuOK)) {
            if (!ssuOK) {
                if (_log.shouldWarn())
                    _log.warn("Null address with always config", new Exception());
                return false;
            }
            // ip non-null
            String nhost = Addresses.toString(ip);
            if (_log.shouldInfo())
                _log.info("Old: " + ohost + " Config: " + name + " New: " + nhost);
            if (nhost == null || nhost.length() <= 0)
                return false;
            if (ohost == null || ! ohost.equalsIgnoreCase(nhost)) {
                newProps.setProperty(RouterAddress.PROP_HOST, nhost);
                if (cost == NTCP2_OUTBOUND_COST)
                    newAddr.setCost(DEFAULT_COST);
                changed = true;
            }
        } else if (enabled.equals("false") &&
                   name != null && name.length() > 0 &&
                   !name.equals(ohost)) {
            // Host name is configured, and we have a port (either auto or configured)
            // but we probably only get here if the port is auto,
            // otherwise createNTCPAddress() would have done it already
            if (_log.shouldInfo())
                _log.info("old host: " + ohost + " Config: " + name + " New: " + name);
            newProps.setProperty(RouterAddress.PROP_HOST, name);
            if (cost == NTCP2_OUTBOUND_COST)
                newAddr.setCost(DEFAULT_COST);
            changed = true;
        } else if (ohost == null || ohost.length() <= 0) {
            // SSU2 told us to remove our IPv6 address
            // getCurrentAddress(true) returns null for a "46" address
            // Get v4 address and see if it has a "6" in it,
            // if not, put in a "6" address
            if (isIPv6 && _haveIPv6Address && oldAddr == null && ip == null && port <= 0) {
                RouterAddress v4Addr = getCurrentAddress(false);
                if (v4Addr != null) {
                    String caps = v4Addr.getOption("caps");
                    if (caps != null && caps.contains(CAP_IPV6)) {
                        if (_log.shouldInfo())
                            _log.info("[NTCP] No old host, no new host, no change to address");
                        return false;
                    }
                }
                if (_log.shouldInfo())
                    _log.info("[NTCP] IPv6 now firewalled, adding 6 address");
                setOutboundNTCP2Address(true);
                return true;
            }
            if (_log.shouldInfo())
                _log.info("No old host, no new host, no change to NTCP Address");
            return false;
        } else if (Boolean.parseBoolean(enabled) && !ssuOK) {
            // UDP transitioned to not-OK, turn off NTCP address
            // This will commonly happen at startup if we were initially OK
            // because UPnP was successful, but a subsequent SSU Peer Test determines
            // we are still firewalled (SW firewall, bad UPnP indication, etc.)
            if (_log.shouldInfo())
                _log.info("Old host: " + ohost + " Config: " + name + " New: null");
            // addNTCP2Options() called below
            newProps.clear();
            newAddr = new RouterAddress(STYLE2, newProps, NTCP2_OUTBOUND_COST);
            changed = true;
        }

        if (!changed) {
            if (oldAddr != null) {
                // change cost only?
                int oldCost = oldAddr.getCost();
                int newCost = getDefaultCost(ohost != null && ohost.contains(":"));
                if (ADJUST_COST && !haveCapacity())
                    newCost += CONGESTION_COST_ADJUSTMENT;
                if (newCost != oldCost) {
                    newAddr.setCost(newCost);
                    if (_log.shouldWarn())
                        _log.warn("Changing NTCP cost from " + oldCost + " to " + newCost);
                    // fall thru and republish
                } else {
                    _log.info("No change to NTCP Address");
                    return false;
                }
            } else {
                _log.info("No change to NTCP Address");
                return false;
            }
        }

        if (!isIPv6 || newProps.containsKey(RouterAddress.PROP_HOST) || getIPv6Config() == IPV6_ONLY) {
            addNTCP2Options(newProps);
        } else {
            // IPv6
            // We have an IPv4 address, IPv6 transitioned to firewalled,
            if (_log.shouldInfo())
                _log.info("[NTCP] IPv6 now firewalled, adding 6 address");
            setOutboundNTCP2Address(true);
            return true;
        }

        // do not restart on transition to firewalled
        if (ip != null || port > 0)
        restartListening(newAddr, isIPv6);
        else
            replaceAddress(newAddr);
        if (_log.shouldWarn())
            _log.warn("Updating NTCP Address (IPv6? " + isIPv6 + ") with " + newAddr);
        return true;
    }

    /**
     *  If we didn't used to be forwarded, and we have an address,
     *  and we are configured to use UPnP, update our RouterAddress
     *
     *  Don't do anything now. If it fails, we don't know if it's
     *  because there is no firewall, or if the firewall rejected the request.
     *  So we just use the SSU reachability status
     *  to decide whether to enable inbound NTCP. SSU will have CSFI build a new
     *  NTCP address when it transitions to OK.
     */
    @Override
    public void forwardPortStatus(byte[] ip, int port, int externalPort, boolean success, String reason) {
        if (_log.shouldWarn()) {
            if (success)
                _log.warn("UPnP has opened the NTCP port: " + port + " via " + Addresses.toString(ip, externalPort));
            else
                _log.warn("UPnP has failed to open the NTCP port: " + Addresses.toString(ip, externalPort) + " reason: " + reason);
        }
    }

    /**
     *  @return current IPv4 port, else NTCP configured port, else -1 (but not UDP port if auto)
     */
    @Override
    public int getRequestedPort() {
        RouterAddress addr = getCurrentAddress(false);
        if (addr != null) {
            int port = addr.getPort();
            if (port > 0)
                return port;
        }
        // would be nice to do this here but we can't easily get to the UDP transport.getRequested_Port()
        // from here, so we do it in TransportManager.
        // if (Boolean.valueOf(_context.getProperty(CommSystemFacadeImpl.PROP_I2NP_NTCP_AUTO_PORT)).booleanValue())
        //    return foo;
        return _context.getProperty(PROP_I2NP_NTCP_PORT, -1);
    }

    /**
     * Maybe we should trust UPnP here and report OK if it opened the port, but
     * for now we don't. Just go through and if we have one inbound connection,
     * we must be good. As we drop idle connections pretty quickly, this will
     * be fairly accurate.
     *
     * We have to be careful here because much of the router console code assumes
     * that the reachability status is really just the UDP status.
     *
     * This only returns OK, DISABLED, or UNKNOWN for IPv4 and IPv6.
     * We leave the FIREWALLED status for UDP.
     *
     * Previously returned short, now enum as of 0.9.20
     */
    public Status getReachabilityStatus() {
        boolean fwV4 = isIPv4Firewalled();
        boolean fwV6 = isIPv6Firewalled();
        if (fwV4 && fwV6)
            return Status.REJECT_UNSOLICITED;
        if (!isAlive())
            return Status.UNKNOWN;
        TransportUtil.IPv6Config config = getIPv6Config();
        boolean v4Disabled, v6Disabled;
        if (config == IPV6_DISABLED) {
            v4Disabled = false;
            v6Disabled = true;
        } else if (config == IPV6_ONLY) {
            v4Disabled = true;
            v6Disabled = false;
        } else {
            v4Disabled = false;
            v6Disabled = false;
        }
        boolean hasV4 = !fwV4 && getCurrentAddress(false) != null;
        boolean hasV6 = !fwV6 && getCurrentAddress(true) != null;
        boolean showFirewalled = !_context.getBooleanPropertyDefaultTrue(TransportManager.PROP_ENABLE_UDP) &&
                                 _context.router().getUptime() > 10*60*1000;
        if (!hasV4 && !hasV6) {
            return showFirewalled ? Status.REJECT_UNSOLICITED : Status.UNKNOWN;
        }
        long now = _context.clock().now();
        boolean v4OK = hasV4 && !v4Disabled && now - _lastInboundIPv4 < 10*60*1000;
        boolean v6OK = hasV6 && !v6Disabled && now - _lastInboundIPv6 < 30*60*1000;
        if (v4OK) {
            if (v6OK)
                return Status.OK;
            if (v6Disabled)
                return Status.OK;
            if (!_haveIPv6Address)
                return Status.OK;
            if (fwV6)
                return Status.IPV4_OK_IPV6_FIREWALLED;
            if (!hasV6)
                return Status.IPV4_OK_IPV6_UNKNOWN;
        }
        if (v6OK) {
            if (v4Disabled)
                return Status.IPV4_DISABLED_IPV6_OK;
            if (fwV4)
                return Status.IPV4_FIREWALLED_IPV6_OK;
            if (!hasV4)
                return showFirewalled ? Status.IPV4_FIREWALLED_IPV6_OK : Status.IPV4_UNKNOWN_IPV6_OK;
        }
        for (NTCPConnection con : _conByIdent.values()) {
            if (con.isInbound()) {
                if (con.isIPv6()) {
                    if (hasV6)
                        v6OK = true;
                } else {
                    if (hasV4)
                        v4OK = true;
                }
                if (v4OK) {
                    if (v6OK)
                        return Status.OK;
                    if (v6Disabled)
                        return Status.OK;
                    if (!hasV6)
                        return Status.IPV4_OK_IPV6_UNKNOWN;
                }
                if (v6OK) {
                    if (v4Disabled)
                        return Status.IPV4_DISABLED_IPV6_OK;
                    if (!hasV4)
                        return showFirewalled ? Status.IPV4_FIREWALLED_IPV6_OK : Status.IPV4_UNKNOWN_IPV6_OK;
                }
            }
        }
        if (v4OK) {
            if (!_haveIPv6Address)
                return Status.OK;
            return Status.IPV4_OK_IPV6_UNKNOWN;
        }
        if (v6OK)
            return showFirewalled ? Status.IPV4_FIREWALLED_IPV6_OK : Status.IPV4_UNKNOWN_IPV6_OK;
        if (v4Disabled)
            return Status.IPV4_DISABLED_IPV6_UNKNOWN;
        //if (v6Disabled)
        //    return Status.UNKNOWN;
        return showFirewalled ? Status.REJECT_UNSOLICITED : Status.UNKNOWN;
    }

    /**
     *  This doesn't (completely) block, caller should check isAlive()
     *  before calling startListening() or restartListening()
     */
    public synchronized void stopListening() {
        if (_log.shouldWarn()) _log.warn("Stopping NTCP transport...");
        _pumper.stopPumping();
        _writer.stopWriting();
        _reader.stopReading();
        _finisher.stop();
        List<NTCPConnection> cons;
        synchronized (_conLock) {
            cons = new ArrayList<NTCPConnection>(_conByIdent.values());
            _conByIdent.clear();
        }
        for (NTCPConnection con : cons) {
            con.close();
        }
        NTCPConnection.releaseResources();
        replaceAddress(null);
        _endpoints.clear();
        _lastInboundIPv4 = 0;
        _lastInboundIPv6 = 0;
    }

    /**
     * Determines whether the given Throwable (or any of its causes)
     * contains a message that should be suppressed from logging.
     *
     * @param t the Throwable to check
     * @return true if the exception should be suppressed, false otherwise
     * @since 0.9.68+
     */
    private boolean shouldSuppressException(Throwable t) {
        Set<String> suppressionPatterns = new HashSet<>(Arrays.asList(
            "Old and slow",
            "RouterInfo store fail"
        ));

        while (t != null) {
            String message = t.getMessage();
            if (message != null) {
                for (String pattern : suppressionPatterns) {
                    if (message.contains(pattern)) {
                        return true;
                    }
                }
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * Cache the bid to reduce object churn
     */
    private class SharedBid extends TransportBid {
        public SharedBid(int ms) {super(); setLatencyMs(ms);}
        @Override
        public Transport getTransport() {return NTCPTransport.this;}
        @Override
        public String toString() {return "NTCP bid @ " + getLatencyMs();}
    }
}
