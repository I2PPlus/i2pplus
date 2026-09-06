package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.StringWriter;

import net.i2p.router.web.HelperBase;
import net.i2p.util.Log;

/**
 * Helper for participating tunnels page rendering and form processing.
 * @since 0.9.33
 */
public class TunnelParticipatingHelper extends HelperBase {
    /**
     * TunnelParticipatingHelper.
     */
    public TunnelParticipatingHelper() { /* nop */ }

    /**
     * @return the tunnels participating
     */
    public String getTunnelsParticipating() {
        TunnelRenderer renderer = new TunnelRenderer(_context);
        try {
            if (_out != null) {
                renderer.renderParticipating(_out, false);
                return "";
            } else {
                StringWriter sw = new StringWriter(32*1024);
                renderer.renderParticipating(sw, false);
                return sw.toString();
            }
        } catch (IOException ioe) {
            _log.error("Error rendering participating tunnels", ioe);
            return "";
        }
    }

    /**
     *  Render a single named element for the contentonly fragment mode of the
     *  most-recent participating tunnels page.
     *
     *  @param id the element id
     *  @since 0.9.70+
     */
    public void renderFragment(String id) {
        TunnelRenderer renderer = new TunnelRenderer(_context);
        try {
            if (_out != null) {
                renderer.renderParticipatingFragment(_out, false, id);
            }
        } catch (IOException ioe) {
            _log.error("Error rendering participating tunnels fragment for " + id, ioe);
        }
    }
}
