package i2p.susi.webmail;

import java.util.Locale;

/**
 * Check user-agent for support of CSP.
 * @since 0.9.62
 */
class CSPDetector {
    /**
     * Ref: https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Security-Policy
     */
    public static boolean supportsCSP(String ua) {
        if (ua == null) { return false; }

        ua = ua.toLowerCase(Locale.US);

        // Mobile devices: assume no CSP support
        if (ua.contains("mobile")) { return false; }

        // Chrome family (Chrome, Edge, Opera) must be version >= 25
        int idx = ua.indexOf("chrome/");
        if (idx >= 0) { idx += 7; return getVersion(ua, idx) >= 25; }

        // Safari: require Version/7 or higher
        idx = ua.indexOf("safari/");
        if (idx >= 0) {
            int verIdx = ua.indexOf("version/");
            if (verIdx >= 0) { verIdx += 8; return getVersion(ua, verIdx) >= 7; }
        }

        // Firefox: require version >= 23
        idx = ua.indexOf("firefox/");
        if (idx >= 0) { idx += 8; return getVersion(ua, idx) >= 23; }

        return false;
    }

    private static int getVersion(String ua, int idx) {
        int value = 0;
        for (int i = idx; i < ua.length(); i++) {
            char c = ua.charAt(i);
            if (c < '0' || c > '9') { break; }
            if (i > idx) { value *= 10; }
            value += c - '0';
        }
        return value;
    }

}
