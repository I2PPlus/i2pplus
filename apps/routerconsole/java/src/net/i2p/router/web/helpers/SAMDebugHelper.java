package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;

import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppManager;
import net.i2p.sam.SAMBridge;
import net.i2p.router.web.HelperBase;
import net.i2p.util.Log;
import net.i2p.util.LogFactory;

/**
 * Helper for the /samdebug page - shows SAM bridge status with rings
 * and a session table.  The page is accessible from the Debug tab when
 * the SAM bridge is running.
 *
 * @since 0.9.70+
 */
public class SAMDebugHelper extends HelperBase {

    private static final Log _log = LogFactory.getLog(SAMDebugHelper.class);

    public SAMDebugHelper() {}

    /**
     * Render the full SAM debug page content (rings + session table).
     */
    public String getSAMDHelper() {
        try {
            if (_out != null) {
                renderSAMD(_out);
                return "";
            } else {
                StringWriter sw = new StringWriter(32*1024);
                renderSAMD(sw);
                return sw.toString();
            }
        } catch (IOException ioe) {
            _log.error("Error rendering SAM debug page", ioe);
            return "";
        }
    }

    private void renderSAMD(Writer out) throws IOException {
        SAMBridge bridge = getBridge();
        if (bridge == null) {
            out.write("<p class=infohelp>");
            out.write(_t("SAM bridge is not running."));
            out.write("</p>\n");
            return;
        }

        int sessionCount = bridge.getSessionCount();
        int handlerCount = bridge.getHandlerCount();
        int poolActive = bridge.getPoolActiveCount();
        int poolSize = bridge.getPoolSize();
        int queueSize = bridge.getQueueSize();
        int stolenCount = bridge.getStolenSocketCount();

        int sessionMax = 50;
        int handlerMax = 50;
        int queueMax = 256;

        double sessionScore = sessionCount > 0 ? Math.min((double) sessionCount / sessionMax, 1.0) : -1;
        double handlerScore = handlerCount > 0 ? Math.min((double) handlerCount / handlerMax, 1.0) : -1;
        double poolScore = poolSize > 0 ? Math.min((double) poolActive / poolSize, 1.0) : -1;
        double queueScore = queueSize > 0 ? Math.min((double) queueSize / queueMax, 1.0) : -1;
        double stolenScore = handlerCount > 0 ? Math.min((double) stolenCount / Math.max(handlerCount, 1), 1.0) : -1;

        out.write("<div id=samstats>");
        out.write(RingRenderer.renderRingCell(sessionScore, "Sessions", String.valueOf(sessionCount),
                  new String[]{_t("Active sessions in SessionsDB")}, RingRenderer.MODE_ACTIVITY));
        out.write(RingRenderer.renderRingCell(poolScore, "Workers", poolActive + "/" + poolSize,
                  new String[]{_t("Active / pool worker threads")}, RingRenderer.MODE_ACTIVITY));
        out.write(RingRenderer.renderRingCell(queueScore, "Queue", String.valueOf(queueSize),
                  new String[]{_t("Pending commands in pool")}, RingRenderer.MODE_ACTIVITY));
        out.write(RingRenderer.renderRingCell(handlerScore, "Handlers", String.valueOf(handlerCount),
                  new String[]{_t("Connected pool handlers")}, RingRenderer.MODE_ACTIVITY));
        out.write(RingRenderer.renderRingCell(stolenScore, "Stolen", String.valueOf(stolenCount),
                  new String[]{_t("Sockets stolen for data streaming")}, RingRenderer.MODE_NEUTRAL));
        out.write("</div>\n");

        out.write("<div class=debug_section id=samdebug>\n");
        bridge.renderSessionTableHTML(out);
        out.write("</div>\n");
    }

    /**
     *  Get the SAM bridge instance if running.
     *
     * @return the SAMBridge instance, or null if not running
     */
    private SAMBridge getBridge() {
        if (_context == null)
            return null;
        ClientAppManager cmgr = _context.clientAppManager();
        if (cmgr == null)
            return null;
        ClientApp app = cmgr.getRegisteredApp("SAM");
        if (app instanceof SAMBridge)
            return (SAMBridge) app;
        return null;
    }
}
