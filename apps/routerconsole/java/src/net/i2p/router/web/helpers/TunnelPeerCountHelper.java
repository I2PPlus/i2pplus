package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.StringWriter;

import net.i2p.router.web.HelperBase;
import net.i2p.util.Log;
import net.i2p.util.LogFactory;

/**
 * Helper for tunnel peer count page rendering and form processing.
 * @since 0.9.33
 */
public class TunnelPeerCountHelper extends HelperBase {
    private static final Log _log = LogFactory.getLog(TunnelPeerCountHelper.class);
    public TunnelPeerCountHelper() { /* nop */ }

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
            _log.error("Error rendering tunnel peer count", ioe);
            return "";
        }
    }
}
