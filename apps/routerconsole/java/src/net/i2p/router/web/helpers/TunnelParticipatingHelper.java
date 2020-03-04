package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.StringWriter;

import net.i2p.router.web.HelperBase;

import net.i2p.router.RouterContext;

public class TunnelParticipatingHelper extends HelperBase {
    public TunnelParticipatingHelper() {}

    public String getTunnelsParticipating() {
        TunnelRenderer renderer = new TunnelRenderer(_context);
        try {
            if (_out != null) {
                renderer.renderParticipating(_out);
                return "";
            } else {
                StringWriter sw = new StringWriter(32*1024);
                renderer.renderParticipating(sw);
                return sw.toString();
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return "";
        }
    }
}
