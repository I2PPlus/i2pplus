package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.StringWriter;

import net.i2p.router.web.HelperBase;

import net.i2p.router.RouterContext;

public class TransitSummaryHelper extends HelperBase {
    public TransitSummaryHelper() {}

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
            ioe.printStackTrace();
            return "";
        }
    }
}
