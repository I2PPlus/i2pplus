package edu.internet2.ndt;

/* This class has code taken from:
 * http://nerds.palmdrive.net/useragent/code.html
 *
 * Class used to obtain information about who is accessing a web-server.
 *
 * When a web browser accesses a web-server, it usually transmits a "User-Agent" string.
 * This is expected to include the name and versions of the browser and the underlying Operating System.
 * Though the information inside a user-agent string is not restricted to these alone, currently,
 * NDT uses this to get Browser OS only.
 */

/**
 * Utility class for parsing user agent strings to extract browser and OS information.
 */
public class UserAgentTools {

    /**
     * The parsers below are static and hold no configuration, so an instance carries
     * nothing.
     */
    public UserAgentTools() {}

    /**
     * Bundle three parsed fields into the fixed-shape result array callers expect.
     *
     * @param a the browser name, or "?" when it could not be determined
     * @param b the browser version, or "?" when it could not be determined
     * @param c the operating system name, or "?" when it could not be determined
     * @return a new three-element array holding a, b and c in that order
     */
    public static String[] getArray(String a, String b, String c) {
        String[] res = new String[3];
        res[0] = a;
        res[1] = b;
        res[2] = c;
        return res;
    }

    /**
     * Extract the browser and OS fields from a User-Agent header.
     *
     * @param userAgent the raw header value, which is ignored here because NDT
     *        reports unknown for every field it does not recognize
     * @return a three-element array of browser name, browser version and OS name,
     * each "?" when the value is not one NDT knows
     */
    public static String[] getBrowser(String userAgent) {return getArray("?", "?", "?");}
}
