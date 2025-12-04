package net.i2p.router.web.helpers;


import java.io.IOException;
import java.io.StringWriter;

import net.i2p.router.tunnel.pool.TunnelPool;
import net.i2p.router.web.HelperBase;

/**
 * Helper for tunnel bandwidth page rendering and form processing.
 * @since 0.9.33
 */
public class TunnelBandwidthHelper extends HelperBase {
    public String getTunnelBandwidth() {
        TunnelRenderer renderer = new TunnelRenderer(_context);
        try {
            StringWriter sw = new StringWriter(64*1024);
            TunnelPool in = null;
            TunnelPool out = null; // or pass actual TunnelPool objects if available
            renderer.renderLifetimeBandwidth(sw, in, out);
            return sw.toString();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return "";
        }
    }
}
