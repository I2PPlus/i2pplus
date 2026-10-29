package net.i2p.router.web;

import java.util.Collections;
import java.util.HashMap;
import java.util.regex.Pattern;
import java.util.Locale;
import java.util.Map;

import javax.servlet.http.HttpSession;

import net.i2p.I2PAppContext;
import net.i2p.servlet.util.ServletUtil;
import net.i2p.util.LHMCache;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;
import net.i2p.util.SystemVersion;

/**
 * Copied and modded from I2PTunnel IndexBean (GPL).
 */
public class CSSHelper extends HelperBase {

    /** The context is injected by setContextId(), not by the ctor. */
    public CSSHelper() {}

    /** The log for the CSS helper. */
    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(CSSHelper.class);
    /** Pattern of characters allowed in language names. */
    private static final Pattern LANG_PATTERN = Pattern.compile("[a-zA-Z_]");
    /** Pattern that splits text on whitespace. */
    private static final Pattern SPACE_SPLIT = Pattern.compile("\\s+");

    /** Max User-Agent strings retained by {@link #_UACache} before LRU eviction. */
    static final int MAX_UA_CACHE = 64;
    /**
     * Bounded LRU memo of the "is this a small browser?" decision, keyed on the
     * request User-Agent. The key is a client-supplied string, so an unbounded
     * map would grow with every unique UA ever seen; {@link LHMCache} caps it.
     * Wrapped in {@link Collections#synchronizedMap} because {@link LHMCache} is
     * unsynchronized and the console serves concurrent requests.
     */
    private static final Map<String, Boolean> _UACache =
            Collections.synchronizedMap(new LHMCache<String, Boolean>(MAX_UA_CACHE));
    /**
     * Property key selecting the universal (theme-independent) colour scheme.
     */
    public static final String PROP_UNIVERSAL_THEMING = "routerconsole.universal.theme";
    /**
     * Property key naming the console theme in use.
     */
    public static final String PROP_THEME_NAME = "routerconsole.theme";
    /**
     * Prefix for the per-theme properties mapping a theme name to its directory.
     * @since 0.9.33 moved from ConfigUIHelper
     */
    public static final String PROP_THEME_PFX = PROP_THEME_NAME + '.';
    /**
     * Theme applied when none is configured.
     */
    public static final String DEFAULT_THEME = "dark";
    /**
     * Console-relative path each theme's directory is served under.
     */
    public static final String BASE_THEME_PATH = "/themes/console/";
    /** Theme name that forces the classic console. */
    private static final String FORCE = "classic";
    /**
     * Property key holding the summary page refresh interval in seconds.
     */
    public static final String PROP_REFRESH = "routerconsole.summaryRefresh";
    /**
     * Refresh interval in seconds used when none is configured.
     */
    public static final String DEFAULT_REFRESH = "3";
    /**
     * Lowest refresh interval accepted, disabling automatic refresh when zero.
     */
    public static final int MIN_REFRESH = 0;
    /**
     * Property key that suppresses the summary page's automatic refresh.
     */
    public static final String PROP_DISABLE_REFRESH = "routerconsole.summaryDisableRefresh";
    /** Config key for disabling the X-Frame protection. */
    private static final String PROP_XFRAME = "routerconsole.disableXFrame";
    /**
     * Property key forcing the narrow mobile console on wide screens.
     */
    public static final String PROP_FORCE_MOBILE_CONSOLE = "routerconsole.forceMobileConsole";
    /** Whether apps open inside a console IFrame instead of replacing the console.
     * @since 0.9.32 */
    public static final String PROP_EMBED_APPS = "routerconsole.embedApps";
    /** Whether the console uses the Sora display font instead of Open Sans.
     * @since 0.9.59+ */
    public static final String PROP_ENABLE_SORA_FONT = "routerconsole.displayFontSora";
    /**
     * Whether the Sora display font is used when no preference is configured.
     */
    public static final boolean DEFAULT_ENABLE_SORA_FONT = false;
    /** Rotating nonce for CSRF protection, rotates every 5 minutes. */
    private static String currentNonce;
    /** The nonces accepted for the current and previous rotation. */
    private static final String[] _recentNonces = new String[2];
    /** When the content nonces were last rotated. */
    private static long lastRotation;
    /** Interval between the nonce rotations. */
    private static final long NONCE_ROTATION_MS = 5 * (long) 60 * 1000; // 5 minutes
    /** Whether the sidebar is unified with the main content column.
     * @since 0.9.67+ */
    public static final String PROP_UNIFIED_SIDEBAR = "routerconsole.unifiedSidebar";
    /**
     * Whether the sidebar is merged into the main content column by default.
     */
    public static final boolean DEFAULT_UNIFIED_SIDEBAR = false;
    /** Whether a sidebar shorter than the viewport sticks to the top while scrolling.
     * @since 0.9.68+ */
    public static final String PROP_STICKY_SIDEBAR = "routerconsole.stickySidebar";
    /**
     * Whether a short sidebar sticks to the top while the page scrolls.
     */
    public static final boolean DEFAULT_STICKY_SIDEBAR = true;
    /** True for the legacy RRD4J minigraph renderer, false for the dual-baseline canvas.
     * @since 0.9.70+ */
    public static final String PROP_SIDEBAR_GRAPH_LEGACY = "routerconsole.sidebarGraphLegacy";
    /** Default true for legacy RRD4J; set false for dual-baseline canvas.
     * @since 0.9.70+ */
    public static final boolean DEFAULT_SIDEBAR_GRAPH_LEGACY = true;
    /** Sidebar graph time period in minutes (2–30, default 20).
     * @since 0.9.70+ */
    public static final String PROP_SIDEBAR_GRAPH_MINUTES = "routerconsole.sidebarGraphMinutes";
    /** True to split the display (inbound top, outbound bottom), false to overlay.
     * @since 0.9.70+ */
    public static final String PROP_SIDEBAR_GRAPH_SPLIT = "routerconsole.sidebarGraphSplit";
    /** Sidebar graph render direction: "ltr" or "rtl".
     * @since 0.9.70+ */
    public static final String PROP_SIDEBAR_GRAPH_DIRECTION = "routerconsole.sidebarGraphDirection";
    /** True for a continuous scrolling graph, false for a full-buffer redraw (default).
     * @since 0.9.70+ */
    public static final String PROP_SIDEBAR_GRAPH_CONTINUOUS = "routerconsole.sidebarGraphContinuous";

    /** Session-bound nonce for CSRF protection, replaces static nonces @since 0.9.69. */
    private static final String SESSION_CONSOLE_NONCE = "__router.console.nonce.queue__";

    /**
     * Session-bound nonce generation - replaces static consoleNonce, updateNonce, reseedNonce, systemNonce.
     * <p>Issue one nonce per nonce-guarded form or link. A nonce is consumed
     * by {@link #validateNonce(HttpSession, String)}, so two controls that
     * share one nonce leave the second of them silently dead once the first is
     * used.
     *
     * @param session returns an invalid nonce if null
     * @return a new nonce for each call
     * @since 0.9.69
     */
    public static String getNonce(HttpSession session) {
        if (session == null) {
            return "FAIL_SESSION_NOT_SET";
        }
        // add a prefix to distinguish from other nonces for debugging
        String rv = "CN" + RandomSource.getInstance().nextLong();
        long now = System.currentTimeMillis();
        try {
            synchronized(session) {
                Object queued = session.getAttribute(SESSION_CONSOLE_NONCE);
                // Not a queue (a session built by an older console, or nothing
                // at all) — start a fresh one, the old entries are unusable.
                ConsoleNonceQueue nonces = queued instanceof ConsoleNonceQueue
                                           ? (ConsoleNonceQueue) queued
                                           : new ConsoleNonceQueue();
                nonces.add(rv, now);
                session.setAttribute(SESSION_CONSOLE_NONCE, nonces);
            }
        } catch (IllegalStateException ise) {
            // Session was invalidated/expired mid-render; Jetty throws ISE from
            // getAttribute/setAttribute. Degrade to the static nonce so the page
            // still renders and FormHandler's static fallback validates on submit.
            if (_log.shouldWarn()) {
                _log.warn("CSSHelper.getNonce(): session invalid, using static nonce", ise);
            }
            return getNonce();
        }
        return rv;
    }

    /**
     * Session-bound nonce validation - replaces static nonce validation.
     * @param session returns false if null
     * @param nonce returns false if null
     * @return true if valid
     * @since 0.9.69
     */
    public static boolean validateNonce(HttpSession session, String nonce) {
        return validateNonce(session, nonce, false);
    }

    /**
     * Session-bound nonce validation with preserve option.
     * <p>Validation consumes the nonce, so a control that submits a nonce
     * already used, or one the session has stopped remembering, is refused
     * here and does nothing at its own call site.
     *
     * @param session returns false if null
     * @param nonce returns false if null
     * @param preserve if true, do not delete the nonce. Use for early checks in jsps.
     * @return true if valid
     * @since 0.9.69
     */
    public static boolean validateNonce(HttpSession session, String nonce, boolean preserve) {
        if (nonce == null) {
            return false;
        }
        if (session == null) {
            return false;
        }
        boolean rv;
        long now = System.currentTimeMillis();
        try {
            synchronized(session) {
                Object queued = session.getAttribute(SESSION_CONSOLE_NONCE);
                ConsoleNonceQueue nonces = queued instanceof ConsoleNonceQueue
                                           ? (ConsoleNonceQueue) queued
                                           : null;
                rv = nonces != null && nonces.validate(nonce, now, preserve);
            }
        } catch (IllegalStateException ise) {
            // Session invalidated/expired mid-request; Jetty throws ISE from getAttribute.
            // Fail closed to the static fallback in FormHandler.validate().
            if (_log.shouldWarn()) {
                _log.warn("CSSHelper.validateNonce(): session invalid, failing to static fallback", ise);
            }
            return false;
        }
        if (_log.shouldDebug()) {
            _log.debug("CSRF nonce check -> " + (rv ? "Accepted" : "Rejected") +
                       " * Session: " + System.identityHashCode(session));
        }
        return rv;
    }

    /**
     * Get current CSRF nonce, rotating every 5 minutes.
     * Keeps 2 previous nonces for backward compatibility (multi-tab, slow clients).
     * @return the current CSRF token, replaced with a fresh one once the
     *         rotation interval has elapsed
     * @since 0.9.4
     */
    public static synchronized String getNonce() {
        if (currentNonce == null) {
            currentNonce = Long.toString(RandomSource.getInstance().nextLong());
            lastRotation = System.currentTimeMillis();
        } else if (System.currentTimeMillis() - lastRotation > NONCE_ROTATION_MS) {
            // Rotate: shift current to recent[0], recent[0] to recent[1], generate new
            _recentNonces[1] = _recentNonces[0];
            _recentNonces[0] = currentNonce;
            currentNonce = Long.toString(RandomSource.getInstance().nextLong());
            lastRotation = System.currentTimeMillis();
        }
        return currentNonce;
    }

    /**
     * Get recent nonce for backward compatibility.
     * @param index 0 for most recent, 1 for second most recent
     * @return nonce or null if not available
     * @since 0.9.70+
     */
    public static String getRecentNonce(int index) {
        if (index >= 0 && index < 2) {
            return _recentNonces[index];
        }
        return null;
    }

    /**
     * Theme directory prefix, forced to classic for pre-Trident/6 IE.
     * @param userAgent the request User-Agent header, null for a normal browser
     * @return the theme path to prepend to console CSS and image references
     */
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
     * Returns whether app embedding is enabled or disabled.
     * @return true to open apps in a console IFrame, false to replace the console
     * @since 0.9.32
     */
    public boolean embedApps() {return _context.getBooleanProperty(PROP_EMBED_APPS);}

    /**
     * Whether the Sora display font is enabled.
     * @return true to use Sora, false for Open Sans
     * @since 0.9.59+
     */
    public boolean useSoraDisplayFont() {return _context.getBooleanProperty(PROP_ENABLE_SORA_FONT);}

    /**
     * Save the configuration to enable the Sora display font, if the
     * property is set.
     * @since 0.9.59+
     */
    public void setSoraDisplayFont() {
        // Protected with nonce in css.jsi
        if (_context.getBooleanProperty(PROP_ENABLE_SORA_FONT))
            _context.router().saveConfig(PROP_ENABLE_SORA_FONT, "true");
    }

    /**
     * Whether the unified sidebar is enabled, defaulting to false.
     * @return true if explicitly enabled, false otherwise
     * @since 0.9.67+
     */
    public boolean useUnifiedSidebar() {return _context.getBooleanProperty(PROP_UNIFIED_SIDEBAR);}

    /**
     * Whether the unified sidebar is in use, defaulting to true.
     * @return true unless the user has explicitly turned the unified sidebar off
     * @since 0.9.67+
     */
    public boolean enableUnifiedSidebar() {return _context.getBooleanPropertyDefaultTrue(PROP_UNIFIED_SIDEBAR);}

    /**
     * Returns whether we should use a sticky sidebar when the sidebar height is less than the height of the viewport.
     * @return true if explicitly enabled, false otherwise
     * @since 0.9.68+
     */
    public boolean useStickySidebar() {return _context.getBooleanProperty(PROP_STICKY_SIDEBAR);}

    /**
     * Returns whether to use the legacy RRD4J minigraph renderer.
     * When false, the new dual-baseline canvas renderer is used with
     * inbound spiking upward (top half) and outbound downward (bottom half).
     * @return true for RRD4J, false for the canvas renderer
     * @since 0.9.70+
     */
    public boolean useLegacySidebarGraph() {return _context.getBooleanProperty(PROP_SIDEBAR_GRAPH_LEGACY);}

    /**
     * Returns the sidebar graph time period in minutes (2–30).
     * @return the sidebar graph minutes
     * @since 0.9.70+
     */
    public int getSidebarGraphMinutes() {
        int val = _context.getProperty(PROP_SIDEBAR_GRAPH_MINUTES, 20);
        return Math.max(2, Math.min(30, val));
    }

    /**
     * Returns whether the sidebar graph uses split mode (inbound top, outbound bottom).
     * When false, overlay mode is used (both lines from top).
     * @return true unless the user has explicitly turned split mode off
     * @since 0.9.70+
     */
    public boolean useSidebarGraphSplit() {return _context.getBooleanPropertyDefaultTrue(PROP_SIDEBAR_GRAPH_SPLIT);}

    /**
     * Returns whether the sidebar graph uses continuous scroll mode.
     * When enabled, the graph scrolls left as new data arrives.
     * @return true for continuous scrolling, false to redraw the whole buffer
     * @since 0.9.70+
     */
    public boolean useSidebarGraphContinuous() {return _context.getBooleanProperty(PROP_SIDEBAR_GRAPH_CONTINUOUS);}

    /**
     * Returns the sidebar graph render direction ("ltr" or "rtl").
     * @return the sidebar graph direction
     * @since 0.9.70+
     */
    public String getSidebarGraphDirection() {
        String val = _context.getProperty(PROP_SIDEBAR_GRAPH_DIRECTION, "rtl");
        return "ltr".equals(val) ? "ltr" : "rtl";
    }

    /**
     * Returns whether we should use a sticky sidebar when the sidebar height is less than the height of the viewport.
     * @return true unless the user has explicitly turned the sticky sidebar off
     * @since 0.9.68+
     */
    public boolean enableStickySidebar() {return _context.getBooleanPropertyDefaultTrue(PROP_STICKY_SIDEBAR);}

    /**
     * Change default language for the router AND save it.
     * @param lang xx OR xx_XX OR xxx OR xxx_XX
     */
    public void setLang(String lang) {
        // Protected with nonce in css.jsi
        if (lang != null && lang.length() >= 2 && lang.length() <= 6 &&
            LANG_PATTERN.matcher(lang).replaceAll("").isEmpty()) {
            Map<String, String> m = new HashMap<>(2);
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
     * Needed for conditional css loads for zh.
     * @return two-letter only, lower-case
     */
    public String getLang() {return Messages.getLanguage(_context);}

    /**
     * Show / hide news on home page.
     * @param val if non-null, "1" to show, else hide
     * @since 0.8.12
     */
    public void setNews(String val) {
        // Protected with nonce in css.jsi
        if (val != null) {NewsHelper.showNews(_context, val.equals("1"));}
    }

    /**
     * Should we send X_Frame_Options=SAMEORIGIN
     * Default true.
     * @return whether send x frame
     * @since 0.9.1
     */
    public boolean shouldSendXFrame() {return !_context.getBooleanProperty(PROP_XFRAME);}

    /**
     * Change refresh and save it, "0" also disables the refresh entirely.
     * @param r the new refresh period in seconds
     */
    public void setRefresh(String r) {
        try {
            if (r.equals("0")) {_context.router().saveConfig(PROP_DISABLE_REFRESH, "true");}
            if (Integer.parseInt(r) < MIN_REFRESH) {r = Integer.toString(MIN_REFRESH);}
            _context.router().saveConfig(PROP_REFRESH, r);
        } catch (RuntimeException e) { /* ignored */ }
    }

    /**
     * The summary page refresh period, floored at 5s under heavy CPU load.
     * @return refresh time in seconds, as a string, or "3600" when refresh is disabled
     */
    public String getRefresh() {
        String r = _context.getProperty(PROP_REFRESH, DEFAULT_REFRESH);
        if (r.equals("0")) {r = "3600";}
        try { if (Integer.parseInt(r) < MIN_REFRESH) {r = Integer.toString(MIN_REFRESH);} }
        catch (RuntimeException e) {r = Integer.toString(MIN_REFRESH);}
        if (SystemVersion.getCPULoadAvg() > 90 && Integer.parseInt(r) < 5 && Integer.parseInt(r) != 0) {return "5";}
        else {return r;}
    }

    /**
     * Change disable refresh boolean and save it.
     * @param r "0" to disable the refresh, anything else to re-enable it
     * @since 0.9.1
     */
    public void setDisableRefresh(String r) {
        String disableRefresh = "false";
        if ("0".equals(r)) {disableRefresh = "true";}
        _context.router().saveConfig(PROP_DISABLE_REFRESH, disableRefresh);
    }

    /**
     * Is the summary page refresh turned off?
     * @return true if refresh is disabled
     * @since 0.9.1
     */
    public boolean getDisableRefresh() {return _context.getBooleanProperty(PROP_DISABLE_REFRESH);}

    /**
     * Translate the title and display consistently.
     * @param s the raw page title
     * @return a complete title element, prefixed by {@code routerconsole.pageTitlePrefix} if set
     */
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
     * Should we allow a refreshing IFrame?
     * @param ua the request User-Agent header, null to allow the IFrame
     * @return false if the mobile console is forced or the browser is too small
     * @since 0.8.5
     */
    public boolean allowIFrame(String ua) {
        boolean forceMobileConsole = _context.getBooleanProperty(PROP_FORCE_MOBILE_CONSOLE);
        if (forceMobileConsole) {return false;}
        if (ua == null) {return true;}
        return cachedAllowIFrame(ua);
    }

    /**
     * Memoise the small-browser decision for a User-Agent in the bounded
     * {@link #_UACache}. Package-visible and static so the eviction behaviour is
     * testable without a router context.
     *
     * @param ua the request User-Agent header, non-null
     * @return whether an IFrame is allowed for this UA
     * @since 0.9.71+
     */
    static boolean cachedAllowIFrame(String ua) {
        Boolean brv = _UACache.get(ua);
        if (brv != null) {return brv.booleanValue();}
        boolean rv = shouldAllowIFrame(ua);
        _UACache.put(ua, Boolean.valueOf(rv));
        return rv;
    }

    /**
     * Number of User-Agent strings currently memoised in {@link #_UACache}.
     *
     * @return the cache size, for tests and diagnostics
     * @since 0.9.71+
     */
    static int uaCacheSize() {
        return _UACache.size();
    }

    /**
     * Whether the user agent may embed the console in a frame.
     *
     * @param ua the user agent string
     * @return true to allow the frame
     */
    private static boolean shouldAllowIFrame(String ua) {
        return !ServletUtil.isSmallBrowser(ua);
    }

    /**
     * Are the advanced console settings pages shown?
     * @return whether advanced mode
     */
    public boolean isAdvancedMode() {
        return _context.getBooleanProperty("routerconsole.advanced");
    }

    /** Capitalize first letter of each word of string */
    public static class StringFormatter {

        /** All methods are static; the ctor exists only for javadoc. */
        public StringFormatter() {}

        /**
         * Capitalizes the first letter of every whitespace-separated word.
         * @param str the words to capitalize
         * @return the string with an upper case first letter in every word
         */
        public static String capitalizeWord(String str) {
            if (str == null || str.isEmpty()) return str;
            String trimmed = str.trim();
            if (trimmed.isEmpty()) return trimmed;
            String[] words = SPACE_SPLIT.split(trimmed);
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
