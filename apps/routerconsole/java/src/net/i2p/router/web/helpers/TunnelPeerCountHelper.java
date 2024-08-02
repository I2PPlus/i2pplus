package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.StringWriter;

import net.i2p.router.web.HelperBase;


public class TunnelPeerCountHelper extends HelperBase {
    public TunnelPeerCountHelper() {}

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
            ioe.printStackTrace();
            return "";
        }
    }
}
