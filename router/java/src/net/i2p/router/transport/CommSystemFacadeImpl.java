package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.crypto.X25519KeyFactory;
import net.i2p.router.util.EventLog;
import net.i2p.util.AddressType;
import net.i2p.util.Addresses;
import net.i2p.util.ArraySet;
import net.i2p.util.LHMCache;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
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
    private final ReverseDnsLookup _rdns;
    private final CountryLookup _countryLookup;
    private final PeerHTMLRenderer _peerHTML;
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

    private static final Object DUMMY = Integer.valueOf(0);

    /**
     * CommSystemFacadeImpl.
     */
    public CommSystemFacadeImpl(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(CommSystemFacadeImpl.class);
        _netMonitorStatus = true;
        _geoIP = new GeoIP(_context);
        _manager = new TransportManager(_context);
        _exemptIncoming = new LHMCache<>(128);
        _rdns = new ReverseDnsLookup(_context, _geoIP);
        _countryLookup = new CountryLookup(_context, _geoIP);
        _peerHTML = new PeerHTMLRenderer(_context, _countryLookup, _rdns);
    }

    /**
     * The rDNS executor core pool size.
     * @since 0.9.70+
     */
    public static int getRdnsCorePoolSize() { return ReverseDnsLookup.getCorePoolSize(); }

    /**
     * The rDNS executor core pool size, bounded 2-8.
     * @since 0.9.70+
     */
    public static void setRdnsCorePoolSize(int size) {
        ReverseDnsLookup.setCorePoolSize(size);
    }

    /**
     * The rDNS executor max pool size.
     * @since 0.9.70+
     */
    public static int getRdnsMaxPoolSize() { return ReverseDnsLookup.getMaxPoolSize(); }

    /**
     * The rDNS executor max pool size, bounded 2-8.
     * @since 0.9.70+
     */
    public static void setRdnsMaxPoolSize(int size) {
        ReverseDnsLookup.setMaxPoolSize(size);
    }

    /**
     * The size of the rDNS cache file, in KB.
     */
    public static String rdnsCacheSize() { return ReverseDnsLookup.rdnsCacheSize(); }

    /**
     * The number of entries in the rDNS cache.
     */
    public static int countRdnsCacheEntries() { return ReverseDnsLookup.countRdnsCacheEntries(); }

    /**
     * Cache statistics for monitoring.
     * @return formatted string with cache stats
     */
    public static String getRdnsCacheStats() { return ReverseDnsLookup.getRdnsCacheStats(); }

    /**
     * The computed maximum rDNS cache size based on available memory.
     * @return the computed maximum rDNS cache size based on available memory
     * @since 0.9.61+
     */
    public static int getMaxRdnsCacheSize() { return ReverseDnsLookup.getMaxRdnsCacheSize(); }

    /**
     * Adjust the running rDNS executor pool sizes.
     * Called by the Tuner when queue depth signals scaling.
     *
     * @param coreSize new core pool size (bounded 2-8)
     * @since 0.9.70+
     */
    public void adjustRdnsPool(int coreSize) {
        _rdns.adjustPool(coreSize);
    }

    /**
     * Returns rDNS executor pending lookup count, or 0 if not running.
     *
     * @return the rdns queue size
     * @since 0.9.70+
     */
    public int getRdnsQueueSize() {
        return _rdns.getQueueSize();
    }

    /**
     * Returns rDNS executor active thread count, or 0 if not running.
     *
     * @return the rdns active count
     * @since 0.9.70+
     */
    public int getRdnsActiveCount() {
        return _rdns.getActiveCount();
    }

    /**
     * Returns rDNS executor total thread count, or 0 if not running.
     *
     * @return the rdns pool size
     * @since 0.9.70+
     */
    public int getRdnsPoolSize() {
        return _rdns.getPoolSize();
    }

    /**
     * Returns rDNS executor utilization as a ratio (0.0-1.0),
     * or {@link Double#NaN} if not running.
     *
     * @return the rdns utilization
     * @since 0.9.70+
     */
    public double getRdnsUtilization() {
        return _rdns.getUtilization();
    }

    /**
     * Whether reverse lookups are enabled.
     * @since 0.9.71+
     */
    @Override
    public boolean enableReverseLookups() {return _rdns.enableReverseLookups();}

    /**
     * Canonical hostname for the given IP address from cache or DNS.
     * If RDNS is enabled, performs a reverse DNS lookup first.
     * Falls back to local ASN database if DNS fails, returning the org name.
     *
     * @param ipAddress IP address to resolve, or null/"null" to return null
     * @return hostname, org name from ASN database, or null for invalid input
     * @since 0.9.58+
     */
    @Override
    public String getCanonicalHostName(String ipAddress) {
        return _rdns.getCanonicalHostName(ipAddress);
    }

    /**
     * The canonical host name for the given IP address, resolved synchronously.
     * @return the canonical host name
     */
    @Override
    public String getCanonicalHostNameSync(String ipAddress) {
        return _rdns.getCanonicalHostNameSync(ipAddress);
    }

    /**
     * Fast hostname lookup that never blocks on I/O (MMDB or network).
     * Returns cached result if available and useful.
     * On cache miss, queues a background job that does ASN org name lookup
     * (MMDB file read + regex normalization) and optional RDNS, then
     * returns null immediately — page rendering is never blocked.
     *
     * @return cached hostname/ASN org name, or null if not yet resolved
     * @since 0.9.70+
     */
    @Override
    public String getLocalHostName(String ipAddress) {
        return _rdns.getLocalHostName(ipAddress);
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
     * Cannot be restarted after calling this. Use restart() for that.
     *
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
        _rdns.shutdown();
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
     * Whether the comm system is running.
     * @return whether running
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
     * Whether we have capacity to handle an inbound message at the given percentage.
     */
    @Override
    public boolean haveInboundCapacity(int pct) { return _manager.haveInboundCapacity(pct); }
    /**
     * Whether we have capacity to handle an outbound message at the given percentage.
     */
    @Override
    public boolean haveOutboundCapacity(int pct) { return _manager.haveOutboundCapacity(pct); }
    /**
     * Whether we have high outbound capacity.
     */
    @Override
    public boolean haveHighOutboundCapacity() { return _manager.haveHighOutboundCapacity(); }

    /**
     * The framed average clock skew of connected peers in milliseconds, or the clock offset if we cannot answer.
     * @param percentToInclude 1-100
     * @return The framed average clock skew of connected peers in milliseconds, or the clock offset if we cannot answer.
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

    /**
     * Whether the given peer is backlogged.
     *
     * @return whether backlogged
     */
    @Override
    public boolean isBacklogged(Hash peer) {
        return _manager.isBacklogged(peer);
    }

    /**
     * Whether the given peer is established.
     * @return whether established
     */
    @Override
    public boolean isEstablished(Hash peer) {
        return _manager.isEstablished(peer);
    }

    /**
     *  Established peers.
     *
     *  @return a new list, may be modified
     *  @since 0.9.34
     */
    public List<Hash> getEstablished() {
        return _manager.getEstablished();
    }

    /**
     * Whether the given peer was recently unreachable.
     */
    @Override
    public boolean wasUnreachable(Hash peer) {
        return _manager.wasUnreachable(peer);
    }

    /**
     * Whether we are currently connecting to the given peer.
     * @return whether connecting
     */
    @Override
    public boolean isConnecting(Hash peer) {
        return _manager.isConnecting(peer);
    }

    /**
     * The known IP address of the given peer.
     * @return the IP address
     */
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

    /**
     * Force an immediate disconnect from the given peer with the given reason.
     */
    @Override
    public void forceDisconnect(Hash peer, String reason) {
        _manager.forceDisconnect(peer, reason);
    }

    /**
     * The status of the communication system.
     *
     * @return the status
     * @since 0.9.20
     */
    @Override
    public List<String> getMostRecentErrorMessages() {
        return _manager.getMostRecentErrorMessages();
    }

    /**
     * The current network status.
     * @return the status
     * @since 0.9.20
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
     * The localized status string from getStatus().toStatusString(), translated if available.
     * @return the localized status string
     * @since 0.9.45
     */
    @Override
    public String getLocalizedStatusString() {
        return Translate.getString(getStatus().toStatusString(), _context, ROUTER_BUNDLE_NAME);
    }

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
     *  Transports in use.
     *
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
        /**
         * Compare two router addresses for consistent ordering.
         */
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
            Transport udp = _manager.getTransport(Transport.STYLE_SSU);
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
     *  @return whether exempt incoming
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
     *  setListener()
     *  externalAddressReceived() (zero or more times, one for each known address)
     *  startListening();
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
     *  setListener(null)
     *  stopListening();
     *
     *  @since 0.9.16
     */
    @Override
    public void unregisterTransport(Transport t) {
        _manager.stopAndUnregister(t);
    }

    /**
     * Factory for making X25519 key pairs.
     * @return the x d h factory
     * @since 0.9.46
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

    /**
     *  Router must call after netdb is initialized.
     *  Starts the recurring country-lookup pipeline; the reverse-DNS cache
     *  file is loaded once the GeoIP queue has had a chance to settle.
     *  @since 0.9.41
     */
    private void startGeoIP() {
        _countryLookup.start();
        if (_rdns.enableReverseLookups()) {ReverseDnsLookup.readRDNSCacheFromFile();}
    }

    /**
     *  Country code for this router, from the GeoIP lookup.
     *  @return two-letter lower-case country code or null
     *  @since 0.8.11
     */
    @Override
    public String getOurCountry() {return _countryLookup.getOurCountry();}

    /**
     *  Are we in a strict country
     *  @return whether in strict country
     *  @since 0.8.13
     */
    @Override
    public boolean isInStrictCountry() {return _countryLookup.isInStrictCountry();}

    /**
     *  Are they in a strict country
     *  @param peer peer Hash
     *  @return whether in strict country
     *  @since 0.9.16
     */
    @Override
    public boolean isInStrictCountry(Hash peer) {return _countryLookup.isInStrictCountry(peer);}

    /**
     *  Are they in a strict country
     *  @param ri RouterInfo
     *  @return whether in strict country
     *  @since 0.9.16
     */
    @Override
    public boolean isInStrictCountry(RouterInfo ri) {return _countryLookup.isInStrictCountry(ri);}

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
    public String getCountry(Hash peer) {return _countryLookup.getCountry(peer);}

    /**
     *  The two-letter country code for an IP address string.
     *  @param ip IP address string
     *  @return two-letter country code or null if unknown
     */
    @Override
    public String getCountry(String ip) {return _countryLookup.getCountry(ip);}

    /**
     *  Full name for a country code.
     *  @param c country code
     *  @return full name or the code if unknown
     */
    @Override
    public String getCountryName(String c) {return _countryLookup.getCountryName(c);}

    /**
     *  Provides country code mappings.
     *  @return Unmodifiable map of lower-case country codes to untranslated names
     *  @since 0.9.53
     */
    @Override
    public Map<String, String> getCountries() {return _countryLookup.getCountries();}

    /**
     *  Queue all current destination for lookup.
     *  For internal use only.
     *  @param ip full 128 bit ip address
     */
    public void queueLookup(byte[] ip) {_countryLookup.queueLookup(ip);}

    /**
     *  Domain name from a reverse DNS hostname
     *  @return domain name only from reverse dns hostname lookups
     *  @since 0.9.58+
     */
    public static String getDomain(String hostname) {return CountryLookup.getDomain(hostname);}

    /**
     *  Return first valid IP (v4 or v6) we find, any transport.
     *  @return IP or null
     *  @since 0.9.18
     */
    public static byte[] getValidIP(RouterInfo ri) {return CountryLookup.getValidIP(ri);}

    /**
     *  IP address compatible with our capabilities (IPv4/IPv6).
     *  @param ri RouterInfo to get IP from
     *  @return IP or null
     *  @since 0.9.68+
     */
    public static byte[] getCompatibleIP(RouterInfo ri) {return CountryLookup.getCompatibleIP(ri);}

    /**
     * Renders HTML for a peer with optional extended info.
     * Uses cached RouterInfo, country info, and reverse lookup cache.
     *
     * @param peer Peer Hash
     * @param extended Whether to show extended capabilities
     * @return HTML snippet representing peer
     * @since 0.9.71+
     */
    @Override
    public String renderPeerHTML(Hash peer, boolean extended) {return _peerHTML.renderPeerHTML(peer, extended);}

    /**
     * Render the HTML flag image for the given peer.
     * @since 0.9.71+
     */
    @Override
    public String renderPeerFlag(Hash peer) {return _peerHTML.renderPeerFlag(peer);}

    /**
     * Renders the peer's capability HTML block.
     * Caches data and removes unnecessary repeated computation.
     *
     * @param peer Peer Hash
     * @param inline If true, render inline without table wrapper
     * @return HTML snippet of peer capabilities
     * @since 0.9.71+
     */
    @Override
    public String renderPeerCaps(Hash peer, boolean inline) {return _peerHTML.renderPeerCaps(peer, inline);}

    /**
     * Is everything disabled for testing?
     * @return whether dummy
     * @since 0.8.13
     */
    @Override
    public boolean isDummy() {return _context.getBooleanProperty(PROP_DISABLED);}

    /*
     * Timestamper stuff
     *
     * This is used as a backup to NTP over UDP.
     * @since 0.7.12
     */

    private static final int TIME_START_DELAY = 5*60*1000;
    private static final int TIME_REPEAT_DELAY = 8*60*1000;

    /** Start the backup timestamper */
    private void startTimestamper() {
        new Timestamper().schedule(TIME_START_DELAY);
    }

    /**
     * Update the clock offset based on the average of the peers.
     * This uses the default stratum which is lower than any reasonable
     * NTP source, so it will be ignored unless NTP is broken.
     * @since 0.7.12
     */
    private class Timestamper extends SimpleTimer2.TimedEvent {
        /**
         * Timestamper.
         */
        public Timestamper() { super(_context.simpleTimer2()); }
        /**
         * Run the scheduled task.
         */
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
             schedule(TIME_REPEAT_DELAY);
        }
    }

    /** Start the network monitor */
    private void startNetMonitor() {new NetMonitor();}

    /**
     * Simple check to see if we have a network connection
     * @since 0.9.4
     */
    private class NetMonitor extends SimpleTimer2.TimedEvent {
        private static final long SHORT_DELAY = 15*1000L;
        private static final long LONG_DELAY = 90*1000L;

        /**
         * NetMonitor.
         */
        public NetMonitor() {super(_context.simpleTimer2(), 0);}

        /**
         * Run the scheduled task.
         */
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
