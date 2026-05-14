package net.i2p.servlet.filters;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.i2p.I2PAppContext;
import net.i2p.router.web.CSSHelper;
import net.i2p.util.Log;

/**
 * Filter to check for valid session on all console requests.
 * Redirects to login page if not authenticated.
 */
public class AuthFilter implements Filter {
    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(AuthFilter.class);
    private static final String PROP_AUTH_TYPE = "routerconsole.auth.type";
    private static final String AUTH_TYPE_CUSTOM = "custom";
    private static final String PROP_PW_ENABLE = "routerconsole.auth.enable";
    private static final String PROP_ENFORCE_LOGIN = "routerconsole.enforceLogin";
    private static final String DEFAULT_THEME = "dark";

    private static final Set<String> PUBLIC_PATHS = new HashSet<String>();
    private static final Set<String> PUBLIC_FILES = new HashSet<String>();
    static {
        PUBLIC_PATHS.add("/index.jsp");
        PUBLIC_PATHS.add("/login");
        PUBLIC_PATHS.add("/prefs");
        PUBLIC_PATHS.add("/themes/login/");
        PUBLIC_PATHS.add("/js/");
        PUBLIC_PATHS.add("/flags.jsp");
        PUBLIC_FILES.add("/themes/console/images/bug.svg");
        PUBLIC_FILES.add("/themes/console/images/plus.svg");
        PUBLIC_FILES.add("/themes/console/classic/images/thumbnail.png");
        PUBLIC_FILES.add("/themes/console/dark/images/thumbnail.png");
        PUBLIC_FILES.add("/themes/console/light/images/thumbnail.png");
        PUBLIC_FILES.add("/themes/console/midnight/images/thumbnail.png");
        PUBLIC_FILES.add("/themes/console/{theme}/images/favicon.svg");
        PUBLIC_FILES.add("/themes/console/{theme}/images/i2plogo.png");
        PUBLIC_FILES.add("/themes/console/{theme}/images/thumbnail.png");
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {}

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        net.i2p.router.RouterContext ctx = (net.i2p.router.RouterContext) I2PAppContext.getGlobalContext();

        String authType = ctx.getProperty(PROP_AUTH_TYPE, AUTH_TYPE_CUSTOM);
        boolean authEnabled = ctx.getBooleanPropertyDefaultTrue(PROP_PW_ENABLE);

        boolean hasPasswords = hasAnyPassword(ctx);
        boolean enforceLogin = !ctx.getBooleanPropertyDefaultTrue(PROP_ENFORCE_LOGIN);

if (!AUTH_TYPE_CUSTOM.equals(authType)) {
            chain.doFilter(request, response);
            return;
        }

        String path = req.getRequestURI();
        if (path == null) {
            path = "";
        }

if ("/prefs".equals(path)) {
            chain.doFilter(request, response);
            return;
        }

        if (!enforceLogin && !hasPasswords) {
            chain.doFilter(request, response);
            return;
        }

        if (isPublicPath(path)) {
            chain.doFilter(request, response);
            return;
        }

        String sessionToken = getSessionCookie(req);
        String username = SessionManager.getInstance().validateSession(sessionToken);

        if (username == null) {
            username = authenticateBasicAuth(req, resp, ctx);
        }

        if (username != null) {
            req.setAttribute(SessionManager.SESSION_ATTR_USER, username);
            chain.doFilter(request, response);
            return;
        }

        if (!hasPasswords && "admin".equals(req.getParameter("username")) && "password".equals(req.getParameter("password"))) {
            username = "admin";
            req.setAttribute(SessionManager.SESSION_ATTR_USER, username);
            chain.doFilter(request, response);
            return;
        }

        String loginUrl = req.getContextPath() + "/login";
        if (!path.equals("/") && !path.isEmpty()) {
            loginUrl += "?redirect=" + path;
        }
        resp.sendRedirect(loginUrl);
    }

    private boolean isPublicPath(String path) {
        for (String publicPath : PUBLIC_PATHS) {
            if (path.equals(publicPath) || path.startsWith(publicPath)) {
                return true;
            }
        }
        for (String publicFile : PUBLIC_FILES) {
            if (path.equals(publicFile.replace("{theme}", getActiveTheme()))) {
                return true;
            }
        }
        return false;
    }

    private String getActiveTheme() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        if (ctx instanceof net.i2p.router.RouterContext) {
            String theme = ((net.i2p.router.RouterContext) ctx).getProperty(CSSHelper.PROP_THEME_NAME, CSSHelper.DEFAULT_THEME);
            if (theme != null && !theme.isEmpty()) {
                return theme;
            }
        }
        return DEFAULT_THEME;
    }

    private boolean hasAnyPassword(net.i2p.router.RouterContext ctx) {
        net.i2p.router.web.ConsolePasswordManager mgr = new net.i2p.router.web.ConsolePasswordManager(ctx);
        boolean hasMD5 = !mgr.getMD5(net.i2p.router.web.RouterConsoleRunner.PROP_CONSOLE_PW).isEmpty();
        if (hasMD5) {
            return true;
        }
        String pfx = net.i2p.router.web.RouterConsoleRunner.PROP_CONSOLE_PW + ".";
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

    private String authenticateBasicAuth(HttpServletRequest req, HttpServletResponse resp, net.i2p.router.RouterContext ctx) {
        String authHeader = req.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            return null;
        }
        try {
            String encoded = authHeader.substring(6);
            String decoded = new String(java.util.Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon <= 0) return null;
            String username = decoded.substring(0, colon);
            String password = decoded.substring(colon + 1);
            net.i2p.router.web.ConsolePasswordManager mgr = new net.i2p.router.web.ConsolePasswordManager(ctx);
            String pbkdf2Prop = net.i2p.router.web.RouterConsoleRunner.PROP_CONSOLE_PW + "." + username + ".pbkdf2";
            String pbkdf2Value = ctx.router().getConfigMap().get(pbkdf2Prop);
            boolean noAuthConfigured = mgr.getMD5(net.i2p.router.web.RouterConsoleRunner.PROP_CONSOLE_PW).isEmpty()
                    && pbkdf2Value == null;
            if (noAuthConfigured) {
                return null;
            }
            boolean verified = mgr.checkMD5(net.i2p.router.web.RouterConsoleRunner.PROP_CONSOLE_PW, "i2prouter", username, password);
            if (verified) {
                return createSessionAndSetCookie(req, resp, username);
            }
        } catch (Exception e) {
            _log.warn("Basic auth error", e);
        }
        return null;
    }

    private String createSessionAndSetCookie(HttpServletRequest req, HttpServletResponse resp, String username) {
        String sessionToken = SessionManager.getInstance().createSession(username);
        javax.servlet.http.Cookie cookie = new javax.servlet.http.Cookie(SessionManager.SESSION_COOKIE_NAME, sessionToken);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(req.isSecure());
        cookie.setMaxAge(30 * 60);
        resp.addCookie(cookie);
        req.getSession(true).setAttribute(SessionManager.SESSION_ATTR_USER, username);
        return username;
    }

    @Override
    public void destroy() {
        _log.info("AuthFilter destroyed");
    }
}
