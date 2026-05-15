package net.i2p.router.web;

import java.util.HashMap;
import java.util.regex.Pattern;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpSession;

import net.i2p.I2PAppContext;
import net.i2p.servlet.util.ServletUtil;
import net.i2p.util.RandomSource;
import net.i2p.util.SystemVersion;

/**
 * Copied and modded from I2PTunnel IndexBean (GPL)
 * @author zzz
 */
public class CSSHelper extends HelperBase {
    private static final Pattern LANG_PATTERN = Pattern.compile("[a-zA-Z_]");

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
    /** Rotating nonce for CSRF protection, rotates every 5 minutes */
    private static String _currentNonce;
    private static final String[] _recentNonces = new String[2];
    private static long _lastRotation;
    private static final long NONCE_ROTATION_MS = 5 * 60 * 1000; // 5 minutes
    /** @since 0.9.67+ */
    public static final String PROP_UNIFIED_SIDEBAR = "routerconsole.unifiedSidebar";
    public static final boolean DEFAULT_UNIFIED_SIDEBAR = false;
    /** @since 0.9.68+ */
    public static final String PROP_STICKY_SIDEBAR = "routerconsole.stickySidebar";
    public static final boolean DEFAULT_STICKY_SIDEBAR = true;

    /** Session-bound nonce for CSRF protection, replaces static nonces @since 0.9.69 */
    private static final String SESSION_CONSOLE_NONCE = "__router.console.nonce.queue__";
    private static final int NONCE_QUEUE_SIZE = 10;

    /**
     *  Session-bound nonce generation - replaces static consoleNonce, updateNonce, reseedNonce, systemNonce
     *  @param session returns an invalid nonce if null
     *  @return a new nonce for each call
     *  @since 0.9.69
     */
    @SuppressWarnings("unchecked")
    public static String getNonce(HttpSession session) {
        if (session == null) {
            return "FAIL_SESSION_NOT_SET";
        }
        // add a prefix to distinguish from other nonces for debugging
        String rv = "CN" + RandomSource.getInstance().nextLong();
        synchronized(session) {
            LinkedList<String> nonces = (LinkedList<String>) session.getAttribute(SESSION_CONSOLE_NONCE);
            if (nonces == null) {
                nonces = new LinkedList<String>();
                session.setAttribute(SESSION_CONSOLE_NONCE, nonces);
            }
            nonces.offer(rv);
            if (nonces.size() > NONCE_QUEUE_SIZE)
                nonces.poll();
        }
        return rv;
    }

    /**
     *  Session-bound nonce validation - replaces static nonce validation
     *  @param nonce returns false if null
     *  @param session returns false if null
     *  @return true if valid
     *  @since 0.9.69
     */
    public static boolean validateNonce(HttpSession session, String nonce) {
        return validateNonce(session, nonce, false);
    }

    /**
     *  Session-bound nonce validation with preserve option
     *  @param nonce returns false if null
     *  @param session returns false if null
     *  @param preserve if true, do not delete the nonce. Use for early checks in jsps.
     *  @return true if valid
     *  @since 0.9.69
     */
    @SuppressWarnings("unchecked")
    public static boolean validateNonce(HttpSession session, String nonce, boolean preserve) {
        if (nonce == null) {
            return false;
        }
        if (session == null) {
            return false;
        }
        boolean rv;
        synchronized(session) {
            LinkedList<String> nonces = (LinkedList<String>) session.getAttribute(SESSION_CONSOLE_NONCE);
            if (nonces != null) {
                if (preserve)
                    rv = nonces.lastIndexOf(nonce) >= 0;
                else
                    rv = nonces.removeLastOccurrence(nonce);
            } else {
                rv = false;
            }
        }
        return rv;
    }

    /**
     *  Get current CSRF nonce, rotating every 5 minutes.
     *  Keeps 2 previous nonces for backward compatibility (multi-tab, slow clients).
     *  @since 0.9.4
     */
    public static String getNonce() {
        if (_currentNonce == null) {
            _currentNonce = Long.toString(RandomSource.getInstance().nextLong());
            _lastRotation = System.currentTimeMillis();
        } else if (System.currentTimeMillis() - _lastRotation > NONCE_ROTATION_MS) {
            // Rotate: shift current to recent[0], recent[0] to recent[1], generate new
            _recentNonces[1] = _recentNonces[0];
            _recentNonces[0] = _currentNonce;
            _currentNonce = Long.toString(RandomSource.getInstance().nextLong());
            _lastRotation = System.currentTimeMillis();
        }
        return _currentNonce;
    }

    /**
     *  Get recent nonce for backward compatibility.
     *  @param index 0 for most recent, 1 for second most recent
     *  @return nonce or null if not available
     *  @since 2.x.x
     */
    public static String getRecentNonce(int index) {
        if (index >= 0 && index < 2) {
            return _recentNonces[index];
        }
        return null;
    }

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
        if (PROP_ENABLE_SORA_FONT != null && "true".equalsIgnoreCase(PROP_ENABLE_SORA_FONT))
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
            LANG_PATTERN.matcher(lang).replaceAll("").length() == 0) {
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
        if (pageTitlePrefix != null && !pageTitlePrefix.isEmpty()) {
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
