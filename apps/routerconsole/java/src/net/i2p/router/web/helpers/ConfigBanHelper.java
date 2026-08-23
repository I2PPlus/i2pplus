package net.i2p.router.web.helpers;

import net.i2p.router.web.HelperBase;

/**
 * Helper for ban configuration page rendering.
 * Displays current ban settings and thresholds.
 * @since 0.9.70
 */
public class ConfigBanHelper extends HelperBase {
    /**
     * Construct a new ConfigBanHelper.
     */
    public ConfigBanHelper() {}

    public static final String PROP_MAX_OFFENSES = "router.banlist.maxOffenses";
    public static final String PROP_OFFENSE_WINDOW = "router.banlist.offenseWindow";
    public static final String PROP_STARTUP_GRACE = "router.banlist.startupGrace";
    public static final String PROP_BAD_PACKET_DURATION = "router.banlist.badPacketDuration";
    public static final String PROP_ENABLE_BAD_PACKET_BAN = "router.banlist.enableBadPacketBan";
    public static final String PROP_ENABLE_CORRUPT_CONNECTION_BAN = "router.banlist.enableCorruptConnectionBan";
    public static final String PROP_ENABLE_PORT_HOPPING_BAN = "router.banlist.enablePortHoppingBan";
    public static final String PROP_ENABLE_BLOCKLIST = "router.blocklist.enable";
    public static final String PROP_ENABLE_TOR_BLOCKLIST = "router.blocklistTor.enable";
    public static final String PROP_ENABLE_COUNTRY_BAN = "router.blocklistCountries.enable";
    public static final String PROP_ENABLE_XG_BAN = "router.banlistXG";
    public static final String PROP_ENABLE_LU_BAN = "router.banlistLU";
    public static final String PROP_ENABLE_BLOCK_MY_COUNTRY = "i2np.blockMyCountry";
    public static final String PROP_CUSTOM_CAPABILITY_BANS = "router.banlistCapabilities";
    public static final String PROP_COUNTRY_CODES = "router.blockCountries";
    public static final String PROP_ENABLE_UNRESPONSIVE_FLOODFILL_BAN = "router.banlist.enableUnresponsiveFloodfillBan";
    public static final String PROP_ENABLE_NO_VERSION_BAN = "router.banlist.enableNoVersionBan";
    public static final String PROP_ENABLE_EXCESSIVE_TUNNEL_REQUESTS_BAN = "router.banlist.enableExcessiveTunnelRequestsBan";
    /** When false, peers seen only as transit next hops are exempt from policy bans */
    public static final String PROP_BAN_NEXT_HOP = "router.banlist.banNextHop";

    /**
     * Get the maximum number of offenses before a peer is banned.
     *
     * @return the max offenses setting value
     */
    public String getMaxOffenses() {
        return String.valueOf(_context.getProperty(PROP_MAX_OFFENSES, 3));
    }

    /**
     * Get the offense window in minutes.
     *
     * @return the offense window in minutes
     */
    public String getOffenseWindow() {
        long ms = _context.getProperty(PROP_OFFENSE_WINDOW, 15*60*1000);
        return String.valueOf(ms / 60000);
    }

    /**
     * Get the startup grace period in minutes.
     *
     * @return the startup grace in minutes
     */
    public String getStartupGrace() {
        long ms = _context.getProperty(PROP_STARTUP_GRACE, 3*60*1000);
        return String.valueOf(ms / 60000);
    }

    /**
     * Get the bad packet ban duration in minutes.
     *
     * @return the bad packet ban duration in minutes
     */
    public String getBadPacketDuration() {
        long ms = _context.getProperty(PROP_BAD_PACKET_DURATION, 60*60*1000);
        return String.valueOf(ms / 60000);
    }

    /**
     * Get the checked status of the bad packet ban option.
     *
     * @return "checked" if enabled, empty string otherwise
     */
    public String getBadPacketBanChecked() {
        boolean enabled = "true".equals(_context.getProperty(PROP_ENABLE_BAD_PACKET_BAN, "true"));
        return enabled ? "checked" : "";
    }

    /**
     * Get the checked status of the corrupt connection ban option.
     *
     * @return "checked" if enabled, empty string otherwise
     */
    public String getCorruptConnectionBanChecked() {
        boolean enabled = "true".equals(_context.getProperty(PROP_ENABLE_CORRUPT_CONNECTION_BAN, "true"));
        return enabled ? "checked" : "";
    }

    /**
     * Get the checked status of the port hopping ban option.
     *
     * @return "checked" if enabled, empty string otherwise
     */
    public String getPortHoppingBanChecked() {
        boolean enabled = "true".equals(_context.getProperty(PROP_ENABLE_PORT_HOPPING_BAN, "true"));
        return enabled ? "checked" : "";
    }

    /**
     * Get the checked status of the blocklist option.
     *
     * @return "checked" if enabled, empty string otherwise
     */
    public String getBlocklistChecked() {
        boolean enabled = "true".equals(_context.getProperty(PROP_ENABLE_BLOCKLIST, "true"));
        return enabled ? "checked" : "";
    }

    /**
     * Get the checked status of the Tor blocklist option.
     *
     * @return "checked" if enabled, empty string otherwise
     */
    public String getTorBlocklistChecked() {
        boolean enabled = "true".equals(_context.getProperty(PROP_ENABLE_TOR_BLOCKLIST, "true"));
        return enabled ? "checked" : "";
    }

    /**
     * Get the checked status of the country ban option.
     *
     * @return "checked" if enabled, empty string otherwise
     */
    public String getCountryBanChecked() {
        boolean enabled = "true".equals(_context.getProperty(PROP_ENABLE_COUNTRY_BAN, "false"));
        return enabled ? "checked" : "";
    }

    /**
     * Get the checked status of the XG ban option.
     *
     * @return "checked" if enabled, empty string otherwise
     */
    public String getXgBanChecked() {
        boolean enabled = "true".equals(_context.getProperty(PROP_ENABLE_XG_BAN, "false"));
        return enabled ? "checked" : "";
    }

    /**
     * Get the checked status of the LU ban option.
     *
     * @return "checked" if enabled, empty string otherwise
     */
    public String getLuBanChecked() {
        boolean enabled = "true".equals(_context.getProperty(PROP_ENABLE_LU_BAN, "true"));
        return enabled ? "checked" : "";
    }

    /**
     * Get the checked status of the block my country option.
     *
     * @return "checked" if enabled, empty string otherwise
     */
    public String getBlockMyCountryChecked() {
        boolean enabled = "true".equals(_context.getProperty(PROP_ENABLE_BLOCK_MY_COUNTRY, "false"));
        return enabled ? "checked" : "";
    }

    /**
     * Get the custom capability ban patterns.
     *
     * @return the custom capability bans string
     */
    public String getCustomCapabilityBans() {
        return _context.getProperty(PROP_CUSTOM_CAPABILITY_BANS, "");
    }

    /**
     * Get the custom country codes for ban filtering.
     *
     * @return the custom country codes string
     */
    public String getCustomCountryCodes() {
        return _context.getProperty(PROP_COUNTRY_CODES, "");
    }

    /**
     * Get the checked status of the unresponsive floodfill ban option.
     *
     * @return "checked" if enabled, empty string otherwise
     */
    public String getUnresponsiveFloodfillBanChecked() {
        boolean enabled = "true".equals(_context.getProperty(PROP_ENABLE_UNRESPONSIVE_FLOODFILL_BAN, "true"));
        return enabled ? "checked" : "";
    }

    /**
     * Get the checked status of the no version ban option.
     *
     * @return "checked" if enabled, empty string otherwise
     */
    public String getNoVersionBanChecked() {
        boolean enabled = "true".equals(_context.getProperty(PROP_ENABLE_NO_VERSION_BAN, "true"));
        return enabled ? "checked" : "";
    }

    /**
     * Get the checked status of the excessive tunnel requests ban option.
     *
     * @return "checked" if enabled, empty string otherwise
     */
    public String getExcessiveTunnelRequestsBanChecked() {
        boolean enabled = "true".equals(_context.getProperty(PROP_ENABLE_EXCESSIVE_TUNNEL_REQUESTS_BAN, "true"));
        return enabled ? "checked" : "";
    }

    /**
     * Get the checked status of the next-hop ban option.
     *
     * When enabled (default), peers resolved from the network that we are
     * not directly connected to - transit next hops as well as client
     * tunnel endpoints - are subject to policy bans like any other peer.
     * When disabled, such peers are exempt from policy bans unless we are
     * directly connected to them.
     *
     * @return "checked" if enabled, empty string otherwise
     */
    public String getBanNextHopChecked() {
        boolean enabled = "true".equals(_context.getProperty(PROP_BAN_NEXT_HOP, "true"));
        return enabled ? "checked" : "";
    }
}

