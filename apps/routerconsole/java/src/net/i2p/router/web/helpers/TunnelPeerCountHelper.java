package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.StringWriter;

import net.i2p.router.web.HelperBase;
import net.i2p.util.Log;

/**
 * Helper for tunnel peer count page rendering and form processing.
 * @since 0.9.33
 */
public class TunnelPeerCountHelper extends HelperBase {
    /**
     * TunnelPeerCountHelper.
     */
    public TunnelPeerCountHelper() { /* nop */ }

    /**
     * @return the tunnel peer count
     */
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

    /**
     *  Render a single named element for the contentonly fragment mode of the
     *  tunnel peer count page.
     *
     *  @param id the element id
     *  @since 0.9.70+
     */
    public void renderFragment(String id) {
        TunnelRenderer renderer = new TunnelRenderer(_context);
        try {
            if (_out != null) {
                renderer.renderPeerFragment(_out, id);
            }
        } catch (IOException ioe) {
            _log.error("Error rendering tunnel peer count fragment for " + id, ioe);
        }
    }
}
