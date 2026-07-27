package net.i2p.router.web.helpers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;
import net.i2p.router.networkdb.reseed.Reseeder;
import net.i2p.router.web.HelperBase;

/**
 * Helper for reseed configuration page rendering and form processing.
 * @since 0.9.33
 */
public class ConfigReseedHelper extends HelperBase {

    /**
     * Create a new helper for reseed configuration.
     */
    public ConfigReseedHelper() {}

    /**
     * Retrieve the configured proxy port for reseeding.
     *
     * @return the proxy port, or empty string if not set
     */
    public String getPort() {
        return _context.getProperty(Reseeder.PROP_PROXY_PORT, "");
    }

    /**
     * Retrieve the configured proxy host for reseeding.
     *
     * @return the proxy host, or empty string if not set
     */
    public String getHost() {
        return _context.getProperty(Reseeder.PROP_PROXY_HOST, "");
    }

    /**
     * Retrieve the configured proxy username for reseeding.
     *
     * @return the proxy username
     * @since 0.8.9
     */
    public String getUsername() {
        return _context.getProperty(Reseeder.PROP_PROXY_USERNAME, "");
    }

    /**
     * Retrieve the configured proxy password for reseeding.
     *
     * @return the proxy password
     * @since 0.8.9
     */
    public String getNofilter_password() {
        return _context.getProperty(Reseeder.PROP_PROXY_PASSWORD, "");
    }

    /**
     * Retrieve the configured SSL proxy port for reseeding.
     *
     * @return the SSL proxy port
     * @since 0.8.9
     */
    public String getSport() {
        return _context.getProperty(Reseeder.PROP_SPROXY_PORT, "");
    }

    /**
     * Retrieve the configured SSL proxy host for reseeding.
     *
     * @return the SSL proxy host
     * @since 0.8.9
     */
    public String getShost() {
        return _context.getProperty(Reseeder.PROP_SPROXY_HOST, "");
    }

    /**
     * Retrieve the configured SSL proxy username for reseeding.
     *
     * @return the SSL proxy username
     * @since 0.8.9
     */
    public String getSusername() {
        return _context.getProperty(Reseeder.PROP_SPROXY_USERNAME, "");
    }

    /**
     * Retrieve the configured SSL proxy password for reseeding.
     *
     * @return the SSL proxy password
     * @since 0.8.9
     */
    public String getNofilter_spassword() {
        return _context.getProperty(Reseeder.PROP_SPROXY_PASSWORD, "");
    }

    /**
     * Check whether the given SSL mode matches the current configuration.
     *
     * @param mode the mode to check (0=optional, 1=required, 2=disabled)
     * @return the CHECKED constant if mode matches, empty string otherwise
     */
    public String modeChecked(int mode) {
        boolean required =  _context.getBooleanPropertyDefaultTrue(Reseeder.PROP_SSL_REQUIRED);
        boolean disabled =  _context.getBooleanProperty(Reseeder.PROP_SSL_DISABLE);
        if ((mode == 0 && (!disabled) && (!required)) ||
            (mode == 1 && (!disabled) && required) ||
            (mode == 2 && disabled))
            return CHECKED;
        return "";
    }

    /**
     * Check whether the given proxy mode matches the current proxy configuration.
     *
     * @param mode the proxy mode to check (0=disabled, 1=HTTP, 2=SOCKS4, 3=SOCKS5, 4=INTERNAL)
     * @return the CHECKED constant if mode matches, empty string otherwise
     * @since 0.9.33
     */
    public String pmodeChecked(int mode) {
        String c =  _context.getProperty(Reseeder.PROP_SPROXY_TYPE, "HTTP");
        boolean disabled =  !_context.getBooleanProperty(Reseeder.PROP_SPROXY_ENABLE);
        if ((mode == 0 && disabled) ||
            (mode == 1 && !disabled && c.equals("HTTP")) ||
            (mode == 2 && !disabled && c.equals("SOCKS4")) ||
            (mode == 3 && !disabled && c.equals("SOCKS5")) ||
            (mode == 4 && !disabled && c.equals("INTERNAL")))
            return CHECKED;
        return "";
    }

    /**
     * Retrieve whether proxy reseeding is enabled.
     *
     * @return the CHECKED constant if enabled, empty string otherwise
     */
    public String getEnable() {
        return getChecked(Reseeder.PROP_PROXY_ENABLE);
    }

    /**
     * Retrieve whether proxy authentication is enabled.
     *
     * @return the CHECKED constant if auth enabled, empty string otherwise
     * @since 0.8.9
     */
    public String getAuth() {
        return getChecked(Reseeder.PROP_PROXY_AUTH_ENABLE);
    }

/****
    public String getSenable() {
        return getChecked(Reseeder.PROP_SPROXY_ENABLE);
    }
****/

    /**
     * Retrieve whether SSL proxy authentication is enabled.
     *
     * @return the CHECKED constant if auth enabled, empty string otherwise
     * @since 0.8.9
     */
    public String getSauth() {
        return getChecked(Reseeder.PROP_SPROXY_AUTH_ENABLE);
    }

    private List<String> reseedList() {
        String urls = _context.getProperty(Reseeder.PROP_RESEED_URL, Reseeder.DEFAULT_SEED_URL + ',' + Reseeder.DEFAULT_SSL_SEED_URL);
        StringTokenizer tok = new StringTokenizer(urls, " ,\r\n");
        List<String> URLList = new ArrayList<>(16);
        while (tok.hasMoreTokens()) {
            String s = tok.nextToken().trim();
            if (!s.isEmpty())
                URLList.add(s);
        }
        return URLList;
    }

    /**
     * Retrieve the configured reseed URLs as a newline-separated string.
     *
     * @return the reseed URLs, one per line
     */
    public String getReseedURL() {
        List<String> URLList = reseedList();
        Collections.sort(URLList);
        StringBuilder buf = new StringBuilder();
        for (String s : URLList) {
             if (buf.length() > 0)
                 buf.append('\n');
             buf.append(s);
        }
        return buf.toString();
    }

    /**
     *  Determine if both HTTP and HTTPS reseed URLs are available.
     *
     *  @return true only if we have both http and https URLs
     *  @since 0.9.33
     */
    public boolean shouldShowSelect() {
        boolean http = false;
        boolean https = false;
        for (String u : reseedList()) {
            if (u.startsWith("https://")) {
                if (http)
                    return true;
                https = true;
            } else if (u.startsWith("http://")) {
                if (https)
                    return true;
                http = true;
            }
        }
        return false;
    }

    /**
     *  Determine if any HTTP reseed URLs are configured.
     *
     *  @return true only if we have a http URL
     *  @since 0.9.33
     */
    public boolean shouldShowHTTPProxy() {
        for (String u : reseedList()) {
            if (u.startsWith("http://"))
                return true;
        }
        return false;
    }

    /**
     *  Determine if any HTTPS reseed URLs are configured.
     *
     *  @return true only if we have a https URL
     *  @since 0.9.33
     */
    public boolean shouldShowHTTPSProxy() {
        for (String u : reseedList()) {
            if (u.startsWith("https://"))
                return true;
        }
        return false;
    }
}
