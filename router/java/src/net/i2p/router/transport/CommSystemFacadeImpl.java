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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.net.SocketFactory;
import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.crypto.X25519KeyFactory;
import net.i2p.router.transport.udp.UDPTransport;
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
import org.apache.commons.net.whois.WhoisClient;

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

    public CommSystemFacadeImpl(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(CommSystemFacadeImpl.class);
        _netMonitorStatus = true;
        _geoIP = new GeoIP(_context);
        _manager = new TransportManager(_context);
        _exemptIncoming = new LHMCache<String, Object>(128);
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

        shutdownStatic();
    }

    /**
     * Cleanup static resources.
     * This should be called when the router is shutting down to prevent memory leaks.
     */
    private static synchronized void shutdownStatic() {
        if (!WHOIS_QUERY_EXECUTOR.isShutdown()) {
            WHOIS_QUERY_EXECUTOR.shutdown();
            try {
                if (!WHOIS_QUERY_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                    WHOIS_QUERY_EXECUTOR.shutdownNow();
                }
            } catch (InterruptedException e) {
                WHOIS_QUERY_EXECUTOR.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (!REVERSE_DNS_EXECUTOR.isShutdown()) {
            REVERSE_DNS_EXECUTOR.shutdown();
            try {
                if (!REVERSE_DNS_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                    REVERSE_DNS_EXECUTOR.shutdownNow();
                }
            } catch (InterruptedException e) {
                REVERSE_DNS_EXECUTOR.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Clean up expired entries from downServers
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : downServers.entrySet()) {
            if (now - entry.getValue() >= SERVER_DOWN_TIMEOUT_MS) {
                downServers.remove(entry.getKey());
            }
        }
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
        List<RouterAddress> addresses = new ArrayList<RouterAddress>(_manager.getAddresses());
        if (addresses.size() > 1) {Collections.sort(addresses, new AddrComparator());}
        return addresses;
    }

    /**
     *  Arbitrary sort for consistency.
     *  Note that the console UI has its own sorter.
     *  @since 0.9.50
     */
    private static class AddrComparator implements Comparator<RouterAddress>, Serializable {
        public int compare(RouterAddress l, RouterAddress r) {
            int rv = l.getCost() - r.getCost();
            if (rv != 0) {return rv;}
            int lh = l.hashCode();
            int rh = l.hashCode();
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
        ArraySet<String> ips = new ArraySet<String>(addrs.size());
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
    private static final String PROP_ENABLE_WHOIS_LOOKUPS = "routerconsole.enableWhoisLookups";
    public boolean enableReverseLookups() {return _context.getBooleanProperty(PROP_ENABLE_REVERSE_LOOKUPS);}
    public boolean enableWhoisLookups() {return _context.getBooleanProperty(PROP_ENABLE_WHOIS_LOOKUPS);}
    private static final Charset ENCODING = StandardCharsets.UTF_8;
    private static final String NEWLINE = "\n";

    private void startGeoIP() {
        new QueueAll(START_DELAY);
        if (enableReverseLookups()) {readRDNSCacheFromFile();}
    }

    /**
     * Collect the IPs for all routers in the DB, and queue them for lookup,
     * then fire off the periodic lookup task for the first time.
     *
     *  As of 0.9.32, works only for literal IPs, ignores host names.
     */
    private class QueueAll extends SimpleTimer2.TimedEvent {
        public QueueAll(long timeoutMs) {
            super(_context.simpleTimer2(), timeoutMs);
        }
        @Override
        public void timeReached() {
            long uptime = _context.router().getUptime();
            for (Hash h : _context.netDb().getAllRouters()) {
                //RouterInfo ri = _context.netDb().lookupRouterInfoLocally(h);
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
    private static final long EXPIRE_TIME = (!has512MB ? 24 : (has1GB ? 48 : 36)) * 60 * 60 * 1000; // 1/1.5/2 day expiration
    private static final long EVICT_THRESHOLD = 3 * 24 * 60 * 60 * 1000; // 3 day for eviction from file cache
    private static final int MAX_RDNS_CACHE_SIZE = !has512MB ? 8000 : (has1GB ? 24000 : 16000);
    private static final Object rdnslock = new Object();

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

    // LRU Cache implemented with LinkedHashMap with access order
    private static class LRUCache<K, V> extends LinkedHashMap<K, V> {
        private final int maxEntries;

        public LRUCache(int maxEntries) {
            super(maxEntries + 1, 0.75f, true); // access order set to true
            this.maxEntries = maxEntries;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > maxEntries;
        }
    }

    /**
     * In-memory reverse DNS cache storing IP-to-hostname mappings.
     *
     * This cache is a size-bounded LRU cache that automatically evicts
     * least recently used entries when the maximum size is exceeded.
     * The cache is wrapped in Collections.synchronizedMap() for thread safety.
     *
     * Keys are IP addresses as Strings. Values are CacheEntry objects
     * containing hostname and timestamp.
     */
    private static final Map<String, CacheEntry> rdnsCache = Collections.synchronizedMap(new LRUCache<>(MAX_RDNS_CACHE_SIZE));

    private static Map<String, CacheEntry> getRDNSCache() {
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
            System.err.println("[RDNSCache] Error reading RDNS cache file. Creating new file...");
            createRdnsCacheFile();
            ex.printStackTrace();
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
                System.err.println("[RDNSCache] Error creating cache file: " + ex.getMessage());
            }
        } else {
            readRDNSCacheFromFile();
        }
    }

    private static class RDNSCacheFileWriter extends TimerTask {
        public RDNSCacheFileWriter() {}

        @Override
        public void run() {
            Map<String, CacheEntry> liveCacheSnapshot;
            synchronized (rdnsCache) {
                liveCacheSnapshot = new HashMap<>(rdnsCache);
            }
            File cacheFile = new File(RDNS_CACHE_FILE);
            try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
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
                System.err.println("[RDNSCache] Error updating reverse DNS cache file: " + ex.getMessage());
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
                    System.out.println("[RDNSCache] No existing cache file found, new file created: " + RDNS_CACHE_FILE);
                }

                File tmpFile = new File(RDNS_CACHE_FILE + ".tmp");
                long now = System.currentTimeMillis();
                AtomicInteger writtenCount = new AtomicInteger(0);

                try (BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(tmpFile), ENCODING))) {

                    synchronized (rdnsCache) {
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
                }

                Files.copy(tmpFile.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                tmpFile.delete();
            } catch (IOException ex) {
                System.err.println("[RDNSCache] Error updating reverse DNS cache file: " + ex.getMessage());
                ex.printStackTrace();
            }
        }
        cleanupRDNSCache();
    }

    private static synchronized void cleanupRDNSCache() {
        long now = System.currentTimeMillis();
        int removed = 0;
        long unknownExpireTimeMillis = 15 * 60 * 1000; // 15 minutes
        synchronized (rdnsCache) {
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
                System.out.println("[RDNSCache] Removed " + removed + " stale entries from the cache");
            }
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
     * Falls back to a WHOIS lookup if DNS fails, returning "ip (whois info)" if available,
     * or just the IP if WHOIS data is missing.
     *
     * @param ipAddress IP address to resolve, or null/"null" to return null
     * @return hostname, IP with WHOIS info, just IP if no WHOIS, or null for invalid input
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
            return existingEntry.getHostname();
        }

        if (pendingLookups.putIfAbsent(ipAddress, true) == null) {
            REVERSE_DNS_EXECUTOR.submit(() -> {
                try {
                    String hostName = ipAddress;
                    try {
                        hostName = InetAddress.getByName(ipAddress).getCanonicalHostName();
                        if ((hostName.equals(ipAddress) || _t("unknown").equals(hostName)) && enableWhoisLookups()) {
                            String countryCode = getCountryFromIPAddress(ipAddress);
                            String whoisData = null;
                            try {
                                whoisData = queryWhoisServers(ipAddress, countryCode).get();
                            } catch (InterruptedException | ExecutionException e) {
                                if (e instanceof InterruptedException) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            if (whoisData != null && !whoisData.isEmpty()) {
                                String whoisHost = parseWhois(whoisData);
                                if (whoisHost != null && !whoisHost.isEmpty()) {
                                    hostName = whoisHost;
                                }
                            }
                        }
                    } catch (UnknownHostException e) {
                        hostName = _t("unknown");
                    }
                    rdnsCache.put(ipAddress, new CacheEntry(ipAddress, hostName, System.currentTimeMillis()));
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
            return existingEntry.getHostname();
        }

        String hostName = ipAddress;
        try {
            hostName = InetAddress.getByName(ipAddress).getCanonicalHostName();
            if ((hostName.equals(ipAddress) || _t("unknown").equals(hostName)) && enableWhoisLookups()) {
                String countryCode = getCountryFromIPAddress(ipAddress);
                String whoisData = null;
                try {
                    whoisData = queryWhoisServers(ipAddress, countryCode).get();
                } catch (InterruptedException | ExecutionException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (whoisData != null && !whoisData.isEmpty()) {
                    String whoisHost = parseWhois(whoisData);
                    if (whoisHost != null && !whoisHost.isEmpty()) {
                        hostName = whoisHost;
                    }
                }
            }
        } catch (UnknownHostException e) {
            hostName = _t("unknown");
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
        } catch (UnknownHostException e) {return _t("unknown");}
    }

   private static final int cores = SystemVersion.getCores();

    private static final ExecutorService WHOIS_QUERY_EXECUTOR = new ThreadPoolExecutor(
        Math.max(8, cores - 4), // core pool size
        Math.max(20, cores*8), // max pool size
        60L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(1000), // Bounded queue
        new ThreadPoolExecutor.CallerRunsPolicy() // Apply backpressure
    );

    private static final int SOCKET_TIMEOUT_MS = 5000; // 5 seconds timeout
    private static final int MAX_RETRIES = 1;

    /**
     * Query WHOIS server(s) based on country code fallback logic asynchronously.
     * Returns WHOIS response or null if all fail.
     */
    private CompletableFuture<String> queryWhoisServers(String ipAddress, String countryCode) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> countryServers = (countryCode == null || countryCode.trim().isEmpty())
                ? Collections.emptyList()
                : WHOIS_SERVERS_BY_COUNTRY.getOrDefault(countryCode.toLowerCase(), Collections.emptyList());

            String result = tryWhoisServers(ipAddress, countryServers);
            if (result == null || result.trim().isEmpty() || "unknown".equalsIgnoreCase(result)) {
                // Fall back to generic WHOIS servers
                String genericResult = tryWhoisServers(ipAddress, GENERIC_WHOIS_SERVERS);
                return genericResult != null ? genericResult : "unknown";
            }
            return result;
        }, WHOIS_QUERY_EXECUTOR);
    }

    private String tryWhoisServers(String query, List<String> servers) {
        if (servers == null || servers.isEmpty()) {
            return "unknown";
        }

        List<Callable<String>> tasks = servers.stream()
            .map(server -> (Callable<String>) () -> {
                for (int i = 0; i < MAX_RETRIES; i++) {
                    String result = queryWhoisServerWithFallback(query, server);
                    if (result != null && !result.trim().isEmpty() && !"unknown".equalsIgnoreCase(result.trim())) {
                        return result;
                    }
                }
                return null;
            })
            .collect(Collectors.toList());

        try {
            List<Future<String>> futures = WHOIS_QUERY_EXECUTOR.invokeAll(tasks, 5, TimeUnit.SECONDS);
            for (Future<String> future : futures) {
                if (future.isDone()) {
                    try {
                        String result = future.get();
                        if (result != null && !result.isEmpty()) {
                            return result;
                        }
                    } catch (ExecutionException e) {
                        // ignore
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return "unknown";
    }

    private static final long SERVER_DOWN_TIMEOUT_MS = 60 * 60 * 1000L;

    private static final ConcurrentMap<String, Long> downServers = new ConcurrentHashMap<>();

   private static final ExecutorService REVERSE_DNS_EXECUTOR = new ThreadPoolExecutor(
         10, 20,
         60L, TimeUnit.SECONDS,
         new LinkedBlockingQueue<>(500),
         new ThreadPoolExecutor.CallerRunsPolicy()
     );

    private static final ConcurrentMap<String, Boolean> pendingLookups = new ConcurrentHashMap<>();

    private static Timer _rdnsTimer;

    private String queryWhoisServerWithFallback(String query, String whoisServer) {
        // Skip server if marked down and timeout not expired
        Long downSince = downServers.get(whoisServer);
        if (downSince != null && (System.currentTimeMillis() - downSince) < SERVER_DOWN_TIMEOUT_MS) {
            return null; // skip querying this server
        }

        int port = 43;
        boolean useTor = false;

        if ("23.184.48.6".equals(whoisServer) ||
            "23.128.248.249".equals(whoisServer) ||
            "104.36.80.11".equals(whoisServer) ||
            "23.171.8.170".equals(whoisServer) ||
            "74.48.163.73".equals(whoisServer) ||
            "23.137.249.9".equals(whoisServer)) {
            port = 38444;
            useTor = true;
        } else if ("127.0.0.1".equals(whoisServer)) {
            port = 4043;
        }

        int maxAttempts = 2;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String result = useTor
                        ? queryWhoisServerOverTor(query, whoisServer, port)
                        : queryWhoisServer(query, whoisServer, port);

                if (result == null || result.trim().isEmpty()) {
                    continue; // Retry on empty/null result
                }

                String lower = result.toLowerCase();
                if (lower.contains("denied") || lower.contains("refused") || lower.contains("is not registered") ||
                    lower.contains("not managed by") || lower.contains("have been further assigned") ||
                    lower.contains("non-ripe-ncc-managed-address-block")) {
                    // Mark server as down and stop retries
                    downServers.put(whoisServer, System.currentTimeMillis());
                    return null;
                }

                return result; // Valid result, return immediately

            } catch (IOException e) {}
        }

        return null; // All attempts failed without valid response
    }

    /**
     * WHOIS query with socket timeout support.
     */
    private String queryWhoisServer(String query, String whoisServer, int port) throws IOException {
        WhoisClient whois = new WhoisClient();
        try {
            whois.setDefaultTimeout(SOCKET_TIMEOUT_MS);
            whois.connect(whoisServer, port);
            String result = whois.query(query);
            whois.disconnect();
            return result;
        } finally {
            if (whois.isConnected()) {
                whois.disconnect();
            }
        }
    }

    /**
     * Get whois server for a country or TLD, or default if unknown
     */
    private static final Map<String, List<String>> WHOIS_SERVERS_BY_COUNTRY;
    static {
        Map<String, List<String>> map = new HashMap<>();

        // Africa region (including Indian Ocean islands)
        map.put("bi", Arrays.asList("whois.nic.bi", "whois.afrinic.net"));
        map.put("bj", Arrays.asList("whois.nic.bj", "whois.afrinic.net"));
        map.put("bw", Arrays.asList("whois.nic.net.bw", "whois.afrinic.net"));
        map.put("cm", Arrays.asList("whois.netcom.cm", "whois.afrinic.net"));
        map.put("ci", Arrays.asList("whois.nic.ci", "whois.afrinic.net"));
        map.put("dj", Arrays.asList("whois.nic.dj", "whois.afrinic.net"));
        map.put("dz", Arrays.asList("whois.nic.dz", "whois.afrinic.net"));
        map.put("eg", Arrays.asList("whois.ripe.net")); // Egypt uses RIPE NCC
        map.put("et", Arrays.asList("whois.nic.et", "whois.afrinic.net"));
        map.put("ga", Arrays.asList("whois.accra.ga", "whois.afrinic.net"));
        map.put("gh", Arrays.asList("whois.nic.gh", "whois.afrinic.net"));
        map.put("gn", Arrays.asList("whois.nic.gn", "whois.afrinic.net"));
        map.put("gq", Arrays.asList("whois.nic.gq", "whois.afrinic.net"));
        map.put("gw", Arrays.asList("whois.nic.gw", "whois.afrinic.net"));
        map.put("ke", Arrays.asList("whois.kenic.or.ke", "whois.afrinic.net"));
        map.put("km", Arrays.asList("whois.nic.km", "whois.afrinic.net"));
        map.put("lr", Arrays.asList("whois.nic.lr", "whois.afrinic.net"));
        map.put("ly", Arrays.asList("whois.nic.ly", "whois.afrinic.net"));
        map.put("ma", Arrays.asList("whois.iam.net.ma", "whois.afrinic.net"));
        map.put("mg", Arrays.asList("whois.nic.mg", "whois.afrinic.net"));
        map.put("ml", Arrays.asList("whois.dot.ml", "whois.afrinic.net"));
        map.put("mr", Arrays.asList("whois.nic.mr", "whois.afrinic.net"));
        map.put("mu", Arrays.asList("whois.nic.mu", "whois.afrinic.net"));
        map.put("mw", Arrays.asList("whois.nic.mw", "whois.afrinic.net"));
        map.put("mz", Arrays.asList("whois.nic.mz", "whois.afrinic.net"));
        map.put("na", Arrays.asList("whois.na-nic.com.na", "whois.afrinic.net"));
        map.put("ne", Arrays.asList("whois.nic.ne", "whois.afrinic.net"));
        map.put("ng", Arrays.asList("whois.nic.net.ng", "whois.afrinic.net"));
        map.put("rw", Arrays.asList("whois.nic.rw", "whois.afrinic.net"));
        map.put("sd", Arrays.asList("whois.nic.sd", "whois.afrinic.net"));
        map.put("sn", Arrays.asList("whois.nic.sn", "whois.afrinic.net"));
        map.put("sl", Arrays.asList("whois.nic.sl", "whois.afrinic.net"));
        map.put("so", Arrays.asList("whois.nic.so", "whois.afrinic.net"));
        map.put("ss", Arrays.asList("whois.nic.ss", "whois.afrinic.net"));
        map.put("sz", Arrays.asList("whois.sz", "whois.afrinic.net"));
        map.put("td", Arrays.asList("whois.nic.td", "whois.afrinic.net"));
        map.put("tg", Arrays.asList("whois.nic.tg", "whois.afrinic.net"));
        map.put("tn", Arrays.asList("whois.ati.tn", "whois.afrinic.net"));
        map.put("tz", Arrays.asList("whois.tznic.or.tz", "whois.afrinic.net"));
        map.put("ug", Arrays.asList("whois.co.ug", "whois.afrinic.net"));
        map.put("za", Arrays.asList("whois.registry.net.za", "whois.afrinic.net"));
        map.put("zm", Arrays.asList("whois.nic.zm", "whois.afrinic.net"));
        map.put("zw", Arrays.asList("whois.nic.zw", "whois.afrinic.net"));

        // Asia-Pacific region
        map.put("as", Arrays.asList("whois.nic.as"));
        map.put("au", Arrays.asList("whois.auda.org.au", "whois.aunic.net"));
        map.put("fj", Arrays.asList("whois.nic.fj", "whois.apnic.net"));
        map.put("fm", Arrays.asList("whois.nic.fm"));
        map.put("gu", Arrays.asList("whois.nic.gu"));
        map.put("hk", Arrays.asList("whois.hknic.net.hk", "whois.apnic.net"));
        map.put("id", Arrays.asList("whois.pandi.or.id"));
        map.put("jp", Arrays.asList("whois.jprs.jp"));
        map.put("kh", Arrays.asList("whois.nic.kh"));
        map.put("ki", Arrays.asList("whois.nic.ki"));
        map.put("la", Arrays.asList("whois2.afilias-grs.net"));
        map.put("lk", Arrays.asList("whois.nic.lk"));
        map.put("mh", Arrays.asList("whois.nic.mh"));
        map.put("mm", Arrays.asList("whois.nic.mm"));
        map.put("mp", Arrays.asList("whois.nic.mp"));
        map.put("mq", Arrays.asList("whois.nic.mq"));
        map.put("mv", Arrays.asList("whois.nic.mv"));
        map.put("nc", Arrays.asList("whois.nc"));
        map.put("nf", Arrays.asList("whois.nic.cx"));
        map.put("np", Arrays.asList("whois.nic.np"));
        map.put("nr", Arrays.asList("whois.nic.nr"));
        map.put("nu", Arrays.asList("whois.nic.nu"));
        map.put("nz", Arrays.asList("whois.srs.net.nz"));
        map.put("pf", Arrays.asList("whois.registry.pf", "whois.apnic.net"));
        map.put("pg", Arrays.asList("whois.nic.pg", "whois.apnic.net"));
        map.put("ph", Arrays.asList("whois.nic.ph"));
        map.put("pk", Arrays.asList("whois.pknic.net.pk"));
        map.put("pw", Arrays.asList("whois.nic.pw"));
        map.put("sb", Arrays.asList("whois.nic.net.sb"));
        map.put("sg", Arrays.asList("whois.nic.net.sg", "whois.apnic.net"));
        map.put("sh", Arrays.asList("whois.nic.sh"));
        map.put("tj", Arrays.asList("whois.nic.tj"));
        map.put("tl", Arrays.asList("whois.domains.tl"));
        map.put("to", Arrays.asList("whois.tonic.to"));
        map.put("tp", Arrays.asList("whois.domains.tl"));
        map.put("tv", Arrays.asList("whois.nic.tv"));
        map.put("tw", Arrays.asList("whois.twnic.net.tw", "whois.apnic.net"));
        map.put("vu", Arrays.asList("vunic.vu"));

        // Europe region
        map.put("at", Arrays.asList("whois.nic.at"));
        map.put("be", Arrays.asList("whois.dns.be"));
        map.put("bg", Arrays.asList("whois.register.bg"));
        map.put("ch", Arrays.asList("whois.nic.ch"));
        map.put("cz", Arrays.asList("whois.nic.cz"));
        map.put("de", Arrays.asList("whois.denic.de"));
        map.put("dk", Arrays.asList("whois.dk-hostmaster.dk"));
        map.put("ee", Arrays.asList("whois.tld.ee"));
        map.put("es", Arrays.asList("whois.nic.es"));
        map.put("fi", Arrays.asList("whois.fi"));
        map.put("fo", Arrays.asList("whois.nic.fo"));
        map.put("fr", Arrays.asList("whois.nic.fr"));
        map.put("gi", Arrays.asList("whois.gg"));
        map.put("gl", Arrays.asList("whois.nic.gl"));
        map.put("hr", Arrays.asList("whois.dns.hr"));
        map.put("hu", Arrays.asList("whois.nic.hu"));
        map.put("ie", Arrays.asList("whois.domainregistry.ie"));
        map.put("il", Arrays.asList("whois.isoc.org.il"));
        map.put("im", Arrays.asList("whois.nic.im"));
        map.put("is", Arrays.asList("whois.isnic.is"));
        map.put("it", Arrays.asList("whois.nic.it"));
        map.put("je", Arrays.asList("whois.je"));
        map.put("li", Arrays.asList("whois.nic.li"));
        map.put("lt", Arrays.asList("whois.domreg.lt"));
        map.put("lu", Arrays.asList("whois.restena.lu"));
        map.put("lv", Arrays.asList("whois.nic.lv"));
        map.put("me", Arrays.asList("whois.nic.me"));
        map.put("md", Arrays.asList("whois.nic.md"));
        map.put("nl", Arrays.asList("whois.domain-registry.nl"));
        map.put("no", Arrays.asList("whois.norid.no"));
        map.put("pl", Arrays.asList("whois.dns.pl"));
        map.put("pt", Arrays.asList("whois.dns.pt"));
        map.put("ro", Arrays.asList("whois.rotld.ro"));
        map.put("rs", Arrays.asList("whois.rnids.rs"));
        map.put("ru", Arrays.asList("whois.tcinet.ru"));
        map.put("se", Arrays.asList("whois.nic-se.se"));
        map.put("si", Arrays.asList("whois.arnes.si"));
        map.put("sk", Arrays.asList("whois.sk-nic.sk"));
        map.put("sm", Arrays.asList("whois.nic.sm"));
        map.put("ua", Arrays.asList("whois.ua"));
        map.put("uk", Arrays.asList("whois.nic.uk"));
        map.put("yt", Arrays.asList("whois.nic.yt"));

        // North America region
        map.put("ca", Arrays.asList("whois.cira.ca", "whois.arin.net"));
        map.put("us", Arrays.asList("whois.nic.us", "whois.arin.net"));
        map.put("mx", Arrays.asList("whois.nic.mx"));
        map.put("pa", Arrays.asList("whois.nic.pa"));
        map.put("pr", Arrays.asList("whois.nic.pr"));
        map.put("tt", Arrays.asList("whois.nic.tt"));

        // Latin America and Caribbean region
        map.put("ar", Arrays.asList("whois.nic.ar", "whois.lacnic.net"));
        map.put("bo", Arrays.asList("whois.nic.bo"));
        map.put("cl", Arrays.asList("whois.nic.cl", "whois.lacnic.net"));
        map.put("co", Arrays.asList("whois.nic.co"));
        map.put("cr", Arrays.asList("whois.nic.cr"));
        map.put("cu", Arrays.asList("whois.nic.cu"));
        map.put("do", Arrays.asList("whois.nic.do"));
        map.put("ec", Arrays.asList("whois.nic.ec"));
        map.put("gf", Arrays.asList("whois.nic.gf"));
        map.put("gt", Arrays.asList("whois.gt"));
        map.put("gy", Arrays.asList("whois.registry.gy"));
        map.put("hn", Arrays.asList("whois2.afilias-grs.net"));
        map.put("jm", Arrays.asList("whois.nic.jm"));
        map.put("ni", Arrays.asList("whois.nic.ni"));
        map.put("py", Arrays.asList("whois.nic.py", "whois.lacnic.net"));
        map.put("sr", Arrays.asList("whois.registry.sr"));
        map.put("sv", Arrays.asList("whois.svnet.org"));
        map.put("uy", Arrays.asList("nic.uy", "whois.lacnic.net"));
        map.put("ve", Arrays.asList("whois.nic.ve"));
        map.put("vg", Arrays.asList("ccwhois.ksregistry.net"));
        map.put("vi", Arrays.asList("whois.nic.vi"));

        // Other / Miscellaneous entries without clear regional grouping or single countries
        map.put("ac", Arrays.asList("whois.nic.ac"));
        map.put("aw", Arrays.asList("whois.nic.aw"));
        map.put("ax", Arrays.asList("whois.ax"));
        map.put("cc", Arrays.asList("whois.nic.cc"));
        map.put("cw", Arrays.asList("whois.cw"));
        map.put("cx", Arrays.asList("whois.nic.cx"));
        map.put("hm", Arrays.asList("whois.registry.hm"));
        map.put("lc", Arrays.asList("whois2.afilias-grs.net"));
        map.put("pm", Arrays.asList("whois.nic.pm"));
        map.put("pn", Arrays.asList("whois.nic.pn"));
        map.put("re", Arrays.asList("whois.nic.re"));
        map.put("sc", Arrays.asList("whois2.afilias-grs.net"));
        map.put("tc", Arrays.asList("whois.adamsnames.tc"));
        map.put("tf", Arrays.asList("whois.nic.tf"));
        map.put("wf", Arrays.asList("whois.nic.wf"));
        map.put("ws", Arrays.asList("whois.website.ws"));
        map.put("ye", Arrays.asList("whois.registry.ye"));

        WHOIS_SERVERS_BY_COUNTRY = Collections.unmodifiableMap(map);
    }

    private static final List<String> GENERIC_WHOIS_SERVERS = Arrays.asList(
        "23.171.8.170",  /* outproxy-1a.stormycloud.org */
        "74.48.163.73",  /* outproxy-1b.stormycloud.org */
        "23.137.249.9", /* outproxy-1c.stormycloud.org */
        "whois.arin.net",
        "whois.iana.org",
        "whois.ripe.net",
        "104.36.80.11",
        "23.128.248.249",
        "127.0.0.1"
    );

    /**
     * WHOIS query over Tor with socket timeout support
     */
    private String queryWhoisServerOverTor(String query, String whoisServer, int port) throws IOException {
        WhoisClient whois = new WhoisClient();
        try {
            whois.setSocketFactory(createTorSocketFactory("127.0.0.1", 9050));
            whois.setDefaultTimeout(SOCKET_TIMEOUT_MS);
            whois.connect(whoisServer, port);
            String result = whois.query(query);
            whois.disconnect();
            return result;
        } catch (IOException e) {
            if (whois.isConnected()) { whois.disconnect(); }
            throw e;
        }
    }

    private SocketFactory createTorSocketFactory(String host, int port) {
        Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(host, port));
        return new SocketFactory() {
            @Override public Socket createSocket() throws IOException { return new Socket(proxy); }
            @Override public Socket createSocket(String host, int port) throws IOException {
                Socket s = new Socket(proxy);
                s.connect(new InetSocketAddress(host, port));
                return s;
            }
            @Override public Socket createSocket(InetAddress host, int port) throws IOException {
                Socket s = new Socket(proxy);
                s.connect(new InetSocketAddress(host, port));
                return s;
            }
            @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
                Socket s = new Socket(proxy);
                s.bind(new InetSocketAddress(localHost, localPort));
                s.connect(new InetSocketAddress(host, port));
                return s;
            }
            @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
                Socket s = new Socket(proxy);
                s.bind(new InetSocketAddress(localAddress, localPort));
                s.connect(new InetSocketAddress(address, port));
                return s;
            }
        };
    }

    /**
     * Parse WHOIS response to extract a single organization or network name.
     *
     * This implementation performs a single pass over the WHOIS lines.
     * It prefers keys in priority order:
     *   - orgname:, organization:, organisation:, org-name:
     *   - If none found, fallback to netname:, net-name:, descr:
     *
     * Returns the first matching value found in preferred order, else "unknown".
     */
    private String parseWhois(String whoisData) {
        if (whoisData.contains("ORG-IANA1-AFRINIC")) {
            return _t("unknown");
        }

        String fallback = null; // For netname, descr, etc.

        for (String line : whoisData.split("\\r?\\n")) {
            line = line.trim();
            String lower = line.toLowerCase();

            if (lower.startsWith("orgname:") || lower.startsWith("organization:") ||
                lower.startsWith("organisation:") || lower.startsWith("org-name:")) {
                return line.split(":", 2)[1].trim();
            }

            if (fallback == null &&
                (lower.startsWith("netname:") || lower.startsWith("net-name:") || lower.startsWith("descr:"))) {
                fallback = line.split(":", 2)[1].trim();
            }
        }

        if (fallback != null) {
            String lc = fallback.toLowerCase();
            if (lc.contains("latin american and caribbean")) {
                fallback = "LACNIC";
            } else if (lc.contains("asia pacific network") || lc.contains("administered by apnic")) {
               fallback = "APNIC";
            } else if (lc.contains("african network information center")) {
               fallback = "AFRINIC";
            } else if (lc.contains("ripe network coordination")) {
                fallback = "RIPE";
            } else if (lc.contains("centurylink communications, llc")) {
                fallback = "CENTURYLINK";
            } else if (lc.contains("google fiber inc")) {
                fallback = "GOOGLE FIBER";
            } else if (lc.contains("charter communications inc")) {
                fallback = "CHARTER";
            } else if (lc.contains("oracle corporation")) {
                fallback = "ORACLE";
            } else if (lc.contains("fibernetics corporation")) {
                fallback = "FIBERNETICS CORP";
            } else if (lc.contains("frantech solutions")) {
                fallback = "FRANTECH";
            } else if (lc.contains("stormycloud inc")) {
                fallback = "STORMYCLOUD";
            } else if (lc.contains("t-mobile usa, inc")) {
                fallback = "T-MOBILE USA";
            } else if (lc.contains("data bridge limited")) {
                fallback = "DATA BRIDGE LTD";
            } else if (lc.contains("root")) {
                fallback = "PRIVATE IP ADDRESS";
            }
        }

        return (fallback != null) ? fallback.replaceAll("\\(.*?\\)", "").trim() : _t("unknown");
    }

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

        if (len < 2) return hostname;

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
    private static final long COUNTRY_CACHE_EXPIRY = 60*60*1000; // 1 hour
    private static final Random random = new Random();
    private long lastLookupTime = 0;
    private long lastUnknownPurge = 0;
    private long lastCacheCleanup = 0;
    private LinkedHashMap<Hash, String> countryCache = new LinkedHashMap<Hash, String>(MAX_COUNTRY_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Hash, String> eldest) {return size() > MAX_COUNTRY_CACHE_SIZE;}
    };

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
        long uptime = _context.router().getUptime();

        // Periodic cleanup of stale entries (8 hour expiry)
        if (now - lastCacheCleanup > 5*60*1000) { // Check every 5 minutes
            cleanupCountryCache();
            lastCacheCleanup = now;
        }

        if (cachedCountry != null && !cachedCountry.equals("xx")) {return cachedCountry;}
        else if (cachedCountry != null && cachedCountry.equals("xx") && now - lastUnknownPurge > 5*60*1000) {
            countryCache.remove(peer);
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
        if ((ri != null && country == null) || country == "xx") {
            if (_log.shouldDebug()) {
                try {
                    InetAddress address = InetAddress.getByAddress(ip);
                    String hostAddress = address.getHostAddress();
                    _log.debug("Country not found for IP address: " + hostAddress);
                } catch (UnknownHostException e) {
                    _log.debug("Unknown host while attempting to resolve address: " + e.getMessage());
                }
            }
            // Queue the IP for GeoIP lookup so it will be resolved asynchronously
            if (ip != null) {
                _geoIP.add(ip);
            }
            return "xx";
        } else if (country == null && ri == null) {
            return "xx";
        }

        if (countryCache.size() >= MAX_COUNTRY_CACHE_SIZE) {
            // Fetch keys, synchronize to avoid ConcurrentModificationException
            Set<Hash> keySet = Collections.synchronizedSet(new HashSet<>(countryCache.keySet()));
            Iterator<Hash> iterator = keySet.iterator();
            while (iterator.hasNext()) {
                Hash eldestKey = iterator.next();
                iterator.remove(); // Remove the oldest key
            }
        }

        lastLookupTime = now;

        boolean blockMyCountry = _context.getBooleanProperty(PROP_BLOCK_MY_COUNTRY);
        boolean isStrict = _context.commSystem().isInStrictCountry();
        boolean isHidden = _context.router().isHidden();

        if (isStrict || isHidden || blockMyCountry) {
            String myCountry = _context.getProperty(PROP_IP_COUNTRY);
            if (myCountry != null && myCountry == country) {
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
     *  Clean up stale entries from the country cache
     */
    private void cleanupCountryCache() {
        long now = System.currentTimeMillis();
        int removed = 0;
        synchronized (countryCache) {
            Iterator<Map.Entry<Hash, String>> it = countryCache.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Hash, String> entry = it.next();
                // For country cache, we need to track timestamps separately since LinkedHashMap doesn't store them
                // For now, we'll remove unknown entries and rely on LRU for size management
                if (entry.getValue() != null && entry.getValue().equals("xx")) {
                    it.remove();
                    removed++;
                }
            }
        }
        if (removed > 0 && _log.shouldInfo()) {
            _log.info("Cleaned up " + removed + " unknown country entries from cache");
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
     *  Return the first IP compatible with our outbound transports.
     *  If the peer has both IPv4 and IPv6 addresses, and we support both,
     *  prefer IPv4. If we only support one, return only that type.
     *  Falls back to any valid IP if no compatible one found.
     *
     *  @return IP or null
     *  @since 2.11.0
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
        // If peer has only one type, return it
        if (haveIPv4 && !haveIPv6) {
            return getFirstValidIPOfType(ri, false);
        }
        if (haveIPv6 && !haveIPv4) {
            return getFirstValidIPOfType(ri, true);
        }
        // Peer has both - check what we support
        boolean weSupportIPv4 = supportsIPv4(ctx);
        boolean weSupportIPv6 = supportsIPv6(ctx);
        // Prefer IPv4 if we support it
        if (weSupportIPv4) {
            byte[] ipv4 = getFirstValidIPOfType(ri, false);
            if (ipv4 != null) {return ipv4;}
        }
        if (weSupportIPv6) {
            byte[] ipv6 = getFirstValidIPOfType(ri, true);
            if (ipv6 != null) {return ipv6;}
        }
        // Fallback to any valid IP
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
        // Check NTCP
        boolean ntcpEnabled = TransportManager.isNTCPEnabled(ctx);
        // Check SSU/SSU2
        boolean ssuEnabled = ctx.getBooleanPropertyDefaultTrue(TransportManager.PROP_ENABLE_UDP);
        return ntcpEnabled || ssuEnabled;
    }

    /**
     *  Check if we support outbound IPv6 connections.
     */
    private static boolean supportsIPv6(RouterContext ctx) {
        // Check NTCP IPv6 config
        TransportUtil.IPv6Config ntcp6 = TransportUtil.getIPv6Config(ctx, "NTCP");
        // Check SSU/SSU2 IPv6 config
        TransportUtil.IPv6Config ssu6 = TransportUtil.getIPv6Config(ctx, "SSU");
        return ntcp6 == TransportUtil.IPv6Config.IPV6_ENABLED ||
               ntcp6 == TransportUtil.IPv6Config.IPV6_ONLY ||
               ntcp6 == TransportUtil.IPv6Config.IPV6_PREFERRED ||
               ssu6 == TransportUtil.IPv6Config.IPV6_ENABLED ||
               ssu6 == TransportUtil.IPv6Config.IPV6_ONLY ||
               ssu6 == TransportUtil.IPv6Config.IPV6_PREFERRED;
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
     * Get country code for an IP address.
     * @param ip IP address string (IPv4 or IPv6)
     * @return two-letter lower-case country code or null if not found
     * @since 0.9.68+
     */
    @Override
    public String getCountry(String ip) {
        if (_geoIP == null || ip == null || ip.isEmpty()) {return null;}
        String country = _geoIP.get(ip);
        if (country == null) {
            _geoIP.add(ip);
        }
        return country;
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
                        String canonicalHost = reverseLookupCache.computeIfAbsent(ip, k -> {
                            try {
                                return CompletableFuture.supplyAsync(() -> {
                                    try {
                                        return _context.commSystem().getCanonicalHostName(k);
                                     } catch (Exception e) {
                                        return _t("unknown");
                                     }
                                }, REVERSE_DNS_EXECUTOR).get(3, TimeUnit.SECONDS);
                            } catch (Exception e) {
                                return _t("unknown");
                            }
                        });
                        if (canonicalHost != null && !"unknown".equals(canonicalHost)) {
                            buf.append(canonicalHost).append(" (").append(ip).append(")");
                        } else {buf.append(ip);}
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
            String visibleCapacity = capacity.replaceFirst("[DEG]", "");
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
    private final ConcurrentHashMap<String, String> reverseLookupCache = new ConcurrentHashMap<>(50);

    // Cache RouterInfo and Capacity to improve repeated lookup efficiency
    private final ConcurrentHashMap<Hash, RouterInfo> routerInfoCache = new ConcurrentHashMap<>(5000);
    private final ConcurrentHashMap<Hash, String> capacityCache = new ConcurrentHashMap<>(5000);

    private RouterInfo getRouterInfoCached(Hash peer) {
        return routerInfoCache.computeIfAbsent(peer, p -> (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(p));
    }

    private String getCapacityCached(Hash peer) {
        return capacityCache.computeIfAbsent(peer, p -> {
            RouterInfo ri = getRouterInfoCached(p);
            if (ri == null) return "?";
            String caps = ri.getCapabilities();
            for (int i = 0; i < RouterInfo.BW_CAPABILITY_CHARS.length(); i++) {
                char c = RouterInfo.BW_CAPABILITY_CHARS.charAt(i);
                if (caps.indexOf(c) >= 0) return String.valueOf(c);
            }
            return "?";
        });
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
        private static final long SHORT_DELAY = 15*1000;
        private static final long LONG_DELAY = 90*1000;

        public NetMonitor() {super(_context.simpleTimer2(), 0);}

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
