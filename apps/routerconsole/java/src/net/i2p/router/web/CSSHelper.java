package net.i2p.router.web;

import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.i2p.servlet.util.ServletUtil;
import net.i2p.util.RandomSource;
import net.i2p.util.SystemVersion;

/**
 * Copied and modded from I2PTunnel IndexBean (GPL)
 * @author zzz
 */
public class CSSHelper extends HelperBase {

    private static final Map<String, Boolean> _UACache = new ConcurrentHashMap<String, Boolean>();
    public static final String PROP_UNIVERSAL_THEMING = "routerconsole.universal.theme";
    public static final String PROP_THEME_NAME = "routerconsole.theme";
    /**  @since 0.9.33 moved from ConfigUIHelper */
    public static final String PROP_THEME_PFX = PROP_THEME_NAME + '.';
    public static final String DEFAULT_THEME = "dark";
    public static final String BASE_THEME_PATH = "/themes/console/";
    private static final String FORCE = "classic";
    public static final String PROP_REFRESH = "routerconsole.summaryRefresh";
    public static final String DEFAULT_REFRESH = "3";
    public static final int MIN_REFRESH = 0;
    public static final String PROP_DISABLE_REFRESH = "routerconsole.summaryDisableRefresh";
    private static final String PROP_XFRAME = "routerconsole.disableXFrame";
    public static final String PROP_FORCE_MOBILE_CONSOLE = "routerconsole.forceMobileConsole";
    /** @since 0.9.32 */
    public static final String PROP_EMBED_APPS = "routerconsole.embedApps";
    /** @since 0.9.59+ */
    public static final String PROP_ENABLE_SORA_FONT = "routerconsole.displayFontSora";
    public static final boolean DEFAULT_ENABLE_SORA_FONT = false;
    private static final String _consoleNonce = Long.toString(RandomSource.getInstance().nextLong());
    /** @since 0.9.67+ */
    public static final String PROP_UNIFIED_SIDEBAR = "routerconsole.unifiedSidebar";
    public static final boolean DEFAULT_UNIFIED_SIDEBAR = false;
    /** @since 0.9.68+ */
    public static final String PROP_STICKY_SIDEBAR = "routerconsole.stickySidebar";
    public static final boolean DEFAULT_STICKY_SIDEBAR = true;

    /**
     *  formerly stored in System.getProperty("router.consoleNonce")
     *  @since 0.9.4
     */
    public static String getNonce() {return _consoleNonce;}

    public String getTheme(String userAgent) {
        String url = BASE_THEME_PATH;
        if (userAgent != null && userAgent.contains("MSIE") && !userAgent.contains("Trident/6")) {
            url += FORCE + "/";
        } else {
            // This is the first thing to use _context on most pages
            if (_context == null) {
                throw new IllegalStateException("No contexts. This is usually because the router is either starting up or shutting down.");
            }
            String theme = _context.getProperty(PROP_THEME_NAME, DEFAULT_THEME);
            url += theme + "/";
        }
        return url;
    }

    /**
     * Returns whether app embedding is enabled or disabled
     * @since 0.9.32
     */
    public boolean embedApps() {return _context.getBooleanProperty(PROP_EMBED_APPS);}

    /**
     * Returns whether we should use Sora display font
     * @since 0.9.59+
     */
    public boolean useSoraDisplayFont() {return _context.getBooleanProperty(PROP_ENABLE_SORA_FONT);}

    /**
     *  Save config for alternative display font if enabled
     *  @since 0.9.59+
     */
    public void setSoraDisplayFont() {
        // Protected with nonce in css.jsi
        if (PROP_ENABLE_SORA_FONT != null && PROP_ENABLE_SORA_FONT.toLowerCase().equals("true"))
            _context.router().saveConfig(PROP_ENABLE_SORA_FONT, "true");
    }

    /**
     * Returns whether we should use a unified sidebar
     * @since 0.9.67+
     */
    public boolean useUnifiedSidebar() {return _context.getBooleanProperty(PROP_UNIFIED_SIDEBAR);}

    /**
     * Returns whether we should use a unified sidebar
     * @since 0.9.67+
     */
    public boolean enableUnifiedSidebar() {return _context.getBooleanPropertyDefaultTrue(PROP_UNIFIED_SIDEBAR);}

    /**
     * Returns whether we should use a sticky sidebar when the sidebar height is less than the height of the viewport
     * @since 0.9.68+
     */
    public boolean useStickySidebar() {return _context.getBooleanProperty(PROP_STICKY_SIDEBAR);}

    /**
     * Returns whether we should use a unified sidebar
     * @since 0.9.68+
     */
    public boolean enableStickySidebar() {return _context.getBooleanPropertyDefaultTrue(PROP_STICKY_SIDEBAR);}

    /**
     * change default language for the router AND save it
     * @param lang xx OR xx_XX OR xxx OR xxx_XX
     */
    public void setLang(String lang) {
        // Protected with nonce in css.jsi
        if (lang != null && lang.length() >= 2 && lang.length() <= 6 &&
            lang.replaceAll("[a-zA-Z_]", "").length() == 0) {
            Map<String, String> m = new HashMap<String, String>(2);
            int under = lang.indexOf('_');
            if (under < 0) {
                m.put(Messages.PROP_LANG, lang.toLowerCase(Locale.US));
                m.put(Messages.PROP_COUNTRY, "");
                _context.router().saveConfig(m, null);
            } else if (under > 0 && lang.length() > under + 1) {
                m.put(Messages.PROP_LANG, lang.substring(0, under).toLowerCase(Locale.US));
                m.put(Messages.PROP_COUNTRY, lang.substring(under + 1).toUpperCase(Locale.US));
                _context.router().saveConfig(m, null);
            }
        }
    }

    /**
     * needed for conditional css loads for zh
     * @return two-letter only, lower-case
     */
    public String getLang() {return Messages.getLanguage(_context);}

    /**
     *  Show / hide news on home page
     *  @param val if non-null, "1" to show, else hide
     *  @since 0.8.12
     */
    public void setNews(String val) {
        // Protected with nonce in css.jsi
        if (val != null) {NewsHelper.showNews(_context, val.equals("1"));}
    }

    /**
     *  Should we send X_Frame_Options=SAMEORIGIN
     *  Default true
     *  @since 0.9.1
     */
    public boolean shouldSendXFrame() {return !_context.getBooleanProperty(PROP_XFRAME);}

    /** change refresh and save it */
    public void setRefresh(String r) {
        try {
            if (r.equals("0")) {_context.router().saveConfig(PROP_DISABLE_REFRESH, "true");}
            if (Integer.parseInt(r) < MIN_REFRESH) {r = Integer.toString(MIN_REFRESH);}
            _context.router().saveConfig(PROP_REFRESH, r);
        } catch (RuntimeException e) {}
    }

    /** @return refresh time in seconds, as a string */
    public String getRefresh() {
        String r = _context.getProperty(PROP_REFRESH, DEFAULT_REFRESH);
        if (r.equals("0")) {r = "3600";}
        try { if (Integer.parseInt(r) < MIN_REFRESH) {r = Integer.toString(MIN_REFRESH);} }
        catch (RuntimeException e) {r = Integer.toString(MIN_REFRESH);}
        if (SystemVersion.getCPULoadAvg() > 90 && Integer.parseInt(r) < 5 && Integer.parseInt(r) != 0) {return "5";}
        else {return r;}
    }

    /**
     * change disable refresh boolean and save it
     * @since 0.9.1
     */
    public void setDisableRefresh(String r) {
        String disableRefresh = "false";
        if ("0".equals(r)) {disableRefresh = "true";}
        _context.router().saveConfig(PROP_DISABLE_REFRESH, disableRefresh);
    }

    /**
     * @return true if refresh is disabled
     * @since 0.9.1
     */
    public boolean getDisableRefresh() {return _context.getBooleanProperty(PROP_DISABLE_REFRESH);}

    /** translate the title and display consistently */
    public String title(String s) {
         StringBuilder buf = new StringBuilder(128);
         String lang = _context.getProperty("routerconsole.lang");
         String pageTitlePrefix = _context.getProperty("routerconsole.pageTitlePrefix");
         if ((lang == null || lang.equals("en")) && s.startsWith("config ")) {
             s = s.replace(_t("config"), _t("Configure"));
         }
         if ((lang == null || lang.equals("en")) && s.contains("i2cp")) {
             s = s.replace("i2cp", "I2CP");
         }
         buf.append("<title>");
         if (pageTitlePrefix != null && !pageTitlePrefix.equals("")) {
             buf.append(pageTitlePrefix).append(" ");
         }
         buf.append(StringFormatter.capitalizeWord(_t(s))).append(" - I2P+").append("</title>");
         return buf.toString();
    }

    /**
     *  Should we allow a refreshing IFrame?
     *  @since 0.8.5
     */
    public boolean allowIFrame(String ua) {
        boolean forceMobileConsole = _context.getBooleanProperty(PROP_FORCE_MOBILE_CONSOLE);
        if (forceMobileConsole) {return false;}
        if (ua == null) {return true;}
        Boolean brv = _UACache.get(ua);
        if (brv != null) {return brv.booleanValue();}
        boolean rv = shouldAllowIFrame(ua);
        _UACache.put(ua, Boolean.valueOf(rv));
        return rv;
    }

    private static boolean shouldAllowIFrame(String ua) {
        return !ServletUtil.isSmallBrowser(ua);
    }

    public boolean isAdvancedMode() {
        return _context.getBooleanProperty("routerconsole.advanced");
    }

    /** Capitalize first letter of each word of string
     * https://www.javatpoint.com/java-program-to-capitalize-each-word-in-string
     */
    public static class StringFormatter {
        public static String capitalizeWord(String str) {
            String words[] = str.split("\\s");
            StringBuilder capitalizeWord = new StringBuilder("");
            for (String w:words) {
                String first = w.substring(0,1);
                String afterfirst = w.substring(1);
                capitalizeWord.append(first.toUpperCase());
                capitalizeWord.append(afterfirst);
                capitalizeWord.append(" ");
            }
            return capitalizeWord.toString().trim();
        }
    }

}
