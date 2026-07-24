package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.StringWriter;

import net.i2p.router.web.HelperBase;
import net.i2p.util.Log;
import net.i2p.util.LogFactory;

/**
 * Helper for transit tunnel summary page rendering and form processing.
 * @since 0.9.33
 */
public class TransitSummaryHelper extends HelperBase {
    private static final Log _log = LogFactory.getLog(TransitSummaryHelper.class);
    public TransitSummaryHelper() { /* nop */ }

    public String getTransitSummary() {
        TunnelRenderer renderer = new TunnelRenderer(_context);
        try {
            if (_out != null) {
                renderer.renderTransitSummary(_out);
                return "";
            } else {
                StringWriter sw = new StringWriter(32*1024);
                renderer.renderTransitSummary(sw);
                return sw.toString();
            }
        } catch (IOException ioe) {
            _log.error("Error rendering transit summary", ioe);
            return "";
        }
    }
}
