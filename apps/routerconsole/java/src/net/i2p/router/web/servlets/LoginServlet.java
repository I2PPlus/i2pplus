package net.i2p.router.web.servlets;

import java.io.IOException;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Pattern;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpSession;
import net.i2p.I2PAppContext;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.web.CSSHelper;
import net.i2p.router.web.ConsolePasswordManager;
import net.i2p.router.web.RouterConsoleRunner;
import net.i2p.servlet.filters.SessionManager;
import net.i2p.util.Addresses;
import net.i2p.util.Log;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;

/**
 * Login servlet for console authentication.
 */
public class LoginServlet extends HttpServlet {

    /**
     * Constructor, used by the servlet container. The container calls {@link #init()}
     * afterwards, which is where the persisted sessions are loaded.
     */
    public LoginServlet() {}

    /** Logger for the login servlet. */
    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(LoginServlet.class);

    @Override
    public void init() throws ServletException {
        _log.info("=== LoginServlet.init() CALLED ===");
        loadPersistedSessions();
    }

    /** Form parameter carrying the user name. */
    private static final String PARAM_USERNAME = "nofilter_username";
    /** Form parameter carrying the password. */
    private static final String PARAM_PASSWORD = "nofilter_password";
    /** The authentication realm name. */
    private static final String REALM = "i2prouter";
    /** Default login page theme. */
    private static final String DEFAULT_THEME = "dark";
    /** Config key for the persisted session count. */
    private static final String PROP_PERSISTED_SESSIONS = "routerconsole.persistedSessions";
    /**
     * Serializes the read-modify-write of the persisted-session list. Two
     * concurrent logins otherwise read the same list and the later saveConfig()
     * overwrites the earlier one's entry, so a session that was already issued
     * silently stops working.
     */
    private static final Object _SESSION_LIST_LOCK = new Object();
    /**
     * How many "remember me" sessions router.config holds. Every login used to
     * append one, with no expiry pruning, so the value grew without bound.
     */
    static final int MAX_PERSISTED_SESSIONS = 20;
    /**
     * Set to true only when the console really does sit behind a reverse proxy
     * that rewrites the forwarding headers. Left false, the login blocklist keys
     * on the peer address of the connection, which a client cannot forge.
     */
    private static final String PROP_TRUST_FORWARDED_FOR = "routerconsole.trustForwardedFor";
    /** Splits a stored session record on colons. */
    private static final Pattern COLON_SPLIT = Pattern.compile(":");
    /** Splits the list of stored session records. */
    private static final Pattern PIPE_SPLIT = Pattern.compile("\\|");
    /** Random source for the CSRF tokens. */
    private static final SecureRandom CSRF_RANDOM = new SecureRandom();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        boolean routerReady = false;
        if (ctx instanceof RouterContext) {
            Router router = ((RouterContext) ctx).router();
            routerReady = router.isAlive() && router.isRunning();
        }
        if (!routerReady) {
            req.setAttribute("routerStarting", true);
            req.setAttribute("theme", getLoginTheme());
            req.getRequestDispatcher("/login.jsp").forward(req, resp);
            return;
        }

        String sessionToken = getSessionCookie(req);
        String username = SessionManager.getInstance().validateSession(sessionToken);
        if (username != null) {
            resp.sendRedirect(req.getContextPath() + "/");
            return;
        }
        String theme = getLoginTheme();
        boolean enforceLogin = false;
        if (ctx instanceof RouterContext) {
            enforceLogin = ( ctx).getBooleanPropertyDefaultTrue("routerconsole.enforceLogin");
        }
        boolean hasPasswords = isPasswordConfigured();
        if (!enforceLogin && !hasPasswords) {
            resp.sendRedirect(req.getContextPath() + "/");
            return;
        }

        String error = req.getParameter("error");
        if ("session_expired".equals(error)) {
            req.setAttribute("error", "Session expired. Please try again.");
        } else if ("csrf_invalid".equals(error)) {
            req.setAttribute("error", "Invalid request. Please try again.");
        }

        req.setAttribute("theme", theme);
        req.setAttribute("setupMode", !hasPasswords);
        if (!hasPasswords) {
            req.setAttribute("setupTitle", "Set Up Console Access");
            req.setAttribute("setupMessage", "Create a username and password to access the router console.");
        }
        String csrfToken = generateCSRFToken();
        req.setAttribute("I2P+CSRFTOKEN", csrfToken);
        req.getSession(true).setAttribute("loginCSRF", csrfToken);
        req.getRequestDispatcher("/login.jsp").forward(req, resp);
    }

    /**
     * Checks whether any console password is configured.
     *
     * @return true if a password exists
     */
    private boolean isPasswordConfigured() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        if (!(ctx instanceof RouterContext)) return false;
        return hasAnyPassword((RouterContext) ctx);
    }

    /**
     * Checks the password files for any usable password.
     *
     * @param ctx the router context
     * @return true if a password exists
     */
    private boolean hasAnyPassword(RouterContext ctx) {
        ConsolePasswordManager mgr = new ConsolePasswordManager(ctx);
        boolean hasMD5 = !mgr.getMD5(RouterConsoleRunner.PROP_CONSOLE_PW).isEmpty();
        if (hasMD5) {
            return true;
        }
        String pfx = RouterConsoleRunner.PROP_CONSOLE_PW + ".";
        for (Map.Entry<String, String> e : ctx.router().getConfigMap().entrySet()) {
            String key = e.getKey();
            if (key != null && key.startsWith(pfx) && key.endsWith(".pbkdf2")) {
                String val = e.getValue();
                if (val != null && !val.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the configured login page theme.
     *
     * @return the theme name
     */
    private String getLoginTheme() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        if (ctx instanceof RouterContext) {
            String theme = ((RouterContext) ctx).getProperty(CSSHelper.PROP_THEME_NAME, CSSHelper.DEFAULT_THEME);
            if (theme != null && !theme.isEmpty()) {
                return theme;
            }
        }
        return DEFAULT_THEME;
    }

    /** Themes the login page accepts. */
    private static final Set<String> ALLOWED_THEMES =
            Collections.unmodifiableSet(
                new HashSet<>(Arrays.asList("dark", "classic", "light", "midnight")));
    /**
     * Languages offered by the login page flag bar (login.jsp langToCountry).
     * Any addition there MUST be mirrored here, or the selection is silently
     * discarded and falls back to "en".
     */
    private static final Set<String> ALLOWED_LANGS =
            Collections.unmodifiableSet(
                new HashSet<>(Arrays.asList(
                    "ar", "az", "bn", "bo", "ca", "cs", "da", "de", "el",
                    "en", "es", "et", "fa", "fi", "fr", "he", "hi", "hu",
                    "in", "it", "ja", "ko", "nb", "nl", "pl", "ps", "pt",
                    "ro", "ru", "sk", "sl", "sv", "sw", "th", "tl", "tr", "uk",
                    "ur", "vi", "zh", "zh_TW")));

    /**
     * Keeps only the known theme names.
     *
     * @param theme the theme from the request, may be null
     * @return a safe theme name
     */
    private static String sanitizeTheme(String theme) {
        if (theme != null && ALLOWED_THEMES.contains(theme)) return theme;
        return "dark";
    }

    /**
     * Keeps only a well formed language tag.
     *
     * @param lang the language from the request, may be null
     * @return a safe language tag
     */
    private static String sanitizeLang(String lang) {
        if (lang != null && ALLOWED_LANGS.contains(lang)) return lang;
        return "en";
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        String themeRaw = req.getParameter("theme");
        String langRaw = req.getParameter("lang");

        if (themeRaw != null && langRaw == null) {
            String theme = sanitizeTheme(themeRaw);
            updatePreference("routerconsole.theme", theme);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"success\":true,\"theme\":\"" +
                DataHelper.escapeHTML(theme) + "\"}");
            return;
        }
        if (langRaw != null) {
            String lang = sanitizeLang(langRaw);
            // Region-qualified codes (zh_TW) must be split, or Translate would
            // build new Locale("zh_TW") and miss the bundle. Mirrors CSSHelper.setLang().
            int under = lang.indexOf('_');
            if (under > 0 && lang.length() > under + 1) {
                updatePreference("routerconsole.lang", lang.substring(0, under).toLowerCase(Locale.US));
                updatePreference("routerconsole.country", lang.substring(under + 1).toUpperCase(Locale.US));
            } else {
                updatePreference("routerconsole.lang", lang);
                updatePreference("routerconsole.country", "");
            }
            resp.setContentType("application/json");
            resp.getWriter().write("{\"success\":true,\"lang\":\"" +
                DataHelper.escapeHTML(lang) + "\"}");
            return;
        }

        try {
        String username = req.getParameter(PARAM_USERNAME);
        String password = req.getParameter(PARAM_PASSWORD);
        String confirmPassword = req.getParameter("nofilter_confirmPassword");
        String duration = req.getParameter("duration");
        String csrfToken = req.getParameter("I2P+CSRFTOKEN");

        HttpSession session = req.getSession(true);
        String sessionCSRF = (String) session.getAttribute("loginCSRF");
        if (sessionCSRF == null || csrfToken == null || !csrfToken.equals(sessionCSRF)) {
            _log.warn("CSRF validation failed or session expired — re-displaying form");
            String newCsrfToken = Long.toString(System.currentTimeMillis()) + "-" + UUID.randomUUID().toString();
            session.setAttribute("loginCSRF", newCsrfToken);
            req.setAttribute("I2P+CSRFTOKEN", newCsrfToken);
            req.setAttribute("error", "Session expired, please try again");
            req.setAttribute("theme", getLoginTheme());
            boolean hasPasswords = isPasswordConfigured();
            req.setAttribute("setupMode", !hasPasswords);
            if (!hasPasswords) {
                req.setAttribute("setupTitle", "Set Up Console Access");
                req.setAttribute("setupMessage", "Create a username and password to access the router console.");
            }
            req.getRequestDispatcher("/login.jsp").forward(req, resp);
            return;
        }
        session.removeAttribute("loginCSRF");

        boolean hasPasswords = isPasswordConfigured();
        if (!hasPasswords) {
            if (username == null || password == null || confirmPassword == null ||
                username.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
                req.setAttribute("error", "Username, password and confirmation required");
                req.setAttribute("theme", getLoginTheme());
                req.setAttribute("setupMode", true);
                req.setAttribute("setupTitle", "Set Up Console Access");
                req.setAttribute("setupMessage", "Create a username and password to access the router console.");
                req.getRequestDispatcher("/login.jsp").forward(req, resp);
                return;
            }
            username = username.trim();
            if (!password.equals(confirmPassword)) {
                req.setAttribute("error", "Passwords do not match");
                req.setAttribute("theme", getLoginTheme());
                req.setAttribute("setupMode", true);
                req.setAttribute("setupTitle", "Set Up Console Access");
                req.setAttribute("setupMessage", "Create a username and password to access the router console.");
                req.getRequestDispatcher("/login.jsp").forward(req, resp);
                return;
            }
            if (password.length() < 8) {
                req.setAttribute("error", "Password must be at least 8 characters");
                req.setAttribute("theme", getLoginTheme());
                req.setAttribute("setupMode", true);
                req.setAttribute("setupTitle", "Set Up Console Access");
                req.setAttribute("setupMessage", "Create a username and password to access the router console.");
                req.getRequestDispatcher("/login.jsp").forward(req, resp);
                return;
            }
            I2PAppContext ctx = I2PAppContext.getGlobalContext();
            if (ctx instanceof RouterContext) {
                RouterContext rctx = (RouterContext) ctx;
                ConsolePasswordManager mgr = new ConsolePasswordManager(rctx);
                if (mgr.saveMD5(RouterConsoleRunner.PROP_CONSOLE_PW, REALM, username, password)) {
                    _log.info("Password set for user: " + username);
                    req.setAttribute("success", "Password set successfully. Please log in.");
                    req.setAttribute("theme", getLoginTheme());
                    String newCsrfToken = Long.toString(System.currentTimeMillis()) + "-" + UUID.randomUUID().toString();
                    req.setAttribute("I2P+CSRFTOKEN", newCsrfToken);
                    req.getSession(true).setAttribute("loginCSRF", newCsrfToken);
                    req.getRequestDispatcher("/login.jsp").forward(req, resp);
                    return;
                } else {
                    req.setAttribute("error", "Failed to save password");
                    req.setAttribute("theme", getLoginTheme());
                    req.setAttribute("setupMode", true);
                    req.setAttribute("setupTitle", "Set Up Console Access");
                    req.setAttribute("setupMessage", "Create a username and password to access the router console.");
                    req.getRequestDispatcher("/login.jsp").forward(req, resp);
                    return;
                }
            }
        }

        if (username == null || password == null || username.isEmpty() || password.isEmpty()) {
            req.setAttribute("error", "Username and password required");
            req.setAttribute("theme", getLoginTheme());
            req.getRequestDispatcher("/login.jsp").forward(req, resp);
            return;
        }

        username = username.trim();

        String clientIp = getClientIP(req);
        SessionManager sm = SessionManager.getInstance();

        if (sm.isBlocked(clientIp)) {
            _log.warn("Login blocked due to too many failed attempts from: " + clientIp);
            req.setAttribute("error", "Too many failed attempts. Please try again later.");
            req.setAttribute("theme", getLoginTheme());
            req.getRequestDispatcher("/login.jsp").forward(req, resp);
            return;
        }

        if (password.length() < 8) {
            req.setAttribute("error", "Password must be at least 8 characters");
            req.setAttribute("theme", getLoginTheme());
            resp.sendRedirect(req.getContextPath() + "/login?error=password_short");
            return;
        }

        boolean verified = verifyPassword(username, password);

        if (verified) {
            sm.clearFailedLogins(clientIp);
            long expiryMs = parseDuration(duration);
            String sessionToken = sm.createSession(username, expiryMs);
            if (duration != null && !duration.isEmpty()) {
                persistSession(sessionToken, username, expiryMs);
            }
            String cookieHeader = SessionManager.SESSION_COOKIE_NAME + "=" + sessionToken + "; Path=/; HttpOnly; SameSite=Lax";
            if (req.isSecure()) cookieHeader += "; Secure";
            if (expiryMs > 0) cookieHeader += "; Max-Age=" + (expiryMs / 1000);
            resp.addHeader("Set-Cookie", cookieHeader);
            String redirect = req.getParameter("redirect");
            if (redirect != null && !redirect.isEmpty()) {
                // URL-decode to defeat encoding tricks, then validate
                String decoded;
                try {
                    decoded = URLDecoder.decode(redirect, "UTF-8");
                } catch (Exception e) {
                    decoded = redirect;
                }
                if (!decoded.contains("\r") && !decoded.contains("\n")
                    && decoded.startsWith("/")
                    && !decoded.startsWith("//")
                    && !decoded.contains("://")) {
                    resp.sendRedirect(req.getContextPath() + decoded);
                } else {
                    resp.sendRedirect(req.getContextPath() + "/");
                }
            } else {
                resp.sendRedirect(req.getContextPath() + "/");
            }
        } else {
            sm.recordFailedLogin(clientIp);
            req.setAttribute("error", "Invalid username or password");
            req.setAttribute("theme", getLoginTheme());
            req.getRequestDispatcher("/login.jsp").forward(req, resp);
        }
        } catch (Exception e) {
            _log.error("Login page request failed", e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.getWriter().write("Login failed. Please try again later.");
        }
    }

    /**
     * Parses a session duration like 7d.
     *
     * @param duration the text from the form
     * @return the duration, in ms
     */
    private long parseDuration(String duration) {
        if (duration == null || duration.isEmpty()) {
            return 60 * (long) 60 * 1000; // default 1 hour
        }
        switch (duration) {
            case "15m": return 15 * (long) 60 * 1000;
            case "1h":  return 60 * (long) 60 * 1000;
            case "4h":  return 4 * 60 * (long) 60 * 1000;
            case "8h":  return 8 * 60 * (long) 60 * 1000;
            case "1d":  return 24 * 60 * (long) 60 * 1000;
            case "forever": return -1;
            default: return 60 * (long) 60 * 1000;
        }
    }

    /**
     * Returns the client address of the request.
     *
     * @param req the request to read the client address from
     * @return the address text
     */
    private String getClientIP(HttpServletRequest req) {
        boolean trust = false;
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        if (ctx instanceof RouterContext)
            trust = Boolean.parseBoolean(
                    ((RouterContext) ctx).getProperty(PROP_TRUST_FORWARDED_FOR));
        return clientIP(req.getHeader("X-Forwarded-For"),
                        req.getHeader("X-Real-IP"),
                        req.getRemoteAddr(),
                        trust);
    }

    /**
     * Choose the address the login blocklist keys on.
     * <p>
     * The peer address of the connection is the default. The forwarding headers
     * are attacker-controlled on a directly-reachable console, and honouring them
     * let every attempt with a fresh X-Forwarded-For value look like a new client,
     * so the blocklist never engaged and its map grew one entry per spoofed
     * address. They are read only when an operator has opted in via
     * {@link #PROP_TRUST_FORWARDED_FOR}, having put a real proxy in front.
     * <p>
     * That proxy must OVERWRITE the forwarding header with the address it saw -
     * nginx {@code X-Real-IP} from {@code $remote_addr}, for example. A proxy that
     * appends instead (nginx {@code $proxy_add_x_forwarded_for}, Apache's default)
     * leaves the client's own text as the leftmost entry, which is exactly the
     * fresh-identity bypass the header is not trusted for by default. A value
     * that is not an IP literal is ignored outright, so a hostile header can
     * neither key the blocklist on text of the sender's choosing nor reach the log
     * line verbatim.
     *
     * @param forwardedFor the X-Forwarded-For header, a comma-separated chain, or null
     * @param realIp the X-Real-IP header, or null
     * @param remoteAddr the peer address of the connection, never null in practice
     * @param trustForwarded true only when a trusted reverse proxy fronts the console
     * @return the address to rate-limit, never null or empty
     * @since 0.9.72+
     */
    static String clientIP(String forwardedFor, String realIp, String remoteAddr,
                           boolean trustForwarded) {
        if (trustForwarded) {
            String ip = firstForwarded(forwardedFor);
            if (ip == null)
                ip = firstForwarded(realIp);
            if (ip != null && Addresses.isIPAddress(ip))
                return ip;
        }
        String ip = remoteAddr;
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip))
            ip = "unknown";
        return ip;
    }

    /**
     * The client address from a forwarding header: the leftmost entry of an
     * X-Forwarded-For chain, since later entries are appended by proxies further
     * along and the first is the one the nearest proxy saw.
     *
     * @param header the raw header value, or null
     * @return the trimmed address, or null if the header is absent, blank, or the
     *         literal "unknown" some proxies send
     * @since 0.9.72+
     */
    static String firstForwarded(String header) {
        if (header == null)
            return null;
        int comma = header.indexOf(',');
        String ip = (comma >= 0 ? header.substring(0, comma) : header).trim();
        if (ip.isEmpty() || "unknown".equalsIgnoreCase(ip))
            return null;
        return ip;
    }

    /**
     * Checks the submitted password against the stored hashes.
     *
     * @param username the user name from the form
     * @param password the password from the form
     * @return true if the password matches
     */
    private boolean verifyPassword(String username, String password) {
        I2PAppContext appCtx = I2PAppContext.getGlobalContext();
        if (!(appCtx instanceof RouterContext)) {
            _log.error("Global context is not a RouterContext: " + appCtx.getClass().getName());
            return false;
        }
        RouterContext ctx = (RouterContext) appCtx;
        Router router = ctx.router();
        if (router == null) {
            _log.error("Router is null in RouterContext");
            return false;
        }
        if (!router.isAlive() || !router.isRunning()) {
            _log.error("Router is not alive or running");
            return false;
        }
        if (_log.shouldDebug()) {_log.debug("Router context obtained, config file: " + router.getConfigFilename());}

        ConsolePasswordManager mgr = new ConsolePasswordManager(ctx);
        if (_log.shouldDebug()) {_log.debug("Verifying password for user: " + username);}

        boolean result = mgr.checkMD5(RouterConsoleRunner.PROP_CONSOLE_PW, REALM, username, password);
        if (_log.shouldDebug()) {_log.debug("MD5 check result: " + result + " for user: " + username);}

        if (result) {
            if (_log.shouldDebug()) {_log.debug("SUCCESS - user authenticated via MD5");}
            return true;
        }

        String pbkdf2Prop = RouterConsoleRunner.PROP_CONSOLE_PW + "." + username + ".pbkdf2";
        if (_log.shouldDebug()) {_log.debug("Looking for PBKDF2 at: " + pbkdf2Prop);}
        String pbkdf2Hash = ctx.router().getConfigMap().get(pbkdf2Prop);
        if (_log.shouldDebug()) {_log.debug("PBKDF2 property " + pbkdf2Prop + " = " + (pbkdf2Hash != null ? "present" : "null"));}
        if (pbkdf2Hash != null && !pbkdf2Hash.isEmpty()) {
            if (_log.shouldDebug()) {_log.debug("Checking PBKDF2 for user: " + username);}
            try {
                String[] parts = COLON_SPLIT.split(pbkdf2Hash);
                if (parts.length >= 3) {
                    int iterations = Integer.parseInt(parts[0]);
                    byte[] salt = Base64.decode(parts[1]);
                    byte[] storedHash = Base64.decode(parts[2]);
                    PBEKeySpec spec = new PBEKeySpec(
                        password.toCharArray(), salt, iterations, 256);
                    SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                    byte[] computedHash = skf.generateSecret(spec).getEncoded();
                    result = DataHelper.eq(storedHash, computedHash);
                    if (_log.shouldDebug()) {_log.debug("PBKDF2 verification result: " + result);}
                }
            } catch (Exception e) {
                _log.error("PBKDF2 verification error", e);
            }
        }

        if (result) {
            return true;
        }

        if (!result && DataHelper.eqCT("admin", username) && DataHelper.eqCT("password", password)) {
            boolean hasAnyPassword = hasAnyPassword(ctx);
            if (_log.shouldDebug()) {_log.debug("Default credentials rejected -> A password is configured: " + hasAnyPassword);}
            if (!hasAnyPassword) {
                if (_log.shouldDebug()) {_log.debug("No passwords configured, allowing default");}
                return true;
            }
        }
        return result;
    }

    /**
     * Returns the persisted session cookie value.
     *
     * @param req the request to read the cookie from
     * @return the cookie value, null if absent
     */
    private String getSessionCookie(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (SessionManager.SESSION_COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * Saves a session to survive a restart.
     *
     * @param token the session token
     * @param username the user name
     * @param expiryMs the expiry time, in ms since epoch
     */
    private void persistSession(String token, String username, long expiryMs) {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        if (!(ctx instanceof RouterContext)) return;
        RouterContext rctx = (RouterContext) ctx;
        Router router = rctx.router();

        long now = System.currentTimeMillis();
        long expiresAt = expiryMs > 0 ? now + expiryMs : -1;

        synchronized (_SESSION_LIST_LOCK) {
            String existing = rctx.getProperty(PROP_PERSISTED_SESSIONS);
            router.saveConfig(PROP_PERSISTED_SESSIONS,
                              addPersistedSession(existing, token, username, expiresAt, now));
        }
    }

    /**
     * Rebuild the persisted-session list with one session added.
     * <p>
     * The list used to be append-only: every login wrote a new entry, nothing
     * ever removed one, and the whole value was rewritten each time - so a
     * router left running accumulated a session token per login in router.config
     * for ever, including ones that had expired and were never loadable again.
     * This drops expired and unreadable entries, replaces an existing entry for
     * the same token instead of adding a second, and caps the list so it cannot
     * grow without bound.
     * <p>
     * Pure and static so a test can drive a hostile stored value without a
     * router.
     *
     * @param existing the stored value, or null
     * @param token the session token being added
     * @param username who the session belongs to
     * @param expiresAt when it expires in ms, or -1 for no expiry
     * @param now current time in ms
     * @return the value to store, entries joined with '|'
     * @since 0.9.72+
     */
    static String addPersistedSession(String existing, String token, String username,
                                      long expiresAt, long now) {
        List<String> kept = new ArrayList<>();
        if (existing != null && !existing.isEmpty()) {
            for (String entry : PIPE_SPLIT.split(existing)) {
                if (entry.isEmpty()) {continue;}
                int eq = entry.indexOf('=');
                if (eq <= 0) {continue;}
                if (token.equals(entry.substring(0, eq))) {continue;}
                String data = entry.substring(eq + 1);
                int colon = data.indexOf(':');
                if (colon <= 0) {continue;}
                try {
                    long exp = Long.parseLong(data.substring(colon + 1));
                    if (exp > 0 && exp <= now) {continue;}
                } catch (NumberFormatException e) {
                    // Unreadable expiry: the loader would skip it too, so drop it
                    // rather than carry it forward for the router's lifetime.
                    continue;
                }
                kept.add(entry);
            }
        }
        // Entries are appended newest last, so trimming the front drops the
        // oldest survivors and keeps the cap on the list's total size.
        int overflow = kept.size() - (MAX_PERSISTED_SESSIONS - 1);
        if (overflow > 0) {
            kept = kept.subList(overflow, kept.size());
        }
        kept.add(token + "=" + username + ':' + expiresAt);
        return String.join("|", kept);
    }

    /** Loads the saved sessions from the config. */
    private void loadPersistedSessions() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        if (!(ctx instanceof RouterContext)) return;
        RouterContext rctx = (RouterContext) ctx;

        String persisted = rctx.getProperty(PROP_PERSISTED_SESSIONS);
        if (persisted == null || persisted.isEmpty()) return;

        long now = System.currentTimeMillis();
        SessionManager sm = SessionManager.getInstance();
        for (String entry : PIPE_SPLIT.split(persisted)) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String data = entry.substring(eq + 1);

            int colon = data.indexOf(':');
            if (colon <= 0) continue;
            String username = data.substring(0, colon);
            try {
                long expiresAt = Long.parseLong(data.substring(colon + 1));
                if (expiresAt > 0 && expiresAt <= now) continue;
                String token = entry.substring(0, eq);
                sm.restoreSession(token, username, expiresAt);
                if (_log.shouldDebug()) {_log.debug("Loaded persisted session for user: " + username);}
            } catch (NumberFormatException e) {
                _log.warn("Invalid persisted session data (length: " + data.length() + ")");
            }
        }
    }

    /**
     * Generates a random CSRF token.
     *
     * @return the token, hex
     */
    private String generateCSRFToken() {
        byte[] randomBytes = new byte[24];
        CSRF_RANDOM.nextBytes(randomBytes);
        return DataHelper.toHexString(randomBytes);
    }

    /**
     * Writes one console preference to the config.
     *
     * @param key the preference name
     * @param value the preference value
     */
    private void updatePreference(String key, String value) {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        if (!(ctx instanceof RouterContext)) return;
        RouterContext rctx = (RouterContext) ctx;
        Router router = rctx.router();
        router.saveConfig(key, value);
        if (_log.shouldDebug()) {_log.debug("Preference updated: " + key + "=" + value);}
    }
}
