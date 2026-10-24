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
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.stat.RateConstants;
import net.i2p.util.Log;

/**
 * Helper for the /streams page - shows active I2P streaming connections.
 * @since 0.9.70+
 */
public class StreamHelper extends HelperBase {

    /** Markup starting a table row. */
    private static final String ROW_OPEN = "<tr>";
    /** Markup ending a table row. */
    private static final String ROW_CLOSE = "</tr>\n";
    /** Markup starting a table cell. */
    private static final String TD_OPEN = "<td>";
    /** Markup ending a table cell. */
    private static final String TD_CLOSE = "</td>";

    /** Only streams in this direction are shown, if set. */
    private String _direction;

    /**
     * Creates a helper that renders active streaming connections to the
     * /streams page.
     */
    public StreamHelper() { /* nop */ }

    /**
     * Gets the direction filter applied to the streams table.
     * @return the configured direction, either "inbound" or "outbound", or empty when no
     *         filter is set and the combined table is rendered
     */
    public String getDirection() { return _direction != null ? _direction : ""; }
    /**
     * Sets the direction filter applied to the streams table.
     *
     * @param d the direction filter to apply: "inbound" or "outbound" restricts the table to one side, and null or empty renders the combined table
     */
    public void setDirection(String d) { _direction = d; }

    /**
     * Render the streaming connections table(s).
     * @return the stream summary
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
            _log.error("Error rendering stream summary", ioe);
            return "";
        }
    }

    /**
     * Read a rate stat value from the stat manager, returning 0 if unavailable.
     * @param name the rate stat name, e.g. "stream.rtt"
     * @param period the rate period in ms, one of the {@link RateConstants} values
     * @return the rate's average value, or 0 when the stat or period has no data
     */
    private double getStatAvg(String name, long period) {
        RateStat rs = _context.statManager().getRate(name);
        if (rs == null) return 0;
        Rate r = rs.getRate(period);
        return r != null ? r.getAverageValue() : 0;
    }

    /**
     * Read the last-event-count from a rate stat, returning 0 if unavailable.
     * @param name the rate stat name, e.g. "stream.connectionCreated"
     * @param period the rate period in ms, one of the {@link RateConstants} values
     * @return the event count of the most recent period, or 0 when the stat has no data
     */
    private long getStatCount(String name, long period) {
        RateStat rs = _context.statManager().getRate(name);
        if (rs == null) return 0;
        Rate r = rs.getRate(period);
        return r != null ? r.getLastEventCount() : 0;
    }

    /**
     * Count open streaming sockets by direction, walking the tunnel
     * controllers the same way the stream tables do. Used by the debug
     * overview tile.
     *
     * @param tcg the controller group, or null before router init
     * @return {inbound, outbound} socket counts, or null if tcg is null
     * @since 0.9.71
     */
    static int[] countStreams(TunnelControllerGroup tcg) {
        if (tcg == null) {return null;}
        int in = 0, out = 0;
        List<TunnelController> controllers = tcg.getControllers();
        if (controllers == null) {return new int[] {0, 0};}
        for (TunnelController controller : controllers) {
            I2PTunnel tunnel = controller.getTunnel();
            if (tunnel == null) {continue;}
            List<I2PTunnelTask> tasks = tunnel.getTasks();
            if (tasks == null) {continue;}
            for (I2PTunnelTask task : tasks) {
                if (task == null || !task.isOpen()) {continue;}
                I2PSocketManager mgr = task.getSocketManager();
                if (mgr == null) {continue;}
                Set<I2PSocket> sockets = mgr.listSockets();
                if (sockets == null) {continue;}
                boolean inbound = (task instanceof I2PTunnelServer);
                for (I2PSocket sock : sockets) {
                    if (sock == null || sock.isClosed()) {continue;}
                    if (inbound) {in++;} else {out++;}
                }
            }
        }
        return new int[] {in, out};
    }

    /**
     * Writes the table of open streams.
     *
     * @param out where the table is written
     * @throws IOException if the page cannot be written
     */
    private void renderStreamsHTML(Writer out) throws IOException {
        TunnelControllerGroup tcg = TunnelControllerGroup.getInstance();
        if (tcg == null) {
            out.write("<p class=infohelp>Streaming connections will appear here once the router has initialized.</p>\n");
            return;
        }

        List<TunnelController> controllers = tcg.getControllers();
        if (controllers == null || controllers.isEmpty()) {
            out.write("<p class=infohelp>No tunnel controllers found.</p>\n");
            return;
        }

        // Per-direction totals
        int inCount = 0, outCount = 0;
        long inSent = 0, inRecv = 0, outSent = 0, outRecv = 0;

        for (TunnelController controller : controllers) {
            I2PTunnel tunnel = controller.getTunnel();
            if (tunnel == null) continue;
            List<I2PTunnelTask> tasks = tunnel.getTasks();
            if (tasks == null || tasks.isEmpty()) continue;
            for (I2PTunnelTask task : tasks) {
                if (task == null || !task.isOpen()) continue;
                I2PSocketManager mgr = task.getSocketManager();
                if (mgr == null) continue;
                Set<I2PSocket> sockets = mgr.listSockets();
                if (sockets == null || sockets.isEmpty()) continue;
                boolean inbound = (task instanceof I2PTunnelServer);
                for (I2PSocket sock : sockets) {
                    if (sock == null || sock.isClosed()) continue;
                    if (inbound) {
                        inCount++;
                        inSent += sock.getLifetimeBytesSent();
                        inRecv += sock.getLifetimeBytesReceived();
                    } else {
                        outCount++;
                        outSent += sock.getLifetimeBytesSent();
                        outRecv += sock.getLifetimeBytesReceived();
                    }
                }
            }
        }

        renderStreamStats(out, inCount + outCount);

        if (_direction == null || _direction.isEmpty()) {
            renderCombinedTable(out, controllers, inCount, inSent, inRecv, outCount, outSent, outRecv);
        } else {
            boolean inbound = "inbound".equals(_direction);
            if (inbound) {
                renderTable(out, controllers, true, "Peer", inCount, inSent, inRecv);
            } else {
                renderTable(out, controllers, false, "Target", outCount, outSent, outRecv);
            }
        }
    }

    /**
     * Read the average event weight (duration in ms) from a rate stat,
     * returning 0 if unavailable.
     * @param name the rate stat name, e.g. "stream.con.lifetimeBytesSent"
     * @param period the rate period in ms, one of the {@link RateConstants} values
     * @return the average event duration in ms, or 0 when the stat has no data
     * @since 0.9.71
     */
    private double getStatAvgDuration(String name, long period) {
        RateStat rs = _context.statManager().getRate(name);
        if (rs == null) return 0;
        Rate r = rs.getRate(period);
        if (r == null) return 0;
        long count = r.getCurrentEventCount();
        if (count > 0)
            return (double) r.getCurrentTotalEventTime() / count;
        count = r.getLastEventCount();
        return count > 0 ? (double) r.getLastTotalEventTime() / count : 0;
    }

    /**
     * Convert a byte-count stat's average value and average duration into a
     * bytes/sec rate. The stream lifetime byte stats record each closed
     * connection's total bytes weighted by its lifetime, so
     * avgValue/avgDuration is the mean throughput of the streams that closed
     * in the window. Streams that never close report nothing.
     *
     * @param avgBytesPerConn average lifetime bytes per closed connection
     * @param avgLifetimeMs average closed-connection lifetime in ms
     * @return the average rate in bytes/s, 0 when either input is missing
     * @since 0.9.71
     */
    static double closedStreamRateBps(double avgBytesPerConn, double avgLifetimeMs) {
        if (avgBytesPerConn <= 0 || avgLifetimeMs <= 0 ||
            Double.isNaN(avgLifetimeMs) || Double.isInfinite(avgLifetimeMs))
            return 0;
        return avgBytesPerConn / avgLifetimeMs * 1000;
    }

    /**
     * Render the streaming health ring dashboard.
     *
     * @param out the writer the dashboard HTML is written to
     * @param totalSockets the number of open streaming sockets, shown in the
     *                     Active ring; 0 renders that ring with no data
     * @throws IOException if writing to the writer fails
     */
    private void renderStreamStats(Writer out, int totalSockets) throws IOException {
        // Live RTT samples are recorded whenever a connection's RTT updates;
        // the lifetime stat is only recorded at close, so it goes stale while
        // long-lived connections run.
        double rtt = getStatAvg("stream.rtt", RateConstants.ONE_MINUTE);
        if (rtt <= 0) rtt = getStatAvg("stream.con.lifetimeRTT", RateConstants.ONE_MINUTE);
        if (rtt <= 0) rtt = getStatAvg("stream.con.initialRTT.out", RateConstants.ONE_MINUTE);
        String rttStr = rtt > 0 ? (rtt >= 1000 ? String.format("%.1fs", rtt / 1000) : (int) rtt + "ms") : "\u2014";
        double rttScore = rtt > 0 ? Math.max(0, 1.0 - rtt / 15000) : -1;

        double sendsBeforeAck = getStatAvg("stream.sendsBeforeAck", RateConstants.ONE_MINUTE);
        double retransRate = sendsBeforeAck > 1 ? (sendsBeforeAck - 1) / sendsBeforeAck : 0;
        String retransStr = retransRate > 0 ? (int) (retransRate * 100) + "%" : (sendsBeforeAck > 0 ? "0%" : "\u2014");
        double retransScore = sendsBeforeAck > 0 ? Math.max(0, 1.0 - retransRate) : -1;

        long connEvents1m = getStatCount("stream.connectionCreated", RateConstants.ONE_MINUTE)
                          + getStatCount("stream.connectionReceived", RateConstants.ONE_MINUTE);
        double connRate = connEvents1m / 60.0;
        String connRateStr = connRate > 0 ? String.format("%.1f/s", connRate) : (connEvents1m > 0 ? "0/s" : "\u2014");
        double connScore = connRate > 0 ? Math.min(connRate / 5.0, 1.0) : -1;

        double sendBps = closedStreamRateBps(
                getStatAvg("stream.con.lifetimeBytesSent", RateConstants.ONE_MINUTE),
                getStatAvgDuration("stream.con.lifetimeBytesSent", RateConstants.ONE_MINUTE));
        double recvBps = closedStreamRateBps(
                getStatAvg("stream.con.lifetimeBytesReceived", RateConstants.ONE_MINUTE),
                getStatAvgDuration("stream.con.lifetimeBytesReceived", RateConstants.ONE_MINUTE));
        double totalBps = sendBps + recvBps;
        String tpStr = totalBps > 0 ? formatKBps(totalBps) : "\u2014";
        double tpScore = totalBps > 0 ? Math.min(totalBps / 100000.0, 1.0) : -1;

        double activeScore = Math.min((double) totalSockets / 100.0, 1.0);
        if (totalSockets == 0) activeScore = -1;

        out.write("<div id=streamstats>");
        out.write(RingRenderer.renderRingCell(activeScore, _t("Active"), String.valueOf(totalSockets),
                  new String[]{_t("Open streaming connections"), _t("Full at {0}", 100)},
                  RingRenderer.MODE_ACTIVITY));
        out.write(RingRenderer.renderRingCell(rttScore, _t("RTT"), rttStr,
                  new String[]{_t("Round-trip time (live samples, 1 min)")},
                  RingRenderer.MODE_HEALTH));
        out.write(RingRenderer.renderRingCell(retransScore, _t("Retrans"), retransStr,
                  new String[]{_t("Share of sends needing retransmit (1 min)")},
                  RingRenderer.MODE_HEALTH));
        out.write(RingRenderer.renderRingCell(connScore, _t("Conns/s"), connRateStr,
                  new String[]{_t("Created + received (1 min)"), _t("Full at {0}/s", 5)},
                  RingRenderer.MODE_ACTIVITY));
        out.write(RingRenderer.renderRingCell(tpScore, _t("Throughput"), tpStr,
                  new String[]{_t("Mean rate of streams closed in the last minute")},
                  RingRenderer.MODE_ACTIVITY));
        out.write("</div>\n");
    }

    /**
     * Render the combined table for the main page with inbound and outbound sections.
     *
     * @param out the writer the HTML is written to
     * @param controllers the tunnel controllers scanned for open sockets
     * @param inCount the number of inbound sockets, shown in the section header
     * @param inSent total bytes sent on inbound sockets, shown in the header
     * @param inRecv total bytes received on inbound sockets, shown in the header
     * @param outCount the number of outbound sockets, shown in the section header
     * @param outSent total bytes sent on outbound sockets, shown in the header
     * @param outRecv total bytes received on outbound sockets, shown in the header
     * @throws IOException if writing to the writer fails
     */
    private void renderCombinedTable(Writer out, List<TunnelController> controllers,
                                     int inCount, long inSent, long inRecv,
                                     int outCount, long outSent, long outRecv) throws IOException {
        out.write("<div class=streamsContainer>\n");
        out.write("<table class=streams>\n");
        renderTableSection(out, controllers, true, "Peer", inCount, inSent, inRecv);
        out.write("</table>\n");
        out.write("<table class=streams>\n");
        renderTableSection(out, controllers, false, "Target", outCount, outSent, outRecv);
        out.write("</table>\n");
        out.write("</div>\n");
    }

    /**
     * Render one thead (section header + column headers) + tbody for a direction.
     *
     * @param out the writer the HTML is written to
     * @param controllers the tunnel controllers scanned for open sockets
     * @param inbound true renders the inbound (server) section, false the outbound one
     * @param peerHeader the header text for the peer column, "Peer" or "Target"
     * @param count the number of sockets in this direction, shown in the header
     * @param totalSent total bytes sent in this direction, shown in the header
     * @param totalReceived total bytes received in this direction, shown in the header
     * @throws IOException if writing to the writer fails
     */
    private void renderTableSection(Writer out, List<TunnelController> controllers,
                                    boolean inbound, String peerHeader, int count,
                                    long totalSent, long totalReceived) throws IOException {
        String label = inbound ? "Inbound" : "Outbound";
        String totals = "<span class=streams_out>" + formatSize(totalSent) + " " + esc(_t("sent")) +
                        "</span> / <span class=streams_in>" + formatSize(totalReceived) +
                        " " + esc(_t("received")) + "</span>";
        out.write("<thead>\n");
        out.write("<tr><th class=sectionhead colspan=6>" + label + " (" + count + ")" +
                  "<span class=totalBandwidth>" + totals + "</span></th></tr>\n");
        out.write("<tr class=tunnelhead>" +
                  "<th>Type</th>" +
                  "<th>Tunnel</th>" +
                  "<th>" + peerHeader + "</th>" +
                  "<th>Port</th>" +
                  "<th class=tx title=\"Bytes Sent\">&uarr;</th>" +
                  "<th class=rx title=\"Bytes Received\">&darr;</th>" +
                  "</tr>\n");
        out.write("</thead>\n<tbody>\n");

        for (TunnelController controller : controllers) {
            I2PTunnel tunnel = controller.getTunnel();
            if (tunnel == null) continue;
            String tunnelName = controller.getName();
            if (tunnelName == null) tunnelName = "Unnamed";
            List<I2PTunnelTask> tasks = tunnel.getTasks();
            if (tasks == null || tasks.isEmpty()) continue;
            for (I2PTunnelTask task : tasks) {
                if (task == null || !task.isOpen()) continue;
                I2PSocketManager mgr = task.getSocketManager();
                if (mgr == null) continue;
                Set<I2PSocket> sockets = mgr.listSockets();
                if (sockets == null || sockets.isEmpty()) continue;
                boolean isInbound = (task instanceof I2PTunnelServer);
                if (isInbound != inbound) continue;
                String taskType = isInbound ? "Server" : "Client";
                for (I2PSocket sock : sockets) {
                    if (sock == null || sock.isClosed()) continue;
                    out.write(ROW_OPEN);
                    out.write(TD_OPEN);
                    out.write("<span class=" + taskType.toLowerCase() + ">" + taskType + "</span>");
                    out.write(TD_CLOSE);
                    out.write(TD_OPEN);
                    String namePrefix = "";
                    if (!isInbound && mgr != null) {
                        try {
                            Destination localDest = mgr.getSession().getMyDestination();
                            if (localDest != null) {
                                String b32 = localDest.toBase32();
                                if (b32 != null && b32.length() >= 8)
                                    namePrefix = b32.substring(0,8);
                            }
                        } catch (Exception e) { /* ignore */ }
                    }
                    if (!isInbound && !namePrefix.isEmpty()) {
                        out.write("<span class=truncB32>");
                        out.write(esc(namePrefix));
                        out.write("</span> ");
                    }
                    out.write(tunnelName);
                    out.write(TD_CLOSE);
                    out.write(TD_OPEN);
                    Destination peer = sock.getPeerDestination();
                    if (peer != null) {
                        String b32 = peer.toBase32();
                        if (b32 != null) {
                            String hostname = _context.namingService().reverseLookup(peer);
                            String display = (hostname != null) ? hostname : b32.substring(0, Math.min(52, b32.length())) + ".b32.i2p";
                            out.write("<span class=fullb32>" + esc(display) + "</span>");
                        } else {
                            out.write("<span title=unknown>unknown</span>");
                        }
                    } else {
                        out.write("<span title=unknown>unknown</span>");
                    }
                    out.write(TD_CLOSE);
                    if (isInbound) {
                        out.write(TD_OPEN + String.valueOf(sock.getLocalPort()) + TD_CLOSE);
                    } else {
                        out.write(TD_OPEN + String.valueOf(sock.getPort()) + TD_CLOSE);
                    }
                    out.write(TD_OPEN);
                    out.write(formatSize(sock.getLifetimeBytesSent()));
                    out.write(TD_CLOSE);
                    out.write(TD_OPEN);
                    out.write(formatSize(sock.getLifetimeBytesReceived()));
                    out.write(TD_CLOSE);
                    out.write(ROW_CLOSE);
                }
            }
        }
        out.write("</tbody>\n");
    }

    /**
     * Render a simple filtered table (single section, no section header).
     *
     * @param out the writer the HTML is written to
     * @param controllers the tunnel controllers scanned for open sockets
     * @param inbound true renders the inbound (server) rows, false the outbound ones
     * @param peerHeader the header text for the peer column, "Peer" or "Target"
     * @param count the number of sockets in this direction, shown in the header
     * @param totalSent total bytes sent in this direction, shown in the header
     * @param totalReceived total bytes received in this direction, shown in the header
     * @throws IOException if writing to the writer fails
     */
    private void renderTable(Writer out, List<TunnelController> controllers,
                             boolean inbound, String peerHeader,
                             int count, long totalSent, long totalReceived) throws IOException {
        out.write("<div class=streamsContainer>\n");
        String label = inbound ? "Inbound" : "Outbound";
        String totals = "<span class=streams_out>" + formatSize(totalSent) + " " + esc(_t("sent"))
                      + "</span> <span class=streams_in>" + formatSize(totalReceived) + " " + esc(_t("received")) + "</span>";
        out.write("<table class=streams><thead>\n" +
                  "<tr><th class=sectionhead colspan=6>" + label + " (" + count + ")" + "<span class=totalBandwidth>" + totals + "</span></th></tr>\n" +
                   "<tr class=tunnelhead>\n" +
                   "<th>Type</th>\n" +
                   "<th>Tunnel</th>\n" +
                   "<th>" + peerHeader + "</th>\n" +
                   "<th>Port</th>\n" +
                   "<th title=\"Bytes Sent\">&uarr;</th>\n" +
                   "<th title=\"Bytes Received\">&darr;</th>\n" +
                   "</tr>\n</thead>\n<tbody>\n");

        for (TunnelController controller : controllers) {
            I2PTunnel tunnel = controller.getTunnel();
            if (tunnel == null) continue;
            String tunnelName = controller.getName();
            if (tunnelName == null) tunnelName = "Unnamed";
            List<I2PTunnelTask> tasks = tunnel.getTasks();
            if (tasks == null || tasks.isEmpty()) continue;
            for (I2PTunnelTask task : tasks) {
                if (task == null || !task.isOpen()) continue;
                I2PSocketManager mgr = task.getSocketManager();
                if (mgr == null) continue;
                Set<I2PSocket> sockets = mgr.listSockets();
                if (sockets == null || sockets.isEmpty()) continue;
                boolean isInbound = (task instanceof I2PTunnelServer);
                if (isInbound != inbound) continue;
                String taskType = isInbound ? "Server" : "Client";
                for (I2PSocket sock : sockets) {
                    if (sock == null || sock.isClosed()) continue;
                    out.write(ROW_OPEN);
                    out.write(TD_OPEN);
                    out.write("<span class=" + taskType.toLowerCase() + ">" + taskType + "</span>");
                    out.write(TD_CLOSE);
                    out.write(TD_OPEN);
                    out.write(esc(tunnelName));
                    out.write(TD_CLOSE);
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
                    if (isInbound) {
                        out.write(TD_OPEN + String.valueOf(sock.getLocalPort()) + TD_CLOSE);
                    } else {
                        out.write(TD_OPEN + String.valueOf(sock.getPort()) + TD_CLOSE);
                    }
                    out.write(TD_OPEN);
                    out.write(formatSize(sock.getLifetimeBytesSent()));
                    out.write(TD_CLOSE);
                    out.write(TD_OPEN);
                    out.write(formatSize(sock.getLifetimeBytesReceived()));
                    out.write(TD_CLOSE);
                    out.write(ROW_CLOSE);
                }
            }
        }

        if (count == 0) {
            out.write("<tr><td class=empty colspan=6>" + esc(_t("No active streaming connections.")) + "</td></tr>\n");
        }
        out.write("</tbody>\n</table>\n");
        out.write("</div>\n");
    }

    /**
     * Format bytes/sec as KB/s or MB/s (decimal units, no HTML entities).
     *
     * @param bytesPerSec the rate to format, in bytes per second
     * @return the formatted rate with one decimal for KB/s and MB/s, whole
     *         B/s below 1000
     */
    private static String formatKBps(double bytesPerSec) {
        if (bytesPerSec >= 1000000)
            return String.format("%.1f MB/s", bytesPerSec / 1000000);
        else if (bytesPerSec >= 1000)
            return String.format("%.1f KB/s", bytesPerSec / 1000);
        else
            return (int) bytesPerSec + " B/s";
    }

    /**
     * Format bytes without space before unit: "0B", "1.23KiB".
     *
     * @param bytes the byte count to format
     * @return the size with a binary unit suffix and no space, e.g. "1.23KiB"
     */
    private static String formatSize(long bytes) {
        return DataHelper.formatSize2(bytes, false).replace(" ", "") + "B";
    }

    /**
     * HTML-escape a string.
     *
     * @param s the text to escape; null is rendered as an empty string
     * @return the text with ampersands, angle brackets and quotes replaced by
     *         HTML entities
     */
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
