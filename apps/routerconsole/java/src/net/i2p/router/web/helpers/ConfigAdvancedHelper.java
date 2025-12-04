package net.i2p.router.web.helpers;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.i2p.data.DataHelper;
import net.i2p.router.Router;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.RouterConsoleRunner;

/**
 * Helper for advanced configuration page rendering and form processing.
 * @since 0.9.33
 */
public class ConfigAdvancedHelper extends HelperBase {
    static final String PROP_FLOODFILL_PARTICIPANT = "router.floodfillParticipant";
    private static final String PROP_AUTH_PFX = RouterConsoleRunner.PROP_CONSOLE_PW + '.';

    private static final Set<String> _hideKeys;
    static {
        // do not show these settings in the UI
        String[] keys = {
            "i2cp.keyPassword", "i2cp.keystorePassword",
            "i2np.ntcp2.sp",
            "netdb.family.keyPassword", "netdb.family.keystorePassword",
            Router.PROP_IB_RANDOM_KEY, Router.PROP_OB_RANDOM_KEY,
            "router.reseedProxy.password", "router.reseedSSLProxy.password",
            RouterConsoleRunner.PROP_KEY_PASSWORD, RouterConsoleRunner.PROP_KEYSTORE_PASSWORD
        };
        _hideKeys = new HashSet<String>(Arrays.asList(keys));
    }

    private static final Map<String, String> _headers = new HashMap<String, String>(16);
    static {
        _headers.put("crypto", "Crypto");
        _headers.put("desktopgui", "System Tray");
        _headers.put("i2cp", "I2P Client Protocol");
        _headers.put("i2np", "I2P Network Protocol");
        _headers.put("i2p", "I2P");
        _headers.put("jbigi", "Java Big Integer");
        _headers.put("netdb", "Network Database");
        _headers.put("prng", "Random Number Generator");
        _headers.put("routerconsole", "Router Console");
        _headers.put("router", "Router");
        _headers.put("stat", "Statistics");
        _headers.put("time", "Time");
    }

    public String getSettings() {
        StringBuilder buf = new StringBuilder(4*1024);
        TreeMap<String, String> sorted = new TreeMap<String, String>();
        sorted.putAll(_context.router().getConfigMap());
        boolean adv = isAdvanced();
        String lastType = null;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            String key = e.getKey();
            String name = DataHelper.escapeHTML(key);
            String val = DataHelper.escapeHTML(e.getValue());
            if (!adv) {
                if (_hideKeys.contains(key) ||
                    key.startsWith("i2cp.auth.") ||
                    key.startsWith(PROP_AUTH_PFX) ||
                    key.startsWith("X") ||
                    key.startsWith("homehelper") ||
                    key.startsWith("desktopgui") ||
                    key.startsWith("jbigi") ||
                    key.startsWith("prng")) {
                    continue;
                }
                String type = key;
                int dot = key.indexOf('.');
                if (dot > 0) {type = type.substring(0, dot);}
                if (!type.equals(lastType)) {
                    lastType = type;
                    String dtype = _headers.get(type);
                    if (dtype == null) {dtype = type;}
                    if (type.length() > 0) {
                        buf.append("<tr class=section><th colspan=2>").append(_t(dtype)).append("</th></tr>\n");
                    }
                } else {
                    buf.append("<tr><td>").append(name).append("</td>")
                       .append("<td>").append(val).append("</td></tr>\n");
                }
            } else {buf.append(name).append('=').append(val).append('\n');} // adv. mode
        }
        return buf.toString();
    }

    /** @since 0.9.14.1 */
    public String getConfigFileName() {return _context.router().getConfigFilename();}

    /** @since 0.9.20 */
    public String getFFChecked(int mode) {
        String ff = _context.getProperty(PROP_FLOODFILL_PARTICIPANT, "auto");
        if ((mode == 0 && ff.equals("false")) ||
            (mode == 1 && ff.equals("true")) ||
            (mode == 2 && ff.equals("auto")))
            return CHECKED;
        return "";
    }

    /** @since 0.9.21 */
    public boolean isFloodfill() {return _context.netDb().floodfillEnabled();}
}
