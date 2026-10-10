package net.i2p.router.web.helpers;

import net.i2p.router.web.HelperBase;

import java.io.IOException;
import java.io.StringWriter;

import net.i2p.util.Log;

/**
 * Helper for fastest participating tunnels page rendering and form processing.
 * The context is supplied by the base class, so there is nothing to do here.
 * @since 0.9.33
 */
public class TunnelParticipatingFastestHelper extends HelperBase {
    /**
     * Nothing to initialize; the router context arrives with the base class.
     */
    public TunnelParticipatingFastestHelper() { /* nop */ }

    /**
     * Render the list of the fastest participating tunnels. The markup goes
     * straight to the response writer when the helper has one, otherwise it is
     * returned for the caller to place. A rendering failure is logged, not
     * thrown, and yields an empty string.
     *
     * @return the tunnel participating fastest, or an empty string on failure
     */
    public String getTunnelParticipatingFastest() {
        TunnelRenderer renderer = new TunnelRenderer(_context);
        try {
            if (_out != null) {
                renderer.renderParticipating(_out, true);
                return "";
            } else {
                StringWriter sw = new StringWriter(32 * 1024);
                renderer.renderParticipating(sw, true);
                return sw.toString();
            }
        } catch (IOException ioe) {
            _log.error("Error rendering participating fastest tunnels", ioe);
            return "";
        }
    }

    /**
     *  Render a single named element for the contentonly fragment mode of the
     *  fastest participating tunnels page.
     *
     *  @param id the element id
     *  @since 0.9.70+
     */
    public void renderFragment(String id) {
        TunnelRenderer renderer = new TunnelRenderer(_context);
        try {
            if (_out != null) {
                renderer.renderParticipatingFragment(_out, true, id);
            }
        } catch (IOException ioe) {
            _log.error("Error rendering participating fastest tunnels fragment for " + id, ioe);
        }
    }
}
