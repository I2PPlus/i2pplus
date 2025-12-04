package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.StringWriter;

import net.i2p.router.web.HelperBase;


/**
 * Helper for tunnel peer count page rendering and form processing.
 * @since 0.9.33
 */
public class TunnelPeerCountHelper extends HelperBase {
    public TunnelPeerCountHelper() {}

    public String getTunnelPeerCount() {
        TunnelRenderer renderer = new TunnelRenderer(_context);
        try {
            if (_out != null) {
                renderer.renderPeers(_out);
                return "";
            } else {
                StringWriter sw = new StringWriter(1024*1024);
                renderer.renderPeers(sw);
                return sw.toString();
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return "";
        }
    }
}
