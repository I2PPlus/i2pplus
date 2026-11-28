package net.i2p.servlet.util;

import java.util.Base64;

import net.i2p.util.RandomSource;

/**
 * Simple utilities for servlets.
 * Consolidated from i2psnark, susimail, and routerconsole
 * @since 0.9.33
 */
public class ServletUtil {

    /**
     * Random bytes in one CSP nonce. 18, not 16, so the Base64 needs no
     * padding: several console pages write the nonce into an unquoted
     * attribute, where a trailing = is an HTML parse error.
     */
    private static final int CSP_NONCE_BYTES = 18;

    /** Utility class, not intended for instantiation. */
    private ServletUtil() {}

    /**
     * Does the request come from a browser too small for the full
     * desktop pages, i.e. either a text-mode browser or a mobile one?
     *
     * @param ua User-Agent string, non-null
     * @return true if a text-mode or mobile browser
     */
    public static boolean isSmallBrowser(String ua) {
        return isTextBrowser(ua) || isMobileBrowser(ua);
    }

    /**
     * Does the User-Agent name a text-mode browser? Matched on the
     * product token at the start of the string: Lynx, w3m, ELinks,
     * Links, Dillo or Emacs-w3m.
     *
     * @param ua User-Agent string, non-null
     * @return true if a text-mode browser
     */
    public static boolean isTextBrowser(String ua) {
        return
            ua.startsWith("Lynx") || ua.startsWith("w3m") ||
            ua.startsWith("ELinks") || ua.startsWith("Links") ||
            ua.startsWith("Dillo") || ua.startsWith("Emacs-w3m");
    }


    /**
     * The intent here is to return true for phones but
     * false for big tablets? But not consistent.
     *
     * @param ua User-Agent string, non-null
     * @return true if a mobile browser
     */
    public static boolean isMobileBrowser(String ua) {
        return
            // http://www.zytrax.com/tech/web/mobile_ids.html
            // Android tablet UAs don't have "Mobile" in them
            (ua.contains("Android") && ua.contains("Mobile")) ||
            ua.contains("BlackBerry") ||
            ua.contains("iPhone") ||
            ua.contains("iPod") || ua.contains("iPad") ||
            ua.contains("Kindle") || ua.contains("Mobile") ||
            ua.contains("Nintendo") ||
            ua.contains("Opera Mini") || ua.contains("Opera Mobi") ||
            ua.contains("Palm") ||
            ua.contains("PLAYSTATION") || ua.contains("Playstation") ||
            ua.contains("Profile/MIDP-") || ua.contains("SymbianOS") ||
            ua.contains("Windows CE") || ua.contains("Windows Phone") ||
            ua.startsWith("DoCoMo") ||
            ua.startsWith("Cricket") || ua.startsWith("HTC") ||
            ua.startsWith("J-PHONE") || ua.startsWith("KDDI-") ||
            ua.startsWith("LG-") || ua.startsWith("LGE-") ||
            ua.startsWith("Nokia") || ua.startsWith("OPWV-SDK") ||
            ua.startsWith("MOT-") || ua.startsWith("SAMSUNG-") ||
            ua.startsWith("nook") || ua.startsWith("SCH-") ||
            ua.startsWith("SEC-") || ua.startsWith("SonyEricsson") ||
            ua.startsWith("Vodafone");
    }

    /**
     * Truncate a String.
     * Same as s.substring(0, len) except that
     * it won't split a surrogate pair or at a ZWJ.
     *
     * @param s non-null
     * @param len greater than zero
     * @return s if shorter; s.substring(0, len) if
     *         the char at len-1 is not a high surrogate
     *         or the char at len-1 or len is not a zero-width joiner;
     *         s.substring(0, len+1 or len+2) if it is
     * @since 0.9.33
     */
    public static String truncate(String s, int len) {
        if (s.length() <= len)
            return s;
        char c = s.charAt(len - 1);
        // https://en.wikipedia.org/wiki/Zero-width_joiner
        if (Character.isHighSurrogate(c) || c == 0x200D)
            return s.substring(0, len + 1);
        if (s.charAt(len) == 0x200D)
            return s.substring(0, len + 2);
        return s.substring(0, len);
    }

    /**
     * Sanitize an encoded String from getQueryString().
     * Does not decode.
     *
     * @param s may be null
     * @return s or null if s was null or s contained any problematic characters
     * @since 0.9.70
     */
    public static String sanitizeQuery(String s) {
        if (s == null)
            return null;
        int len = s.length();
        for (int i = 0; i < len; i++) {
            switch (s.charAt(i)) {
                case '\r':
                case '\n':
                case '"':
                case '\'':
                case '<':
                case '>':
                    return null;
            }
        }
        return s;
    }

    /**
     * A Content-Security-Policy script nonce for one response. Drawn from
     * {@link RandomSource}, which is SecureRandom-backed, and Base64 encoded,
     * so it holds at least the 128 bits a CSP nonce is meant to carry and a
     * page cannot be reached by searching the value out.
     * <p>
     * Call this once per response and use the result in both the
     * script-src 'nonce-…' directive and every inline script tag: a nonce
     * computed once per servlet or per JVM is a constant printed on every page
     * the operator loads, which retires it as the one thing separating an
     * inline script from being blocked.
     *
     * @return a 24-character Base64 string, no padding, never null
     * @since 0.9.72+
     */
    public static String getCSPNonce() {
        byte[] nonce = new byte[CSP_NONCE_BYTES];
        RandomSource.getInstance().nextBytes(nonce);
        return Base64.getEncoder().encodeToString(nonce);
    }

    /**
     * The opening tag of an inline script that this response's CSP nonce
     * allows, with the nonce quoted.
     * <p>
     * The quoting is the whole point. A nonce is Base64, so it carries
     * {@code /} and {@code +} - roughly one response in three has a slash - and
     * an unquoted attribute value ends at the first slash. The browser then
     * holds a nonce of the first eight characters or so while the header names
     * all twenty-four, and CSP blocks the script: the page loads looking normal
     * with its sorting, toggling and theme scripts dead.
     *
     * @param nonce the nonce for this response, from {@link #getCSPNonce()}
     * @param attrs extra attributes for the tag, e.g. {@code "type=module"},
     *              empty for none
     * @return the opening tag, ending at the close of the tag, no body
     * @since 0.9.72+
     */
    public static String scriptOpen(String nonce, String attrs) {
        StringBuilder buf = new StringBuilder(48);
        buf.append("<script nonce=\"").append(nonce).append('"');
        if (!attrs.isEmpty()) {buf.append(' ').append(attrs);}
        buf.append('>');
        return buf.toString();
    }
}
