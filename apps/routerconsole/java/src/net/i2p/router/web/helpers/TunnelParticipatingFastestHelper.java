package net.i2p.router.web.helpers;

import net.i2p.router.web.HelperBase;

import java.io.IOException;
import java.io.StringWriter;

import net.i2p.util.Log;

/**
 * Helper for fastest participating tunnels page rendering and form processing.
 * @since 0.9.33
 */
public class TunnelParticipatingFastestHelper extends HelperBase {
    public TunnelParticipatingFastestHelper() { /* nop */ }

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
}
