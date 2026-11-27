package net.i2p.servlet.filters;

import java.io.IOException;
import java.util.Locale;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * Adds SameSite=Lax to all cookies in Set-Cookie headers as defense-in-depth.
 * Primary SameSite is set in web.xml session-config.
 */
public class SameSiteCookieFilter implements Filter {
    /** Logger for this class. */
    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(SameSiteCookieFilter.class);

    /**
     * The filter holds no configuration - {@link #init} is a nop and the logger is static - so a
     * bare instance is ready for the container to call {@link #doFilter} on.
     */
    public SameSiteCookieFilter() {}

    @Override
    public void init(FilterConfig filterConfig) throws ServletException { /* nop */ }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain)
            throws IOException, ServletException {
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        HttpServletResponse wrapped = new HttpServletResponseWrapper(httpResponse) {
            @Override
            public void addHeader(String name, String value) {
                if (name != null && name.equalsIgnoreCase("Set-Cookie")) {
                    if (_log.shouldDebug()) {_log.debug("Set-Cookie header: " + value);}
                    String withSameSite = addSameSite(value);
                    if (!withSameSite.equals(value)) {
                        if (_log.shouldDebug()) {_log.debug("Added SameSite to: " + withSameSite);}
                        value = withSameSite;
                    }
                }
                super.addHeader(name, value);
            }

            @Override
            public void setHeader(String name, String value) {
                if (name != null && name.equalsIgnoreCase("Set-Cookie")) {
                    if (_log.shouldDebug()) {_log.debug("Set-Cookie header (set): " + value);}
                    String withSameSite = addSameSite(value);
                    if (!withSameSite.equals(value)) {
                        if (_log.shouldDebug()) {_log.debug("Added SameSite to: " + withSameSite);}
                        value = withSameSite;
                    }
                }
                super.setHeader(name, value);
            }
        };

        chain.doFilter(request, wrapped);
    }

    /**
     * The Set-Cookie value with {@code SameSite=Lax} added, unless the value
     * already names a SameSite attribute of its own.
     *
     * <p>The attribute is looked for case-insensitively, and that fold names a
     * locale instead of following the machine's. A cookie attribute name is
     * case-insensitive on the wire, so a servlet is free to write it in caps,
     * and "SAMESITE" folds under a Turkish default locale to a dotless ı: the
     * attribute goes unseen, a second one is appended after it, and a browser
     * keeps the last SameSite in a cookie - so this filter's Lax quietly
     * replaces the webapp's None.
     *
     * @param value the Set-Cookie value as the servlet wrote it
     * @return the value to send, with the attribute added when it was missing
     * @since 0.9.72+
     */
    static String addSameSite(String value) {
        if (value.toLowerCase(Locale.US).contains("samesite")) {return value;}
        return value + "; SameSite=Lax";
    }

    @Override
    public void destroy() { /* nop */ }
}
