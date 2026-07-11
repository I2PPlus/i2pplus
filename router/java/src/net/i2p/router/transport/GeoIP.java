package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Use at your own risk.
 */

/**
 * Manages geographic IP lookups using Tor geoip format.
 */

import com.maxmind.db.CHMCache;
import com.maxmind.geoip.InvalidDatabaseException;
import com.maxmind.geoip.LookupService;
import com.maxmind.geoip2.DatabaseReader;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import java.util.regex.Pattern;
import net.i2p.I2PAppContext;
import net.i2p.app.ClientAppManager;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Blocklist;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.BanLogger;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.update.UpdateManager;
import net.i2p.update.UpdateType;
import net.i2p.util.Addresses;
import net.i2p.util.Log;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SystemVersion;

/**
 * Manage geoip lookup in a file with the Tor geoip format.
 *
 * The lookup is expensive, so a lookup is queued with add().
 * The actual lookup of multiple IPs is fired with lookup().
 * To get a country for an IP, use get() which returns a lower-case,
 * generally two-letter country code or null.
 *
 * Everything here uses longs, since Java is signed-only, the file is
 * sorted by unsigned, and we don't store the table in memory
 * (unlike in Blocklist.java, where it's in-memory so we want to be
 * space-efficient)
 *
 * @author zzz
 */
public class GeoIP {
    private final Log _log;
    private final I2PAppContext _context;
    private static volatile BanLogger _banLogger;
    private final Map<String, String> _codeToName;
    /** code to itself to prevent String proliferation */
    private final Map<String, String> _codeCache;

    // In the following structures, an IPv4 IP is stored as a non-negative long, 0 to 2**32 - 1,
    // and the first 8 bytes of an IPv6 IP are stored as a signed long.
    private final Map<Long, String> _IPToCountry;
    /** Cap for _IPToCountry to prevent unbounded growth */
    private static final int MAX_IP_CACHE_SIZE = 32768;

/**
    private final Set<Long> _pendingSearch;
    private final Set<Long> _pendingIPv6Search;
    private final Set<Long> _notFound;
**/

    private final Set<Long> _pendingSearch;
    private final Set<Long> _pendingIPv6Search;
    private final Set<Long> _notFound;

    private final AtomicBoolean _lock;
    private int _lookupRunCount;
    private static final Map<String, List<String>> _associatedCountries;

    // ASN database for IP → org name lookups
    private volatile com.maxmind.db.Reader _asnReader;

    static final String PROP_GEOIP_ENABLED = "routerconsole.geoip.enable";
    /** Normalize ISP/org names from ASN database (strip suffixes, abbreviate, title-case) */
    static final String PROP_NORMALIZE_ISP = "routerconsole.enableISPNameNormalization";
    public static final String PROP_GEOIP_DIR = "geoip.dir";
    public static final String GEOIP_DIR_DEFAULT = "geoip";
    static final String GEOIP_FILE_DEFAULT = "geoip.txt";
    public static final String GEOIP2_FILE_DEFAULT = "GeoLite2-Country.mmdb";
    public static final String ASN_FILE_DEFAULT = "db-ip-asn.mmdb";
    static final String COUNTRY_FILE_DEFAULT = "countries.txt";
    public static final String PROP_IP_COUNTRY = "i2np.lastCountry";
    public static final String PROP_DEBIAN_GEOIP = "geoip.dat";
    public static final String PROP_DEBIAN_GEOIPV6 = "geoip.v6.dat";
    private static final String DEBIAN_GEOIP_FILE = "/usr/share/GeoIP/GeoIP.dat";
    private static final String DEBIAN_GEOIPV6_FILE = "/usr/share/GeoIP/GeoIPv6.dat";
    private static final boolean DISABLE_DEBIAN = false;
    private static final boolean ENABLE_DEBIAN = !DISABLE_DEBIAN && !(SystemVersion.isWindows() || SystemVersion.isAndroid());
    public static final String PROP_BLOCK_MY_COUNTRY = "i2np.blockMyCountry";
    /** maxmind API */
    private static final String UNKNOWN_COUNTRY_CODE = "--";
    /** db-ip.com https://db-ip.com/faq.php */
    private static final String UNKNOWN_COUNTRY_CODE2 = "ZZ";

    static {
        // To block additional countries b,c,d when blocking country a,
        // put the list a,b,c,d for country a.
        _associatedCountries = new HashMap<>(3);
        List<String> c = new ArrayList<>(3);
        c.add("cn");
        c.add("hk");
        c.add("mo");
        _associatedCountries.put("cn", Collections.unmodifiableList(c));
        _associatedCountries.put("hk", Collections.unmodifiableList(c));
        _associatedCountries.put("mo", Collections.unmodifiableList(c));
    }

    /**
     *  @param context RouterContext in production, I2PAppContext for testing only
     */
    public GeoIP(I2PAppContext context) {
        _context = context;
        _log = context.logManager().getLog(GeoIP.class);
        _codeToName = new ConcurrentHashMap<>(512);
        _codeCache = new ConcurrentHashMap<>(512);
        _IPToCountry = new ConcurrentHashMap<>(32768);
/**
        _pendingSearch = new ConcurrentHashSet<>();
        _pendingIPv6Search = new ConcurrentHashSet<>();
        _notFound = new ConcurrentHashSet<>();
**/

        _pendingSearch = ConcurrentHashMap.newKeySet();
        _pendingIPv6Search = ConcurrentHashMap.newKeySet();
        _notFound = ConcurrentHashMap.newKeySet();

        _lock = new AtomicBoolean();
        readCountryFile();
    }

    /**
     *  @since 0.9.3
     */
    public void shutdown() {
        _codeToName.clear();
        _codeCache.clear();
        _IPToCountry.clear();
        _pendingSearch.clear();
        _pendingIPv6Search.clear();
        _notFound.clear();
        com.maxmind.db.Reader reader = _asnReader;
        if (reader != null) {
            try { reader.close(); } catch (IOException e) { /* ignore */ }
            _asnReader = null;
        }
    }

    /**
     * Blocking lookup of all pending IPs.
     * Results will be added to the table and available via get() after completion.
     *
     * Public for BundleRouterInfos
     */

    public void blockingLookup() {
        if (!_context.getBooleanPropertyDefaultTrue(PROP_GEOIP_ENABLED)) {
            _pendingSearch.clear();
            _pendingIPv6Search.clear();
            return;
        }
        try {
            LookupJob j = new LookupJob();
            long ts = j.runit();
            if (ts > 0) {updateOurCountry(ts);}
        } catch (Exception e) {
            _log.error("Error during GeoIP lookup (" + e.getMessage() + ")");
        }
    }

    private class LookupJob implements Runnable {
        private static final int CLEAR = 8;

        @Override
        public void run() {
            runit();
        }

        /**
         *  @return timestamp of the geoip ipv4 file used, or 0 on failure
         */
        public long runit() {
            if (_lock.getAndSet(true))
                return 0;
            int toSearch = 0;
            int found = 0;
            File geoip2 = getGeoIP2();
            DatabaseReader dbr = null;
            long rv = 0;
            long start = _context.clock().now();
            try {
                // clear the negative cache every few runs, to prevent it from getting too big
                if (((++_lookupRunCount) % CLEAR) == 0) {_notFound.clear();}
                // add our detected addresses
                Set<String> addrs = Addresses.getAddresses(false, true);
                for (String ip : addrs) {add(ip);}
                String lastIP = _context.getProperty(UDPTransport.PROP_IP);
                if (lastIP != null) {add(lastIP);}
                lastIP = _context.getProperty(UDPTransport.PROP_IPV6);
                if (lastIP != null) {add(lastIP);}
                // IPv4
                Long[] search = _pendingSearch.toArray(new Long[_pendingSearch.size()]);
                _pendingSearch.clear();
                if (search.length > 0) {
                    toSearch += search.length;
                    Arrays.sort(search);
                    File f = new File(_context.getProperty(PROP_DEBIAN_GEOIP, DEBIAN_GEOIP_FILE));
                    // if we have both, prefer the most recent.
                    // The Debian data can be pretty old.
                    // For now, we use the file date, we don't open it up to get the metadata.
                    if (ENABLE_DEBIAN && f.exists() &&
                        (geoip2 == null || f.lastModified() > geoip2.lastModified())) {
                        // Maxmind v1 database
                        LookupService ls = null;
                        try {
                            ls = new LookupService(f, LookupService.GEOIP_STANDARD);
                            Date date = ls.getDatabaseInfo().getDate();
                            if (date != null) {
                                long time = date.getTime();
                                notifyVersion("GeoIPv4", time);
                                rv = time;
                            }
                            if (_IPToCountry.size() >= MAX_IP_CACHE_SIZE) {_IPToCountry.clear();}
                            for (int i = 0; i < search.length; i++) {
                                Long ipl = search[i];
                                long ip = ipl.longValue();
                                // returns upper case or "--"
                                com.maxmind.geoip.Country cn = ls.getCountry(ip);
                                String uc = cn != null ? cn.getCode() : UNKNOWN_COUNTRY_CODE;
                                if (!uc.equals(UNKNOWN_COUNTRY_CODE)) {
                                    String lc = uc.toLowerCase(Locale.US);
                                    String cached = _codeCache.get(lc);
                                    if (cached == null)
                                        cached = lc;
                                    _IPToCountry.put(ipl, cached);
                                    found++;
                                } else {_notFound.add(ipl);}
                            }
                        } catch (IOException ioe) {
                            _log.error("GeoIP failure", ioe);
                        } catch (InvalidDatabaseException ide) {
                            _log.error("GeoIP failure", ide);
                        } finally {
                            if (ls != null) {ls.close();}
                        }
                    } else if (geoip2 != null) {
                        // Maxmind v2 database
                        try {
                            dbr = openGeoIP2(geoip2);
                            Date buildDate = dbr.getMetadata().getBuildDate();
                            if (buildDate != null) {
                                long time = buildDate.getTime();
                                if (time > 0) {
                                    notifyVersion("GeoIP2", time);
                                    rv = time;
                                }
                            }
                            if (_IPToCountry.size() >= MAX_IP_CACHE_SIZE) {_IPToCountry.clear();}
                            for (int i = 0; i < search.length; i++) {
                                Long ipl = search[i];
                                String ipv4 = toV4(ipl);
                                // returns upper case or null
                                String uc = dbr.country(ipv4);
                                if (uc != null && !uc.equals(UNKNOWN_COUNTRY_CODE2)) {
                                    String lc = uc.toLowerCase(Locale.US);
                                    String cached = _codeCache.get(lc);
                                    if (cached == null)
                                        cached = lc;
                                    _IPToCountry.put(ipl, cached);
                                    found++;
                                } else {
                                    _notFound.add(ipl);
                                }
                            }
                        } catch (IOException ioe) {
                            _log.error("GeoIP2 failure", ioe);
                        }
                    } else {
                        // Tor-style database
                        String[] countries = readGeoIPFile(search);
                        if (countries.length > 0) {
                            String geoDir = _context.getProperty(PROP_GEOIP_DIR, GEOIP_DIR_DEFAULT);
                            File geoFile = new File(geoDir);
                            if (!geoFile.isAbsolute())
                                geoFile = new File(_context.getBaseDir(), geoDir);
                            geoFile = new File(geoFile, GEOIP_FILE_DEFAULT);
                            rv = geoFile.lastModified();
                        }
                        if (_IPToCountry.size() >= MAX_IP_CACHE_SIZE) {_IPToCountry.clear();}
                        for (int i = 0; i < countries.length; i++) {
                            if (countries[i] != null) {
                                _IPToCountry.put(search[i], countries[i]);
                                found++;
                            } else {
                                _notFound.add(search[i]);
                            }
                        }
                    }
                }
                // IPv6
                search = _pendingIPv6Search.toArray(new Long[_pendingIPv6Search.size()]);
                _pendingIPv6Search.clear();
                if (search.length > 0) {
                    toSearch += search.length;
                    Arrays.sort(search);
                    File f = new File(_context.getProperty(PROP_DEBIAN_GEOIPV6, DEBIAN_GEOIPV6_FILE));
                    if (ENABLE_DEBIAN && f.exists() &&
                        (geoip2 == null || f.lastModified() > geoip2.lastModified())) {
                        // Maxmind v1 database
                        LookupService ls = null;
                        try {
                            ls = new LookupService(f, LookupService.GEOIP_STANDARD);
                            Date date = ls.getDatabaseInfo().getDate();
                            if (date != null)
                                notifyVersion("GeoIPv6", date.getTime());
                            if (_IPToCountry.size() >= MAX_IP_CACHE_SIZE) {_IPToCountry.clear();}
                            for (int i = 0; i < search.length; i++) {
                                Long ipl = search[i];
                                long ip = ipl.longValue();
                                String ipv6 = toV6(ip);
                                // returns upper case or "--"
                                com.maxmind.geoip.Country cn6 = ls.getCountryV6(ipv6);
                                String uc = cn6 != null ? cn6.getCode() : UNKNOWN_COUNTRY_CODE;
                                if (!uc.equals(UNKNOWN_COUNTRY_CODE)) {
                                    String lc = uc.toLowerCase(Locale.US);
                                    String cached = _codeCache.get(lc);
                                    if (cached == null)
                                        cached = lc;
                                    _IPToCountry.put(ipl, cached);
                                    found++;
                                } else {
                                    _notFound.add(ipl);
                                }
                            }
                        } catch (IOException ioe) {
                            _log.error("GeoIP failure", ioe);
                        } catch (InvalidDatabaseException ide) {
                            _log.error("GeoIP failure", ide);
                        } finally {
                            if (ls != null) ls.close();
                        }
                    } else if (geoip2 != null) {
                        // Maxmind v2 database
                        try {
                            if (dbr == null)
                                dbr = openGeoIP2(geoip2);
                            if (_IPToCountry.size() >= MAX_IP_CACHE_SIZE) {_IPToCountry.clear();}
                            for (int i = 0; i < search.length; i++) {
                                Long ipl = search[i];
                                String ipv6 = toV6(ipl);
                                // returns upper case or null
                                String uc = dbr.country(ipv6);
                                if (uc != null && !uc.equals(UNKNOWN_COUNTRY_CODE2)) {
                                    String lc = uc.toLowerCase(Locale.US);
                                    String cached = _codeCache.get(lc);
                                    if (cached == null)
                                        cached = lc;
                                    _IPToCountry.put(ipl, cached);
                                    found++;
                                } else {
                                    _notFound.add(ipl);
                                }
                            }
                        } catch (IOException ioe) {
                            _log.error("GeoIP2 failure", ioe);
                        }
                     } else {
                        // I2P format IPv6 database
                        String[] countries = GeoIPv6.readGeoIPFile(_context, search, _codeCache);
                        if (_IPToCountry.size() >= MAX_IP_CACHE_SIZE) {_IPToCountry.clear();}
                        for (int i = 0; i < countries.length; i++) {
                            if (countries[i] != null) {
                                _IPToCountry.put(search[i], countries[i]);
                                found++;
                            } else {
                                _notFound.add(search[i]);
                            }
                        }
                    }
                }
            } finally {
                if (dbr != null) try { dbr.close(); } catch (IOException ioe) { /* ignored */ }
                _lock.set(false);
            }
            if (_log.shouldInfo())
                _log.info("Finished processing " + toSearch + " GeoIP RouterInfo lookups -> Found: " + found +
                          " (Time taken: " + (_context.clock().now() - start) + "ms)");
            return rv;
        }
    }

    /**
     *  Write all IP ranges for country to blocklist-country.txt.
     *  Inline, blocking.
     *
     *  @param two-letter lower-case country code
     *  @since 0.9.48
     */
    private void countryToIP(String country) {
        while (_lock.getAndSet(true)) {
            try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
        File geoip2 = getGeoIP2();
        DatabaseReader dbr = null;
        if (_log.shouldInfo())
            _log.info("Generating blocklist for our country " + country);
        long start = _context.clock().now();
        File fout = new File(_context.getConfigDir(), Blocklist.BLOCKLIST_COUNTRY_FILE);
        BufferedWriter out = null;
        List<String> countries = _associatedCountries.get(country);
        if (countries == null)
            countries = Collections.singletonList(country);
        try {
            File f = new File(_context.getProperty(PROP_DEBIAN_GEOIP, DEBIAN_GEOIP_FILE));
            // if we have both, prefer the most recent.
            // The Debian data can be pretty old.
            // For now, we use the file date, we don't open it up to get the metadata.
            if (ENABLE_DEBIAN && f.exists() &&
                (geoip2 == null || f.lastModified() > geoip2.lastModified())) {
                // Maxmind v1 database
                LookupService ls = null; // NOSONAR S2093: LookupService doesn't implement AutoCloseable
                try {
                    out = new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(fout), StandardCharsets.UTF_8));
                    ls = new LookupService(f, LookupService.GEOIP_STANDARD);
                    for (String c : countries) {
                        ls.countryToIP(c, out);
                    }
                    out.close();
                    out = null;
                    // App context for unit tests / CLI
                    if (_context.isRouterContext()) {
                        RouterContext ctx = (RouterContext) _context;
                        ctx.blocklist().addCountryFile();
                    }
                } catch (IOException ioe) {
                    _log.error("GeoIP failure", ioe);
                } catch (InvalidDatabaseException ide) {
                    _log.error("GeoIP failure", ide);
                } finally {
                    if (ls != null) ls.close();
                }
            } else if (geoip2 != null) {
                // Maxmind v2 database
                try {
                    out = new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(fout), StandardCharsets.UTF_8));
                    dbr = openGeoIP2(geoip2);
                    for (String c : countries) {
                        dbr.countryToIP(c, out);
                    }
                    out.close();
                    out = null;
                    // App context for unit tests / CLI
                    if (_context.isRouterContext()) {
                        RouterContext ctx = (RouterContext) _context;
                        ctx.blocklist().addCountryFile();
                    }
                } catch (IOException ioe) {
                    _log.error("GeoIP2 failure", ioe);
                }
            } else {
                // Tor-style database, unsupported
            }
        } finally {
            if (out != null) try { out.close(); } catch (IOException e) { /* ignored */ }
            if (dbr != null) try { dbr.close(); } catch (IOException ioe) { /* ignored */ }
            _lock.set(false);
        }
        if (_log.shouldInfo())
            _log.info("Finished generating blocklist for our country, time: " + (_context.clock().now() - start));
    }

   /**
    * Get the GeoIP2 database file
    *
    * @return null if not found
    * @since 0.9.38
    */
    private File getGeoIP2() {
        String geoDir = _context.getProperty(PROP_GEOIP_DIR, GEOIP_DIR_DEFAULT);
        File geoFile = new File(geoDir);
        if (!geoFile.isAbsolute())
            geoFile = new File(_context.getBaseDir(), geoDir);
        geoFile = new File(geoFile, GEOIP2_FILE_DEFAULT);
        if (!geoFile.exists()) {
            if (_log.shouldError())
                _log.error("GeoIP2 file not found: " + geoFile.getAbsolutePath());
            return null;
        }
        return geoFile;
    }

   /**
    * Get the ASN database file
    *
    * @return null if not found
    * @since 0.9.65+
    */
    private File getASN() {
        String geoDir = _context.getProperty(PROP_GEOIP_DIR, GEOIP_DIR_DEFAULT);
        File geoFile = new File(geoDir);
        if (!geoFile.isAbsolute())
            geoFile = new File(_context.getBaseDir(), geoDir);
        geoFile = new File(geoFile, ASN_FILE_DEFAULT);
        if (!geoFile.exists()) {
            if (_log.shouldDebug())
                _log.debug("ASN database not found: " + geoFile.getAbsolutePath());
            return null;
        }
        return geoFile;
    }

   /**
    * Open the ASN database reader (lazy, cached)
    */
    private com.maxmind.db.Reader openASN() throws IOException {
        File asnFile = getASN();
        if (asnFile == null) {return null;}
        com.maxmind.db.Reader rv = new com.maxmind.db.Reader(asnFile);
        if (_log.shouldDebug()) {_log.debug("Opened ASN Database, Metadata: " + rv.getMetadata());}
        return rv;
    }

    /**
     * Get the organization name for an IP from the local ASN database.
     * Returns the AS organization (e.g. "Google LLC") or null if not found.
     *
     * @param ipAddress IPv4 or IPv6 address string
     * @return org name or null
     * @since 0.9.65+
     */
    public String getOrgName(String ipAddress) {
        if (ipAddress == null || ipAddress.isEmpty()) {return null;}
        try {
            com.maxmind.db.Reader reader = _asnReader;
            if (reader == null) {
                synchronized (this) {
                    reader = _asnReader;
                    if (reader == null) {
                        reader = openASN();
                        _asnReader = reader;
                    }
                }
            }
            if (reader == null) {return null;}
            InetAddress ia = InetAddress.getByName(ipAddress);
            Object result = reader.get(ia);
            if (result instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) result;
                Object org = map.get("autonomous_system_organization");
                if (org instanceof String) {
                    String raw = (String) org;
                    if (_context.getProperty(PROP_NORMALIZE_ISP, true)) {
                        return normalizeOrgName(raw);
                    }
                    return raw;
                }
            }
        } catch (Exception e) {
            if (_log.shouldWarn())
                _log.warn("ASN lookup failed for " + ipAddress, e);
        }
        return null;
    }

    // --- ISP Name Normalization (ported from Python normalize-asn.py) ---

    /** Known broken org names in the MaxMind ASN DB — straight replacements */
    private static final Map<String, String> ASN_DB_OVERRIDES;
    static {
        ASN_DB_OVERRIDES = new HashMap<>();
        ASN_DB_OVERRIDES.put("CSJP )puorG talasite( oC puorG moceleT setarimE", "Emirates Telecom Group Co (etisalat Group) PJSC");
        ASN_DB_OVERRIDES.put("eteicoS esiacnarF uD enohpeletoidaR", "Societe France Du RadioTelephone");
        ASN_DB_OVERRIDES.put("etavirP namssenisuB vonayruB nitnatsnoK", "Private Business von Knutsen");
        ASN_DB_OVERRIDES.put("oixenI eigolonhcetsnoitamrofnI dnU", "Und Informationstechnologie Inexio");
    }

    /**
     * Common org/internet words used to detect character-reversed names.
     * If enough words in a name match their reversed form, the name is auto-reversed.
     */
    private static final Set<String> KNOWN_WORDS = new HashSet<>(Arrays.asList(
        // Core business/org terms
        "private", "business", "telecom", "group", "network", "internet",
        "services", "systems", "technology", "communications", "corporate",
        "international", "national", "global", "online", "digital",
        "security", "hosting", "solutions", "consulting", "management",
        "information", "development", "engineering",
        "university", "college", "institute", "foundation", "association",
        "government", "municipal", "regional", "local", "federal",
        // Infrastructure
        "cable", "wireless", "mobile", "broadband", "fiber", "optical",
        "data", "cloud", "server", "host", "domain", "portal",
        "connection", "direct", "perfect",
        // Science/education
        "research", "science", "physics", "logical", "mathematics",
        "medical", "health", "education",
        // Media
        "media", "entertainment", "production", "broadcast", "radio",
        "television", "telephone", "satellite", "space",
        // Industry
        "energy", "power", "electric", "gas", "oil", "mining",
        "finance", "bank", "insurance", "investment", "capital",
        "real", "estate", "property", "construction", "building",
        "transport", "logistics", "shipping", "airline", "marine",
        "food", "agriculture", "environment", "ecology",
        "software", "hardware", "electronics", "semiconductor",
        "automotive", "aerospace", "defense", "military",
        // Domain/tech abbreviations
        "com", "net", "org", "edu", "gov", "co", "ltd", "inc",
        "corp", "gmbh", "sarl", "sas", "bv", "nv", "ab", "kk",
        // Country/region names
        "france", "germany", "spain", "italy", "china", "japan",
        "korea", "india", "brazil", "russia", "canada", "australia",
        "emirates", "saudi", "egypt", "nigeria", "kenya",
        // City names
        "amsterdam", "london", "berlin", "paris", "tokyo",
        "virginia", "california", "texas", "florida", "york",
        // Common brand/org words
        "radio", "telephone", "telecom", "societe", "residential",
        "people", "communicate", "recommend", "balance",
        "academy", "pharmacy", "hospital", "clinic",
        "studio", "laboratory", "library", "museum",
        "church", "temple", "mosque",
        "market", "store", "shop", "retail",
        "travel", "hotel", "restaurant",
        "sports", "fitness", "gym",
        "design", "creative", "art",
        "legal", "law", "accounting",
        "marketing", "advertising", "publishing",
        "printing", "packaging", "manufacturing",
        "industrial", "chemical", "pharmaceutical",
        "biotechnology", "nanotechnology",
        "automobile", "aviation", "navigation",
        "robotics", "automation", "instrumentation",
        // German compound words common in MaxMind ASN DB
        "telekommunikation", "informationstechnologie", "dienstleistung",
        "versicherung", "elektronik", "netzwerk", "datenverarbeitung",
        "nachrichtentechnik", "systemhaus", "systemintegration",
        "unternehmen", "dienstanbieter", "breitband", "glasfaser",
        // French/Spanish/Portuguese common org words
        "telecomunicaciones", "telecomunicacao", "informatica",
        "soluciones", "servicios", "tecnologia", "comunicacoes",
        "provedores", "redes", "societe", "entreprise"
    ));

    /** Reverse a single word */
    private static String reverseWord(String word) {
        return new StringBuilder(word).reverse().toString();
    }

    /** Suffixes to strip from org names (longer patterns first) */
    private static final String[] SUFFIX_PATTERNS = {
        ",?\\s+Pty\\.?\\s+Ltd\\.?$",
        ",?\\s+PTY\\s+LTD\\.?$",
        ",?\\s+Limited\\s+Liability\\s+Partnership\\.?$",
        ",?\\s+Limited\\.?$",
        ",?\\s+LIMITED\\.?$",
        ",?\\s+Ltd\\.?\\s+Co\\.?$",
        ",?\\s+LTD\\.?\\s+CO\\.?$",
        ",?\\s+Ltd\\.?$",
        ",?\\s+LTD\\.?$",
        ",?\\s+Inc\\.?$",
        ",?\\s+INC\\.?$",
        ",?\\s+Incorporated\\.?$",
        ",?\\s+LLC\\.?$",
        ",?\\s+LLP\\.?$",
        ",?\\s+Corporation\\.?$",
        ",?\\s+CORPORATION\\.?$",
        ",?\\s+Corp\\.?\\s+Co\\.?$",
        ",?\\s+Corp\\.?$",
        ",?\\s+CORP\\.?$",
        ",?\\s+Company\\.?$",
        ",?\\s+COMPANY\\.?$",
        ",?\\s+Co\\.?\\s+Ltd\\.?$",
        ",?\\s+CO\\.?\\s+LTD\\.?$",
        ",?\\s+Co\\.?$",
        ",?\\s+CO\\.?$",
        ",?\\s+S\\.?\\s+A\\.?\\s+R\\.?\\s+L\\.?$",
        ",?\\s+S\\.?\\s+r\\.?\\s+l\\.?$",
        ",?\\s+S\\.?\\s+A\\.?$",
        ",?\\s+SA\\.?$",
        ",?\\s+GmbH\\s*&\\s+Co\\.?\\s+KG\\.?$",
        ",?\\s+GmbH\\s*&\\s+Co\\.?$",
        ",?\\s+GmbH\\.?$",
        ",?\\s+B\\.?\\s+V\\.?$",
        ",?\\s+BV\\.?$",
        ",?\\s+N\\.?\\s+V\\.?$",
        ",?\\s+NV\\.?$",
        ",?\\s+PLC\\.?$",
        ",?\\s+Group\\.?$",
        ",?\\s+GROUP\\.?$",
        ",?\\s+SpA\\.?$",
        ",?\\s+Sp\\.?\\s+Z\\s+o\\.?\\s+o\\.?\\s+L\\.?$",
        ",?\\s+Pvt\\.?\\s*(Ltd\\.?)?$",
        ",?\\s+PVT\\.?\\s*(LTD\\.?)?$",
    };
    private static final Pattern[] SUFFIX_COMPILED;
    static {
        SUFFIX_COMPILED = new Pattern[SUFFIX_PATTERNS.length];
        for (int i = 0; i < SUFFIX_PATTERNS.length; i++) {
            SUFFIX_COMPILED[i] = Pattern.compile(SUFFIX_PATTERNS[i], Pattern.CASE_INSENSITIVE);
        }
    }

    /** Words to keep uppercase */
    private static final Set<String> KEEP_UPPER = new HashSet<>(Arrays.asList(
        "IP", "IPv4", "IPv6", "ATM", "TV", "DNS", "IPTV", "AS",
        "USA", "UK", "EU", "APAC", "EMEA", "LAN", "WAN", "DSL",
        "ISDN", "GSM", "CDMA", "UMTS", "LTE", "WiFi", "WiMAX",
        "LLC", "LTD", "INC", "CORP", "GMBH", "PLC", "AG", "SE", "AB", "KK",
        "JSC", "OAO", "ZAO", "OOO", "CN",
        "IMS", "TOT", "ISPS",
        "ABSA", "MTN", "VOD", "TELKOM", "SBC", "BT", "AT&T", "NTL",
        "RCN", "MCI"
    ));

    /** Brand names that should keep their exact casing */
    private static final Map<String, String> BRAND_NAMES = new HashMap<>();
    static {
        String[][] brands = {
            {"SoftBank", "SoftBank"}, {"T-Mobile", "T-Mobile"}, {"Telia", "Telia"},
            {"KPN", "KPN"}, {"IIJ", "IIJ"}, {"NTT", "NTT"}, {"KDDI", "KDDI"},
            {"SK", "SK"}, {"LG", "LG"}, {"Qwest", "Qwest"}, {"Sprint", "Sprint"},
            {"Verizon", "Verizon"}, {"Comcast", "Comcast"}, {"Charter", "Charter"},
            {"Cogent", "Cogent"}, {"Hurricane", "Hurricane"}, {"Telenor", "Telenor"},
            {"Tele2", "Tele2"}, {"Swisscom", "Swisscom"}, {"Deutsche", "Deutsche"},
            {"Orange", "Orange"}, {"Telefonica", "Telefonica"},
        };
        for (String[] b : brands) {BRAND_NAMES.put(b[0], b[1]);}
    }

    /** Abbreviation map for verbose Spanish/Portuguese/etc words */
    private static final String[][] ABBREV_ENTRIES = {
        {"Telecomunicaciones", "Telecom"}, {"Telecomunicacoes", "Telecom"},
        {"Telecomunicacoes", "Telecom"}, {"Companhia", "Co"}, {"Compania", "Co"},
        {"Servicios", "Svc"}, {"Servicio", "Svc"}, {"Servicos", "Svc"},
        {"Redes", "Net"}, {"Cooperativa", "Coop"}, {"Sociedad", "SA"},
        {"Comunicacao", "Comms"}, {"Informatica", "IT"}, {"Tecnologia", "Tech"},
        {"Multimidia", "Multi"}, {"Equipamentos", "Equip"},
        {"Provedores", "Prov"}, {"Provedor", "Prov"},
        {"Solucoes", "Sols"}, {"Associacao", "Assoc"}, {"Comercio", "Comm"},
        {"Desenvolvimento", "Dev"}, {"Economico", "Econ"},
        {"Nacional", "Natl"}, {"Internacional", "Intl"},
        {"University", "Univ"}, {"Company", "Co"},
        {"Telekomunikasyon", "Telecom"}, {"Komunikasi", "Comms"},
        {"Informatika", "IT"}, {"Sirketi", "Co"}, {"Ticaret", "Trade"},
        {"Limited", "Ltd"}, {"Mbh", "GmbH"}, {"Information", "Info"},
        {"Centre", "Center"}, {"Department", "Dept"}, {"Agricultural", "Agri"},
        {"Technical", "Tech"}, {"Metropolitan", "Metro"}, {"Headquarters", "HQ"},
        {"Corporation", "Corp"}, {"Organization", "Org"}, {"Institution", "Inst"},
        {"Administration", "Admin"}, {"Commission", "Comm"}, {"Authority", "Auth"},
        {"Institute", "Inst"}, {"Foundation", "Fdn"}, {"Association", "Assoc"},
        {"Committee", "Comm"}, {"Division", "Div"}, {"Directorate", "Dir"},
        {"Ministry", "Min"}, {"National", "Natl"}, {"Federal", "Fed"},
        {"Telecommunications", "Telecom"}, {"Infrastructure", "Infra"},
        {"Industries", "Ind"}, {"Industrial", "Ind"},
        {"Multimedia", "Multi"}, {"Negocios", "Biz"},
        {"Ingenieria", "Eng"}, {"Ingenieur", "Eng"},
        {"Kommunale", "Muni"}, {"Regionalis", "Regional"},
        {"Autonomous", "Auto"}, {"Non-profit", "Nonprofit"},
    };
    private static final Pattern[][] ABBREV_COMPILED;
    static {
        ABBREV_COMPILED = new Pattern[ABBREV_ENTRIES.length][];
        for (int i = 0; i < ABBREV_ENTRIES.length; i++) {
            ABBREV_COMPILED[i] = new Pattern[]{
                Pattern.compile("\\b" + Pattern.quote(ABBREV_ENTRIES[i][0]) + "\\b", Pattern.CASE_INSENSITIVE),
                null
            };
        }
    }

    /** Filler prepositions/articles to drop */
    private static final Pattern FILLER_DROP = Pattern.compile(
        "\\b(?:de|do|da|dos|das|em|para|por|com|e|y|o|a|os|as|dan|serta|untuk|dari|ke|pada|" +
        "im|am|von|der|den|del|della|delle|dello|alla|alle|il|le|la|i|gli|un|una|sul|negli|nel|" +
        "ve|fuer|and|of|the|for|in|on|at|to|by|with|from|as|is|or)\\b",
        Pattern.CASE_INSENSITIVE
    );

    /** Embedded ASN numbers (e.g. AS12345) */
    private static final Pattern ASN_EMBEDDED = Pattern.compile("\\bAS\\d+\\b\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASN_TRAILING = Pattern.compile("\\s*\\bAS\\d+\\b$", Pattern.CASE_INSENSITIVE);

    /** Paired single quotes around text (e.g. 'TIMEWEB') — keeps internal apostrophes like O'Brien */
    private static final Pattern PAIRED_SINGLE_QUOTES = Pattern.compile("'([^']{2,})'");

    /** Trailing parentheticals to strip */
    private static final Pattern PAREN_TRAILING = Pattern.compile("\\s*\\([^)]{0,50}\\)\\s*$");

    /** Leading/trailing junk */
    private static final Pattern LEADING_JUNK = Pattern.compile("^[-\u2013\u2014\\s\"']+");
    private static final Pattern TRAILING_JUNK = Pattern.compile("[-\u2013\u2014\\s\"']+$");
    private static final Pattern DOUBLE_COMMA = Pattern.compile(",\\s*,");
    private static final Pattern DOUBLE_SPACE = Pattern.compile("  +");

    /** Verbose prefixes to strip */
    private static final Pattern[] STRIP_PREFIXES = {
        Pattern.compile("^(Internet Domain Name System)\\s+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(Internet (?:Backbone|Network|Service|Provider|Telecom|Communications))\\s+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(Autonomous System(?:\\s+Number)?)\\s+(?:of|for)\\s+", Pattern.CASE_INSENSITIVE),
    };

    /** Filler words that can be trimmed from long names */
    private static final Pattern FILLER_WORDS = Pattern.compile(
        "\\b(?:Center|Centre|Engineering|Development|Technical|Technology|Information|" +
        "Communications?|Telecommunications?|Network(?:s)?|Services?|Solutions?|Systems?|" +
        "Global|International)\\b",
        Pattern.CASE_INSENSITIVE
    );

    /** Precompiled pattern for short all-caps acronym detection */
    private static final Pattern ACRONYM_PATTERN = Pattern.compile("[A-Z]+");

    /** Names that should be returned as-is */
    private static final Pattern[] SKIP_PATTERNS = {
        Pattern.compile("^Reserved\\s+AS", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^Autonomous\\s+System\\s+number", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^N/?A$", Pattern.CASE_INSENSITIVE),
    };

    /**
     * Detect and fix character-reversed words from MaxMind ASN DB.
     * e.g. "eteicoS esiacnarF uD enohpeletoidaR" -> "Societe France Du RadioTelephone"
     *
     * Two-pass detection:
     * 1. Word-by-word: words starting lowercase and ending uppercase are likely reversed.
     * 2. Full-string: try reversing the entire name for compound words without spaces.
     * Picks whichever pass matches more known words.
     */
    private static String fixReversedWords(String name) {
        String[] words = name.split("\\s+");
        if (words.length < 1) {return name;}

        // Pass 1: full-string reversal (handles compound words and whole-string reversals)
        String fullResult = tryFullStringReversal(name);

        // Pass 2: word-by-word reversal using case-pattern detection
        String wordResult = tryWordReversal(words);

        // Pick the result with more known-word matches.
        // On tie, prefer full-string reversal (more robust for whole-string reversals).
        // Word-reversal only wins if it scores strictly better.
        int fullScore = countKnownWordMatches(fullResult);
        int wordScore = countKnownWordMatches(wordResult);
        if (fullScore > 0 && fullScore >= wordScore) {return fullResult;}
        if (wordScore > fullScore) {return wordResult;}
        return name;
    }

    /** Try reversing individual words that look reversed (lowercase start, uppercase end). */
    private static String tryWordReversal(String[] words) {
        boolean anyReversed = false;
        for (String w : words) {
            if (looksReversed(w)) {anyReversed = true; break;}
        }
        if (!anyReversed) {return "";}

        StringBuilder fixed = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {fixed.append(' ');}
            fixed.append(looksReversed(words[i]) ? reverseWord(words[i]) : words[i]);
        }
        return fixed.toString();
    }

    /** Try reversing the entire string as one block (for compound words). */
    private static String tryFullStringReversal(String name) {
        String reversed = reverseWord(name);
        // Only use if the reversed form looks like normal text
        // (contains at least one word starting with uppercase)
        String[] revWords = reversed.split("\\s+");
        for (String w : revWords) {
            if (w.length() > 1 && Character.isUpperCase(w.charAt(0))) {return reversed;}
        }
        return "";
    }

    /** Count how many space-separated words match known words. */
    private static int countKnownWordMatches(String text) {
        if (text.isEmpty()) {return 0;}
        int count = 0;
        for (String w : text.split("\\s+")) {
            if (KNOWN_WORDS.contains(w.toLowerCase(Locale.US))) {count++;}
        }
        return count;
    }

    /**
     * A word looks reversed if it starts with a lowercase letter and ends with
     * an uppercase letter — normal English/org words don't do this.
     * Excludes short words (likely abbreviations) and all-uppercase tokens.
     */
    private static boolean looksReversed(String word) {
        if (word.length() < 3) {return false;}
        char first = word.charAt(0);
        char last = word.charAt(word.length() - 1);
        return Character.isLowerCase(first) && Character.isUpperCase(last);
    }

    /**
     * Normalize an ISP/org name from the ASN database.
     * Strips suffixes, abbreviates verbose words, title-cases with acronym preservation.
     *
     * @param raw original org name from MMDB
     * @return normalized name
     * @since 0.9.70+
     */
    public static String normalizeOrgName(String raw) {
        if (raw == null || raw.isEmpty()) {return raw;}

        String name = raw.trim();

        // Skip reserved / non-org names
        for (Pattern pat : SKIP_PATTERNS) {
            if (pat.matcher(name).find()) {return name;}
        }

        // Known broken org names in the MaxMind ASN DB (reversed strings, etc.)
        String fixed = ASN_DB_OVERRIDES.get(name);
        if (fixed != null) return fixed;

        // Auto-detect reversed words: if multiple words look character-reversed, fix them
        name = fixReversedWords(name);

        // Strip embedded ASN numbers
        name = ASN_EMBEDDED.matcher(name).replaceAll("");
        name = ASN_TRAILING.matcher(name).replaceAll("");
        name = name.trim();

        // Strip verbose prefixes
        for (Pattern pat : STRIP_PREFIXES) {
            name = pat.matcher(name).replaceAll("");
        }

        // Abbreviate verbose words
        for (int i = 0; i < ABBREV_ENTRIES.length; i++) {
            name = ABBREV_COMPILED[i][0].matcher(name).replaceAll(ABBREV_ENTRIES[i][1]);
        }

        // Drop filler prepositions/articles
        name = FILLER_DROP.matcher(name).replaceAll(" ");
        name = DOUBLE_SPACE.matcher(name).replaceAll(" ");
        name = name.trim();

        // Strip corporate suffixes (longer patterns first via sorted array)
        for (Pattern pat : SUFFIX_COMPILED) {
            name = pat.matcher(name).replaceAll("");
        }
        name = name.trim();

        // Strip trailing parentheticals
        name = PAREN_TRAILING.matcher(name).replaceAll("");

        // Clean punctuation
        name = LEADING_JUNK.matcher(name).replaceAll("");
        name = TRAILING_JUNK.matcher(name).replaceAll("");
        name = name.replace("\"", "");
        name = PAIRED_SINGLE_QUOTES.matcher(name).replaceAll("$1");
        // Collapse consecutive apostrophes (e.g. Fruit'' -> Fruit)
        name = name.replace("''", "'");
        name = DOUBLE_COMMA.matcher(name).replaceAll(",");
        name = DOUBLE_SPACE.matcher(name).replaceAll(" ");
        name = name.replaceAll("^[ ,.]+|[ ,.]+$", "");

        // Title case with acronym preservation
        name = titleCasePreserve(name);

        // Final trim
        name = name.replaceAll("^[ ,.]+|[ ,.]+$", "");

        // If still too long, trim trailing filler words
        if (name.length() > 40) {
            String[] words = name.split("\\s+");
            StringBuilder trimmed = new StringBuilder();
            for (int i = words.length - 1; i >= 0; i--) {
                if (trimmed.length() > 0 && trimmed.length() + words[i].length() + 1 > 40
                    && FILLER_WORDS.matcher(words[i]).matches()) {
                    continue;
                }
                if (trimmed.length() > 0) {trimmed.append(' ');}
                trimmed.append(words[i]);
            }
            if (trimmed.length() > 5) {
                name = trimmed.reverse().toString();
            }
        }

        // Absolute cap
        if (name.length() > 50) {
            name = name.substring(0, 47);
            int lastSpace = name.lastIndexOf(' ');
            if (lastSpace > 10) {name = name.substring(0, lastSpace);}
            name += "...";
        }

        return name;
    }

    /**
     * Title-case a name while preserving acronyms and brand names.
     */
    private static String titleCasePreserve(String name) {
        String[] words = name.split("\\s+");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            String clean = w.endsWith(".") ? w.substring(0, w.length() - 1) : w;
            // Check brand names (exact match)
            String brand = BRAND_NAMES.get(clean);
            if (brand != null) {
                if (result.length() > 0) {result.append(' ');}
                result.append(brand);
            } else if (KEEP_UPPER.contains(clean.toUpperCase(Locale.ROOT))) {
                if (result.length() > 0) {result.append(' ');}
                result.append(w.toUpperCase(Locale.ROOT));
            } else if (w.length() <= 4 && w.equals(w.toUpperCase(Locale.ROOT)) && ACRONYM_PATTERN.matcher(w).matches()) {
                // Short all-caps in source — likely acronym
                if (result.length() > 0) {result.append(' ');}
                result.append(w);
            } else {
                if (result.length() > 0) {result.append(' ');}
                result.append(Character.toUpperCase(w.charAt(0)));
                if (w.length() > 1) {result.append(w.substring(1).toLowerCase(Locale.ROOT));}
            }
        }
        return result.toString();
    }

    /**
    * Open a GeoIP2 database
    * @since 0.9.38
    */
    private DatabaseReader openGeoIP2(File geoFile) throws IOException {
        DatabaseReader.Builder b = new DatabaseReader.Builder(geoFile);
        b.withCache(new CHMCache(256));
        DatabaseReader rv = b.build();
        if (_log.shouldDebug()) {_log.debug("Opened GeoIP2 Database, Metadata: " + rv.getMetadata());}
        long time = rv.getMetadata().getBuildDate().getTime();
        notifyVersion("GeoIP2", time);
        return rv;
    }

   /**
    * Return the current GeoIP database version
    * @since 0.9.65+
    */
    public String getGeoIPBuildInfo() {
        File geoFile = getGeoIP2();
        if (geoFile == null) {return "GeoIP Db not found";}

        long fileSize = geoFile.length();
        double fileSizeMB = fileSize / (1024.0 * 1024.0);
        DecimalFormat df = new DecimalFormat("#.0");
        String formattedFileSize = df.format(fileSizeMB);
        String filePath = geoFile.getAbsolutePath();

        try (DatabaseReader reader = openGeoIP2(geoFile)) {
            long buildTime = reader.getMetadata().getBuildDate().getTime();
            Date buildDate = new Date(buildTime);
            SimpleDateFormat sdf = new SimpleDateFormat("MMM d, yyyy");
            return "<b>Built:</b> " + sdf.format(buildDate) + "&ensp;<b>Size:</b> " + formattedFileSize + "MB&ensp;<b>Location:</b> " + filePath;
        } catch (Exception e) {return "Unknown GeoIP Db version";}
    }

   /**
    * Return the current ASN database version
    * @since 0.9.65+
    */
    public String getASNBuildInfo() {
        File asnFile = getASN();
        if (asnFile == null) {return "ASN Db not found";}

        long fileSize = asnFile.length();
        double fileSizeMB = fileSize / (1024.0 * 1024.0);
        DecimalFormat df = new DecimalFormat("#.0");
        String formattedFileSize = df.format(fileSizeMB);
        String filePath = asnFile.getAbsolutePath();

        try (com.maxmind.db.Reader reader = openASN()) {
            if (reader == null) {return "ASN Db not found";}
            long buildTime = reader.getMetadata().getBuildDate().getTime();
            Date buildDate = new Date(buildTime);
            SimpleDateFormat sdf = new SimpleDateFormat("MMM d, yyyy");
            return "<b>Built:</b> " + sdf.format(buildDate) + "&ensp;<b>Size:</b> " + formattedFileSize + "MB&ensp;<b>Location:</b> " + filePath;
        } catch (Exception e) {return "Unknown ASN Db version";}
    }

   /**
    * Read in and parse the country file.
    * The file need not be sorted.
    *
    * Acceptable formats:
    *   #comment (# must be in column 1)
    *   code,full name
    *
    * Example:
    *   US,UNITED STATES
    *
    * To create:
    * wget http://ip-to-country.webhosting.info/downloads/ip-to-country.csv.zip
    * unzip ip-to-country.csv.zip
    * cut -d, -f3,5 < ip-to-country.csv|sed 's/"//g' | sort | uniq > countries.txt
    *
    */
    private void readCountryFile() {
        String geoDir = _context.getProperty(PROP_GEOIP_DIR, GEOIP_DIR_DEFAULT);
        File geoFile = new File(geoDir);
        if (!geoFile.isAbsolute())
            geoFile = new File(_context.getBaseDir(), geoDir);
        geoFile = new File(geoFile, COUNTRY_FILE_DEFAULT);
        if (!geoFile.exists()) {
            if (_log.shouldError())
                _log.error("Country file not found: " + geoFile.getAbsolutePath());
            return;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(geoFile), StandardCharsets.UTF_8))) {
            String line = null;
            while ( (line = br.readLine()) != null) {
                try {
                    if (line.isEmpty() || line.charAt(0) == '#') {
                        continue;
                    }
                    String[] s = DataHelper.split(line, ",");
                    if (s.length < 2) {continue;}
                    String lc = s[0].toLowerCase(Locale.US);
                    _codeToName.put(lc, s[1]);
                    _codeCache.put(lc, lc);
                } catch (IndexOutOfBoundsException ioobe) { /* ignored */ }
            }
        } catch (IOException ioe) {
            if (_log.shouldError())
                _log.error("Error reading the Country File", ioe);
        }
    }

   /**
    * Read in and parse the geoip file.
    * The geoip file must be sorted, and may not contain overlapping entries.
    *
    * Acceptable formats (IPV4 only):
    *   #comment (# must be in column 1)
    *   integer IP,integer IP, country code
    *
    * Example:
    *   121195296,121195327,IT
    *
    * This is identical to the Tor geoip file, which can be found in
    * src/config/geoip in their distribution, or /usr/local/lib/share/tor/geoip
    * in their installation.
    * Thanks to Tor for finding a source for the data, and the format script.
    *
    * To create:
    * wget http://ip-to-country.webhosting.info/downloads/ip-to-country.csv.zip
    * unzip ip-to-country.csv.zip
    * cut -d, -f0-3 < ip-to-country.csv|sed 's/"//g' > geoip.txt
    *
    * @param search a sorted array of IPs to search
    * @return an array of country codes, same order as the search param,
    *         or a zero-length array on failure
    *
    */
    private String[] readGeoIPFile(Long[] search) {
        String geoDir = _context.getProperty(PROP_GEOIP_DIR, GEOIP_DIR_DEFAULT);
        File geoFile = new File(geoDir);
        if (!geoFile.isAbsolute()) {geoFile = new File(_context.getBaseDir(), geoDir);}
        geoFile = new File(geoFile, GEOIP_FILE_DEFAULT);
        if (!geoFile.exists()) {
            if (_log.shouldError()) {_log.error("GeoIP file not found: " + geoFile.getAbsolutePath());}
            return new String[0];
        }
        String[] rv = new String[search.length];
        int idx = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(geoFile), StandardCharsets.ISO_8859_1))) {
            String buf = null;
            notifyVersion("Torv4", geoFile.lastModified());
            while ((buf = br.readLine()) != null && idx < search.length) {
                try {
                    if (buf.isEmpty() || buf.charAt(0) == '#') {continue;}
                    String[] s = DataHelper.split(buf, ",");
                    if (s.length < 3) {continue;}
                    long ip1 = Long.parseLong(s[0]);
                    long ip2 = Long.parseLong(s[1]);
                    while (idx < search.length && search[idx].longValue() < ip1) {idx++;}
                    while (idx < search.length && search[idx].longValue() >= ip1 && search[idx].longValue() <= ip2) {
                        String lc = s[2].toLowerCase(Locale.US);
                        // replace the new string with the identical one from the cache
                        String cached = _codeCache.get(lc);
                        if (cached == null) {cached = lc;}
                        rv[idx++] = cached;
                    }
                } catch (IndexOutOfBoundsException ioobe) { /* ignored */ }
                catch (NumberFormatException nfe) { /* ignored */ }
            }
        } catch (IOException ioe) {
            if (_log.shouldError()) {_log.error("Error reading the GeoIP file", ioe);}
        }

        return rv;
    }

    /**
     *  Tell the update manager.
     *
     *  @since 0.9.45
     */
    private void notifyVersion(String subtype, long version) {
        notifyVersion(_context, subtype, version);
    }

    /**
     *  Tell the update manager.
     *
     *  @since 0.9.45
     */
    static void notifyVersion(I2PAppContext ctx, String subtype, long version) {
        if (version <= 0) {return;}
        ClientAppManager cmgr = ctx.clientAppManager();
        if (cmgr != null) {
            UpdateManager umgr = (UpdateManager) cmgr.getRegisteredApp(UpdateManager.APP_NAME);
            if (umgr != null) {
                umgr.notifyInstalled(UpdateType.GEOIP, subtype, Long.toString(version));
            }
        }
    }

    /**
     *  Put our country code in the config, where others (such as Timestamper) can get it,
     *  and it will be there next time at startup.
     *
     *  Does nothing in I2PAppContext
     *  @param ts the timestamp of the geoip file that was read, greater than zero
     */
    private void updateOurCountry(long ts) {
        if (! (_context instanceof RouterContext)) {return;}
        RouterContext ctx = (RouterContext) _context;
        String oldCountry = ctx.router().getConfigSetting(PROP_IP_COUNTRY);
        boolean isHidden = ctx.getBooleanProperty(Router.PROP_HIDDEN_HIDDEN);
        boolean blockMyCountry = ctx.getBooleanProperty(PROP_BLOCK_MY_COUNTRY);
        RouterInfo us = ctx.router().getRouterInfo();
        String country = null;

        // we should always have a RouterInfo by now, but we had one report of an NPE here
        if (us != null) {
            // try our published addresses
            for (RouterAddress ra : us.getAddresses()) {
                byte[] ip = ra.getIP();
                if (ip != null) {
                    country = get(ip);
                    if (country != null) {break;}
                }
            }
        }
        if (country == null) {
            // try our detected addresses
            Set<String> addrs = Addresses.getAddresses(false, true);
            for (String ip : addrs) {
                country = get(ip);
                if (country != null) {break;}
            }
            if (country == null) {
                String lastIP = _context.getProperty(UDPTransport.PROP_IP);
                if (lastIP != null) {
                    country = get(lastIP);
                    if (country == null) {
                        lastIP = _context.getProperty(UDPTransport.PROP_IPV6);
                        if (lastIP != null) {country = get(lastIP);}
                    }
                }
            }
        }
        if (country != null && !country.equals(oldCountry)) {
            if (_log.shouldDebug()) {
                _log.debug("Our router's previously identified country was " + oldCountry + " -> New country is " + country);
            }
            boolean wasStrict = ctx.commSystem().isInStrictCountry();
            String wasStrictCountry = wasStrict ? "strict" : "non-strict";
            ctx.router().saveConfig(PROP_IP_COUNTRY, country);
            boolean isStrict = ctx.commSystem().isInStrictCountry();
            String isStrictCountry = isStrict ? "strict" : "non-strict";
            if (_log.shouldInfo() && isStrict != wasStrict) {
                _log.info("Our router's previously identified country (" + oldCountry + ") was " + wasStrictCountry +
                          " -> New country (" + country + ") is designated as " + isStrictCountry);
            }
            if (isStrict || isHidden || blockMyCountry) {
                countryToIP(country); // generate country blocklist
                banCountry(ctx, country); // go thru the netdb
                if ((blockMyCountry || isHidden) && _log.shouldWarn()) {
                    _log.warn("Banning all routers from our country (" + country + ") -> " +
                              (isHidden ? "Hidden Mode is active" : "Enabled via configuration"));
                }
            } else {
                // remove country blocklist, won't take effect until restart
                File bc = new File(_context.getConfigDir(), Blocklist.BLOCKLIST_COUNTRY_FILE);
                bc.delete(); // NOSONAR false positive S899
                if (_log.shouldWarn()) {
                    _log.warn("Removing global ban for all routers from our country (" + country + ") -> " +
                              "Restart required");
                }
            }
            if (wasStrict != isStrict && !isHidden) {
                if (isStrict) {
                    String name = fullName(country);
                    if (name == null) {name = country;}
                    _log.logAlways(Log.WARN, "Enabling Hidden mode for additional security features in " + name +
                                             "\n* You may override this setting on the network configuration page if required");
                }
                ctx.router().rebuildRouterInfo();
            }
        } else if (country != null) {
            // No change, but we may need to update blocklist-country.txt
            boolean isStrict = ctx.commSystem().isInStrictCountry();
            if (isStrict || isHidden || blockMyCountry) {
                File bc = new File(_context.getConfigDir(), Blocklist.BLOCKLIST_COUNTRY_FILE); // check country blocklist timestamp
                long lm = bc.lastModified();
                if (lm < ts) {countryToIP(country);} // regenerate blocklist
                if (_lookupRunCount == 1) {banCountry(ctx, country);} // go thru the netdb
            }
        }
    }

    /**
     * Bans routers located in a specified country.
     *
     * This method iterates through all known routers in the network database
     * and adds those located in the specified country to the banlist.
     * Routers are banned indefinitely with a reason message that reflects
     * whether the application is in 'block my country' mode or 'hidden' mode.
     *
     * @param ctx the RouterContext instance providing access to network database and banlist
     * @param country the country code (ISO 3166-1 alpha-2 format) of the country whose routers should be banned
     * @since 0.9.48 Introduced method for banning routers by country
     */
    public static void banCountry(RouterContext ctx, String country) {
        if (!ctx.banlist().isCountryBanEnabled()) return;
        BanLogger bl = _banLogger;
        if (bl == null) {
            synchronized (GeoIP.class) {
                bl = _banLogger;
                if (bl == null) {
                    bl = new BanLogger();
                    bl.initialize(ctx);
                    _banLogger = bl;
                }
            }
        }
        boolean blockMyCountry = ctx.getBooleanProperty(PROP_BLOCK_MY_COUNTRY);
        for (Hash h : ctx.netDb().getAllRouters()) {
            String hisCountry = ctx.commSystem().getCountry(h);
            if (country.equals(hisCountry)) {
                if (blockMyCountry) {
                    _banLogger.logBanForever(h, ctx, "In our country (banned via config)");
                    ctx.banlist().banlistRouterForever(h, "In our country (banned via config)");
                } else {
                    _banLogger.logBanForever(h, ctx, "In our country (we are in Hidden mode)");
                    ctx.banlist().banlistRouterForever(h, "In our country (we are in Hidden mode)");
                }
            }
        }
    }

    /**
     * Add to the list needing lookup
     * Public for BundleRouterInfos
     *
     * @param ip IPv4 or IPv6
     */
    public void add(String ip) {
        byte[] pib = Addresses.getIPOnly(ip);
        if (pib == null) {return;}
        add(pib);
    }

    /**
     * Add to the list needing lookup
     * Public for BundleRouterInfos
     *
     * @param ip IPv4 or IPv6
     */
    public void add(byte[] ip) {
        // skip he.net tunnel 2001:470:: so we will get correct geoip from IPv4
        // ditto route48
        if (ip.length == 16 &&
            ((ip[0] == 0x20 && ip[1] == 0x01 &&
              ip[2] == 0x04 && ip[3] == 0x70) ||
             (ip[0] == 0x2a && ip[1] == 0x06 &&
              ip[2] == (byte) 0xa0 && ip[3] == 0x04))) {
            return;
        }
        add(toLong(ip));
    }

    /** see above for ip-to-long mapping */
    private void add(long ip) {
        Long li = Long.valueOf(ip);
        if (!(_IPToCountry.containsKey(li) || _notFound.contains(li))) {
            if (ip >= 0 && ip < (1L << 32)) {_pendingSearch.add(li);}
            else {_pendingIPv6Search.add(li);}
        }
    }

    /**
     * Get the country for an IP from the cache.
     * Public for BundleRouterInfos
     *
     * @param ip IPv4 or IPv6
     * @return lower-case code, generally two letters, or null.
     */
    public String get(String ip) {
        byte[] pib = Addresses.getIPOnly(ip);
        if (pib == null) return null;
        return get(pib);
    }

    /**
     * Get the country for an IP from the cache.
     * @param ip IPv4 or IPv6
     * @return lower-case code, generally two letters, or null.
     */
    String get(byte[] ip) {
        if (ip == null) {return null;}
        // skip he.net tunnel 2001:470:: so we will get correct geoip from IPv4
        if (ip.length == 16 && ip[0] == 0x20 && ip[1] == 0x01 && ip[2] == 0x04 && ip[3] == 0x70) {return null;}
        return get(toLong(ip));
    }

    /** see above for ip-to-long mapping */
    private String get(long ip) {return _IPToCountry.get(Long.valueOf(ip));}

    /** see above for ip-to-long mapping */
    private static long toLong(byte[] ip) {
        long rv = 0;
        if (ip.length == 16) {
            for (int i = 0; i < 8; i++) {rv |= (ip[i] & 0xffL) << ((7-i)*8);}
            return rv;
        } else {
            for (int i = 0; i < 4; i++) {rv |= (ip[i] & 0xff) << ((3-i)*8);}
            return rv & 0xFFFFFFFFL;
        }
    }

    /**
     * @return e.g. 1.2.3.4
     * @since 0.9.38 for maxmind
     */
    private static String toV4(long ip) {
        StringBuilder buf = new StringBuilder(15);
        for (int i = 0; i < 4; i++) {
            buf.append(Long.toString((ip >> ((3-i)*8)) & 0xff));
            if (i == 3) {break;}
            buf.append('.');
        }
        return buf.toString();
    }

    /**
     * @return e.g. aabb:ccdd:eeff:1122::
     * @since 0.9.26 for maxmind
     */
    private static String toV6(long ip) {
        StringBuilder buf = new StringBuilder(21);
        for (int i = 0; i < 4; i++) {
            buf.append(Long.toHexString((ip >> ((3-i)*16)) & 0xffff));
            buf.append(':');
        }
        buf.append(':');
        return buf.toString();
    }

    /**
     * Get the country for a country code
     * Public for BundleRouterInfos
     *
     * @param code two-letter lower case code
     * @return untranslated name or null
     */
    public String fullName(String code) {
        if (code != null && !code.equals("a0")) {return _codeToName.get(code);}
        else {return null;}
    }

    /**
     * Get the country code map
     *
     * @return Map of two-letter lower case code to untranslated country name, unmodifiable
     * @since 0.9.53
     */
    public Map<String, String> getCountries() {
        return Collections.unmodifiableMap(_codeToName);
    }

    public static void main(String[] args) {
        if (args.length <= 0) {
            System.out.print("Usage: GeoIP {IP ADDRESS}...\n" +
                              "      GeoIP -c {2 letter country code} Dump all subnets for a country to " +
                              Blocklist.BLOCKLIST_COUNTRY_FILE); // NOSONAR S106 CLI output
            System.exit(1);
        }
        GeoIP g = new GeoIP(I2PAppContext.getGlobalContext());
        if (args[0].equals("-c") && args.length == 2) {
            g.countryToIP(args[1]);
            System.out.println("Subnets for country " + args[1] + " dumped to " +
                               Blocklist.BLOCKLIST_COUNTRY_FILE); // NOSONAR S106 CLI output
            return;
        }
        for (int i = 0; i < args.length; i++) {g.add(args[i]);}
        long start = System.currentTimeMillis();
        g.blockingLookup();
        System.out.println("Lookup took " + (System.currentTimeMillis() - start) + "ms"); // NOSONAR S106 CLI output
        for (int i = 0; i < args.length; i++) {System.out.println(args[i] + " : " + g.get(args[i]));} // NOSONAR S106 CLI output
    }
}
