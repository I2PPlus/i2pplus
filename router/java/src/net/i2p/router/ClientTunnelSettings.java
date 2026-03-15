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
 * Wrap up the client settings specifying their tunnel criteria
 *
 */
public class ClientTunnelSettings {
    private final TunnelPoolSettings _inboundSettings;
    private final TunnelPoolSettings _outboundSettings;

    public ClientTunnelSettings(Hash dest) {
        _inboundSettings = new TunnelPoolSettings(dest, true);
        _outboundSettings = new TunnelPoolSettings(dest, false);
    }

    public TunnelPoolSettings getInboundSettings() { return _inboundSettings; }
    //public void setInboundSettings(TunnelPoolSettings settings) { _inboundSettings = settings; }
    public TunnelPoolSettings getOutboundSettings() { return _outboundSettings; }
    //public void setOutboundSettings(TunnelPoolSettings settings) { _outboundSettings = settings; }

    public void readFromProperties(Properties props) {
        _inboundSettings.readFromProperties("inbound.", props);
        _outboundSettings.readFromProperties("outbound.", props);
   }

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
