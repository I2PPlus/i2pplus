package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.StringWriter;

import net.i2p.router.web.HelperBase;
import net.i2p.util.Log;
import net.i2p.util.LogFactory;

/**
 * Helper for participating tunnels page rendering and form processing.
 * @since 0.9.33
 */
public class TunnelParticipatingHelper extends HelperBase {
    private static final Log _log = LogFactory.getLog(TunnelParticipatingHelper.class);
    public TunnelParticipatingHelper() { /* nop */ }

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
}
