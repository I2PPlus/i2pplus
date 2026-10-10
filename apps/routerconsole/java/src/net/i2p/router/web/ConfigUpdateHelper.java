package net.i2p.router.web;

import javax.servlet.http.HttpSession;

import net.i2p.I2PAppContext;
import net.i2p.crypto.TrustedUpdate;
import net.i2p.data.DataHelper;
import net.i2p.util.PortMapper;

/**
 * Helper class for managing I2P router update configuration and UI generation.
 *
 * <p>This class provides methods for configuring and displaying update settings
 * including proxy configuration, update policies, refresh frequencies, and trusted
 * key management. It integrates with the router console to generate HTML form
 * elements for update configuration.</p>
 */
public class ConfigUpdateHelper extends HelperBase {
    private boolean _dontInstall;
    /**
     * The web session this helper renders for.
     */
    protected HttpSession _session;

    /**
     * For nonce validation and session-bound nonce generation
     *
     * @param session the console HTTP session the page is being rendered into
     * @since 0.9.69
     */
    public void storeSession(HttpSession session) { _session = session; }

    /**
     * Create a helper with no update restrictions.
     */
    public ConfigUpdateHelper() {
        // TODO
    }
    /** hook this so we can call dontInstall() once after getting a context */
    @Override
    public void setContextId(String contextId) {
        super.setContextId(contextId);
        _dontInstall = NewsHelper.dontInstall(_context);
    }

    /**
     * Whether an offered update may actually be installed here.
     *
     * @return false when the current configuration forbids installing an update
     */
    public boolean canInstall() {return !_dontInstall;}

    /**
     * Whether the update check is offered on this page.
     *
     * @return true, since the router always offers an update check
     */
    public boolean updateAvailable() {return true;}

    /**
     * The URL of the packaged news feed.
     *
     * @return the news feed URL
     */
    public String getNewsURL() {return getNewsURL(_context);}

    /**
     * The URL of the packaged news feed.
     *
     * @param ctx the application context, unused here: the packaged URL is returned
     * @return the news feed URL
     */
    public static String getNewsURL(I2PAppContext ctx) {
        return ConfigUpdateHandler.DEFAULT_NEWS_URL_SU3;
    }

    /**
     * The update URL, from the configured property or the packaged default.
     *
     * @return the update URL, with any comma separating alternate hosts replaced
     *         by a newline
     */
    public String getUpdateURL() {
        String url = _context.getProperty(ConfigUpdateHandler.PROP_UPDATE_URL);
        if (url != null) {return url.replace(",", "\n");}
        else {return ConfigUpdateHandler.DEFAULT_UPDATE_URL;}
    }

    /**
     * The proxy host to use when fetching an update.
     *
     * @return the configured host, or a read-only marker when the router is
     *         already proxying for itself
     */
    public String getProxyHost() {
        if (isInternal()) {return _t("internal") + "\" readonly";}
        return _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST, ConfigUpdateHandler.DEFAULT_PROXY_HOST);
    }

    /**
     * The proxy port to use when fetching an update.
     *
     * @return the configured port, or a read-only marker when the router is
     *         already proxying for itself
     */
    public String getProxyPort() {
        if (isInternal()) {return _t("internal") + "\" readonly";}
        return Integer.toString(ConfigUpdateHandler.proxyPort(_context));
    }

    /**
     * This should almost always be true.
     * @return true if settings are at defaults and proxy is registered
     * @since 0.8.13
     */
    private boolean isInternal() {
        String host = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST);
        String port = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_PORT);
        return (host == null || host.equals(ConfigUpdateHandler.DEFAULT_PROXY_HOST)) &&
               (port == null || port.equals(ConfigUpdateHandler.DEFAULT_PROXY_PORT)) &&
               _context.portMapper().getPort(PortMapper.SVC_HTTP_PROXY) == ConfigUpdateHandler.DEFAULT_PROXY_PORT_INT;
    }

    /**
     * The checkbox controlling whether updates are fetched through the proxy.
     *
     * @return checkbox markup, checked when updates are proxied
     */
    public String getUpdateThroughProxy() {
        if (_context.getProperty(ConfigUpdateHandler.PROP_SHOULD_PROXY, ConfigUpdateHandler.DEFAULT_SHOULD_PROXY)) {
            return "<input type=checkbox class=\"optbox slider\" value=true name=\"updateThroughProxy\" id=updateThroughProxy checked>";
        } else {
            return "<input type=checkbox class=\"optbox slider\" value=true name=\"updateThroughProxy\" id=updateThroughProxy >";
        }
    }

    /**
     * The checkbox controlling whether news is fetched through the proxy.
     *
     * @return checkbox markup, checked when news fetching is proxied
     * @since 0.9.9
     */
    public String getNewsThroughProxy() {
        if (_context.getProperty(ConfigUpdateHandler.PROP_SHOULD_PROXY_NEWS, ConfigUpdateHandler.DEFAULT_SHOULD_PROXY_NEWS)) {
            return "<input type=checkbox class=\"optbox slider\" value=true name=\"newsThroughProxy\" id=newsThroughProxy checked>";
        } else {
            return "<input type=checkbox class=\"optbox slider\" value=true name=\"newsThroughProxy\" id=newsThroughProxy >";
        }
    }

    /**
     * The checkbox controlling whether an update may be installed unsigned.
     *
     * @return checkbox markup reflecting the current setting
     */
    public String getUpdateUnsigned() {
        return "<input type=checkbox class=\"optbox slider\" value=true name=\"updateUnsigned\" id=updateUnsigned " +
               getChecked(ConfigUpdateHandler.PROP_UPDATE_UNSIGNED) + '>';
    }

    /**
     * The checkbox selecting the development SU3 channel over the signed release.
     *
     * @return checkbox markup reflecting the current setting
     * @since 0.9.20
     */
    public String getUpdateDevSU3() {
        return "<input type=checkbox class=\"optbox slider\" value=true name=\"updateDevSU3\" id=updateDevSU3 " +
               getChecked(ConfigUpdateHandler.PROP_UPDATE_DEV_SU3) + '>';
    }

    private static final long[] PERIODS = new long[] { 4*60*60*1000L, 8*60*60*1000L, 12*60*60*1000L,
                                                       24*60*60*1000L, 36*60*60*1000L, 48*60*60*1000L,
                                                       3*24*60*60*1000L, 7*24*60*60*1000L, -1L };

    /**
     * The select box offering the update check intervals.
     *
     * @return select markup with the configured interval preselected
     */
    public String getRefreshFrequencySelectBox() {
        String freq = _context.getProperty(ConfigUpdateHandler.PROP_REFRESH_FREQUENCY,
                                           ConfigUpdateHandler.DEFAULT_REFRESH_FREQUENCY);
        long ms = ConfigUpdateHandler.DEFAULT_REFRESH_FREQ;
        try {
            ms = Long.parseLong(freq);
            if (ms <= 0) {ms = -1;}
        } catch (NumberFormatException nfe) { /* ignored */ }

        StringBuilder buf = new StringBuilder(256);
        buf.append("<select name=\"refreshFrequency\">\n");
        for (int i = 0; i < PERIODS.length; i++) {
            buf.append("<option value=\"").append(PERIODS[i]).append("\"");
            if (PERIODS[i] == ms) {buf.append(SELECTED);}
            buf.append(">");
            if (PERIODS[i] == -1) {buf.append(_t("Never"));}
            else {buf.append(_t("Every")).append(' ').append(DataHelper.formatDuration2(PERIODS[i]));}
            buf.append("</option>\n");
        }
        buf.append("</select>\n");
        return buf.toString();
    }

    /**
     * Right now the jsp hides the whole select box if _dontInstall is true but this could change
     * @return the update policy select box
     */
    public String getUpdatePolicySelectBox() {
        String policy = _context.getProperty(ConfigUpdateHandler.PROP_UPDATE_POLICY, ConfigUpdateHandler.DEFAULT_UPDATE_POLICY);

        StringBuilder buf = new StringBuilder(256);

        buf.append("<select name=\"updatePolicy\">");

        buf.append("<option value=\"notify\"");
        if ("notify".equals(policy) || _dontInstall) {buf.append(SELECTED);}
        buf.append('>').append(_t("Notify only")).append("</option>");

        buf.append("<option value=\"download\"");
        if (_dontInstall) {buf.append(" disabled");}
        else if ("download".equals(policy)) {buf.append(SELECTED);}
        buf.append('>').append(_t("Download and verify only")).append("</option>");

        if (_context.hasWrapper()) {
            buf.append("<option value=\"install\"");
            if (_dontInstall) {buf.append(" disabled");}
            else if ("install".equals(policy)) {buf.append(SELECTED);}
            buf.append('>').append(_t("Download, verify, and restart")).append("</option>");
        }

        buf.append("</select>\n");
        return buf.toString();
    }

    /**
     * The signing keys accepted for update verification.
     *
     * @return the trusted keys, formatted for display
     */
    public String getTrustedKeys() {return new TrustedUpdate(_context).getTrustedKeysString();}

    /**
     * The URL of the full router zip download.
     *
     * @return the configured zip URL, or the packaged default
     */
    public String getZipURL() {
        return _context.getProperty(ConfigUpdateHandler.PROP_ZIP_URL, "http://skank.i2p/i2pupdate.zip");
    }

    /**
     * The URL of the development SU3 download.
     *
     * @return the configured URL, or an empty string when it has not been set
     * @since 0.9.20
     */
    public String getDevSU3URL() {
        return _context.getProperty(ConfigUpdateHandler.PROP_DEV_SU3_URL, "");
    }

    /**
     * Whether the last news fetch succeeded, and when it last ran.
     *
     * @return the news status, formatted for display
     */
    public String getNewsStatus() {return NewsHelper.status(_context, _session);}

}
