package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.time.BuildTime;
import net.i2p.util.Addresses;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;
import net.i2p.router.BanLogger;

/**
 * Manages router banlist entries with configurable expiration times and transport-specific bans.
 * Provides methods to add, remove, and query ban status for routers based on various criteria
 * including permanent bans, temporary bans, and transport-specific restrictions.
 */
public class Banlist {
    private final Log _log;
    private final RouterContext _context;
    private final Map<Hash, Entry> _entries;

    // Key: router hash (Hash), Value: {timestamp, count}
    private final ConcurrentHashMap<Hash, long[]> _unsolicitedDBSearch;
    // Threshold for unsolicited DbSearchReply messages
    private static final int MAX_UNSOLICITED_DBSEARCH = 3;
    // Time window for unsolicited replies (ms): 15 minutes
    private static final long UNSOLICITED_DBSEARCH_WINDOW = 15*60*1000;

    // IP-based bad packet offender tracking
    // Key: IP address (String), Value: {timestamp, count}
    private final ConcurrentHashMap<String, long[]> _badPacketIPs;
    // Key: IP address (String), Value: {timestamp, count}
    private final ConcurrentHashMap<String, long[]> _corruptConnectionIPs;
    // Key: IP address (String), Value: {timestamp, count}
    private final ConcurrentHashMap<String, long[]> _portHoppingIPs;
    // Global threshold: number of offenses before temp ban (configurable)
    private static final String PROP_MAX_OFFENSES = "router.banlist.maxOffenses";
    private static final int MAX_OFFENSES_DEFAULT = 3;
    private int _maxOffenses;
    // Time window: offenses within this period count toward threshold (ms): 15 minutes (configurable)
    private static final String PROP_OFFENSE_WINDOW = "router.banlist.offenseWindow";
    private static final long OFFENSE_WINDOW_DEFAULT = 15*60*1000;
    private long _offenseWindow;
    // Startup grace period: don't track offenses in first 3 minutes after startup (configurable)
    private static final String PROP_STARTUP_GRACE = "router.banlist.startupGrace";
    private static final long STARTUP_GRACE_DEFAULT = 3*60*1000;
    private long _startupGrace;
    // Default ban duration for bad packet offenders (configurable)
    private static final String PROP_BAD_PACKET_DURATION = "router.banlist.badPacketDuration";
    private static final long BANLIST_DURATION_BAD_PACKETS_DEFAULT = 60*60*1000;
    private long _badPacketDuration;
    // Enable/disable auto-banning features (configurable)
    private static final String PROP_ENABLE_BAD_PACKET_BAN = "router.banlist.enableBadPacketBan";
    private static final boolean ENABLE_BAD_PACKET_BAN_DEFAULT = true;
    private boolean _enableBadPacketBan;
    private static final String PROP_ENABLE_CORRUPT_CONNECTION_BAN = "router.banlist.enableCorruptConnectionBan";
    private static final boolean ENABLE_CORRUPT_CONNECTION_BAN_DEFAULT = true;
    private boolean _enableCorruptConnectionBan;
    private static final String PROP_ENABLE_PORT_HOPPING_BAN = "router.banlist.enablePortHoppingBan";
    private static final boolean ENABLE_PORT_HOPPING_BAN_DEFAULT = true;
    private boolean _enablePortHoppingBan;
    private static final String PROP_ENABLE_DBSEARCH_BAN = "router.banlist.enableDbSearchBan";
    private static final boolean ENABLE_DBSEARCH_BAN_DEFAULT = true;
    private boolean _enableDbSearchBan;
    // Blocklist enable (maps to router.blocklist.enable)
    private static final String PROP_ENABLE_BLOCKLIST = "router.blocklist.enable";
    private static final boolean ENABLE_BLOCKLIST_DEFAULT = true;
    private boolean _enableBlocklist;
    // Tor blocklist enable (maps to router.blocklistTor.enable)
    private static final String PROP_ENABLE_TOR_BLOCKLIST = "router.blocklistTor.enable";
    private static final boolean ENABLE_TOR_BLOCKLIST_DEFAULT = true;
    private boolean _enableTorBlocklist;
    // Country blocklist enable (maps to router.blocklistCountries.enable)
    private static final String PROP_ENABLE_COUNTRY_BAN = "router.blocklistCountries.enable";
    private static final boolean ENABLE_COUNTRY_BAN_DEFAULT = false;
    private boolean _enableCountryBan;
    // XG router ban (unlimited bandwidth, no transit tunnels)
    private static final String PROP_ENABLE_XG_BAN = "router.banlistXG";
    private static final boolean ENABLE_XG_BAN_DEFAULT = false;
    private boolean _enableXgBan;
    // LU router ban (low bandwidth tier, unreachable/firewalled)
    private static final String PROP_ENABLE_LU_BAN = "router.banlistLU";
    private static final boolean ENABLE_LU_BAN_DEFAULT = true;
    private boolean _enableLuBan;
    // Custom capability bans (e.g., "LG", "XfU", "DGU")
    private static final String PROP_CUSTOM_CAPABILITY_BANS = "router.banlistCapabilities";
    private static final Pattern COMMA_SPLIT = Pattern.compile("[,\\s]+");
    private String _customCapabilityBans;
    // Country ban duration - uses existing router.blockCountries property (session-based)

    /**
     *  hash of 387 zeros
     *  @since 0.9.66
     */
    public static final Hash HASH_ZERORI = new Hash(Base64.decode("MRn86w6tHQgE25D7DIejOBCJ-dImSjdsQaOaBuUypkE="));

    /** Entry representing a banned peer with expiration and reason information */
    public static class Entry {
        /** when it should expire, per the i2p clock */
        public long expireOn;
        /** why they were banlisted */
        public String cause;
        /** separate code so cause can contain {0} for translation */
        public String causeCode;
        /** set of transports to ban, null means all transports */
        public Set<String> transports;

        /**
         *  Default constructor.
         */
        public Entry() {}
    }

    /**
     * Default ban duration for transient bans.
     */
    public final static long BANLIST_DURATION_MS = 7*60*1000; // Don't make this too long as the failure may be transient due to connection limits.
    /**
     * Maximum ban duration for transient bans.
     */
    public final static long BANLIST_DURATION_MAX = 30*60*1000;
    /**
     * Default ban duration for transport-specific bans.
     */
    public final static long BANLIST_DURATION_PARTIAL = 10*60*1000;
    /**
     * Permanent ban duration (will be rounded down to 180 days on console).
     */
    public final static long BANLIST_DURATION_FOREVER = 181L*24*60*60*1000; // will get rounded down to 180d on console

    /**
     *  Buggy i2pd fork
     *  @since 0.9.52
     */
    /**
     *  Buggy i2pd fork
     *  @since 0.9.52
     */
    public final static long BANLIST_DURATION_NO_NETWORK = 30*24*60*60*1000L;
    /**
     * Ban duration for private IP addresses.
     */
    public final static long BANLIST_DURATION_PRIVATE = 2*60*60*1000;
    /**
     * Ban duration for repeat bad packet offenders.
     */
    public final static long BANLIST_DURATION_BAD_PACKETS = 60*60*1000; // 1 hour
    private final static long BANLIST_CLEANER_START_DELAY = BANLIST_DURATION_PARTIAL;

    /**
     *  A ban that expires after this will return true in isBanlistedForever().
     *  In the transports, "forever" is treated as a hard ban, and both
     *  inbound and outbound connections will be rejected.
     *  Not-forever is treated as a soft ban, with outbound rejected
     *  but inbound will be allowed and will automatically unban.
     */
    private static final long BANLIST_FOREVER_THRESHOLD = 24*60*60*1000L;

    /**
     *  Constructor.
     *  @param context the router context
     */
    public Banlist(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(Banlist.class);
        _entries = new ConcurrentHashMap<Hash, Entry>(16);
        _badPacketIPs = new ConcurrentHashMap<String, long[]>(64);
        _corruptConnectionIPs = new ConcurrentHashMap<String, long[]>(64);
        _portHoppingIPs = new ConcurrentHashMap<String, long[]>(64);
        _unsolicitedDBSearch = new ConcurrentHashMap<Hash, long[]>(64);
        _context.jobQueue().addJob(new Cleanup(_context));
        banlistRouterForever(Hash.FAKE_HASH, "" + "Invalid Hash"); // i2pd bug?
        banlistRouterForever(HASH_ZERORI, "" + "Invalid Hash (All zeros)");
        initConfig();
    }

    /**
     *  Initialize configurable properties.
     *  Reads from router config, falls back to defaults.
     */
    private void initConfig() {
        _maxOffenses = _context.getProperty(PROP_MAX_OFFENSES, MAX_OFFENSES_DEFAULT);
        _offenseWindow = _context.getProperty(PROP_OFFENSE_WINDOW, OFFENSE_WINDOW_DEFAULT);
        _startupGrace = _context.getProperty(PROP_STARTUP_GRACE, STARTUP_GRACE_DEFAULT);
        _badPacketDuration = _context.getProperty(PROP_BAD_PACKET_DURATION, BANLIST_DURATION_BAD_PACKETS_DEFAULT);
        _enableBadPacketBan = "true".equals(_context.getProperty(PROP_ENABLE_BAD_PACKET_BAN, String.valueOf(ENABLE_BAD_PACKET_BAN_DEFAULT)));
        _enableCorruptConnectionBan = "true".equals(_context.getProperty(PROP_ENABLE_CORRUPT_CONNECTION_BAN, String.valueOf(ENABLE_CORRUPT_CONNECTION_BAN_DEFAULT)));
        _enablePortHoppingBan = "true".equals(_context.getProperty(PROP_ENABLE_PORT_HOPPING_BAN, String.valueOf(ENABLE_PORT_HOPPING_BAN_DEFAULT)));
        _enableDbSearchBan = "true".equals(_context.getProperty(PROP_ENABLE_DBSEARCH_BAN, String.valueOf(ENABLE_DBSEARCH_BAN_DEFAULT)));
        _enableBlocklist = "true".equals(_context.getProperty(PROP_ENABLE_BLOCKLIST, String.valueOf(ENABLE_BLOCKLIST_DEFAULT)));
        _enableTorBlocklist = "true".equals(_context.getProperty(PROP_ENABLE_TOR_BLOCKLIST, String.valueOf(ENABLE_TOR_BLOCKLIST_DEFAULT)));
        _enableCountryBan = "true".equals(_context.getProperty(PROP_ENABLE_COUNTRY_BAN, String.valueOf(ENABLE_COUNTRY_BAN_DEFAULT)));
        _enableXgBan = "true".equals(_context.getProperty(PROP_ENABLE_XG_BAN, String.valueOf(ENABLE_XG_BAN_DEFAULT)));
        _enableLuBan = "true".equals(_context.getProperty(PROP_ENABLE_LU_BAN, String.valueOf(ENABLE_LU_BAN_DEFAULT)));
        _customCapabilityBans = _context.getProperty(PROP_CUSTOM_CAPABILITY_BANS, "");
    }

    /**
     *  Reload configuration from properties.
     *  Called when settings are changed in the console.
     *  @since 0.9.70
     */
    public void reloadConfig() {
        initConfig();
    }

    /**
     *  Clear all session-based bans.
     *  @since 0.9.70
     */
    public void clearSessionBans() {
        _entries.clear();
        _unsolicitedDBSearch.clear();
        _badPacketIPs.clear();
        _corruptConnectionIPs.clear();
        _portHoppingIPs.clear();
    }

    /**
     *  Get the current max offenses setting.
     *  @since 0.9.70
     */
    public int getMaxOffenses() { return _maxOffenses; }

    /**
     *  Get the current offense window setting in ms.
     *  @since 0.9.70
     */
    public long getOffenseWindow() { return _offenseWindow; }

    /**
     *  Get the current startup grace period in ms.
     *  @since 0.9.70
     */
    public long getStartupGrace() { return _startupGrace; }

    /**
     *  Get the current bad packet ban duration in ms.
     *  @since 0.9.70
     */
    public long getBadPacketDuration() { return _badPacketDuration; }

    /**
     *  Check if bad packet auto-ban is enabled.
     *  @since 0.9.70
     */
    public boolean isBadPacketBanEnabled() { return _enableBadPacketBan; }

    /**
     *  Check if corrupt connection auto-ban is enabled.
     *  @since 0.9.70
     */
    public boolean isCorruptConnectionBanEnabled() { return _enableCorruptConnectionBan; }

    /**
     *  Check if port hopping auto-ban is enabled.
     *  @since 0.9.70
     */
    public boolean isPortHoppingBanEnabled() { return _enablePortHoppingBan; }

    /**
     *  Check if unsolicited DB search reply auto-ban is enabled.
     *  @since 0.9.70
     */
    public boolean isDbSearchBanEnabled() { return _enableDbSearchBan; }

    /**
     *  Check if IP blocklist is enabled.
     *  @since 0.9.70
     */
    public boolean isBlocklistEnabled() { return _enableBlocklist; }

    /**
     *  Check if Tor exit node blocklist is enabled.
     *  @since 0.9.70
     */
    public boolean isTorBlocklistEnabled() { return _enableTorBlocklist; }

    /**
     *  Check if country-based bans are enabled.
     *  @since 0.9.70
     */
    public boolean isCountryBanEnabled() { return _enableCountryBan; }

    /**
     *  Check if XG router bans are enabled.
     *  XG = unlimited bandwidth (X), no transit tunnels (G) - often botnet indicators
     *  @since 0.9.70
     */
    public boolean isXgBanEnabled() { return _enableXgBan; }

/**
     *  Check if LU router bans are enabled.
     *  LU = low bandwidth tier (L) + unreachable/firewalled (U)
     *  @since 0.9.70
     */
    public boolean isLuBanEnabled() { return _enableLuBan; }

    /**
     *  Check if a retroactive NetDb purge sweep is needed.
     *  True if LU/XG bans or custom capability bans are enabled.
     *  @since 0.9.70
     */
    public boolean shouldPurgeExistingRouters() {
        return _enableLuBan || _enableXgBan ||
               (_customCapabilityBans != null && !_customCapabilityBans.isEmpty());
    }

    /**
     *  Get custom capability ban pattern.
     *  Format: string of capability letters (e.g., "DG", "UX")
     *  @since 0.9.70
     */
    public String getCustomCapabilityBans() { return _customCapabilityBans != null ? _customCapabilityBans : ""; }

    /**
     *  Check if router capabilities match any of the custom ban patterns.
     *  @param capabilities router capabilities string (e.g., "XfP")
     *  @return the matched pattern (e.g., "G") or null if no match
     *  @since 0.9.70
     */
    public String shouldBanlistByCapability(String capabilities) {
        if (_customCapabilityBans == null || _customCapabilityBans.isEmpty() || capabilities == null) {
            return null;
        }
        String caps = capabilities.toUpperCase();
        String[] patterns = COMMA_SPLIT.split(_customCapabilityBans);
        for (String pattern : patterns) {
            pattern = pattern.trim().toUpperCase();
            if (pattern.isEmpty()) continue;
            boolean matches = true;
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (caps.indexOf(c) < 0) {
                    matches = false;
                    break;
                }
            }
            if (matches) return pattern;
        }
        return null;
    }

    private class Cleanup extends JobImpl {
        /**
         *  @param ctx the router context
         */
        public Cleanup(RouterContext ctx) {
            super(ctx);
            getTiming().setStartAfter(ctx.clock().now() + BANLIST_CLEANER_START_DELAY);
        }

        public String getName() {return "Expire Banned Peers";}

        /**
         *  Removes expired ban entries and updates message history.
         */
        public void runJob() {
            List<Hash> toUnbanlist = new ArrayList<Hash>(4);
            long now = getContext().clock().now();
            try {
                for (Iterator<Map.Entry<Hash, Entry>> iter = _entries.entrySet().iterator(); iter.hasNext(); ) {
                    Map.Entry<Hash, Entry> e = iter.next();
                    if (e.getValue().expireOn <= now) {
                        iter.remove();
                        toUnbanlist.add(e.getKey());
                    }
                }
            } catch (IllegalStateException ise) {} // next time...
            for (Hash peer : toUnbanlist) {
                _context.messageHistory().unbanlist(peer);
                if (_log.shouldInfo()) {
                    _log.info("Removing expired ban from [" + peer.toBase64().substring(0,6) + "]");
                }
            }

            // Clean up expired unsolicited DBSearch tracking entries
            try {
                for (Iterator<Map.Entry<Hash, long[]>> iter = _unsolicitedDBSearch.entrySet().iterator(); iter.hasNext(); ) {
                    Map.Entry<Hash, long[]> e = iter.next();
                    if (now - e.getValue()[0] > UNSOLICITED_DBSEARCH_WINDOW) {
                        iter.remove();
                    }
                }
            } catch (IllegalStateException ise) {} // next time...

            requeue(5*60*1000);
        }
    }

/**
      * Record a bad packet from an IP.
      * If threshold exceeded, ban the IP.
      * @param ip IP address (format: "1.2.3.4" or "1.2.3.4:port")
      * @param version router version info (may be null)
      */
    public void badPacket(String ip, String version) {
        if (ip == null) return;
        if (!_enableBadPacketBan) return;
        if (_context.router().getUptime() < _startupGrace) return;
        long now = _context.clock().now();

        long[] data = _badPacketIPs.get(ip);
        if (data == null) {
            // First bad packet from this IP
            data = new long[] {now, 1};
            _badPacketIPs.put(ip, data);
            return;
        }

        // Check if within window
        if (now - data[0] > _offenseWindow) {
            // Window expired, reset count
            data[0] = now;
            data[1] = 1;
        } else {
            // Within window, increment
            data[1]++;
        }

        if (data[1] >= _maxOffenses) {
            // Ban the IP
            String reason = "Sending bad packets";
            if (version != null) {
                reason += " (" + version + ")";
            }

            if (_log.shouldWarn()) {
                _log.warn("Bad packet limit exceeded for " + ip + ": banning for " + _badPacketDuration/60000 + " min");
            }

            BanLogger banLogger = new BanLogger();
            banLogger.initialize(_context);
            banLogger.logBanIPOnly(ip, reason, _badPacketDuration);

            _context.blocklist().add(ip, reason);

            // Remove from tracker
            _badPacketIPs.remove(ip);
        }
    }

    /**
     * Record a corrupt connection (EOF/no data during establishment) from an IP.
     * If threshold exceeded, ban the IP.
     * @param ip IP address (format: "1.2.3.4" or "1.2.3.4:port")
     * @param version router version info (may be null)
     */
    public void corruptConnection(String ip, String version) {
        if (ip == null) return;
        if (!_enableCorruptConnectionBan) return;
        if (_context.router().getUptime() < _startupGrace) return;
        long now = _context.clock().now();

        long[] data = _corruptConnectionIPs.get(ip);
        if (data == null) {
            data = new long[] {now, 1};
            _corruptConnectionIPs.put(ip, data);
            return;
        }

        if (now - data[0] > _offenseWindow) {
            data[0] = now;
            data[1] = 1;
        } else {
            data[1]++;
        }

        if (data[1] >= _maxOffenses) {
            String reason = "Corrupt connection (no data)";
            if (version != null) {
                reason += " (" + version + ")";
            }

            if (_log.shouldWarn()) {
                _log.warn("Corrupt connection limit exceeded for " + ip + ": banning for " + _badPacketDuration/60000 + " min");
            }

            BanLogger banLogger = new BanLogger();
            banLogger.initialize(_context);
            banLogger.logBanIPOnly(ip, reason, _badPacketDuration);

            _context.blocklist().add(ip, reason);

            _corruptConnectionIPs.remove(ip);
        }
    }

    /**
     * Record a port hopping attempt from an IP.
     * If threshold exceeded, ban the IP.
     * @param ip IP address (format: "1.2.3.4")
     */
    public void portHopping(String ip) {
        if (ip == null) return;
        if (!_enablePortHoppingBan) return;
        if (_context.router().getUptime() < _startupGrace) return;
        long now = _context.clock().now();

        long[] data = _portHoppingIPs.get(ip);
        if (data == null) {
            data = new long[] {now, 1};
            _portHoppingIPs.put(ip, data);
            return;
        }

        if (now - data[0] > _offenseWindow) {
            data[0] = now;
            data[1] = 1;
        } else {
            data[1]++;
        }

        if (data[1] >= _maxOffenses) {
            String reason = "Port hopping";

            if (_log.shouldWarn()) {
                _log.warn("Port hopping limit exceeded for " + ip + ": banning for " + _badPacketDuration/60000 + " min");
            }

            BanLogger banLogger = new BanLogger();
            banLogger.initialize(_context);
            banLogger.logBanIPOnly(ip, reason, _badPacketDuration);

            _context.blocklist().add(ip, reason);

            _portHoppingIPs.remove(ip);
        }
    }

    /**
     * Track an unsolicited DbSearchReply from a router.
     * If threshold exceeded in window, ban the router hash.
     * @param routerHash Hash of the router sending unsolicited reply
     * @param version router version (may be null)
     */
    public void unsolicitedDBSearchReply(Hash routerHash, String version) {
        if (routerHash == null) return;
        if (!_enableDbSearchBan) return;
        if (_context.router().getUptime() < _startupGrace) return;
        long now = _context.clock().now();

        long[] data = _unsolicitedDBSearch.get(routerHash);
        if (data == null) {
            data = new long[] {now, 1};
            _unsolicitedDBSearch.put(routerHash, data);
            return;
        }

        if (now - data[0] > UNSOLICITED_DBSEARCH_WINDOW) {
            data[0] = now;
            data[1] = 1;
        } else {
            data[1]++;
        }

        if (data[1] >= MAX_UNSOLICITED_DBSEARCH) {
            String reason = "Fake/Slow Search Replies";
            if (version != null) {
                reason += " (" + version + ")";
            }

            if (_log.shouldWarn()) {
                String hashStr = routerHash.toBase64().substring(0, 6);
                _log.warn("Unsolicited DbSearchReply limit exceeded for [" + hashStr + "]: banning for " + _badPacketDuration/60000 + " min");
            }

            BanLogger banLogger = new BanLogger();
            banLogger.initialize(_context);
            banLogger.logBan(routerHash, _context, reason, _badPacketDuration);
            banlistRouter(routerHash, reason, null, null, _context.clock().now() + _badPacketDuration);
            _unsolicitedDBSearch.remove(routerHash);
        }
    }

    /**
     *  Get the number of currently banlisted routers.
     *  @return the number of currently banlisted routers
     */
    public int getRouterCount() {return _entries.size();}

    /**
     *  Get the banlist entries.
     *  For BanlistRenderer in router console.
     *  Note - may contain expired entries.
     *  @return an unmodifiable map of router hashes to banlist entries
     */
    public Map<Hash, Entry> getEntries() {return Collections.unmodifiableMap(_entries);}

    /**
     *  Ban a router with default duration.
     *  @param peer the router hash to ban
     *  @return true if it WAS previously on the list
     */
    public boolean banlistRouter(Hash peer) {return banlistRouter(peer, null);}

    /**
     *  Ban a router with default duration.
     *  @param peer the router hash to ban
     *  @param reason the reason for the ban (may be null)
     *  @return true if it WAS previously on the list
     */
    public boolean banlistRouter(Hash peer, String reason) {return banlistRouter(peer, reason, null);}

    /**
     *  Ban a router with default duration.
     *  @param reasonCode separate code so cause can contain {0} for translation
     *  @param peer the router hash to ban
     *  @param reason the reason for the ban (may be null)
     *  @return true if it WAS previously on the list
     */
    public boolean banlistRouter(String reasonCode, Hash peer, String reason) {
        return banlistRouter(peer, reason, reasonCode, null, false);
    }

    /**
     *  Ban a router on a specific transport.
     *  @param peer the router hash to ban
     *  @param reason the reason for the ban (may be null)
     *  @param transport the transport to ban (may be null for all transports)
     *  @return true if it WAS previously on the list
     */
    public boolean banlistRouter(Hash peer, String reason, String transport) {
        return banlistRouter(peer, reason, transport, false);
    }

    /**
     *  Permanently ban a router.
     *  @param peer the router hash to ban
     *  @param reason the reason for the ban (may be null)
     *  @return true if it WAS previously on the list
     */
    public boolean banlistRouterForever(Hash peer, String reason) {
        return banlistRouter(peer, reason, null, true);
    }

    /**
     *  Permanently ban a router.
     *  @param peer the router hash to ban
     *  @param reason the reason for the ban (may be null)
     *  @param reasonCode separate code so cause can contain {0} for translation
     *  @return true if it WAS previously on the list
     */
    public boolean banlistRouterForever(Hash peer, String reason, String reasonCode) {
        return banlistRouter(peer, reason, reasonCode, null, true);
    }

    /**
     *  Ban a router with configurable duration and transport.
     *  @param peer the router hash to ban
     *  @param reason the reason for the ban (may be null)
     *  @param transport the transport to ban (may be null for all transports)
     *  @param forever if true, ban permanently
     *  @return true if it WAS previously on the list
     */
    public boolean banlistRouter(Hash peer, String reason, String transport, boolean forever) {
        return banlistRouter(peer, reason, null, transport, forever);
    }

    /**
     *  Ban a router with automatic duration calculation.
     *  @param peer the router hash to ban
     *  @param reason the reason for the ban (may be null)
     *  @param reasonCode separate code so cause can contain {0} for translation (may be null)
     *  @param transport the transport to ban (may be null for all transports)
     *  @param forever if true, ban permanently
     *  @return true if it WAS previously on the list
     */
    private boolean banlistRouter(Hash peer, String reason, String reasonCode, String transport, boolean forever) {
        long expireOn;
        if (forever) {expireOn = _context.clock().now() + BANLIST_DURATION_FOREVER;}
        else if (transport != null) {expireOn = _context.clock().now() + BANLIST_DURATION_PARTIAL;}
        else {
            long period = BANLIST_DURATION_MS + _context.random().nextLong(BANLIST_DURATION_MS / 4);
            if (period > BANLIST_DURATION_MAX) {period = BANLIST_DURATION_MAX;}
            expireOn = _context.clock().now() + period;
        }
        return banlistRouter(peer, reason, reasonCode, transport, expireOn);
    }

    /**
     *  Ban a router with a specified expiration time.
     *  @param peer the router hash to ban
     *  @param reason the reason for the ban (may be null)
     *  @param reasonCode separate code so cause can contain {0} for translation (may be null)
     *  @param transport the transport to ban (may be null for all transports)
     *  @param expireOn absolute time when the ban expires, not a duration
     *  @return true if it WAS previously on the list
     *  @throws IllegalArgumentException if expireOn is before the earliest valid time
     *  @since 0.9.18
     */
    public boolean banlistRouter(Hash peer, String reason, String reasonCode, String transport, long expireOn) {
        long banDuration =  ((expireOn - _context.clock().now()) / 1000) / 60;
        if (expireOn < _context.clock().now()) {
            if (expireOn < BuildTime.getEarliestTime()) {
                throw new IllegalArgumentException("Bad expiration: " + DataHelper.formatTime(expireOn)); // catch errors where we were passed a duration
            }
            return false;
        }
        if (peer == null) {
            if (_log.shouldWarn()) {_log.warn("Cannot apply Router ban, peer is null");}
            return false;
        }
        if (peer.equals(_context.routerHash())) {
            if (_log.shouldWarn()) {_log.warn("Not banning our own router!");}
            return false;
        }
        boolean wasAlready = false;
        String logReason = reason.replace("<b>➜</b>", "->");
        if (_log.shouldInfo()) {
            _log.info("Banning [" + peer.toBase64().substring(0,6) + "] for " + banDuration + " minutes " +
               ((transport != null) ? "on transport " + transport : "") + logReason);
        }

        Entry e = new Entry();
        e.expireOn = expireOn;
        e.cause = reason;
        e.causeCode = reasonCode;
        e.transports = null;
        if (transport != null) {
            e.transports = new ConcurrentHashSet<String>(2);
            e.transports.add(transport);
        }

        Entry old = _entries.get(peer);
        if (old != null) {
            wasAlready = true;
            // take the oldest expiration and cause, combine transports
            if (old.expireOn > e.expireOn) {
                e.expireOn = old.expireOn;
                e.cause = old.cause;
                e.causeCode = old.causeCode;
            }
            if (e.transports != null) {
                if (old.transports != null) {
                    e.transports.addAll(old.transports);
                } else {
                    e.transports = null;
                    e.cause = reason;
                    e.causeCode = reasonCode;
                }
            }
        }
        _entries.put(peer, e);

        if (transport == null) {
            // we hate the peer on *any* transport
            _context.netDb().fail(peer);
            _context.tunnelManager().fail(peer);
        }
        if (!wasAlready) {_context.messageHistory().banlist(peer, reason);}
        return wasAlready;
    }

    /**
     *  Remove a router from the banlist.
     *  @param peer the router hash to remove from banlist
     */
    public void unbanlistRouter(Hash peer) {unbanlistRouter(peer, true);}
    /**
     *  @param peer the router hash to remove from banlist
     *  @param realUnbanlist if true, update message history
     */
    private void unbanlistRouter(Hash peer, boolean realUnbanlist) {unbanlistRouter(peer, realUnbanlist, null);}
    /**
     *  Remove a router from the banlist for a specific transport.
     *  @param peer the router hash to remove from banlist
     *  @param transport the transport to unban (may be null for all transports)
     */
    public void unbanlistRouter(Hash peer, String transport) {unbanlistRouter(peer, true, transport);}

    /**
     *  Remove a router from the banlist.
     *  @param peer the router hash to remove from banlist
     *  @param realUnbanlist if true, update message history
     *  @param transport the transport to unban (may be null for all transports)
     */
    private void unbanlistRouter(Hash peer, boolean realUnbanlist, String transport) {
        if (peer == null) return;
        if (_log.shouldInfo())
            _log.info("Removing expired ban from [" + peer.toBase64().substring(0,6) + "]"
                      + (transport != null ? "/" + transport : ""));
        boolean fully = false;

        Entry e = _entries.remove(peer);
        if ( (e == null) || (e.transports == null) || (transport == null) || (e.transports.size() <= 1) ) {
            // fully unbanlisted
            fully = true;
        } else {
            e.transports.remove(transport);
            if (e.transports.isEmpty())
                fully = true;
            else
                _entries.put(peer, e);
        }

        if (fully) {
            _context.messageHistory().unbanlist(peer);
            if (_log.shouldInfo() && e != null)
                _log.info("Removing expired ban from [" + peer.toBase64().substring(0,6) + "]"
                          + (transport != null ? " / " + transport : ""));
        }
    }

    /**
     *  Check if a router is banlisted.
     *  @param peer the router hash to check
     *  @return true if the router is banlisted on any transport
     */
    public boolean isBanlisted(Hash peer) {return isBanlisted(peer, null);}

    /**
     *  Check if a router is banlisted on a specific transport.
     *  @param peer the router hash to check
     *  @param transport the transport to check (may be null)
     *  @return true if the router is banlisted on the specified transport
     */
    public boolean isBanlisted(Hash peer, String transport) {
        if (peer == null || transport == null) {return false;}

        boolean rv = false;
        boolean unbanlist = false;

        Entry entry = _entries.get(peer);
        if (entry == null) {rv = false;}
        else if (entry.expireOn <= _context.clock().now()) {
            _entries.remove(peer);
            unbanlist = true;
            rv = false;
        } else if (entry.transports == null) {rv = true;}
        else {rv = entry.transports.contains(transport);}

        if (unbanlist) {
            _context.messageHistory().unbanlist(peer);
            if (_log.shouldInfo()) {
                _log.info("Removing expired ban from [" + peer.toBase64().substring(0,6) + "]");
            }
        }

        return rv;
    }

    /**
     *  Check if a router is permanently banlisted.
     *  @param peer the router hash to check
     *  @return true if the router is permanently banlisted
     */
    public boolean isBanlistedForever(Hash peer) {
        Entry entry = _entries.get(peer);
        return entry != null && entry.expireOn > _context.clock().now() + BANLIST_FOREVER_THRESHOLD;
    }

    /**
     *  Check if a router is banlisted with a hostile duration (at least 1 hour).
     *  @param peer the router hash to check
     *  @return true if the router is banlisted with hostile duration
     *  @since 0.9.58+
     */
    public boolean isBanlistedHostile(Hash peer) {
        if (peer != null) {
            Entry entry = _entries.get(peer);
            return entry != null && entry.expireOn >= _context.clock().now() + 60*60*1000L;
        } else {return false;}
    }

    /** @deprecated moved to router console */
    @Deprecated
    public void renderStatusHTML(Writer out) throws IOException {}

}
