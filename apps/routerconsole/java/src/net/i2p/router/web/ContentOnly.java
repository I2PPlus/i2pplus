package net.i2p.router.web;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import javax.servlet.http.HttpServletRequest;

/**
 * Per-request utility for the opt-in console fragment mode. When a page is
 * requested with a contentonly request parameter, only the named elements
 * are rendered and the shared head/sidebar chrome is skipped, so auto-refresh
 * ticks transfer and parse a fraction of the full page. Full-page requests
 * (no contentonly parameter) are unaffected.
 *
 * Pages opt in by gating each refresh target with
 * {@link #render(HttpServletRequest, String)}; the chrome guards in head.jsi
 * and sidebar.jsi are shared by every console page.
 *
 * @since 0.9.70+
 */
public class ContentOnly {

    private ContentOnly() {}

    /** Splits the contentonly parameter on commas and whitespace */
    private static final Pattern SPLIT = Pattern.compile("[,\\s]+");
    /** Element ids are [A-Za-z0-9_-] runs */
    private static final Pattern ELEMENT_ID = Pattern.compile("[A-Za-z0-9_-]+");

    /**
     * True when the request asks for fragment rendering.
     *
     * @param req the request
     * @return true iff the contentonly parameter is present and non-empty
     */
    public static boolean isContentOnly(HttpServletRequest req) {
        String param = req.getParameter("contentonly");
        return param != null && !param.trim().isEmpty();
    }

    /**
     * The sanitized set of requested element ids. Splits the parameter on
     * commas and whitespace and keeps only ids matching [A-Za-z0-9_-].
     *
     * @param req the request
     * @return the requested ids, or an empty set
     */
    public static Set<String> requestedIds(HttpServletRequest req) {
        String param = req.getParameter("contentonly");
        if (param == null) {return Collections.emptySet();}
        Set<String> ids = new HashSet<String>(4);
        String[] parts = SPLIT.split(param);
        for (String part : parts) {
            if (part.length() > 0 && ELEMENT_ID.matcher(part).matches()) {
                ids.add(part);
            }
        }
        return ids;
    }

    /**
     * Whether the named element must be rendered for this request.
     *
     * @param req the request
     * @param id the element id
     * @return true for full-page requests, and for fragment requests that include the id
     */
    public static boolean render(HttpServletRequest req, String id) {
        return !isContentOnly(req) || requestedIds(req).contains(id);
    }
}
