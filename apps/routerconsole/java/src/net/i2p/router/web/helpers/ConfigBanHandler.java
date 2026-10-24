package net.i2p.router.web.helpers;

import static net.i2p.router.web.helpers.ConfigBanHelper.*;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import net.i2p.router.web.FormHandler;

/**
 * Handler to deal with form submissions from the ban configuration form.
 * @since 0.9.70
 */
public class ConfigBanHandler extends FormHandler {

    /**
     * Constructs the handler.
     */
    public ConfigBanHandler() {}

    /** Form value for the maximum offenses before a ban. */
    private String _maxOffenses;
    /** Form value for the offense window, in minutes. */
    private String _offenseWindow;
    /** Form value for the startup grace period, in minutes. */
    private String _startupGrace;
    /** Form value for the bad packet ban duration, in minutes. */
    private String _badPacketDuration;
    /** Form flag for the bad packet ban. */
    private boolean _enableBadPacketBan;
    /** Form flag for the corrupt connection ban. */
    private boolean _enableCorruptConnectionBan;
    /** Form flag for the port hopping ban. */
    private boolean _enablePortHoppingBan;
    /** Form flag for the blocklist. */
    private boolean _enableBlocklist;
    /** Form flag for the Tor blocklist. */
    private boolean _enableTorBlocklist;
    /** Form flag for the country ban. */
    private boolean _enableCountryBan;
    /** Form flag for the Xfer-Gosset ban. */
    private boolean _enableXgBan;
    /** Form flag for the LU ban. */
    private boolean _enableLuBan;
    /** Form flag for the introducer ban. */
    private boolean _enableIntroducerBan;
    /** Form flag for the invalid version ban. */
    private boolean _enableInvalidVersionBan;
    /** Form flag for the publish date ban. */
    private boolean _enablePubDateBan;
    /** Form flag for the restricted usage caps ban. */
    private boolean _enableRUCapsBan;
    /** Form flag for blocking our own country. */
    private boolean _enableBlockMyCountry;
    /** Form flag for the unresponsive floodfill ban. */
    private boolean _enableUnresponsiveFloodfillBan;
    /** Form flag for the missing version ban. */
    private boolean _enableNoVersionBan;
    /** Form flag for the excessive tunnel requests ban. */
    private boolean _enableExcessiveTunnelRequestsBan;
    /** Form flag for banning the next hop as well. */
    private boolean _banNextHop;
    /** Form value for the custom capability bans. */
    private String _customCapabilityBans;
    /** Form value for the custom country codes. */
    private String _customCountryCodes;

    /** Splits the form's comma-separated lists. */
    private static final Pattern COMMA_SPLIT = Pattern.compile("[,\\s]+");
    /** Matches one two-letter country code. */
    private static final Pattern COUNTRY_CODE = Pattern.compile("[a-z][a-z]");

    @Override
    protected void processForm() {
        String action = resolveEffectiveAction(_action, _settings);
        if (action == null) {return;}
        if (action.equals("clearBans")) {
            clearAllBans();
        } else if (action.equals("resetDefaults")) {
            resetToDefaults();
        } else {
            // Save changes: default action when no other operation matches.
            // Covers the "Save changes" button and Enter-key submission from
            // any form field (hidden input sends action=blah).
            saveChanges();
        }
    }

    /** Turns every ban setting off. */
    private void clearAllBans() {
        _context.banlist().clearSessionBans();
        _context.blocklist().clearAll();
        addFormNotice(_t("All session bans and blocklists cleared"), true);
    }

    /** Sets every ban setting back to its default. */
    private void resetToDefaults() {
        Map<String, String> defaults = new HashMap<>();
        defaults.put(PROP_MAX_OFFENSES, "3");
        defaults.put(PROP_OFFENSE_WINDOW, String.valueOf(15*60*1000));
        defaults.put(PROP_STARTUP_GRACE, String.valueOf(3*60*1000));
        defaults.put(PROP_BAD_PACKET_DURATION, String.valueOf(60*60*1000));
        defaults.put(PROP_ENABLE_BAD_PACKET_BAN, "false");
        defaults.put(PROP_ENABLE_CORRUPT_CONNECTION_BAN, "false");
        defaults.put(PROP_ENABLE_PORT_HOPPING_BAN, "false");
        defaults.put(PROP_ENABLE_BLOCKLIST, "true");
        defaults.put(PROP_ENABLE_TOR_BLOCKLIST, "true");
        defaults.put(PROP_ENABLE_COUNTRY_BAN, "false");
        defaults.put(PROP_ENABLE_XG_BAN, "false");
        defaults.put(PROP_ENABLE_LU_BAN, "false");
        defaults.put(PROP_ENABLE_INTRODUCER_BAN, "true");
        defaults.put(PROP_ENABLE_INVALID_VERSION_BAN, "true");
        defaults.put(PROP_ENABLE_PUB_DATE_BAN, "true");
        defaults.put(PROP_ENABLE_RU_CAPS_BAN, "true");
        defaults.put(PROP_ENABLE_BLOCK_MY_COUNTRY, "false");
        defaults.put(PROP_ENABLE_UNRESPONSIVE_FLOODFILL_BAN, "false");
        defaults.put(PROP_ENABLE_NO_VERSION_BAN, "true");
        defaults.put(PROP_ENABLE_EXCESSIVE_TUNNEL_REQUESTS_BAN, "false");
        defaults.put(PROP_BAN_NEXT_HOP, "true");
        defaults.put(PROP_CUSTOM_CAPABILITY_BANS, "");
        defaults.put(PROP_COUNTRY_CODES, "");
        _context.router().saveConfig(defaults, null);
        _context.banlist().reloadConfig();
        _context.blocklist().reloadConfig();
        addFormNotice(_t("Ban settings reset to defaults"), true);
    }

    /**
     * Set the maximum offenses.
     * @param val the maximum number of offenses
     */
    public void setMaxOffenses(String val) { _maxOffenses = val; }
    /**
     * Set the offense window.
     * @param val the offense window in minutes
     */
    public void setOffenseWindow(String val) { _offenseWindow = val; }
    /**
     * Set the startup grace period.
     * @param val the grace period in minutes
     */
    public void setStartupGrace(String val) { _startupGrace = val; }
    /**
     * Set the bad packet ban duration.
     * @param val the duration in minutes
     */
    public void setBadPacketDuration(String val) { _badPacketDuration = val; }
    /**
     * Enable or disable bad packet banning.
     * @param val the value
     */
    public void setEnableBadPacketBan(String val) { _enableBadPacketBan = true; }
    /**
     * Enable or disable corrupt connection banning.
     * @param val the value
     */
    public void setEnableCorruptConnectionBan(String val) { _enableCorruptConnectionBan = true; }
    /**
     * Enable or disable port hopping banning.
     * @param val the value
     */
    public void setEnablePortHoppingBan(String val) { _enablePortHoppingBan = true; }
    /**
     * Enable or disable the blocklist.
     * @param val the value
     */
    public void setEnableBlocklist(String val) { _enableBlocklist = true; }
    /**
     * Enable or disable the Tor blocklist.
     * @param val the value
     */
    public void setEnableTorBlocklist(String val) { _enableTorBlocklist = true; }
    /**
     * Enable or disable country banning.
     * @param val the value
     */
    public void setEnableCountryBan(String val) { _enableCountryBan = true; }
    /**
     * Enable or disable XG banning.
     * @param val the value
     */
    public void setEnableXgBan(String val) { _enableXgBan = true; }
    /**
     * Enable or disable LU banning.
     * @param val the value
     */
    public void setEnableLuBan(String val) { _enableLuBan = true; }
    /**
     * Enable or disable banning of routers advertising more introducers on one
     * address than the SSU2 spec allows.
     * @param val the value
     */
    public void setEnableIntroducerBan(String val) { _enableIntroducerBan = true; }
    /**
     * Enable or disable banning RouterInfos that publish a version above every
     * released version, which only a hand-built RouterInfo can do.
     * @param val the value
     */
    public void setEnableInvalidVersionBan(String val) { _enableInvalidVersionBan = true; }
    /**
     * Enable or disable banning RouterInfos with an invalid publication date.
     * @param val the value
     */
    public void setEnablePubDateBan(String val) { _enablePubDateBan = true; }
    /**
     * Enable or disable banning routers that publish both the reachable and the
     * unreachable flag at once.
     * @param val the value
     */
    public void setEnableRUCapsBan(String val) { _enableRUCapsBan = true; }
    /**
     * Enable or disable blocking of the router's own country.
     * @param val the value
     */
    public void setEnableBlockMyCountry(String val) { _enableBlockMyCountry = true; }
    /**
     * Enable or disable unresponsive floodfill banning.
     * @param val the value
     */
    public void setEnableUnresponsiveFloodfillBan(String val) { _enableUnresponsiveFloodfillBan = true; }
    /**
     * Enable or disable no-version banning.
     * @param val the value
     */
    public void setEnableNoVersionBan(String val) { _enableNoVersionBan = true; }
    /**
     * Enable or disable excessive tunnel requests banning.
     * @param val the value
     */
    public void setEnableExcessiveTunnelRequestsBan(String val) { _enableExcessiveTunnelRequestsBan = true; }
    /**
     * Enable or disable banning of next-hop peers.
     * @param val the value
     */
    public void setBanNextHop(String val) { _banNextHop = true; }
    /**
     * Set custom capability bans.
     * @param val the capability patterns
     */
    public void setCustomCapabilityBans(String val) { _customCapabilityBans = val != null ? val.trim() : ""; }
    /**
     * Set custom country codes.
     * @param val the country codes
     */
    public void setCustomCountryCodes(String val) { _customCountryCodes = val != null ? val.trim().toLowerCase() : ""; }

    /**
     * Save ban configuration settings.
     */
    private void saveChanges() {
        Map<String, String> changes = new HashMap<>();

        if (_maxOffenses != null && !_maxOffenses.isEmpty()) {
            try {
                int val = Integer.parseInt(_maxOffenses);
                if (val >= 1 && val <= 100) {
                    changes.put(PROP_MAX_OFFENSES, String.valueOf(val));
                }
            } catch (NumberFormatException e) { /* ignored */ }
        }

        if (_offenseWindow != null && !_offenseWindow.isEmpty()) {
            try {
                int val = Integer.parseInt(_offenseWindow);
                if (val >= 1 && val <= 1440) {
                    changes.put(PROP_OFFENSE_WINDOW, String.valueOf(val * 60000));
                }
            } catch (NumberFormatException e) { /* ignored */ }
        }

        if (_startupGrace != null && !_startupGrace.isEmpty()) {
            try {
                int val = Integer.parseInt(_startupGrace);
                if (val >= 0 && val <= 60) {
                    changes.put(PROP_STARTUP_GRACE, String.valueOf(val * 60000));
                }
            } catch (NumberFormatException e) { /* ignored */ }
        }

        if (_badPacketDuration != null && !_badPacketDuration.isEmpty()) {
            try {
                int val = Integer.parseInt(_badPacketDuration);
                if (val >= 1 && val <= 10080) {
                    changes.put(PROP_BAD_PACKET_DURATION, String.valueOf(val * 60000));
                }
            } catch (NumberFormatException e) { /* ignored */ }
        }

        changes.put(PROP_ENABLE_BAD_PACKET_BAN, Boolean.toString(_enableBadPacketBan));
        changes.put(PROP_ENABLE_CORRUPT_CONNECTION_BAN, Boolean.toString(_enableCorruptConnectionBan));
        changes.put(PROP_ENABLE_PORT_HOPPING_BAN, Boolean.toString(_enablePortHoppingBan));

        boolean blocklistWasEnabled = "true".equals(_context.getProperty(PROP_ENABLE_BLOCKLIST, "true"));
        boolean torBlocklistWasEnabled = "true".equals(_context.getProperty(PROP_ENABLE_TOR_BLOCKLIST, "true"));
        changes.put(PROP_ENABLE_BLOCKLIST, Boolean.toString(_enableBlocklist));
        changes.put(PROP_ENABLE_TOR_BLOCKLIST, Boolean.toString(_enableTorBlocklist));
        changes.put(PROP_ENABLE_COUNTRY_BAN, Boolean.toString(_enableCountryBan));
        changes.put(PROP_ENABLE_XG_BAN, Boolean.toString(_enableXgBan));
        changes.put(PROP_ENABLE_LU_BAN, Boolean.toString(_enableLuBan));
        changes.put(PROP_ENABLE_INTRODUCER_BAN, Boolean.toString(_enableIntroducerBan));
        changes.put(PROP_ENABLE_INVALID_VERSION_BAN, Boolean.toString(_enableInvalidVersionBan));
        changes.put(PROP_ENABLE_PUB_DATE_BAN, Boolean.toString(_enablePubDateBan));
        changes.put(PROP_ENABLE_RU_CAPS_BAN, Boolean.toString(_enableRUCapsBan));
        changes.put(PROP_ENABLE_BLOCK_MY_COUNTRY, Boolean.toString(_enableBlockMyCountry));
        changes.put(PROP_ENABLE_UNRESPONSIVE_FLOODFILL_BAN, Boolean.toString(_enableUnresponsiveFloodfillBan));
        changes.put(PROP_ENABLE_NO_VERSION_BAN, Boolean.toString(_enableNoVersionBan));
        changes.put(PROP_ENABLE_EXCESSIVE_TUNNEL_REQUESTS_BAN, Boolean.toString(_enableExcessiveTunnelRequestsBan));
        changes.put(PROP_BAN_NEXT_HOP, Boolean.toString(_banNextHop));

        if ((blocklistWasEnabled && !_enableBlocklist) || (torBlocklistWasEnabled && !_enableTorBlocklist)) {
            _context.blocklist().clearAll();
        }

        String validatedCaps = validateCapabilityBans(_customCapabilityBans);
        changes.put(PROP_CUSTOM_CAPABILITY_BANS, validatedCaps);

        String validatedCountries = validateCountryCodes(_customCountryCodes);
        changes.put(PROP_COUNTRY_CODES, validatedCountries);

        // Check if retroactive NetDb purge is needed (LU/XG enabled or custom caps changed)
        boolean xgWasEnabled = "true".equals(_context.getProperty(PROP_ENABLE_XG_BAN, "false"));
        boolean luWasEnabled = "true".equals(_context.getProperty(PROP_ENABLE_LU_BAN, "true"));
        boolean introducerWasEnabled = "true".equals(_context.getProperty(PROP_ENABLE_INTRODUCER_BAN, "true"));
        boolean invalidVersionWasEnabled = "true".equals(_context.getProperty(PROP_ENABLE_INVALID_VERSION_BAN, "true"));
        boolean pubDateWasEnabled = "true".equals(_context.getProperty(PROP_ENABLE_PUB_DATE_BAN, "true"));
        boolean ruCapsWasEnabled = "true".equals(_context.getProperty(PROP_ENABLE_RU_CAPS_BAN, "true"));
        String existingCaps = _context.getProperty(PROP_CUSTOM_CAPABILITY_BANS, "");
        // Re-evaluate the existing netdb whenever the pattern list actually
        // changes - not only when it goes from empty to non-empty. Without
        // this, edits to an already-populated list (e.g. adding "Lf" next to
        // "G!f") never take effect for routers we already know.
        boolean capsChanged = !validatedCaps.equals(existingCaps);

        boolean purgeNeeded = (_enableXgBan && !xgWasEnabled) ||
                              (_enableLuBan && !luWasEnabled) ||
                              (_enableIntroducerBan && !introducerWasEnabled) ||
                              (_enableInvalidVersionBan && !invalidVersionWasEnabled) ||
                              (_enablePubDateBan && !pubDateWasEnabled) ||
                              (_enableRUCapsBan && !ruCapsWasEnabled) ||
                              (!validatedCaps.isEmpty() && capsChanged);

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
     * Validate custom capability ban patterns.
     * <p>
     * Capability letters are case-sensitive: lowercase 'f' is floodfill,
     * uppercase letters are the remaining tiers and flags. Input is
     * validated per character and passed through as entered - no sorting,
     * no case folding.
     * <p>
     * Separates multiple patterns with comma or space. A pattern may carry
     * an exclusion group after '!': "G!f" bans G routers unless floodfill;
     * exclusions require at least one required character.
     *
     * @param input the patterns as entered, comma or space separated, may be null or empty
     * @return the valid patterns joined with commas, invalid patterns dropped
     */
    private String validateCapabilityBans(String input) {
        if (input == null || input.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        String[] patterns = COMMA_SPLIT.split(input);
        for (String pattern : patterns) {
            pattern = pattern.trim();
            if (pattern.isEmpty()) continue;
            int bang = pattern.indexOf('!');
            String reqPart = bang >= 0 ? pattern.substring(0, bang) : pattern;
            String exclPart = bang >= 0 ? pattern.substring(bang + 1) : "";
            // require at least one positive cap, so a bare exclusion can never
            // ban the entire network
            if (!isValidCaps(reqPart) || reqPart.isEmpty()) continue;
            if (!exclPart.isEmpty() && !isValidCaps(exclPart)) continue;
            if (out.length() > 0) out.append(",");
            out.append(pattern);
        }
        return out.toString();
    }

    /**
     * Recognized characters in capability ban patterns.
     * Case-sensitive: lowercase 'f' = floodfill.
     * Includes '!' for exclusion group separator (e.g. "G!f").
     */
    private static final String VALID_CAP_CHARS = "KLMNOPXfGDEURH!";

    /**
     * Checks the custom capability list is well formed.
     *
     * @param s the capability list from the form
     * @return true if the list may be saved
     */
    private static boolean isValidCaps(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (VALID_CAP_CHARS.indexOf(s.charAt(i)) < 0) {return false;}
        }
        return true;
    }

    /**
     * Validate country codes - only allow valid 2-letter codes.
     *
     * @param input the codes as entered, comma or space separated, may be null or empty
     * @return the valid codes lower-cased and joined with commas, invalid codes dropped
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
