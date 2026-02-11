package net.i2p.router.networkdb.reseed;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.*;
import net.i2p.I2PAppContext;
import net.i2p.crypto.SU3File;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterClock;
import net.i2p.router.RouterContext;
import net.i2p.router.util.EventLog;
import net.i2p.util.EepGet;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.PortMapper;
import net.i2p.util.RFC822Date;
import net.i2p.util.SSLEepGet;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SystemVersion;
import net.i2p.util.Translate;
import net.i2p.util.VersionComparator;

/**
 * Moved from ReseedHandler in routerconsole. See ReseedChecker for additional comments.
 *
 * Handler to deal with reseed requests.  This will reseed from the URLs
 * specified below unless the I2P configuration property "i2p.reseedURL" is
 * set.  It always writes to ./netDb/, so don't mess with that.
 *
 * This is somewhat complicated by trying to log to three places - the console,
 * the router log, and the wrapper log.
 */
public class Reseeder {
    private final RouterContext _context;
    private final Log _log;
    private final ReseedChecker _checker;

    // Reject unreasonably big files, because we download into a ByteArrayOutputStream.
    private static final long MAX_RESEED_RESPONSE_SIZE = 2 * 1024 * 1024;
    private static final long MAX_SU3_RESPONSE_SIZE = 1024 * 1024;
    /** limit to spend on a single host, to avoid getting stuck on one that is seriously overloaded */
    private static final int MAX_TIME_PER_HOST = 10 * 1000;
    private static final long MAX_FILE_AGE = 3*24*60*60*1000L;
    /** Don't disable this! */
    private static final boolean ENABLE_SU3 = true;
    /** if false, use su3 only, and disable fallback reading directory index and individual dat files */
    private static final boolean ENABLE_NON_SU3 = false;
    //private static final int MIN_RI_WANTED = 100;
    private static final int MIN_RI_WANTED = 800;
    //private static final int MIN_RESEED_SERVERS = 2;
    private static final int MIN_RESEED_SERVERS = 12;
    // network ID cross-check, proposal 147, as of 0.9.42
    private static final String NETID_PARAM = "?netid=";
    private static final String MIN_VERSION = "0.9.65";

    /**
     *  NOTE - URLs that are in both the standard and SSL groups must use the same hostname,
     *         so the reseed process will not download from both.
     *         Ports are supported as of 0.9.14.
     *
     *  NOTE - Each seedURL must be a directory, it must end with a '/',
     *         it can't end with 'index.html', for example. Both because of how individual file
     *         URLs are constructed, and because SSLEepGet doesn't follow redirects.
     */
    public static final String DEFAULT_SEED_URL = ""; // Disable due to misconfiguation (ticket #1466)

    /**
     *  The I2P reseed servers are managed by backup (backup@mail.i2p).
     *  Please contact him for support, change requests, or issues.
     *  See also the reseed forum http://zzz.i2p/forums/18
     *  and the reseed setup and testing guide
     *  https://geti2p.net/en/get-involved/guides/reseed
     *
     *  All supported reseed hosts need a corresponding reseed (SU3)
     *  signing certificate installed in the router.
     *
     *  All supported reseed hosts with selfsigned SSL certificates
     *  need the corresponding SSL certificate installed in the router.
     *
     *  While this implementation supports SNI, others may not, so
     *  SNI requirements are noted.
     *
     * @since 0.8.2
     */
    public static final String DEFAULT_SSL_SEED_URL =
        //
        // https url:port, ending with "/"                    certificates/reseed/                 certificates/ssl/                 notes
        // ----------------------------------                 ---------------------------------    ------------------------------    --------------------------
        "https://coconut.incognet.io/"              + ',' +   // rambler_at_mail.i2p.crt           CA
        "https://i2p.diyarciftci.xyz/"              + ',' +   // diyarciftci_at_protonmail.com.crt CA                                Java 8+
        "https://i2p.novg.net/"                     + ',' +   // igor_at_novg.net.crt              CA                                Java 8+
        "https://i2pseed.creativecowpat.net:8443/"  + ',' +   // creativecowpat_at_mail.i2p.crt    i2pseed.creativecowpat.net.crt    Java 7+
        "https://reseed2.i2p.net/"                  + ',' +   // echelon3_at_mail.i2p.crt          CA
        "https://reseed.diva.exchange/"             + ',' +   // reseed_at_diva.exchange.crt       CA
        "https://reseed-fr.i2pd.xyz/"               + ',' +   // r4sas-reseed_at_mail.i2p.crt      CA
        "https://reseed.i2pgit.org/"                + ',' +   // hankhill19580_at_gmail.com.crt    CA                                Java 8+
        "https://reseed.onion.im/"                  + ',' +   // lazygravy_at_mail.i2p             CA                                Java 8+
        "https://reseed-pl.i2pd.xyz/"               + ',' +   // r4sas-reseed_at_mail.i2p.crt      CA
        "https://reseed.stormycloud.org/"           + ',' +   // admin_at_stormycloud.org.crt      CA
        "https://reseed.sahil.world/"               + ',' +   // sahil_at_mail.i2p.crt             CA
        "https://www2.mk16.de/";                              // i2p-reseed_at_mk16.de.crt         CA

        //"https://cubicchaos.net:8443/"              + ',' +   // unixeno_at_cubicchaos.net.crt     cubicchaos.net.crt
        //"https://i2p.ghativega.in/"                 + ',' +   // arnavbhatt288_at_mail.i2p.crt     CA
        //"https://banana.incognet.io/"               + ',' +   // rambler_at_mail.i2p.crt           CA
        //"https://reseed.memcpy.io/"                 + ',' +   // hottuna_at_mail.i2p.crt           CA                                SNI required

    private static final String SU3_FILENAME = "i2pseeds.su3";

    public static final String PROP_PROXY_HOST = "router.reseedProxyHost";
    public static final String PROP_PROXY_PORT = "router.reseedProxyPort";
    /** @since 0.8.2 */
    public static final String PROP_PROXY_ENABLE = "router.reseedProxyEnable";
    /** @since 0.8.2 */
    public static final String PROP_SSL_DISABLE = "router.reseedSSLDisable";
    /** @since 0.8.2 */
    public static final String PROP_SSL_REQUIRED = "router.reseedSSLRequired";
    /** @since 0.8.3 */
    public static final String PROP_RESEED_URL = "i2p.reseedURL";
    /** all these @since 0.8.9 */
    public static final String PROP_PROXY_USERNAME = "router.reseedProxy.username";
    public static final String PROP_PROXY_PASSWORD = "router.reseedProxy.password";
    public static final String PROP_PROXY_AUTH_ENABLE = "router.reseedProxy.authEnable";
    public static final String PROP_SPROXY_HOST = "router.reseedSSLProxyHost";
    public static final String PROP_SPROXY_PORT = "router.reseedSSLProxyPort";
    public static final String PROP_SPROXY_ENABLE = "router.reseedSSLProxyEnable";
    public static final String PROP_SPROXY_USERNAME = "router.reseedSSLProxy.username";
    public static final String PROP_SPROXY_PASSWORD = "router.reseedSSLProxy.password";
    public static final String PROP_SPROXY_AUTH_ENABLE = "router.reseedSSLProxy.authEnable";
    /** @since 0.9.33 */
    public static final String PROP_SPROXY_TYPE = "router.reseedSSLProxyType";
    /** @since 0.9 */
    public static final String PROP_DISABLE = "router.reseedDisable";

    // from PersistentDataStore
    private static final String ROUTERINFO_PREFIX = "routerInfo-";
    private static final String ROUTERINFO_SUFFIX = ".dat";

    Reseeder(RouterContext ctx, ReseedChecker rc) {
        _context = ctx;
        _log = ctx.logManager().getLog(Reseeder.class);
        _checker = rc;
    }

    /**
     *  Start a reseed using the default reseed URLs.
     *  Supports su3 and directories.
     *  Threaded, nonblocking.
     */
    void requestReseed() {
        ReseedRunner reseedRunner = new ReseedRunner();
        // set to daemon so it doesn't hang a shutdown
        Thread reseed = new I2PAppThread(reseedRunner, "Reseed", true);
        reseed.start();
    }

    /**
     *  Start a reseed from a single zip or su3 URL only.
     *  Threaded, nonblocking.
     *
     *  @throws IllegalArgumentException if it doesn't end with zip or su3
     *  @since 0.9.19
     */
    void requestReseed(URI url) throws IllegalArgumentException {
        ReseedRunner reseedRunner = new ReseedRunner(url);
        // set to daemon so it doesn't hang a shutdown
        Thread reseed = new I2PAppThread(reseedRunner, "Reseed", true);
        reseed.start();
    }

    /**
     *  Start a reseed from a zip or su3 input stream.
     *  Blocking, inline. Should be fast.
     *  This will close the stream.
     *
     *  @return number of valid routerinfos imported
     *  @throws IOException on most errors
     *  @since 0.9.19
     */
    int requestReseed(InputStream in) throws IOException {
        _checker.setError("");
        _checker.setStatus(_t("Reseeding from file") + "&hellip;");
        byte[] su3Magic = DataHelper.getASCII(SU3File.MAGIC);
        byte[] zipMagic = new byte[] { 0x50, 0x4b, 0x03, 0x04 };
        int len = Math.max(su3Magic.length, zipMagic.length);
        byte[] magic = new byte[len];
        File tmp =  null;
        OutputStream out = null;
        try {
            DataHelper.read(in, magic);
            boolean isSU3;
            if (DataHelper.eq(magic, 0, su3Magic, 0, su3Magic.length)) {isSU3 = true;}
            else if (DataHelper.eq(magic, 0, zipMagic, 0, zipMagic.length)) {isSU3 = false;}
            else {throw new IOException("Not a zip or su3 file");}
            tmp =  new File(_context.getTempDir(), "manualreseeds-" + _context.random().nextInt() + (isSU3 ? ".su3" : ".zip"));
            out = new BufferedOutputStream(new SecureFileOutputStream(tmp));
            out.write(magic);
            DataHelper.copy(in, out);
            out.close();
            int[] stats;
            ReseedRunner reseedRunner = new ReseedRunner();
            // inline
            if (isSU3) {stats = reseedRunner.extractSU3(tmp);}
            else {stats = reseedRunner.extractZip(tmp);}
            int fetched = stats[0];
            int errors = stats[1];
            if (fetched <= 0) {throw new IOException("No seeds extracted");}
            if (errors <= 0) {_checker.setStatus(_t("Imported {0} router infos.", fetched));}
            else {_checker.setStatus(_t("Imported {0} router infos ({1} errors).", fetched, errors));}
            System.err.println("Reseed acquired " + fetched + " router infos from file with " + errors + " errors");
            if (fetched > 0) {_context.router().eventLog().addEvent(EventLog.RESEED, "imported " + fetched + " router infos from file");}
            return fetched;
        } finally {
            try {in.close();}
            catch (IOException ioe) {}
            if (out != null) {
                try {out.close();}
                catch (IOException ioe) {}
            }
            if (tmp != null) {tmp.delete();}
        }
    }

    /**
     *  Since Java 7 or Android 2.3 (API 9),
     *  which is the lowest Android we support anyway.
     *
     *  Not guaranteed to be correct, e.g. FreeBSD:
     *  https://bugs.freebsd.org/bugzilla/show_bug.cgi?id=201446
     *
     *  @since 0.9.20
     */
    private static boolean isSNISupported() {return SystemVersion.isJava7() || SystemVersion.isAndroid();}

    private class ReseedRunner implements Runnable, EepGet.StatusListener {
        private boolean _isRunning;
        private final String _proxyHost, _sproxyHost;
        private final int _proxyPort, _sproxyPort;
        private final boolean _shouldProxyHTTP, _shouldProxySSL;
        private final SSLEepGet.ProxyType _sproxyType;
        private SSLEepGet.SSLState _sslState;
        private int _gotDate;
        private long _attemptStarted;
        /** bytes per sec for each su3 downloaded */
        private final List<Long> _bandwidths;
        //private static final int MAX_DATE_SETS = 2;
        private static final int MAX_DATE_SETS = 4;
        private final URI _url;

        /**
         *  Start a reseed from the default URL list
         */
        public ReseedRunner() {this(null);}

        /**
         *  Start a reseed from this URL only, or null for trying one or more from the default list.
         *
         *  @param url if non-null, must be a zip or su3 URL, NOT a directory
         *  @throws IllegalArgumentException if it doesn't end with zip or su3
         *  @since 0.9.19
         */
        public ReseedRunner(URI url) throws IllegalArgumentException {
            validateUrl(url);
            _url = url;
            _bandwidths = new ArrayList<>(4);

            boolean proxyEnabled = _context.getBooleanProperty(PROP_PROXY_ENABLE);
            _proxyHost = proxyEnabled ? _context.getProperty(PROP_PROXY_HOST) : null;
            _proxyPort = proxyEnabled ? _context.getProperty(PROP_PROXY_PORT, -1) : -1;
            _shouldProxyHTTP = isValidHostPort(_proxyHost, _proxyPort);

            boolean shouldProxySSL = _context.getBooleanProperty(PROP_SPROXY_ENABLE);
            if (shouldProxySSL) {
                SSLEepGet.ProxyType sproxyType = getProxyType();
                if (sproxyType == SSLEepGet.ProxyType.INTERNAL) {
                    _sproxyHost = "localhost";
                    _sproxyPort = _context.portMapper().getPort(PortMapper.SVC_HTTP_PROXY, 4444);
                } else {
                    _sproxyHost = _context.getProperty(PROP_SPROXY_HOST);
                    _sproxyPort = _context.getProperty(PROP_SPROXY_PORT, -1);
                }
                _shouldProxySSL = isValidHostPort(_sproxyHost, _sproxyPort);
                _sproxyType = _shouldProxySSL ? sproxyType : SSLEepGet.ProxyType.NONE;
            } else {
                _sproxyHost = null;
                _sproxyPort = -1;
                _shouldProxySSL = false;
                _sproxyType = SSLEepGet.ProxyType.NONE;
            }
        }

        private void validateUrl(URI url) {
            if (url == null) return;
            String path = url.getPath();
            if (path == null || !(path.toLowerCase(Locale.US).endsWith(".zip") || path.toLowerCase(Locale.US).endsWith(".su3"))) {
                throw new IllegalArgumentException("Reseed URL must end with .zip or .su3");
            }
        }

        private boolean isValidHostPort(String host, int port) {
            return host != null && !host.isEmpty() && port > 0;
        }

        /*
         * Do it.
         */
        public void run() {
            try {run2();}
            finally {
                _checker.done();
                processBandwidths();
            }
        }

        private void run2() {
            _isRunning = true;
            _checker.setError("");
            _checker.setStatus(_t("Initiating Reseed") + "&hellip;");
            System.out.println("Starting Reseed process...");
            int total;
            if (_url != null) {
                String lc = _url.getPath().toLowerCase(Locale.US);
                if (lc.endsWith(".su3")) {
                    URI uri;
                    try {uri = new URI(_url.toString() + NETID_PARAM + _context.router().getNetworkID());}
                    catch (URISyntaxException use) {throw new IllegalArgumentException("Bad URL " + _url, use);}
                    total = reseedSU3(uri, false);
                } else if (lc.endsWith(".zip")) {total = reseedZip(_url, false);}
                else {throw new IllegalArgumentException("Must end with .zip or .su3");}
            } else {                total = reseed(false);
            }
            if (total >= 20) {
                String s = ngettext("Acquired {0} router info from reseed hosts",
                                    "Acquired {0} router infos from reseed hosts", total);
                System.out.println(s + getDisplayString(_url));
                _checker.setStatus(s);
                _checker.setError("");
            } else if (total > 0) {
                String s = ngettext("Acquired only 1 router info from reseed hosts",
                                    "Acquired only {0} router infos from reseed hosts", total);
                System.out.println(s + getDisplayString(_url));
                _checker.setError(s);
                _checker.setStatus("");
            } else {
                if (total == 0 && !_context.router().gracefulShutdownInProgress()) {
                    System.out.println("Reseed failed " + getDisplayString(_url) + " -> Check network connection!");
                    System.out.println("Ensure that nothing blocks outbound HTTP or HTTPS, check the logs, " +
                                       "and if nothing helps, read the FAQ about reseeding manually.");
                    if (_url == null || "https".equals(_url.getScheme())) {
                        if (_sproxyHost != null && _sproxyPort > 0)
                            System.out.println("Check current proxy setting! Type: " + getDisplayString(_sproxyType) +
                                               " Host: " + _sproxyHost + " Port: " + _sproxyPort);
                        else
                            System.out.println("Consider enabling a proxy for https on the reseed configuration page");
                    } else {
                        if (_proxyHost != null && _proxyPort > 0)
                            System.out.println("Check HTTP proxy setting - host: " + _proxyHost + " port: " + _proxyPort);
                        else
                            System.out.println("Consider enabling an HTTP proxy on the reseed configuration page");
                    }
                } // else < 0, no valid URLs
                String old = _checker.getError();
                String notify = old.replaceAll("\\(.*\\)", "").replace(" \\.", ""); // remove dupe error msgs
                _checker.setError(_t("{0}Reseed{1} failed:", "<a href=\"/configreseed\">", "</a>") + ' '  + notify + " <br>" +
                                  _t("For assistance, see the {0}",
                                    "<a target=_top href=\"/help/reseed\">" + _t("reseed help") + "</a>"));
                _checker.setStatus("");
            }
            _isRunning = false;
            // ReseedChecker will set timer to clean up
            //_checker.setStatus("");
            if (total > 0) {
                _context.router().eventLog().addEvent(EventLog.RESEED, Integer.toString(total) + " router infos acquired");
            }
        }

        /**
         *  @since 0.9.18
         */
        private void processBandwidths() {
            if (_bandwidths.isEmpty()) {return;}
            long tot = 0;
            for (Long sample : _bandwidths) {tot += sample.longValue();}
            long avg = tot / _bandwidths.size();
            if (_log.shouldInfo()) {
                _log.info("Bandwidth average: " + avg + " KB/s from " + _bandwidths.size() + " samples");
            }
            // TODO _context.bandwidthLimiter().....
        }

        // EepGet status listeners
        public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause) {
            /*
             * Since readURL() runs an EepGet with 0 retries, we can report errors with
             * attemptFailed() instead of transferFailed() which has the benefit of providing
             * cause of failure, which helps resolve issues.
             */
            String truncatedURL = url.replace("https://", "");
            int slashIndex = truncatedURL.indexOf('/');
            String hostname = slashIndex > 0 ? truncatedURL.substring(0, slashIndex) : truncatedURL;
            String reason = cause != null && cause.getMessage() != null ?
                            cause.getMessage().replaceAll("verification failed for .*", "verification failed") : "Unknown error";
            String msg = "Reseeding failed [" + hostname + "] -> " + reason;
            if (_log.shouldWarn()) {_log.warn(msg);}
            else {_log.logAlways(Log.WARN, msg);}
            if (cause != null && cause.getMessage() != null) {
                _checker.setError(DataHelper.escapeHTML(cause.getMessage()));
            }
        }

        public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {}
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {}
        public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {}

        /**
         *  Use the Date header as a backup time source
         */
        public void headerReceived(String url, int attemptNum, String key, String val) {
            /*
             * We do this more than once, because the first SSL handshake may take a while,
             * and it may take the server a while to render the index page.
             */
            if (_gotDate < MAX_DATE_SETS && "date".equals(key.toLowerCase(Locale.US)) && _attemptStarted > 0) {
                long timeRcvd = System.currentTimeMillis();
                long serverTime = RFC822Date.parse822Date(val);
                if (serverTime > 0) {
                    // Add 500ms since it's 1-sec resolution, and add half the RTT
                    long now = serverTime + 500 + ((timeRcvd - _attemptStarted) / 2);
                    long offset = now - _context.clock().now();
                    if (_context.clock().getUpdatedSuccessfully()) {
                        // 2nd time better than the first
                        if (_gotDate > 0)
                            _context.clock().setNow(now, RouterClock.DEFAULT_STRATUM - 2);
                        else
                            _context.clock().setNow(now, RouterClock.DEFAULT_STRATUM - 1);
                        if (_log.shouldWarn())
                            _log.warn("Reseed adjusting clock by " +
                                      DataHelper.formatDuration(Math.abs(offset)));
                    } else {
                        // No peers or NTP yet, this is probably better than the peer average will be for a while
                        // default stratum - 1, so the peer average is a worse stratum
                        _context.clock().setNow(now, RouterClock.DEFAULT_STRATUM - 1);
                        _log.logAlways(Log.WARN, "NTP failure, Reseed adjusting clock by " +
                                                 DataHelper.formatDuration(Math.abs(offset)));
                    }
                    _gotDate++;
                }
            }
        }

        /** save the start time */
        public void attempting(String url) {
            if (_gotDate < MAX_DATE_SETS)
                _attemptStarted = System.currentTimeMillis();
        }

        // End of EepGet status listeners

        /**
         * Performs reseed operation using configured URLs or defaults.
         *
         * &lt;p&gt;Builds a list of URIs to use for reseeding based on current
         * configuration and SSL settings, then delegates to the reseed method
         * that accepts a URI list.
         *
         * @param echoStatus Whether to echo status messages (typically false)
         * @return count of routerinfos successfully fetched, or -1 if no valid URLs
         */
        private int reseed(boolean echoStatus) {
            List<URI> urlList = buildUrlList();
            if (urlList.isEmpty()) {
                System.out.println("No valid reseed URLs");
                _checker.setError("No valid reseed URLs");
                return -1;
            }
            return reseed(urlList, echoStatus);
        }

        /**
         * Builds the list of URIs to use for reseed based on configured properties and defaults.
         *
         * &lt;p&gt;Handles default URLs, SSL enablement/disablement, shuffling,
         * and filtering based on SNI support.
         *
         * @return list of URIs to attempt reseed from
         */
        private List<URI> buildUrlList() {
            String urls = _context.getProperty(PROP_RESEED_URL);
            boolean defaulted = urls == null;
            boolean sslDisable = _context.getBooleanProperty(PROP_SSL_DISABLE);
            boolean sslRequired = _context.getBooleanPropertyDefaultTrue(PROP_SSL_REQUIRED);

            List<URI> urlList;

            if (defaulted) {
                urls = sslDisable ? DEFAULT_SEED_URL : DEFAULT_SSL_SEED_URL;
                urlList = parseUrlsWithTrailingSlash(urls);
                Collections.shuffle(urlList, _context.random());

                if (!sslDisable && !sslRequired) {
                    List<URI> fallbackList = parseUrlsWithTrailingSlash(DEFAULT_SEED_URL);
                    Collections.shuffle(fallbackList, _context.random());
                    urlList.addAll(fallbackList);
                }
            } else {
                List<URI> sslList = new ArrayList<>();
                List<URI> nonSslList = new ArrayList<>();

                for (String u : urls.split("[ ,]+")) {
                    u = u.trim();
                    if (!u.endsWith("/")) u += "/";
                    URI uri = safeUri(u);
                    if (uri == null) continue;
                    if (u.startsWith("https")) sslList.add(uri);
                    else nonSslList.add(uri);
                }

                urlList = new ArrayList<>();
                if (!sslDisable) {
                    Collections.shuffle(sslList, _context.random());
                    urlList.addAll(sslList);
                }
                if (sslDisable || !sslRequired) {
                    Collections.shuffle(nonSslList, _context.random());
                    urlList.addAll(nonSslList);
                }
            }

            filterUrlsIfNoSNI(urlList);
            return urlList;
        }

        /**
         * Parses a comma/space separated list of URLs into a list of URIs,
         * ensuring each ends with a trailing slash.
         *
         * @param urls String containing the URLs to parse
         * @return list of URIs, excluding invalid ones
         */
        private List<URI> parseUrlsWithTrailingSlash(String urls) {
            List<URI> list = new ArrayList<>();
            for (String u : urls.split("[ ,]+")) {
                u = u.trim();
                if (!u.endsWith("/")) u += "/";
                URI uri = safeUri(u);
                if (uri != null) list.add(uri);
            }
            return list;
        }

        /**
         * Safely converts a string to a URI, returning null if invalid.
         *
         * @param u URL string to convert
         * @return corresponding URI, or null if syntax invalid
         */
        private URI safeUri(String u) {
            try {
                return new URI(u);
            } catch (URISyntaxException e) {
                return null;
            }
        }

        /**
         * Removes specific URLs from the list if Server Name Indication (SNI) is not supported.
         *
         * @param urlList list of URIs to filter
         */
        private void filterUrlsIfNoSNI(List<URI> urlList) {
            if (!isSNISupported()) {
                List<String> toRemove = Arrays.asList(
                    "https://netdb.i2p2.no/",
                    "https://reseed.i2p2.no/",
                    "https://reseed2.i2p2.no/",
                    "https://reseed.memcpy.io"
                );
                urlList.removeIf(uri -> uri != null && toRemove.contains(uri.toString()));
            }
        }

        /**
         * Attempts to fetch router information files from the specified list of URIs.
         *
         * &lt;p&gt;Tracks the number of routerinfos successfully fetched and stops
         * when minimum targets are met or when shutdown in progress.
         *
         * @param urlList the list of URIs to fetch reseed data from
         * @param echoStatus Whether to echo status messages (typically false)
         * @return count of routerinfos successfully fetched
         */
        private int reseed(List<URI> urlList, boolean echoStatus) {
            String query = NETID_PARAM + _context.router().getNetworkID();
            int total = 0;
            int fetchedReseedServers = 0;

            for (int i = 0; i < urlList.size() && _isRunning; i++) {
                if (_context.router().gracefulShutdownInProgress()) {
                    System.out.println("Reseed aborted, shutdown in progress...");
                    return total;
                }
                URI url = urlList.get(i);
                int dl = 0;
                if (ENABLE_SU3) {
                    try {
                        dl = reseedSU3(new URI(url.toString() + SU3_FILENAME + query), echoStatus);
                    } catch (URISyntaxException ignored) {}
                }
                if (ENABLE_NON_SU3 && dl <= 0) {
                    dl = reseedOne(url, echoStatus);
                }
                if (dl > 0) {
                    total += dl;
                    fetchedReseedServers++;
                    if (total >= MIN_RI_WANTED && fetchedReseedServers >= MIN_RESEED_SERVERS) break;

                    for (int j = i + 1; j < urlList.size();) {
                        if (url.getHost().equals(urlList.get(j).getHost())) {
                            urlList.remove(j);
                        } else {
                            j++;
                        }
                    }
                }
            }
            return total;
        }

        /**
         * Attempts to reseed by fetching router info files from a single reseed URL.
         *
         * Downloads, extracts router info URLs from content, filters own router info,
         * and attempts to fetch up to 250 router infos with error handling,
         * respecting a time limit and error thresholds.
         *
         * @param seedURL the reseed URI to fetch data from
         * @param echoStatus flag indicating if progress should be printed
         * @return number of router infos successfully fetched
         */
        private int reseedOne(URI seedURL, boolean echoStatus) {
            String display = getDisplayString(seedURL);
            String trimmed = cleanDisplayString(display);

            try {
                final long timeLimit = System.currentTimeMillis() + MAX_TIME_PER_HOST;
                _checker.setStatus(_t("Contacting reseed host") + ":<br>" + trimmed);
                System.err.println("Reseeding from " + display);

                byte[] contentRaw = readURL(seedURL);
                if (contentRaw == null) {
                    System.err.println("No RouterInfos received from: " + trimmed);
                    return 0;
                }

                String content = DataHelper.getUTF8(contentRaw);
                Set<String> urls = new HashSet<>(1024);
                Hash ourHash = _context.routerHash();
                String ourB64 = (ourHash != null) ? ourHash.toBase64() : null;

                int cur = 0;
                int total = 0;
                while (total++ < 1000) {
                    int start = indexOfIgnoreCase(content, "href=\"" + ROUTERINFO_PREFIX, cur);
                    if (start < 0) break;

                    int end = content.indexOf(ROUTERINFO_SUFFIX + "\">", start);
                    if (end < 0) break;

                    if (start - end > 200) {
                        cur = end + 1;
                        continue;
                    }

                    String name = content.substring(start + ("href=\"" + ROUTERINFO_PREFIX).length(), end);

                    if (ourB64 == null || !name.contains(ourB64)) {
                        urls.add(name);
                    } else if (_log.shouldInfo()) {
                        _log.info("Skipping our own RI");
                    }
                    cur = end + 1;
                }

                if (urls.isEmpty()) {
                    if (_log.shouldWarn()) {
                        _log.warn("Read " + contentRaw.length + " bytes from reseed server " + trimmed + " but found no RouterInfo URLs");
                    }
                    System.err.println("No RouterInfos received from: " + trimmed);
                    return 0;
                }

                List<String> urlList = new ArrayList<>(urls);
                Collections.shuffle(urlList, _context.random());

                int fetched = 0;
                int errors = 0;
                for (Iterator<String> iter = urlList.iterator();
                     iter.hasNext() && fetched < 250 && System.currentTimeMillis() < timeLimit; ) {
                    String routerInfoName = iter.next();
                    try {
                        _checker.setStatus(_t("Reseeding: fetching router info from seed URL ({0} successful, {1} errors).", fetched, errors));
                        if (!fetchSeed(seedURL.toString(), routerInfoName)) {
                            continue;
                        }
                        fetched++;
                        if (echoStatus) {
                            System.out.print(".");
                            if (fetched % 60 == 0) System.out.println();
                        }
                    } catch (RuntimeException e) {
                        if (_log.shouldInfo()) _log.info("Failed fetch", e);
                        errors++;
                    }
                    if (errors >= 50 || (errors >= 10 && fetched <= 1)) break;
                }

                System.err.println("Reseed acquired " + fetched + " router infos with " + errors + " errors [" + trimmed + "]");
                if (fetched > 0) _context.netDb().rescan();
                return fetched;

            } catch (Throwable t) {
                if (_log.shouldWarn()) _log.warn("Error reseeding -> " + t.getMessage());
                System.err.println("No router infos " + display);
                return 0;
            }
        }

        /** Cleans the display string for logging by removing unwanted parts. */
        private String cleanDisplayString(String s) {
            return s.replace("http://", "")
                    .replace("https://", "")
                    .replace("netDb/", "")
                    .replace("/i2pseeds.su3", "")
                    .replace("from ", "")
                    .replace("?", "")
                    .replace("netid=2", "")
                    .replaceAll("\\(.*\\)", "");
        }

        /** Case insensitive indexOf for content parsing, returns -1 if not found */
        private int indexOfIgnoreCase(String str, String search, int fromIndex) {
            final String lowerStr = str.toLowerCase(Locale.US);
            final String lowerSearch = search.toLowerCase(Locale.US);
            return lowerStr.indexOf(lowerSearch, fromIndex);
        }

        /**
         *  Fetch an su3 file containing routerInfo files
         *
         *  We update the status here.
         *
         *  @param seedURL the URL of the SU3 file
         *  @param echoStatus apparently always false
         *  @return count of routerinfos successfully fetched
         *  @since 0.9.14
         **/
        public int reseedSU3(URI seedURL, boolean echoStatus) {
            return reseedSU3OrZip(seedURL, true, echoStatus);
        }

        /**
         *  Fetch a zip file containing routerInfo files
         *
         *  We update the status here.
         *
         *  @param seedURL the URL of the zip file
         *  @param echoStatus apparently always false
         *  @return count of routerinfos successfully fetched
         *  @since 0.9.19
         **/
        public int reseedZip(URI seedURL, boolean echoStatus) {
            return reseedSU3OrZip(seedURL, false, echoStatus);
        }

        /**
         *  Fetch an su3 or zip file containing routerInfo files
         *
         *  We update the status here.
         *
         *  @param seedURL the URL of the SU3 or zip file
         *  @param echoStatus apparently always false
         *  @return count of routerinfos successfully fetched
         *  @since 0.9.19
         **/
        private int reseedSU3OrZip(URI seedURL, boolean isSU3, boolean echoStatus) {
            int fetched = 0;
            int errors = 0;
            File contentRaw = null;
            String s = getDisplayString(seedURL);
            String trimmed = s.replace("http://","").replace("https://","").replace("netDb/","").replace("/i2pseeds.su3","")
                              .replace("from ","").replace("netid=2", "").replaceAll("\\(.*\\)", "").replaceAll("\\?", "");
            try {
                _checker.setStatus(_t("Contacting reseed host") + ":<br>" + trimmed);
                System.err.println("Reseeding " + s);
                // don't use context time, as we may be step-changing it
                // from the server header
                long startTime = System.currentTimeMillis();
                contentRaw = fetchURL(seedURL);
                long totalTime = System.currentTimeMillis() - startTime;
                if (contentRaw == null) {return 0;}
                if (totalTime > 0) {
                    long sz = contentRaw.length();
                    long bw = 1000 * sz / totalTime;
                    _bandwidths.add(Long.valueOf(bw));
                    if (_log.shouldDebug())
                        _log.debug("Received " + sz + " bytes in " + totalTime + " ms " + getDisplayString(seedURL));
                }
                int[] stats;
                if (isSU3) {stats = extractSU3(contentRaw);}
                else {stats = extractZip(contentRaw);}
                fetched = stats[0];
                errors = stats[1];
            } catch (Throwable t) {
                System.err.println("Error reseeding " + trimmed + " -> " + t.getMessage());
                _log.error("Error reseeding " + trimmed + " -> " + t.getMessage());
                errors++;
            } finally {
                if (contentRaw != null) {contentRaw.delete();}
            }
            if (errors <= 0) {
                _checker.setStatus(_t("Acquired {0} router infos from reseed hosts", fetched));
                System.out.println("Acquired " + fetched + " router infos from " + trimmed);

            } else {
                _checker.setStatus(_t("Acquired {0} router infos from reseed hosts ({1} errors)", fetched, errors));
                System.err.println("Acquired " + fetched + " router infos from " + trimmed + "-> " + errors + (errors > 1 ? " errors" : " error"));
            }
            return fetched;
        }


        /**
         *  @return 2 ints: number successful and number of errors
         *  @since 0.9.19 pulled from reseedSU3
         */
        public int[] extractSU3(File contentRaw) throws IOException {
            int fetched = 0;
            int errors = 0;
            File zip = null;
            try {
                SU3File su3 = new SU3File(_context, contentRaw);
                zip = new File(_context.getTempDir(), "reseed-" + _context.random().nextInt() + ".zip");
                su3.verifyAndMigrate(zip);
                int type = su3.getContentType();
                if (type != SU3File.CONTENT_RESEED) {throw new IOException("Bad content type (" + type + ")");}
                String version = su3.getVersionString();
                try {
                    Long ver = Long.parseLong(version.trim());
                    if (ver >= 1400000000L) {
                        // Preliminary code was using "3"
                        // New format is date +%s
                        ver *= 1000;
                        if (ver < _context.clock().now() - MAX_FILE_AGE) {
                            throw new IOException("su3 file is too old");
                        }
                    }
                } catch (NumberFormatException nfe) {}

                int[] stats = extractZip(zip);
                fetched = stats[0];
                errors = stats[1];
            } catch (Throwable t) {
                String msg = "Error with downloaded reseed bundle -> " + t.getMessage();
                System.err.println(msg);
                _log.error(msg);
                errors++;
            } finally {
                contentRaw.delete();
                if (zip != null) {zip.delete();}
            }

            int[] rv = new int[2];
            rv[0] = fetched;
            rv[1] = errors;
            return rv;
        }

        /**
         *  @return 2 ints: number successful and number of errors
         *  @since 0.9.19 pulled from reseedSU3
         */
        public int[] extractZip(File zip) throws IOException {
            int fetched = 0;
            int errors = 0;
            File tmpDir = null;
            try {
                tmpDir = new File(_context.getTempDir(), "reseeds-" + _context.random().nextInt());
                if (!FileUtil.extractZip(zip, tmpDir)) {throw new IOException("Bad zip file");}

                Hash ourHash = _context.routerHash();
                String ourB64 = ourHash != null ? ROUTERINFO_PREFIX + ourHash.toBase64() + ROUTERINFO_SUFFIX : "";

                File[] files = tmpDir.listFiles();
                if (files == null || files.length == 0) {throw new IOException("No files in zip");}
                List<File> fList = Arrays.asList(files);
                Collections.shuffle(fList, _context.random());
                long minTime = _context.clock().now() - MAX_FILE_AGE;
                File netDbDir = new SecureDirectory(_context.getRouterDir(), "netDb");
                if (!netDbDir.exists()) {netDbDir.mkdirs();}

                // 1000 max from one reseed file
                for (Iterator<File> iter = fList.iterator(); iter.hasNext() && fetched < 1000; ) {
                    File f = iter.next();
                    String name = f.getName();
                    if (name.length() != ROUTERINFO_PREFIX.length() + 44 + ROUTERINFO_SUFFIX.length() ||
                        name.equals(ourB64) ||
                        f.length() > RouterInfo.MAX_UNCOMPRESSED_SIZE ||
                        f.lastModified() < minTime ||
                        !name.startsWith(ROUTERINFO_PREFIX) ||
                        !name.endsWith(ROUTERINFO_SUFFIX) ||
                        !f.isFile()) {
                        if (_log.shouldWarn()) {_log.warn("Skipping " + f);}
                        f.delete();
                        errors++;
                        continue;
                    }
                    File to = new File(netDbDir, name);
                    if (FileUtil.rename(f, to)) {fetched++;}
                    else {
                        f.delete();
                        errors++;
                    }
                    // Give up on this host after lots of errors
                    if (errors >= 5) {break;}
                }
            } finally {
                if (tmpDir != null) {FileUtil.rmdir(tmpDir, false);}
            }

            if (fetched > 0) {_context.netDb().rescan();}
            int[] rv = new int[2];
            rv[0] = fetched;
            rv[1] = errors;
            return rv;
        }

        /**
         *  Always throws an exception if something fails.
         *  We do NOT validate the received data here - that is done in PersistentDataStore
         *
         *  @param peer The Base64 hash, may include % encoding. It is decoded and validated here.
         *  @return true on success, false if skipped
         */
        private boolean fetchSeed(String seedURL, String peer) throws IOException, URISyntaxException {
            /* Use URI to do % decoding of the B64 hash (some servers escape ~ and =)
             * Also do basic hash validation. This prevents stuff like * .. or / in the file name
             */
            URI uri = new URI(peer);
            String b64 = uri.getPath();
            if (b64 == null) {throw new IOException("Bad hash " + peer);}
            byte[] hash = Base64.decode(b64);
            if (hash == null || hash.length != Hash.HASH_LENGTH) {throw new IOException("Bad hash " + peer);}
            Hash ourHash = _context.routerHash();
            if (ourHash != null && DataHelper.eq(hash, ourHash.getData())) {return false;}

            URI url = new URI(seedURL + (seedURL.endsWith("/") ? "" : "/") + ROUTERINFO_PREFIX + peer + ROUTERINFO_SUFFIX);

            byte data[] = readURL(url);
            if (data == null || data.length <= 0) {throw new IOException("Failed fetch of " + url);}
            return writeSeed(b64, data);
        }

        /** @return null on error */
        private byte[] readURL(URI url) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4*1024);
            EepGet get;
            boolean ssl = "https".equals(url.getScheme());
            if (ssl) {
                SSLEepGet sslget;
                if (_sslState == null) {
                    if (_shouldProxySSL) {
                        sslget = new SSLEepGet(_context, _sproxyType, _sproxyHost, _sproxyPort,
                                               baos, url.toString());
                    } else {sslget = new SSLEepGet(_context, baos, url.toString());}
                    _sslState = sslget.getSSLState(); // Save state for next time
                } else {
                    if (_shouldProxySSL) {
                        sslget = new SSLEepGet(_context, _sproxyType, _sproxyHost, _sproxyPort,
                                               baos, url.toString(), _sslState);
                    } else {
                        sslget = new SSLEepGet(_context, baos, url.toString(), _sslState);
                    }
                }
                get = sslget;
                if (_shouldProxySSL && _context.getBooleanProperty(PROP_SPROXY_AUTH_ENABLE)) {
                    String user = _context.getProperty(PROP_SPROXY_USERNAME);
                    String pass = _context.getProperty(PROP_SPROXY_PASSWORD);
                    if (user != null && user.length() > 0 &&
                        pass != null && pass.length() > 0)
                        get.addAuthorization(user, pass);
                }
            } else {
                // Do a (probably) non-proxied eepget into our ByteArrayOutputStream with 0 retries
                get = new EepGet(_context, _shouldProxyHTTP, _proxyHost, _proxyPort, 3, 0, MAX_RESEED_RESPONSE_SIZE,
                                 null, baos, url.toString(), false, null, null);
                if (_shouldProxyHTTP && _context.getBooleanProperty(PROP_PROXY_AUTH_ENABLE)) {
                    String user = _context.getProperty(PROP_PROXY_USERNAME);
                    String pass = _context.getProperty(PROP_PROXY_PASSWORD);
                    if (user != null && user.length() > 0 &&
                        pass != null && pass.length() > 0)
                        get.addAuthorization(user, pass);
                }
            }
            if (!url.toString().endsWith("/")) {
                String minLastMod = RFC822Date.to822Date(_context.clock().now() - MAX_FILE_AGE);
                get.addHeader("If-Modified-Since", minLastMod);
            }
            get.addStatusListener(ReseedRunner.this);
            if (get.fetch() && get.getStatusCode() == 200) {return baos.toByteArray();}
            return null;
        }

        /**
         *  Fetch a URL to a file.
         *
         *  @return null on error
         *  @since 0.9.14
         */
        private File fetchURL(URI url) throws IOException {
            File out = new File(_context.getTempDir(), "reseed-" + _context.random().nextInt() + ".tmp");
            EepGet get;
            boolean ssl = "https".equals(url.getScheme());
            if (ssl) {
                SSLEepGet sslget;
                if (_sslState == null) {
                    if (_shouldProxySSL) {
                        sslget = new SSLEepGet(_context, _sproxyType, _sproxyHost, _sproxyPort,
                                               out.getPath(), url.toString());
                    } else {sslget = new SSLEepGet(_context, out.getPath(), url.toString());}
                    _sslState = sslget.getSSLState(); // Save state for next time
                } else if (_shouldProxySSL) {
                        sslget = new SSLEepGet(_context, _sproxyType, _sproxyHost, _sproxyPort,
                                               out.getPath(), url.toString(), _sslState);
                } else {sslget = new SSLEepGet(_context, out.getPath(), url.toString(), _sslState);}

                get = sslget;
                if (_shouldProxySSL && _context.getBooleanProperty(PROP_SPROXY_AUTH_ENABLE)) {
                    String user = _context.getProperty(PROP_SPROXY_USERNAME);
                    String pass = _context.getProperty(PROP_SPROXY_PASSWORD);
                    if (user != null && user.length() > 0 &&
                        pass != null && pass.length() > 0)
                        get.addAuthorization(user, pass);
                }
            } else {
                // Do a (probably) non-proxied eepget into file with 3 retries
                get = new EepGet(_context, _shouldProxyHTTP, _proxyHost, _proxyPort, 3, 0, MAX_SU3_RESPONSE_SIZE,
                                 out.getPath(), null, url.toString(), false, null, null);
                if (_shouldProxyHTTP && _context.getBooleanProperty(PROP_PROXY_AUTH_ENABLE)) {
                    String user = _context.getProperty(PROP_PROXY_USERNAME);
                    String pass = _context.getProperty(PROP_PROXY_PASSWORD);
                    if (user != null && user.length() > 0 &&
                        pass != null && pass.length() > 0) {
                        get.addAuthorization(user, pass);
                    }
                }
            }
            if (!url.toString().endsWith("/")) {
                String minLastMod = RFC822Date.to822Date(_context.clock().now() - MAX_FILE_AGE);
                get.addHeader("If-Modified-Since", minLastMod);
            }
            get.addStatusListener(ReseedRunner.this);
            if (get.fetch() && get.getStatusCode() == 200) {return out;}
            out.delete();
            return null;
        }

        /**
         *  @param name valid Base64 hash
         *  @return true on success, false if skipped
         */
        private boolean writeSeed(String name, byte data[]) throws IOException {
            String dirName = "netDb"; // _context.getProperty("router.networkDatabase.dbDir", "netDb");
            File netDbDir = new SecureDirectory(_context.getRouterDir(), dirName);
            if (!netDbDir.exists()) {netDbDir.mkdirs();}
            File file = new File(netDbDir, ROUTERINFO_PREFIX + name + ROUTERINFO_SUFFIX);
            // Don't overwrite recent file
            // TODO: even better would be to compare to last-mod date from eepget
            if (file.exists() && file.lastModified() > _context.clock().now() - 60*60*1000) {
                if (_log.shouldDebug()) {
                    _log.debug("Skipping RouterInfo; local copy is more recent: " + file);
                }
                return false;
            }
            FileOutputStream fos = null;
            try {
                fos = new SecureFileOutputStream(file);
                fos.write(data);
                if (_log.shouldInfo()) {
                    _log.info("Saved RouterInfo (" + data.length + " bytes) to " + file);
                }
            } finally {
                try {
                    if (fos != null) {fos.close();}
                } catch (IOException ioe) {}
            }
            return true;
        }

        /**
         *  @throws IllegalArgumentException if unknown, default is HTTP
         *  @return non-null
         *  @since 0.9.33
         */
        private SSLEepGet.ProxyType getProxyType() throws IllegalArgumentException {
            String sptype = _context.getProperty(PROP_SPROXY_TYPE, "HTTP").toUpperCase(Locale.US);
            return SSLEepGet.ProxyType.valueOf(sptype);
        }

        /**
         *  Display string for what we're fetching.
         *  Untranslated, for logs only.
         *
         *  @param url if null, returns ""
         *  @return non-null
         *  @since 0.9.33
         */
        private String getDisplayString(URI url) {
            if (url == null) {return "";}
            return getDisplayString(url.toString());
        }

        /**
         *  Display string for what we're fetching.
         *  Untranslated, for logs only.
         *
         *  @param url if null, returns ""
         *  @return non-null
         *  @since 0.9.33
         */
        private String getDisplayString(String url) {
            if (url == null) {return "";}
            StringBuilder buf = new StringBuilder(64);
            buf.append("from ").append(url);
            boolean ssl = url.startsWith("https://");
            if (ssl && _shouldProxySSL) {
                buf.append(" (").append(getDisplayString(_sproxyType)).append(" proxy ");
                if (_sproxyHost.contains(":")) {buf.append('[').append(_sproxyHost).append(']');}
                else {buf.append(_sproxyHost);}
                buf.append(':').append(_sproxyPort).append(')');
            } else if (!ssl && _shouldProxyHTTP) {
                buf.append(" (HTTP proxy ");
                if (_proxyHost.contains(":")) {buf.append('[').append(_proxyHost).append(']');}
                else {buf.append(_proxyHost);}
                buf.append(':').append(_proxyPort).append(')');
            }
            return buf.toString();
        }

        /**
         *  Display string for what we're fetching.
         *  Untranslated, for logs only.
         *
         *  @since 0.9.33
         */
        private String getDisplayString(SSLEepGet.ProxyType type) {
            switch(type) {
                case HTTP:
                    return "HTTPS";
                case SOCKS4:
                    return "SOCKS4/4a";
                case SOCKS5:
                    return "SOCKS5";
                case INTERNAL:
                    // Change reported string for outproxy so we don't see "I2P Outproxy proxy" in the logs
                    return "I2P HTTP";
                default:
                    return type.toString();
            }
        }
    }

    private static final String BUNDLE_NAME = "net.i2p.router.web.messages";

    /** translate */
    private String _t(String key) {
        return Translate.getString(key, _context, BUNDLE_NAME);
    }

    /** translate */
    private String _t(String s, Object o) {
        return Translate.getString(s, o, _context, BUNDLE_NAME);
    }

    /** translate */
    private String _t(String s, Object o, Object o2) {
        return Translate.getString(s, o, o2, _context, BUNDLE_NAME);
    }

    /** translate */
    private String ngettext(String s, String p, int n) {
        return Translate.getString(n, s, p, _context, BUNDLE_NAME);
    }

    /**
     *  @since 0.9.58
     */
    public static void main(String args[]) throws Exception {
        if (args.length == 1 && args[0].equals("help")) {
            System.out.println("Usage: reseeder [-6] [https://hostname/ ...]");
            System.exit(0);
        }
        boolean ipV6 = false;
        if (args.length > 0 && args[0].equals("-6")) {
            ipV6 = true;
            args = Arrays.copyOfRange(args, 1, args.length);
        }
        File f = new File("certificates");
        if (!f.exists()) {
            System.out.println("Must be run from $I2P or have symlink to $I2P/certificates in this directory");
            System.exit(0);
        }
        String[] urls = (args.length > 0) ? args : DataHelper.split(DEFAULT_SSL_SEED_URL, ",");
        if (args.length == 0) {Arrays.sort(urls);}
        int pass = 0, warn = 0, fail = 0;
        SSLEepGet.SSLState sslState = null;
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        System.out.println("Initiating reseed hosts test...\n");
        for (String url : urls) {
            url += SU3_FILENAME + NETID_PARAM + '2';
            URI uri = new URI(url);
            String host = uri.getHost();
            System.out.println("Host:     " + host);
            File su3 = new File(host + ".su3");
            su3.delete();
            try {
                SSLEepGet get;
                if (sslState == null) {
                    get = new SSLEepGet(ctx, su3.getPath(), url);
                    sslState = get.getSSLState();
                } else {get = new SSLEepGet(ctx, su3.getPath(), url, sslState);}

                if (ipV6) {get.forceDNSOverHTTPS(true);}
                long start = System.currentTimeMillis();
                if (get.fetch()) {
                    int rc = get.getStatusCode();
                    if (rc == 200) {
                        SU3File su3f = new SU3File(su3);
                        File zip = new File(host + ".zip");
                        zip.delete();
                        su3f.verifyAndMigrate(zip);
                        SU3File.main(new String[] {"showversion", su3.getPath()});
                        String version = su3f.getVersionString();
                        long ver = Long.parseLong(version.trim()) * 1000;
                        long cutoff = System.currentTimeMillis() - MAX_FILE_AGE / 4;
                        if (ver < cutoff) {throw new IOException("su3 file is too old");}
                        java.util.zip.ZipFile zipf = new java.util.zip.ZipFile(zip);
                        java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zipf.entries();
                        int ri = 0, old = 0, bad = 0;
                        int oldver = 0, unreach = 0;
                        while (entries.hasMoreElements()) {
                            java.util.zip.ZipEntry entry = (java.util.zip.ZipEntry) entries.nextElement();
                            net.i2p.data.router.RouterInfo r = new net.i2p.data.router.RouterInfo();
                            InputStream in = zipf.getInputStream(entry);
                            try {r.readBytes(in);}
                            catch (DataFormatException dfe) {
                                System.out.println("Bad entry " + entry.getName() + ": " + dfe);
                                bad++;
                                continue;
                            } finally {in.close();}
                            if (r.getPublished() > cutoff) {ri++;}
                            else {old++;}
                            if (VersionComparator.comp(r.getVersion(), MIN_VERSION) < 0) {oldver++;}
                            if (r.getCapabilities().indexOf('U') >= 0) {unreach++;}
                        }
                        zipf.close();
                        if (bad > 0) {System.out.println(bad + " bad entries");}
                        if (old > 0) {
                            System.out.println("Failure:  " + old + " old RouterInfos returned");
                            fail++;
                        } else if (ri >= 50) {
                            System.out.println("Success:  " + ri + " RouterInfos returned");
                            pass++;
                            long time = System.currentTimeMillis() - start;
                            if (time > 30*1000) {
                                System.out.println("Test very slow for " + host + ", took " + DataHelper.formatDuration(time));
                                warn++;
                            }
                        } else {
                            System.out.println("Failure:  Only " + ri + " RouterInfos returned (less than 50)");
                            fail++;
                        }
                        System.out.println("Router infos included " + oldver + " with versions older than " + MIN_VERSION + " and " + unreach + " unreachable");
                    } else {
                        System.out.println("Failure:  Status code " + rc);
                        su3.delete();
                        fail++;
                    }
                } else {
                    int rc = get.getStatusCode();
                    System.out.println("Failure:  Status code " + rc);
                    su3.delete();
                    fail++;
                }
            } catch (Exception ioe) {
                System.out.println("Failure:  " + ioe.getMessage() + "\n");
                if (su3.exists()) {
                    try {SU3File.main(new String[] {"showversion", su3.getPath()});}
                    catch (Exception e) {}
                    su3.delete();
                }
                fail++;
            }
            System.out.println();
        }
        System.out.println("Test complete: " + (pass + fail) + " reseed hosts tested - " + pass + " passed, " + warn + " slow, " + fail + " failed");
        if (fail > 0) {System.exit(0);}
    }

}