package net.i2p.servlet.filters;

import java.io.IOException;
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
    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(SameSiteCookieFilter.class);

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {}

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain)
            throws IOException, ServletException {
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        HttpServletResponse wrapped = new HttpServletResponseWrapper(httpResponse) {
            @Override
            public void addHeader(String name, String value) {
                if (name != null && name.equalsIgnoreCase("Set-Cookie")) {
                    String lower = value.toLowerCase();
                    _log.debug("Set-Cookie header: " + value);
                    if (!lower.contains("samesite")) {
                        value = value + "; SameSite=Lax";
                        _log.debug("Added SameSite to: " + value);
                    }
                }
                super.addHeader(name, value);
            }

            @Override
            public void setHeader(String name, String value) {
                if (name != null && name.equalsIgnoreCase("Set-Cookie")) {
                    String lower = value.toLowerCase();
                    _log.debug("Set-Cookie header (set): " + value);
                    if (!lower.contains("samesite")) {
                        value = value + "; SameSite=Lax";
                        _log.debug("Added SameSite to: " + value);
                    }
                }
                super.setHeader(name, value);
            }
        };

        chain.doFilter(request, wrapped);
    }

    @Override
    public void destroy() {}
}
