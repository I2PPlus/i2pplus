package net.i2p.router.transport.udp;

import static net.i2p.router.transport.Transport.AddressSource.*;
import static net.i2p.router.transport.TransportUtil.IPv6Config.*;
import static net.i2p.router.transport.udp.PeerTestState.Role.*;

import java.io.IOException;
import java.io.Writer;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyPair;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Banlist;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.transport.Transport;
import net.i2p.router.transport.TransportBid;
import net.i2p.router.transport.TransportImpl;
import net.i2p.router.transport.TransportUtil;
import net.i2p.router.transport.crypto.X25519KeyFactory;
import net.i2p.router.util.EventLog;
import net.i2p.router.util.RandomIterator;
import net.i2p.stat.RateConstants;
import net.i2p.util.Addresses;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.LHMCache;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 * Secure Semi-reliable UDP (SSU) transport implementation for I2P.
 *
 * <p>This class provides the primary UDP-based transport for I2P
 * communication, implementing both SSU1 (legacy) and SSU2
 * (modern) protocols. It handles peer connections, packet
 * processing, session management, and reachability testing.</p>
 *
 * <p><strong>Protocol Features:</strong></p>
 * <ul>
 *   <li>Session establishment and maintenance</li>
 *   <li>Inbound and outbound message handling</li>
 *   <li>Peer introduction and testing systems</li>
 *   <li>Reachability detection and reporting</li>
 *   <li>IPv4 and IPv6 addressing support</li>
 *   <li>Packet fragmentation and reassembly</li>
 *   <li>Acknowledgment and reliability mechanisms</li>
 * </ul>
 *
 * <p><strong>Internal Architecture:</strong></p>
 * <ul>
 *   <li>{@link EstablishmentManager} for session establishment</li>
 *   <li>{@link PacketHandler} for processing incoming packets</li>
 *   <li>{@link IntroductionManager} for managing introductions</li>
 *   <li>{@link PeerTestManager} for reachability testing</li>
 *   <li>Various state managers for connection tracking</li>
 * </ul>
 *
 * <p><strong>SSU Protocol Support:</strong></p>
 * <ul>
 *   <li>SSU1: Legacy protocol for older peers</li>
 *   <li>SSU2: Modern protocol with improved efficiency</li>
 *   <li>Automatic protocol negotiation and fallback</li>
 *   <li>Backward compatibility with existing peers</li>
 *  </ul>
 */
public class UDPTransport extends TransportImpl {
    private final Log _log;
    private final List<UDPEndpoint> _endpoints;
    private final Object _addDropLock = new Object();
    /**
     *  Map of known peers, keyed by their identity hash.
     *  Used for fast lookup of peer state information during message handling and routing.
     */
    private final Map<Hash, PeerState> _peersByIdent;
    /**
     *  Map of known peers, keyed by their remote host and port.
     *  Used to identify peers based on source address in incoming packets.
     */
    private final Map<RemoteHostId, PeerState> _peersByRemoteHost;
    private final Map<Long, PeerState2> _peersByConnID;
    private final Map<Long, PeerStateDestroyed> _recentlyClosedConnIDs;
    private PacketHandler _handler;
    private EstablishmentManager _establisher;
    private final OutboundMessageFragments _fragments;
    private volatile PacketPusher _pusher;
    private final InboundMessageFragments _inboundFragments;
    /**
     *  Manages peer reachability testing to determine if we are firewalled or reachable.
     */
    private final PeerTestManager _testManager;
    private final IntroductionManager _introManager;
    private final ExpirePeerEvent _expireEvent;
    private final PeerTestEvent _testEvent;
    private Status _reachabilityStatus;
    private Status _reachabilityStatusPending;
    // only for logging, to be removed
    private long _reachabilityStatusLastUpdated;
    private int _reachabilityStatusUnchanged;
    private long _v4IntroducersSelectedOn;
    private long _v6IntroducersSelectedOn;
    private long _lastInboundReceivedOn;
    private int _mtu = PeerState.MIN_MTU;
    private int _mtu_ipv6 = PeerState.MIN_IPV6_MTU;
    private int _mtu_ssu2;
    private int _mtu_ssu2_ipv6;
    private final int _defaultMTU;
    private boolean _mismatchLogged;
    private final int _networkID;

    /**
     *  Do we have a public IPv6 address?
     */
    private volatile boolean _haveIPv6Address;
    private long _lastInboundIPv6;
    private final int _min_peers;
    private final int _min_v6_peers;

    /**
     *  Flag indicating whether the router's external address needs to be rebuilt.
     *  This is typically set after significant network changes or reachability updates.
     */
    private boolean _needsRebuild;
    private final Object _rebuildLock = new Object();

    /** Introduction key */
    private SessionKey _introKey;

    /**
     *  List of RemoteHostId for peers whose packets we want to drop outright
     *  This is only for old network IDs (pre-0.6.1.10), so it isn't really used now.
     */
    private final Set<RemoteHostId> _dropList;

    private volatile long _expireTimeout;

    /** Last report from a peer of our IP */
    private Hash _lastFromv4, _lastFromv6;
    private byte[] _lastOurIPv4, _lastOurIPv6;
    private int _lastOurPortv4, _lastOurPortv6;
    private boolean _haveUPnP;
    /** We don't publish our IP/port if introduced, so we need to store it somewhere. */
    private RouterAddress _currentOurV4Address;
    private RouterAddress _currentOurV6Address;

    // SSU2
    public static final String STYLE2 = "SSU2";
    static final int SSU2_INT_VERSION = 2;
    /** "2" */
    static final String SSU2_VERSION = Integer.toString(SSU2_INT_VERSION);
    /** "2," */
    static final String SSU2_VERSION_ALT = SSU2_VERSION + ',';
    private final PacketBuilder2 _packetBuilder2;
    private final X25519KeyFactory _xdhFactory;
    private final byte[] _ssu2StaticPubKey;
    private final byte[] _ssu2StaticPrivKey;
    private final byte[] _ssu2StaticIntroKey;
    private final String _ssu2B64StaticPubKey;
    private final String _ssu2B64StaticIntroKey;
    /** b64 static private key */
    public static final String PROP_SSU2_SP = "i2np.ssu2.sp";
    /** b64 static IV */
    public static final String PROP_SSU2_IKEY = "i2np.ssu2.ikey";
    private static final long MIN_DOWNTIME_TO_REKEY_HIDDEN = 24*60*60*1000L;

    private static final int DROPLIST_PERIOD = 10*60*1000;
    public static final String STYLE = "SSU";
    public static final String PROP_INTERNAL_PORT = "i2np.udp.internalPort";
    /** define this to explicitly set an external IP address */
    public static final String PROP_EXTERNAL_HOST = "i2np.udp.host";
    /** define this to explicitly set an external port */
    public static final String PROP_EXTERNAL_PORT = "i2np.udp.port";
    /**
     * If i2np.udp.preferred is set to "always", UDP bids will always be under the bid from
     * the TCP transport - even if a TCP connection already exists.
     * If set to "true", UDP is preferred unless no UDP session exists and a TCP connection
     * already exists.
     * If it is set to "false" (the default), it will prefer TCP unless no TCP session exists
     * and a UDP connection already exists.
     */
    public static final String PROP_PREFER_UDP = "i2np.udp.preferred";
    private static final String DEFAULT_PREFER_UDP = "false";

    /** Override whether we will change our advertised port no matter what our peers tell us
     *  See getIsPortFixed() for default behaviour.
     */
    private static final String PROP_FIXED_PORT = "i2np.udp.fixedPort";

    /** allowed sources of address updates */
    public static final String PROP_SOURCES = "i2np.udp.addressSources";
    public static final String DEFAULT_SOURCES = SOURCE_INTERFACE.toConfigString() + ',' +
                                                 SOURCE_UPNP.toConfigString() + ',' +
                                                 SOURCE_SSU.toConfigString();
    /** remember IP changes */
    public static final String PROP_IP= "i2np.lastIP";
    public static final String PROP_IP_CHANGE = "i2np.lastIPChange";
    public static final String PROP_LAPTOP_MODE = "i2np.laptopMode";
    /** @since 0.9.43 */
    public static final String PROP_IPV6 = "i2np.lastIPv6";

    /** Do we require introducers, regardless of our status? */
    public static final String PROP_FORCE_INTRODUCERS = "i2np.udp.forceIntroducers";
    /** Do we allow direct SSU connections, sans introducers?  */
    public static final String PROP_ALLOW_DIRECT = "i2np.udp.allowDirect";
    /** This is rarely if ever used, default is to bind to wildcard address */
    public static final String PROP_BIND_INTERFACE = "i2np.udp.bindInterface";
    /** Override the "large" (max) MTU, default is PeerState.LARGE_MTU */
    private static final String PROP_DEFAULT_MTU = "i2np.udp.mtu";
    private static final String PROP_ADVANCED = "routerconsole.advanced";
    /** @since 0.9.48 */
    public static final String PROP_INTRO_KEY = "i2np.udp.introKey";

    private static final String CAP_TESTING = Character.toString(UDPAddress.CAPACITY_TESTING);
    private static final String CAP_TESTING_INTRO = CAP_TESTING + UDPAddress.CAPACITY_INTRODUCER;
    private static final String CAP_TESTING_4 = CAP_TESTING + CAP_IPV4;
    private static final String CAP_TESTING_6 = CAP_TESTING + CAP_IPV6;

    /** How many relays offered to us will we use at a time? */
    public static final int PUBLIC_RELAY_COUNT = 3;

    /** Configure the priority queue with the given split points */
    private static final int PRIORITY_LIMITS[] = new int[] { 100, 200, 300, 400, 500, 1000 };
    /** Configure the priority queue with the given weighting per priority group */
    private static final int PRIORITY_WEIGHT[] = new int[] { 1, 1, 1, 1, 1, 2 };
    private static final int MAX_CONSECUTIVE_FAILED = 3;

    public static final int DEFAULT_COST = 5;
    private static final int SSU_OUTBOUND_COST = 14;
    static final long[] RATES = RateConstants.SHORT_TERM_RATES;
    /** Minimum active peers to maintain IP detection, etc. */
    private static final int MIN_PEERS = 80;
    private static final int MIN_PEERS_IF_HAVE_V6 = 100;
    /** Minimum peers volunteering to be introducers if we need that */
    private static final int MIN_INTRODUCER_POOL = 30;
    static final long INTRODUCER_EXPIRATION_MARGIN = 20*60*1000L;
    private static final long MIN_DOWNTIME_TO_REKEY = 30*24*60*60*1000L;

    private static final int[] BID_VALUES = { 15, 20, 50, 65, 80, 95, 100, 115, TransportBid.TRANSIENT_FAIL };
    private static final int FAST_PREFERRED_BID = 0;
    private static final int SLOW_PREFERRED_BID = 1;
    private static final int FAST_BID = 2;
    private static final int SLOW_BID = 3;
    private static final int SLOWEST_BID = 4;
    private static final int SLOWEST_COST_BID = 5;
    private static final int NEAR_CAPACITY_BID = 6;
    private static final int NEAR_CAPACITY_COST_BID = 7;
    private static final int TRANSIENT_FAIL_BID = 8;
    private final TransportBid[] _cachedBid;

    private static final String THINSP = " / ";

    /**
     *  RI sigtypes supported in 0.9.16, but due to a bug in InboundEstablishState
     *  fixed in 0.9.17, we cannot connect out to routers before that version.
     */
    private static final String MIN_SIGTYPE_VERSION = "0.9.17";

    // SSU2 stable
    private static final String MIN_PEER_TEST_VERSION = "0.9.62";

    // various state bitmaps

    private static final Set<Status> STATUS_IPV4_UNK =   EnumSet.of(Status.UNKNOWN,
                                                                    Status.DISCONNECTED,
                                                                    Status.HOSED,
                                                                    Status.IPV4_UNKNOWN_IPV6_OK,
                                                                    Status.IPV4_UNKNOWN_IPV6_FIREWALLED);

    private static final Set<Status> STATUS_IPV6_UNK =   EnumSet.of(Status.UNKNOWN,
                                                                    Status.DISCONNECTED,
                                                                    Status.HOSED,
                                                                    Status.IPV4_OK_IPV6_UNKNOWN,
                                                                    Status.IPV4_FIREWALLED_IPV6_UNKNOWN,
                                                                    Status.IPV4_SNAT_IPV6_UNKNOWN,
                                                                    Status.IPV4_DISABLED_IPV6_UNKNOWN);

    private static final Set<Status> STATUS_IPV4_FW =    EnumSet.of(Status.DIFFERENT,
                                                                    Status.REJECT_UNSOLICITED,
                                                                    Status.IPV4_FIREWALLED_IPV6_OK,
                                                                    Status.IPV4_SNAT_IPV6_OK,
                                                                    Status.IPV4_SNAT_IPV6_UNKNOWN,
                                                                    Status.IPV4_FIREWALLED_IPV6_UNKNOWN);

    private static final Set<Status> STATUS_IPV6_FW =    EnumSet.of(Status.IPV4_OK_IPV6_FIREWALLED,
                                                                    Status.IPV4_UNKNOWN_IPV6_FIREWALLED,
                                                                    Status.IPV4_DISABLED_IPV6_FIREWALLED);

    private static final Set<Status> STATUS_FW =         EnumSet.of(Status.DIFFERENT,
                                                                    Status.REJECT_UNSOLICITED,
                                                                    Status.IPV4_FIREWALLED_IPV6_OK,
                                                                    Status.IPV4_SNAT_IPV6_OK,
                                                                    Status.IPV4_SNAT_IPV6_UNKNOWN,
                                                                    Status.IPV4_FIREWALLED_IPV6_UNKNOWN,
                                                                    Status.IPV4_OK_IPV6_FIREWALLED,
                                                                    Status.IPV4_UNKNOWN_IPV6_FIREWALLED,
                                                                    Status.IPV4_DISABLED_IPV6_FIREWALLED);

    private static final Set<Status> STATUS_IPV6_FW_2 =  EnumSet.of(Status.IPV4_OK_IPV6_FIREWALLED,
                                                                    Status.IPV4_UNKNOWN_IPV6_FIREWALLED,
                                                                    Status.IPV4_DISABLED_IPV6_FIREWALLED,
                                                                    Status.DIFFERENT,
                                                                    Status.REJECT_UNSOLICITED);

    private static final Set<Status> STATUS_IPV6_OK =    EnumSet.of(Status.OK,
                                                                    Status.IPV4_UNKNOWN_IPV6_OK,
                                                                    Status.IPV4_FIREWALLED_IPV6_OK,
                                                                    Status.IPV4_DISABLED_IPV6_OK,
                                                                    Status.IPV4_SNAT_IPV6_OK);

    private static final Set<Status> STATUS_NO_RETEST =  EnumSet.of(Status.OK,
                                                                    Status.IPV4_OK_IPV6_UNKNOWN,
                                                                    Status.IPV4_OK_IPV6_FIREWALLED,
                                                                    Status.IPV4_DISABLED_IPV6_OK,
                                                                    Status.IPV4_DISABLED_IPV6_UNKNOWN,
                                                                    Status.IPV4_DISABLED_IPV6_FIREWALLED,
                                                                    Status.DISCONNECTED);

    private static final Set<Status> STATUS_OK =         EnumSet.of(Status.OK,
                                                                    Status.IPV4_DISABLED_IPV6_OK);

    private static final Set<Status> STATUS_IPV4_SYMNAT =  EnumSet.of(Status.DIFFERENT,
                                                                    Status.IPV4_SNAT_IPV6_OK,
                                                                    Status.IPV4_SNAT_IPV6_UNKNOWN);

    // States where we cannot be a v4 Charlie
    private static final Set<Status> STATUS_IPV4_NO_TEST = EnumSet.of(Status.DIFFERENT,
                                                                      Status.DISCONNECTED,
                                                                      Status.HOSED,
                                                                      Status.UNKNOWN,
                                                                      Status.IPV4_UNKNOWN_IPV6_OK,
                                                                      Status.IPV4_UNKNOWN_IPV6_FIREWALLED,
                                                                      Status.IPV4_DISABLED_IPV6_OK,
                                                                      Status.IPV4_DISABLED_IPV6_UNKNOWN,
                                                                      Status.IPV4_DISABLED_IPV6_FIREWALLED,
                                                                      Status.IPV4_SNAT_IPV6_OK,
                                                                      Status.IPV4_SNAT_IPV6_UNKNOWN);

    // States where we cannot be a v6 Charlie
    private static final Set<Status> STATUS_IPV6_NO_TEST = EnumSet.of(Status.DIFFERENT,
                                                                      Status.DISCONNECTED,
                                                                      Status.HOSED,
                                                                      Status.UNKNOWN,
                                                                      Status.IPV4_OK_IPV6_UNKNOWN,
                                                                      Status.IPV4_FIREWALLED_IPV6_UNKNOWN,
                                                                      Status.IPV4_DISABLED_IPV6_UNKNOWN,
                                                                      Status.IPV4_SNAT_IPV6_UNKNOWN);

    /**
     *  @param xdh non-null
     */
    public UDPTransport(RouterContext ctx, X25519KeyFactory xdh) {
        super(ctx);
        _networkID = ctx.router().getNetworkID();
        _xdhFactory = xdh;
        _log = ctx.logManager().getLog(UDPTransport.class);
        _peersByIdent = new ConcurrentHashMap<Hash, PeerState>(128);
        _peersByRemoteHost = new ConcurrentHashMap<RemoteHostId, PeerState>(128);
        _peersByConnID = (xdh != null) ? new ConcurrentHashMap<Long, PeerState2>(32) : null;
        // roughly scale based on expected traffic
        int sz = Math.max(16, Math.min(128, getMaxConnections() / 16));
        _recentlyClosedConnIDs = new DestroyedCache(sz);
        _dropList = new ConcurrentHashSet<RemoteHostId>(2);
        _endpoints = new CopyOnWriteArrayList<UDPEndpoint>();

        _cachedBid = new SharedBid[BID_VALUES.length];
        for (int i = 0; i < BID_VALUES.length; i++) {
            _cachedBid[i] = new SharedBid(BID_VALUES[i]);
        }

        _packetBuilder2 = new PacketBuilder2(_context, this);
        _fragments = new OutboundMessageFragments(_context, this);
        _inboundFragments = new InboundMessageFragments(_context, _fragments, this);
        _expireTimeout = EXPIRE_TIMEOUT;
        _expireEvent = new ExpirePeerEvent();
        _testManager = new PeerTestManager(_context, this);
        _testEvent = new PeerTestEvent(_context, this, _testManager);
        _reachabilityStatus = Status.UNKNOWN;
        _reachabilityStatusPending = Status.OK;
        _introManager = new IntroductionManager(_context, this);
        _v4IntroducersSelectedOn = -1;
        _v6IntroducersSelectedOn = -1;
        _lastInboundReceivedOn = -1;
        _mtu = PeerState.LARGE_MTU;
        _mtu_ipv6 = PeerState.MIN_IPV6_MTU;
        setupPort();
        _needsRebuild = true;
        _min_peers = _context.getProperty("i2np.udp.minpeers", MIN_PEERS);
        _min_v6_peers = _context.getProperty("i2np.udp.minv6peers", MIN_PEERS_IF_HAVE_V6);

        _context.statManager().createRateStat("udp.addressTestInsteadOfUpdate", "Number of times we fire off a peer test of ourselves instead of adjusting our own reachable address", "Transport [UDP]", RATES);
        _context.statManager().createRateStat("udp.addressUpdated", "How often we adjust our own reachable IP address", "Transport [UDP]", RATES);
        _context.statManager().createRateStat("udp.alreadyConnected", "Lifetime of a reestablished session", "Transport [UDP]", RATES);
        _context.statManager().createRateStat("udp.droppedPeer", "How long ago dropped peer sent us a message (duration = session lifetime", "Transport [UDP]", RATES);
        _context.statManager().createRateStat("udp.dropPeerConsecutiveFailures", "Consecutive failed sends to a peer before establishing new session (lifetime is inactivity period)", "Transport [UDP]", RATES);
        _context.statManager().createRateStat("udp.dropPeerDroplist", "Number of current peers experiencing dropped packets when new peer is added to list", "Transport [UDP]", RATES);
        _context.statManager().createRateStat("udp.inboundIPv4Conn", "Inbound IPv4 UDP Connection", "Transport [UDP]", RATES);
        _context.statManager().createRateStat("udp.inboundIPv6Conn", "Inbound IPv6 UDP Connection", "Transport [UDP]", RATES);
        _context.statManager().createRateStat("udp.proactiveReestablish", "Time session was idle for when we proactively reestablished it", "Transport [UDP]", RATES);

        _context.simpleTimer2().addPeriodicEvent(new PingIntroducers(), MIN_EXPIRE_TIMEOUT * 3 / 4);

        // SSU2 key and IV generation if required
        _defaultMTU = PeerState2.DEFAULT_MTU;
        _mtu_ssu2 = PeerState2.MIN_MTU;
        _mtu_ssu2_ipv6 = PeerState2.MIN_MTU;

        // If any ipv4 address is lower than 1280 MTU, disable
        Set<String> ipset = Addresses.getAddresses(true, false, false);
        for (String ips : ipset) {
            try {
                InetAddress addr = InetAddress.getByName(ips);
                int mtu = MTU.getMTU(addr, true);
                if (mtu > 0 && mtu < PeerState2.MIN_MTU) {
                    _log.logAlways(Log.WARN, "Disabling SSU2 on address " + ips + " -> MTU is " + mtu + ", minimum required is " + PeerState2.MIN_MTU);
                }
            } catch (UnknownHostException e) {}
        }

        byte[] ikey = null;
        String b64Ikey = null;
        byte[] priv = null;
        boolean shouldSave = false;
        String s = null;
        // Try to determine if we've been down for 30 days or more
        long minDowntime = _context.router().isHidden() ? MIN_DOWNTIME_TO_REKEY_HIDDEN : MIN_DOWNTIME_TO_REKEY;
        boolean shouldRekey = !allowLocal() && _context.getEstimatedDowntime() >= minDowntime;
        if (!shouldRekey) {
            s = ctx.getProperty(PROP_SSU2_SP);
            if (s != null) {
                priv = Base64.decode(s);
            }
        }
        if (priv == null || priv.length != SSU2Util.KEY_LEN) {
            KeyPair keys = xdh.getKeys();
            _ssu2StaticPrivKey = keys.getPrivate().getData();
            _ssu2StaticPubKey = keys.getPublic().getData();
            shouldSave = true;
        } else {
            _ssu2StaticPrivKey = priv;
            _ssu2StaticPubKey = (new PrivateKey(EncType.ECIES_X25519, priv)).toPublic().getData();
        }
        if (!shouldSave) {
            s = ctx.getProperty(PROP_SSU2_IKEY);
            if (s != null) {
                ikey = Base64.decode(s);
                b64Ikey = s;
            }
        }
        if (ikey == null || ikey.length != SSU2Util.INTRO_KEY_LEN) {
            ikey = new byte[SSU2Util.INTRO_KEY_LEN];
            do {
                ctx.random().nextBytes(ikey);
            } while (DataHelper.eq(ikey, 0, SSU2Util.ZEROKEY, 0, SSU2Util.INTRO_KEY_LEN));
            shouldSave = true;
        }
        if (shouldSave) {
            Map<String, String> changes = new HashMap<String, String>(2);
            String b64Priv = Base64.encode(_ssu2StaticPrivKey);
            b64Ikey = Base64.encode(ikey);
            changes.put(PROP_SSU2_SP, b64Priv);
            changes.put(PROP_SSU2_IKEY, b64Ikey);
            ctx.router().saveConfig(changes, null);
        }

        _ssu2StaticIntroKey = ikey;
        _ssu2B64StaticIntroKey = b64Ikey;
        _ssu2B64StaticPubKey = (_ssu2StaticPubKey != null) ? Base64.encode(_ssu2StaticPubKey) : null;
    }

    /**
     * @return the instance of OutboundMessageFragments
     * @since 0.9.48
     */
    OutboundMessageFragments getOMF() {
        return _fragments;
    }


    /**
     *  Pick a port if not previously configured, so that TransportManager may
     *  call getRequestedPort() before we've started to get a best-guess of what our
     *  port is going to be, and pass that to NTCP
     *
     *  @since IPv6
     */
    private void setupPort() {
        int port = getRequestedPort();
        if (port <= 0) {
            port = TransportUtil.selectRandomPort(_context, STYLE);
            Map<String, String> changes = new HashMap<String, String>(2);
            String sport = Integer.toString(port);
            changes.put(PROP_INTERNAL_PORT, sport);
            changes.put(PROP_EXTERNAL_PORT, sport);
            _context.router().saveConfig(changes, null);
            _log.logAlways(Log.INFO, "UDP selected random port " + port);
        }
    }

    /**
     * Starts up the SSU transport listener, configuring and binding UDP endpoints,
     * initializing components, and setting reachability status.
     * This method is synchronized to prevent concurrent startup attempts.
     */
    private synchronized void startup() {
        shutdownComponents();
        Set<InetAddress> bindToAddrs = resolveBindAddresses();
        int port = determinePort(bindToAddrs);
        setupEndpoints(bindToAddrs, port);
        initializeManagers();

        int newPort = startEndpointsAndGetPort();
        if (_endpoints.isEmpty()) {
            _log.log(Log.CRIT, "[SSU] Unable to open port");
            setReachabilityStatus(Status.HOSED);
            return;
        }

        updatePortsInConfig(port, newPort);
        startupComponentServices();

        configureExternalAddressesAndStatus(newPort, bindToAddrs);

        adjustReachabilityForFirewalled();

        _establisher.startup();
        _handler.startup();
        rebuildExternalAddress(false, getIPv6Config() == IPV6_ONLY);
    }

    /**
     * Shutdown components and clear endpoints safely.
     */
    private void shutdownComponents() {
        _fragments.shutdown();
        if (_pusher != null)
            _pusher.shutdown();
        if (_handler != null)
            _handler.shutdown();

        Iterator<UDPEndpoint> iterator = _endpoints.iterator();
        while (iterator.hasNext()) {
            UDPEndpoint endpoint = iterator.next();
            endpoint.shutdown();
            iterator.remove();
        }

        if (_establisher != null)
            _establisher.shutdown();

        _inboundFragments.shutdown();
        _introManager.reset();
        UDPPacket.clearCache();

        if (_log.shouldInfo())
            _log.info("Starting SSU transport listener...");
    }

    /**
     * Resolves bind addresses from configuration properties,
     * expanding hostnames if necessary.
     *
     * @return set of InetAddress to bind to, empty if none specified
     */
    private Set<InetAddress> resolveBindAddresses() {
        String bindTo = _context.getProperty(PROP_BIND_INTERFACE);
        if (bindTo == null) {
            bindTo = expandExternalHostToBind();
        }
        Set<InetAddress> bindToAddrs = new HashSet<>(4);
        if (bindTo != null) {
            String[] bta = DataHelper.split(bindTo, "[,; \r\n\t]");
            for (String bt : bta) {
                if (bt.length() <= 0) continue;
                try {
                    bindToAddrs.add(InetAddress.getByName(bt));
                } catch (UnknownHostException uhe) {
                    _log.error("Invalid SSU bind interface specified [" + bt + "]", uhe);
                }
            }
        }
        return bindToAddrs;
    }

    /**
     * Expands the PROP_EXTERNAL_HOST configuration into a comma-separated
     * string of IP addresses that are local and allowed.
     *
     * @return expanded bind string or null if no resolved addresses
     */
    private String expandExternalHostToBind() {
        String fixedHost = _context.getProperty(PROP_EXTERNAL_HOST);
        if (fixedHost == null || fixedHost.isEmpty())
            return null;

        TransportUtil.IPv6Config cfg = getIPv6Config();
        Set<String> myAddrs = (cfg == IPV6_DISABLED) ? Addresses.getAddresses(false, false) : Addresses.getAddresses(false, true);

        StringBuilder buf = new StringBuilder();
        String[] hosts = DataHelper.split(fixedHost, "[,; \r\n\t]");
        for (String bt : hosts) {
            if (bt.isEmpty())
                continue;
            try {
                InetAddress[] all = InetAddress.getAllByName(bt);
                for (InetAddress ia : all) {
                    if (cfg == IPV6_ONLY && (ia instanceof Inet4Address)) {
                        if (_log.shouldWarn())
                            _log.warn("Configured for IPv6 only, not binding to configured IPv4 host " + bt);
                        continue;
                    }
                    String testAddr = ia.getHostAddress();
                    if (myAddrs.contains(testAddr)) {
                        if (buf.length() > 0)
                            buf.append(',');
                        buf.append(testAddr);
                    } else {
                        if (_log.shouldWarn())
                            _log.warn("Not a local address, not binding to configured IP " + testAddr);
                    }
                }
            } catch (UnknownHostException uhe) {
                if (_log.shouldWarn())
                    _log.warn("Not binding to configured host " + bt + " - " + uhe);
            }
        }

        String expanded = (buf.length() > 0) ? buf.toString() : null;
        if (_log.shouldWarn() && expanded != null && !fixedHost.equals(expanded))
            _log.warn("Expanded external host config \"" + fixedHost + "\" to \"" + expanded + '"');
        return expanded;
    }

    /**
     * Determines the port to bind to based on internal, bind, or external port properties.
     *
     * @param bindToAddrs set of addresses to bind; used for logging
     * @return the port to bind to
     */
    private int determinePort(Set<InetAddress> bindToAddrs) {
        int oldIPort = _context.getProperty(PROP_INTERNAL_PORT, -1);
        int oldBindPort = getListenPort(false);
        int oldEPort = _context.getProperty(PROP_EXTERNAL_PORT, -1);
        int port;
        if (oldIPort > 0)
            port = oldIPort;
        else if (oldBindPort > 0)
            port = oldBindPort;
        else
            port = oldEPort;

        if (!bindToAddrs.isEmpty() && _log.shouldWarn())
            _log.warn("Binding only to " + bindToAddrs);
        if (_log.shouldInfo())
            _log.info("Binding to port: " + port);

        return port;
    }

    /**
     * Sets up UDP endpoints based on bind addresses and port.
     *
     * @param bindToAddrs addresses to bind, empty means wildcard
     * @param port port to bind endpoints on
     */
    private void setupEndpoints(Set<InetAddress> bindToAddrs, int port) {
        if (_endpoints.isEmpty()) {
            if (bindToAddrs.isEmpty()) {
                UDPEndpoint endpoint = new UDPEndpoint(_context, this, port, null);
                _endpoints.add(endpoint);
                setMTU(null);
            } else {
                for (InetAddress addr : bindToAddrs) {
                    UDPEndpoint endpoint = new UDPEndpoint(_context, this, port, addr);
                    _endpoints.add(endpoint);
                    setMTU(addr);
                }
            }
        } else {
            for (UDPEndpoint endpoint : _endpoints) {
                if (endpoint.isIPv4()) {
                    endpoint.setListenPort(port);
                    break;
                }
            }
        }
    }

    /**
     * Initializes establishment and handler managers if missing.
     */
    private void initializeManagers() {
        if (_establisher == null)
            _establisher = new EstablishmentManager(_context, this);
        if (_handler == null)
            _handler = new PacketHandler(_context, this, _establisher, _inboundFragments, _testManager, _introManager);
    }

    /**
     * Starts all endpoints, returns the listen port of the first IPv4 endpoint started or -1 if none.
     *
     * @return new port that endpoints are listening on or -1
     */
    private int startEndpointsAndGetPort() {
        int newPort = -1;
        Iterator<UDPEndpoint> iterator = _endpoints.iterator();
        while (iterator.hasNext()) {
            UDPEndpoint endpoint = iterator.next();
            try {
                endpoint.startup();
                if (newPort < 0 && endpoint.isIPv4()) {
                    newPort = endpoint.getListenPort();
                }
                if (_log.shouldWarn())
                    _log.warn("Started " + endpoint);
            } catch (SocketException se) {
                iterator.remove();
                _log.error("Failed to start " + endpoint, se);
            }
        }
        return newPort;
    }

    /**
     * Updates internal and external port config after successful endpoint startup.
     *
     * @param port previously requested port
     * @param newPort actual port endpoints listen on
     */
    private void updatePortsInConfig(int port, int newPort) {
        int oldIPort = _context.getProperty(PROP_INTERNAL_PORT, -1);
        int oldEPort = _context.getProperty(PROP_EXTERNAL_PORT, -1);
        if (newPort > 0 && (newPort != port || newPort != oldIPort)) {
            Map<String, String> changes = new HashMap<>();
            String sport = Integer.toString(newPort);
            changes.put(PROP_INTERNAL_PORT, sport);
            if (oldEPort <= 0)
                changes.put(PROP_EXTERNAL_PORT, sport);
            _context.router().saveConfig(changes, null);
        }
    }

    /**
     * Starts fragment and packet components.
     */
    private void startupComponentServices() {
        _fragments.startup();
        _inboundFragments.startup();
        _pusher = new PacketPusher(_context, _fragments, _endpoints);
        _pusher.startup();
        _expireEvent.setIsAlive(true);
        _reachabilityStatus = Status.UNKNOWN;
        _testEvent.setIsAlive(true);
    }

    /**
     * Configures external addresses and sets reachability status based on current state.
     *
     * @param newPort port that endpoints are listening on
     * @param bindToAddrs bind addresses provided
     */
    private void configureExternalAddressesAndStatus(int newPort, Set<InetAddress> bindToAddrs) {
        boolean v6only = getIPv6Config() == IPV6_ONLY;
        boolean save = _context.router().isHidden();
        Map<String, String> changes = save ? new HashMap<>(4) : null;

        if (newPort > 0 && bindToAddrs.isEmpty()) {
            boolean hasv6 = false;
            for (InetAddress ia : getSavedLocalAddresses()) {
                byte[] addr = ia.getAddress();
                String prop = addr.length == 4 ? PROP_IP : PROP_IPV6;
                String oldIP = save ? _context.getProperty(prop) : null;
                String newIP = Addresses.toString(addr);

                if (addr.length == 16) {
                    if (hasv6) continue;
                    hasv6 = true;

                    OrderedProperties localOpts = new OrderedProperties();
                    localOpts.setProperty(UDPAddress.PROP_PORT, String.valueOf(newPort));
                    localOpts.setProperty(UDPAddress.PROP_HOST, newIP);
                    RouterAddress local = new RouterAddress(getPublishStyle(), localOpts, DEFAULT_COST);
                    replaceCurrentExternalAddress(local, true);

                    if (isIPv6Firewalled()) {
                        setReachabilityStatus(Status.IPV4_UNKNOWN_IPV6_FIREWALLED, true);
                    } else if (_context.getBooleanProperty(PROP_IPV6_FIREWALLED)) {
                        setReachabilityStatus(Status.IPV4_UNKNOWN_IPV6_FIREWALLED, true);
                        _testEvent.forceRunSoon(true, v6only ? 10_000 : 60_000);
                    } else {
                        _lastInboundIPv6 = _context.clock().now();
                        setReachabilityStatus(Status.IPV4_UNKNOWN_IPV6_OK, true);
                        rebuildExternalAddress(newIP, newPort, false);
                        _testEvent.forceRunSoon(true, v6only ? 10_000 : 60_000);
                    }
                } else {
                    OrderedProperties localOpts = new OrderedProperties();
                    localOpts.setProperty(UDPAddress.PROP_PORT, String.valueOf(newPort));
                    localOpts.setProperty(UDPAddress.PROP_HOST, newIP);
                    RouterAddress local = new RouterAddress(getPublishStyle(), localOpts, DEFAULT_COST);
                    replaceCurrentExternalAddress(local, false);

                    if (isIPv4Firewalled()) {
                        setReachabilityStatus(Status.IPV4_FIREWALLED_IPV6_UNKNOWN);
                    } else {
                        setReachabilityStatus(Status.IPV4_OK_IPV6_UNKNOWN);
                        rebuildExternalAddress(newIP, newPort, false);
                        if (!v6only)
                            _testEvent.forceRunSoon(false, 10_000);
                    }
                }

                if (save && !newIP.equals(oldIP)) {
                    changes.put(prop, newIP);
                    if (addr.length == 4)
                        changes.put(PROP_IP_CHANGE, Long.toString(_context.clock().now()));
                    if (oldIP != null)
                        _context.router().eventLog().addEvent(EventLog.CHANGE_IP, newIP);
                }
            }
            if (save && changes != null && !changes.isEmpty())
                _context.router().saveConfig(changes, null);
        } else if (newPort > 0) { // bindToAddrs not empty
            for (InetAddress ia : bindToAddrs) {
                if (ia.getAddress().length == 16) {
                    _lastInboundIPv6 = _context.clock().now();
                    if (!isIPv6Firewalled())
                        setReachabilityStatus(Status.IPV4_UNKNOWN_IPV6_OK, true);
                } else {
                    if (!isIPv4Firewalled())
                        setReachabilityStatus(Status.IPV4_OK_IPV6_UNKNOWN);
                }
                rebuildExternalAddress(ia.getHostAddress(), newPort, false);
            }
            // TODO: IPv4/IPv6 binding edge cases
        } else { // newPort <= 0
            if ((!v6only && !isIPv4Firewalled()) || (v6only && !isIPv6Firewalled()))
                _testEvent.forceRunSoon(v6only, 10_000);
        }
    }

    /**
     * Adjusts reachability status when IPv4 is firewalled.
     */
    private void adjustReachabilityForFirewalled() {
        if (isIPv4Firewalled()) {
            if (_lastInboundIPv6 > 0)
                setReachabilityStatus(Status.IPV4_FIREWALLED_IPV6_UNKNOWN);
            else
                setReachabilityStatus(Status.REJECT_UNSOLICITED);
        }
    }

    public synchronized void shutdown() {
        if (_haveIPv6Address) {
            boolean fwOld = _context.getBooleanProperty(PROP_IPV6_FIREWALLED);
            boolean fwNew = STATUS_IPV6_FW_2.contains(_reachabilityStatus);
            if (fwOld != fwNew)
                _context.router().saveConfig(PROP_IPV6_FIREWALLED, Boolean.toString(fwNew));
        }
        destroyAll();
        for (UDPEndpoint endpoint : _endpoints) {
            endpoint.shutdown();
            // should we remove?
            _endpoints.remove(endpoint);
        }
        if (_handler != null)
            _handler.shutdown();
        if (_pusher != null)
            _pusher.shutdown();
        _fragments.shutdown();
        if (_establisher != null)
            _establisher.shutdown();
        _inboundFragments.shutdown();
        _expireEvent.setIsAlive(false);
        _testEvent.setIsAlive(false);
        _peersByRemoteHost.clear();
        _peersByIdent.clear();
        if (_peersByConnID != null)
            _peersByConnID.clear();
        if (_recentlyClosedConnIDs != null) {
            synchronized(_addDropLock) {
                for (PeerStateDestroyed psd : _recentlyClosedConnIDs.values()) {
                    psd.kill();
                }
                _recentlyClosedConnIDs.clear();
            }
        }
        _dropList.clear();
        _introManager.reset();
        UDPPacket.clearCache();
        UDPAddress.clearCache();
        _lastInboundIPv6 = 0;
    }

    /**
     *  The endpoint has failed. Remove it.
     *
     *  @since 0.9.16
     */
    public void fail(UDPEndpoint endpoint) {
        if (_endpoints.remove(endpoint)) {
            _log.log(Log.CRIT, "UDP port failure: " + endpoint);
            if (_endpoints.isEmpty()) {
                _log.log(Log.CRIT, "No more UDP sockets open");
                setReachabilityStatus(Status.HOSED);
                // TODO restart?
            }
            rebuildExternalAddress(endpoint.isIPv6());
        }
    }

    /** @since IPv6 */
    private boolean isAlive() {
        return _inboundFragments.isAlive();
    }

    /**
     * Introduction key that people should use to contact us,
     * or null if SSU1 disabled.
     */
    SessionKey getIntroKey() { return _introKey; }

    /**
     * The static Intro key
     *
     * @return null if not configured for SSU2
     * @since 0.9.54
     */
    byte[] getSSU2StaticIntroKey() {
        return _ssu2StaticIntroKey;
    }

    /**
     * The static pub key
     *
     * @return null if not configured for SSU2
     * @since 0.9.54
     */
    byte[] getSSU2StaticPubKey() {
        return _ssu2StaticPubKey;
    }

    /**
     * The static priv key
     *
     * @return null if not configured for SSU2
     * @since 0.9.54
     */
    byte[] getSSU2StaticPrivKey() {
        return _ssu2StaticPrivKey;
    }

    /**
     * Get the valid SSU version of Bob's SSU address
     * for our outbound connections as Alice.
     *
     * @return the valid version 1 or 2, or 0 if unusable
     * @since 0.9.54
     */
    int getSSUVersion(RouterAddress addr) {
        int rv;
        String style = addr.getTransportStyle();
        if (style.equals(STYLE)) {
            rv = 1;
        } else if (style.equals(STYLE2)) {
            rv = SSU2_INT_VERSION;
        } else {
            return 0;
        }
        // check version == "2" || version starts with "2,"
        // and static key and intro key
        // and, until we support relay, host and port.
        String v = addr.getOption("v");
        if (v == null ||
            addr.getOption("i") == null ||
            addr.getOption("s") == null ||
            (!v.equals(SSU2_VERSION) && !v.startsWith(SSU2_VERSION_ALT))) {
            // his address is SSU1 or is outbound SSU2 only
            return 0;
        }
        // his address is SSU2
        // do not validate the s/i b64, we will just catch it later
        // todo check mtu
        return SSU2_INT_VERSION;
    }

    /**
     * Add the required options to the properties for a SSU2 address.
     * Host/port must already be set in props if they are going to be.
     * Must only be called if SSU2 is enabled.
     *
     * @since 0.9.54
     */
    private void addSSU2Options(Properties props) {
        // Unlike in NTCP2, we need the intro key whether firewalled or not
        props.setProperty("i", _ssu2B64StaticIntroKey);
        props.setProperty("s", _ssu2B64StaticPubKey);
        props.setProperty("v", SSU2_VERSION);
    }

    /**
     *  Published or requested port
     */
    int getExternalPort(boolean ipv6) {
        RouterAddress addr = getCurrentAddress(ipv6);
        if (addr != null) {
            int rv = addr.getPort();
            if (rv > 0)
                return rv;
        }
        return getRequestedPort(ipv6);
    }

    /**
     *  Published IP, IPv4 only
     *  @return IP or null
     *  @since 0.9.2
     */
    byte[] getExternalIP() {
        RouterAddress addr = getCurrentAddress(false);
        if (addr != null)
            return addr.getIP();
        return null;
    }

    /**
     *  For PeerTestManager
     *  @since 0.9.30
     */
    boolean hasIPv6Address() {
        return _haveIPv6Address;
    }

    /**
     *  Is this IP too close to ours to trust it for
     *  things like relaying?
     *  @param ip IPv4 or IPv6
     *  @since IPv6
     */
    boolean isTooClose(byte[] ip) {
        if (allowLocal())
            return false;
        byte[] myip = ip.length == 16 ? _lastOurIPv6 : _lastOurIPv4;
        if (myip == null)
            return false;
        if (ip.length == 4) {
            if (DataHelper.eq(ip, 0, myip, 0, 2))
                return true;
        } else if (ip.length == 16) {
            if (DataHelper.eq(ip, 0, myip, 0, 4))
                return true;
        }
        return false;
    }

    /**
     *  The current port of the first matching endpoint.
     *  To be enhanced to handle multiple endpoints of the same type.
     *  @return port or -1
     *  @since IPv6
     */
    private int getListenPort(boolean ipv6) {
        for (UDPEndpoint endpoint : _endpoints) {
            if (((!ipv6) && endpoint.isIPv4()) ||
                (ipv6 && endpoint.isIPv6()))
                return endpoint.getListenPort();
       }
       return -1;
    }

    /**
     *  The current or configured internal IPv4 port.
     *  UDPEndpoint should always be instantiated (and a random port picked if not configured)
     *  before this is called, so the returned value should be &gt; 0
     *  unless the endpoint failed to bind.
     */
    @Override
    public int getRequestedPort() {
        return getRequestedPort(false);
    }

    /**
     *  The current or configured internal port.
     *  UDPEndpoint should always be instantiated (and a random port picked if not configured)
     *  before this is called, so the returned value should be &gt; 0
     *  unless the endpoint failed to bind.
     */
    private int getRequestedPort(boolean ipv6) {
        int rv = getListenPort(ipv6);
        if (rv > 0)
            return rv;
        // fallbacks
        rv = _context.getProperty(PROP_INTERNAL_PORT, -1);
        if (rv > 0)
            return rv;
        return _context.getProperty(PROP_EXTERNAL_PORT, -1);
    }

    /**
     *  Set the MTU for the socket interface at addr.
     *  @param addr null ok
     *  @return the mtu
     *  @since 0.9.2
     */
    private int setMTU(InetAddress addr) {
        // TODO remove config
        String p = _context.getProperty(PROP_DEFAULT_MTU);
        if (p != null) {
            try {
                int pmtu = Integer.parseInt(p);
                _mtu = MTU.rectify(false, pmtu);
                _mtu_ipv6 = MTU.rectify(true, pmtu);
                return _mtu;
            } catch (NumberFormatException nfe) {}
        }
        int mtu = MTU.getMTU(addr, false);
        if (addr != null && addr.getAddress().length == 16) {
            if (mtu <= 0)
                mtu = PeerState.MIN_IPV6_MTU;
            _mtu_ipv6 = mtu;
        } else {
            if (mtu <= 0)
                mtu = PeerState.LARGE_MTU;
            _mtu = mtu;
        }
        if (addr != null) {
            int mtussu2 = MTU.getMTU(addr, true);
            if (mtussu2 > 0 && mtussu2 < PeerState2.MIN_MTU) {
                _log.logAlways(Log.WARN, "Low MTU " + mtussu2 + " for interface " + addr + ", consider disabling SSU2");
                mtussu2 = PeerState2.MIN_MTU;
            }
            if (addr.getAddress().length == 16) {
                _mtu_ssu2_ipv6 = mtussu2;
            } else {
                _mtu_ssu2 = mtussu2;
            }
        }
        return mtu;
    }

    /**
     * The MTU for the socket interface.
     * To be used as the "large" MTU.
     * @return limited to range PeerState.MIN_MTU to PeerState.LARGE_MTU.
     * @since 0.9.2, public since 0.9.31
     */
    public int getMTU(boolean ipv6) {
        // TODO multiple interfaces of each type
        return getSSU2MTU(ipv6);
    }

    /**
     * The SSU2 MTU for the socket interface.
     * To be used as the "large" MTU.
     *
     * @return limited to range PeerState2.MIN_MTU to PeerState2.LARGE_MTU, or 0 if unavailable
     * @since 0.9.55
     */
    public int getSSU2MTU(boolean ipv6) {
        return ipv6 ? _mtu_ssu2_ipv6 : _mtu_ssu2;
    }

    /**
     * If we have received an inbound connection in the last 2 minutes, don't allow
     * our IP to change.
     */
    private static final int ALLOW_IP_CHANGE_INTERVAL = 2*60*1000;

    void inboundConnectionReceived(boolean isIPv6) {
        if (isIPv6) {
            _lastInboundIPv6 = _context.clock().now();
            _context.statManager().addRateData("udp.inboundIPv6Conn", 1);
            // former workaround for lack of IPv6 peer testing
            //if (_currentOurV6Address != null)
            //    setReachabilityStatus(Status.IPV4_UNKNOWN_IPV6_OK, true);
        } else {
            // Introduced connections are still inbound, this is not evidence
            // that we are not firewalled.
            // use OS clock since its an ordering thing, not a time thing
            _lastInboundReceivedOn = System.currentTimeMillis();
            _context.statManager().addRateData("udp.inboundIPv4Conn", 1);
        }
    }

    // temp prevent multiples
    private boolean gotIPv4Addr = false;
    private boolean gotIPv6Addr = false;

    /**
     * From config, UPnP, local i/f, ...
     * Not for info received from peers - see externalAddressReceived(Hash, ip, port)
     *
     * @param source as defined in Transport.SOURCE_xxx
     * @param ip publicly routable IPv4 or IPv6, null ok
     * @param port 0 if unknown
     */
    @Override
    public void externalAddressReceived(Transport.AddressSource source, byte[] ip, int port) {
        if (_log.shouldWarn())
            _log.warn("Received address: " + Addresses.toString(ip, port) + " from: " + source);
        if (ip == null)
            return;
        // this is essentially isValid(ip), but we can't use that because
        // _haveIPv6Address is not set yet
        if (!(isPubliclyRoutable(ip) || allowLocal())) {
            if (_log.shouldWarn())
                _log.warn("Invalid address: " + Addresses.toString(ip, port) + " from: " + source);
            return;
        }
        if (source == SOURCE_INTERFACE && ip.length == 16) {
            // NOW we can set it, it's a valid v6 address
            // (we don't want to set this for Teredo, 6to4, ...)
            _haveIPv6Address = true;
        }
        if (source == SOURCE_UPNP)
            _haveUPnP = true;
        if (explicitAddressSpecified())
            return;
        String sources = _context.getProperty(PROP_SOURCES, DEFAULT_SOURCES);
        if (!sources.contains(source.toConfigString()))
            return;
        if (!isAlive()) {
            if (source == SOURCE_INTERFACE || source == SOURCE_UPNP) {
                try {
                    InetAddress ia = InetAddress.getByAddress(ip);
                    saveLocalAddress(ia);
                    setMTU(ia);
                } catch (UnknownHostException uhe) {}
            }
            return;
        }
        if (source == SOURCE_INTERFACE) {
            // temp prevent multiples
            if (ip.length == 4) {
                if (gotIPv4Addr)
                    return;
                else
                    gotIPv4Addr = true;
            } else if (ip.length == 16) {
                if (gotIPv6Addr)
                    return;
                else
                    gotIPv6Addr = true;
            }
        }
        if ((source == SOURCE_INTERFACE || source == SOURCE_UPNP) &&
            _context.router().isHidden()) {
            // Update some config variables and event logs,
            // because changeAddress() below won't do that for hidden mode
            // because rebuildExternalAddress() always returns null.
            String prop = ip.length == 4 ? PROP_IP : PROP_IPV6;
            String oldIP = _context.getProperty(prop);
            String newIP = Addresses.toString(ip);
            if (!newIP.equals(oldIP)) {
                Map<String, String> changes = new HashMap<String, String>(2);
                changes.put(prop, newIP);
                if (ip.length == 4)
                    changes.put(PROP_IP_CHANGE, Long.toString(_context.clock().now()));
                _context.router().saveConfig(changes, null);
                if (oldIP != null)
                    _context.router().eventLog().addEvent(EventLog.CHANGE_IP, newIP);
            }
        }
        boolean changed = changeAddress(ip, port);
        // Assume if we have an interface with a public IP that we aren't firewalled.
        // If this is wrong, the peer test will figure it out and change the status.
        if (changed && source == SOURCE_INTERFACE) {
            if (ip.length == 4) {
                if (!isIPv4Firewalled())
                    setReachabilityStatus(Status.IPV4_OK_IPV6_UNKNOWN);
            } else if (ip.length == 16) {
                // TODO if we start periodically scanning our interfaces (we don't now),
                // this will set non-firewalled every time our IPv6 address changes
                if (!isIPv6Firewalled())
                    setReachabilityStatus(Status.IPV4_UNKNOWN_IPV6_OK, true);
            }
        }
    }

    /**
     *  Callback from UPnP.
     *  If we we have an IP address and UPnP claims success, believe it.
     *  If this is wrong, the peer test will figure it out and change the status.
     *  Don't do anything if UPnP claims failure.
     */
    @Override
    public void forwardPortStatus(byte[] ip, int port, int externalPort, boolean success, String reason) {
        if (success)
            _haveUPnP = true;
        if (_log.shouldWarn()) {
            if (success)
                _log.warn("UPnP has opened the SSU port: " + port + " via " + Addresses.toString(ip, externalPort));
            else
                _log.warn("UPnP has failed to open the SSU port: " + Addresses.toString(ip, externalPort) + " reason: " + reason);
        }
        if (success && ip != null) {
            if (ip.length == 4) {
                if (getCurrentExternalAddress(false) != null && !isIPv4Firewalled())
                    setReachabilityStatus(Status.IPV4_OK_IPV6_UNKNOWN);
            } else if (ip.length == 16) {
                boolean fwOld = _context.getBooleanProperty(PROP_IPV6_FIREWALLED);
                if (!fwOld)
                    _context.router().saveConfig(PROP_IPV6_FIREWALLED, "false");
                if (!isIPv6Firewalled())
                    setReachabilityStatus(Status.IPV4_UNKNOWN_IPV6_OK, true);
            }
        }
    }

    /**
     * Someone we tried to contact gave us what they think our IP address is.
     * Right now, we just blindly trust them, changing our IP and port on a
     * whim.  this is not good ;)
     *
     * Slight enhancement - require two different peers in a row to agree
     *
     * Todo:
     *   - Much better tracking of troublemakers
     *   - Disable if we have good local address or UPnP
     *   - This gets harder if and when we publish multiple addresses, or IPv6
     *
     * @param from Hash of inbound destination
     * @param ourIP publicly routable IPv4 or IPv6 only, non-null
     * @param ourPort &gt;= 1024
     */
    void externalAddressReceived(Hash from, byte ourIP[], int ourPort) {
        boolean isValid = isValid(ourIP) &&
                          TransportUtil.isValidPort(ourPort);
        boolean explicitSpecified = explicitAddressSpecified();
        boolean inboundRecent;
        boolean isIPv6 = ourIP.length == 16;
        if (!isIPv6)
            inboundRecent = _lastInboundReceivedOn + ALLOW_IP_CHANGE_INTERVAL > System.currentTimeMillis();
        else
            inboundRecent = _lastInboundIPv6 + ALLOW_IP_CHANGE_INTERVAL > _context.clock().now();
        if (_log.shouldInfo())
            _log.info("External address received: " + Addresses.toString(ourIP, ourPort) + " from ["
                      + from.toBase64().substring(0,6) + "]\n* Address: isValid? " + isValid + ", explicitSpecified? " + explicitSpecified
                      + ", receivedInboundRecent? " + inboundRecent + " Status: " + _reachabilityStatus);

        if (explicitSpecified)
            return;
        String sources = _context.getProperty(PROP_SOURCES, DEFAULT_SOURCES);
        if (!sources.contains("ssu"))
            return;

        if (!isValid) {
            // ignore them
            // ticket #2467 natted to an invalid port
            // if the port is the only issue, don't call markUnreachable()
            if (ourPort < 1024 || ourPort > 65535 || !isValid(ourIP)) {
                if (_log.shouldWarn())
                _log.warn("[" + from.toBase64().substring(0,6) + "] told us we have an invalid IP - "
                           + Addresses.toString(ourIP, ourPort) + ". Let's throw tomatoes at them!");
            markUnreachable(from);
            } else {
                _log.logAlways(Log.WARN, "[" + from.toBase64().substring(0,6) + "] told us we have an invalid port "
                                         + ourPort
                                         + "\n* Check NAT/firewall configuration, the IANA recommended dynamic outside port range is 49152-65535");
            }
            _context.banlist().banlistRouter(from, "Reported our IP address or port as invalid", STYLE);
            return;
        }

        RouterAddress addr = getCurrentExternalAddress(isIPv6);
        if (inboundRecent && addr != null && addr.getPort() > 0 && addr.getHost() != null) {
            // use OS clock since its an ordering thing, not a time thing
            // Note that this fails us if we switch from one IP to a second, then back to the first,
            // as some routers still have the first IP and will successfully connect,
            // leaving us thinking the second IP is still good.
            if (_log.shouldDebug())
                _log.debug("Ignoring IP address suggestion as we have received an inbound connection recently");
            return;
        }

        // Still could be the same IP/port, we don't check until changeAddress() below.
        boolean changeIt = false;
        Hash lastFrom = null;
        synchronized(this) {
            if (!isIPv6) {
                if (from.equals(_lastFromv4) || !eq(_lastOurIPv4, _lastOurPortv4, ourIP, ourPort)) {
                    if (_log.shouldInfo())
                        _log.info("[" + from.toBase64().substring(0,6) + "] told us we have a new IP address or port: "
                                  + Addresses.toString(ourIP, ourPort) + "; awaiting confirmation from another peer");
                } else {
                    changeIt = true;
                        lastFrom = _lastFromv4;
                }
                _lastFromv4 = from;
                _lastOurIPv4 = ourIP;
                _lastOurPortv4 = ourPort;
                } else {
                if (from.equals(_lastFromv6) || !eq(_lastOurIPv6, _lastOurPortv6, ourIP, ourPort)) {
                    if (_log.shouldInfo())
                        _log.info("[" + from.toBase64().substring(0,6) + "] told us we have a new IP address or port: "
                                  + Addresses.toString(ourIP, ourPort) + "; awaiting confirmation from another peer");
                } else {
                    changeIt = true;
                        lastFrom = _lastFromv6;
                }
                _lastFromv6 = from;
                _lastOurIPv6 = ourIP;
                _lastOurPortv6 = ourPort;
            }
        }
        if (changeIt) {
            if (_log.shouldInfo())
                _log.info("[" + from.toBase64().substring(0,6) + "] and [" + lastFrom.toBase64().substring(0,6) + "] agree we have a new IP address: "
                          + Addresses.toString(ourIP, ourPort) + " -> Updating...");
            // Never change port for IPv6 or if we have UPnP
            if (_haveUPnP || ourIP.length == 16)
                ourPort = 0;
            changeAddress(ourIP, ourPort);
        }
    }

    /**
     * Updates the external IP address and/or port based on feedback from a peer.
     * This is used when a peer reports our external address, or when we detect a change.
     *
     * @param ip the new IP address (IPv4 or IPv6)
     * @param port the new port number, or 0 to keep the current port
     * @return true if the address was successfully updated
     */
    private boolean changeAddress(byte ourIP[], int ourPort) {
        boolean updated = false;
        boolean fireTest = false;

        boolean isIPv6 = ourIP.length == 16;
        // this defaults to true when we are firewalled or unknown and false otherwise.
        boolean fixedPort = getIsPortFixed(isIPv6);

        synchronized (_rebuildLock) {
            RouterAddress current = getCurrentExternalAddress(isIPv6);
            byte[] externalListenHost = current != null ? current.getIP() : null;
            int externalListenPort = current != null ? current.getPort() : getRequestedPort(isIPv6);

            if (_log.shouldDebug())
            _log.debug("Change address? Status: " + _reachabilityStatus +
                      "; Last updated: " + (_context.clock().now() - _reachabilityStatusLastUpdated) +
                      "ms ago; Old: " + Addresses.toString(externalListenHost, externalListenPort) +
                      "; New: " + Addresses.toString(ourIP, ourPort));

            if ((fixedPort && externalListenPort > 0) || ourPort <= 0)
                ourPort = externalListenPort;

                if (ourPort > 0 &&
                    !eq(externalListenHost, externalListenPort, ourIP, ourPort)) {
                    boolean rebuild = true;
                    if (isIPv6) {
                        // For IPv6, we only accept changes if this is one of our local addresses
                        Set<String> ipset = Addresses.getAddresses(false, true);
                        String ipstr = Addresses.toString(ourIP);
                        if (!ipset.contains(ipstr)) {
                            if (_log.shouldInfo())
                                _log.info("New IPv6 address received but not one of our local addresses: " + ipstr, new Exception());
                            return false;
                        }
                        if (STATUS_IPV6_FW_2.contains(_reachabilityStatus)) {
                            // If we were firewalled before, let's assume we're still firewalled.
                            // Save the new IP and fire a test
                            String oldIP = _context.getProperty(PROP_IPV6);
                            String newIP = Addresses.toString(ourIP);
                            if (!newIP.equals(oldIP)) {
                                Map<String, String> changes = new HashMap<String, String>(1);
                                changes.put(PROP_IPV6, newIP);
                                _context.router().saveConfig(changes, null);
                                if (oldIP != null) {
                                    _context.router().eventLog().addEvent(EventLog.CHANGE_IP, newIP);
                                }
                                // save the external address but don't publish it
                                OrderedProperties localOpts = new OrderedProperties();
                                localOpts.setProperty(UDPAddress.PROP_PORT, String.valueOf(ourPort));
                                localOpts.setProperty(UDPAddress.PROP_HOST, newIP);
                                RouterAddress local = new RouterAddress(getPublishStyle(), localOpts, DEFAULT_COST);
                                replaceCurrentExternalAddress(local, true);
                                if (_log.shouldWarn())
                                    _log.warn("New IPv6 address, assuming still firewalled [" +
                                              newIP + "]:" + ourPort, new Exception());
                            } else {
                                if (_log.shouldInfo())
                                    _log.info("Same IPv6 address, assuming still firewalled [" +
                                              newIP + "]:" + ourPort);
                                return false;
                            }
                            rebuild = false;
                            fireTest = true;
                        }
                    }

                    // they told us something different and our tests are either old or failing
                    if (rebuild) {
                            if (externalListenPort > 0 && ourPort > 0 &&
                                externalListenPort != ourPort &&
                                _context.getProperty(PROP_EXTERNAL_PORT, 0) != ourPort) {
                                // save the external port setting only
                                _context.router().saveConfig(PROP_EXTERNAL_PORT, Integer.toString(ourPort));
                                _context.router().eventLog().addEvent(EventLog.CHANGE_PORT, "IPv" +
                                                                                            (isIPv6 ? '6' : '4') +
                                                                                            " port " + ourPort);
                            }

                            // flush SSU2 tokens
                            if (ourPort != externalListenPort) {
                                _establisher.portChanged();
                            } else if (externalListenHost != null && !Arrays.equals(ourIP, externalListenHost)) {
                                _establisher.ipChanged(isIPv6);
                            }

                            if (_log.shouldWarn())
                                _log.warn("Trying to change our external address to " +
                                          Addresses.toString(ourIP, ourPort));
                            RouterAddress newAddr = rebuildExternalAddress(ourIP, ourPort, true);
                            updated = newAddr != null;
                    }
                } else {
                    // matched what we expect
                    if (_log.shouldDebug())
                        _log.info("Not updating our external address: matches existing");
                }
        }

        if (fireTest) {
            _context.statManager().addRateData("udp.addressTestInsteadOfUpdate", 1);
            _testEvent.forceRunImmediately(isIPv6);
        } else if (updated) {
            _context.statManager().addRateData("udp.addressUpdated", 1);
            Map<String, String> changes = new HashMap<String, String>();
            if (!isIPv6 && !fixedPort)
                changes.put(PROP_EXTERNAL_PORT, Integer.toString(ourPort));
            // Queue a country code lookup of the new IP
            _context.commSystem().queueLookup(ourIP);
            // store these for laptop-mode (change ident on restart... or every time... when IP changes)
            // IPV4 ONLY
            String oldIP = _context.getProperty(PROP_IP);
            String newIP = Addresses.toString(ourIP);
            if (!isIPv6 && !newIP.equals(oldIP)) {
                long lastChanged = 0;
                long now = _context.clock().now();
                String lcs = _context.getProperty(PROP_IP_CHANGE);
                if (lcs != null) {
                    try {lastChanged = Long.parseLong(lcs);}
                    catch (NumberFormatException nfe) {}
                }

                changes.put(PROP_IP, newIP);
                changes.put(PROP_IP_CHANGE, Long.toString(now));
                _context.router().saveConfig(changes, null);

                if (oldIP != null) {
                    _context.router().eventLog().addEvent(EventLog.CHANGE_IP, newIP);
                }

                // laptop mode
                // For now, only do this at startup
                if (oldIP != null &&
                    SystemVersion.hasWrapper() &&
                    _context.getBooleanProperty(PROP_LAPTOP_MODE) &&
                    now - lastChanged > 10*60*1000 &&
                    _context.router().getUptime() < 10*60*1000) {
                    System.out.println("WARN: IP changed; restarting with a new identity and port.");
                    _log.logAlways(Log.WARN, "IP changed; restarting with a new identity and port.");
                    // this removes the UDP port config
                    _context.router().killKeys();
                    // do we need WrapperManager.signalStopped() like in ConfigServiceHandler ???
                    // without it, the wrapper complains "shutdown unexpectedly"
                    // but we can't have that dependency in the router
                    _context.router().shutdown(Router.EXIT_HARD_RESTART);
                    // doesn't return
                }
            } else if (!isIPv6 && !fixedPort) {
                // save PROP_EXTERNAL_PORT
                _context.router().saveConfig(changes, null);
            } else if (isIPv6) {
                oldIP = _context.getProperty(PROP_IPV6);
                if (!newIP.equals(oldIP)) {
                    changes.put(PROP_IPV6, newIP);
                    _context.router().saveConfig(changes, null);
                    if (oldIP != null) {
                        _context.router().eventLog().addEvent(EventLog.CHANGE_IP, newIP);
                    }
                }
            }
            // deadlock thru here ticket #1699
            // this causes duplicate publish, REA() call above calls rebuildRouterInfo
            //_context.router().rebuildRouterInfo();
            _testEvent.forceRunImmediately(isIPv6);
        }
        return updated;
    }

    /**
     *  @param laddr and raddr may be null
     */
    private static final boolean eq(byte laddr[], int lport, byte raddr[], int rport) {
        return (rport == lport) && DataHelper.eq(laddr, raddr);
    }

    /**
     * An IPv6 address is only valid if we are configured to support IPv6
     * AND we have a public IPv6 address.
     *
     * @param addr may be null, returns false
     */
    public final boolean isValid(byte addr[]) {
        if (addr == null) return false;
        if (isPubliclyRoutable(addr) &&
            (addr.length != 16 || _haveIPv6Address))
            return true;
        return allowLocal();
    }

    /**
     *  Was true before 0.9.2
     *  Now false if we need introducers (as perhaps that's why we need them,
     *  our firewall is changing our port), unless overridden by the property.
     *  We must have an accurate external port when firewalled, or else
     *  our signature of the SessionCreated packet will be invalid.
     *
     *  As of 0.9.58, returns false if status is UNKNOWN
     */
    private boolean getIsPortFixed(boolean isIPv6) {
        String prop = _context.getProperty(PROP_FIXED_PORT);
        if (prop != null)
            return Boolean.parseBoolean(prop);
        Status status = getReachabilityStatus();
        if (isIPv6) {
            if (STATUS_IPV6_UNK.contains(status))
                return false;
            return !STATUS_IPV6_FW.contains(status);
        } else {
            if (STATUS_IPV4_UNK.contains(status))
                return false;
            return !STATUS_IPV4_FW.contains(status);
        }
    }

    /**
     * Retrieves the current connection state for a peer identified by their remote host and port.
     *
     * @param hostInfo the remote host and port of the peer
     * @return the peer state, or null if not found
     */
    PeerState getPeerState(RemoteHostId hostInfo) {
        return _peersByRemoteHost.get(hostInfo);
    }

    /**
     *  Get the states for all peers at the given remote host, ignoring port.
     *  Used for a last-chance search for a peer that changed port, by PacketHandler.
     *  Always returns empty list for IPv6 hostInfo.
     *  @since 0.9.3
     */
    List<PeerState> getPeerStatesByIP(RemoteHostId hostInfo) {
        List<PeerState> rv = new ArrayList<PeerState>(4);
        byte[] ip = hostInfo.getIP();
        if (ip != null && ip.length == 4) {
            for (PeerState ps : _peersByIdent.values()) {
                if (DataHelper.eq(ip, ps.getRemoteIP()))
                    rv.add(ps);
            }
        }
        return rv;
    }

    /**
     * get the state for the peer with the given ident, or null
     * if no state exists
     */
    PeerState getPeerState(Hash remotePeer) {
        return _peersByIdent.get(remotePeer);
    }

    /**
     * Get the state by SSU2 connection ID
     * @since 0.9.55
     */
    PeerState2 getPeerState(long rcvConnID) {
        return _peersByConnID.get(Long.valueOf(rcvConnID));
    }

    /**
     * Was the state for this SSU2 receive connection ID recently closed?
     * @since 0.9.56
     */
    PeerStateDestroyed getRecentlyClosed(long rcvConnID) {
        Long id = Long.valueOf(rcvConnID);
        synchronized(_addDropLock) {
            return _recentlyClosedConnIDs.get(id);
        }
    }

    /**
     * Start listening for packets on a destroyed connection
     * @since 0.9.57
     */
    void addRecentlyClosed(PeerStateDestroyed peer) {
        Long id = Long.valueOf(peer.getRcvConnID());
        PeerStateDestroyed oldPSD;
        synchronized(_addDropLock) {
            oldPSD = _recentlyClosedConnIDs.put(id, peer);
            if (oldPSD != null)
                _recentlyClosedConnIDs.put(id, oldPSD); // put the old one back
        }
        if (oldPSD != null)
            peer.kill();
    }

    /**
     * Stop listening for packets on a destroyed connection
     * @since 0.9.57
     */
    void removeRecentlyClosed(PeerStateDestroyed peer) {
        Long id = Long.valueOf(peer.getRcvConnID());
        synchronized(_addDropLock) {
            _recentlyClosedConnIDs.remove(id);
        }
    }

    /**
     * For /peers UI only. Not a public API, not for external use.
     *
     * @return not a copy, do not modify
     * @since 0.9.31
     */
    public Collection<PeerState> getPeers() {
        return _peersByIdent.values();
    }

    /**
     * Connected peers.
     *
     * @return a copy, modifiable
     * @since 0.9.34
     */
    public List<Hash> getEstablished() {
        return new ArrayList<Hash>(_peersByIdent.keySet());
    }

    /**
     *  Remove and add to peersByRemoteHost map
     *  @since 0.9.3
     */
    void changePeerPort(PeerState peer, int newPort) {
        // this happens a lot
        int oldPort;
        synchronized (_addDropLock) {
            oldPort = peer.getRemotePort();
            if (oldPort != newPort) {
                _peersByRemoteHost.remove(peer.getRemoteHostId());
                peer.changePort(newPort);
                _peersByRemoteHost.put(peer.getRemoteHostId(), peer);
            }
        }
        if (_log.shouldInfo() && oldPort != newPort)
            _log.info("Changed port from " + oldPort + " to " + newPort + " for " + peer);
    }

    /**
     *  Remove and add to peersByRemoteHost map
     *  @since 0.9.56
     */
    void changePeerAddress(PeerState2 peer, RemoteHostId newAddress) {
        RemoteHostId oldAddress;
        synchronized (_addDropLock) {
            oldAddress = peer.getRemoteHostId();
            if (!oldAddress.equals(newAddress)) {
                _peersByRemoteHost.remove(oldAddress);
                peer.changeAddress(newAddress);
                _peersByRemoteHost.put(newAddress, peer);
            }
        }
        if (_log.shouldInfo() && !oldAddress.equals(newAddress))
            _log.info("Changed address from " + oldAddress + " to " + newAddress + " for " + peer);
    }

    /**
     *  For IntroductionManager
     *  @return may be null if not started
     *  @since 0.9.2
     */
    EstablishmentManager getEstablisher() {
        return _establisher;
    }

    /**
     * Adds a new peer state or replaces an existing one if the peer identity or address changes.
     * This method ensures thread-safe updates to internal peer tracking maps.
     *
     * @param peer the peer state to add or update
     * @return true if the peer was successfully added or updated
     */
    boolean addRemotePeerState(PeerState peer) {
        if (_log.shouldDebug())
            _log.debug("Adding remote peer state\n* Peer: " + peer);
        synchronized(_addDropLock) {
            return locked_addRemotePeerState(peer);
        }
    }

    private boolean locked_addRemotePeerState(PeerState peer) {
        Hash remotePeer = peer.getRemotePeer();
        long oldEstablishedOn = -1;
        PeerState oldPeer = null;
        if (remotePeer != null) {
            oldPeer = _peersByIdent.put(remotePeer, peer);
            if ( (oldPeer != null) && (oldPeer != peer) ) {
                // this happens a lot
                if (_log.shouldInfo())
                    _log.info("Router already connected (PBID): Old = " + oldPeer + " New = " + peer);
                // transfer over the old state/inbound message fragments/etc
                peer.loadFrom(oldPeer);
                oldEstablishedOn = oldPeer.getKeyEstablishedTime();
            }
        }

        if (peer.getVersion() == 2) {
            PeerState2 state2 = (PeerState2) peer;
            _peersByConnID.put(Long.valueOf(state2.getRcvConnID()), state2);
        }

        RemoteHostId remoteId = peer.getRemoteHostId();
        if (oldPeer != null) {
            sendDestroy(oldPeer, SSU2Util.REASON_REPLACED);
            oldPeer.dropOutbound();
            _introManager.remove(oldPeer);
            RemoteHostId oldID = oldPeer.getRemoteHostId();
            if (!remoteId.equals(oldID)) {
                // leak fix, remove old address
                if (_log.shouldInfo())
                    _log.info(remotePeer + " changed address FROM " + oldID + " TO " + remoteId);
                PeerState oldPeer2 = _peersByRemoteHost.remove(oldID);
                // different ones in the two maps? shouldn't happen
                if (oldPeer2 != oldPeer && oldPeer2 != null) {
                    oldPeer2.dropOutbound();
                     _introManager.remove(oldPeer2);
                }
            }
            if (oldPeer != peer && oldPeer.getVersion() == 2) {
                PeerState2 state2 = (PeerState2) oldPeer;
                Long id = Long.valueOf(state2.getRcvConnID());
                PeerStateDestroyed newPSD = new PeerStateDestroyed(_context, this, state2);
                PeerStateDestroyed oldPSD = _recentlyClosedConnIDs.put(id, newPSD);
                if (oldPSD != null) {
                    // put the old one back, kill new one
                    _recentlyClosedConnIDs.put(id, oldPSD);
                    newPSD.kill();
                }
                _peersByConnID.remove(id);
            }
        }

        // don't do this twice
        PeerState oldPeer2 = _peersByRemoteHost.put(remoteId, peer);
        if (oldPeer2 != null && oldPeer2 != peer && oldPeer2 != oldPeer) {
            // this shouldn't happen, should have been removed above
            if (_log.shouldWarn())
                _log.warn("Router already connected (PBRH): old=" + oldPeer2 + " new=" + peer);
            // transfer over the old state/inbound message fragments/etc
            // Send destroy before loadFrom(), because loadFrom() sets dead = true
            sendDestroy(oldPeer2, SSU2Util.REASON_REPLACED);
            peer.loadFrom(oldPeer2);
            oldEstablishedOn = oldPeer2.getKeyEstablishedTime();
            oldPeer2.dropOutbound();
            _introManager.remove(oldPeer2);
        }

        if (_log.shouldWarn() && !_mismatchLogged && _peersByIdent.size() != _peersByRemoteHost.size()) {
            _mismatchLogged = true;
            _log.warn("Size Mismatch after add: " + peer
                       + " byIDsz = " + _peersByIdent.size()
                       + " byHostsz = " + _peersByRemoteHost.size());
        }

        markReachable(peer.getRemotePeer(), peer.isInbound());
        _introManager.add(peer);

        if (oldEstablishedOn > 0)
            _context.statManager().addRateData("udp.alreadyConnected", oldEstablishedOn);

        // The only possible reason to rebuild is if they can be an introducer for us
        // so avoid going through rebuildIfNecessary()
        long tag = peer.getTheyRelayToUsAs();
        if (tag > 0) {
            boolean ipv6 = peer.isIPv6();
            if (introducersRequired(ipv6)) {
                RouterAddress addr = getCurrentAddress(ipv6);
                if (addr != null) {
                    int count = 0;
                    for (String p : UDPAddress.PROP_INTRO_TAG) {
                        if (addr.getOption(p) == null)
                            break;
                        count++;
                    }
                    if (count < PUBLIC_RELAY_COUNT) {
                        long now = _context.clock().now();
                        synchronized (_rebuildLock) {
                            long sinceSelected = now - (ipv6 ? _v6IntroducersSelectedOn : _v4IntroducersSelectedOn);
                            if (count == 0 || sinceSelected > 2*60*1000) {
                                // Rate limit to prevent rapid churn after transition to firewalled or at startup
                                if (_log.shouldWarn())
                                    _log.warn("Rebuilding address -> New introducer (Total: " + count + ") " + peer);
                                rebuildExternalAddress(ipv6);
                            }
                        }
                    }
                }
            }
        }
        return true;
    }

    @Override
    public void messageReceived(I2NPMessage inMsg, RouterIdentity remoteIdent, Hash remoteIdentHash, long msToReceive, int bytesReceived) {
        if (inMsg.getType() == DatabaseStoreMessage.MESSAGE_TYPE) {
            DatabaseStoreMessage dsm = (DatabaseStoreMessage)inMsg;
            DatabaseEntry entry = dsm.getEntry();
            if (entry == null)
                return;
            if (entry.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
                RouterInfo ri = (RouterInfo) entry;
                int id = ri.getNetworkId();
                if (id != _networkID) {
                    Hash peerHash = entry.getHash();
                    if (peerHash.equals(remoteIdentHash)) {
                        PeerState peer = getPeerState(peerHash);
                        if (peer != null) {
                            RemoteHostId remote = peer.getRemoteHostId();
                            _dropList.add(remote);
                            _context.statManager().addRateData("udp.dropPeerDroplist", 1);
                            _context.simpleTimer2().addEvent(new RemoveDropList(remote), DROPLIST_PERIOD);
                        }
                        markUnreachable(peerHash);
                        if (id == -1)
                            _context.banlist().banlistRouter(peerHash, " <b>➜</b> No network specified", null, null, _context.clock().now() + Banlist.BANLIST_DURATION_NO_NETWORK);
                        else
                            _context.banlist().banlistRouterForever(peerHash, " <b>➜</b> Not in our network: " + id);
                        if (peer != null)
                            sendDestroy(peer, SSU2Util.REASON_NETID);
                        dropPeer(peerHash, false, "Not in our network");
                        if (_log.shouldWarn())
                            _log.warn("Not in our network: " + entry, new Exception());
                        return;
                    } // else will be invalidated and handled by netdb
                }
            }
        }
        super.messageReceived(inMsg, remoteIdent, remoteIdentHash, msToReceive, bytesReceived);
    }

    private class RemoveDropList implements SimpleTimer.TimedEvent {
        private final RemoteHostId _peer;
        public RemoveDropList(RemoteHostId peer) { _peer = peer; }
        public void timeReached() {
            _dropList.remove(_peer);
        }
    }

    boolean isInDropList(RemoteHostId peer) { return _dropList.contains(peer); }

    /**
     *  This does not send a session destroy, caller must do that if desired.
     *
     *  @param shouldBanlist doesn't really, only sets unreachable
     */
    void dropPeer(Hash peer, boolean shouldBanlist, String why) {
        PeerState state = getPeerState(peer);
        if (state != null)
            dropPeer(state, shouldBanlist, why);
    }

    /**
     *  This does not send a session destroy, caller must do that if desired.
     *
     *  @param shouldBanlist doesn't really, only sets unreachable
     */
    void dropPeer(PeerState peer, boolean shouldBanlist, String why) {
        if (_log.shouldDebug()) {
            long now = _context.clock().now();
            StringBuilder buf = new StringBuilder(4096);
            long timeSinceSend = now - peer.getLastSendTime();
            long timeSinceRecv = now - peer.getLastReceiveTime();
            long timeSinceAck  = now - peer.getLastACKSend();
            long timeSinceSendOK = now - peer.getLastSendFullyTime();
            int consec = peer.getConsecutiveFailedSends();
            buf.append("Dropping remote peer: ").append(peer.toString()).append(" Banlist? ").append(shouldBanlist)
               .append("\n* Lifetime: ").append(now - peer.getKeyEstablishedTime())
               .append("; Time since send/fully/RECV/ACK: [").append(timeSinceSend).append(" / ")
               .append(timeSinceSendOK).append(" / ")
               .append(timeSinceRecv).append(" / ").append(timeSinceAck)
               .append("]; Consecutive failures: ").append(consec);
            if (why != null) {buf.append("\n* Cause: ").append(why);}
        }
        synchronized(_addDropLock) {locked_dropPeer(peer, shouldBanlist, why);}
        // The only possible reason to rebuild is if they were an introducer for us
        // so avoid going through rebuildIfNecessary()
        long tag = peer.getTheyRelayToUsAs();
        if (tag > 0) {
            boolean ipv6 = peer.isIPv6();
            if (!introducersRequired(ipv6))
                return;
            RouterAddress addr = getCurrentAddress(ipv6);
            if (addr == null)
                return;
            String stag = Long.toString(tag);
            for (String p : UDPAddress.PROP_INTRO_TAG) {
                String itag = addr.getOption(p);
                if (itag == null)
                    break;
                if (itag.equals(stag)) {
                    if (_log.shouldWarn())
                        _log.warn("Rebuilding address -> Dropped published introducer " + peer);
                    synchronized (_rebuildLock) {rebuildExternalAddress(ipv6);}
                    break;
                }
            }
        }
    }

    /**
     *  This does not send a session destroy, caller must do that if desired.
     *
     *  @param shouldBanlist doesn't really, only sets unreachable
     */
    private void locked_dropPeer(PeerState peer, boolean shouldBanlist, String why) {
        peer.dropOutbound();
        peer.expireInboundMessages();
        _introManager.remove(peer);
        _fragments.dropPeer(peer);

        PeerState altByIdent = null;
        if (peer.getRemotePeer() != null) {
            if (shouldBanlist) {
                markUnreachable(peer.getRemotePeer());
                //_context.banlist().banlistRouter(peer.getRemotePeer(), "dropped after too many retries", STYLE);
            }
            long now = _context.clock().now();
            _context.statManager().addRateData("udp.droppedPeer", now - peer.getLastReceiveTime(), now - peer.getKeyEstablishedTime());
            altByIdent = _peersByIdent.remove(peer.getRemotePeer());
        }

        if (peer.getVersion() == 2) {
            PeerState2 state2 = (PeerState2) peer;
            Long id = Long.valueOf(state2.getRcvConnID());
            PeerStateDestroyed newPSD = new PeerStateDestroyed(_context, this, state2);
            PeerStateDestroyed oldPSD = _recentlyClosedConnIDs.put(id, newPSD);
            if (oldPSD != null) {
                // put the old one back, kill new one
                _recentlyClosedConnIDs.put(id, oldPSD);
                newPSD.kill();
            }
            _peersByConnID.remove(id);
        }

        RemoteHostId remoteId = peer.getRemoteHostId();
        PeerState altByHost = _peersByRemoteHost.remove(remoteId);

        if (altByIdent != altByHost && _log.shouldInfo())
            _log.warn("[SSU] Mismatch on remove -> " +
                      (remoteId != null ? "RemoteHostId: " + remoteId : "") +
                      (altByIdent != null ? " byID: " + altByIdent : "") +
                      (altByHost != null ? " byHost: " + altByHost : "") +
                      " byIdentSize: " + _peersByIdent.size() +
                      " byHostSize: " + _peersByRemoteHost.size());

        // deal with races to make sure we drop the peers fully
        if ( (altByIdent != null) && (peer != altByIdent) ) locked_dropPeer(altByIdent, shouldBanlist, "recurse");
        if ( (altByHost != null) && (peer != altByHost) ) locked_dropPeer(altByHost, shouldBanlist, "recurse");
    }

    /**
     *  Rebuild the IPv4 or IPv6 external address if required
     */
    private void rebuildIfNecessary() {
        synchronized (_rebuildLock) {
            int code = locked_needsRebuild();
            if (code != 0)
                rebuildExternalAddress(code == 2);
        }
    }

    /**
     *  @return 1 for ipv4, 2 for ipv6, 0 for neither
     */
    private int locked_needsRebuild() {
        if (_context.router().isHidden()) return 0;
        TransportUtil.IPv6Config config = getIPv6Config();
        // IPv4
        boolean v6Only = config == IPV6_ONLY;
        if (!v6Only) {
            RouterAddress addr = getCurrentAddress(false);
            if (locked_needsRebuild(addr, false))
                return 1;
        }
        // IPv6
        boolean v4Only = config == IPV6_DISABLED;
        if (!v4Only && _haveIPv6Address) {
            RouterAddress addr = getCurrentAddress(true);
            if (locked_needsRebuild(addr, true))
                return 2;
        }
        return 0;
    }

    /**
     *  Does this address need rebuilding?
     *
     *  @param addr may be null
     *  @since 0.9.50 split out from above
     */
    private boolean locked_needsRebuild(RouterAddress addr, boolean ipv6) {
        if (_needsRebuild)
            return true;
        if (introducersRequired(ipv6)) {
            UDPAddress ua = new UDPAddress(addr);
            long now = _context.clock().now();
            int valid = 0;
            int count = ua.getIntroducerCount();
            for (int i = 0; i < count; i++) {
                long exp = ua.getIntroducerExpiration(i);
                if (exp > 0 && exp < now + INTRODUCER_EXPIRATION_MARGIN) {
                    if (_log.shouldInfo())
                        _log.info((ipv6 ? "IPv6" : "IPv4") + " Introducer " + i + " expiring soon, need to replace");
                    continue;
                }
                long tag = ua.getIntroducerTag(i);
                if (_introManager.isInboundTagValid(tag)) {
                    valid++;
                } else {
                    if (_log.shouldInfo())
                        _log.info((ipv6 ? "IPv6" : "IPv4") + " Introducer " + i + " no longer connected, need to replace");
                }
            }
            long sinceSelected = now - (ipv6 ? _v6IntroducersSelectedOn : _v4IntroducersSelectedOn);
            if (valid >= PUBLIC_RELAY_COUNT) {
                if (_log.shouldInfo())
                    _log.info("Our" + (ipv6 ? "IPv6" : "IPv4") + " introducers valid, selected " + DataHelper.formatDuration(sinceSelected) + " ago");
                return false;
            } else if (sinceSelected > 2*60*1000) {
                // Rate limit to prevent rapid churn after transition to firewalled or at startup
                int avail = _introManager.introducerCount(ipv6);
                boolean rv = valid < count || valid < avail;
                if (rv) {
                    if (_log.shouldWarn())
                        _log.warn((ipv6 ? "IPv6" : "IPv4") + " Need more introducers (have " + count + " valid " + valid +
                                  " need " + PUBLIC_RELAY_COUNT + " avail " + avail + ')');
                } else {
                    if (_log.shouldInfo())
                        _log.info((ipv6 ? "IPv6" : "IPv4") + " Need more introducers, no more avail. (have " + valid +
                                  " need " + PUBLIC_RELAY_COUNT + " avail " + avail + ')');
                }
                return rv;
            } else {
                if (_log.shouldInfo())
                    _log.info("Need more " + (ipv6 ? "IPv6" : "IPv4") + " introducers (have " + valid + ", need " + PUBLIC_RELAY_COUNT + ')' +
                              " but we just chose them " + DataHelper.formatDuration(sinceSelected) + " ago; waiting...");
                // TODO also check to see if we actually have more available
                return false;
            }
        } else {
            byte[] externalListenHost = addr != null ? addr.getIP() : null;
            int externalListenPort = addr != null ? addr.getPort() : -1;
            boolean rv = (externalListenHost == null) || (externalListenPort <= 0);
            if (!rv) {
                // shortcut to determine if introducers are present
                if (addr.getOption("itag0") != null)
                    rv = true;  // status == ok and we don't actually need introducers, so rebuild
            }
            if (rv) {
                if (_log.shouldInfo())
                    _log.info((ipv6 ? "IPv6" : "IPv4") + " Need to initialize our direct SSU info (" + Addresses.toString(externalListenHost, externalListenPort) + ')');
            } else if (addr.getPort() <= 0 || addr.getHost() == null) {
                if (_log.shouldInfo())
                    _log.info((ipv6 ? "IPv6" : "IPv4") + " Our direct SSU info is initialized, but not used in our address yet");
                rv = true;
            } else {
                _log.info("Our direct SSU info is initialized");
            }
            return rv;
        }
    }

    /**
     *  This sends it directly out, bypassing OutboundMessageFragments.
     *  The only queueing is for the bandwidth limiter.
     *  BLOCKING if OB queue is full.
     */
    void send(UDPPacket packet) {
        if (_pusher != null) {
            if (_log.shouldDebug())
                _log.debug("Sending direct packet to " + packet);
            _pusher.send(packet);
        } else {
            _log.error("No pusher", new Exception());
        }
    }

    /**
     *  Send a session destroy message, bypassing OMF and PacketPusher.
     *  BLOCKING if OB queue is full.
     *
     *  @param reasonCode SSU2 only, ignored for SSU1
     *  @since 0.8.9
     */
    void sendDestroy(PeerState peer, int reasonCode) {
        UDPPacket pkt;
            try {
                pkt = _packetBuilder2.buildSessionDestroyPacket(reasonCode, (PeerState2) peer);
            } catch (IOException ioe) {
                return;
            }
        if (_log.shouldDebug())
            _log.debug("Sending destroy packet to " + peer);
        send(pkt);
    }

    /**
     *  Send a session destroy message to everybody.
     *  BLOCKING for at least 1 sec per 1K peers, more if BW is very low or if OB queue is full.
     *
     *  @since 0.8.9
     */
    private void destroyAll() {
        for (UDPEndpoint endpoint : _endpoints) {
            endpoint.clearOutbound();
        }
        int howMany = _peersByIdent.size();
        // use no more than 1/4 of configured bandwidth
        final int burst = 8;
        int pps = Math.max(48, (_context.bandwidthLimiter().getOutboundKBytesPerSecond() * 1000 / 4) /  48);
        int burstps = pps / burst;
        // max of 1000 pps
        int toSleep = Math.max(8, (1000 / burstps));
        int count = 0;
        if (_log.shouldInfo())
            _log.info("Sending destroy to: " + howMany + " peers");
        for (PeerState peer : _peersByIdent.values()) {
            sendDestroy(peer, SSU2Util.REASON_TIMEOUT);
            // 1000 per second * 48 bytes = 400 KBps
            if ((++count) % burst == 0) {
                try {
                    Thread.sleep(toSleep);
                } catch (InterruptedException ie) {}
            }
        }
        toSleep = Math.min(howMany / 3, 750);
        if (toSleep > 0) {
            try {
                Thread.sleep(toSleep);
            } catch (InterruptedException ie) {}
        }
    }

    public TransportBid bid(RouterInfo toAddress, int dataSize) {
        if (dataSize > OutboundMessageState.MAX_MSG_SIZE) {
            // NTCP max is lower, so msg will get dropped
            return null;
        }
        Hash to = toAddress.getIdentity().calculateHash();
        PeerState peer = getPeerState(to);
        if (peer != null) {
            if (preferUDP())
                return _cachedBid[FAST_PREFERRED_BID];
            else
                return _cachedBid[FAST_BID];
        } else {
            int nid = toAddress.getNetworkId();
            if (nid != _networkID) {
                if (nid == -1)
                    _context.banlist().banlistRouter(to, " <b>➜</b> No network specified", null, null, _context.clock().now() + Banlist.BANLIST_DURATION_NO_NETWORK);
                else
                    _context.banlist().banlistRouterForever(to, " <b>➜</b> Not in our network: " + nid);
                markUnreachable(to);
                return null;
            }

            // If we don't have a port, all is lost
            if ( _reachabilityStatus == Status.HOSED) {
                markUnreachable(to);
                return null;
            }

            if (isUnreachable(to))
                return null;

            // temp, let NTCP2 deal with him (prop. 165)
            // Be less aggressive about rejecting floodfills when firewalled, as we need more peers
            if (toAddress.getCapabilities().indexOf(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL) >= 0) {
                PeerProfile prof = _context.profileOrganizer().getProfileNonblocking(to);
                if (prof != null) {
                    int agreed = Math.round(prof.getTunnelHistory().getLifetimeAgreedTo());
                    int rejected = Math.round(prof.getTunnelHistory().getLifetimeRejected());
                    boolean weAreFirewalled = _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.REJECT_UNSOLICITED ||
                                              _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_FIREWALLED_IPV6_OK ||
                                              _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_FIREWALLED_IPV6_UNKNOWN ||
                                              _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_OK_IPV6_FIREWALLED ||
                                              _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_UNKNOWN_IPV6_FIREWALLED ||
                                              _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_DISABLED_IPV6_FIREWALLED;
                    // Much more lenient rejection threshold when firewalled (5x instead of 2x)
                    int threshold = weAreFirewalled ? 10 : 2; // 10/2 = 5x for firewalled
                    if (prof.getLastHeardFrom() <= 0 || rejected > agreed*threshold) {return null;}
                } else  {return null;}
            }

            // Validate his SSU address
            RouterAddress addr = getTargetAddress(toAddress);
            if (addr == null) {
                markUnreachable(to);
                return null;
            }

            // c++ bug thru 2.36.0/0.9.49, will disconnect inbound session after 5 seconds
            int cost = addr.getCost();
            if (cost == 10) {
                if (VersionComparator.comp(toAddress.getVersion(), "0.9.52") <= 0) {
                    markUnreachable(to);
                    return null;
                }
            } else if (cost == 9) {
                // c++ bug in 2.40.0/0.9.52, drops SSU messages
                if (toAddress.getVersion().equals("0.9.52")) {
                    markUnreachable(to);
                    return null;
                }
            }

            // Check for supported sig type
            SigType type = toAddress.getIdentity().getSigType();
            if (type == null || !type.isAvailable()) {
                markUnreachable(to);
                return null;
            }

            // Can we connect to them if we are not DSA?
            RouterInfo us = _context.router().getRouterInfo();
            if (us != null) {
                RouterIdentity id = us.getIdentity();
                if (id.getSigType() != SigType.DSA_SHA1) {
                    String v = toAddress.getVersion();
                    if (VersionComparator.comp(v, MIN_SIGTYPE_VERSION) < 0) {
                        markUnreachable(to);
                        return null;
                    }
                }
            }

            if (!allowConnection())
                return _cachedBid[TRANSIENT_FAIL_BID];

            boolean weAreFirewalled = _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.REJECT_UNSOLICITED ||
                                      _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_FIREWALLED_IPV6_OK ||
                                      _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_FIREWALLED_IPV6_UNKNOWN ||
                                      _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_OK_IPV6_FIREWALLED ||
                                      _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_UNKNOWN_IPV6_FIREWALLED ||
                                      _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_DISABLED_IPV6_FIREWALLED;

            /*
             * Try to maintain at least 5 peers (30 for v6) so we can determine our IP address and
             * we have a selection to run peer tests with.
             *
             * If we are firewalled, and we don't have enough peers that volunteered to also introduce us,
             * also bid aggressively so we are preferred over NTCP - otherwise we only talk UDP to those that
             * are firewalled, and we will never get any introducers
             */
            if (alwaysPreferUDP()) {
                int capacityThreshold = weAreFirewalled ? 75 : 90;
                if (haveCapacity(capacityThreshold))
                    return _cachedBid[SLOW_PREFERRED_BID];
                if (cost > DEFAULT_COST)
                    return _cachedBid[NEAR_CAPACITY_COST_BID];
                return _cachedBid[NEAR_CAPACITY_BID];
            }
            int count = _peersByIdent.size();
            boolean ipv6 = TransportUtil.isIPv6(addr);
            boolean needIntroducers = (introducersRequired(ipv6) &&
                        addr.getOption(UDPAddress.PROP_CAPACITY) != null &&
                        addr.getOption(UDPAddress.PROP_CAPACITY).indexOf(UDPAddress.CAPACITY_INTRODUCER) >= 0 &&
                        _introManager.introducerCount(ipv6) < MIN_INTRODUCER_POOL);
            if ((!ipv6 && count < _min_peers) ||
                       (ipv6 && _haveIPv6Address && count < _min_v6_peers) ||
                       needIntroducers) {
                 /*
                  * Even if we haven't hit our minimums, give NTCP a chance some of the time.
                  * This may make things work a little faster at startup, especially when we
                  * have an IPv6 address and the increased minimums, and if UDP is completely
                  * blocked we'll still have some connectivity.
                  *
                  * TODO After some time, decide that UDP is blocked/broken and return TRANSIENT_FAIL_BID?
                  * Even more if hidden.
                  * We'll have very low connection counts, and we don't need peer testing
                  */
                int ratio;
                if (needIntroducers) {
                    // When we need introducers, prefer UDP but not too aggressively (75% chance)
                    ratio = 4; // 1 in 4 = 25% chance of SLOWEST, 75% chance of SLOW_PREFERRED
                } else if (_context.router().isHidden() || weAreFirewalled) {
                    ratio = 2;
                } else {
                    ratio = 4;
                }
                if (_context.random().nextInt(ratio) == 0) {return _cachedBid[SLOWEST_BID];}
                return _cachedBid[SLOW_PREFERRED_BID];
            } else if (preferUDP()) {return _cachedBid[SLOW_BID];}
            else if (haveCapacity()) {
                if (cost > DEFAULT_COST) {return _cachedBid[SLOWEST_COST_BID];}
                return _cachedBid[SLOWEST_BID];
            } else {
                if (cost > DEFAULT_COST) {return _cachedBid[NEAR_CAPACITY_COST_BID];}
                return _cachedBid[NEAR_CAPACITY_BID];
            }
        }
    }

    /**
     *  Get first available address we can use.
     *  @return address or null
     *  @since 0.9.6
     */
    RouterAddress getTargetAddress(RouterInfo target) {
        List<RouterAddress> addrs = getTargetAddresses(target);
        for (int i = 0; i < addrs.size(); i++) {
            RouterAddress addr = addrs.get(i);
            if (addr.getTransportStyle().equals("SSU") && !"2".equals(addr.getOption("v")))
                continue;
            if (addr.getOption("itag0") == null) {
                // No introducers
                // Skip outbound-only or invalid address/port
                byte[] ip = addr.getIP();
                int port = addr.getPort();
                if (ip == null || !TransportUtil.isValidPort(port) ||
                    (!isValid(ip)) ||
                    (Arrays.equals(ip, getExternalIP()) && !allowLocal())) {
                    continue;
                }
            } else {
                // introducers
                String caps = addr.getOption(UDPAddress.PROP_CAPACITY);
                if (caps != null && caps.contains(CAP_IPV6) && !_haveIPv6Address)
                    continue;
            }
            return addr;
        }
        return null;
    }

    private boolean preferUDP() {
        String pref = _context.getProperty(PROP_PREFER_UDP, DEFAULT_PREFER_UDP);
        return !"false".equals(pref);
    }

    private boolean alwaysPreferUDP() {
        return "always".equals(_context.getProperty(PROP_PREFER_UDP)) ||
               "cn".equals(_context.commSystem().getOurCountry());
    }

    /**
     * We used to have MAX_IDLE_TIME = 5m, but this causes us to drop peers
     * and lose the old introducer tags, causing introduction fails,
     * so we keep the max time long to give the introducer keepalive code
     * in the IntroductionManager a chance to work.
     */
    public static final int EXPIRE_TIMEOUT = 20*60*1000;
    private static final int MAX_IDLE_TIME = EXPIRE_TIMEOUT;
    public static final int MIN_EXPIRE_TIMEOUT = 3*60*1000;

    public String getStyle() {return STYLE;}

    /**
     * An alternate supported style, or null.
     * @since 0.9.54
     */
    @Override
    public String getAltStyle() {return STYLE2;}

    /**
     * @return "SSU" unless SSU1 disabled, then "SSU2"
     * @since 0.9.57
     */
    private String getPublishStyle() {return STYLE2;}

    @Override
    public void send(OutNetMessage msg) {
        if (msg == null) return;
        RouterInfo tori = msg.getTarget();
        if (tori == null) return;
        if (tori.getIdentity() == null) return;
        if (_establisher == null) {
            failed(msg, "UDP not up yet");
            return;
        }

        boolean weAreFirewalled = _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.REJECT_UNSOLICITED ||
                                  _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_FIREWALLED_IPV6_OK ||
                                  _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_FIREWALLED_IPV6_UNKNOWN ||
                                  _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_OK_IPV6_FIREWALLED ||
                                  _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_UNKNOWN_IPV6_FIREWALLED ||
                                  _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_DISABLED_IPV6_FIREWALLED;

        msg.timestamp("Sending on UDP transport");
        Hash to = tori.getIdentity().calculateHash();
        PeerState peer = getPeerState(to);
        if (_log.shouldDebug()) {_log.debug("Sending to " + (to != null ? to.toString() : ""));}
        if (peer != null) {
            long lastSend = peer.getLastSendFullyTime();
            long lastRecv = peer.getLastReceiveTime();
            long now = _context.clock().now();
            int inboundActive = peer.expireInboundMessages();
            int maxIdle = weAreFirewalled ? MAX_IDLE_TIME*2 : MAX_IDLE_TIME;
            if (!weAreFirewalled && (lastSend > 0) && (lastRecv > 0)) {
                if ((now - lastSend > MAX_IDLE_TIME) &&
                     (now - lastRecv > MAX_IDLE_TIME) &&
                     (peer.getConsecutiveFailedSends() > 5) &&
                     (inboundActive <= 0)) {
                    // peer is waaaay idle, drop the con and queue it up as a new con
                    dropPeer(peer, false, "proactive reconnection");
                    msg.timestamp("peer is really idle, dropping con and reestablishing");
                    if (_log.shouldDebug()) {_log.debug("Proactive reestablish to " + to);}
                    _establisher.establish(msg);
                    _context.statManager().addRateData("udp.proactiveReestablish", now-lastSend, now-peer.getKeyEstablishedTime());
                    return;
                }
            }
            msg.timestamp("Enqueueing for an already established peer");
            // skip the priority queue and go straight to the active pool
            _fragments.add(msg);
        } else {
            if (_log.shouldDebug())
                _log.debug("Establish new connection to " + to);
            msg.timestamp("Establishing a new connection");
            _establisher.establish(msg);
        }
    }

    /**
     *  Send only if established, otherwise fail immediately.
     *  Never queue with the establisher.
     *  @since 0.9.2
     */
    void sendIfEstablished(OutNetMessage msg) {
        _fragments.add(msg);
    }

    /**
     *  "injected" message from the EstablishmentManager.
     *  If you have multiple messages, use the list variant, so the messages may be bundled efficiently.
     *
     *  @param peer the message MUST be going to this peer
     */
    void send(I2NPMessage msg, PeerState peer) {
        try {
            OutboundMessageState state = new OutboundMessageState(_context, msg, peer);
            if (_log.shouldDebug())
                _log.debug("Injecting a data message to a new peer: " + peer);
            _fragments.add(state, peer);
        } catch (IllegalArgumentException iae) {
            if (_log.shouldWarn())
                _log.warn("Shouldn't happen", new Exception("I did it"));
        }
    }

    /**
     *  "injected" message from the EstablishmentManager, plus pending messages to send,
     *  so the messages may be bundled efficiently. Called at end of outbound establishment.
     *
     *  @param msg may be null if nothing to inject
     *  @param msgs non-null, may be empty
     *  @param peer all messages MUST be going to this peer
     *  @since 0.9.24
     */
    void send(I2NPMessage msg, List<OutNetMessage> msgs, PeerState peer) {
        try {
            int sz = msgs.size();
            List<OutboundMessageState> states = new ArrayList<OutboundMessageState>(sz + 1);
            if (msg != null) {
                OutboundMessageState state = new OutboundMessageState(_context, msg, peer);
                states.add(state);
            }
            for (int i = 0; i < sz; i++) {
                OutboundMessageState state = new OutboundMessageState(_context, msgs.get(i), peer);
                states.add(state);
            }
            if (_log.shouldDebug())
                _log.debug("Injecting " + states.size() + " data messages to a new peer: " + peer);
            _fragments.add(states, peer);
        } catch (IllegalArgumentException iae) {
            if (_log.shouldWarn())
                _log.warn("Shouldn't happen", new Exception("I did it"));
        }
    }

    /**
     *  "injected" messages from the EstablishmentManager.
     *  Called at end of inbound establishment.
     *
     *  @param peer all messages MUST be going to this peer
     *  @since 0.9.24
     */
    void send(List<I2NPMessage> msgs, PeerState peer) {
        try {
            int sz = msgs.size();
            List<OutboundMessageState> states = new ArrayList<OutboundMessageState>(sz);
            for (int i = 0; i < sz; i++) {
                OutboundMessageState state = new OutboundMessageState(_context, msgs.get(i), peer);
                states.add(state);
            }
            if (_log.shouldDebug())
                _log.debug("Injecting " + sz + " data messages to a new peer: " + peer);
            _fragments.add(states, peer);
        } catch (IllegalArgumentException iae) {
            if (_log.shouldWarn())
                _log.warn("Shouldn't happen", new Exception("I did it"));
        }
    }

    // we don't need the following, since we have our own queueing
    protected void outboundMessageReady() { throw new UnsupportedOperationException("Not used for UDP"); }

    public void startListening() {startup();}

    public void stopListening() {
        shutdown();
        replaceAddress(null);
    }

    private boolean explicitAddressSpecified() {
        String h = _context.getProperty(PROP_EXTERNAL_HOST);
        // Bug in config.jsp prior to 0.7.14, sets an empty host config
        return h != null && h.length() > 0;
    }

    /**
     * Rebuild to get updated cost and introducers. IPv4 only, unless configured as IPv6 only.
     * Do not tell the router (he is the one calling this)
     * @since 0.7.12
     */
    @Override
    public List<RouterAddress> updateAddress() {
        boolean ipv6 = getIPv6Config() == IPV6_ONLY;
        rebuildExternalAddress(false, ipv6);
        return getCurrentAddresses();
    }

    /**
     * Rebuilds the external router address for either IPv4 or IPv6.
     * This is called when the router needs to update its published address,
     * typically after a reachability change or IP update.
     *
     * @param allowRebuildRouterInfo whether to trigger a full router info rebuild
     * @param ipv6 whether to rebuild the IPv6 address
     * @return the new router address if changed, otherwise null
     */
    private RouterAddress rebuildExternalAddress(boolean ipv6) {
        if (_log.shouldDebug())
            _log.debug("REA1 ipv6? " + ipv6);
        return rebuildExternalAddress(true, ipv6);
    }

    /**
     *  Update our IPv4 address and optionally tell the router to rebuild and republish the router info.
     *
     *  If PROP_EXTERNAL_HOST is set, use those addresses (comma/space separated).
     *  If a hostname is configured in that property, use it.
     *  As of 0.9.32, a hostname is resolved here into one or more addresses
     *  and the IPs are published, to implement proposal 141.
     *
     *  A max of one v4 and one v6 address will be set. Significant changes both
     *  here and in NTCP would be required to publish multiple v4 or v6 addresses.
     *
     *  @param allowRebuildRouterInfo whether to tell the router
     *  @return the new address if changed, else null
     */
    private RouterAddress rebuildExternalAddress(boolean allowRebuildRouterInfo, boolean ipv6) {
        if (_log.shouldDebug())
            _log.debug("REA2 " + allowRebuildRouterInfo + " ipv6? " + ipv6);
        // if the external port is specified, we want to use that to bind to even
        // if we don't know the external host.
        int port = _context.getProperty(PROP_EXTERNAL_PORT, -1);

        String host = null;
        if (explicitAddressSpecified()) {
            host = _context.getProperty(PROP_EXTERNAL_HOST);
            if (host != null) {
                String[] hosts = DataHelper.split(host, "[,; \r\n\t]");
                RouterAddress rv = null;
                // we only take one each of v4 and v6
                boolean v4 = false;
                boolean v6 = false;
                // prevent adding a type if disabled
                TransportUtil.IPv6Config cfg = getIPv6Config();
                if (cfg == IPV6_DISABLED)
                    v6 = true;
                else if (cfg == IPV6_ONLY)
                    v4 = true;
                for (int i = 0; i < hosts.length; i++) {
                    String h = hosts[i];
                    if (h.length() <= 0)
                        continue;
                    if (Addresses.isIPv4Address(h)) {
                        if (v4)
                            continue;
                        v4 = true;
                    } else if (Addresses.isIPv6Address(h)) {
                        if (v6)
                            continue;
                        v6 = true;
                    } else {
                        int valid = 0;
                        List<byte[]> ips = Addresses.getIPs(h);
                        if (ips != null) {
                            for (byte[] ip : ips) {
                                if (!isValid(ip)) {
                                    if (_log.shouldWarn())
                                        _log.warn("REA2: skipping invalid " + Addresses.toString(ip) + " for " + h);
                                    continue;
                                }
                                if ((v4 && ip.length == 4) || (v6 && ip.length == 16)) {
                                    if (_log.shouldWarn())
                                        _log.warn("REA2: skipping additional " + Addresses.toString(ip) + " for " + h);
                                    continue;
                                }
                                if (ip.length == 4)
                                    v4 = true;
                                else if (ip.length == 16)
                                    v6 = true;
                                valid++;
                                if (_log.shouldDebug())
                                    _log.debug("REA2: adding " + Addresses.toString(ip) + " for " + h);
                                RouterAddress trv = rebuildExternalAddress(ip, port, allowRebuildRouterInfo);
                                if (trv != null)
                                    rv = trv;
                            }
                        }
                        if (valid == 0)
                            _log.error("No valid IPs for configured hostname " + h);
                        continue;
                    }
                    RouterAddress trv = rebuildExternalAddress(h, port, allowRebuildRouterInfo);
                    if (trv != null)
                        rv = trv;
                }
                return rv;
            }
        } else {
            if (!introducersRequired(ipv6)) {
                RouterAddress cur = getCurrentExternalAddress(ipv6);
                if (cur != null)
                    host = cur.getHost();
            }
            if (ipv6 && host == null)
                host = ":";  // special flag, see REA4
        }
        return rebuildExternalAddress(host, port, allowRebuildRouterInfo);
    }

    /**
     *  Update our IPv4 or IPv6 address and optionally tell the router to rebuild and republish the router info.
     *
     *  @param ip new ip valid IPv4 or IPv6 or null
     *  @param port new valid port or -1
     *  @param allowRebuildRouterInfo whether to tell the router
     *  @return the new address if changed, else null
     *  @since IPv6
     */
    private RouterAddress rebuildExternalAddress(byte[] ip, int port, boolean allowRebuildRouterInfo) {
        if (_log.shouldDebug())
            _log.debug("REA3 " + Addresses.toString(ip, port));
        if (ip == null)
            return rebuildExternalAddress((String) null, port, allowRebuildRouterInfo);
        if (isValid(ip))
            return rebuildExternalAddress(Addresses.toString(ip), port, allowRebuildRouterInfo);
        return null;
    }

    /**
     *  Update our IPv4 or IPv6 address and optionally tell the router to rebuild and republish the router info.
     *  FIXME no way to remove an IPv6 address
     *
     *  @param host new validated IPv4 or IPv6 or DNS hostname or null
     *              or ":" to force IPv6 introducer rebuild
     *  @param port new validated port or 0/-1
     *  @param allowRebuildRouterInfo whether to tell the router
     *  @return the new address if changed, else null
     *  @since IPv6
     */
    private RouterAddress rebuildExternalAddress(String host, int port, boolean allowRebuildRouterInfo) {
        synchronized (_rebuildLock) {
            return locked_rebuildExternalAddress(host, port, allowRebuildRouterInfo);
        }
    }

    /**
     *  @param host new validated IPv4 or IPv6 or DNS hostname or null
     *              or ":" to force IPv6 introducer rebuild
     */
    private RouterAddress locked_rebuildExternalAddress(String host, int port, boolean allowRebuildRouterInfo) {
        if (_log.shouldDebug())
            _log.debug("REA4 " + host + ' ' + port, new Exception());
        boolean isIPv6 = host != null && host.contains(":");
        if (isIPv6 && host.equals(":"))
            host = null;
        boolean weAreFirewalled = _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.REJECT_UNSOLICITED ||
                                  _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_FIREWALLED_IPV6_OK ||
                                  _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_FIREWALLED_IPV6_UNKNOWN ||
                                  _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_OK_IPV6_FIREWALLED ||
                                  _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_UNKNOWN_IPV6_FIREWALLED ||
                                  _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_DISABLED_IPV6_FIREWALLED;
        OrderedProperties options = new OrderedProperties();
        if (_context.router().isHidden()) {
            // save the external address, since we didn't publish it
            if (port > 0 && host != null) {
                RouterAddress old = getCurrentExternalAddress(isIPv6);
                if (old == null || !host.equals(old.getHost()) || port != old.getPort()) {
                    options.setProperty(UDPAddress.PROP_PORT, String.valueOf(port));
                    options.setProperty(UDPAddress.PROP_HOST, host);
                    RouterAddress local = new RouterAddress(getPublishStyle(), options, SSU_OUTBOUND_COST);
                    replaceCurrentExternalAddress(local, isIPv6);
                    options = new OrderedProperties();
                }
            }
            // As of 0.9.50, make an address with only 4/6 caps
            String caps;
            int mtu;
            TransportUtil.IPv6Config config = getIPv6Config();
            if (config == IPV6_ONLY) {
                caps = CAP_IPV6;
                mtu = getMTU(true);
            } else if (config != IPV6_DISABLED && hasIPv6Address()) {
                caps = CAP_IPV4_IPV6;
                mtu = getMTU(true);
            } else {
                caps = CAP_IPV4;
                mtu = getMTU(false);
            }
            options.setProperty(UDPAddress.PROP_CAPACITY, caps);
            if (mtu != _defaultMTU && mtu > 0)
                options.setProperty(UDPAddress.PROP_MTU, Integer.toString(mtu));
            if (mtu >= PeerState2.MIN_MTU || mtu == 0)
                addSSU2Options(options);
            RouterAddress current = getCurrentAddress(false);
            RouterAddress addr = new RouterAddress(getPublishStyle(), options, SSU_OUTBOUND_COST);
            if (!addr.deepEquals(current)) {
                if (_log.shouldInfo())
                    _log.info("Address rebuilt: " + addr, new Exception());
                replaceAddress(addr);
                if (allowRebuildRouterInfo)
                    rebuildRouterInfo();
            } else {
                addr = null;
            }
            _needsRebuild = false;
            return addr;
        }

        boolean directIncluded;
        // DNS name assumed IPv4
        boolean introducersRequired = introducersRequired(isIPv6);
        if (!introducersRequired && allowDirectUDP() && port > 0 && host != null) {
            options.setProperty(UDPAddress.PROP_PORT, String.valueOf(port));
            options.setProperty(UDPAddress.PROP_HOST, host);
            directIncluded = true;
        } else {
            directIncluded = false;
        }

        boolean introducersIncluded = false;
        if (introducersRequired) {
            // intro manager now sorts introducers, so
            // deepEquals() below will not fail even with same introducers.
            // Was only a problem when we had very very few peers to pick from.
            RouterAddress current = getCurrentAddress(isIPv6);
            int found = _introManager.pickInbound(current, isIPv6, options, PUBLIC_RELAY_COUNT);
            if (found > 0) {
                if (_log.shouldInfo())
                    _log.info("Selected " + (isIPv6 ? "IPv6 " : "") + "introducers: " + found);
                long now = _context.clock().now();
                if (isIPv6)
                    _v6IntroducersSelectedOn = now;
                else
                    _v4IntroducersSelectedOn = now;
                introducersIncluded = true;
            } else {
                // logged elsewhere
                //if (_log.shouldWarn())
                //    _log.warn("ipv6? " + isIPv6 + " no introducers");
            }
        }

        // if we have explicit external addresses, they had better be reachable
        String caps;
        if (!canTestAsCharlie(isIPv6)) {
            // we could still be a Bob, but we don't have separate caps for Bob and Charlie
            caps = isIPv6 ? CAP_IPV6 : CAP_IPV4;
        } else if (introducersRequired || !canIntroduce(isIPv6)) {
            if (!directIncluded) {
                if (isIPv6) {caps = CAP_TESTING_6;}
                else {caps = CAP_TESTING_4;}
            } else {caps = CAP_TESTING;}
        } else {caps = CAP_TESTING_INTRO;}
        options.setProperty(UDPAddress.PROP_CAPACITY, caps);

        // MTU since 0.9.2
        int mtu = getMTU(isIPv6);
        if (mtu != _defaultMTU && mtu > 0)
            options.setProperty(UDPAddress.PROP_MTU, Integer.toString(mtu));

        if (directIncluded || introducersIncluded) {
            // SSU seems to regulate at about 85%, so make it a little higher.
            // If this is too low, both NTCP and SSU always have incremented cost and
            // the whole mechanism is not helpful.
            int cost = DEFAULT_COST;
            if (ADJUST_COST && !haveCapacity(91))
                cost += CONGESTION_COST_ADJUSTMENT;
            if (introducersIncluded)
                cost += 2;
            if (isIPv6) {
                TransportUtil.IPv6Config config = getIPv6Config();
                if (config == IPV6_PREFERRED)
                    cost--;
                else if (config == IPV6_NOT_PREFERRED)
                    cost++;
            }
            if (mtu >= PeerState2.MIN_MTU || mtu == 0)
                addSSU2Options(options);
            RouterAddress addr = new RouterAddress(getPublishStyle(), options, cost);

            RouterAddress current = getCurrentAddress(isIPv6);
            boolean wantsRebuild = !addr.deepEquals(current);

            // save the external address, even if we didn't publish it
            if (port > 0 && host != null) {
                RouterAddress local;
                if (directIncluded) {
                    local = addr;
                } else {
                    OrderedProperties localOpts = new OrderedProperties();
                    localOpts.setProperty(UDPAddress.PROP_PORT, String.valueOf(port));
                    localOpts.setProperty(UDPAddress.PROP_HOST, host);
                    local = new RouterAddress(getPublishStyle(), localOpts, cost);
                }
                replaceCurrentExternalAddress(local, isIPv6);
            }

            if (wantsRebuild) {
                if (_log.shouldInfo())
                    _log.info("Address rebuilt\n* " + addr);
                replaceAddress(addr);
                if (!isIPv6 &&
                    getCurrentAddress(true) == null &&
                    getIPv6Config() != IPV6_DISABLED &&
                    hasIPv6Address()) {
                    // Also make an empty "6" address
                    OrderedProperties opts = new OrderedProperties();
                    opts.setProperty(UDPAddress.PROP_CAPACITY, CAP_IPV6);
                    mtu = getMTU(true);
                    if (mtu != _defaultMTU && mtu > 0)
                        opts.setProperty(UDPAddress.PROP_MTU, Integer.toString(mtu));
                    addSSU2Options(opts);
                    RouterAddress addr6 = new RouterAddress(getPublishStyle(), opts, SSU_OUTBOUND_COST);
                    replaceAddress(addr6);
                }
                // warning, this calls back into us with allowRebuildRouterInfo = false,
                // via CSFI.createAddresses->TM.getAddresses()->updateAddress()->REA
                if (allowRebuildRouterInfo)
                    rebuildRouterInfo();
            } else {
                addr = null;
            }

            _needsRebuild = false;
            return addr;
        } else {
            long uptime = _context.router().getUptime();
            if (_log.shouldWarn() && uptime > 3*60*1000) {
                _log.warn("Failed to rebuild our " + (isIPv6 ? "IPv6 " : "") + "SSU address" + (introducersRequired ? " -> Need introducers" : ""));
            }
            _needsRebuild = true;
            // save the external address, even if we didn't publish it
            if (port > 0 && host != null) {
                OrderedProperties localOpts = new OrderedProperties();
                localOpts.setProperty(UDPAddress.PROP_PORT, String.valueOf(port));
                localOpts.setProperty(UDPAddress.PROP_HOST, host);
                RouterAddress local = new RouterAddress(getPublishStyle(), localOpts, DEFAULT_COST);
                replaceCurrentExternalAddress(local, isIPv6);
            }
            // Make an empty "4" or "6" address
            OrderedProperties opts = new OrderedProperties();
            opts.setProperty(UDPAddress.PROP_CAPACITY, isIPv6 ? CAP_IPV6 : CAP_IPV4);
            if (mtu != _defaultMTU && mtu > 0)
                opts.setProperty(UDPAddress.PROP_MTU, Integer.toString(mtu));
            if (mtu >= PeerState2.MIN_MTU || mtu == 0)
                addSSU2Options(opts);
            RouterAddress addr = new RouterAddress(getPublishStyle(), opts, SSU_OUTBOUND_COST);
            RouterAddress current = getCurrentAddress(isIPv6);
            boolean wantsRebuild = !addr.deepEquals(current);
            if (!wantsRebuild)
                return null;
            replaceAddress(addr);
            if (allowRebuildRouterInfo)
                rebuildRouterInfo();
            return addr;
        }
    }

    /**
     *  Simple storage of IP and port, since
     *  we don't put them in the real, published RouterAddress anymore
     *  if we are firewalled.
     *
     *  Side effect: Sets our MTU
     *
     *  Caller must sync on _rebuildLock
     *
     *  @since 0.9.18
     */
    private void replaceCurrentExternalAddress(RouterAddress ra, boolean isIPv6) {
        if (isIPv6)
            _currentOurV6Address = ra;
        else
            _currentOurV4Address = ra;
        try {
            InetAddress ia = InetAddress.getByName(ra.getHost());
            setMTU(ia);
        } catch (UnknownHostException uhe) {}
    }

    /**
     *  @since 0.9.43 pulled out of locked_rebuildExternalAddress
     */
    private void removeExternalAddress(boolean isIPv6, boolean allowRebuildRouterInfo) {
        synchronized (_rebuildLock) {
            if (getCurrentAddress(isIPv6) != null) {
                // We must remove current address, otherwise the user will see
                // "firewalled with inbound NTCP enabled" warning in console.
                // Remove the v4/v6 address only
                removeAddress(isIPv6);
                // warning, this calls back into us with allowRebuildRouterInfo = false,
                // via CSFI.createAddresses->TM.getAddresses()->updateAddress()->REA
                if (allowRebuildRouterInfo)
                    rebuildRouterInfo();
            }
        }
    }

    /**
     *  Avoid deadlocks part 999
     *  @since 0.9.49
     */
    private void rebuildRouterInfo() {
        (new RebuildEvent()).schedule(0);
    }

    /**
     *  @since 0.9.49
     */
    private class RebuildEvent extends SimpleTimer2.TimedEvent {
        /**
         *  Caller must schedule
         */
        public RebuildEvent() {
            super(_context.simpleTimer2());
        }
        public void timeReached() {
            _context.router().rebuildRouterInfo(true);
        }
    }


    /**
     *  Simple fetch of stored IP and port, since
     *  we don't put them in the real, published RouterAddress anymore
     *  if we are firewalled.
     *
     *  @since 0.9.18, public for PacketBuilder and TransportManager since 0.9.50
     */
    public RouterAddress getCurrentExternalAddress(boolean isIPv6) {
        // deadlock thru here ticket #1699
        synchronized (_rebuildLock) {
            return isIPv6 ? _currentOurV6Address : _currentOurV4Address;
        }
    }

    /**
     *  Replace then tell NTCP that we changed.
     *
     *  @param address the new address or null to remove all
     */
    @Override
    protected void replaceAddress(RouterAddress address) {
        super.replaceAddress(address);
        _context.commSystem().notifyReplaceAddress(address);
    }

    /**
     *  Remove then tell NTCP that we changed.
     *
     *  @since 0.9.20
     */
    @Override
    protected void removeAddress(RouterAddress address) {
        super.removeAddress(address);
        _context.commSystem().notifyRemoveAddress(address);
    }

    /**
     *  Remove then tell NTCP that we changed.
     *
     *  @since 0.9.20
     */
    @Override
    protected void removeAddress(boolean ipv6) {
        super.removeAddress(ipv6);
        if (ipv6)
            _lastInboundIPv6 = 0;
        _context.commSystem().notifyRemoveAddress(ipv6);
    }

    /**
     *  Do we require introducers?
     */
    private boolean introducersRequired(boolean ipv6) {
        if (_context.router().isHidden())
            return false;
        Status status = getReachabilityStatus();
        TransportUtil.IPv6Config config = getIPv6Config();
        if (ipv6) {
            if (!_haveIPv6Address)
                return false;
            if (config == IPV6_DISABLED)
                return false;
            if (isIPv6Firewalled())
                return true;
            switch (status) {
                case REJECT_UNSOLICITED:
                case DIFFERENT:
                case IPV4_OK_IPV6_FIREWALLED:
                case IPV4_UNKNOWN_IPV6_FIREWALLED:
                    if (_log.shouldDebug())
                        _log.debug("Require IPv6 introducers, status is " + status);
                    return true;
            }
        } else {
            if (config == IPV6_ONLY)
                return false;
            if (isIPv4Firewalled())
                return true;
            switch (status) {
                case REJECT_UNSOLICITED:
                case DIFFERENT:
                case IPV4_FIREWALLED_IPV6_OK:
                case IPV4_FIREWALLED_IPV6_UNKNOWN:
                case IPV4_SNAT_IPV6_OK:
                case IPV4_SNAT_IPV6_UNKNOWN:
                if (_log.shouldDebug())
                    _log.debug("IPv4 Introducers required because our status is [" + status + "]");
                return true;
            }
        }
        if (!allowDirectUDP()) {
            if (_log.shouldDebug())
                _log.debug("Introducers required because we do not allow direct UDP connections");
            return true;
        }
        return false;
    }

    /**
     *  MIGHT we require introducers?
     *  This is like introducersRequired, but if we aren't sure, this returns true.
     *  Used only by EstablishmentManager.
     *
     *  @since 0.9.24
     */
    boolean introducersMaybeRequired(boolean ipv6) {
        if (_context.router().isHidden())
            return false;
        //if (ipv6) return false;
        Status status = getReachabilityStatus();
        TransportUtil.IPv6Config config = getIPv6Config();
        if (ipv6) {
            if (!_haveIPv6Address)
                return false;
            if (config == IPV6_DISABLED)
                return false;
            if (isIPv6Firewalled())
                return true;
            switch (status) {
                case REJECT_UNSOLICITED:
                case DIFFERENT:
                case IPV4_OK_IPV6_FIREWALLED:
                case IPV4_UNKNOWN_IPV6_FIREWALLED:
                case IPV4_OK_IPV6_UNKNOWN:
                case IPV4_FIREWALLED_IPV6_UNKNOWN:
                case UNKNOWN:
                    return _introManager.introducerCount(true) < 3 * MIN_INTRODUCER_POOL;
            }
        } else {
            if (config == IPV6_ONLY)
                return false;
            if (isIPv4Firewalled())
                return true;
            switch (status) {
                case REJECT_UNSOLICITED:
                case DIFFERENT:
                case IPV4_FIREWALLED_IPV6_OK:
                case IPV4_FIREWALLED_IPV6_UNKNOWN:
                case IPV4_UNKNOWN_IPV6_OK:
                case IPV4_UNKNOWN_IPV6_FIREWALLED:
                case UNKNOWN:
                return _introManager.introducerCount(false) < 3 * MIN_INTRODUCER_POOL;

            }
        }
        return !allowDirectUDP();
    }

    /**
     *  For EstablishmentManager.
     *
     *  @since 0.9.3
     */
    boolean canIntroduce(boolean ipv6) {
        // we don't expect inbound connections when hidden, but it could happen
        // Don't offer if we are approaching max connections. While Relay Intros do not
        // count as connections, we have to keep the connection to this peer up longer if
        // we are offering introductions.
        return
            !SystemVersion.isAndroid() &&
            (!_context.router().isHidden()) &&
            (!introducersRequired(ipv6)) &&
            haveCapacity() &&
            (!_context.netDb().floodfillEnabled()) &&
            (!ipv6 || _haveIPv6Address) &&
            ((!ipv6 && getIPv6Config() != IPV6_ONLY) ||
             (ipv6 && getIPv6Config() != IPV6_DISABLED)) &&
            _introManager.introducedCount() < IntroductionManager.MAX_OUTBOUND &&
            _introManager.introducedCount() < getMaxConnections() / 4;
    }

    /** default true */
    private boolean allowDirectUDP() {
        return _context.getBooleanPropertyDefaultTrue(PROP_ALLOW_DIRECT);
    }

    String getPacketHandlerStatus() {
        PacketHandler handler = _handler;
        if (handler != null)
            return handler.getHandlerStatus();
        else
            return "";
    }

    /** @since IPv6 */
    PacketHandler getPacketHandler() {
        return _handler;
    }

    public void failed(OutboundMessageState msg) { failed(msg, true); }

    void failed(OutboundMessageState msg, boolean allowPeerFailure) {
        if (msg == null) return;
        OutNetMessage m = msg.getMessage();
        if ( allowPeerFailure && (msg.getPeer() != null) &&
             ( (msg.getMaxSends() >= OutboundMessageFragments.MAX_VOLLEYS) ||
               (msg.isExpired())) ) {
            int consecutive = msg.getPeer().incrementConsecutiveFailedSends();
            if (_log.shouldInfo())
                _log.info("Consecutive failure #" + consecutive
                          + msg.toString()
                          + " to " + msg.getPeer());
            if (consecutive < MAX_CONSECUTIVE_FAILED ||
                _context.clock().now() - msg.getPeer().getLastSendFullyTime() <= 60*1000) {
                // ok, a few conseutive failures, but we /are/ getting through to them
            } else {
                _context.statManager().addRateData("udp.dropPeerConsecutiveFailures", consecutive, msg.getPeer().getInactivityTime());
                sendDestroy(msg.getPeer(), SSU2Util.REASON_FRAME_TIMEOUT);
                dropPeer(msg.getPeer(), true, "too many failures");
            }
        } else {
            if (_log.shouldDebug())
                _log.debug("Failed sending " + msg + " to " + msg.getPeer());
        }
        //noteSend(msg, false);
        if (m != null)
            super.afterSend(m, false);
    }

    public void failed(OutNetMessage msg, String reason) {
        if (msg == null) return;
        if (_log.shouldInfo())
            _log.info("Send failed: " + reason + msg);

        if (_context.messageHistory().getDoLog())
            _context.messageHistory().sendMessage(msg.getMessageType(), msg.getMessageId(), msg.getExpiration(),
                                              msg.getTarget().getIdentity().calculateHash(), false, reason);
        super.afterSend(msg, false);
    }

    public void succeeded(OutboundMessageState msg) {
        if (msg == null) return;
        if (_log.shouldDebug())
            _log.debug("Sending message succeeded: " + msg);
        OutNetMessage m = msg.getMessage();
        if (m != null)
            super.afterSend(m, true);
    }

    public int countPeers() {
            return _peersByIdent.size();
    }

    /**
     * Returns statistics on the number of connected peers, broken down by:
     * &lt;ul&gt;
     *     &lt;li&gt;Protocol version (SSU1 or SSU2)&lt;/li&gt;
     *     &lt;li&gt;Address family (IPv4 or IPv6)&lt;/li&gt;
     *     &lt;li&gt;Connection direction (inbound or outbound)&lt;/li&gt;
     * &lt;/ul&gt;
     *
     * @return an array of 8 integers representing peer counts for each category
     * @since 0.9.57
     */
    public int[] getPeerCounts() {
        int[] rv = new int[8];
        long old = _context.clock().now() - 60*1000;
        for (PeerState peer : _peersByIdent.values()) {
            if ((peer.getMessagesReceived() > 0 && peer.getLastReceiveTime() >= old) ||
                (peer.getMessagesSent() > 0 && peer.getLastSendTime() >= old)) {
                int idx = 0;
                if (peer.getVersion() > 1)
                    idx += 4;
                if (peer.isIPv6())
                    idx += 2;
                if (!peer.isInbound())
                    idx++;
                rv[idx]++;
            }
        }
        return rv;
    }

    public int countActivePeers() {
        long old = _context.clock().now() - 60*1000;
        int active = 0;
        for (PeerState peer : _peersByIdent.values()) {
            // PeerState initializes times at construction, so check message count also
            if ((peer.getMessagesReceived() > 0 && peer.getLastReceiveTime() >= old) ||
                (peer.getMessagesSent() > 0 && peer.getLastSendTime() >= old)) {
                active++;
            }
        }
        return active;
    }

    public int countActiveSendPeers() {
        long old = _context.clock().now() - 60*1000;
        int active = 0;
        for (PeerState peer : _peersByIdent.values()) {
                if (peer.getLastSendFullyTime() >= old)
                    active++;
            }
        return active;
    }

    @Override
    public boolean isEstablished(Hash dest) {
        return _peersByIdent.containsKey(dest);
    }

    /**
     *  @since 0.9.3
     */
    @Override
    public boolean isBacklogged(Hash dest) {
        PeerState peer =  _peersByIdent.get(dest);
        return peer != null && peer.isBacklogged();
    }

    /**
     * Tell the transport that we may disconnect from this peer.
     * This is advisory only.
     *
     * @since 0.9.24
     */
    @Override
    public void mayDisconnect(final Hash peer) {
        final PeerState ps =  _peersByIdent.get(peer);
        if (ps != null &&
            ps.getWeRelayToThemAs() <= 0 &&
            (ps.getTheyRelayToUsAs() <= 0 || ps.getIntroducerTime() < _context.clock().now() - 2*60*60*1000) &&
            ps.getMessagesReceived() <= 2 && ps.getMessagesSent() <= 2) {
            ps.setMayDisconnect();
        }
    }

    /**
     * Tell the transport to disconnect from this peer.
     *
     * @since 0.9.38
     */
    public void forceDisconnect(Hash peer) {
        PeerState ps =  _peersByIdent.get(peer);
        boolean isBanned = _context.banlist().isBanlisted(peer);
        boolean isBannedHard = _context.banlist().isBanlistedForever(peer);
        boolean isBlocklisted = _context.blocklist().isBlocklisted(peer);
        if (ps != null) {
            if (_log.shouldWarn()) {
                _log.warn("[SSU] Forcing immediate disconnection of " +
                          (isBannedHard ? "permanently banned " : isBanned ? "temp banned " : isBlocklisted ? "blocklisted " : "") +
                          "Router [" + peer.toBase64().substring(0,6) + "]");
            }
            dropPeer(ps, true, "router");
        }
    }

    public boolean allowConnection() {
        int maxConn = getMaxConnections();
        boolean weAreFirewalled = _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.REJECT_UNSOLICITED ||
                                  _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_FIREWALLED_IPV6_OK ||
                                  _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_FIREWALLED_IPV6_UNKNOWN ||
                                  _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_OK_IPV6_FIREWALLED ||
                                  _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_UNKNOWN_IPV6_FIREWALLED ||
                                  _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_DISABLED_IPV6_FIREWALLED;
        if (weAreFirewalled) {maxConn *= 2.5;} // Increase from 1.5x to 2.5x for firewalled routers
        return _peersByIdent.size() < maxConn;
    }

    /**
     * Return our peer clock skews on this transport.
     * List composed of Long, each element representing a peer skew in seconds.
     * A positive number means our clock is ahead of theirs.
     */
    @Override
    public List<Long> getClockSkews() {
        List<Long> skews = new ArrayList<Long>(_peersByIdent.size());

        // If our clock is way off, we may not have many (or any) successful connections,
        // so try hard in that case to return good data
        boolean includeEverybody = _context.router().getUptime() < 10*60*1000 || _peersByIdent.size() < 10;
        long now = _context.clock().now();
        for (PeerState peer : _peersByIdent.values()) {
            if ((!includeEverybody) && now - peer.getLastReceiveTime() > 60*1000)
                continue; // skip old peers
            if (peer.getRTT() > 1250)
                continue; // Big RTT makes for a poor calculation
            skews.add(Long.valueOf(peer.getClockSkew() / 1000));
        }
        return skews;
    }

    /**
     *  @return null if not configured for SSU2
     *  @since 0.9.54
     */
    X25519KeyFactory getXDHFactory() {
        return _xdhFactory;
    }

    /**
     *  @return null if not configured for SSU2
     *  @since 0.9.54
     */
    PacketBuilder2 getBuilder2() {
        return _packetBuilder2;
    }

    /**
     *  @since 0.9.54
     */
    IntroductionManager getIntroManager() {
        return _introManager;
    }

    /**
     *  @since 0.9.54
     */
    PeerTestManager getPeerTestManager() {
        return _testManager;
    }

    /**
     *  @since 0.9.54
     */
    InboundMessageFragments getInboundFragments() {
        return _inboundFragments;
    }

    /**
     * Does nothing
     * @deprecated as of 0.9.31
     */
    @Override
    @Deprecated
    public void renderStatusHTML(Writer out, String urlBase, int sortFlags) throws IOException {
    }

    /*
     * Cache the bid to reduce object churn
     */
    private class SharedBid extends TransportBid {
        public SharedBid(int ms) { super(); setLatencyMs(ms); }
        @Override
        public Transport getTransport() { return UDPTransport.this; }
        @Override
        public String toString() { return "UDP bid @ " + getLatencyMs(); }
    }

    private class ExpirePeerEvent extends SimpleTimer2.TimedEvent {
        private final List<PeerState> _expireBuffer;
        private volatile boolean _alive;
        private int _runCount;
        private boolean _lastLoopShort;
        // we've seen firewalls change ports after 40 seconds
        private static final long PING_FIREWALL_TIME = 30*1000;
        private static final long PING_FIREWALL_CUTOFF = PING_FIREWALL_TIME / 2;
        // ping 1/4 of the peers every loop
        private static final int SLICES = 4;
        private static final long SHORT_LOOP_TIME = PING_FIREWALL_CUTOFF / (SLICES + 1);
        private static final long LONG_LOOP_TIME = 25*1000;
        private static final long EXPIRE_INCREMENT = 15*1000;
        private static final long EXPIRE_DECREMENT = 45*1000;
        private static final long MAY_DISCON_TIMEOUT = 15*1000;
        private static final long RI_STORE_INTERVAL = 29*60*1000;

        public ExpirePeerEvent() {
            super(_context.simpleTimer2());
            _expireBuffer = new ArrayList<PeerState>();
        }

        public void timeReached() {
            boolean weAreFirewalled = _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.REJECT_UNSOLICITED ||
                                      _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_FIREWALLED_IPV6_OK ||
                                      _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_FIREWALLED_IPV6_UNKNOWN ||
                                      _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_OK_IPV6_FIREWALLED ||
                                      _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_UNKNOWN_IPV6_FIREWALLED ||
                                      _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_DISABLED_IPV6_FIREWALLED;

            // Increase allowed idle time if we are well under allowed connections, otherwise decrease
            // Much more lenient capacity check for firewalled routers to keep peers longer
            boolean haveCap = haveCapacity(weAreFirewalled ? 15 : 33); // 15% instead of 25% for firewalled
            if (haveCap) {
                long inc;
                // don't adjust too quickly if we are looping fast
                if (_lastLoopShort)
                    inc = EXPIRE_INCREMENT * SHORT_LOOP_TIME / LONG_LOOP_TIME;
                else
                    inc = EXPIRE_INCREMENT;
                _expireTimeout = Math.min(_expireTimeout + inc, EXPIRE_TIMEOUT);
            } else {
                long dec;
                if (_lastLoopShort)
                    dec = EXPIRE_DECREMENT * SHORT_LOOP_TIME / LONG_LOOP_TIME;
                else
                    dec = EXPIRE_DECREMENT;
                _expireTimeout = Math.max(_expireTimeout - dec, MIN_EXPIRE_TIMEOUT);
            }

            long now = _context.clock().now();
            long shortInactivityCutoff;
            long longInactivityCutoff;

            if (weAreFirewalled) {
               // Use much more lenient timeouts for firewalled routers to retain peers
               shortInactivityCutoff = now - Math.max(_expireTimeout, 25*60*1000);  // Min 25 minutes
               longInactivityCutoff = now - Math.max(EXPIRE_TIMEOUT, 45*60*1000); // Min 45 minutes
            } else {
               shortInactivityCutoff = now - _expireTimeout;
               longInactivityCutoff = now - EXPIRE_TIMEOUT;
            }

            final long mayDisconCutoff = now - MAY_DISCON_TIMEOUT;
            long pingCutoff = now - (2 * 60*60*1000);
            long pingFirewallCutoff = now - PING_FIREWALL_CUTOFF;
            boolean shouldPingFirewall = !STATUS_OK.contains(_reachabilityStatus);
            int currentListenPort = getListenPort(false);
            boolean pingOneOnly = shouldPingFirewall && getExternalPort(false) == currentListenPort;
            boolean shortLoop = shouldPingFirewall || !haveCap || _context.netDb().floodfillEnabled();
            long loopTime = shortLoop ? SHORT_LOOP_TIME : LONG_LOOP_TIME;
            _lastLoopShort = shortLoop;
            _expireBuffer.clear();
            _runCount++;

                for (PeerState peer : _peersByIdent.values()) {
                    long inactivityCutoff;
                    // if we offered to introduce them, or we used them as introducer in last 2 hours
                    if (peer.getWeRelayToThemAs() > 0 || peer.getIntroducerTime() > pingCutoff) {
                        inactivityCutoff = longInactivityCutoff;
                    } else if ((!haveCap || !peer.isInbound()) &&
                               peer.getMayDisconnect() &&
                               peer.getMessagesReceived() <= 2 && peer.getMessagesSent() <= 2) {
                        //if (_log.shouldInfo())
                        //    _log.info("Possible early disconnect for: " + peer);
                        inactivityCutoff = mayDisconCutoff;
                    } else {
                        inactivityCutoff = shortInactivityCutoff;
                    }
                    if ( (peer.getLastReceiveTime() < inactivityCutoff) && (peer.getLastSendTime() < inactivityCutoff) ) {
                        _expireBuffer.add(peer);
                    } else if (shouldPingFirewall &&
                               ((_runCount ^ peer.hashCode()) & (SLICES - 1)) == 0 &&
                               peer.getLastSendOrPingTime() < pingFirewallCutoff &&
                               peer.getLastReceiveTime() < pingFirewallCutoff) {
                        // ping if firewall is mapping the port to keep port the same...
                        // if the port changes we are screwed
                        if (_log.shouldDebug())
                            _log.debug("Pinging for firewall: " + peer);
                        // don't update or idle time won't be right and peer won't get dropped
                        // TODO if both sides are firewalled should only one ping
                        // or else session will stay open forever?
                        //peer.setLastSendTime(now);
                        UDPPacket ping;
                        try {ping = _packetBuilder2.buildPing((PeerState2) peer);}
                        catch (IOException ioe) {continue;}
                        send(ping);
                        peer.setLastPingTime(now);
                        // If external port is different, it may be changing the port for every
                        // session, so ping all of them. Otherwise only one.
                        if (pingOneOnly)
                            shouldPingFirewall = false;
                    } else {
                        // periodically send our RI
                        long uptime = now - peer.getKeyEstablishedTime();
                        if (uptime >= RI_STORE_INTERVAL) {
                            long mod = uptime % RI_STORE_INTERVAL;
                            if (mod < loopTime) {
                                DatabaseStoreMessage dsm = _establisher.getOurInfo();
                                send(dsm, peer);
                            }
                        }
                    }
                }

            if (!_expireBuffer.isEmpty()) {
                if (_log.shouldDebug())
                    _log.debug("Expiring " + _expireBuffer.size() + " peers");
                for (PeerState peer : _expireBuffer) {
                    sendDestroy(peer, SSU2Util.REASON_TIMEOUT);
                    dropPeer(peer, false, "idle too long");
                    // TODO sleep to limit burst like in destroyAll() ??
                    // but we are on the timer thread...
                    // hopefully this isn't too many at once
                    // ... or only send a max of x, then requeue
                }
                _expireBuffer.clear();
            }

            if (_alive)
                schedule(loopTime);
        }

        public void setIsAlive(boolean isAlive) {
            _alive = isAlive;
            if (isAlive) {
                reschedule(LONG_LOOP_TIME);
            } else {
                cancel();
            }
        }
    }

    /**
     *  IPv4 only
     */
    private void setReachabilityStatus(Status status) {
        setReachabilityStatus(status, false);
    }

    /**
     *  @since 0.9.27
     *  @param isIPv6 Is the change an IPv6 change?
     */
    void setReachabilityStatus(Status status, boolean isIPv6) {
        synchronized (_rebuildLock) {
            locked_setReachabilityStatus(status, isIPv6);
        }
    }

    /**
     *  1) Merge IPv4 or IPv6 newStatus into the current IPv4+IPv6 status
     *  2a) If current status changed, call rebuildExternalAddress()
     *  2b) Otherwise, If we need to retest, call PeerTestEvent.forceRunSoon()
     *
     *  @param isIPv6 Is the change an IPv6 change?
     */
    private void locked_setReachabilityStatus(Status newStatus, boolean isIPv6) {
        Status old = _reachabilityStatus;
        if (newStatus == Status.UNKNOWN) {
            // now that addRemotePeerState() doesn't schedule peer tests like crazy,
            // we need to reschedule here
            boolean runtest = false;
            switch (old) {
                case UNKNOWN:
                    runtest = true;
                    break;

                case IPV4_UNKNOWN_IPV6_OK:
                case IPV4_UNKNOWN_IPV6_FIREWALLED:
                    if (!isIPv6)
                        runtest = true;
                    break;

                case IPV4_OK_IPV6_UNKNOWN:
                case IPV4_DISABLED_IPV6_UNKNOWN:
                case IPV4_FIREWALLED_IPV6_UNKNOWN:
                case IPV4_SNAT_IPV6_UNKNOWN:
                    if (isIPv6)
                        runtest = true;
                    break;
            }
            if (runtest || old != _reachabilityStatusPending) {
                if (_log.shouldWarn())
                    _log.warn("Old status: " + old + " unchanged after update: UNKNOWN, reschedule test soon, ipv6? " + isIPv6);
                // 60 sec, greater than MIN_TEST_FREQUENCY, so that IPv6 will get a chance if IPv4, and vice versa
                _testEvent.forceRunSoon(isIPv6, RateConstants.ONE_MINUTE);
            } else {
                // run a little sooner than usual
                _testEvent.forceRunSoon(isIPv6, 5*60*1000);
            }
            return;
        }
        // merge new status into old
        Status status = Status.merge(old, newStatus);
        _testEvent.setLastTested(isIPv6);
        // now modify if we are IPv6 only
        TransportUtil.IPv6Config config = getIPv6Config();
        if (config == IPV6_ONLY) {
            if (status == Status.IPV4_UNKNOWN_IPV6_OK)
                status = Status.IPV4_DISABLED_IPV6_OK;
            else if (status == Status.IPV4_UNKNOWN_IPV6_FIREWALLED)
                status = Status.IPV4_DISABLED_IPV6_FIREWALLED;
            else if (status == Status.UNKNOWN)
                status = Status.IPV4_DISABLED_IPV6_UNKNOWN;
        }
        if (status != Status.UNKNOWN) {
            // now modify if we have no IPv6 address
            if (_currentOurV6Address == null && !_haveIPv6Address) {
                if (status == Status.IPV4_OK_IPV6_UNKNOWN)
                    status = Status.OK;
                else if (status == Status.IPV4_FIREWALLED_IPV6_UNKNOWN)
                    status = Status.REJECT_UNSOLICITED;
                else if (status == Status.IPV4_SNAT_IPV6_UNKNOWN)
                    status = Status.DIFFERENT;
                // prevent firewalled -> OK -> firewalled+OK
                else if (status == Status.IPV4_FIREWALLED_IPV6_OK)
                    status = Status.REJECT_UNSOLICITED;
                else if (status == Status.IPV4_SNAT_IPV6_OK)
                    status = Status.DIFFERENT;
            }

            if (status != old) {
                // for the following transitions ONLY, require two in a row
                // to prevent thrashing
                if ((STATUS_OK.contains(old) && STATUS_FW.contains(status)) ||
                    (STATUS_OK.contains(status) && STATUS_FW.contains(old)) ||
                    (STATUS_FW.contains(status) && STATUS_FW.contains(old)) ||
                    (!isIPv6 && STATUS_IPV4_UNK.contains(old) && !STATUS_IPV4_UNK.contains(status))) {
                    if (status != _reachabilityStatusPending) {
                        if (_log.shouldWarn())
                            _log.warn("Old status: " + old + "\n* Status (pending confirmation): " + status +
                                      "\n* Caused by update: " + newStatus);
                        _reachabilityStatusPending = status;
                        _testEvent.forceRunSoon(isIPv6);
                        return;
                    }
                }
                _reachabilityStatusUnchanged = 0;
                long now = _context.clock().now();
                _reachabilityStatusLastUpdated = now;
                _reachabilityStatus = status;
            } else {
                _reachabilityStatusUnchanged++;
            }
            _reachabilityStatusPending = status;
        }
        if (status != old) {
            if (_log.shouldWarn())
                _log.warn("Old status: " + old + " New status: " + status +
                          "\n* Caused by update: " + newStatus);
            if (old != Status.UNKNOWN && _context.router().getUptime() > 5*60*1000) {
                _context.router().eventLog().addEvent(EventLog.REACHABILITY,
                _t(old.toStatusString()) + " ➜ " +  _t(status.toStatusString()));
            }
            // Always rebuild when the status changes, even if our address hasn't changed,
            // as rebuildExternalAddress() calls replaceAddress() which calls CSFI.notifyReplaceAddress()
            // which will start up NTCP inbound when we transition to OK.
            if (isIPv6) {
                if (STATUS_IPV6_FW_2.contains(status)) {
                    rebuildExternalAddress(true);   // we must publish i/s/v
                } else if (STATUS_IPV6_FW_2.contains(old) &&
                           STATUS_IPV6_OK.contains(status) &&
                           !explicitAddressSpecified()){
                    RouterAddress ra = _currentOurV6Address;
                    if (ra != null) {
                        String addr = ra.getHost();
                        if (addr != null) {
                            int port = _context.getProperty(PROP_EXTERNAL_PORT, -1);
                            rebuildExternalAddress(addr, port, true);
                        } else if (_log.shouldWarn()) {
                            _log.warn("Not IPv6 firewalled but no address?");
                        }
                    } else if (_log.shouldWarn()) {
                        _log.warn("Not IPv6 firewalled but no address?");
                    }
                }
            } else {
                rebuildExternalAddress(false);
            }
        } else {
            if (newStatus == Status.UNKNOWN && status != _reachabilityStatusPending) {
                // still have something pending, try again
                if (_log.shouldWarn())
                    _log.warn("Old status: " + status + "\n* Status (pending confirmation): " + _reachabilityStatusPending +
                              "\n* Caused by update: " + newStatus);
                _testEvent.forceRunSoon(isIPv6);
            }
            if (_log.shouldInfo())
                _log.info("Status unchanged: " + _reachabilityStatus +
                          " after update: " + newStatus +
                          " (unchanged " + _reachabilityStatusUnchanged + " consecutive times), last updated " +
                          DataHelper.formatDuration(_context.clock().now() - _reachabilityStatusLastUpdated) + " ago");
        }
    }

    private static final String PROP_REACHABILITY_STATUS_OVERRIDE = "i2np.udp.status";

    /**
     * Returns the current reachability status of the transport.
     * This includes both IPv4 and IPv6 reachability states.
     *
     * @return the current status, never null
     */
    public Status getReachabilityStatus() {
        String override = _context.getProperty(PROP_REACHABILITY_STATUS_OVERRIDE);
        if (override != null) {
            if ("ok".equals(override))
                return Status.OK;
            else if ("err-reject".equals(override))
                return Status.REJECT_UNSOLICITED;
            else if ("err-different".equals(override))
                return Status.DIFFERENT;
        }
        return _reachabilityStatus;
    }

    /**
     *  Is IPv4 Symmetric NATted?
     *  @since 0.9.57
     */
    boolean isSymNatted() {
        return STATUS_IPV4_SYMNAT.contains(getReachabilityStatus());
    }

    /**
     *  Can we be a Charlie right now?
     *  @return true if we can participate
     *  @since 0.9.57
     */
    boolean canTestAsCharlie(boolean ipv6) {
        Status status = getReachabilityStatus();
        if (ipv6)
            return !STATUS_IPV6_NO_TEST.contains(status);
        return !STATUS_IPV4_NO_TEST.contains(status);
    }

    /**
     *  Pick a Bob (if we are Alice) or a Charlie (if we are Bob).
     *
     *  For Bob (as called from PeerTestEvent below), returns an established IPv4/v6 peer.
     *  While the protocol allows Alice to select an unestablished Bob, we don't support that.
     *
     *  For Charlie (as called from PeerTestManager), returns an established IPv4 or IPv6 peer.
     *  (doesn't matter how Bob and Charlie communicate)
     *
     *  Any returned peer must advertise an IPv4 address to prove it is IPv4-capable.
     *  Ditto for v6.
     *
     *  @param peerRole The role of the peer we are looking for, BOB or CHARLIE only (NOT our role)
     *  @param version 1 or 2 for role CHARLIE; ignored for role BOB
     *  @param isIPv6 true to get a v6-capable peer back
     *  @param dontInclude may be null
     *  @return peer or null
     */
    PeerState pickTestPeer(PeerTestState.Role peerRole, int version, boolean isIPv6, RemoteHostId dontInclude) {
        if (peerRole == ALICE)
            throw new IllegalArgumentException();
        // if we are or may be symmetric natted, require SSU2 so we don't let an SSU1 test change the state
        boolean requireV2 = peerRole == BOB && !isIPv6 &&
                            (isSymNatted() || STATUS_IPV4_SYMNAT.contains(_reachabilityStatusPending));
        List<PeerState> peers = new ArrayList<PeerState>(_peersByIdent.values());
        for (Iterator<PeerState> iter = new RandomIterator<PeerState>(peers); iter.hasNext(); ) {
            PeerState peer = iter.next();
            if (peerRole == BOB) {
                version = peer.getVersion();
                if (version == 1) {
                    if (requireV2)
                        continue;
                } else {
                    // we must know our IP/port
                    PeerState2 bob = (PeerState2) peer;
                    if (bob.getOurIP() == null || bob.getOurPort() <= 0)
                        continue;
                }
            } else {
                // charlie must be same version
                if (peer.getVersion() != version)
                    continue;
            }
            if ( (dontInclude != null) && (dontInclude.equals(peer.getRemoteHostId())) )
                continue;
            // enforce IPv4/v6 connection if we are ALICE looking for a BOB
            byte[] ip = peer.getRemoteIP();
            if (peerRole == BOB) {
                if (isIPv6) {
                    if (ip.length != 16)
                        continue;
                } else {
                    if (ip.length != 4)
                        continue;
                }
            }
            // enforce IPv4/v6 advertised for all
            RouterInfo peerInfo = _context.netDb().lookupRouterInfoLocally(peer.getRemotePeer());
            if (peerInfo == null)
                continue;
            String v = peerInfo.getVersion();
            if (VersionComparator.comp(v, MIN_PEER_TEST_VERSION) < 0)
                continue;
            ip = null;
            List<RouterAddress> addrs = getTargetAddresses(peerInfo);
            for (RouterAddress addr : addrs) {
                // get the right address
                String style = addr.getTransportStyle();
                if (version == 1) {
                    if (style.equals("SSU2"))
                        continue;
                } else {
                    if (style.equals("SSU") && !"2".equals(addr.getOption("v")))
                        continue;
                }

                byte[] rip = addr.getIP();
                if (rip != null) {
                    if (isIPv6) {
                        if (rip.length != 16)
                            continue;
                    } else {
                        if (rip.length != 4)
                            continue;
                    }
                    // as of 0.9.27, we trust the 'B' cap for IPv6
                    String caps = addr.getOption(UDPAddress.PROP_CAPACITY);
                    if (caps != null && caps.contains(CAP_TESTING)) {
                        ip = rip;
                        break;
                    }
                }
            }
            if (ip == null)
                continue;
            if (isTooClose(ip))
                continue;
            return peer;
        }
        return null;
    }

    /**
     *  Periodically ping the introducers, split out since we need to do it faster
     *  than we rebuild our address.
     *  @since 0.8.11
     */
    private class PingIntroducers implements SimpleTimer.TimedEvent {
        public void timeReached() {
            if (introducersRequired(false) || introducersRequired(true))
                _introManager.pingIntroducers();
        }
    }

    /**
     *  For PeerStateDestroyed, to kill the timers on overflow, else the memory won't be freed.
     *  @since 0.9.57
     */
    private static class DestroyedCache extends LHMCache<Long, PeerStateDestroyed> {

        public DestroyedCache(int max) {
            super(max);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, PeerStateDestroyed> eldest) {
            boolean rv = super.removeEldestEntry(eldest);
            if (rv) {
                eldest.getValue().kill();
            }
            return rv;
        }
    }

    /**
     * Reduce the UDPPacket cache size in response to memory pressure.
     * This reduces the cache to its minimum size rather than clearing completely,
     * maintaining some performance while freeing memory.
     *
     * @since 0.9.68+
     */
    public static void reduceCacheSize() {
        UDPPacket.reduceCacheSize();
    }

}
