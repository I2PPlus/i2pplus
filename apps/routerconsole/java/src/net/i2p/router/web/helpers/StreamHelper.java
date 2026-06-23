package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.List;
import java.util.Set;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.I2PTunnelServer;
import net.i2p.i2ptunnel.I2PTunnelTask;
import net.i2p.i2ptunnel.TunnelController;
import net.i2p.i2ptunnel.TunnelControllerGroup;
import net.i2p.router.web.HelperBase;

/**
 * Helper for the /streams page - shows active I2P streaming connections.
 * @since 0.9.63
 */
public class StreamHelper extends HelperBase {

    private static final String ROW_OPEN = "<tr>";
    private static final String ROW_CLOSE = "</tr>\n";
    private static final String TD_OPEN = "<td>";
    private static final String TD_CLOSE = "</td>";

    public StreamHelper() {}

    /**
     * Render the streaming connections table.
     */
    public String getStreamSummary() {
        try {
            if (_out != null) {
                renderStreamsHTML(_out);
                return "";
            } else {
                StringWriter sw = new StringWriter(32*1024);
                renderStreamsHTML(sw);
                return sw.toString();
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return "";
        }
    }

    private void renderStreamsHTML(Writer out) throws IOException {
        TunnelControllerGroup tcg = TunnelControllerGroup.getInstance();
        if (tcg == null) {
            out.write("<p class=infohelp>Streaming subsystem not available.</p>\n");
            return;
        }

        List<TunnelController> controllers = tcg.getControllers();
        if (controllers == null || controllers.isEmpty()) {
            out.write("<p class=infohelp>No tunnel controllers found.</p>\n");
            return;
        }

        out.write("<table id=streams><thead><tr class=tunnelhead>\n" +
                  "<th>Tunnel</th>\n" +
                  "<th>Type</th>\n" +
                  "<th class=direction>Direction</th>\n" +
                  "<th>Peer</th>\n" +
                  "<th>Port</th>\n" +
                  "<th>Local Port</th>\n" +
                  "<th>Status</th>\n" +
                  "<th title=\"Bytes Sent\">&uarr;</th>\n" +
                  "<th title=\"Bytes Received\">&darr;</th>\n" +
                  "</tr></thead>\n<tbody>\n");

        int totalSockets = 0;
        for (TunnelController controller : controllers) {
            I2PTunnel tunnel = controller.getTunnel();
            if (tunnel == null)
                continue;
            String tunnelName = controller.getName();
            if (tunnelName == null)
                tunnelName = "Unnamed";
            List<I2PTunnelTask> tasks = tunnel.getTasks();
            if (tasks == null || tasks.isEmpty())
                continue;
            for (I2PTunnelTask task : tasks) {
                if (task == null || !task.isOpen())
                    continue;
                I2PSocketManager mgr = task.getSocketManager();
                if (mgr == null)
                    continue;
                Set<I2PSocket> sockets = mgr.listSockets();
                if (sockets == null || sockets.isEmpty())
                    continue;
                String taskType = (task instanceof I2PTunnelServer) ? "Server" : "Client";
                for (I2PSocket sock : sockets) {
                    if (sock == null || sock.isClosed())
                        continue;
                    totalSockets++;
                    out.write(ROW_OPEN);
                    out.write(TD_OPEN);
                    out.write(esc(tunnelName));
                    out.write(TD_CLOSE);
                    out.write(TD_OPEN);
                    out.write(taskType);
                    out.write(TD_CLOSE);
                    boolean inbound = (task instanceof I2PTunnelServer);
                    if (inbound) {
                        out.write("<td class=direction data-sort=in><span class=inbound title=Inbound>" +
                                  "<img src=/themes/console/images/inbound.svg alt=Inbound></span></td>");
                    } else {
                        out.write("<td class=direction data-sort=out><span class=outbound title=Inbound>" +
                                  "<img src=/themes/console/images/outbound.svg alt=Inbound></span></td>");
                    }
                    out.write(TD_OPEN);
                    Destination peer = sock.getPeerDestination();
                    if (peer != null) {
                        String b32 = peer.toBase32();
                        if (b32 != null) {
                            String hostname = _context.namingService().reverseLookup(peer);
                            String display = (hostname != null) ? hostname : b32.substring(0, Math.min(52, b32.length())) + "...";
                            out.write("<span title=\"" + esc(b32) + "\">" + esc(display) + "</span>");
                        } else {
                            out.write("<span title=unknown>unknown</span>");
                        }
                    } else {
                        out.write("<span title=unknown>unknown</span>");
                    }
                    out.write(TD_CLOSE);
                    out.write(TD_OPEN);
                    out.write(String.valueOf(sock.getPort()));
                    out.write(TD_CLOSE);
                    out.write(TD_OPEN);
                    out.write(String.valueOf(sock.getLocalPort()));
                    out.write(TD_CLOSE);
                    out.write(TD_OPEN);
                    out.write(sock.isClosed() ? "Closed" : "Open");
                    out.write(TD_CLOSE);
                    out.write(TD_OPEN);
                    out.write(DataHelper.formatSize2(sock.getLifetimeBytesSent()));
                    out.write("B");
                    out.write(TD_CLOSE);
                    out.write(TD_OPEN);
                    out.write(DataHelper.formatSize2(sock.getLifetimeBytesReceived()));
                    out.write("B");
                    out.write(TD_CLOSE);
                    out.write(ROW_CLOSE);
                }
            }
        }

        out.write("</tbody>\n</table>\n");
        if (totalSockets == 0) {
            out.write("<p class=infohelp>No active streaming connections.</p>\n");
        }
    }

    /** HTML-escape a string */
    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder buf = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': buf.append("&amp;"); break;
                case '<': buf.append("&lt;"); break;
                case '>': buf.append("&gt;"); break;
                case '"': buf.append("&#34;"); break;
                case '\'': buf.append("&#39;"); break;
                default: buf.append(c);
            }
        }
        return buf.toString();
    }
}
