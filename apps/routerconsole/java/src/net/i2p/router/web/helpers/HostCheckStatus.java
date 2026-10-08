package net.i2p.router.web.helpers;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import net.i2p.data.DataHelper;

/**
 *  Whether each site in the Sites of Interest listing is still worth showing.
 *
 *  <p>A site can fail in two independent ways, so two sources are consulted.
 *
 *  <p><b>Addressbook membership.</b> The router resolves names through
 *  {@code BlockfileNamingService}, backed by {@code hostsdb.blockfile} - the live addressbook,
 *  which is much larger than the {@code hosts.txt} shipped at install and accumulates
 *  entries beyond it. A site the naming service cannot resolve cannot be reached by name, so
 *  it is not shown. Membership is therefore asked of {@code NamingService} rather than read
 *  from any file: {@code hosts.txt} is only the seed the database was built from and says
 *  nothing about what is live.
 *
 *  <p><b>Probe status.</b> The addressbook checker periodically probes the sites it knows and
 *  appends the outcome to {@code addressbook/hosts_check.txt}, one CSV row per probe:
 *
 *  <pre>
 *  # Format: timestamp,host,reachable,category,responseTime,leaseSetTypes
 *  1791429608769,exil3.i2p,y,forum,34765,[6,4]
 *  </pre>
 *
 *  <p>A site in the addressbook whose latest probe failed is hidden and reappears when a later
 *  probe succeeds. A site in the addressbook that has not been probed yet is shown: absence of
 *  a result is not evidence of failure, so a cold cache must not empty the page.
 *
 *  <p>Only the host column and the reachable flag are read. The trailing field contains commas
 *  of its own ({@code [6,4]}); that is harmless because the fields of interest come first and
 *  the split is positional.
 *
 *  <p>Probe results are cached for one ping interval, since they cannot change any faster than
 *  the checker refreshes them.
 *
 *  @since 0.9.71+
 */
public class HostCheckStatus {

    /** Directory under the router dir holding the addressbook's own state. */
    static final String STATUS_DIR = "addressbook";

    /** The probe results, written by the addressbook host checker. */
    static final String STATUS_FILE = "hosts_check.txt";

    /** Where the ping interval is configured. */
    static final String CONFIG_FILE = "config.txt";

    /** Key naming the ping interval in {@link #CONFIG_FILE}. */
    static final String PROP_PING_INTERVAL = "pingInterval";

    /** Used when the config is absent or unparseable. Matches the checker's own default. */
    static final long DEFAULT_INTERVAL_MS = 4L * 60L * 60L * 1000L;

    /** The reachable flag as the checker writes it. */
    static final String REACHABLE_YES = "y";
    static final String REACHABLE_NO = "n";

    /** Rows carry at least timestamp, host and reachable. */
    private static final int MIN_FIELDS = 3;
    private static final int FIELD_HOST = 1;
    private static final int FIELD_REACHABLE = 2;

    private static final int MAX_LINE = 4096;

    private static File _cachedDir;
    private static Map<String, Boolean> _probed = Collections.emptyMap();
    private static boolean _haveFile;
    private static long _loadedAt;
    private static long _ttl = DEFAULT_INTERVAL_MS;

    /**
     *  The probe results, cached for the current ping interval.
     *
     *  <p>Callers rendering a whole list should fetch once and use
     *  {@link #isDown(String, boolean, Map)} per entry, rather than re-reading per entry.
     *
     *  @param routerDir the router's working directory
     *  @return host to reachable flag; empty if the results file could not be read, which
     *          judges nothing rather than judging everything
     */
    public static synchronized Map<String, Boolean> probed(File routerDir) {
        if (routerDir == null) { return Collections.emptyMap(); }
        long now = System.currentTimeMillis();
        if (_haveFile && _cachedDir != null && _cachedDir.equals(routerDir)
                && now - _loadedAt < _ttl) {
            return _probed;
        }
        _cachedDir = routerDir;
        _ttl = readInterval(routerDir);
        Map<String, Boolean> read = readProbed(routerDir);
        _probed = read != null ? read : Collections.<String, Boolean>emptyMap();
        _haveFile = read != null;
        _loadedAt = now;
        return _probed;
    }

    /**
     *  Whether a site should be hidden from the listing.
     *
     *  <p>A site is hidden when it is not in the addressbook at all, or when its latest probe
     *  failed. A site in the addressbook with no probe result is shown, because untested is
     *  not the same as down.
     *
     *  @param host hostname, already lowercased
     *  @param inAddressbook whether the naming service resolves the host
     * @param probed result of {@link #probed(File)}
     *  @return true if the link should not be shown
     */
    public static boolean isDown(String host, boolean inAddressbook, Map<String, Boolean> probed) {
        if (host == null) { return false; }
        if (!inAddressbook) { return true; }
        if (probed == null) { return false; }
        Boolean up = probed.get(host);
        return up != null && !up.booleanValue();
    }

    /**
     *  Extract the host from a link's URL.
     *
     *  <p>Strips the scheme, any path, any port, and lowercases the result. Returns null when
     *  there is no host to extract, which is also the signal that the link is not a site the
     *  addressbook could ever hold.
     *
     *  @param url the configured URL
     *  @return the lowercase host, or null
     */
    public static String hostFromUrl(String url) {
        if (url == null) { return null; }
        String rest = url.trim();
        if (rest.isEmpty()) { return null; }
        int scheme = rest.indexOf("://");
        if (scheme >= 0) { rest = rest.substring(scheme + 3); }
        int slash = rest.indexOf('/');
        if (slash >= 0) { rest = rest.substring(0, slash); }
        int colon = rest.indexOf(':');
        if (colon >= 0) { rest = rest.substring(0, colon); }
        if (rest.isEmpty()) { return null; }
        return rest.toLowerCase(Locale.US);
    }

    /**
     *  Whether a host is one the addressbook can hold, i.e. an I2P name.
     *
     *  <p>Links to clearnet or to the console's own paths are always shown; only .i2p names
     *  can be judged down.
     *
     *  <p>A {@code .b32.i2p} name is deliberately included: it is an I2P name, and the naming
     *  service resolves it. It is left in scope even though resolving one can reach the
     *  network, because the listing is not expected to contain them.
     *
     *  @param host lowercase host, as returned by {@link #hostFromUrl(String)}
     *  @return true if the host is an I2P name
     */
    public static boolean isI2pHost(String host) {
        return host != null && host.endsWith(".i2p");
    }

    /**
     *  Record one row of the results file.
     *
     *  <p>Comments and blank lines are skipped, as is any row whose reachable flag is neither
     *  {@code y} nor {@code n}, so a half-written row cannot be mistaken for a verdict and hide
     *  a working site. Hosts are lowercased so lookups need not worry about case. Later rows
     *  win, which is what lets a recovered site come back.
     *
     *  @param line one line from the results file
     *  @param out receives host to reachable
     */
    public static void parseProbeLine(String line, Map<String, Boolean> out) {
        if (line == null || out == null) { return; }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) == '#') { return; }
        String[] fields = trimmed.split(",");
        if (fields.length < MIN_FIELDS) { return; }
        String host = fields[FIELD_HOST].trim().toLowerCase(Locale.US);
        if (host.isEmpty()) { return; }
        String reachable = fields[FIELD_REACHABLE].trim();
        if (REACHABLE_YES.equals(reachable)) {
            out.put(host, Boolean.TRUE);
        } else if (REACHABLE_NO.equals(reachable)) {
            out.put(host, Boolean.FALSE);
        }
    }

    /**
     *  Convert a configured ping interval to milliseconds.
     *
     *  <p>Follows the checker's own convention: a trailing {@code M} means minutes, anything
     *  else means hours. The config in use reads {@code pingInterval=1}, which means one hour
     *  rather than one minute.
     *
     *  @param value the raw config value
     *  @return the interval in milliseconds, or {@link #DEFAULT_INTERVAL_MS} if unusable
     */
    public static long parseInterval(String value) {
        if (value == null) { return DEFAULT_INTERVAL_MS; }
        String raw = value.trim();
        if (raw.isEmpty()) { return DEFAULT_INTERVAL_MS; }
        boolean minutes = raw.toUpperCase(Locale.US).endsWith("M");
        if (minutes) { raw = raw.substring(0, raw.length() - 1).trim(); }
        try {
            long parsed = Long.parseLong(raw);
            if (parsed <= 0) { return DEFAULT_INTERVAL_MS; }
            return minutes ? parsed * 60L * 1000L : parsed * 60L * 60L * 1000L;
        } catch (NumberFormatException nfe) {
            return DEFAULT_INTERVAL_MS;
        }
    }

    /**
     *  Read the probe results.
     *
     *  @param routerDir the router's working directory
     *  @return host to reachable, or null if the file could not be read
     */
    private static Map<String, Boolean> readProbed(File routerDir) {
        File file = new File(new File(routerDir, STATUS_DIR), STATUS_FILE);
        if (!file.canRead()) { return null; }
        Map<String, Boolean> out = new HashMap<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8), MAX_LINE)) {
            String line;
            while ((line = in.readLine()) != null) { parseProbeLine(line, out); }
        } catch (IOException ioe) {
            return null;
        }
        return out;
    }

    /**
     *  Read the configured ping interval.
     *
     *  @param routerDir the router's working directory
     *  @return the interval in milliseconds, defaulting if unreadable
     */
    private static long readInterval(File routerDir) {
        File file = new File(new File(routerDir, STATUS_DIR), CONFIG_FILE);
        if (!file.canRead()) { return DEFAULT_INTERVAL_MS; }
        Properties props = new Properties();
        try {
            DataHelper.loadProps(props, file);
        } catch (IOException ioe) {
            return DEFAULT_INTERVAL_MS;
        }
        return parseInterval(props.getProperty(PROP_PING_INTERVAL));
    }

    /** Drop the cache. For tests, and for reacting to a configuration change. */
    static synchronized void clearCache() {
        _cachedDir = null;
        _probed = Collections.emptyMap();
        _haveFile = false;
        _loadedAt = 0;
        _ttl = DEFAULT_INTERVAL_MS;
    }
}
