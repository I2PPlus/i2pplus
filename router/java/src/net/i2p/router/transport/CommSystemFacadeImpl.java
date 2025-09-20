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
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
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
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
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

import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.I2PAppContext;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.crypto.X25519KeyFactory;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.router.util.EventLog;
import net.i2p.util.Addresses;
import net.i2p.util.AddressType;
import net.i2p.util.ArraySet;
import net.i2p.util.I2PThread;
import net.i2p.util.LHMCache;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;
import net.i2p.util.Translate;

public class CommSystemFacadeImpl extends CommSystemFacade {
    private final Log _log;
    private final RouterContext _context;
    private final TransportManager _manager;
    private final GeoIP _geoIP;
    private final Map<String, Object> _exemptIncoming;
    private volatile boolean _netMonitorStatus;
    private boolean _wasStarted;

    /**
     *  Disable connections for testing
     *  @since IPv6
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

    public synchronized void startup() {
        _log.info("Starting up the Comm System...");
        _manager.startListening();
        startTimestamper();
        startNetMonitor();
        _wasStarted = true;
    }

    /**
     *  Cannot be restarted after calling this. Use restart() for that.
     */
    public synchronized void shutdown() {
        _manager.shutdown();
        _geoIP.shutdown();
    }

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

    /** @return non-null, possibly empty */
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
    public boolean enableReverseLookups() {return _context.getBooleanProperty(PROP_ENABLE_REVERSE_LOOKUPS);}
    private static final Charset ENCODING = StandardCharsets.UTF_8;
    private static final String NEWLINE = "\n";

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
    public void queueLookup(byte[] ip) {
        _geoIP.add(ip);
    }

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
    private static final long EXPIRE_TIME = (!has512MB ? 1L : 2L) * 24 * 60 * 60 * 1000; // 24/48 hours expiration
    private static final long EVICT_THRESHOLD = 3L * 24 * 60 * 60 * 1000; // 3 days expiration for file eviction
    private static final int MAX_RDNS_CACHE_SIZE = !has512MB ? 20000 : 80000;
    private static final Object rdnslock = new Object();

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
     * This cache is a thread-safe ConcurrentHashMap that supports
     * concurrent reads and writes without external synchronization.
     * It replaces the previous synchronized LinkedHashMap to improve
     * lookup performance under high concurrency.
     *
     * Note that while ConcurrentHashMap is thread-safe, it does not
     * provide built-in size-based eviction like LRU. Expiration and
     * eviction are managed separately by periodic cleanup methods.
     *
     * Keys are IP addresses as Strings. Values are CacheEntry objects
     * containing hostname and timestamp.
     */
    private static final ConcurrentHashMap<String, CacheEntry> rdnsCache = new ConcurrentHashMap<>();

    // Use a synchronized LRUCache for thread safety and size-bounded cache
    //private static final Map<String, CacheEntry> rdnsCache = Collections.synchronizedMap(new LRUCache<>(MAX_RDNS_CACHE_SIZE));

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
        try (BufferedReader reader = new BufferedReader(new FileReader(fCache))) {
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
        Timer timer = new Timer(true);
        long delay = RDNS_WRITE_INTERVAL;
        timer.schedule(new RDNSCacheFileWriter(), delay, delay);
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
                System.out.println("[RDNSCache] Entries written to file: " + writtenCount);
            } catch (IOException ex) {
                System.err.println("[RDNSCache] Error updating reverse DNS cache file: " + ex.getMessage());
                ex.printStackTrace();
            }
        }
    }

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
                int writtenCount = 0;
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmpFile), ENCODING))) {
                    synchronized (rdnsCache) {
                        for (CacheEntry cacheEntry : rdnsCache.values()) {
                            if (now - cacheEntry.getTimestamp() <= EVICT_THRESHOLD) {
                                String line = rdnsEntryToString(cacheEntry) + NEWLINE;
                                writer.write(line);
                                writtenCount++;
                            }
                        }
                    }
                    writer.flush();
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
        synchronized (rdnsCache) {
            Iterator<Map.Entry<String, CacheEntry>> it = rdnsCache.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, CacheEntry> entry = it.next();
                CacheEntry ce = entry.getValue();
                if (now - ce.getTimestamp() > EXPIRE_TIME) {
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
        synchronized (rdnsCache) {
            return rdnsCache.size();
        }
    }

    /**
     * Returns the canonical hostname for the given IP address, using a cached
     * reverse DNS lookup with expiration.
     *
     * $lt;p$gt;The cache is checked first; if the entry is missing or expired,
     * a DNS lookup is performed and the result cached.
     * Returns "unknown" if lookup fails or returns the IP itself.
     *
     * @param ipAddress the IP address to resolve, or null/"null" returns null
     * @return the hostname, "unknown" if unresolved, or null for invalid input
     * @since 0.9.58+
     */
    @Override
    public String getCanonicalHostName(String ipAddress) {
        if (ipAddress == null || ipAddress.equals("null")) {return null;}

        long now = System.currentTimeMillis();
        CacheEntry cacheEntry = rdnsCache.compute(ipAddress, (key, existingEntry) -> {
            if (existingEntry != null && (now - existingEntry.getTimestamp() <= EXPIRE_TIME)) {
                // Valid cache entry found, return as is
                return existingEntry;
            }

            // Cache absent or expired, do DNS lookup (outside compute to avoid blocking)
            String hostName;
            try {
                hostName = InetAddress.getByName(key).getCanonicalHostName();
                if (hostName.equals(key)) {hostName = "unknown";}
            } catch (UnknownHostException e) {hostName = "unknown";}

            // Return new CacheEntry with updated hostname and timestamp
            return new CacheEntry(key, hostName, now);
        });

        return cacheEntry.getHostname();
    }

    /**
     *  @return domain name only from reverse dns hostname lookups
     *  @since 0.9.58+
     */

    public static String getDomain(String hostname) {
        String[] domainArray = hostname.split("\\.");
        int length = domainArray.length;

        if (length > 3 && (hostname.endsWith(".uk") || hostname.endsWith(".au") || hostname.endsWith(".nz") ||
                           hostname.contains(".co.") || hostname.contains(".ne.") || hostname.contains(".com.") ||
                           hostname.contains(".net.") || hostname.contains(".org.") || hostname.contains(".gov."))) {
            return domainArray[length - 3] + "." + domainArray[length - 2] + "." + domainArray[length - 1];
        } else if (length == 1) {return domainArray[0];}
        else if (length > 2) {return domainArray[length - 2] + "." + domainArray[length - 1];}
        return "";
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
    private static final Random random = new Random();
    private long lastLookupTime = 0;
    private long lastUnknownPurge = 0;
    private LinkedHashMap<Hash, String> countryCache = new LinkedHashMap<Hash, String>(MAX_COUNTRY_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Hash, String> eldest) {return size() > MAX_COUNTRY_CACHE_SIZE;}
    };

    /**
     *  Uses the transport IP first because that lookup is fast,
     *  then the IP from the netDb.
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
        ConcurrentHashMap<Hash, String> countryCache = new ConcurrentHashMap<>();
        String cachedCountry = countryCache.get(peer);
        long now = System.currentTimeMillis();
        long uptime = _context.router().getUptime();

        if (cachedCountry != null && !cachedCountry.equals("xx")) {return cachedCountry;}
        else if (cachedCountry != null && cachedCountry.equals("xx") && now - lastUnknownPurge > 5*1000) {
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
        if (ri != null && country == null || country == "xx") {
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

    /** Cache for reverse DNS lookups */
    private final Map<String, String> reverseLookupCache = new ConcurrentHashMap<>();

    /**
     * Renders a peer's country flag with optional reverse DNS.
     *
     * @param peer Peer Hash
     * @return HTML snippet of peer flag with country and hostname
     */
    @Override
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
                            try {return _context.commSystem().getCanonicalHostName(k);}
                            catch (Exception e) {return _t("unknown");}
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

    // Cache RouterInfo and Capacity to improve repeated lookup efficiency
    private final Map<Hash, RouterInfo> routerInfoCache = new HashMap<>();
    private final Map<Hash, String> capacityCache = new HashMap<>();

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
