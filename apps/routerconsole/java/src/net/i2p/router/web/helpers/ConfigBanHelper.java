package net.i2p.router.web.helpers;

import net.i2p.router.Banlist;
import net.i2p.router.web.HelperBase;

/**
 * Helper for ban configuration page rendering.
 * Displays current ban settings and thresholds.
 * @since 0.9.70
 */
public class ConfigBanHelper extends HelperBase {

    private static final String PROP_MAX_OFFENSES = "router.banlist.maxOffenses";
    private static final String PROP_OFFENSE_WINDOW = "router.banlist.offenseWindow";
    private static final String PROP_STARTUP_GRACE = "router.banlist.startupGrace";
    private static final String PROP_BAD_PACKET_DURATION = "router.banlist.badPacketDuration";
    private static final String PROP_ENABLE_BAD_PACKET_BAN = "router.banlist.enableBadPacketBan";
    private static final String PROP_ENABLE_CORRUPT_CONNECTION_BAN = "router.banlist.enableCorruptConnectionBan";
    private static final String PROP_ENABLE_PORT_HOPPING_BAN = "router.banlist.enablePortHoppingBan";
    private static final String PROP_ENABLE_DBSEARCH_BAN = "router.banlist.enableDbSearchBan";
    private static final String PROP_ENABLE_BLOCKLIST = "router.blocklist.enable";
    private static final String PROP_ENABLE_TOR_BLOCKLIST = "router.blocklistTor.enable";
    private static final String PROP_ENABLE_COUNTRY_BAN = "router.blocklistCountries.enable";
    private static final String PROP_ENABLE_XG_BAN = "router.banlistXG";
    private static final String PROP_ENABLE_LU_BAN = "router.banlistLU";
    private static final String PROP_ENABLE_BLOCK_MY_COUNTRY = "i2np.blockMyCountry";
    private static final String PROP_CUSTOM_CAPABILITY_BANS = "router.banlistCapabilities";
    private static final String PROP_COUNTRY_CODES = "router.blockCountries";

    public String getMaxOffenses() {
        return String.valueOf(_context.getProperty(PROP_MAX_OFFENSES, 3));
    }

    public String getOffenseWindow() {
        long ms = _context.getProperty(PROP_OFFENSE_WINDOW, 15*60*1000);
        return String.valueOf(ms / 60000);
    }

    public String getStartupGrace() {
        long ms = _context.getProperty(PROP_STARTUP_GRACE, 3*60*1000);
        return String.valueOf(ms / 60000);
    }

    public String getBadPacketDuration() {
        long ms = _context.getProperty(PROP_BAD_PACKET_DURATION, 60*60*1000);
        return String.valueOf(ms / 60000);
    }

    public String getBadPacketBanChecked() {
        boolean enabled = "true".equals(_context.getProperty(PROP_ENABLE_BAD_PACKET_BAN, "true"));
        return enabled ? "checked" : "";
    }

    public String getCorruptConnectionBanChecked() {
        boolean enabled = "true".equals(_context.getProperty(PROP_ENABLE_CORRUPT_CONNECTION_BAN, "true"));
        return enabled ? "checked" : "";
    }

    public String getPortHoppingBanChecked() {
        boolean enabled = "true".equals(_context.getProperty(PROP_ENABLE_PORT_HOPPING_BAN, "true"));
        return enabled ? "checked" : "";
    }

    public String getDbSearchBanChecked() {
        boolean enabled = "true".equals(_context.getProperty(PROP_ENABLE_DBSEARCH_BAN, "true"));
        return enabled ? "checked" : "";
    }

    public String getBlocklistChecked() {
        boolean enabled = "true".equals(_context.getProperty(PROP_ENABLE_BLOCKLIST, "true"));
        return enabled ? "checked" : "";
    }

    public String getTorBlocklistChecked() {
        boolean enabled = "true".equals(_context.getProperty(PROP_ENABLE_TOR_BLOCKLIST, "true"));
        return enabled ? "checked" : "";
    }

    public String getCountryBanChecked() {
        boolean enabled = "true".equals(_context.getProperty(PROP_ENABLE_COUNTRY_BAN, "false"));
        return enabled ? "checked" : "";
    }

    public String getXgBanChecked() {
        boolean enabled = "true".equals(_context.getProperty(PROP_ENABLE_XG_BAN, "false"));
        return enabled ? "checked" : "";
    }

    public String getLuBanChecked() {
        boolean enabled = "true".equals(_context.getProperty(PROP_ENABLE_LU_BAN, "true"));
        return enabled ? "checked" : "";
    }

    public String getBlockMyCountryChecked() {
        boolean enabled = "true".equals(_context.getProperty(PROP_ENABLE_BLOCK_MY_COUNTRY, "false"));
        return enabled ? "checked" : "";
    }

    public String getCustomCapabilityBans() {
        return _context.getProperty(PROP_CUSTOM_CAPABILITY_BANS, "");
    }

    public String getCustomCountryCodes() {
        return _context.getProperty(PROP_COUNTRY_CODES, "");
    }
}


