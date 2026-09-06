package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;

/**
 *  Country-code lookup, caching, and strict-country policy for the router.
 *
 *  Extracted from {@link CommSystemFacadeImpl} (2026-09-06) so the country
 *  cluster - a per-peer country cache with periodic cleanup, over-capacity
 *  eviction, strict-country gating, and startup GeoIP queueing - lives apart
 *  from the facade core.  The facade retains thin delegation stubs so the
 *  public static and {@code commSystem()} APIs are unchanged.
 *
 *  All pure decision logic (cache eviction decisions, memory-independent
 *  helpers) is exposed as package-visible static methods so it can be tested
 *  without a running router; see {@code CountryLookupDecisionTest}.
 *
 *  @since 0.9.71
 */
public class CountryLookup {

    private final RouterContext _context;
    private final GeoIP _geoIP;
    private final Log _log;

    /* We hope the routerinfos are read in and things have settled down by now, but it's not required to be so */
    private static final int START_DELAY = SystemVersion.isSlow() ? 60*1000 : 5*1000;
    private static final int LOOKUP_TIME = 75*1000;
    // Re-queue the entire netDb for GeoIP lookup periodically so peers learned
    // after startup are resolved even if their page is never explicitly viewed.
    private static final int QUEUE_TIME = 10*60*1000;

    private static final int MAX_COUNTRY_CACHE_SIZE = 20000;
    private static final long COUNTRY_CACHE_EXPIRY = 60*60*1000L; // 1 hour

    private long lastUnknownPurge = 0;
    private long lastCacheCleanup = 0;
    private final ConcurrentHashMap<Hash, String> countryCache = new ConcurrentHashMap<>(MAX_COUNTRY_CACHE_SIZE);
    private final ConcurrentHashMap<Hash, Long> countryCacheTimestamps = new ConcurrentHashMap<>(MAX_COUNTRY_CACHE_SIZE);

    /**
     *  @param ctx non-null
     *  @param geoIP non-null
     *  @since 0.9.71
     */
    public CountryLookup(RouterContext ctx, GeoIP geoIP) {
        _context = ctx;
        _geoIP = geoIP;
        _log = ctx.logManager().getLog(getClass());
    }

    /**
     *  Collect the IPs for all routers in the DB and queue them for lookup.
     *  The recurring lookup pipeline starts on the first pass only.
     *
     *  @since 0.9.71
     */
    public void start() {
        new QueueAll().schedule(START_DELAY);
    }

    /**
     *  Collect the IPs for all routers in the DB, and queue them for lookup,
     *  then fire off the periodic lookup task for the first time.
     *
     *  As of 0.9.32, works only for literal IPs, ignores host names.
     */
    private class QueueAll extends SimpleTimer2.TimedEvent {
        private boolean _firstRun = true;
        /**
         * QueueAll.
         */
        public QueueAll() { super(_context.simpleTimer2()); }
        /**
         * Run the scheduled task.
         */
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
            // Only the first pass needs to start the recurring Lookup processor;
            // subsequent passes just re-queue the whole netDb so newly-learned
            // peers get a GeoIP lookup. _geoIP.add() dedupes against the cache,
            // so re-queueing already-resolved IPs is a no-op.
            if (_firstRun) {
                _firstRun = false;
                new Lookup().schedule(5000);
            }
            schedule(QUEUE_TIME);
        }
    }

    private class Lookup extends SimpleTimer2.TimedEvent {
        /**
         * Lookup.
         */
        public Lookup() { super(_context.simpleTimer2()); }
        /**
         * Run the scheduled task.
         */
        @Override
        public void timeReached() {
            (new LookupThread()).start();
            schedule(LOOKUP_TIME);
        }
    }

    /**
     *  This takes too long to run on the SimpleTimer2 queue
     *  @since 0.9.10
     */
    private class LookupThread extends I2PThread {

        /**
         * LookupThread.
         */
        public LookupThread() {
            super("GeoIP Lookup");
            setDaemon(true);
        }

        /**
         * Run the GeoIP lookup for all routers in the NetDB.
         */
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
     *  Queues a GeoIP lookup for the given IP.
     *
     *  @param ip ipv4 or ipv6
     */
    public void queueLookup(byte[] ip) {_geoIP.add(ip);}

    /**
     *  Country code for this router, from the GeoIP lookup.
     *
     *  @return two-letter lower-case country code or null
     *  @since 0.8.11
     */
    public String getOurCountry() {return _context.getProperty(GeoIP.PROP_IP_COUNTRY);}

    /**
     * Are we in a strict country
     * @return whether in strict country
     * @since 0.8.13
     */
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
     *  @return whether in strict country
     *  @since 0.9.16
     */
    public boolean isInStrictCountry(Hash peer) {
        String c = getCountry(peer);
        return c != null && StrictCountries.contains(c);
    }

    /**
     *  Are they in a strict country
     *  @param ri non-null
     *  @return whether in strict country
     *  @since 0.9.16
     */
    public boolean isInStrictCountry(RouterInfo ri) {
        byte[] ip = getIP(ri);
        Hash h = ri.getHash();
        if (ip == null) {ip = TransportImpl.getIP(h);}
        if (ip == null) {return false;}
        String c = _geoIP.get(ip);
        return c != null && StrictCountries.contains(c);
    }

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
        if (country == null) {
            // Queue the IP so it gets resolved on the next lookup cycle; this
            // guarantees eventual country resolution for any peer actually
            // displayed, not just those present at startup or explicitly viewed.
            // ip may be null when the peer has no resolvable address, so guard it.
            if (ip != null)
                queueLookup(ip);
            return "xx";
        }
        if (country.equals("xx")) {
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
        }

        if (countryCache.size() >= MAX_COUNTRY_CACHE_SIZE) {
            trimToCapacity(countryCache, countryCacheTimestamps, System.currentTimeMillis(), COUNTRY_CACHE_EXPIRY, MAX_COUNTRY_CACHE_SIZE);
        }

        countryCache.put(peer, country);
        countryCacheTimestamps.put(peer, now);

        boolean blockMyCountry = _context.getBooleanProperty(GeoIP.PROP_BLOCK_MY_COUNTRY);
        boolean isStrict = _context.commSystem().isInStrictCountry();
        boolean isHidden = _context.router().isHidden();

        if (_context.banlist().isCountryBanEnabled() && (isStrict || isHidden || blockMyCountry)) {
            String myCountry = _context.getProperty(GeoIP.PROP_IP_COUNTRY);
            if (myCountry != null && myCountry.equals(country)) {
                _geoIP.banCountry(_context, country);
            }
        }

        return country;
    }

    /**
     * The two-letter country code for an IP address string.
     * @param ip IP address string
     * @return two-letter country code or null if unknown
     */
    public String getCountry(String ip) {
        return getCountryFromIPAddress(ip);
    }

    /**
     * Convert IP string to Hash and call existing getCountry method
     * Return two-letter country code or null if unknown
     * @return the country from i p address
     */
    private String getCountryFromIPAddress(String ipAddress) {
        try {
            byte[] ipBytes = InetAddress.getByName(ipAddress).getAddress();
            return _geoIP.get(ipBytes); // Existing geoip lookup by raw IP bytes
        } catch (UnknownHostException e) {return null;}
    }

    /**
     *  Clean up stale entries from the country cache
     */
    private void cleanupCountryCache() {
        int removed = evictExpired(countryCache, countryCacheTimestamps, System.currentTimeMillis(), COUNTRY_CACHE_EXPIRY);
        if (removed > 0 && _log.shouldInfo()) {
            _log.info("Cleaned up " + removed + " stale country entries from cache");
        }
    }

    /**
     *  Full name for a country code, or the code if we don't know the name.
     */
    public String getCountryName(String c) {
        if (_geoIP == null) {return c;}
        String n = _geoIP.fullName(c);
        if (n == null) {return c;}
        return n;
    }

    /**
     *  Provides country code mappings.
     *  @return Unmodifiable map of lower-case country codes to untranslated names.
     *  Returns empty map if geoIP data is unavailable.
     *  @since 0.9.53
     */
    public Map<String, String> getCountries() {
        if (_geoIP == null) return Collections.emptyMap();
        return _geoIP.getCountries();
    }

    /**
     *  Domain name from a reverse DNS hostname.
     *
     *  Multi-part TLDs (co.uk, com.au, ...) are recognized so the registrable
     *  domain is returned rather than a bare second-level label.
     *
     *  @return domain name only from reverse dns hostname lookups
     *  @since 0.9.58+
     */
    public static String getDomain(String hostname) {
        if (hostname == null || hostname.isEmpty()) return "";

        hostname = hostname.toLowerCase().trim();
        if (hostname.startsWith(".")) hostname = hostname.substring(1);

        // Filter out empty or whitespace-only parts (malformed RDNS entries with spaces)
        String[] raw = DOT_SPLIT.split(hostname);
        List<String> filtered = new ArrayList<>(raw.length);
        for (String p : raw) {
            p = p.trim();
            if (!p.isEmpty()) filtered.add(p);
        }
        int len = filtered.size();

        if (len < 2) return filtered.isEmpty() ? hostname : filtered.get(0);

        // Try to match known multi-part TLDs
        for (int i = 1; i < len; i++) {
            StringBuilder tldBuilder = new StringBuilder();
            for (int j = i; j < len; j++) {
                if (j > i) tldBuilder.append(".");
                tldBuilder.append(filtered.get(j));
            }
            if (MULTI_PART_TLDS.contains(tldBuilder.toString())) {
                int domainIndex = len - DOT_SPLIT.split(tldBuilder.toString()).length - 1;
                StringBuilder domain = new StringBuilder();
                for (int k = domainIndex; k < len; k++) {
                    if (k > domainIndex) domain.append(".");
                    domain.append(filtered.get(k));
                }
                return domain.toString();
            }
        }

        // Default to 2-part domain
        return filtered.get(len - 2) + "." + filtered.get(len - 1);
    }

    /** Pre-compiled dot split for domain parsing */
    private static final Pattern DOT_SPLIT = Pattern.compile("\\.");

    /** Multi-part TLD list for domain extraction */
    private static final Set<String> MULTI_PART_TLDS;

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
     *  Return first IP (v4 or v6) we find, any transport.
     *  Not validated, may be local, etc.
     *
     *  As of 0.9.32, works only for literal IPs, returns null for host names.
     *
     *  @return IP or null
     */
    static byte[] getIP(RouterInfo ri) {
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

    /**
     *  IP address compatible with our capabilities (IPv4/IPv6).
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
     *  First valid IP of the specified type (IPv4 or IPv6).
     *  @return the first valid i p of type
     */
    static byte[] getFirstValidIPOfType(RouterInfo ri, boolean wantIPv6) {
        if (ri == null) {return null;}
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
    static boolean supportsIPv4(RouterContext ctx) {
        boolean ntcpEnabled = TransportManager.isNTCPEnabled(ctx);
        boolean ssuEnabled = ctx.getBooleanPropertyDefaultTrue(TransportManager.PROP_ENABLE_UDP);
        return ntcpEnabled || ssuEnabled;
    }

    /**
     *  Check if we support outbound IPv6 connections.
     */
    static boolean supportsIPv6(RouterContext ctx) {
        TransportUtil.IPv6Config ntcp6 = TransportUtil.getIPv6Config(ctx, "NTCP");
        TransportUtil.IPv6Config ssu6 = TransportUtil.getIPv6Config(ctx, "UDP");
        return ntcp6 != TransportUtil.IPv6Config.IPV6_DISABLED || ssu6 != TransportUtil.IPv6Config.IPV6_DISABLED;
    }

    /**
     *  Remove stale and unresolved ("xx") entries from the country cache.
     *
     *  An entry is expired when its timestamp is strictly older than
     *  {@code expiryMs}; "xx" (unresolved) entries are always dropped. Called
     *  both periodically from {@link #getCountry(Hash)} and as the first stage
     *  of {@link #trimToCapacity} so the hot path never recomputes the loop.
     *
     *  @param cache country cache to drain (entries removed in place)
     *  @param timestamps parallel timestamp map for the same keys
     *  @param now cache age reference in millis
     *  @param expiryMs age threshold in millis (strict {@code >})
     *  @return number of entries removed
     *  @since 0.9.71
     */
    static int evictExpired(Map<Hash, String> cache, Map<Hash, Long> timestamps, long now, long expiryMs) {
        int removed = 0;
        Iterator<Map.Entry<Hash, String>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Hash, String> entry = it.next();
            Long ts = timestamps.get(entry.getKey());
            boolean expired = ts != null && (now - ts > expiryMs);
            if (expired || isUnknownCountryCode(entry.getValue())) {
                it.remove();
                timestamps.remove(entry.getKey());
                removed++;
            }
        }
        return removed;
    }

    /**
     *  Bring an over-capacity country cache back under its size bound.
     *
     *  First advances the expired-entry pass via {@link #evictExpired}; if
     *  still at or above {@code maxSize}, drops the oldest quarter by
     *  timestamp. The redundant removeIf()/stream cascade in the original
     *  facade is preserved, so hot-path behavior (evict expired first, then
     *  oldest) is unchanged.
     *
     *  @param cache country cache to shrink (entries removed in place)
     *  @param timestamps parallel timestamp map for the same keys
     *  @param now cache age reference in millis
     *  @param expiryMs age threshold in millis for the expired-entry pass
     *  @param maxSize size bound that triggers trimming
     *  @since 0.9.71
     */
    static void trimToCapacity(Map<Hash, String> cache, Map<Hash, Long> timestamps, long now, long expiryMs, int maxSize) {
        if (cache.size() < maxSize) {return;}
        evictExpired(cache, timestamps, now, expiryMs);
        if (cache.size() >= maxSize) {
            List<Hash> toRemove = timestamps.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(maxSize / 4)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
            for (Hash key : toRemove) {
                cache.remove(key);
                timestamps.remove(key);
            }
        }
    }

    /**
     *  True when the cache stores an unresolved-country marker ("xx").
     *  @since 0.9.71
     */
    static boolean isUnknownCountryCode(String code) {return code != null && code.equals("xx");}
}
