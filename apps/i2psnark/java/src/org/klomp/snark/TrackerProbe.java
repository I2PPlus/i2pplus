package org.klomp.snark;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import net.i2p.util.Log;

/**
 * What we remember about the BEP-15 (udp) support of the trackers we announce
 * to over http, and the probe that finds it out.
 * <p>
 * A torrent names its trackers, and a tracker that answers on both transports
 * is usually listed twice, once as an http URL and once as a udp one. When it
 * is listed only over http we can ask it, at the conventional udp port, whether
 * it answers BEP-15 there. The question is the BEP-15 connect, which carries a
 * protocol id, an action and a transaction id, and no info hash, so asking it
 * discloses nothing about any torrent. The answer is a property of the tracker
 * host, so it is remembered per host, in the config file, with the instant it
 * was learned, and it goes stale on its own: a tracker may stop answering udp,
 * move port, or get filtered.
 * <p>
 * Nothing here changes what a torrent announces to on its own. The announce set
 * is derived from the torrent's metadata plus these verdicts each time the
 * torrent starts, so deleting the config keys reverts the behaviour exactly.
 *
 * @since 0.9.72+
 */
class TrackerProbe {

    /** Config property: announce over udp where a tracker is known to answer udp. */
    static final String PROP_PREFER_UDP = "i2psnark.preferUdpTrackers";

    /** Config property: ask http trackers over BEP-15 whether they answer udp. */
    static final String PROP_PROBE_UDP = "i2psnark.probeUdpTrackers";

    /**
     * Config key prefix for one host's remembered verdict, written as
     * {@code <prefix><host> = ok,<millis>} or {@code broken,<millis>}, the
     * millis being when it was learned. Host, verdict and time only, no
     * torrent named, so the file stays auditable.
     */
    static final String PROP_UDP_TRACKER = "i2psnark.udpTracker.";

    /** How long a "this host answers udp" verdict is trusted, in ms. */
    static final long OK_TTL = 30L*24*60*60*1000;

    /** How long a "it does not" verdict is trusted, in ms, deliberately shorter. */
    static final long FAIL_TTL = 24*60*60*1000;

    /** Most verdicts remembered, the oldest learned dropped beyond this. */
    static final int MAX_HOSTS = 200;

    /** How long to wait for a connect reply before calling it a miss, in ms. */
    static final long PROBE_TIMEOUT = 15*1000;

    /** Most probes queued at once; beyond that they are dropped, not queued. */
    static final int MAX_IN_FLIGHT = 16;

    /**
     * Longest host name worth probing: a full base-64 destination with the
     * {@code .i2p} suffix, which is what a URL host of a tracker with no
     * nickname looks like.
     */
    static final int MAX_HOST_LEN = 520;

    /** What we know about one tracker host's udp side. */
    enum Support {
        /** It answered a BEP-15 connect. */
        UDP,
        /** It did not answer, so announce to it over http. */
        NO_UDP,
        /** Never asked, or the answer has expired. */
        UNKNOWN
    }

    /** Logger. */
    private final Log _log;

    /** The util, for the shared udp tracker client and the config flags. */
    private final I2PSnarkUtil _util;

    /** The manager, to write verdicts into the config file. */
    private final SnarkManager _manager;

    /** Host to verdict; expired entries read as UNKNOWN. */
    private final Map<String, Support> _support;

    /** Host to the instant its verdict was learned, for the expiry and the trim. */
    private final Map<String, Long> _probedOn;

    /** Hosts with a probe in flight, so a torrent storm asks once. */
    private final Set<String> _inFlight;

    /** One thread, a bounded queue, a discard policy: probes are best effort. */
    private final ThreadPoolExecutor _probes;

    /**
     * Load the remembered verdicts from the config.
     *
     * @param util the util, for the udp client and the config flags
     * @param manager the manager, to persist verdicts
     * @param config the loaded config, read for existing verdicts, not null
     */
    TrackerProbe(I2PSnarkUtil util, SnarkManager manager, Properties config) {
        _log = new Log(TrackerProbe.class);
        _util = util;
        _manager = manager;
        _support = new ConcurrentHashMap<>(16);
        _probedOn = new ConcurrentHashMap<>(16);
        _inFlight = ConcurrentHashMap.newKeySet();
        _probes = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                                         new LinkedBlockingQueue<Runnable>(MAX_IN_FLIGHT),
                                         new ThreadPoolExecutor.DiscardPolicy());
        load(config);
    }

    /**
     * Re-read the verdicts from the config, dropping what is no longer in it.
     * The config file is the record, so a config reload, such as the webapp
     * loading a named config file, replaces the cache.
     *
     * @param config the loaded config, read for existing verdicts, not null
     */
    void reload(Properties config) {
        _support.clear();
        _probedOn.clear();
        load(config);
    }

    /**
     * Read the verdicts out of a config, skipping the absent, malformed and
     * expired ones.
     *
     * @param config the config to read, not null
     */
    private void load(Properties config) {
        long now = _util.getContext().clock().now();
        for (String key : config.stringPropertyNames()) {
            if (!key.startsWith(PROP_UDP_TRACKER)) {
                continue;
            }
            String value = config.getProperty(key);
            Support s = parse(value, now, OK_TTL, FAIL_TTL);
            if (s == Support.UNKNOWN) {
                continue;
            }
            String host = key.substring(PROP_UDP_TRACKER.length()).toLowerCase(Locale.US);
            _support.put(host, s);
            _probedOn.put(host, Long.valueOf(learned(value, now, OK_TTL, FAIL_TTL)));
        }
    }

    /**
     * Stop probing, dropping anything queued.
     */
    void stop() {
        _probes.shutdownNow();
    }

    /**
     * Whether the config asks us to prefer udp where a tracker answers it.
     *
     * @return true to prefer udp
     */
    boolean preferEnabled() {
        return "true".equals(_util.getContext().getProperty(PROP_PREFER_UDP));
    }

    /**
     * Whether the config asks us to probe http trackers for udp support. It
     * implies the preference, since a verdict is only worth learning to use.
     *
     * @return true to probe
     */
    boolean probeEnabled() {
        return preferEnabled() && "true".equals(_util.getContext().getProperty(PROP_PROBE_UDP));
    }

    /**
     * What we know about a host: UNKNOWN when never asked, or when the answer
     * has expired.
     *
     * @param host the tracker hostname, may be null
     * @return the verdict, never null
     */
    Support support(String host) {
        if (host == null) {
            return Support.UNKNOWN;
        }
        String key = host.toLowerCase(Locale.US);
        Support s = _support.get(key);
        if (s == null) {
            return Support.UNKNOWN;
        }
        Long when = _probedOn.get(key);
        long age = _util.getContext().clock().now() - (when == null ? 0L : when.longValue());
        return age > (s == Support.UDP ? OK_TTL : FAIL_TTL) ? Support.UNKNOWN : s;
    }

    /**
     * Ask a host, once, whether it answers BEP-15 on the port. Best effort,
     * and the guardrails are that the host is an I2P name, that we are not
     * already asking it, that we have a verdict to learn, and that the queue
     * is not full. A probe sends the BEP-15 connect and nothing else, so it
     * names no torrent.
     *
     * @param host the tracker hostname to ask
     * @param port the udp port to ask, usually the conventional one
     */
    void probe(String host, int port) {
        if (!probeEnabled() || port <= 0 || !isProbeable(host)) {
            return;
        }
        String key = host.toLowerCase(Locale.US);
        if (support(key) != Support.UNKNOWN || !_inFlight.add(key)) {
            return;
        }
        if (_probes.getQueue().remainingCapacity() <= 0) {
            _inFlight.remove(key);
            return;
        }
        _probes.execute(() -> {
            try {
                Support s = ask(key, port);
                record(key, s);
                if (_log.shouldInfo()) {
                    _log.info("BEP-15 probe of " + key + ':' + port + " -> " +
                              (s == Support.UDP ? "Answers udp" : "No udp answer"));
                }
            } catch (RuntimeException re) {
                _log.warn("BEP-15 probe of " + key + " failed -> " + re.toString(), re);
            } finally {
                _inFlight.remove(key);
            }
        });
    }

    /**
     * One probe: resolve the destination and run the connect handshake, with
     * the shared client, which needs no per-torrent destination.
     *
     * @param host the tracker hostname
     * @param port the udp port
     * @return UDP if it answered, NO_UDP if it did not, UNKNOWN if we could
     *         not ask, which is not a verdict about the tracker
     */
    private Support ask(String host, int port) {
        UDPTrackerClient udp = _util.getUDPTrackerClient();
        if (udp == null || !_util.connected()) {
            return Support.UNKNOWN;
        }
        return udp.probe(host, port, PROBE_TIMEOUT) ? Support.UDP : Support.NO_UDP;
    }

    /**
     * Remember a verdict and write it to the config, dropping the oldest
     * verdicts beyond {@link #MAX_HOSTS} so a torrent storm cannot grow the
     * config file without limit.
     *
     * @param host the tracker hostname
     * @param s the verdict; UNKNOWN is not worth remembering
     */
    void record(String host, Support s) {
        if (host == null || s == Support.UNKNOWN) {
            return;
        }
        String key = host.toLowerCase(Locale.US);
        long now = _util.getContext().clock().now();
        _support.put(key, s);
        _probedOn.put(key, Long.valueOf(now));
        _manager.setConfigProperty(PROP_UDP_TRACKER + key, format(s, now));
        int extra = _support.size() - MAX_HOSTS;
        if (extra > 0) {
            for (String oldest : trim(_probedOn, extra)) {
                _support.remove(oldest);
                _probedOn.remove(oldest);
                _manager.setConfigProperty(PROP_UDP_TRACKER + oldest, null);
            }
        }
        _manager.saveConfig();
    }

    /**
     * The hosts whose verdicts were learned longest ago.
     *
     * @param probedOn host to the instant it was learned, not null
     * @param count how many to name
     * @return up to count hosts, oldest first, never null
     */
    static Set<String> trim(Map<String, Long> probedOn, int count) {
        if (count <= 0) {
            return new LinkedHashSet<>(2);
        }
        Set<String> rv = new LinkedHashSet<>(count);
        List<Map.Entry<String, Long>> entries = new ArrayList<>(probedOn.entrySet());
        entries.sort(Comparator.comparingLong(e -> e.getValue().longValue()));
        for (Map.Entry<String, Long> e : entries) {
            if (rv.size() >= count) {
                break;
            }
            rv.add(e.getKey());
        }
        return rv;
    }

    /**
     * Read one stored verdict, applying its expiry.
     *
     * @param value the stored {@code verdict,millis}, may be null
     * @param now the current time in ms
     * @param okTTL how long a "yes" is trusted, in ms
     * @param failTTL how long a "no" is trusted, in ms
     * @return the verdict, UNKNOWN if absent, malformed, stamped in the future,
     *         or expired
     */
    static Support parse(String value, long now, long okTTL, long failTTL) {
        if (value == null) {
            return Support.UNKNOWN;
        }
        int comma = value.indexOf(',');
        if (comma <= 0) {
            return Support.UNKNOWN;
        }
        String verdict = value.substring(0, comma);
        long when;
        try {
            when = Long.parseLong(value.substring(comma + 1).trim());
        } catch (NumberFormatException nfe) {
            return Support.UNKNOWN;
        }
        long age = now - when;
        // A verdict stamped in the future is a clock that has moved, not a fact
        if (when > now) {
            return Support.UNKNOWN;
        }
        if ("ok".equals(verdict)) {
            return age <= okTTL ? Support.UDP : Support.UNKNOWN;
        }
        if ("broken".equals(verdict)) {
            return age <= failTTL ? Support.NO_UDP : Support.UNKNOWN;
        }
        return Support.UNKNOWN;
    }

    /**
     * One verdict as stored.
     *
     * @param s the verdict, not UNKNOWN
     * @param now the instant it was learned, in ms
     * @return the stored form, {@code ok,<millis>} or {@code broken,<millis>}
     */
    static String format(Support s, long now) {
        return (s == Support.UDP ? "ok," : "broken,") + now;
    }

    /**
     * The instant a stored verdict says it was learned.
     *
     * @param value the stored {@code verdict,millis}, not null
     * @param now the current time in ms
     * @param okTTL how long a "yes" is trusted, in ms
     * @param failTTL how long a "no" is trusted, in ms
     * @return the learned time, now if the value is unreadable, expired, or
     *         stamped in the future
     */
    static long learned(String value, long now, long okTTL, long failTTL) {
        if (parse(value, now, okTTL, failTTL) == Support.UNKNOWN) {
            return now;
        }
        int comma = value.indexOf(',');
        try {
            return Long.parseLong(value.substring(comma + 1).trim());
        } catch (NumberFormatException nfe) {
            return now;
        }
    }

    /**
     * Whether a host is worth probing: an I2P name and nothing else. A probe
     * of a clearnet host would be an outbound announce-shaped packet off the
     * box, and a name carrying a space, a port or credentials is not a host.
     *
     * @param host the tracker hostname, may be null
     * @return true to allow probing it
     */
    static boolean isProbeable(String host) {
        if (host == null || !host.endsWith(".i2p")) {
            return false;
        }
        int len = host.length();
        // 520 is a full base-64 destination with the suffix, 60 a base-32 one
        return len > 4 && len <= MAX_HOST_LEN && host.indexOf(' ') < 0 && host.indexOf(':') < 0 &&
               host.indexOf('@') < 0 && !host.startsWith("[");
    }

    /**
     * The host of an announce URL.
     *
     * @param url an http, https or udp announce URL, may be null
     * @return the host, lower-cased, null if there is none, e.g. for a URL with
     *         no host part
     */
    static String hostOf(String url) {
        if (url == null) {
            return null;
        }
        String rest = url;
        if (rest.startsWith("http://")) {
            rest = rest.substring(7);
        } else if (rest.startsWith("https://")) {
            rest = rest.substring(8);
        } else if (rest.startsWith("udp://")) {
            rest = rest.substring(6);
        }
        // http:///announce has no host in it, and neither does a bare path
        if (rest.startsWith("/")) {
            return null;
        }
        int slsh = rest.indexOf('/');
        if (slsh > 0) {
            rest = rest.substring(0, slsh);
        }
        int colon = rest.indexOf(':');
        if (colon > 0) {
            rest = rest.substring(0, colon);
        }
        return rest.isEmpty() ? null : rest.toLowerCase(Locale.US);
    }

    /**
     * The announce URL to use for the primary tracker's host: the udp URL for
     * that host when the torrent lists one, the conventional udp URL when a
     * probe says the host answers BEP-15, and the primary unchanged otherwise.
     * A primary that is already udp, and a host nothing supports over udp, are
     * returned untouched, so the shipped behaviour is what the caller gets.
     *
     * @param primary the torrent's declared announce URL, may be null
     * @param candidates the other announce URLs of the torrent, not null
     * @param udpSupported which hosts we may announce to over udp, not null
     * @return the URL to announce to for the primary's host, null only for a
     *         null primary
     */
    static String preferUDP(String primary, Collection<String> candidates, Predicate<String> udpSupported) {
        if (primary == null || primary.startsWith("udp://")) {
            return primary;
        }
        String host = hostOf(primary);
        if (host == null || !udpSupported.test(host)) {
            return primary;
        }
        for (String c : candidates) {
            if (c != null && c.startsWith("udp://") && host.equals(hostOf(c))) {
                return c;
            }
        }
        return "udp://" + host + ':' + TrackerClient.DEFAULT_UDP_TRACKER_PORT + '/';
    }

    /**
     * The hosts a set of announce URLs names for one transport. The http side
     * is everything that is not a udp URL, so a URL that cannot be read is
     * counted as http, which is what we would announce over anyway.
     *
     * @param urls the announce URLs, may be null
     * @param udp true for the udp hosts, false for the http ones
     * @return the hosts, lower-cased, never null
     */
    static Set<String> hosts(Collection<String> urls, boolean udp) {
        Set<String> rv = ConcurrentHashMap.newKeySet();
        if (urls == null) {
            return rv;
        }
        for (String u : urls) {
            if (u == null) {
                continue;
            }
            if (udp == u.startsWith("udp://")) {
                String h = hostOf(u);
                if (h != null) {
                    rv.add(h);
                }
            }
        }
        return rv;
    }
}
