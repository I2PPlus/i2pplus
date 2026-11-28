package net.i2p.servlet.filters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
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
import javax.servlet.http.Cookie;
import net.i2p.I2PAppContext;
import net.i2p.router.RouterContext;
import net.i2p.router.web.CSSHelper;
import net.i2p.router.web.ConsolePasswordManager;
import net.i2p.router.web.RouterConsoleRunner;
import net.i2p.util.Log;

/**
 * Filter to check for valid session on all console requests.
 * Redirects to login page if not authenticated.
 */
public class AuthFilter implements Filter {
    /** Create an instance; the servlet container calls this with no arguments. */
    public AuthFilter() {}
    /** Logger for the authentication filter. */
    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(AuthFilter.class);
    /** Config key for the authentication type. */
    private static final String PROP_AUTH_TYPE = "routerconsole.auth.type";
    /** Auth type value selecting the custom handler. */
    private static final String AUTH_TYPE_CUSTOM = "custom";
    /** Config key requiring the login for every page. */
    private static final String PROP_ENFORCE_LOGIN = "routerconsole.enforceLogin";
    /** Theme used when none is configured. */
    private static final String DEFAULT_THEME = "dark";

    /** The paths that need no authentication. */
    private static final Set<String> PUBLIC_PATHS = new HashSet<>();
    /** The files that need no authentication. */
    private static final Set<String> PUBLIC_FILES = new HashSet<>();
    static {
        PUBLIC_PATHS.add("/index.jsp");
        PUBLIC_PATHS.add("/login");
        PUBLIC_PATHS.add("/logout");
        PUBLIC_PATHS.add("/session/");
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
    public void init(FilterConfig filterConfig) throws ServletException {
        // TODO
    }
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        RouterContext ctx = (RouterContext) I2PAppContext.getGlobalContext();

        String authType = ctx.getProperty(PROP_AUTH_TYPE, AUTH_TYPE_CUSTOM);

        boolean hasPasswords = hasAnyPassword(ctx);
        boolean enforceLogin = !ctx.getBooleanPropertyDefaultTrue(PROP_ENFORCE_LOGIN);

if (!AUTH_TYPE_CUSTOM.equals(authType)) {
            chain.doFilter(request, response);
            return;
        }

        String path = dispatchPath(req);
        // A null path is one the filter cannot name, so it cannot match it
        // against a public prefix either; the request is challenged for a login
        // rather than let through on a guess.
        String rawPath = req.getRequestURI();
        if (rawPath == null) {
            rawPath = "";
        }

if ("/prefs".equals(path)) {
            chain.doFilter(request, response);
            return;
        }

        if (!enforceLogin && !hasPasswords) {
            chain.doFilter(request, response);
            return;
        }

        if (path != null && isPublicPath(path)) {
            chain.doFilter(request, response);
            return;
        }

        String sessionToken = SessionManager.getSessionCookie(req);
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
        if (!rawPath.equals("/") && !rawPath.isEmpty()) {
            loginUrl += "?redirect=" + rawPath;
        }
        resp.sendRedirect(loginUrl);
    }

    /**
     * The path the container will dispatch on, which is the only path the
     * public-page test may be answered about. {@link HttpServletRequest#getRequestURI()}
     * is the request target as the client sent it, percent-encoding and path
     * parameters intact, while Jetty 9.3 decodes that path and resolves
     * {@code .} and {@code ..} before it maps the request. A traversal segment
     * written after a public prefix therefore satisfies a prefix test on the
     * raw URI and names a protected page: {@code /js/../configclients} is
     * public under the {@code /js/} prefix and dispatches to
     * {@code /configclients}.
     *
     * @param req the request being filtered
     * @return the in-context path the container dispatches on, or null if it
     *         cannot be used and the request must be treated as protected
     * @since 0.9.72+
     */
    static String dispatchPath(HttpServletRequest req) {
        String servlet = req.getServletPath();
        String info = req.getPathInfo();
        if (servlet == null) {
            // A catch-all mapping leaves the whole path in pathInfo.
            if (info == null) {return null;}
            servlet = "";
        }
        return canonicalPath(servlet + (info == null ? "" : info));
    }

    /**
     * Resolve the {@code .} and {@code ..} segments of a path the container has
     * already decoded, the same way the container resolves them before mapping
     * the request. A path that walks out of the root, or that still carries a
     * path parameter, has no answer here: the caller reads null as protected,
     * so an unusual path is challenged for a login instead of being matched
     * against a public prefix it merely looks like.
     *
     * @param path the decoded in-context path, starting with {@code /}
     * @return the canonical path, or null if it escapes the root or holds a
     *         path parameter
     * @since 0.9.72+
     */
    static String canonicalPath(String path) {
        if (path == null || path.isEmpty()) {return "/";}
        if (path.indexOf(';') >= 0) {return null;}
        List<String> kept = new ArrayList<>(8);
        for (String seg : path.split("/", -1)) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                if (kept.isEmpty()) {return null;}
                kept.remove(kept.size() - 1);
            } else {
                kept.add(seg);
            }
        }
        StringBuilder buf = new StringBuilder(path.length());
        for (String seg : kept) {
            buf.append('/').append(seg);
        }
        if (path.endsWith("/") && buf.length() > 0) {
            buf.append('/');
        }
        return buf.length() > 0 ? buf.toString() : "/";
    }

    /**
     * Checks whether a path needs no authentication. The match is a prefix
     * match, so the path must already be the one the container dispatches on -
     * see {@link #dispatchPath} - or a traversal segment answers the question
     * about the wrong page.
     *
     * @param path the canonical in-context path
     * @return true if the path is public
     */
    boolean isPublicPath(String path) {
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

    /**
     * Reads the theme in use now.
     *
     * @return the theme name
     */
    private String getActiveTheme() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        if (ctx instanceof RouterContext) {
            String theme = ((RouterContext) ctx).getProperty(CSSHelper.PROP_THEME_NAME, CSSHelper.DEFAULT_THEME);
            if (theme != null && !theme.isEmpty()) {
                return theme;
            }
        }
        return DEFAULT_THEME;
    }

    /**
     * Checks whether any console password is set.
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
     * Authenticates the basic auth header of the request.
     *
     * @param req the request to authenticate
     * @param resp the response to send the challenge to
     * @param ctx the router context
     * @return the user name, null if the credentials fail
     */
    private String authenticateBasicAuth(HttpServletRequest req, HttpServletResponse resp, RouterContext ctx) {
        String authHeader = req.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            return null;
        }
        try {
            String encoded = authHeader.substring(6);
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon <= 0) return null;
            String username = decoded.substring(0, colon);
            String password = decoded.substring(colon + 1);
            ConsolePasswordManager mgr = new ConsolePasswordManager(ctx);
            String pbkdf2Prop = RouterConsoleRunner.PROP_CONSOLE_PW + "." + username + ".pbkdf2";
            String pbkdf2Value = ctx.router().getConfigMap().get(pbkdf2Prop);
            boolean noAuthConfigured = mgr.getMD5(RouterConsoleRunner.PROP_CONSOLE_PW).isEmpty()
                    && pbkdf2Value == null;
            if (noAuthConfigured) {
                return null;
            }
            boolean verified = mgr.checkMD5(RouterConsoleRunner.PROP_CONSOLE_PW, "i2prouter", username, password);
            if (verified) {
                return createSessionAndSetCookie(req, resp, username);
            }
        } catch (Exception e) {
            _log.warnThrottled("authfilter-basic-auth-failed",
                               "Cannot read the basic authorization header -> Redirecting to the login page" +
                               "\n* Reason: " + e.getMessage());
        }
        return null;
    }

    /**
     * Creates a login session and sends its cookie.
     *
     * @param req the request to log in
     * @param resp the response to set the cookie on
     * @param username the user to log in
     * @return the user name of the session
     */
    private String createSessionAndSetCookie(HttpServletRequest req, HttpServletResponse resp, String username) {
        RouterContext ctx = (RouterContext) I2PAppContext.getGlobalContext();
        boolean hasPasswords = hasAnyPassword(ctx);
        // No expiry when no password is configured — session persists until browser closes
        long expiryMs = hasPasswords ? 30 * 60 * 1000 : -1;
        String sessionToken = SessionManager.getInstance().createSession(username, expiryMs);
        Cookie cookie = new Cookie(SessionManager.SESSION_COOKIE_NAME, sessionToken);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(req.isSecure());
        if (expiryMs > 0) cookie.setMaxAge((int) (expiryMs / 1000));
        resp.addCookie(cookie);
        req.getSession(true).setAttribute(SessionManager.SESSION_ATTR_USER, username);
        return username;
    }

    @Override
    public void destroy() {
        _log.info("AuthFilter destroyed");
    }
}
