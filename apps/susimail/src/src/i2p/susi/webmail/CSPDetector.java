package i2p.susi.webmail;

import java.util.Locale;

/**
 *  Check user-agent for support of CSP
 *  @since 0.9.62
 */
class CSPDetector {
    /**
     *  ref: https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Security-Policy
     */
    public static boolean supportsCSP(String ua) {
        if (ua == null) {return false;}
        ua = ua.toLowerCase(Locale.US);
        // mobile anything: assume no
        if (ua.contains("mobile")) {return false;}

        // ref: https://www.useragents.me/
        // min versions
        // chrome: 25
        // covers edge, opera
        int idx = ua.indexOf("chrome/");
        if (idx >= 0) {
            idx += 7;
            return getVersion(ua, idx) >= 25;
        }
        // safari: 7
        idx = ua.indexOf("safari/");
        if (idx >= 0) {
            idx = ua.indexOf("version/");
            if (idx >= 0) {
                idx += 8;
                return getVersion(ua, idx) >= 7;
            }
        }
        // firefox: 23
        idx = ua.indexOf("firefox/");
        if (idx >= 0) {
            idx += 8;
            return getVersion(ua, idx) >= 23;
        }
        return false;
    }

    private static int getVersion(String ua, int idx) {
        int rv = 0;
        for (int i = idx; i < ua.length(); i++) {
            char c = ua.charAt(i);
            if (c < '0' || c > '9') {break;}
            if (i > idx) {rv *= 10;}
            rv += c - '0';
        }
        return rv;
    }

}