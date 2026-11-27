package net.i2p.servlet.filters;

import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.i2p.servlet.util.OriginCheck;

/**
 * Filter to refuse a cross-origin POST.
 * <p>
 * The address book had no defense of its own: its forms carry a
 * <code>serial</code> field, which is the address book's own record version, not
 * a CSRF token, so a page on any other origin could POST a delete, an add or an
 * import and have it carried out with the operator's session. The rule is
 * {@link OriginCheck}'s, the one I2PSnark and SusiMail already apply, so the
 * three webapps cannot drift apart again.
 * <p>
 * Only POST is guarded. A cross-origin GET cannot change state here, and the
 * other methods need a script, which the browser already gates with CORS. A POST
 * with no Origin header at all is left through, as it is in the servlets: that is
 * a script or a command-line client, not a browser form, and the checks behind
 * this one decide it.
 * <p>
 * Register it in the webapp's <code>web.xml</code> mapped to <code>/*</code>.
 *
 * @since 0.9.72+
 */
public class OriginFilter implements Filter {

    /**
     * The filter holds no state - {@link #init} and {@link #destroy} are no-ops
     * and the decision is read off each request - so a bare instance is ready to
     * use.
     */
    public OriginFilter() {}

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // no-op
    }

    @Override
    public void destroy() {
        // no-op
    }

    /**
     * Pass the request on, unless it is a POST whose Origin does not belong to
     * the Host it arrived at.
     *
     * @param request the request being filtered
     * @param response the response, written to only when the POST is refused
     * @param chain the rest of the filter chain and the target servlet
     * @throws IOException if the chain or the error reply fails
     * @throws ServletException if the chain fails
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        if (request instanceof HttpServletRequest && response instanceof HttpServletResponse &&
            "POST".equals(((HttpServletRequest) request).getMethod()) &&
            !OriginCheck.isAllowed((HttpServletRequest) request)) {
            HttpServletRequest req = (HttpServletRequest) request;
            OriginCheck.logDenial(req);
            // The same reply the other two webapps send, so a refused POST looks
            // the same whichever app refused it.
            ((HttpServletResponse) response).sendError(HttpServletResponse.SC_FORBIDDEN,
                                                       "Invalid Origin header for POST request");
            return;
        }
        chain.doFilter(request, response);
    }
}
