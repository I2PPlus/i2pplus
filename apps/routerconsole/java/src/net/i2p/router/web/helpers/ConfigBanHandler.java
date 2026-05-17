package net.i2p.router.web.helpers;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import net.i2p.router.Banlist;
import net.i2p.router.Blocklist;
import net.i2p.router.web.FormHandler;

/**
 * Handler to deal with form submissions from the ban configuration form.
 * @since 0.9.70
 */
public class ConfigBanHandler extends FormHandler {
    private String _maxOffenses;
    private String _offenseWindow;
    private String _startupGrace;
    private String _badPacketDuration;
    private boolean _enableBadPacketBan;
    private boolean _enableCorruptConnectionBan;
    private boolean _enablePortHoppingBan;
    private boolean _enableDbSearchBan;
    private boolean _enableBlocklist;
    private boolean _enableTorBlocklist;
    private boolean _enableCountryBan;
    private boolean _enableXgBan;
    private boolean _enableLuBan;
    private boolean _enableBlockMyCountry;
    private String _customCapabilityBans;
    private String _customCountryCodes;

    private static final String PROP_MAX_OFFENSES = "router.banlist.maxOffenses";
    private static final String PROP_OFFENSE_WINDOW = "router.banlist.offenseWindow";
    private static final String PROP_STARTUP_GRACE = "router.banlist.startupGrace";
    private static final String PROP_BAD_PACKET_DURATION = "router.banlist.badPacketDuration";
    private static final String PROP_ENABLE_BAD_PACKET_BAN = "router.banlist.enableBadPacketBan";
    private static final String PROP_ENABLE_CORRUPT_CONNECTION_BAN = "router.banlist.enableCorruptConnectionBan";
    private static final String PROP_ENABLE_PORT_HOPPING_BAN = "router.banlist.enablePortHoppingBan";
    private static final String PROP_ENABLE_DBSEARCH_BAN = "router.banlist.enableDbSearchBan";
    private static final Pattern COMMA_SPLIT = Pattern.compile("[,\\s]+");
    private static final Pattern COUNTRY_CODE = Pattern.compile("[a-z][a-z]");
    private static final String PROP_ENABLE_BLOCKLIST = "router.blocklist.enable";
    private static final String PROP_ENABLE_TOR_BLOCKLIST = "router.blocklistTor.enable";
    private static final String PROP_ENABLE_COUNTRY_BAN = "router.blocklistCountries.enable";
    private static final String PROP_ENABLE_XG_BAN = "router.banlistXG";
    private static final String PROP_ENABLE_LU_BAN = "router.banlistLU";
    private static final String PROP_ENABLE_BLOCK_MY_COUNTRY = "i2np.blockMyCountry";
    private static final String PROP_CUSTOM_CAPABILITY_BANS = "router.banlistCapabilities";
    private static final String PROP_COUNTRY_CODES = "router.blockCountries";

    @Override
    protected void processForm() {
        if (_action != null && _action.equals("blah")) {
            saveChanges();
        } else if (_action != null && _action.equals("clearBans")) {
            clearAllBans();
        } else if (_action != null && _action.equals("resetDefaults")) {
            resetToDefaults();
        }
    }

    private void clearAllBans() {
        _context.banlist().clearSessionBans();
        _context.blocklist().clearAll();
        addFormNotice(_t("All session bans and blocklists cleared"), true);
    }

    private void resetToDefaults() {
        Map<String, String> defaults = new HashMap<String, String>();
        defaults.put(PROP_MAX_OFFENSES, "3");
        defaults.put(PROP_OFFENSE_WINDOW, String.valueOf(15*60*1000));
        defaults.put(PROP_STARTUP_GRACE, String.valueOf(3*60*1000));
        defaults.put(PROP_BAD_PACKET_DURATION, String.valueOf(60*60*1000));
        defaults.put(PROP_ENABLE_BAD_PACKET_BAN, "true");
        defaults.put(PROP_ENABLE_CORRUPT_CONNECTION_BAN, "true");
        defaults.put(PROP_ENABLE_PORT_HOPPING_BAN, "true");
        defaults.put(PROP_ENABLE_DBSEARCH_BAN, "true");
        defaults.put(PROP_ENABLE_BLOCKLIST, "true");
        defaults.put(PROP_ENABLE_TOR_BLOCKLIST, "true");
        defaults.put(PROP_ENABLE_COUNTRY_BAN, "false");
        defaults.put(PROP_ENABLE_XG_BAN, "false");
        defaults.put(PROP_ENABLE_LU_BAN, "true");
        defaults.put(PROP_ENABLE_BLOCK_MY_COUNTRY, "false");
        defaults.put(PROP_CUSTOM_CAPABILITY_BANS, "");
        defaults.put(PROP_COUNTRY_CODES, "");
        _context.router().saveConfig(defaults, null);
        _context.banlist().reloadConfig();
        _context.blocklist().reloadConfig();
        addFormNotice(_t("Ban settings reset to defaults"), true);
    }

    public void setMaxOffenses(String val) { _maxOffenses = val; }
    public void setOffenseWindow(String val) { _offenseWindow = val; }
    public void setStartupGrace(String val) { _startupGrace = val; }
    public void setBadPacketDuration(String val) { _badPacketDuration = val; }
    public void setEnableBadPacketBan(String val) { _enableBadPacketBan = true; }
    public void setEnableCorruptConnectionBan(String val) { _enableCorruptConnectionBan = true; }
    public void setEnablePortHoppingBan(String val) { _enablePortHoppingBan = true; }
    public void setEnableDbSearchBan(String val) { _enableDbSearchBan = true; }
    public void setEnableBlocklist(String val) { _enableBlocklist = true; }
    public void setEnableTorBlocklist(String val) { _enableTorBlocklist = true; }
    public void setEnableCountryBan(String val) { _enableCountryBan = true; }
    public void setEnableXgBan(String val) { _enableXgBan = true; }
    public void setEnableLuBan(String val) { _enableLuBan = true; }
    public void setEnableBlockMyCountry(String val) { _enableBlockMyCountry = true; }
    public void setCustomCapabilityBans(String val) { _customCapabilityBans = val != null ? val.trim() : ""; }
    public void setCustomCountryCodes(String val) { _customCountryCodes = val != null ? val.trim().toLowerCase() : ""; }

    /**
     * Save ban configuration settings.
     */
    private void saveChanges() {
        Map<String, String> changes = new HashMap<String, String>();

        if (_maxOffenses != null && _maxOffenses.length() > 0) {
            try {
                int val = Integer.parseInt(_maxOffenses);
                if (val >= 1 && val <= 100) {
                    changes.put(PROP_MAX_OFFENSES, String.valueOf(val));
                }
            } catch (NumberFormatException e) {}
        }

        if (_offenseWindow != null && _offenseWindow.length() > 0) {
            try {
                int val = Integer.parseInt(_offenseWindow);
                if (val >= 1 && val <= 1440) {
                    changes.put(PROP_OFFENSE_WINDOW, String.valueOf(val * 60000));
                }
            } catch (NumberFormatException e) {}
        }

        if (_startupGrace != null && _startupGrace.length() > 0) {
            try {
                int val = Integer.parseInt(_startupGrace);
                if (val >= 0 && val <= 60) {
                    changes.put(PROP_STARTUP_GRACE, String.valueOf(val * 60000));
                }
            } catch (NumberFormatException e) {}
        }

        if (_badPacketDuration != null && _badPacketDuration.length() > 0) {
            try {
                int val = Integer.parseInt(_badPacketDuration);
                if (val >= 1 && val <= 10080) {
                    changes.put(PROP_BAD_PACKET_DURATION, String.valueOf(val * 60000));
                }
            } catch (NumberFormatException e) {}
        }

        changes.put(PROP_ENABLE_BAD_PACKET_BAN, Boolean.toString(_enableBadPacketBan));
        changes.put(PROP_ENABLE_CORRUPT_CONNECTION_BAN, Boolean.toString(_enableCorruptConnectionBan));
        changes.put(PROP_ENABLE_PORT_HOPPING_BAN, Boolean.toString(_enablePortHoppingBan));
        changes.put(PROP_ENABLE_DBSEARCH_BAN, Boolean.toString(_enableDbSearchBan));

        boolean blocklistWasEnabled = "true".equals(_context.getProperty(PROP_ENABLE_BLOCKLIST, "true"));
        boolean torBlocklistWasEnabled = "true".equals(_context.getProperty(PROP_ENABLE_TOR_BLOCKLIST, "true"));
        changes.put(PROP_ENABLE_BLOCKLIST, Boolean.toString(_enableBlocklist));
        changes.put(PROP_ENABLE_TOR_BLOCKLIST, Boolean.toString(_enableTorBlocklist));
        changes.put(PROP_ENABLE_COUNTRY_BAN, Boolean.toString(_enableCountryBan));
        changes.put(PROP_ENABLE_XG_BAN, Boolean.toString(_enableXgBan));
        changes.put(PROP_ENABLE_LU_BAN, Boolean.toString(_enableLuBan));
        changes.put(PROP_ENABLE_BLOCK_MY_COUNTRY, Boolean.toString(_enableBlockMyCountry));

        if ((blocklistWasEnabled && !_enableBlocklist) || (torBlocklistWasEnabled && !_enableTorBlocklist)) {
            _context.blocklist().clearAll();
        }

        String validatedCaps = validateCapabilityBans(_customCapabilityBans);
        changes.put(PROP_CUSTOM_CAPABILITY_BANS, validatedCaps);

        String validatedCountries = validateCountryCodes(_customCountryCodes);
        changes.put(PROP_COUNTRY_CODES, validatedCountries);

        // Check if retroactive NetDb purge is needed (LU/XG enabled or custom caps added)
        boolean xgWasEnabled = "true".equals(_context.getProperty(PROP_ENABLE_XG_BAN, "false"));
        boolean luWasEnabled = "true".equals(_context.getProperty(PROP_ENABLE_LU_BAN, "true"));
        String existingCaps = _context.getProperty(PROP_CUSTOM_CAPABILITY_BANS, "");
        boolean capsWereEmpty = existingCaps.isEmpty();

        boolean purgeNeeded = (_enableXgBan && !xgWasEnabled) ||
                              (_enableLuBan && !luWasEnabled) ||
                              (!validatedCaps.isEmpty() && capsWereEmpty);

        if (!changes.isEmpty()) {
            _context.router().saveConfig(changes, null);
            _context.banlist().reloadConfig();
            _context.blocklist().reloadConfig();
            if (purgeNeeded) {
                _context.netDb().purgeMatchingRouters();
            }
            addFormNotice(_t("Ban configuration updated"), true);
        }
    }

    /**
     * Validate custom capability ban pattern.
     * Only allows valid router capability letters: K,L,M,N,O,P,X,f,D,E,G,U,R
     * Separates multiple patterns with comma or space.
     * Sorts characters alphabetically within each pattern.
     */
    private String validateCapabilityBans(String input) {
        if (input == null || input.isEmpty()) return "";
        String validChars = "KLMNOPXFGDEUR";
        StringBuilder result = new StringBuilder();
        String[] patterns = COMMA_SPLIT.split(input);
        for (String pattern : patterns) {
            pattern = pattern.trim().toUpperCase();
            if (pattern.isEmpty()) continue;
            // Sort characters alphabetically
            char[] chars = pattern.toCharArray();
            java.util.Arrays.sort(chars);
            String sortedPattern = new String(chars);
            // Verify all characters are valid
            boolean valid = true;
            for (int i = 0; i < sortedPattern.length(); i++) {
                char c = sortedPattern.charAt(i);
                if (validChars.indexOf(c) < 0) {
                    valid = false;
                    break;
                }
            }
            if (valid) {
                if (result.length() > 0) result.append(",");
                result.append(sortedPattern);
            }
        }
        return result.toString();
    }

    /**
     * Validate country codes - only allow valid 2-letter codes.
     */
    private String validateCountryCodes(String input) {
        if (input == null || input.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        String[] codes = COMMA_SPLIT.split(input);
        for (String code : codes) {
            code = code.trim().toLowerCase();
            if (code.length() == 2 && COUNTRY_CODE.matcher(code).matches()) {
                if (result.length() > 0) result.append(",");
                result.append(code);
            }
        }
        return result.toString();
    }
}


