package net.i2p.router.web.servlets;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Map;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.i2p.I2PAppContext;
import net.i2p.router.Router;
import net.i2p.router.web.CSSHelper;
import net.i2p.router.web.ConsolePasswordManager;
import net.i2p.router.web.RouterConsoleRunner;
import net.i2p.servlet.filters.SessionManager;
import net.i2p.util.Log;
import net.i2p.data.DataHelper;

/**
 * Login servlet for console authentication.
 */
public class LoginServlet extends HttpServlet {
    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(LoginServlet.class);

    @Override
    public void init() throws ServletException {
        _log.info("=== LoginServlet.init() CALLED ===");
        loadPersistedSessions();
    }

    private static final String PARAM_USERNAME = "username";
    private static final String PARAM_PASSWORD = "password";
    private static final String REALM = "i2prouter";
    private static final String DEFAULT_THEME = "dark";
    private static final String PROP_PERSISTED_SESSIONS = "routerconsole.persistedSessions";
    private static final String PROP_THEME = "routerconsole.theme";
    private static final String PROP_LANG = "routerconsole.lang";
    private static final SecureRandom CSRF_RANDOM = new SecureRandom();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        boolean routerReady = false;
        if (ctx instanceof net.i2p.router.RouterContext) {
            net.i2p.router.Router router = ((net.i2p.router.RouterContext) ctx).router();
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
        if (ctx instanceof net.i2p.router.RouterContext) {
            enforceLogin = ((net.i2p.router.RouterContext) ctx).getBooleanPropertyDefaultTrue("routerconsole.enforceLogin");
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

    private boolean isPasswordConfigured() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        if (!(ctx instanceof net.i2p.router.RouterContext)) return false;
        return hasAnyPassword((net.i2p.router.RouterContext) ctx);
    }

    private boolean hasAnyPassword(net.i2p.router.RouterContext ctx) {
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

    private String getLoginTheme() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        if (ctx instanceof net.i2p.router.RouterContext) {
            String theme = ((net.i2p.router.RouterContext) ctx).getProperty(CSSHelper.PROP_THEME_NAME, CSSHelper.DEFAULT_THEME);
            if (theme != null && !theme.isEmpty()) {
                return theme;
            }
        }
        return DEFAULT_THEME;
    }

    private static final java.util.Set<String> ALLOWED_THEMES =
            java.util.Collections.unmodifiableSet(
                new java.util.HashSet<>(java.util.Arrays.asList("dark", "classic", "light", "midnight")));
    private static final java.util.Set<String> ALLOWED_LANGS =
            java.util.Collections.unmodifiableSet(
                new java.util.HashSet<>(java.util.Arrays.asList(
                    "ar", "az", "cs", "zh", "da", "de", "et", "en",
                    "es", "fi", "fr", "el", "hi", "hu", "in", "it",
                    "ja", "ko", "nl", "nb", "fa", "pl", "pt", "ro",
                    "ru", "sl", "sv", "bo", "tr", "vi")));

    private static String sanitizeTheme(String theme) {
        if (theme != null && ALLOWED_THEMES.contains(theme)) return theme;
        return "dark";
    }

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
                net.i2p.data.DataHelper.escapeHTML(theme) + "\"}");
            return;
        }
        if (langRaw != null) {
            String lang = sanitizeLang(langRaw);
            updatePreference("routerconsole.lang", lang);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"success\":true,\"lang\":\"" +
                net.i2p.data.DataHelper.escapeHTML(lang) + "\"}");
            return;
        }

        try {
        String username = req.getParameter(PARAM_USERNAME);
        String password = req.getParameter(PARAM_PASSWORD);
        String confirmPassword = req.getParameter("confirmPassword");
        String duration = req.getParameter("duration");
        String csrfToken = req.getParameter("I2P+CSRFTOKEN");

        javax.servlet.http.HttpSession session = req.getSession(false);
        if (session == null) {
            _log.warn("No session for login request");
            resp.sendRedirect(req.getContextPath() + "/login?error=session_expired");
            return;
        }
        String sessionCSRF = (String) session.getAttribute("loginCSRF");
        if (sessionCSRF == null || csrfToken == null || !csrfToken.equals(sessionCSRF)) {
            _log.warn("CSRF validation failed");
            resp.sendRedirect(req.getContextPath() + "/login?error=csrf_invalid");
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
            if (ctx instanceof net.i2p.router.RouterContext) {
                net.i2p.router.RouterContext rctx = (net.i2p.router.RouterContext) ctx;
                ConsolePasswordManager mgr = new ConsolePasswordManager(rctx);
                if (mgr.saveMD5(RouterConsoleRunner.PROP_CONSOLE_PW, REALM, username, password)) {
                    _log.info("Password set for user: " + username);
                    req.setAttribute("success", "Password set successfully. Please log in.");
                    req.setAttribute("theme", getLoginTheme());
                    String newCsrfToken = Long.toString(System.currentTimeMillis()) + "-" + java.util.UUID.randomUUID().toString();
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
                    decoded = java.net.URLDecoder.decode(redirect, "UTF-8");
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
            _log.error("LoginServlet doPost exception", e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.getWriter().write("Login failed. Please try again later.");
        }
    }

    private long parseDuration(String duration) {
        if (duration == null || duration.isEmpty()) {
            return 60 * 60 * 1000; // default 1 hour
        }
        switch (duration) {
            case "15m": return 15 * 60 * 1000;
            case "1h":  return 60 * 60 * 1000;
            case "4h":  return 4 * 60 * 60 * 1000;
            case "8h":  return 8 * 60 * 60 * 1000;
            case "1d":  return 24 * 60 * 60 * 1000;
            case "forever": return -1;
            default: return 60 * 60 * 1000;
        }
    }

    private String getClientIP(HttpServletRequest req) {
        String ip = req.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = req.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = req.getRemoteAddr();
        }
        return ip;
    }

    private boolean verifyPassword(String username, String password) {
        I2PAppContext appCtx = I2PAppContext.getGlobalContext();
        if (!(appCtx instanceof net.i2p.router.RouterContext)) {
            _log.error("Global context is not a RouterContext: " + appCtx.getClass().getName());
            return false;
        }
        net.i2p.router.RouterContext ctx = (net.i2p.router.RouterContext) appCtx;
        net.i2p.router.Router router = ctx.router();
        if (router == null) {
            _log.error("Router is null in RouterContext");
            return false;
        }
        if (!router.isAlive() || !router.isRunning()) {
            _log.error("Router is not alive or running");
            return false;
        }
        _log.info("Router context obtained, config file: " + router.getConfigFilename());

        ConsolePasswordManager mgr = new ConsolePasswordManager(ctx);
        _log.info("verifyPassword for user: " + username);

boolean result = mgr.checkMD5(RouterConsoleRunner.PROP_CONSOLE_PW, REALM, username, password);
        _log.info("checkMD5 result: " + result + " for user: " + username);

        if (result) {
            _log.info("SUCCESS - user authenticated via MD5");
            return true;
        }

        String pbkdf2Prop = RouterConsoleRunner.PROP_CONSOLE_PW + "." + username + ".pbkdf2";
        _log.info("Looking for PBKDF2 at: " + pbkdf2Prop);
        String pbkdf2Hash = ctx.router().getConfigMap().get(pbkdf2Prop);
        _log.info("PBKDF2 property " + pbkdf2Prop + " = " + (pbkdf2Hash != null ? "present" : "null"));
        if (pbkdf2Hash != null && !pbkdf2Hash.isEmpty()) {
            _log.info("Checking PBKDF2 for user: " + username);
            try {
                String[] parts = pbkdf2Hash.split(":");
                if (parts.length >= 3) {
                    int iterations = Integer.parseInt(parts[0]);
                    byte[] salt = net.i2p.data.Base64.decode(parts[1]);
                    byte[] storedHash = net.i2p.data.Base64.decode(parts[2]);
                    javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                        password.toCharArray(), salt, iterations, 256);
                    javax.crypto.SecretKeyFactory skf = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                    byte[] computedHash = skf.generateSecret(spec).getEncoded();
                    result = net.i2p.data.DataHelper.eq(storedHash, computedHash);
                    _log.info("PBKDF2 verification result: " + result);
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
            _log.info("admin/password failed but hasAnyPassword: " + hasAnyPassword);
            if (!hasAnyPassword) {
                _log.info("No passwords configured, allowing default");
                return true;
            }
        }
        return result;
    }

    private String getSessionCookie(HttpServletRequest req) {
        javax.servlet.http.Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (javax.servlet.http.Cookie cookie : cookies) {
            if (SessionManager.SESSION_COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void persistSession(String token, String username, long expiryMs) {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        if (!(ctx instanceof net.i2p.router.RouterContext)) return;
        net.i2p.router.RouterContext rctx = (net.i2p.router.RouterContext) ctx;
        Router router = rctx.router();

        long expiresAt = expiryMs > 0 ? System.currentTimeMillis() + expiryMs : -1;
        String sessionData = username + ":" + expiresAt;

        String existing = rctx.getProperty(PROP_PERSISTED_SESSIONS);
        StringBuilder sb = new StringBuilder();
        if (existing != null && !existing.isEmpty()) {
            sb.append(existing).append("|");
        }
        sb.append(token).append("=").append(sessionData);
        router.saveConfig(PROP_PERSISTED_SESSIONS, sb.toString());
    }

private void loadPersistedSessions() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        if (!(ctx instanceof net.i2p.router.RouterContext)) return;
        net.i2p.router.RouterContext rctx = (net.i2p.router.RouterContext) ctx;

        String persisted = rctx.getProperty(PROP_PERSISTED_SESSIONS);
        if (persisted == null || persisted.isEmpty()) return;

        long now = System.currentTimeMillis();
        SessionManager sm = SessionManager.getInstance();
        for (String entry : persisted.split("\\|")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String token = entry.substring(0, eq);
            String data = entry.substring(eq + 1);

            int colon = data.indexOf(':');
            if (colon <= 0) continue;
            String username = data.substring(0, colon);
            try {
                long expiresAt = Long.parseLong(data.substring(colon + 1));
                if (expiresAt > 0 && expiresAt <= now) continue;
                sm.createSession(username, expiresAt > 0 ? expiresAt - now : -1);
                _log.info("Loaded persisted session for user: " + username);
            } catch (NumberFormatException e) {
                _log.warn("Invalid persisted session data: " + data);
            }
        }
    }

    private String generateCSRFToken() {
        byte[] randomBytes = new byte[24];
        CSRF_RANDOM.nextBytes(randomBytes);
        return net.i2p.data.DataHelper.toHexString(randomBytes);
    }

    private void updatePreference(String key, String value) {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        if (!(ctx instanceof net.i2p.router.RouterContext)) return;
        net.i2p.router.RouterContext rctx = (net.i2p.router.RouterContext) ctx;
        Router router = rctx.router();
        router.saveConfig(key, value);
        _log.info("Preference updated: " + key + "=" + value);
    }
}
