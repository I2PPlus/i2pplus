package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.StringWriter;

import net.i2p.router.web.HelperBase;
import net.i2p.util.Log;

/**
 * Helper for transit tunnel summary page rendering and form processing.
 * @since 0.9.33
 */
public class TransitSummaryHelper extends HelperBase {
    /**
     * TransitSummaryHelper.
     */
    public TransitSummaryHelper() { /* nop */ }

    /**
     * @return the transit summary
     */
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

    /**
     *  Render a single named element for the contentonly fragment mode of the
     *  transit-summary page.
     *
     *  @param id the element id
     *  @since 0.9.70+
     */
    public void renderFragment(String id) {
        TunnelRenderer renderer = new TunnelRenderer(_context);
        try {
            if (_out != null) {
                renderer.renderTransitSummaryFragment(_out, id);
            }
        } catch (IOException ioe) {
            _log.error("Error rendering transit summary fragment for " + id, ioe);
        }
    }
}
