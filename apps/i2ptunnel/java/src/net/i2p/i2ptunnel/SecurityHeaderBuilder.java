package net.i2p.i2ptunnel;

import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Builds and manages HTTP security headers for server responses.
 * <p>
 * This class provides static methods for adding security-related HTTP headers
 * to server responses. It handles:
 * <ul>
 *   <li>Referrer-Policy header</li>
 *   <li>Allow header</li>
 *   <li>Cache-Control header with appropriate policies</li>
 *   <li>X-XSS-Protection header</li>
 *   <li>X-Content-Type-Options header</li>
 * </ul>
 * </p>
 * <p>
 * Some headers are only added based on the MIME type of the response content.
 * For example, Referrer-Policy and Allow headers are only added for HTML content.
 * Cache-Control policies vary based on whether the content is static (immutable)
 * or dynamic.
 * </p>
 *
 * @since 0.10.0
 */
public class SecurityHeaderBuilder {

    /** Cookie strings that should be filtered from Set-Cookie headers */
    private static final String[] COOKIE_STRINGS = {"STYXKEY", "visited=yes"};

    /**
     * Adds all appropriate security headers to a response.
     * <p>
     * This method adds security headers based on the MIME type of the response.
     * Headers are only added if they are not already present in the response.
     * </p>
     * <p>
     * The following headers may be added:
     * <ul>
     *   <li>Referrer-Policy: same-origin (for HTML/XML/JSON content)</li>
     *   <li>Allow: GET, POST, HEAD (for HTML/XML/JSON content)</li>
     *   <li>Cache-Control (appropriate policy based on MIME type)</li>
     *   <li>X-XSS-Protection: 1; mode=block</li>
     *   <li>X-Content-Type-Options: nosniff</li>
     * </ul>
     * </p>
     *
     * @param headers the HTTP response headers map to modify
     * @param command the HTTP request command (used for logging/debugging)
     * @param mimeType the MIME type of the response content; may be null
     */
    public static void addSecurityHeaders(Map<String, List<String>> headers, StringBuilder command, String mimeType) {
        addReferrerPolicyHeader(headers, mimeType);
        addAllowHeader(headers, mimeType);
        addCacheControlHeader(headers, mimeType);
        addXSSProtectionHeader(headers);
        addNoSniffHeader(headers);
    }

    /**
     * Adds Referrer-Policy header if appropriate and not already present.
     * <p>
     * The Referrer-Policy header is only added for MIME types in the
     * custom whitelist (text/html, application/xhtml+xml, application/xml,
     * text/plain, application/json).
     * </p>
     * <p>
     * Sets: Referrer-Policy: same-origin
     * </p>
     *
     * @param headers the HTTP response headers map
     * @param mimeType the response MIME type
     */
    private static void addReferrerPolicyHeader(Map<String, List<String>> headers, String mimeType) {
        if (!MimeTypeDetector.isCustomWhitelist(mimeType)) {return;}
        boolean hasReferrerPolicy = headers.keySet().stream()
            .anyMatch(key -> key.equalsIgnoreCase("Referrer-Policy"));
        if (!hasReferrerPolicy) {
            I2PTunnelHTTPServer.setEntry(headers, "Referrer-Policy", "same-origin");
        }
    }

    /**
     * Adds Allow header if appropriate and not already present.
     * <p>
     * The Allow header is only added for MIME types in the custom whitelist.
     * </p>
     * <p>
     * Sets: Allow: GET, POST, HEAD
     * </p>
     *
     * @param headers the HTTP response headers map
     * @param mimeType the response MIME type
     */
    private static void addAllowHeader(Map<String, List<String>> headers, String mimeType) {
        if (!MimeTypeDetector.isCustomWhitelist(mimeType)) {return;}
        boolean hasAllow = headers.keySet().stream()
            .anyMatch(key -> key.equalsIgnoreCase("Allow"));
        if (!hasAllow) {
            I2PTunnelHTTPServer.setEntry(headers, "Allow", "GET, POST, HEAD");
        }
    }

    /**
     * Adds or modifies Cache-Control header based on MIME type.
     * <p>
     * Cache-Control policy is determined as follows:
     * <ul>
     *   <li>For immutable content (images, fonts, video, audio): 
     *       Cache-Control: private, max-age=31536000, immutable</li>
     *   <li>For other content: Cache-Control: private, no-cache, max-age=604800</li>
     *   <li>Existing Cache-Control headers are preserved unless they contain
     *       "none" or "post-check" which are stripped</li>
     * </ul>
     * </p>
     * <p>
     * If an existing Cache-Control contains "no-cache" and the content is
     * immutable, it is upgraded to immutable caching.
     * </p>
     *
     * @param headers the HTTP response headers map
     * @param mimeType the response MIME type
     */
    private static void addCacheControlHeader(Map<String, List<String>> headers, String mimeType) {
        boolean hasCacheControl = headers.keySet().stream()
            .anyMatch(key -> key.equalsIgnoreCase("Cache-Control"));
        List<String> cacheControlList = headers.get("Cache-Control");

        if (cacheControlList != null && cacheControlList.contains("none".toLowerCase())) {
            headers.remove("Cache-Control");
            cacheControlList = null;
        }

        if (cacheControlList != null && cacheControlList.contains("post-check".toLowerCase())) {
            List<String> newList = new java.util.ArrayList<>();
            for (String s : cacheControlList) {
                if (!s.toLowerCase().equals("post-check")) {
                    newList.add(s);
                }
            }
            if (newList.isEmpty()) {
                headers.remove("Cache-Control");
                cacheControlList = null;
            } else {
                headers.put("Cache-Control", newList);
                cacheControlList = newList;
            }
        }

        boolean immutableCache = MimeTypeDetector.isImmutableCache(mimeType);

        if (cacheControlList != null) {
            boolean hasNoCache = hasCacheControl && cacheControlList.contains("no-cache".toLowerCase());
            if (hasNoCache && immutableCache) {
                headers.remove("Cache-Control");
                I2PTunnelHTTPServer.setEntry(headers, "Cache-Control", "private, max-age=31536000, immutable");}
            else if (immutableCache && !hasCacheControl) {
                I2PTunnelHTTPServer.setEntry(headers, "Cache-Control", "private, max-age=31536000, immutable");
            } else if (!hasCacheControl) {
                I2PTunnelHTTPServer.setEntry(headers, "Cache-Control", "private, no-cache, max-age=604800");
            }
        } else if (immutableCache) {
            I2PTunnelHTTPServer.setEntry(headers, "Cache-Control", "private, max-age=31536000, immutable");
        } else if (!hasCacheControl) {
            I2PTunnelHTTPServer.setEntry(headers, "Cache-Control", "private, no-cache, max-age=604800");
        }
    }

    /**
     * Adds X-XSS-Protection header if not already present.
     * <p>
     * Sets: X-XSS-Protection: 1; mode=block
     * </p>
     * <p>
     * This header enables the XSS filter in browsers that support it.
     * </p>
     *
     * @param headers the HTTP response headers map
     */
    private static void addXSSProtectionHeader(Map<String, List<String>> headers) {
        boolean hasXSS = headers.keySet().stream()
            .anyMatch(key -> key.equalsIgnoreCase("X-XSS-Protection"));
        if (!hasXSS) {
            I2PTunnelHTTPServer.setEntry(headers, "X-XSS-Protection", "1; mode=block");
        }
    }

    /**
     * Adds X-Content-Type-Options header if not already present.
     * <p>
     * Sets: X-Content-Type-Options: nosniff
     * </p>
     * <p>
     * This header prevents browsers from MIME-sniffing a response away
     * from the declared content-type.
     * </p>
     *
     * @param headers the HTTP response headers map
     */
    private static void addNoSniffHeader(Map<String, List<String>> headers) {
        boolean hasNoSniff = headers.keySet().stream()
            .anyMatch(key -> key.equalsIgnoreCase("X-Content-Type-Options"));
        if (!hasNoSniff) {
            I2PTunnelHTTPServer.setEntry(headers, "X-Content-Type-Options", "nosniff");
        }
    }

    /**
     * Filters Set-Cookie headers to remove unwanted cookies.
     * <p>
     * This method removes Set-Cookie headers containing specific strings
     * (STYXKEY, visited=yes) that are known to be irrelevant or unwanted
     * for I2P HTTP proxying.
     * </p>
     *
     * @param headers the HTTP response headers map
     */
    public static void filterSetCookieHeaders(Map<String, List<String>> headers) {
        List<String> setCookieList = headers.get("Set-Cookie");
        if (setCookieList == null || setCookieList.isEmpty()) {return;}

        List<String> newSetCookieList = new java.util.ArrayList<>();
        for (String setCookie : setCookieList) {
            boolean containsString = false;
            for (String cookieString : COOKIE_STRINGS) {
                if (setCookie.contains(cookieString)) {
                    containsString = true;
                    break;
                }
            }
            if (!containsString) {newSetCookieList.add(setCookie);}
        }

        if (newSetCookieList.isEmpty()) {
            headers.remove("Set-Cookie");
        } else {
            headers.put("Set-Cookie", newSetCookieList);
        }
    }

    /**
     * Filters Cache-Control headers to remove problematic values.
     * <p>
     * This method removes "none" and "post-check" values from
     * Cache-Control headers, as these can cause issues with
     * I2P HTTP proxying.
     * </p>
     *
     * @param headers the HTTP response headers map
     */
    public static void filterCacheControlHeaders(Map<String, List<String>> headers) {
        List<String> cacheControlList = headers.get("Cache-Control");
        if (cacheControlList == null) {return;}

        boolean hasNone = cacheControlList.stream().anyMatch(s -> s.toLowerCase().equals("none"));
        boolean hasPostCheck = cacheControlList.stream().anyMatch(s -> s.toLowerCase().equals("post-check"));

        if (hasNone || hasPostCheck) {
            List<String> newList = new java.util.ArrayList<>();
            for (String s : cacheControlList) {
                String lc = s.toLowerCase();
                if (!lc.equals("none") && !lc.equals("post-check")) {
                    newList.add(s);
                }
            }
            if (newList.isEmpty()) {
                headers.remove("Cache-Control");
            } else {
                headers.put("Cache-Control", newList);
            }
        }
    }
}
