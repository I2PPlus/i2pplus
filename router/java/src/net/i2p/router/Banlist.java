package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
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
import net.i2p.data.router.RouterInfo;
import net.i2p.time.BuildTime;
import net.i2p.util.Addresses;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;


/**
 * Manages router banlist entries with configurable expiration times and transport-specific bans.
 * Provides methods to add, remove, and query ban status for routers based on various criteria
 * including permanent bans, temporary bans, and transport-specific restrictions.
 */
public class Banlist {
    /** Logger for ban decisions. */
    private final Log _log;
    /** The router context. */
    private final RouterContext _context;
    /** Active bans by router hash. */
    private final Map<Hash, Entry> _entries;
    /**
     * How many entries {@link #_entries} may hold. A banlist with no ceiling
     * grows to whatever a flood of RouterInfos can fill it with, and the
     * duration reduction in {@link #reduceDurations} is not a bound on that:
     * it only shortens the bans already here.
     */
    private final int _maxBanEntries;

    /** Bad packet offenses by IP address, with times. */
    private final ConcurrentHashMap<String, long[]> _badPacketIPs;
    /** Port hopping offenses by IP address, with times. */
    private final ConcurrentHashMap<String, long[]> _portHoppingIPs;
    /**
     * Most source addresses one offense tracker will hold at once. A
     * spoofed-source flood presents a new source per packet, so this ceiling,
     * not the age sweep in {@code Cleanup}, is what bounds the trackers; it is
     * the order of magnitude of the single-IP blocklist they feed.
     */
    static final int MAX_OFFENSE_TRACKING = 8192;
    /** Config key for the offenses needed to ban. */
    private static final String PROP_MAX_OFFENSES = "router.banlist.maxOffenses";
    /** Default offenses needed to ban. */
    static final int MAX_OFFENSES_DEFAULT = 3;
    /** Offenses needed to ban, in effect right now. */
    private int _maxOffenses;
    /**
     * Config key for the window offenses count in, 15 minutes by default.
     */
    private static final String PROP_OFFENSE_WINDOW = "router.banlist.offenseWindow";
    /** The default offense window is 15 minutes. */
    static final long OFFENSE_WINDOW_DEFAULT = 15*60*1000L;
    /** Offense window in effect right now. */
    private long _offenseWindow;
    /**
     * Config key for the startup grace period, during which offenses
     * are not tracked, 3 minutes by default.
     */
    private static final String PROP_STARTUP_GRACE = "router.banlist.startupGrace";
    /** The default startup grace before offenses count is 3 minutes. */
    private static final long STARTUP_GRACE_DEFAULT = 3*60*1000L;
    /** Startup grace period in effect right now. */
    private long _startupGrace;
    /** Config key for the bad packet ban length. */
    private static final String PROP_BAD_PACKET_DURATION = "router.banlist.badPacketDuration";
    /** The default bad packet ban lasts 1 hour. */
    private static final long BANLIST_DURATION_BAD_PACKETS_DEFAULT = 60*60*1000L;
    /** Bad packet ban length in effect right now. */
    private long _badPacketDuration;
    /** Config key turning the bad packet ban on or off. */
    private static final String PROP_ENABLE_BAD_PACKET_BAN = "router.banlist.enableBadPacketBan";
    /** Bad packet banning is off by default. */
    private static final boolean ENABLE_BAD_PACKET_BAN_DEFAULT = false;
    /** Whether bad packet banning is enabled. */
    private boolean _enableBadPacketBan;
    /** Config key turning the port hopping ban on or off. */
    private static final String PROP_ENABLE_PORT_HOPPING_BAN = "router.banlist.enablePortHoppingBan";
    /** Port hopping banning is off by default. */
    private static final boolean ENABLE_PORT_HOPPING_BAN_DEFAULT = false;
    /** Whether port hopping banning is enabled. */
    private boolean _enablePortHoppingBan;
    /** Config key turning the blocklist on or off. */
    private static final String PROP_ENABLE_BLOCKLIST = "router.blocklist.enable";
    /** The blocklist is on by default. */
    private static final boolean ENABLE_BLOCKLIST_DEFAULT = true;
    /** Whether the blocklist is enabled. */
    private boolean _enableBlocklist;
    /** Config key turning the Tor blocklist on or off. */
    private static final String PROP_ENABLE_TOR_BLOCKLIST = "router.blocklistTor.enable";
    /** The Tor blocklist is on by default. */
    private static final boolean ENABLE_TOR_BLOCKLIST_DEFAULT = true;
    /** Whether the Tor blocklist is enabled. */
    private boolean _enableTorBlocklist;
    /** Config key turning the country blocklist on or off. */
    private static final String PROP_ENABLE_COUNTRY_BAN = "router.blocklistCountries.enable";
    /** Country banning is off by default. */
    private static final boolean ENABLE_COUNTRY_BAN_DEFAULT = false;
    /** Whether country banning is enabled. */
    private boolean _enableCountryBan;
    /** Config key turning the XG router ban on or off. */
    private static final String PROP_ENABLE_XG_BAN = "router.banlistXG";
    /** Banning XG capacity routers is off by default. */
    private static final boolean ENABLE_XG_BAN_DEFAULT = false;
    /** Whether the XG router ban is enabled. */
    private boolean _enableXgBan;
    /** Config key turning the LU router ban on or off. */
    private static final String PROP_ENABLE_LU_BAN = "router.banlistLU";
    /** Banning LU capacity routers is off by default. */
    private static final boolean ENABLE_LU_BAN_DEFAULT = false;
    /** Whether the LU router ban is enabled. */
    private boolean _enableLuBan;
    /**
     * Config key turning the introducer-cap ban on or off, for an SSU2
     * address advertising more introducers than the spec allows.
     */
    private static final String PROP_ENABLE_INTRODUCER_BAN = "router.banlistIntroducers";
    /** Banning introducer-capped routers is on by default. */
    private static final boolean ENABLE_INTRODUCER_BAN_DEFAULT = true;
    /** Whether the introducer-cap ban is enabled. */
    private boolean _enableIntroducerBan;
    /** Config key turning the invalid version ban on or off. */
    private static final String PROP_ENABLE_INVALID_VERSION_BAN = "router.banlistInvalidVersion";
    /** Banning invalid router versions is on by default. */
    private static final boolean ENABLE_INVALID_VERSION_BAN_DEFAULT = true;
    /** Whether the invalid version ban is enabled. */
    private boolean _enableInvalidVersionBan;
    /**
     * Config key turning the bad publication date ban on or off, for a
     * router info published in the past epoch.
     */
    private static final String PROP_ENABLE_PUB_DATE_BAN = "router.banlistPubDate";
    /** Banning bad publication dates is on by default. */
    private static final boolean ENABLE_PUB_DATE_BAN_DEFAULT = true;
    /** Whether the bad publication date ban is enabled. */
    private boolean _enablePubDateBan;
    /** Config key turning the contradictory caps ban on or off. */
    private static final String PROP_ENABLE_RU_CAPS_BAN = "router.banlistRUCaps";
    /** Banning contradictory reachability caps is on by default. */
    private static final boolean ENABLE_RU_CAPS_BAN_DEFAULT = true;
    /** Whether the contradictory caps ban is enabled. */
    private boolean _enableRUCapsBan;
    /** Config key listing capability letters to ban. */
    private static final String PROP_CUSTOM_CAPABILITY_BANS = "router.banlistCapabilities";
    /** Splits the capability ban list on commas and spaces. */
    private static final Pattern COMMA_SPLIT = Pattern.compile("[,\\s]+");
    /** Capability letters to ban, from the config. */
    private String _customCapabilityBans;

    /**
     * Hash of 387 zeros.
     *
     * @since 0.9.66
     */
    public static final Hash HASH_ZERORI = new Hash(Base64.decode("MRn86w6tHQgE25D7DIejOBCJ-dImSjdsQaOaBuUypkE="));

    /** Entry representing a banned peer with expiration and reason information. */
    public static class Entry {
        /** When it should expire, per the i2p clock. */
        public long expireOn;
        /**
         * When this ban was first recorded, per the i2p clock; 0 when unknown.
         * <p>Separate from {@link #expireOn} because the ban duration varies by
         * class and by transport, so the start time cannot be derived from the
         * expiry. Needed to answer "was this peer already banned when the build
         * was dispatched, or did the ban land mid-flight" — the two need
         * different fixes, and only the duration was previously observable.
         * <p>On a re-ban that keeps the older, longer expiry this retains the
         * <em>original</em> start time: what matters is how long the banlist
         * has been holding this peer, not when the latest report arrived.
         *
         * @since 0.9.71+
         */
        public long addedOn;
        /** Why they were banlisted. */
        public String cause;
        /** Separate code so cause can contain {0} for translation. */
        public String causeCode;
        /** Transports to ban, null means all transports. */
        public Set<String> transports;

        /** Constructs an empty entry. */
        public Entry() {
            // intentionally empty - default constructor
        }
    }

    /**
     * Default ban duration for transient bans.
     */
    public static final long BANLIST_DURATION_MS = 7*60*1000L; // Don't make this too long as the failure may be transient due to connection limits.
    /**
     * Maximum ban duration for transient bans.
     */
    public static final long BANLIST_DURATION_MAX = 30*60*1000L;
    /**
     * Default ban duration for transport-specific bans.
     */
    public static final long BANLIST_DURATION_PARTIAL = 10*60*1000L;
    /**
     * Permanent ban duration (will be rounded down to 180 days on console).
     */
    public static final long BANLIST_DURATION_FOREVER = 181L*24*60*60*1000L; // will get rounded down to 180d on console

    /**
     * Buggy i2pd fork.
     *
     * @since 0.9.52
     */
    public static final long BANLIST_DURATION_NO_NETWORK = 30*24*60*60*1000L;
    /**
     * Ban duration for private IP addresses.
     */
    public static final long BANLIST_DURATION_PRIVATE = 2*60*60*1000L;
    /**
     * Ban duration for repeat bad packet offenders.
     */
    public static final long BANLIST_DURATION_BAD_PACKETS = 60*60*1000L; // 1 hour
    /** Wait before the cleanup job first runs. */
    private static final long BANLIST_CLEANER_START_DELAY = BANLIST_DURATION_PARTIAL;

    /**
     * Ban count baseline for proportional duration reduction.
     * At this count or below, no reduction is applied.
     * Above this, remaining durations are scaled by baseline / count,
     * minimum scale floor MIN_BAN_SCALE.
     */
    static final int BAN_COUNT_BASELINE = 3000;
    /** Smallest factor a ban duration may be scaled by. */
    private static final double MIN_BAN_SCALE = 0.25;
    /** Eligible ban durations for reduction: &gt; 4 hours. */
    static final long BAN_REDUCTION_MIN = 4 * 60 * 60 * 1000L;
    /** Eligible ban durations for reduction: &lt; 1 week. */
    static final long BAN_REDUCTION_MAX = 7 * 24 * 60 * 60 * 1000L;
    /** Above this count, ALL bans over 1h are halved regardless of existing scaling. */
    static final int BAN_COUNT_HIGH = 8000;
    /** A ban with more than this much left is halved once the count is over {@link #BAN_COUNT_HIGH}. */
    static final long BAN_HALVE_CUTOFF = 60 * 60 * 1000L;

    /**
     * A ban with at least this much time left is one of the permanent ones: a
     * signed feed's list, a country ban, the operator's bans on
     * /configpeer, our own previous identity, a peer on another network. The
     * pressure relief in {@link #reduceDurations} and the ceiling enforced by
     * {@link #pruneExcess} leave those alone. The threshold is above the
     * longest ban a peer can buy for itself - 30 days, the
     * unspecified-network class any peer earns with one RouterInfo - and below
     * {@link #BANLIST_DURATION_FOREVER}, so a flood of cheap bans is still
     * shortened and dropped while the bans that matter are not.
     * <p>
     * This is not the question {@link #isBanlistedForever(Hash)} asks: that one
     * is whether a ban is hard enough to refuse an inbound handshake.
     *
     * @since 0.9.72+
     */
    static final long BANLIST_PERMANENT_REMAINING = 90L*24*60*60*1000L;

    /**
     * Banlist ceiling for a slow router, the size upstream gives it. It is
     * below {@link #BAN_COUNT_HIGH}, so a low-memory router bounds the list by
     * dropping its newest bans rather than by shortening them.
     */
    static final int MAX_BAN_ENTRIES_SLOW = 2048;
    /** Banlist ceiling for a desktop router, the size upstream gives it. */
    static final int MAX_BAN_ENTRIES = 16384;

    /**
     * A ban that expires after this will return true in {@link #isBanlistedForever(Hash)}.
     * In the transports, "forever" is treated as a hard ban, and both
     * inbound and outbound connections will be rejected.
     * Not-forever is treated as a soft ban, with outbound rejected
     * but inbound will be allowed and will automatically unban.
     */
    private static final long BANLIST_FOREVER_THRESHOLD = 24*60*60*1000L;

    /**
     * Seeds the entry maps, bans the known invalid hashes, and reads the config.
     *
     * @param context the router context
     */
    public Banlist(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(Banlist.class);
        _entries = new ConcurrentHashMap<>(16);
        _maxBanEntries = maxBanEntries();
        _badPacketIPs = new ConcurrentHashMap<>(64);
        _portHoppingIPs = new ConcurrentHashMap<>(64);
        _context.jobQueue().addJob(new Cleanup(_context));
        banlistRouterForever(Hash.FAKE_HASH, "" + "Invalid Hash"); // i2pd bug?
        banlistRouterForever(HASH_ZERORI, "" + "Invalid Hash (All zeros)");
        initConfig();
    }

    /**
     * Initialize configurable properties.
     * Reads from router config, falls back to defaults.
     */
    private void initConfig() {
        _maxOffenses = _context.getProperty(PROP_MAX_OFFENSES, MAX_OFFENSES_DEFAULT);
        _offenseWindow = _context.getProperty(PROP_OFFENSE_WINDOW, OFFENSE_WINDOW_DEFAULT);
        _startupGrace = _context.getProperty(PROP_STARTUP_GRACE, STARTUP_GRACE_DEFAULT);
        _badPacketDuration = _context.getProperty(PROP_BAD_PACKET_DURATION, BANLIST_DURATION_BAD_PACKETS_DEFAULT);
        _enableBadPacketBan = "true".equals(_context.getProperty(PROP_ENABLE_BAD_PACKET_BAN, String.valueOf(ENABLE_BAD_PACKET_BAN_DEFAULT)));
        _enablePortHoppingBan = "true".equals(_context.getProperty(PROP_ENABLE_PORT_HOPPING_BAN, String.valueOf(ENABLE_PORT_HOPPING_BAN_DEFAULT)));
        _enableBlocklist = "true".equals(_context.getProperty(PROP_ENABLE_BLOCKLIST, String.valueOf(ENABLE_BLOCKLIST_DEFAULT)));
        _enableTorBlocklist = "true".equals(_context.getProperty(PROP_ENABLE_TOR_BLOCKLIST, String.valueOf(ENABLE_TOR_BLOCKLIST_DEFAULT)));
        _enableCountryBan = "true".equals(_context.getProperty(PROP_ENABLE_COUNTRY_BAN, String.valueOf(ENABLE_COUNTRY_BAN_DEFAULT)));
        _enableXgBan = "true".equals(_context.getProperty(PROP_ENABLE_XG_BAN, String.valueOf(ENABLE_XG_BAN_DEFAULT)));
        _enableLuBan = "true".equals(_context.getProperty(PROP_ENABLE_LU_BAN, String.valueOf(ENABLE_LU_BAN_DEFAULT)));
        _enableIntroducerBan = "true".equals(_context.getProperty(PROP_ENABLE_INTRODUCER_BAN, String.valueOf(ENABLE_INTRODUCER_BAN_DEFAULT)));
        _enableInvalidVersionBan = "true".equals(_context.getProperty(PROP_ENABLE_INVALID_VERSION_BAN, String.valueOf(ENABLE_INVALID_VERSION_BAN_DEFAULT)));
        _enablePubDateBan = "true".equals(_context.getProperty(PROP_ENABLE_PUB_DATE_BAN, String.valueOf(ENABLE_PUB_DATE_BAN_DEFAULT)));
        _enableRUCapsBan = "true".equals(_context.getProperty(PROP_ENABLE_RU_CAPS_BAN, String.valueOf(ENABLE_RU_CAPS_BAN_DEFAULT)));
        _customCapabilityBans = _context.getProperty(PROP_CUSTOM_CAPABILITY_BANS, "");
    }

    /**
     * Reload configuration from properties.
     * Called when settings are changed in the console.
     *
     * @since 0.9.70+
     */
    public void reloadConfig() {
        initConfig();
    }

    /**
     * Clear all session-based bans.
     *
     * @since 0.9.70+
     */
    public void clearSessionBans() {
        _entries.clear();
        _badPacketIPs.clear();
        _portHoppingIPs.clear();
    }

    /**
     * Get the current max offenses setting.
     *
     * @return the maximum offenses before auto-ban
     * @since 0.9.70+
     */
    public int getMaxOffenses() { return _maxOffenses; }

    /**
     * Get the current offense window setting in ms.
     *
     * @return the offense window in milliseconds
     * @since 0.9.70+
     */
    public long getOffenseWindow() { return _offenseWindow; }

    /**
     * Get the current startup grace period in ms.
     *
     * @return the startup grace period in milliseconds
     * @since 0.9.70+
     */
    public long getStartupGrace() { return _startupGrace; }

    /**
     * Get the current bad packet ban duration in ms.
     *
     * @return the bad packet ban duration in milliseconds
     * @since 0.9.70+
     */
    public long getBadPacketDuration() { return _badPacketDuration; }

    /**
     * Check if bad packet auto-ban is enabled.
     *
     * @return true if bad packet auto-ban is enabled
     * @since 0.9.70+
     */
    public boolean isBadPacketBanEnabled() { return _enableBadPacketBan; }

    /**
     * Check if port hopping auto-ban is enabled.
     *
     * @return true if port hopping auto-ban is enabled
     * @since 0.9.70+
     */
    public boolean isPortHoppingBanEnabled() { return _enablePortHoppingBan; }

    /**
     * Check if IP blocklist is enabled.
     *
     * @return true if IP blocklist is enabled
     * @since 0.9.70+
     */
    public boolean isBlocklistEnabled() { return _enableBlocklist; }

    /**
     * Check if Tor exit node blocklist is enabled.
     *
     * @return true if Tor exit node blocklist is enabled
     * @since 0.9.70+
     */
    public boolean isTorBlocklistEnabled() { return _enableTorBlocklist; }

    /**
     * Check if country-based bans are enabled.
     *
     * @return true if country-based bans are enabled
     * @since 0.9.70+
     */
    public boolean isCountryBanEnabled() { return _enableCountryBan; }

    /**
     * Check if XG router bans are enabled.
     * XG = unlimited bandwidth (X), no transit tunnels (G) - often botnet indicators.
     *
     * @return true if XG router bans are enabled
     * @since 0.9.70+
     */
    public boolean isXgBanEnabled() { return _enableXgBan; }

    /**
     * Whether the LU ban is enabled - banning peers that publish a low
     * bandwidth tier and no reachability, see
     * {@link #isLowTierUnreachable(String)}.
     * <p>
     * This covers the ban, the disconnect that goes with it, and the NetDb rule
     * that skips storing such a peer. It does not cover the wider rule that
     * keeps unreachable and low-bandwidth RouterInfos off disk: a peer this
     * switch leaves unbanned is still not persisted while it is unreachable.
     *
     * @return true if LU router bans are enabled
     * @since 0.9.70+
     */
    public boolean isLuBanEnabled() { return _enableLuBan; }

    /**
     * Check if introducer-cap router bans are enabled. An SSU2 address
     * advertising more than the spec's five introducers is out of spec -
     * usually a scanner or a modified client - so it can be banned when we
     * learn of it, not only when we handshake.
     *
     * @return true if over-cap introducer bans are enabled
     * @since 0.9.72+
     */
    public boolean isIntroducerBanEnabled() { return _enableIntroducerBan; }

    /**
     * Check the invalid-RouterInfo-version ban is enabled. A RouterInfo
     * publishing a version above every released version is fabricated, so it
     * can be banned where it is seen rather than only at the handshake.
     *
     * @return true if fabricated-version bans are enabled
     * @since 0.9.72+
     */
    public boolean isInvalidVersionBanEnabled() { return _enableInvalidVersionBan; }

    /**
     * Check the invalid-publication-date ban is enabled.
     *
     * @return true if bad-publication-date bans are enabled
     * @since 0.9.72+
     */
    public boolean isPubDateBanEnabled() { return _enablePubDateBan; }

    /**
     * Check the contradictory-reachability-caps ban is enabled.
     *
     * @return true if routers publishing both R and U are banned
     * @since 0.9.72+
     */
    public boolean isRUCapsBanEnabled() { return _enableRUCapsBan; }

    /**
     * Highest I2P version recognised as real. No released version reaches it,
     * so a RouterInfo publishing a version at or above this ceiling has built
     * the RouterInfo by hand - usually a scanner probing for netdb storage.
     * Kept here so the NTCP2 handshake, the floodfill store path and the data
     * store all agree on one ceiling.
     *
     * @since 0.9.72+
     */
    static final String MAX_VALID_VERSION = "2.5.0";

    /**
     * Whether a RouterInfo version is above every version that has been
     * released, which makes the RouterInfo fabricated rather than merely old.
     *
     * @param version the version string from the RouterInfo, may be null
     * @return true if the version is not a plausible released version
     * @since 0.9.72+
     */
    public static boolean isInvalidVersion(String version) {
        return version != null && VersionComparator.comp(version, MAX_VALID_VERSION) >= 0;
    }

    /**
     * Whether a capability string claims to be reachable and unreachable at
     * once. The two flags are mutually exclusive by definition, so a router
     * publishing both has assembled its own RouterInfo rather than run a
     * router that measured its reachability.
     *
     * @param caps the capability string, may be null
     * @return true if both the R and U flags are present
     * @since 0.9.72+
     */
    public static boolean publishesContradictoryCaps(String caps) {
        return caps != null &&
               caps.indexOf(Router.CAPABILITY_REACHABLE) >= 0 &&
               caps.indexOf(Router.CAPABILITY_UNREACHABLE) >= 0;
    }

    /**
     * Whether a capability string is the LU condition: a peer that advertises
     * little bandwidth and cannot be reached as a client. Low tier is BW12 or
     * BW32; unreachable is the U flag, or the absence of R, which is how the
     * transports read a peer that never answered a handshake.
     * <p>
     * This is the one definition of the condition. The two bans in
     * KademliaNetworkDatabaseFacade, the store rules in PersistentDataStore and
     * TransientDataStore, and the request throttle all ask the same question of
     * the same RouterInfo, and they used to answer it from five hand-written
     * capability tests that disagreed about which tiers counted - one read
     * BW12 only, another BW32 only. A peer could be banned on one path and kept
     * on another.
     * <p>
     * ParticipatingThrottler's low-share test is deliberately wider than this -
     * it also counts BW64 and the no-transit G flag - and is not this question.
     *
     * @param caps the capability string, may be null
     * @return true if the peer is both low tier and unreachable
     * @since 0.9.72+
     */
    public static boolean isLowTierUnreachable(String caps) {
        if (caps == null) return false;
        boolean isLowTier = caps.indexOf(Router.CAPABILITY_BW12) >= 0 ||
                            caps.indexOf(Router.CAPABILITY_BW32) >= 0;
        boolean isUnreachable = caps.indexOf(Router.CAPABILITY_UNREACHABLE) >= 0 ||
                                caps.indexOf(Router.CAPABILITY_REACHABLE) < 0;
        return isLowTier && isUnreachable;
    }

    /**
     * Check if a retroactive NetDb purge sweep is needed.
     * True if LU/XG/introducer bans, the RouterInfo validation bans (version,
     * publication date, reachability flags), or custom capability bans are
     * enabled.
     *
     * @return true if existing routers should be purged
     * @since 0.9.70+
     */
    public boolean shouldPurgeExistingRouters() {
        return _enableLuBan || _enableXgBan || _enableIntroducerBan ||
               _enableInvalidVersionBan || _enablePubDateBan || _enableRUCapsBan ||
               (_customCapabilityBans != null && !_customCapabilityBans.isEmpty());
    }

    /**
     * Get custom capability ban pattern.
     * Format: string of capability letters (e.g., "DG", "UX").
     *
     * @return the custom capability ban pattern, or empty string if none
     * @since 0.9.70+
     */
    public String getCustomCapabilityBans() { return _customCapabilityBans != null ? _customCapabilityBans : ""; }

    /**
     * Check if router capabilities match any of the custom ban patterns.
     * <p>
     * A pattern may carry an exclusion group after '!': "G!f" matches
     * routers with G in their caps unless they are also floodfills.
     * Exclusions require at least one required character.
     * <p>
     * Comparison is case-sensitive throughout: capability letters are
     * case-significant (lowercase 'f' is floodfill, uppercase letters are
     * separate meanings), so neither the stored patterns nor the router's
     * caps may be case-folded before matching.
     *
     * @param capabilities router capabilities string (e.g., "XfP")
     * @return the matched pattern exactly as configured (e.g., "G!f") or null if no match
     * @since 0.9.70+
     */
    public String shouldBanlistByCapability(String capabilities) {
        return matchCapabilityPattern(capabilities, _customCapabilityBans);
    }

    /**
     * Pure decision helper: match a capabilities string against a
     * comma/space-separated list of capability ban patterns.
     * <p>
     * Each pattern is a set of required characters, optionally followed by
     * '!'. A router matches when it carries every required character and
     * none of the excluded ones. A pattern with an empty required part can
     * never match, so a bare exclusion cannot ban the entire network.
     * Case-sensitive: see {@link #shouldBanlistByCapability}.
     *
     * @param capabilities router capabilities string, may be null
     * @param patternsStr configured patterns, may be null or empty
     * @return the first matching pattern as configured, or null if none match
     * @since 0.9.70+
     */
    static String matchCapabilityPattern(String capabilities, String patternsStr) {
        if (patternsStr == null || patternsStr.isEmpty() || capabilities == null || capabilities.isEmpty()) {
            return null;
        }
        String[] patterns = COMMA_SPLIT.split(patternsStr);
        for (String pattern : patterns) {
            pattern = pattern.trim();
            if (pattern.isEmpty()) continue;
            int bang = pattern.indexOf('!');
            String required = bang >= 0 ? pattern.substring(0, bang) : pattern;
            String excluded = bang >= 0 ? pattern.substring(bang + 1) : "";
            if (required.isEmpty()) continue;
            boolean ok = true;
            for (int i = 0; i < required.length() && ok; i++) {
                if (capabilities.indexOf(required.charAt(i)) < 0) {ok = false;}
            }
            for (int i = 0; i < excluded.length() && ok; i++) {
                if (capabilities.indexOf(excluded.charAt(i)) >= 0) {ok = false;}
            }
            if (ok) return pattern;
        }
        return null;
    }

    /** Periodically removes expired bans and offenses. */
    private class Cleanup extends JobImpl {
        /**
         * Creates the periodic banlist cleanup job.
         *
         * @param ctx the router context
         */
        public Cleanup(RouterContext ctx) {
            super(ctx);
            getTiming().setStartAfter(ctx.clock().now() + BANLIST_CLEANER_START_DELAY);
        }

        public String getName() {return "Expire Banned Peers";}

        /**
         * Removes expired ban entries and updates message history.
         */
        public void runJob() {
            List<Hash> toUnbanlist = new ArrayList<>(4);
            long now = getContext().clock().now();
            try {
                for (Iterator<Map.Entry<Hash, Entry>> iter = _entries.entrySet().iterator(); iter.hasNext(); ) {
                    Map.Entry<Hash, Entry> e = iter.next();
                    if (e.getValue().expireOn <= now) {
                        iter.remove();
                        toUnbanlist.add(e.getKey());
                    }
                }
            } catch (IllegalStateException ise) { /* ignored */ } // next time...
            for (Hash peer : toUnbanlist) {
                _context.messageHistory().unbanlist(peer);
                if (_log.shouldInfo()) {
                    _log.info("Removing expired ban from [" + peer.toShortBase64() + "]");
                }
            }

            // Proportionally reduce durations of longer bans when count is high
            reduceDurations(now);

            // Shortening bans is no bound on the size of the list: a flood of
            // cheap RouterInfos leaves it as full as it filled. Give the newest
            // of them back, so the room is there for the bans that matter.
            pruneExcess(now);

            // Offense counts older than the window can no longer reach the
            // threshold, and a spoofed-source flood leaves one entry per source
            // behind it, so drop them here.
            pruneOffenses(now);

            requeue(5L*60*1000);
        }
    }

    /**
     * When the ban count exceeds BAN_COUNT_BASELINE, proportionally reduce
     * remaining durations of eligible entries (between BAN_REDUCTION_MIN
     * and BAN_REDUCTION_MAX) to keep the list manageable under memory pressure.
     * Scaling: remaining *= max(MIN_BAN_SCALE, (double) BAN_COUNT_BASELINE / count).
     *
     * @param now the current time in ms, the reference for each entry's
     *            remaining duration
     */
    private void reduceDurations(long now) {
        int count = _entries.size();
        if (count <= BAN_COUNT_BASELINE)
            return;
        double scale = (double) BAN_COUNT_BASELINE / count;
        if (scale < MIN_BAN_SCALE)
            scale = MIN_BAN_SCALE;
        boolean halving = count > BAN_COUNT_HIGH;
        int scaledCount = 0;
        int halvedCount = 0;
        for (Entry e : _entries.values()) {
            long expireOn = e.expireOn;
            long newExpireOn = scaledExpiry(expireOn, now, scale);
            if (newExpireOn != expireOn) {scaledCount++;}
            if (halving) {
                long halvedOn = halvedExpiry(newExpireOn, now);
                if (halvedOn != newExpireOn) {halvedCount++;}
                newExpireOn = halvedOn;
            }
            if (newExpireOn != expireOn) {
                e.expireOn = newExpireOn;
            }
        }
        if (scaledCount > 0 && _log.shouldDebug()) {
            _log.debug("Reduced ban durations -> Bans: " + count + " * Scaled: " + scaledCount +
                       " * Scale: " + scale);
        }
        if (halvedCount > 0 && _log.shouldInfo()) {
            _log.info("Banlist over " + BAN_COUNT_HIGH + " -> Halved bans over " +
                      DataHelper.formatDuration(BAN_HALVE_CUTOFF) + " * Bans: " + count +
                      " * Halved: " + halvedCount);
        }
    }

    /**
     * Is this a permanent ban, one the pressure relief must not undo?
     *
     * @param remaining ms left on the ban, negative if it has already expired
     * @return true if the ban is long enough to be one of the permanent ones
     * @since 0.9.72+
     */
    static boolean isPermanentBan(long remaining) {
        return remaining >= BANLIST_PERMANENT_REMAINING;
    }

    /**
     * New expiry for one ban under the proportional pass: a ban between
     * {@link #BAN_REDUCTION_MIN} and {@link #BAN_REDUCTION_MAX} of remaining
     * time is scaled by the current count, never below half the minimum.
     * Anything else, and any permanent ban, keeps its expiry.
     *
     * @param expireOn when the ban currently expires
     * @param now the current time in ms
     * @param scale the factor for the current count, between
     *              {@link #MIN_BAN_SCALE} and 1
     * @return the expiry to use, expireOn if the ban is not eligible
     * @since 0.9.72+
     */
    static long scaledExpiry(long expireOn, long now, double scale) {
        long remaining = expireOn - now;
        if (isPermanentBan(remaining) || remaining < BAN_REDUCTION_MIN || remaining >= BAN_REDUCTION_MAX) {
            return expireOn;
        }
        long minRemaining = BAN_REDUCTION_MIN / 2;
        long newRemaining = Math.max((long) (remaining * scale), minRemaining);
        return now + newRemaining;
    }

    /**
     * New expiry for one ban under the aggressive pass taken above
     * {@link #BAN_COUNT_HIGH}: every ban with more than
     * {@link #BAN_HALVE_CUTOFF} left has it halved, except a permanent one,
     * which is the case this pass used to clear - seven passes turned a
     * 181-day ban into a soft one and eight took the hostility off it, so a
     * flood of cheap RouterInfos undid the feed's bans and the operator's in
     * under an hour.
     *
     * @param expireOn when the ban currently expires
     * @param now the current time in ms
     * @return the expiry to use, expireOn if the ban is not eligible
     * @since 0.9.72+
     */
    static long halvedExpiry(long expireOn, long now) {
        long remaining = expireOn - now;
        if (isPermanentBan(remaining) || remaining <= BAN_HALVE_CUTOFF) {
            return expireOn;
        }
        return now + remaining / 2;
    }

    /**
     * How many bans this router holds room for. A low-memory router cannot
     * afford the desktop's ceiling, and each entry is a hash, an entry object
     * and the strings naming its cause.
     *
     * @return the ceiling on the number of entries in the banlist
     * @since 0.9.72+
     */
    static int maxBanEntries() {
        return SystemVersion.isSlow() ? MAX_BAN_ENTRIES_SLOW : MAX_BAN_ENTRIES;
    }

    /**
     * How far the Cleanup job trims the list back once it is over the ceiling.
     * Trimming only as far as the ceiling would leave no room for a new ban
     * until the next pass, so a quarter of the list is freed for them.
     *
     * @param maxEntries the ceiling being enforced
     * @return the size to trim down to
     * @since 0.9.72+
     */
    static int pruneTarget(int maxEntries) {
        return maxEntries - maxEntries / 4;
    }

    /**
     * Trim the banlist back to {@link #pruneTarget(int)} when it is over the
     * ceiling, dropping the newest bans first. The newest are the flood: the
     * hashes behind it are ones we will never see again, while the bans that
     * matter were recorded earlier and are the last to go. Permanent bans are
     * not candidates at all, and the ceiling is enforced where bans are
     * recorded as well, so the list cannot grow past it between passes.
     *
     * @param now the current time in ms
     * @since 0.9.72+
     */
    void pruneExcess(long now) {
        if (_entries.size() <= _maxBanEntries) {return;}
        int target = pruneTarget(_maxBanEntries);
        int dropped = 0;
        for (Map.Entry<Hash, Entry> e : droppableBans(_entries, now)) {
            if (_entries.size() <= target) {break;}
            if (_entries.remove(e.getKey(), e.getValue())) {
                _context.messageHistory().unbanlist(e.getKey());
                dropped++;
            }
        }
        if (dropped > 0 && _log.shouldWarn()) {
            _log.warn("Banlist over its limit -> Dropped the newest bans * Dropped: " + dropped +
                      " * Limit: " + _maxBanEntries + " bans");
        }
    }

    /**
     * The bans a trim may drop, the most recently recorded first, so the flood
     * that filled the list is what goes and the bans that have been standing
     * longest are what stays. A permanent ban is never a candidate.
     *
     * @param entries the bans being trimmed
     * @param now the current time in ms, the reference for each entry's
     *            remaining duration
     * @return the droppable entries, newest first; empty if none may go
     * @since 0.9.72+
     */
    static List<Map.Entry<Hash, Entry>> droppableBans(Map<Hash, Entry> entries, long now) {
        List<Map.Entry<Hash, Entry>> droppable = new ArrayList<>(entries.size());
        try {
            for (Map.Entry<Hash, Entry> e : entries.entrySet()) {
                if (!isPermanentBan(e.getValue().expireOn - now)) {
                    droppable.add(e);
                }
            }
        } catch (IllegalStateException ise) {return Collections.emptyList();} // next time...
        droppable.sort((a, b) -> Long.compare(b.getValue().addedOn, a.getValue().addedOn));
        return droppable;
    }

    /**
     * Record a bad packet from an IP.
     * If threshold exceeded, ban the IP.
     *
     * @param ip IP address (format: "1.2.3.4" or "1.2.3.4:port")
     * @param version router version info (may be null)
     */
    public void badPacket(String ip, String version) {
        if (ip == null) return;
        if (!_enableBadPacketBan) return;
        if (_context.router().getUptime() < _startupGrace) return;
        ip = offenseIP(ip);
        long now = _context.clock().now();

        long[] data = _badPacketIPs.get(ip);
        if (data == null) {
            // First bad packet from this IP
            addFirstOffense(_badPacketIPs, ip, now);
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
                _log.warn("Banning [" + ip + "] -> " + reason);
            }

            BanLogger banLogger = new BanLogger();
            banLogger.initialize(_context);
            banLogger.logBan(ip, reason, _badPacketDuration);

            _context.blocklist().add(ip, reason);

            // Remove from tracker
            _badPacketIPs.remove(ip);
        }
    }

    /**
     * Record a bad packet from an established peer, charged to the address the
     * packet was received from. The peer's RouterInfo is looked up in the local
     * netdb for the caps and version of the full-context BanLogger output only -
     * never to choose the address to track or blocklist. Every host in a
     * RouterInfo is a host the peer itself published, so a peer that publishes
     * someone else's address first would otherwise get that stranger blocked
     * while its own address stayed clean.
     * <p>
     * Use {@link #badPacket(String, String)} when no authenticated peer hash is
     * available and only the observed address is known.
     *
     * @param hash Router hash of the peer the session is authenticated to (non-null)
     * @param version router version info (may be null, falls back to RouterInfo version)
     * @param observedIP address the packet arrived from, "1.2.3.4" or "1.2.3.4:5678" (non-null)
     */
    public void badPacket(Hash hash, String version, String observedIP) {
        if (hash == null || observedIP == null) return;
        if (!_enableBadPacketBan) return;
        if (_context.router().getUptime() < _startupGrace) return;
        RouterInfo ri = _context.netDb().lookupRouterInfoLocally(hash);
        String ip = offenseIP(observedIP);
        if (version == null && ri != null) {version = ri.getVersion();}
        long now = _context.clock().now();

        long[] data = _badPacketIPs.get(ip);
        if (data == null) {
            data = new long[] {now, 1};
            _badPacketIPs.put(ip, data);
            return;
        }

        if (now - data[0] > _offenseWindow) {
            data[0] = now;
            data[1] = 1;
        } else {
            data[1]++;
        }

        if (data[1] >= _maxOffenses) {
            String reason = "Sending bad packets";
            if (version != null) {
                reason += " (" + version + ")";
            }

            if (_log.shouldWarn()) {
                _log.warn("Banning [" + ip + "] -> " + reason);
            }

            BanLogger banLogger = new BanLogger();
            banLogger.initialize(_context);
            banLogger.logBan(hash, ip, reason, _badPacketDuration, ri);

            _context.blocklist().add(ip, reason);
            _badPacketIPs.remove(ip);
        }
    }

    /**
     * Record a port hopping attempt from an IP.
     * If threshold exceeded, ban the IP.
     *
     * @param ip IP address (format: "1.2.3.4")
     */
    public void portHopping(String ip) {
        if (ip == null) return;
        if (!_enablePortHoppingBan) return;
        if (_context.router().getUptime() < _startupGrace) return;
        ip = offenseIP(ip);
        long now = _context.clock().now();

        long[] data = _portHoppingIPs.get(ip);
        if (data == null) {
            addFirstOffense(_portHoppingIPs, ip, now);
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
                _log.warn("Banning [" + ip + "] -> " + reason);
            }

            BanLogger banLogger = new BanLogger();
            banLogger.initialize(_context);
            banLogger.logBan(ip, reason, _badPacketDuration);

            _context.blocklist().add(ip, reason);

            _portHoppingIPs.remove(ip);
        }
    }

    /**
     * Record a port hopping attempt from an established peer, charged to the
     * address the path response was received from. The peer's RouterInfo is
     * looked up in the local netdb for the full-context BanLogger output only,
     * never to choose the address to track or blocklist - see
     * {@link #badPacket(Hash, String, String)} for why a published address is
     * the wrong one to ban.
     * <p>
     * Use {@link #portHopping(String)} when no authenticated peer hash is
     * available.
     *
     * @param hash Router hash of the peer the session is authenticated to (non-null)
     * @param observedIP address the path response arrived from, "1.2.3.4" or "1.2.3.4:5678" (non-null)
     */
    public void portHopping(Hash hash, String observedIP) {
        if (hash == null || observedIP == null) return;
        if (!_enablePortHoppingBan) return;
        if (_context.router().getUptime() < _startupGrace) return;
        RouterInfo ri = _context.netDb().lookupRouterInfoLocally(hash);
        String ip = offenseIP(observedIP);
        long now = _context.clock().now();

        long[] data = _portHoppingIPs.get(ip);
        if (data == null) {
            addFirstOffense(_portHoppingIPs, ip, now);
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
                _log.warn("Banning [" + ip + "] -> " + reason);
            }

            BanLogger banLogger = new BanLogger();
            banLogger.initialize(_context);
            banLogger.logBan(hash, ip, reason, _badPacketDuration, ri);

            _context.blocklist().add(ip, reason);
            _portHoppingIPs.remove(ip);
        }
    }

    /**
     * Reduce a source address to the bare IP the offense counters and the
     * blocklist key on. Callers hold socket addresses and hand them over with
     * the port attached, and the blocklist takes only a bare address, so a
     * port-keyed source costs one tracker entry per port and is never banned.
     * An address that does not parse as a literal is returned unchanged -
     * there is nothing safe to strip from it, and the blocklist simply refuses
     * it when the ban is issued.
     *
     * @param ip an address with or without a port (non-null)
     * @return the bare address string, or the input if it has no port to strip
     */
    static String offenseIP(String ip) {
        byte[] bytes = Addresses.getIPOnly(ip);
        if (bytes == null) {
            int i = ip.lastIndexOf(':');
            if (i > 0) {bytes = Addresses.getIPOnly(ip.substring(0, i));}
        }
        return bytes != null ? Addresses.toString(bytes) : ip;
    }

    /**
     * Start an offense count for a source, if there is room to track it.
     * A spoofed-source flood presents a new source per packet, so the trackers
     * need a ceiling as well as the age sweep in {@code Cleanup}; over the
     * ceiling the packet is not tracked rather than evicting a source that is
     * already being counted.
     *
     * @param offenses the tracker to add to
     * @param ip the bare source address being counted
     * @param now current time in ms, stored as the start of the offense window
     */
    private void addFirstOffense(ConcurrentHashMap<String, long[]> offenses, String ip, long now) {
        if (offenses.size() >= MAX_OFFENSE_TRACKING) {return;}
        offenses.put(ip, new long[] {now, 1});
    }

    /**
     * Drop offense entries that can no longer lead to a ban, in all three
     * trackers. A count runs from its first offense, and a strike after the
     * offense window starts a new count instead of adding to it, so an entry
     * older than the window is dead weight - and without this sweep it is also
     * kept forever.
     *
     * @param now current time in ms
     */
    void pruneOffenses(long now) {
        pruneOffenses(now, _badPacketIPs);
        pruneOffenses(now, _portHoppingIPs);
    }

    /**
     * Drop the offense entries of one tracker that are older than the offense
     * window.
     *
     * @param now current time in ms
     * @param offenses the tracker to prune
     */
    private void pruneOffenses(long now, ConcurrentHashMap<String, long[]> offenses) {
        try {
            offenses.entrySet().removeIf(e -> now - e.getValue()[0] > _offenseWindow);
        } catch (IllegalStateException ise) { /* next time... */ }
    }

    /**
     * Number of currently banlisted routers.
     *
     * @return the size of the ban map
     */
    public int getRouterCount() {return _entries.size();}

    /**
     * Get the banlist entries.
     * For BanlistRenderer in router console.
     * Note - may contain expired entries.
     *
     * @return an unmodifiable map of router hashes to banlist entries
     */
    public Map<Hash, Entry> getEntries() {return Collections.unmodifiableMap(_entries);}

    /**
     * Ban a router with default duration.
     *
     * @param peer the router hash to ban
     * @return true if it WAS previously on the list
     */
    public boolean banlistRouter(Hash peer) {return banlistRouter(peer, null);}

    /**
     * Ban a router with default duration.
     *
     * @param peer the router hash to ban
     * @param reason the human-readable cause of the ban, stored in the entry and shown in the console
     * @return true if it WAS previously on the list
     */
    public boolean banlistRouter(Hash peer, String reason) {return banlistRouter(peer, reason, null);}

    /**
     * Ban a router with default duration.
     *
     * @param reasonCode separate code so cause can contain {0} for translation
     * @param peer the router hash to ban
     * @param reason the human-readable cause of the ban, stored in the entry and shown in the console
     * @return true if it WAS previously on the list
     */
    public boolean banlistRouter(String reasonCode, Hash peer, String reason) {
        return banlistRouter(peer, reason, reasonCode, null, false);
    }

    /**
     * Ban a router on a specific transport.
     *
     * @param peer the router hash to ban
     * @param reason the human-readable cause of the ban, stored in the entry and shown in the console
     * @param transport the transport name to restrict the ban to; null bans on every transport
     * @return true if it WAS previously on the list
     */
    public boolean banlistRouter(Hash peer, String reason, String transport) {
        return banlistRouter(peer, reason, transport, false);
    }

    /**
     * Permanently ban a router.
     *
     * @param peer the router hash to ban
     * @param reason the human-readable cause of the ban, stored in the entry and shown in the console
     * @return true if it WAS previously on the list
     */
    public boolean banlistRouterForever(Hash peer, String reason) {
        return banlistRouter(peer, reason, null, true);
    }

    /**
     * Permanently ban a router.
     *
     * @param peer the router hash to ban
     * @param reason the human-readable cause of the ban, stored in the entry and shown in the console
     * @param reasonCode separate code so cause can contain {0} for translation
     * @return true if it WAS previously on the list
     */
    public boolean banlistRouterForever(Hash peer, String reason, String reasonCode) {
        return banlistRouter(peer, reason, reasonCode, null, true);
    }

    /**
     * Ban a router with configurable duration and transport.
     *
     * @param peer the router hash to ban
     * @param reason the human-readable cause of the ban, stored in the entry and shown in the console
     * @param transport the transport name to restrict the ban to; null bans on every transport
     * @param forever if true, ban permanently
     * @return true if it WAS previously on the list
     */
    public boolean banlistRouter(Hash peer, String reason, String transport, boolean forever) {
        return banlistRouter(peer, reason, null, transport, forever);
    }

    /**
     * Ban a router with automatic duration calculation.
     *
     * @param peer the router hash to ban
     * @param reason the human-readable cause of the ban, stored in the entry and shown in the console
     * @param reasonCode separate code so cause can contain {0} for translation (may be null)
     * @param transport the transport name to restrict the ban to; null bans on every transport
     * @param forever if true, ban permanently
     * @return true if it WAS previously on the list
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
     * Ban a router with a specified expiration time.
     *
     * @param peer the router hash to ban
     * @param reason the human-readable cause of the ban, stored in the entry and shown in the console
     * @param reasonCode separate code so cause can contain {0} for translation (may be null)
     * @param transport the transport name to restrict the ban to; null bans on every transport
     * @param expireOn absolute time when the ban expires, not a duration
     * @return true if it WAS previously on the list
     * @throws IllegalArgumentException if expireOn is before the earliest valid time
     * @since 0.9.18
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
            _log.info("Banning [" + peer.toShortBase64() + "] for " + banDuration + " minutes " +
               ((transport != null) ? "on transport " + transport : "") + logReason);
        }

        Entry e = new Entry();
        e.expireOn = expireOn;
        e.addedOn = _context.clock().now();
        e.cause = reason;
        e.causeCode = reasonCode;
        e.transports = null;
        if (transport != null) {
            e.transports = new ConcurrentHashSet<>(2);
            e.transports.add(transport);
        }

        Entry old = _entries.get(peer);
        if (old != null) {
            wasAlready = true;
            // take the oldest expiration and cause, combine transports
            if (old.expireOn > e.expireOn) {
                e.expireOn = old.expireOn;
                // The standing ban is the older one, so its start time is the
                // start of the ban this peer is currently serving. Carrying the
                // new time over would make a peer look freshly banned on every
                // re-ban, which is the opposite of what the age is for.
                e.addedOn = old.addedOn;
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
        if (old == null && _entries.size() >= _maxBanEntries && !isPermanentBan(e.expireOn - e.addedOn)) {
            // The list is full. A ban a peer can buy for itself with one
            // RouterInfo does not get in ahead of the bans already here; the
            // Cleanup job trims the flood back and makes room again.
            _log.warnThrottled("banlist-full",
                               "Banlist at its limit -> Refused a new ban * Limit: " + _maxBanEntries +
                               " bans\n* Cause: " + reason);
            return false;
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
     * Remove a router from the banlist.
     *
     * @param peer the router hash to remove from banlist
     */
    public void unbanlistRouter(Hash peer) {unbanlistRouter(peer, true);}
    /**
     * Remove a peer from the banlist, optionally updating message history.
     *
     * @param peer the router hash to remove from banlist
     * @param realUnbanlist if true, update message history
     */
    private void unbanlistRouter(Hash peer, boolean realUnbanlist) {unbanlistRouter(peer, realUnbanlist, null);}
    /**
     * Remove a router from the banlist for a specific transport.
     *
     * @param peer the router hash to remove from banlist
     * @param transport the transport name whose ban to lift; null lifts the ban on every transport
     */
    public void unbanlistRouter(Hash peer, String transport) {unbanlistRouter(peer, true, transport);}

    /**
     * Remove a router from the banlist.
     *
     * @param peer the router hash to remove from banlist
     * @param realUnbanlist if true, update message history
     * @param transport the transport name whose ban to lift; null lifts the ban on every transport
     */
    private void unbanlistRouter(Hash peer, boolean realUnbanlist, String transport) {
        if (peer == null) return;
        if (_log.shouldInfo())
            _log.info("Removing expired ban from [" + peer.toShortBase64() + "]"
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
                _log.info("Removing expired ban from [" + peer.toShortBase64() + "]"
                          + (transport != null ? " / " + transport : ""));
        }
    }

    /**
     * Check if a router is banlisted.
     *
     * @param peer the router hash to check
     * @return true if the router is banlisted on any transport
     */
    public boolean isBanlisted(Hash peer) {return isBanlisted(peer, null);}

    /**
     * Check if a router is banlisted on a specific transport.
     *
     * @param peer the router hash to check
     * @param transport the transport name to check; null matches a ban on any transport
     * @return true if the router is banlisted on the specified transport
     */
    public boolean isBanlisted(Hash peer, String transport) {
        if (peer == null) {return false;}

        boolean rv = false;
        boolean unbanlist = false;

        Entry entry = _entries.get(peer);
        if (entry == null) {rv = false;}
        else if (entry.expireOn <= _context.clock().now()) {
            _entries.remove(peer);
            unbanlist = true;
            rv = false;
        } else if (entry.transports == null) {rv = true;}
        else if (transport == null) {rv = true;}
        else {rv = entry.transports.contains(transport);}

        if (unbanlist) {
            _context.messageHistory().unbanlist(peer);
            if (_log.shouldInfo()) {
                _log.info("Removing expired ban from [" + peer.toShortBase64() + "]");
            }
        }

        return rv;
    }

    /**
     * Check if a router is permanently banlisted.
     *
     * @param peer the router hash to check
     * @return true if the router is permanently banlisted
     */
    public boolean isBanlistedForever(Hash peer) {
        Entry entry = _entries.get(peer);
        return entry != null && entry.expireOn > _context.clock().now() + BANLIST_FOREVER_THRESHOLD;
    }

    /**
     * Check if a router is banlisted with a hostile duration (at least 1 hour).
     *
     * @param peer the router hash to check
     * @return true if the router is banlisted with hostile duration
     * @since 0.9.58+
     */
    public boolean isBanlistedHostile(Hash peer) {
        if (peer != null) {
            Entry entry = _entries.get(peer);
            return entry != null && entry.expireOn >= _context.clock().now() + 60*60*1000L;
        } else {return false;}
    }

    /**
     * How long the banlist has been holding a peer, in milliseconds.
     * <p>This is the discriminator between the two reasons a build request
     * arrives naming a banlisted peer, which need different fixes:
     * <ul>
     * <li>old age — the ban predates the build, so peer selection handed out
     * a peer the banlist already held, which is a selection bug;</li>
     * <li>young age — the ban landed while the build was in flight, so the
     * selection was correct and only a selection-time cooldown would stop
     * the next request repeating it.</li>
     * </ul>
     * The ban <em>duration</em> class cannot substitute: a peer permanently
     * banned mid-flight and one banned last week both report as permanent.
     *
     * @param peer the router hash to check
     * @return age of the standing ban in ms, or -1 if the peer is not
     *         currently banlisted or the entry predates this field
     * @since 0.9.71+
     */
    public long getBanAge(Hash peer) {
        if (peer == null) {return -1;}
        Entry entry = _entries.get(peer);
        if (entry == null || entry.expireOn <= _context.clock().now()) {return -1;}
        if (entry.addedOn <= 0) {return -1;}
        return Math.max(0, _context.clock().now() - entry.addedOn);
    }

    /**
     * Reason recorded when the peer was banlisted.
     * <p>Diagnostic only. A rejected tunnel build says a peer was banned but not
     * why, so a burst of rejections cannot be attributed to a cause. Without it
     * the only way to find out which subsystem is banning peers is to add
     * temporary logging to each caller.
     *
     * @param peer the router hash to check
     * @return the recorded cause code or reason, or null when the peer is not
     * currently banlisted or no cause was recorded
     * @since 0.9.71+
     */
    public String getBanCause(Hash peer) {
        if (peer == null) {return null;}
        Entry entry = _entries.get(peer);
        if (entry == null || entry.expireOn <= _context.clock().now()) {return null;}
        if (entry.causeCode != null && !entry.causeCode.isEmpty()) {return entry.causeCode;}
        return entry.cause;
    }

    /**
     * Render banlist status as HTML.
     *
     * @param out the writer the status would be rendered to, unused
     * @throws IOException never thrown by this deprecated no-op
     * @deprecated moved to router console
     */
    @Deprecated
    public void renderStatusHTML(Writer out) throws IOException {
        // intentionally empty - deprecated, moved to router console
    }

}
