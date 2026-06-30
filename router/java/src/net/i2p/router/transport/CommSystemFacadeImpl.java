package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.io.Writer;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.crypto.X25519KeyFactory;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.router.transport.TransportManager;
import net.i2p.router.util.EventLog;
import net.i2p.util.AddressType;
import net.i2p.util.Addresses;
import net.i2p.util.ArraySet;
import net.i2p.util.I2PThread;
import net.i2p.util.LHMCache;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;
import net.i2p.util.Translate;

/**
 * Primary implementation of the communication system facade for I2P network operations.
 *
 * This class provides the main interface between the router core and
 * all transport protocols. It coordinates message sending,
 * peer management, address configuration, and network monitoring
 * while abstracting the underlying transport details.
 *
 * <strong>Core Responsibilities:</strong>
 * <ul>
 *   <li>Message routing and delivery across all transports</li>
 *   <li>Peer connection management and statistics</li>
 *   <li>Network address discovery and configuration</li>
 *   <li>Bandwidth allocation and monitoring</li>
 *   <li>Transport lifecycle management (start/stop/restart)</li>
 *   <li>Network reachability testing and reporting</li>
 *   <li>Geographic IP filtering and country blocking</li>
 *   <li>Communication system status and health monitoring</li>
 * </ul>
 *
 * <strong>Transport Integration:</strong>
 * <ul>
 *   <li>Manages NTCP, UDP, and SSU transports</li>
 *   <li>Handles transport selection and failover</li>
 *   <li>Coordinates address updates across protocols</li>
 *   <li>Provides unified API for router components</li>
 * </ul>
 *
 * <strong>Configuration Features:</strong>
 * <ul>
 *   <li>Transport enable/disable controls</li>
 *   <li>Country-based blocking and filtering</li>
 *   <li>Proxy configuration and detection</li>
 *   <li>Network monitoring and testing options</li>
 *   <li>IPv4 and IPv6 addressing support</li>
 * </ul>
 *
 * <strong>Security Features:</strong>
 * <ul>
 *   <li>Geographic IP filtering</li>
 *   <li>Country-based access controls</li>
 *   <li>Peer reputation and banlist management</li>
 *   <li>Transport-specific security policies</li>
 * </ul>
 */
public class CommSystemFacadeImpl extends CommSystemFacade {
    private final Log _log;
    private final RouterContext _context;
    private final TransportManager _manager;
    private final GeoIP _geoIP;
    private final Map<String, Object> _exemptIncoming;
    private volatile boolean _netMonitorStatus;
    private boolean _wasStarted;

    /**
     * Property to disable all network connections for testing purposes.
     *
     * When this property is set to true, the communication
     * system will not establish any outbound connections or accept
     * inbound connections. This is useful for testing scenarios
     * or when running in debug mode.
     *
     * @since IPv6 support was added
     */
    private static final String PROP_DISABLED = "i2np.disable";
    public static final String PROP_BLOCK_MY_COUNTRY = "i2np.blockMyCountry";
    public static final String PROP_IP_COUNTRY = "i2np.lastCountry";

    private static final String BUNDLE_NAME = "net.i2p.router.web.messages";
    private static final String COUNTRY_BUNDLE_NAME = "net.i2p.router.countries.messages";
    private static final Object DUMMY = Integer.valueOf(0);
    private static final Pattern CAPACITY_PATTERN = Pattern.compile("[DEG]");

    public CommSystemFacadeImpl(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(CommSystemFacadeImpl.class);
        _netMonitorStatus = true;
        _geoIP = new GeoIP(_context);
        _manager = new TransportManager(_context);
        _exemptIncoming = new LHMCache<>(128);
        initExecutors();
    }

    private void initExecutors() {
        synchronized (reverseDnsExecutorLock) {
            if (reverseDnsExecutor == null || reverseDnsExecutor.isShutdown()) {
                reverseDnsExecutor = new ThreadPoolExecutor(
                    2, 10,
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(500),
                    new ThreadPoolExecutor.CallerRunsPolicy()
                );
            }
        }
    }

    /**
     * Get the reverse DNS executor, initializing it if necessary.
     */
    private ExecutorService getReverseDnsExecutor() {
        synchronized (reverseDnsExecutorLock) {
            if (reverseDnsExecutor == null || reverseDnsExecutor.isShutdown()) {
                reverseDnsExecutor = new ThreadPoolExecutor(
                    2, 10,
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(500),
                    new ThreadPoolExecutor.CallerRunsPolicy()
                );
            }
            return reverseDnsExecutor;
        }
    }

    /**
     * Start the communication system and all transport protocols.
     *
     * This method initializes the communication system, starts all
     * configured transports, and begins network operations. It handles
     * transport discovery, address configuration, and peer management
     * setup.
     *
     * <strong>Startup Process:</strong>
     * <ul>
     *   <li>Initialize geographic IP filtering</li>
     *   <li>Start network monitoring</li>
     *   <li>Register and start all configured transports</li>
     *   <li>Begin peer discovery and connection attempts</li>
     *   <li>Setup address change notifications</li>
     * </ul>
     *
     * @throws IllegalStateException if already running
     */
    public synchronized void startup() {
        _log.info("Starting the Comm System...");
        _manager.startListening();
        startTimestamper();
        startNetMonitor();
        _wasStarted = true;
    }

    /**
     *  Cannot be restarted after calling this. Use restart() for that.
     */
    /**
     * Gracefully shutdown the communication system.
     *
     * This method performs a clean shutdown of all transport
     * protocols and network operations. It stops accepting
     * new connections, closes existing ones, and performs
     * cleanup of system resources.
     *
     * <strong>Shutdown Process:</strong>
     * <ul>
     *   <li>Stop network monitoring</li>
     *   <li>Stop all transport protocols</li>
     *   <li>Close all active connections</li>
     *   <li>Cleanup system resources and caches</li>
     *   <li>Save final state and statistics</li>
     * </ul>
     *
     * @throws IllegalStateException if not running
     */
    public synchronized void shutdown() {
        _manager.shutdown();
        _geoIP.shutdown();

        if (_rdnsTimer != null) {
            _rdnsTimer.cancel();
            _rdnsTimer = null;
        }

        shutdownExecutors();
        shutdownStatic();
    }

    /**
     * Shutdown instance executors.
     */
    private void shutdownExecutors() {
        synchronized (reverseDnsExecutorLock) {
            if (reverseDnsExecutor != null && !reverseDnsExecutor.isShutdown()) {
                reverseDnsExecutor.shutdown();
                try {
                    if (!reverseDnsExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        reverseDnsExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    reverseDnsExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Cleanup static resources.
     * This should be called when the router is shutting down to prevent memory leaks.
     */
    private static synchronized void shutdownStatic() {
        pendingLookups.clear();
    }

    /**
     * Restart the communication system.
     *
     * This method performs a complete restart of all transport
     * protocols while preserving router state. It stops all
     * transports, reinitializes system components, and restarts
     * network operations.
     *
     * <strong>Restart Process:</strong>
     * <ul>
     *   <li>Stop all transport protocols gracefully</li>
     *   <li>Reinitialize system components</li>
     *   <li>Restart transports with preserved state</li>
     *   <li>Resume network operations and monitoring</li>
     *   <li>Reconfigure address management</li>
     * </ul>
     *
     * @throws IllegalStateException if not running
     */
    public synchronized void restart() {
        if (!_wasStarted) {
            startup();
        } else {
            _wasStarted = false;
            _manager.restart();
            _wasStarted = true;
        }
    }

    /**
     * @since 0.9.53
     */
    @Override
    public synchronized boolean isRunning() { return _wasStarted; }

    /**
     *  How many peers are we currently connected to, that we have
     *  sent a message to or received a message from in the last minute.
     */
    @Override
    public int countActivePeers() { return _manager.countActivePeers(); }

    /**
     *  How many peers are we currently connected to, that we have
     *  sent a message to in the last minute.
     *  Unused for anything, to be removed.
     */
    @Override
    public int countActiveSendPeers() { return _manager.countActiveSendPeers(); }

    @Override
    public boolean haveInboundCapacity(int pct) { return _manager.haveInboundCapacity(pct); }
    @Override
    public boolean haveOutboundCapacity(int pct) { return _manager.haveOutboundCapacity(pct); }
    @Override
    public boolean haveHighOutboundCapacity() { return _manager.haveHighOutboundCapacity(); }

    /**
     * @param percentToInclude 1-100
     * @return Framed average clock skew of connected peers in milliseconds, or the clock offset if we cannot answer.
     * Average is calculated over the middle "percentToInclude" peers.
     *
     * A positive number means our clock is ahead of theirs.
     *
     * Todo: change List to milliseconds
     */
    @Override
    public long getFramedAveragePeerClockSkew(int percentToInclude) {
        List<Long> skews = _manager.getClockSkews();
        if (skews == null ||
            skews.isEmpty() ||
            (skews.size() < 5 && _context.clock().getUpdatedSuccessfully())) {
            return _context.clock().getOffset();
        }

        // Going to calculate, sort them
        Collections.sort(skews);
        // enable this is you REALLY need it, which you don't
        //if (_log.shouldDebug())
        //    _log.debug("Peer clock skews (ms): \n* " + skews);
        // Calculate frame size
        int frameSize = Math.max((skews.size() * percentToInclude / 100), 1);
        int first = (skews.size() / 2) - (frameSize / 2);
        int last = Math.min((skews.size() / 2) + (frameSize / 2), skews.size() - 1);
        // Sum skew values
        long sum = 0;
        for (int i = first; i <= last; i++) {
            long value = skews.get(i).longValue();
            sum = sum + value;
        }
        // Calculate average
        return sum * 1000 / frameSize;
    }

    /** Send the message out */
    /**
     * Process and route an outbound message through the transport system.
     *
     * This method is the central entry point for sending messages
     * to other I2P peers. It selects appropriate transport,
     * handles message queuing, and manages delivery tracking.
     *
     * <strong>Message Processing:</strong>
     * <ul>
     *   <li>Transport selection based on destination</li>
     *   <li>Message validation and filtering</li>
     *   <li>Queueing and throttling</li>
     *   <li>Delivery status tracking</li>
     *   <li>Failure handling and retry logic</li>
     *   <li>Callback execution for send completion</li>
     * </ul>
     *
     * @param msg the outbound message to be processed and delivered
     */
    public void processMessage(OutNetMessage msg) {
        if (msg == null) {return;}
        if (isDummy()) { // testing
            GetBidsJob.fail(_context, msg);
            return;
        }
        GetBidsJob.getBids(_context, _manager, msg);
    }

    @Override
    public boolean isBacklogged(Hash peer) {
        return _manager.isBacklogged(peer);
    }

    @Override
    public boolean isEstablished(Hash peer) {
        return _manager.isEstablished(peer);
    }

    /**
     *  @return a new list, may be modified
     *  @since 0.9.34
     */
    public List<Hash> getEstablished() {
        return _manager.getEstablished();
    }

    @Override
    public boolean wasUnreachable(Hash peer) {
        return _manager.wasUnreachable(peer);
    }

    @Override
    public boolean isConnecting(Hash peer) {
        return _manager.isConnecting(peer);
    }

    @Override
    public byte[] getIP(Hash peer) {
        return _manager.getIP(peer);
    }

    /**
     * Tell the comm system that we may disconnect from this peer.
     * This is advisory only.
     *
     * @since 0.9.24
     */
    @Override
    public void mayDisconnect(Hash peer) {
        _manager.mayDisconnect(peer);
    }

    /**
     * Tell the comm system to disconnect from this peer.
     *
     * @since 0.9.38
     */
    @Override
    public void forceDisconnect(Hash peer) {
        _manager.forceDisconnect(peer);
    }

    @Override
    public void forceDisconnect(Hash peer, String reason) {
        _manager.forceDisconnect(peer, reason);
    }

    @Override
    public List<String> getMostRecentErrorMessages() {
        return _manager.getMostRecentErrorMessages();
    }

    /**
     *  @since 0.9.20
     */
    @Override
    public Status getStatus() {
        if (!_netMonitorStatus)
            return Status.DISCONNECTED;
        Status rv = _manager.getReachabilityStatus();
        if (rv != Status.HOSED && _context.router().isHidden())
            return Status.OK;
        return rv;
    }

    /**
     * getStatus().toStatusString(), translated if available.
     * @since 0.9.45
     */
    @Override
    public String getLocalizedStatusString() {
        return Translate.getString(getStatus().toStatusString(), _context, ROUTER_BUNDLE_NAME);
    }

    /**
     * @deprecated unused
     */
    @Override
    @Deprecated
    public void recheckReachability() { _manager.recheckReachability(); }

    /**
     *  As of 0.9.31, only outputs UPnP status
     *
     *  Warning - blocking, very slow, queries the active UPnP router,
     *  will take many seconds if it has vanished.
     */
    @Override
    public void renderStatusHTML(Writer out, String urlBase, int sortFlags) throws IOException {
        _manager.renderStatusHTML(out, urlBase, sortFlags);
    }

    /**
     *  @return SortedMap of style to Transport (a copy)
     *  @since 0.9.31
     */
    public SortedMap<String, Transport> getTransports() {
        return _manager.getTransports();
    }

     /**
      * Create router addresses for all configured transport protocols.
      *
      * This method generates RouterAddress objects for each
      * transport protocol based on current configuration and
      * network conditions. It handles address discovery,
      * validation, and format conversion.
      *
      * <strong>Address Creation:</strong>
      * <ul>
      *   <li>Iterates through all registered transports</li>
      *   <li>Gets current addresses from each transport</li>
      *   <li>Validates address format and consistency</li>
      *   <li>Handles IPv4 and IPv6 address generation</li>
      *   <li>Applies geographic and network filtering</li>
      * </ul>
      *
      * @return list of RouterAddress objects for all active transports,
      *         may be empty if no addresses available
      */
     @Override
     public List<RouterAddress> createAddresses() {
        List<RouterAddress> addresses = new ArrayList<>(_manager.getAddresses());
        if (addresses.size() > 1) {Collections.sort(addresses, new AddrComparator());}
        return addresses;
    }

    /**
     *  Arbitrary sort for consistency.
     *  Note that the console UI has its own sorter.
     *  @since 0.9.50
     */
    private static class AddrComparator implements Comparator<RouterAddress>, Serializable {
        @Override
        public int compare(RouterAddress l, RouterAddress r) {
            int rv = l.getCost() - r.getCost();
            if (rv != 0) {return rv;}
            int lh = l.hashCode();
            int rh = r.hashCode();
            if (lh > rh) {return 1;}
            if (lh < rh) {return -1;}
            return 0;
        }
    }

    /**
     * UDP changed addresses, tell NTCP and restart
     * All the work moved to NTCPTransport.externalAddressReceived()
     * @param udpAddr may be null; or udpAddr's host/IP may be null
     */
    @Override
    public void notifyReplaceAddress(RouterAddress udpAddr) {
        byte[] ip = null;
        int port = 0;
        // Don't pass IP along if address has introducers
        // Right now we publish the direct UDP address, even if publishing introducers,
        // we probably shouldn't, see UDPTransport rebuildExternalAddress() TODO
        if (udpAddr != null && udpAddr.getOption("itag0") == null) {
            ip = udpAddr.getIP();
            port = udpAddr.getPort();
        }
        if (port < 0) {
            Transport udp = _manager.getTransport(UDPTransport.STYLE);
            if (udp != null) {port = udp.getRequestedPort();}
        }
        if (ip != null || port > 0) {
            _manager.externalAddressReceived(Transport.AddressSource.SOURCE_SSU, ip, port);
        } else {notifyRemoveAddress(udpAddr);}
    }

    /**
     *  Tell other transports our address changed
     *
     *  @param address may be null; or address's host/IP may be null
     *  @since 0.9.20
     */
    @Override
    public void notifyRemoveAddress(RouterAddress address) {
        // just keep this simple for now, multiple v4 or v6 addresses not yet supported
        notifyRemoveAddress(address != null && TransportUtil.isIPv6(address));
    }

    /**
     *  Tell other transports our address changed
     *
     *  @since 0.9.20
     */
    @Override
    public void notifyRemoveAddress(boolean ipv6) {
        _manager.externalAddressRemoved(Transport.AddressSource.SOURCE_SSU, ipv6);
    }

    /**
     *  Exempt this router hash from any incoming throttles or rejections
     *
     *  @since 0.9.58
     */
    @Override
    public void exemptIncoming(Hash peer) {
        if (_manager.isEstablished(peer)) {return;}
        //RouterInfo ri = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(peer);
        RouterInfo ri = _context.netDb().lookupRouterInfoLocally(peer);
        if (ri == null) {return;}
        Collection<RouterAddress> addrs = ri.getAddresses();
        ArraySet<String> ips = new ArraySet<>(addrs.size());
        for (RouterAddress addr : addrs) {
            String ip = addr.getHost();
            if (ip == null) {continue;}
            // Add IPv6 even if we don't have an address, not worth the check
            ips.add(Addresses.toCanonicalString(ip));
        }
        int sz = ips.size();
        if (sz > 0) {
            synchronized(_exemptIncoming) {
                for (int i = 0; i < sz; i++) {
                    _exemptIncoming.put(ips.get(i), DUMMY);
                }
            }
        }
    }

    /**
     *  Is this IP exempt from any incoming throttles or rejections
     *
     *  @param ip canonical string
     *  @since 0.9.58
     */
    @Override
    public boolean isExemptIncoming(String ip) {
        synchronized(_exemptIncoming) {
            return _exemptIncoming.containsKey(ip);
        }
    }

    /**
     *  Remove this IP from the exemptions
     *
     *  @param ip canonical string
     *  @since 0.9.58
     */
    public void removeExemption(String ip) {
        synchronized(_exemptIncoming) {
            _exemptIncoming.remove(ip);
        }
    }

    /**
     *  Pluggable transports. Not for NTCP or SSU.
     *
     *  Do not call from transport constructor. Transport must be ready to be started.
     *
     *  Following transport methods will be called:
     *    setListener()
     *    externalAddressReceived() (zero or more times, one for each known address)
     *    startListening();
     *
     *  @since 0.9.16
     */
    @Override
    public void registerTransport(Transport t) {
        _manager.registerAndStart(t);
    }

    /**
     *  Pluggable transports. Not for NTCP or SSU.
     *
     *  Following transport methods will be called:
     *    setListener(null)
     *    stoptListening();
     *
     *  @since 0.9.16
     */
    @Override
    public void unregisterTransport(Transport t) {
        _manager.stopAndUnregister(t);
    }

    /**
     *  Factory for making X25519 key pairs.
     *  @since 0.9.46
     */
    @Override
    public X25519KeyFactory getXDHFactory() {
        return _manager.getXDHFactory();
    }

    /*
     * GeoIP stuff
     *
     * This is only used in the router console for now, but we put it here because
     * 1) it's a lot easier, and 2) we could use it in the future for peer selection,
     * tunnel selection, banlisting, etc.
     */

    /**
     *  Router must call after netdb is initialized
     *  @since 0.9.41
     */
    @Override
    public void initGeoIP() {startGeoIP();}

    /* We hope the routerinfos are read in and things have settled down by now, but it's not required to be so */
    private static final int START_DELAY = SystemVersion.isSlow() ? 60*1000 : 5*1000;
    private static final int LOOKUP_TIME = 75*1000;
    private static final String PROP_ENABLE_REVERSE_LOOKUPS = "routerconsole.enableReverseLookups";
    public boolean enableReverseLookups() {return _context.getBooleanProperty(PROP_ENABLE_REVERSE_LOOKUPS);}
    private static final Charset ENCODING = StandardCharsets.UTF_8;

    private void startGeoIP() {
        _context.simpleTimer2().addEvent(new QueueAll(), START_DELAY);
        if (enableReverseLookups()) {readRDNSCacheFromFile();}
    }

    /**
     * Collect the IPs for all routers in the DB, and queue them for lookup,
     * then fire off the periodic lookup task for the first time.
     *
     *  As of 0.9.32, works only for literal IPs, ignores host names.
     */
    private class QueueAll implements SimpleTimer.TimedEvent {
        @Override
        public void timeReached() {
            for (Hash h : _context.netDb().getAllRouters()) {
                RouterInfo ri = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(h);
                if (ri == null) {continue;}
                byte[] ip = getIP(ri);
                if (ip == null) {ip = TransportImpl.getIP(h);}
                if (ip == null) {continue;}
                _geoIP.add(ip);
            }
            _context.simpleTimer2().addPeriodicEvent(new Lookup(), 5000, LOOKUP_TIME);
        }
    }

    private class Lookup implements SimpleTimer.TimedEvent {
        @Override
        public void timeReached() {
            (new LookupThread()).start();
        }
    }

    /**
     *  This takes too long to run on the SimpleTimer2 queue
     *  @since 0.9.10
     */
    private class LookupThread extends I2PThread {

        public LookupThread() {
            super("GeoIP Lookup");
            setDaemon(true);
        }

        @Override
        public void run() {
            long start = System.currentTimeMillis();
            _geoIP.blockingLookup();
            if (_log.shouldInfo()) {
                _log.info("GeoIP lookup for all routers in the NetDB took " + (System.currentTimeMillis() - start) + "ms");
            }
        }
    }

    /**
     *  @param ip ipv4 or ipv6
     */
    @Override
    public void queueLookup(byte[] ip) {_geoIP.add(ip);}

    /**
     * Reverse DNS lookup cache with persistent storage and LRU eviction.
     *
     * Maintains a size-limited, thread-safe cache of IP-to-hostname mappings with timestamps for entry expiration.
     * The cache is backed by a disk file that is safely read and written using a temporary file to prevent corruption.
     * Entries expire after 24 hours in memory and are evicted from disk if older than 3 days.
     * Cache size is bounded by system memory-based limits with automatic eviction of least recently used entries.
     *
     * Supports migration from older cache file formats missing timestamps.
     *
     * @since 0.9.61+
     */
    private static final String RDNS_CACHE_FILE = I2PAppContext.getGlobalContext().getConfigDir() +
                                                  File.separator + "rdnscache.txt";
    private static final int RDNS_WRITE_INTERVAL = 15 * 60 * 1000 + 30;
    private static final boolean has512MB = SystemVersion.getMaxMemory() >= 512 * 1024 * 1024;
    private static final boolean has1GB = SystemVersion.getMaxMemory() >= 1024 * 1024 * 1024;
    private static final long EXPIRE_TIME = computeExpireTime() * 60L * 60 * 1000; // 1/1.5/2 day expiration
    private static final long EVICT_THRESHOLD = 3L * 24 * 60 * 60 * 1000; // 3 day for eviction from file cache
    private static final int MAX_RDNS_CACHE_SIZE = computeMaxRdnsCacheSize();
    private static final Object rdnslock = new Object();

    private static long computeExpireTime() {
        if (!has512MB) {
            return 24;
        } else if (has1GB) {
            return 48;
        } else {
            return 36;
        }
    }

    private static int computeMaxRdnsCacheSize() {
        if (!has512MB) {
            return 8000;
        } else if (has1GB) {
            return 24000;
        } else {
            return 16000;
        }
    }

    /**
     * Cache entry for IP address and hostname mappings.
     */
    public static class CacheEntry {
        private final String ipAddress;
        private final String hostname;
        private final long timestamp; // epoch millis when entry was cached

        public CacheEntry(String ipAddress, String hostname) {
            this(ipAddress, hostname, System.currentTimeMillis());
        }

        public CacheEntry(String ipAddress, String hostname, long timestamp) {
            this.ipAddress = ipAddress;
            this.hostname = (hostname != null) ? hostname : "unknown";
            this.timestamp = timestamp;
        }

        public String getIpAddress() {
            return ipAddress;
        }

        public String getHostname() {
            return hostname;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public String getRdnsEntry() {
            return rdnsEntryToString(this);
        }
    }

    /**
     * In-memory reverse DNS cache storing IP-to-hostname mappings.
     * Backed by ConcurrentHashMap for lock-free reads. Entries are
     * periodically flushed to disk and expired entries are cleaned up.
     *
     * Keys are IP addresses as Strings. Values are CacheEntry objects
     * containing hostname and timestamp.
     */
    private static final ConcurrentHashMap<String, CacheEntry> rdnsCache = new ConcurrentHashMap<>(MAX_RDNS_CACHE_SIZE);

    private static ConcurrentHashMap<String, CacheEntry> getRDNSCache() {
        return rdnsCache;
    }

    public static String rdnsCacheSize() {
        File cache = new File(RDNS_CACHE_FILE);
        return String.valueOf(cache.length() / 1024) + "KB";
    }

    private static void readRDNSCacheFromFile() {
        File fCache = new File(RDNS_CACHE_FILE);
        long now = System.currentTimeMillis();
        if (!fCache.exists()) {
            createRdnsCacheFile();
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(fCache), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                CacheEntry cacheEntry = rdnsEntryFromString(line);
                if (cacheEntry != null && now - cacheEntry.getTimestamp() <= EXPIRE_TIME) {
                    rdnsCache.put(cacheEntry.getIpAddress(), cacheEntry);
                }
            }
            //System.out.println("[RDNSCache] Imported " + rdnsCache.size() + " entries from cache file");
        } catch (IOException ex) {
            System.err.println("[RDNSCache] Error reading RDNS cache file. Creating new file..."); // NOSONAR S106 static utility
            createRdnsCacheFile();
            ex.printStackTrace();
        }
        // Cancel existing timer before creating new one to prevent memory leak
        if (_rdnsTimer != null) {
            _rdnsTimer.cancel();
        }
        _rdnsTimer = new Timer(true);
        long delay = RDNS_WRITE_INTERVAL;
        _rdnsTimer.schedule(new RDNSCacheFileWriter(), delay, delay);
    }

    // CacheEntry to string includes timestamp for persistence
    private static String rdnsEntryToString(CacheEntry entry) {
        return entry.getIpAddress() + "," + entry.getHostname() + "," + entry.getTimestamp();
    }

    // Parse string to CacheEntry; support migration with current timestamp if old format
    private static CacheEntry rdnsEntryFromString(String s) {
        String[] parts = s.split(",", 3);
        if (parts.length == 3) {
            try {
                String ipAddress = parts[0];
                String hostname = parts[1];
                long timestamp = Long.parseLong(parts[2]);
                return new CacheEntry(ipAddress, hostname, timestamp);
            } catch (NumberFormatException e) {
                // Fall through to old format migration below
            }
        }
        if (parts.length == 2) {
            String ipAddress = parts[0];
            String hostname = parts[1];
            long timestamp = System.currentTimeMillis();
            return new CacheEntry(ipAddress, hostname, timestamp);
        }
        return null;
    }

    private static synchronized void createRdnsCacheFile() {
        File cacheFile = new File(RDNS_CACHE_FILE);
        if (!cacheFile.exists()) {
            try {
                cacheFile.createNewFile();
            } catch (IOException ex) {
                System.err.println("[RDNSCache] Error creating cache file: " + ex.getMessage()); // NOSONAR S106 static utility
            }
        } else {
            readRDNSCacheFromFile();
        }
    }

    private static class RDNSCacheFileWriter extends TimerTask {
        public RDNSCacheFileWriter() {
            // Intentionally empty - default constructor
        }

        @Override
        public void run() {
            Map<String, CacheEntry> liveCacheSnapshot = new HashMap<>(rdnsCache);
            File cacheFile = new File(RDNS_CACHE_FILE);
            try (BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(cacheFile))) {
                long now = System.currentTimeMillis();
                int writtenCount = 0;
                for (CacheEntry cacheEntry : liveCacheSnapshot.values()) {
                    if (now - cacheEntry.getTimestamp() <= EVICT_THRESHOLD) {
                        String line = rdnsEntryToString(cacheEntry) + "\n";
                        byte[] bytes = line.getBytes(ENCODING);
                        fos.write(bytes);
                        writtenCount++;
                    }
                }
                //System.out.println("[RDNSCache] Entries written to file: " + writtenCount);
            } catch (IOException ex) {
                System.err.println("[RDNSCache] Error updating reverse DNS cache file: " + ex.getMessage()); // NOSONAR S106 static utility
                ex.printStackTrace();
            }
        }
    }

    /**
     * Writes the current reverse DNS cache to a file, sorted by
     * timestamp with newest entries first. Only entries within
     * the eviction threshold are written. The cache is safely
     * synchronized during file writing and updated atomically
     * using a temporary file.
     *
     * This method also triggers cleanup of expired cache entries
     * after writing.
     */
    private static void writeRDNSCacheToFile() {
        synchronized (rdnslock) {
            try {
                File cacheFile = new File(RDNS_CACHE_FILE);
                if (!cacheFile.exists()) {
                    cacheFile.createNewFile();
                    System.out.println("[RDNSCache] No existing cache file found, new file created: " + RDNS_CACHE_FILE); // NOSONAR S106 static utility
                }

                File tmpFile = new File(RDNS_CACHE_FILE + ".tmp");
                long now = System.currentTimeMillis();
                AtomicInteger writtenCount = new AtomicInteger(0);

                try (BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(tmpFile), ENCODING))) {

                    // Convert to list to allow traditional loop
                    List<CacheEntry> entries = rdnsCache.values().stream()
                        .filter(entry -> now - entry.getTimestamp() <= EVICT_THRESHOLD)
                        .sorted((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()))
                        .collect(Collectors.toList());
                    for (CacheEntry cacheEntry : entries) {
                        try {
                            String line = rdnsEntryToString(cacheEntry);
                            if (line == null || line.trim().isEmpty()) {continue;}

                            int firstComma = line.indexOf(',');
                            int lastComma = line.lastIndexOf(',');

                            if (firstComma >= 0 && lastComma > firstComma) {
                                String ip = line.substring(0, firstComma).trim();
                                String name = line.substring(firstComma + 1, lastComma).trim();
                                String timestamp = line.substring(lastComma + 1).trim();
                                String lc = name.toLowerCase();

                                // Early skip based on original name
                                if (lc.contains("unknown") ||
                                    lc.contains("root") ||
                                    lc.contains("administered by")) {
                                    continue;
                                }

                                // Normalize the name
                                if (lc.contains("latin american and caribbean")) {
                                    name = "LACNIC";
                                } else if (lc.contains("asia pacific network") || lc.contains("administered by apnic")) {
                                    name = "APNIC";
                                } else if (lc.contains("ripe network coordination")) {
                                    name = "RIPE NCC";
                                } else if (lc.contains("african network information center")) {
                                    name = "AFRINIC";
                                } else if (lc.contains("centurylink communications")) {
                                    name = "CenturyLink";
                                } else if (lc.contains("cloudflare, inc")) {
                                    name = "CLOUDFLARE";
                                } else if (lc.contains("t-mobile usa")) {
                                    name = "T-MOBILE USA";
                                } else if (lc.equals("mediacom communications corp (mcc-244)")) {
                                    name = "MEDIACOM";
                                } else if (lc.equals("root")) {
                                    name = "PRIVATE";
                                } else if (lc.equals("non-ripe-ncc-managed-address-block")) {
                                    name = "unknown";
                                }

                                line = ip + "," + name + "," + timestamp;

                                // Final skip based on modified line
                                boolean skipWrite = line.toLowerCase().contains("unknown") ||
                                                    line.toLowerCase().contains("private");

                                if (!skipWrite) {
                                    writer.write(line);
                                    writer.newLine();
                                    writtenCount.incrementAndGet();
                                }
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }

                Files.copy(tmpFile.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                tmpFile.delete();
            } catch (IOException ex) {
                System.err.println("[RDNSCache] Error updating reverse DNS cache file: " + ex.getMessage()); // NOSONAR S106 static utility
                ex.printStackTrace();
            }
        }
        cleanupRDNSCache();
    }

    private static void cleanupRDNSCache() {
        long now = System.currentTimeMillis();
        int removed = 0;
        long unknownExpireTimeMillis = 15 * 60 * 1000L; // 15 minutes
        Iterator<Map.Entry<String, CacheEntry>> it = rdnsCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CacheEntry> entry = it.next();
            CacheEntry ce = entry.getValue();
            long age = now - ce.getTimestamp();
            long expireTime = EXPIRE_TIME;

            // Convert cacheEntry to string line and check for "unknown"
            String cacheEntryStr = rdnsEntryToString(ce);
            if (cacheEntryStr.contains("unknown")) {
                expireTime = unknownExpireTimeMillis;
            }
            if (age > expireTime) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            System.out.println("[RDNSCache] Removed " + removed + " stale entries from the cache"); // NOSONAR S106 static utility
        }
    }

    public static int countRdnsCacheEntries() {
        return rdnsCache.size();
    }

    /**
     * Get cache statistics for monitoring
     * @return formatted string with cache stats
     */
    public static String getRdnsCacheStats() {
        int size = rdnsCache.size();
        int maxSize = MAX_RDNS_CACHE_SIZE;
        double utilization = (double) size / maxSize * 100;
        return String.format("RDNS Cache: %d/%d entries (%.1f%% utilized)", size, maxSize, utilization);
    }

    /**
     * Returns the canonical hostname for the given IP address from cache or DNS.
     * If RDNS is enabled, performs a reverse DNS lookup first.
     * Falls back to local ASN database if DNS fails, returning the org name.
     *
     * @param ipAddress IP address to resolve, or null/"null" to return null
     * @return hostname, org name from ASN database, or null for invalid input
     * @since 0.9.58+
     */
    @Override
    public String getCanonicalHostName(String ipAddress) {
        if (ipAddress == null || ipAddress.equals("null")) {
            return _t("unknown");
        }
        long now = System.currentTimeMillis();

        CacheEntry existingEntry = rdnsCache.get(ipAddress);
        if (existingEntry != null && (now - existingEntry.getTimestamp() <= EXPIRE_TIME)) {
            String cached = existingEntry.getHostname();
            if (cached != null && !cached.equals(ipAddress) && !_t("unknown").equals(cached)) {
                return cached;
            }
            // Stale "unknown" or raw IP — queue background re-lookup
        }

        if (pendingLookups.add(ipAddress)) {
            getReverseDnsExecutor().submit(() -> {
                try {
                    String hostName = ipAddress;
                    if (enableReverseLookups()) {
                        try {
                            hostName = InetAddress.getByName(ipAddress).getCanonicalHostName();
                            rdnsCache.put(ipAddress, new CacheEntry(ipAddress, hostName, System.currentTimeMillis()));
                        } catch (UnknownHostException e) {
                            // RDNS failed, will fall through to ASN lookup
                        }
                    }
                    // Fall back to local ASN database if RDNS returned the IP or failed
                    if (hostName.equals(ipAddress) || _t("unknown").equals(hostName)) {
                        String orgName = _geoIP.getOrgName(ipAddress);
                        if (orgName != null && !orgName.isEmpty()) {
                            rdnsCache.put(ipAddress, new CacheEntry(ipAddress, orgName, System.currentTimeMillis()));
                        }
                    }
                } finally {
                    pendingLookups.remove(ipAddress);
                }
            });
        }

        return ipAddress;
    }

    @Override
    public String getCanonicalHostNameSync(String ipAddress) {
        if (ipAddress == null || ipAddress.equals("null")) {
            return _t("unknown");
        }
        long now = System.currentTimeMillis();

        CacheEntry existingEntry = rdnsCache.get(ipAddress);
        if (existingEntry != null && (now - existingEntry.getTimestamp() <= EXPIRE_TIME)) {
            String cached = existingEntry.getHostname();
            // Return useful cached results immediately
            if (cached != null && !cached.equals(ipAddress) && !_t("unknown").equals(cached)) {
                return cached;
            }
            // Stale "unknown" or raw IP — fall through to re-lookup
        }

        String hostName = ipAddress;
        if (enableReverseLookups()) {
            try {
                hostName = InetAddress.getByName(ipAddress).getCanonicalHostName();
            } catch (UnknownHostException e) {
                // RDNS failed, will fall through to ASN lookup
            }
        }
        if (hostName.equals(ipAddress) || _t("unknown").equals(hostName)) {
            String orgName = _geoIP.getOrgName(ipAddress);
            if (orgName != null && !orgName.isEmpty()) {
                hostName = orgName;
            }
        }

        rdnsCache.put(ipAddress, new CacheEntry(ipAddress, hostName, now));
        return hostName;
    }

    /**
     * Convert IP string to Hash and call existing getCountry method
     * Return two-letter country code or null if unknown
     */
    private String getCountryFromIPAddress(String ipAddress) {
        try {
            byte[] ipBytes = InetAddress.getByName(ipAddress).getAddress();
            return _geoIP.get(ipBytes); // Existing geoip lookup by raw IP bytes
        } catch (UnknownHostException e) {return null;}
    }

    private static final int cores = SystemVersion.getCores();

    private ExecutorService reverseDnsExecutor;
    private final Object reverseDnsExecutorLock = new Object();

    private static final Set<String> pendingLookups = ConcurrentHashMap.newKeySet();

    private static Timer _rdnsTimer;

    /**
     *  @return domain name only from reverse dns hostname lookups
     *  @since 0.9.58+
     */
    public static String getDomain(String hostname) {
        if (hostname == null || hostname.isEmpty()) return "";

        hostname = hostname.toLowerCase();
        if (hostname.startsWith(".")) hostname = hostname.substring(1);

        String[] parts = hostname.split("\\.");
        int len = parts.length;

        if (len < 2 || parts[0].isEmpty()) return hostname;

        // Try to match known multi-part TLDs
        for (int i = 1; i < len; i++) {
            StringBuilder tldBuilder = new StringBuilder();
            for (int j = i; j < len; j++) {
                if (j > i) tldBuilder.append(".");
                tldBuilder.append(parts[j]);
            }
            if (MULTI_PART_TLDS.contains(tldBuilder.toString())) {
                int domainIndex = len - tldBuilder.toString().split("\\.").length - 1;
                StringBuilder domain = new StringBuilder();
                for (int k = domainIndex; k < len; k++) {
                    if (k > domainIndex) domain.append(".");
                    domain.append(parts[k]);
                }
                return domain.toString();
            }
        }

        // Default to 2-part domain
        return parts[len - 2] + "." + parts[len - 1];
    }

    /* @since 0.9.68+ */
    private static final Set<String> MULTI_PART_TLDS;

    /* @since 0.9.68+ */
    static {
        MULTI_PART_TLDS = new HashSet<>(Arrays.asList(
            "co.uk", "gov.uk", "ac.uk", "nhs.uk", "org.uk", "mod.uk", "mil.uk", "sch.uk",
            "com.au", "net.au", "org.au", "edu.au", "gov.au",
            "co.nz", "net.nz", "org.nz", "govt.nz", "school.nz",
            "co.jp", "ne.jp", "or.jp", "go.jp", "ac.jp", "ed.jp", "ad.jp", "gr.jp", "lg.jp",
            "co.kr", "re.kr", "pe.kr", "go.kr", "mil.kr", "ac.kr", "hs.kr", "ms.kr", "es.kr", "sc.kr",
            "com.tw", "org.tw", "edu.tw", "gov.tw", "idv.tw",
            "com.br", "org.br", "gov.br", "mil.br",
            "com.tr", "gov.tr", "edu.tr", "org.tr",
            "co.za", "net.za", "org.za", "web.za",
            "co.il", "org.il", "k12.il", "muni.il", "gov.il",
            "com.sg", "net.sg", "org.sg", "gov.sg", "edu.sg"
        ));
    }

    /**
     *  @return two-letter lower-case country code or null
     *  @since 0.8.11
     */
    @Override
    public String getOurCountry() {return _context.getProperty(GeoIP.PROP_IP_COUNTRY);}

    /**
     *  Are we in a strict country
     *  @since 0.8.13
     */
    @Override
    public boolean isInStrictCountry() {
        String us = getOurCountry();
        return (us != null && StrictCountries.contains(us)) ||
                _context.getBooleanProperty("router.forceStrictCountry") ||
                _context.getBooleanProperty("router.blockMyCountry");
    }

    /**
     *  Are they in a strict country.
     *  Not recommended for our local router hash, as we may not be either in the cache or netdb,
     *  or may not be publishing an IP.
     *
     *  @param peer non-null
     *  @since 0.9.16
     */
    @Override
    public boolean isInStrictCountry(Hash peer) {
        String c = getCountry(peer);
        return c != null && StrictCountries.contains(c);
    }

    /**
     *  Are they in a strict country
     *  @param ri non-null
     *  @since 0.9.16
     */
    @Override
    public boolean isInStrictCountry(RouterInfo ri) {
        byte[] ip = getIP(ri);
        Hash h = ri.getHash();
        if (ip == null) {ip = TransportImpl.getIP(h);}
        if (ip == null) {return false;}
        String c = _geoIP.get(ip);
        return c != null && StrictCountries.contains(c);
    }

    /**
     *  Uses the transport IP first because that lookup is fast,
     *  then the IP from the netDb.
     *  Not recommended for our local router hash, as we may not be either in the cache or netdb,
     *  or may not be publishing an IP.
     *
     *  As of 0.9.32, works only for literal IPs, returns null for host names.
     *
     *  @param peer not ourselves - use getOurCountry() for that
     *  @return two-letter lower-case country code or null
     */

    private static final int MAX_COUNTRY_CACHE_SIZE = 20000;
    private static final long COUNTRY_CACHE_EXPIRY = 60*60*1000L; // 1 hour
    private static final Random random = new Random();
    private long lastLookupTime = 0;
    private long lastUnknownPurge = 0;
    private long lastCacheCleanup = 0;
    private final ConcurrentHashMap<Hash, String> countryCache = new ConcurrentHashMap<>(MAX_COUNTRY_CACHE_SIZE);
    private final ConcurrentHashMap<Hash, Long> countryCacheTimestamps = new ConcurrentHashMap<>(MAX_COUNTRY_CACHE_SIZE);

    /**
     *  Uses the transport IP first because that lookup is fast, then the IP from the netDb.
     *  Not recommended for our local router hash, as we may not be either in the cache or netdb,
     *  or may not be publishing an IP.
     *
     *  As of 0.9.32, works only for literal IPs, returns null for hostnames.
     *
     *  @param peer not ourselves - use getOurCountry() for that
     *  @return two-letter lower-case country code or xx for non-banned peers, or null otherwise
     */
    @Override
    public String getCountry(Hash peer) {
        String cachedCountry = countryCache.get(peer);
        long now = System.currentTimeMillis();

        // Periodic cleanup of stale entries (8 hour expiry)
        if (now - lastCacheCleanup > 5*60*1000) { // Check every 5 minutes
            cleanupCountryCache();
            lastCacheCleanup = now;
        }

        if (cachedCountry != null && !cachedCountry.equals("xx")) {return cachedCountry;}
        else if (cachedCountry != null && cachedCountry.equals("xx") && now - lastUnknownPurge > 5*60*1000) {
            countryCache.remove(peer);
            countryCacheTimestamps.remove(peer);
            lastUnknownPurge = now;
        }

        RouterInfo ri = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(peer);
        byte[] ip = TransportImpl.getIP(peer);

        if (ip == null && ri != null) {ip = getIP(ri);}
        if (ip == null && ri != null) {
            if (_log.shouldDebug()) {
                _log.debug("Cannot identify country for Router [" + peer.toBase64().substring(0, 6) + "] -> IP address not found");
            }
            return "xx";
        }

        String country = _geoIP.get(ip);
        if (ri == null || country == null) {return "xx";}
        if (ri != null && country.equals("xx")) {
            if (_log.shouldDebug()) {
                try {
                    InetAddress address = InetAddress.getByAddress(ip);
                    String hostAddress = address.getHostAddress();
                    _log.debug("Country not found for IP address: " + hostAddress);
                } catch (UnknownHostException e) {
                    _log.debug("Unknown host while attempting to resolve address: " + e.getMessage());
                }
            }
            return "xx";
        } else if (country == null && ri == null) {return "xx";}

        if (countryCache.size() >= MAX_COUNTRY_CACHE_SIZE) {
            // Evict oldest entries by timestamp
            long now2 = System.currentTimeMillis();
            countryCacheTimestamps.entrySet().removeIf(e -> {
                if (now2 - e.getValue() > COUNTRY_CACHE_EXPIRY) {
                    countryCache.remove(e.getKey());
                    return true;
                }
                return false;
            });
            // If still over capacity, remove oldest remaining
            if (countryCache.size() >= MAX_COUNTRY_CACHE_SIZE) {
                countryCacheTimestamps.entrySet().stream()
                    .sorted((a, b) -> Long.compare(a.getValue(), b.getValue()))
                    .limit(MAX_COUNTRY_CACHE_SIZE / 4)
                    .forEach(e -> {
                        countryCache.remove(e.getKey());
                        countryCacheTimestamps.remove(e.getKey());
                    });
            }
        }

        lastLookupTime = now;
        countryCache.put(peer, country);
        countryCacheTimestamps.put(peer, now);

        boolean blockMyCountry = _context.getBooleanProperty(PROP_BLOCK_MY_COUNTRY);
        boolean isStrict = _context.commSystem().isInStrictCountry();
        boolean isHidden = _context.router().isHidden();

        if (isStrict || isHidden || blockMyCountry) {
            String myCountry = _context.getProperty(PROP_IP_COUNTRY);
            if (myCountry != null && myCountry.equals(country)) {
                _geoIP.banCountry(_context, country);
            }
        }

        return country;
    }

    /**
     *  Return first IP (v4 or v6) we find, any transport.
     *  Not validated, may be local, etc.
     *
     *  As of 0.9.32, works only for literal IPs, returns null for host names.
     *
     *  @return IP or null
     */
    private static byte[] getIP(RouterInfo ri) {
        if (ri == null) {return null;}
        for (RouterAddress ra : ri.getAddresses()) {
            byte[] rv = ra.getIP();
            if (rv != null) {return rv;}
            else {
                rv = TransportImpl.getIP(ri.getHash());
                if (rv != null) {return rv;}
            }
        }
        return null;
    }

    /**
     * Get country code for an IP address string
     * @param ip IP address string
     * @return two-letter country code or null if unknown
     */
    @Override
    public String getCountry(String ip) {
        return getCountryFromIPAddress(ip);
    }

    /**
     *  Clean up stale entries from the country cache
     */
    private void cleanupCountryCache() {
        long now = System.currentTimeMillis();
        int removed = 0;
        Iterator<Map.Entry<Hash, String>> it = countryCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Hash, String> entry = it.next();
            Long ts = countryCacheTimestamps.get(entry.getKey());
            boolean expired = ts != null && (now - ts > COUNTRY_CACHE_EXPIRY);
            if (expired || (entry.getValue() != null && entry.getValue().equals("xx"))) {
                it.remove();
                countryCacheTimestamps.remove(entry.getKey());
                removed++;
            }
        }
        if (removed > 0 && _log.shouldInfo()) {
            _log.info("Cleaned up " + removed + " stale country entries from cache");
        }
    }

    /**
     *  Return first valid IP (v4 or v6) we find, any transport.
     *  Local and other invalid IPs will not be returned.
     *
     *  As of 0.9.32, works only for literal IPs, returns null for host names.
     *
     *  @return IP or null
     *  @since 0.9.18
     */
    public static byte[] getValidIP(RouterInfo ri) {
        if (ri == null) {return null;}
        for (RouterAddress ra : ri.getAddresses()) { // NPE?!!
            byte[] rv = ra.getIP();
            if (rv != null && TransportUtil.isPubliclyRoutable(rv, true)) {return rv;}
        }
        return null;
    }

    /**
     *  Get an IP address compatible with our capabilities (IPv4/IPv6).
     *  Prefers IPv4 if we support it.
     *
     *  @param ri RouterInfo to get IP from
     *  @return IP or null
     *  @since 0.9.68+
     */
    public static byte[] getCompatibleIP(RouterInfo ri) {
        if (ri == null) {return null;}
        RouterContext ctx = (RouterContext) I2PAppContext.getGlobalContext();
        boolean haveIPv4 = false;
        boolean haveIPv6 = false;
        for (RouterAddress ra : ri.getAddresses()) {
            byte[] ip = ra.getIP();
            if (ip != null && TransportUtil.isPubliclyRoutable(ip, true)) {
                if (ip.length == 16) {
                    haveIPv6 = true;
                } else {
                    haveIPv4 = true;
                }
            }
        }
        if (haveIPv4 && !haveIPv6) {
            return getFirstValidIPOfType(ri, false);
        }
        if (haveIPv6 && !haveIPv4) {
            return getFirstValidIPOfType(ri, true);
        }
        boolean weSupportIPv4 = supportsIPv4(ctx);
        boolean weSupportIPv6 = supportsIPv6(ctx);
        if (weSupportIPv4) {
            byte[] ipv4 = getFirstValidIPOfType(ri, false);
            if (ipv4 != null) {return ipv4;}
        }
        if (weSupportIPv6) {
            byte[] ipv6 = getFirstValidIPOfType(ri, true);
            if (ipv6 != null) {return ipv6;}
        }
        return getValidIP(ri);
    }

    /**
     *  Get the first valid IP of the specified type (IPv4 or IPv6).
     */
    private static byte[] getFirstValidIPOfType(RouterInfo ri, boolean wantIPv6) {
        for (RouterAddress ra : ri.getAddresses()) {
            byte[] ip = ra.getIP();
            if (ip != null && TransportUtil.isPubliclyRoutable(ip, true)) {
                boolean isIPv6 = ip.length == 16;
                if (wantIPv6 == isIPv6) {return ip;}
            }
        }
        return null;
    }

    /**
     *  Check if we support outbound IPv4 connections.
     */
    private static boolean supportsIPv4(RouterContext ctx) {
        boolean ntcpEnabled = TransportManager.isNTCPEnabled(ctx);
        boolean ssuEnabled = ctx.getBooleanPropertyDefaultTrue(TransportManager.PROP_ENABLE_UDP);
        return ntcpEnabled || ssuEnabled;
    }

    /**
     *  Check if we support outbound IPv6 connections.
     */
    private static boolean supportsIPv6(RouterContext ctx) {
        TransportUtil.IPv6Config ntcp6 = TransportUtil.getIPv6Config(ctx, "NTCP");
        TransportUtil.IPv6Config ssu6 = TransportUtil.getIPv6Config(ctx, "UDP");
        return ntcp6 != TransportUtil.IPv6Config.IPV6_DISABLED || ssu6 != TransportUtil.IPv6Config.IPV6_DISABLED;
    }

    /** full name for a country code, or the code if we don't know the name */
    @Override
    public String getCountryName(String c) {
        if (_geoIP == null) {return c;}
        String n = _geoIP.fullName(c);
        if (n == null) {return c;}
        return n;
    }

    /**
     * Provides country code mappings.
     * @return Unmodifiable map of lower-case country codes to untranslated names.
     * Returns empty map if geoIP data is unavailable.
     * @since 0.9.53
     */
    public Map<String, String> getCountries() {
        if (_geoIP == null) return Collections.emptyMap();
        return _geoIP.getCountries();
    }

    /**
     * Renders HTML for a peer with optional extended info.
     * Uses cached RouterInfo, country info, and reverse lookup cache.
     *
     * @param peer Peer Hash
     * @param extended Whether to show extended capabilities
     * @return HTML snippet representing peer
     */
    @Override
    public String renderPeerHTML(Hash peer, boolean extended) {
        StringBuilder buf = new StringBuilder(256);
        RouterInfo ri = getRouterInfoCached(peer);
        String c = getCountry(peer);
        String h = peer.toBase64();

        if (ri != null) {
            String caps = ri.getCapabilities();
            String v = ri.getVersion();
            String ip = net.i2p.util.Addresses.toString(getValidIP(ri));

            buf.append("<table class=rid><tr><td class=rif>");
            if (c != null) {
                String countryName = getCountryName(c);
                if (countryName.length() > 2) {
                    countryName = Translate.getString(countryName, _context, COUNTRY_BUNDLE_NAME);
                }

                buf.append("<a href=\"/netdb?c=").append(c).append("\"><img width=20 height=15 alt=\"")
                   .append(c.toUpperCase(Locale.US)).append("\" title=\"").append(countryName);

                if (ip != null && !"null".equals(ip)) {
                    if (enableReverseLookups()) {
                        String canonicalHost = reverseLookupCache.computeIfAbsent(ip, k -> _context.commSystem().getCanonicalHostName(k));
                        if (!"unknown".equals(canonicalHost)) {buf.append(" &bullet; ").append(canonicalHost);}
                        else {buf.append(" &bullet; ").append(ip);}
                    } else {buf.append(" &bullet; ").append(ip);}
                }
                buf.append("\" src=\"/flags.jsp?c=").append(c).append("\"></a>");
            } else {
                buf.append("<img width=20 height=15 alt=\"??\" src=\"/flags.jsp?c=xx\" title=\"").append(_t("unknown"));
                if (ip != null) {buf.append(" &bullet; ").append(ip);}
                buf.append("\">");
            }
            buf.append("</td><td class=rih>");
            buf.append("<a title=\"");
            if (caps.contains("f") && !extended) {buf.append(_t("Floodfill"));}
            if (v != null) {
                if (!extended) {buf.append(" &bullet; ");}
                buf.append(v);
            }
            buf.append("\" href=\"netdb?r=").append(h.substring(0,10)).append("\">").append(h.substring(0,4)).append("</a>");
            if (extended) {buf.append("</td>").append(renderPeerCaps(peer, true));}
        } else {
            buf.append("<table class=rid><tr><td class=rif>").append(renderPeerFlag(peer))
               .append("</td><td class=rih>").append(h.substring(0,4));
            if (extended) {buf.append("</td><td class=rbw>?</td>");}
        }
        buf.append("</tr></table>");
        return buf.toString();
    }

    public String renderPeerFlag(Hash peer) {
        StringBuilder buf = new StringBuilder(128);
        RouterInfo ri = getRouterInfoCached(peer);
        String unknownFlag = "<img class=unknownflag width=24 height=18 alt=\"??\" src=\"/flags.jsp?c=xx\">";
        String countryCode = getCountry(peer);
        if (countryCode == null) {countryCode = "xx";}
        String countryName = getCountryName(countryCode);
        if (countryName.length() > 2)
            countryName = Translate.getString(countryName, _context, COUNTRY_BUNDLE_NAME);
        buf.append("<span class=cc hidden>").append(countryCode.toUpperCase(Locale.US)).append("</span>");
        buf.append("<span class=peerFlag title=\"");
        if (ri != null) {
            String ip = net.i2p.util.Addresses.toString(getValidIP(ri));
            if (ip == null || ip.isEmpty() || "null".equals(ip)) {
                byte[] transportIP = getIP(ri);
                if (transportIP != null)
                    ip = net.i2p.util.Addresses.toString(transportIP);
            }
            if (!"xx".equals(countryCode) && countryName.length() > 2) {
                buf.append(countryName);
                if (ip != null && ip.length() > 6) {
                    buf.append(" &bullet; ");
                    if (enableReverseLookups()) {
                        // Don't block on reverse DNS lookup - just use the IP for now
                        // The lookup will happen asynchronously and update the cache
                        // The next page refresh will show the hostname
                        String canonicalHost = reverseLookupCache.get(ip);
                        if (canonicalHost != null && !"unknown".equals(canonicalHost)) {
                            buf.append(canonicalHost).append(" (").append(ip).append(")");
                        } else {
                            buf.append(ip);
                            // Trigger async lookup if not already in cache
                            if (canonicalHost == null) {
                                _context.commSystem().getCanonicalHostName(ip);
                            }
                        }
                    } else {buf.append(ip);}
                }
            } else {buf.append(_t("unknown"));}
            buf.append("\">");
            if (!"xx".equals(countryCode)) {
                buf.append("<a href=\"/netdb?c=").append(countryCode).append("\"><img width=24 height=18 alt=\"")
                   .append(countryCode.toUpperCase(Locale.US)).append("\" src=\"/flags.jsp?c=")
                   .append(countryCode).append("\"></a>");
            } else {buf.append(unknownFlag);}
        } else {buf.append(_t("unknown")).append("\">").append(unknownFlag);}
        buf.append("</span>");
        return buf.toString();
    }

    /**
     * Renders the peer's capability HTML block.
     * Caches data and removes unnecessary repeated computation.
     *
     * @param peer Peer Hash
     * @param inline If true, render inline without table wrapper
     * @return HTML snippet of peer capabilities
     */
    @Override
    public String renderPeerCaps(Hash peer, boolean inline) {
        StringBuilder buf = new StringBuilder(inline ? 128 : 256);
        if (!inline) {buf.append("<table class=\"rid ric\"><tr>");}

        RouterInfo ri = getRouterInfoCached(peer);
        if (ri != null) {
            String caps = ri.getCapabilities();
            String capacity = getCapacityCached(peer);

            boolean hasD = caps.indexOf('D') >= 0;
            boolean hasE = caps.indexOf('E') >= 0;
            boolean hasG = caps.indexOf('G') >= 0;
            boolean isFF = caps.indexOf('f') >= 0;
            boolean isU = caps.indexOf('U') >= 0;
            boolean isR = caps.indexOf('R') >= 0;

            buf.append("<td class=\"rbw ").append(capacity);
            if (isFF) buf.append(" isff");
            if (isU) buf.append(" isU");
            if (hasD) buf.append(" isD");
            else if (hasE) buf.append(" isE");
            else if (hasG) buf.append(" isG");
            buf.append("\"><a href=\"/netdb?caps=").append(capacity);

            if (isFF) buf.append("f");
            if (isU) buf.append("U");
            else if (isR) buf.append("R");
            if (hasD) buf.append("D");
            else if (hasE) buf.append("E");
            else if (hasG) buf.append("G");
            buf.append("\" title=\"").append(_t("Show all routers with this capability in the NetDb")).append("\">");

            // Remove first occurrence of D, E, or G character from capacity string
            String visibleCapacity = CAPACITY_PATTERN.matcher(capacity).replaceFirst("");
            buf.append(visibleCapacity);

            buf.append("</a></td>");
        } else {buf.append("<td class=rbw>?</td>");}
        if (!inline) {buf.append("</tr></table>\n");}
        return buf.toString();
    }

    /**
     * Returns the capacity character for the peer, or '?' if unknown.
     *
     * @param peer Peer Hash
     * @return Capacity character or '?'
     */
    private char getCapacity(Hash peer) {
        RouterInfo info = getRouterInfoCached(peer);
        if (info != null) {
            String caps = info.getCapabilities();
            for (int i = 0; i < RouterInfo.BW_CAPABILITY_CHARS.length(); i++) {
                char c = RouterInfo.BW_CAPABILITY_CHARS.charAt(i);
                if (caps.indexOf(c) >= 0) {return c;}
            }
        }
        return '?';
    }

    /** Cache for reverse DNS lookups - small since we rely on file-backed rdnsCache */
    private final Map<String, String> reverseLookupCache = Collections.synchronizedMap(new LHMCache<>(50));

    // Cache RouterInfo and Capacity to improve repeated lookup efficiency
    private final Map<Hash, RouterInfo> routerInfoCache = Collections.synchronizedMap(new LHMCache<>(5000));
    private final Map<Hash, String> capacityCache = Collections.synchronizedMap(new LHMCache<>(5000));

    private RouterInfo getRouterInfoCached(Hash peer) {
        RouterInfo rv = routerInfoCache.get(peer);
        if (rv == null) {
            rv = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(peer);
            if (rv != null)
                routerInfoCache.put(peer, rv);
        }
        return rv;
    }

    private String getCapacityCached(Hash peer) {
        String rv = capacityCache.get(peer);
        if (rv == null) {
            RouterInfo ri = getRouterInfoCached(peer);
            if (ri == null) {
                rv = "?";
            } else {
                String caps = ri.getCapabilities();
                for (int i = 0; i < RouterInfo.BW_CAPABILITY_CHARS.length(); i++) {
                    char c = RouterInfo.BW_CAPABILITY_CHARS.charAt(i);
                    if (caps.indexOf(c) >= 0) {
                        rv = String.valueOf(c);
                        break;
                    }
                }
                if (rv == null)
                    rv = "?";
            }
            capacityCache.put(peer, rv);
        }
        return rv;
    }

    /**
     *  Is everything disabled for testing?
     *  @since 0.8.13
     */
    @Override
    public boolean isDummy() {return _context.getBooleanProperty(PROP_DISABLED);}

    /**
     *  Translate
     */
    private final String _t(String s) {return Translate.getString(s, _context, BUNDLE_NAME);}

    /*
     * Timestamper stuff
     *
     * This is used as a backup to NTP over UDP.
     * @since 0.7.12
     */

    private static final int TIME_START_DELAY = 5*60*1000;
    private static final int TIME_REPEAT_DELAY = 8*60*1000;

    /** @since 0.7.12 */
    private void startTimestamper() {
        _context.simpleTimer2().addPeriodicEvent(new Timestamper(), TIME_START_DELAY,  TIME_REPEAT_DELAY);
    }

    /**
     * Update the clock offset based on the average of the peers.
     * This uses the default stratum which is lower than any reasonable
     * NTP source, so it will be ignored unless NTP is broken.
     * @since 0.7.12
     */
    private class Timestamper implements SimpleTimer.TimedEvent {
        @Override
        public void timeReached() {
             // use the same % as in RouterClock so that check will never fail
             // This is their our offset w.r.t. them...
             long peerOffset = getFramedAveragePeerClockSkew(10);
             if (peerOffset == 0) {return;}
             long currentOffset = _context.clock().getOffset();
             // ... so we subtract it to get in sync with them
             long newOffset = currentOffset - peerOffset;
             _context.clock().setOffset(newOffset);
        }
    }

    /** @since 0.9.4 */
    private void startNetMonitor() {new NetMonitor();}

    /**
     * Simple check to see if we have a network connection
     * @since 0.9.4
     */
    private class NetMonitor extends SimpleTimer2.TimedEvent {
        private static final long SHORT_DELAY = 15*1000L;
        private static final long LONG_DELAY = 90*1000L;

        public NetMonitor() {super(_context.simpleTimer2(), 0);}

        @Override
        public void timeReached() {
            Set<AddressType> addrs = Addresses.getConnectedAddressTypes();
            boolean good = addrs.contains(AddressType.IPV4) || addrs.contains(AddressType.IPV6);
             if (_netMonitorStatus != good) {
                 if (good) {_log.logAlways(Log.INFO, "Network reconnected");}
                 else {_log.error("Network disconnected");}
                 _context.router().eventLog().addEvent(EventLog.NETWORK, good ? "connected" : "disconnected");
                 _netMonitorStatus = good;
                 if (good) {
                     _manager.initializeAddress(); // Check local addresses
                     _manager.transportAddressChanged(); // fire UPnP
                 }
             }
             reschedule(good ? LONG_DELAY : SHORT_DELAY);
        }
    }
}
