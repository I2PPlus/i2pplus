package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Map;
import java.util.Properties;
import net.i2p.data.Hash;

/**
 * Wrap up the client settings specifying their tunnel criteria.
 *
 */
public class ClientTunnelSettings {
    /** Settings for the client's inbound tunnels. */
    private final TunnelPoolSettings _inboundSettings;
    /** Settings for the client's outbound tunnels. */
    private final TunnelPoolSettings _outboundSettings;

    /**
     * Create tunnel settings for the given destination.
     *
     * @param dest the destination hash
     */
    public ClientTunnelSettings(Hash dest) {
        _inboundSettings = new TunnelPoolSettings(dest, true);
        _outboundSettings = new TunnelPoolSettings(dest, false);
    }

    /**
     * Get the settings for the client's inbound tunnel pool.
     *
     * @return the settings, created with the destination and never null
     */
    public TunnelPoolSettings getInboundSettings() { return _inboundSettings; }
    /**
     * Get the settings for the client's outbound tunnel pool.
     *
     * @return the settings, created with the destination and never null
     */
    public TunnelPoolSettings getOutboundSettings() { return _outboundSettings; }

    /**
     * Load settings from the given properties.
     *
     * @param props the properties to load from
     */
    public void readFromProperties(Properties props) {
        _inboundSettings.readFromProperties("inbound.", props);
        _outboundSettings.readFromProperties("outbound.", props);
   }

    /**
     * Writes both pools' settings into the properties.
     *
     * @param props where the settings are written
     */
    private void writeToProperties(Properties props) {
        if (props == null) return;
        _inboundSettings.writeToProperties("inbound.", props);
        _outboundSettings.writeToProperties("outbound.", props);
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        Properties p = new Properties();
        writeToProperties(p);
        buf.append("\n* Client tunnel settings:");
        for (Map.Entry<Object, Object> entry : p.entrySet()) {
            String name = (String) entry.getKey();
            String val  = (String) entry.getValue();
            buf.append("\n* ").append(name).append(" = ").append(val);
        }
        return buf.toString();
    }
}
