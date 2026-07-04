package net.i2p.router.web.helpers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.Tuner;
import net.i2p.router.Tuner.ParamSnapshot;
import net.i2p.router.transport.Transport;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.router.web.HelperBase;

/**
 * Helper for the transport tuning page (tuning.jsp).
 * Renders tunable param tables with inline SVG sparklines.
 *
 * @since 0.9.70+
 */
public class TuningHelper extends HelperBase {

    private static final int SPARK_W = 140;
    private static final int SPARK_H = 36;

    // human-readable labels for raw param names
    private static final Map<String, String> DISPLAY_NAMES = new HashMap<String, String>();
    static {
        DISPLAY_NAMES.put("ACK_FREQUENCY", "Acknowledgement Frequency");
        DISPLAY_NAMES.put("DATA_MESSAGE_TIMEOUT", "Data Message Timeout");
        DISPLAY_NAMES.put("MAX_OB_ESTABLISH_TIME", "Outbound Establish Timeout");
        DISPLAY_NAMES.put("MAX_IB_ESTABLISH_TIME", "Inbound Establish Timeout");
        DISPLAY_NAMES.put("REQUEUE_TIME", "Tunnel Requeue Delay");
        DISPLAY_NAMES.put("REPLENISH_FREQUENCY", "Tunnel Replenish Frequency");
        DISPLAY_NAMES.put("SELECTOR_LOOP_DELAY", "Tunnel Selector Loop Delay");
        DISPLAY_NAMES.put("MAX_OB_MSGS_PER_PUMP", "Outbound Messages Per Pump");
        DISPLAY_NAMES.put("MAX_IB_MSGS_PER_PUMP", "Inbound Messages Per Pump");
        DISPLAY_NAMES.put("INITIAL_WINDOW_SIZE", "Streaming Initial Window");
        DISPLAY_NAMES.put("INITIAL_RTO", "Streaming Initial Retransmission Timeout");
        DISPLAY_NAMES.put("INITIAL_ACK_DELAY", "Streaming Initial Acknowledgement Delay");
        DISPLAY_NAMES.put("PASSIVE_FLUSH_DELAY", "Streaming Passive Flush Delay");
        DISPLAY_NAMES.put("i2p.streaming.maxSlowStartWindow", "Streaming Maximum Slow Start Window");
        DISPLAY_NAMES.put("i2p.streaming.maxRTO", "Streaming Maximum Retransmission Timeout");
        DISPLAY_NAMES.put("i2p.streaming.maxResendDelay", "Streaming Maximum Resend Delay");
        DISPLAY_NAMES.put("i2p.streaming.maxRetransmissions", "Streaming Maximum Retransmissions");
        DISPLAY_NAMES.put("CLIENT_WRITER_QUEUE_SIZE", "I2CP Writer Queue Size");
        DISPLAY_NAMES.put("CODEL_TARGET", "CoDel Target Delay");
        DISPLAY_NAMES.put("CODEL_INTERVAL", "CoDel Interval");
        DISPLAY_NAMES.put("WESTWOOD_DECAY_FACTOR", "Westwood Decay Factor");
        DISPLAY_NAMES.put("crypto.x25519.precalcMin", "X25519 Precalculate Minimum");
        DISPLAY_NAMES.put("crypto.edh.precalcMin", "EDH Precalculate Minimum");
        DISPLAY_NAMES.put("crypto.mlkem.precalcMin", "ML-KEM Precalculate Minimum");
        DISPLAY_NAMES.put("ntcp.sendFinisher.maxThreads", "NTCP Maximum Send Threads");
        DISPLAY_NAMES.put("ntcp.sendFinisher.queueCapacity", "NTCP Send Queue Capacity");
        DISPLAY_NAMES.put("udp.packetHandler.maxThreads", "UDP Maximum Handler Threads");
        DISPLAY_NAMES.put("router.peerOutboundQueueSize", "Peer Outbound Queue Size");
        DISPLAY_NAMES.put("router.transitThrottleFactor", "Transit Throttle Factor");
        DISPLAY_NAMES.put("router.throttleRejectExponent", "Throttle Reject Exponent");
        DISPLAY_NAMES.put("router.maxParticipatingTunnels", "Maximum Participating Tunnels");
        DISPLAY_NAMES.put("router.buildHandlerMaxQueue", "Build Handler Maximum Queue");
        DISPLAY_NAMES.put("i2p.tunnel.goodDeficitThrottle", "Tunnel Deficit Throttle");
        DISPLAY_NAMES.put("router.tunnel.perTunnelBweDivisor", "Per-Tunnel Bandwidth Divisor");
        DISPLAY_NAMES.put("router.tunnelGrowthFactor", "Tunnel Growth Tolerance");
        DISPLAY_NAMES.put("netdb.searchLimit", "NetDB Search Limit");
        DISPLAY_NAMES.put("netdb.maxConcurrent", "NetDB Maximum Concurrent Searches");
        DISPLAY_NAMES.put("netdb.singleSearchTime", "NetDB Single Search Time");
        DISPLAY_NAMES.put("i2np.udp.maxConcurrentEstablish", "UDP Maximum Concurrent Establish");
        DISPLAY_NAMES.put("profileOrganizer.maxProfiles", "Maximum Peer Profiles");
        DISPLAY_NAMES.put("profileOrganizer.minFastPeers", "Minimum Fast Peers");
        DISPLAY_NAMES.put("i2p.tunnel.build.requestTimeout", "Tunnel Build Request Timeout");
        DISPLAY_NAMES.put("i2p.tunnel.build.firstHopTimeout", "Tunnel Build First Hop Timeout");
        DISPLAY_NAMES.put("i2p.streaming.minResendDelay", "Streaming Minimum Resend Delay");
        DISPLAY_NAMES.put("i2p.streaming.congestionAvoidanceGrowthRateFactor", "Congestion Avoidance Growth Rate");
        DISPLAY_NAMES.put("i2p.streaming.slowStartGrowthRateFactor", "Slow Start Growth Rate");
    }

    // brief purpose descriptions (<=120 chars)
    private static final Map<String, String> PARAM_DESCRIPTIONS = new HashMap<String, String>();
    static {
        PARAM_DESCRIPTIONS.put("ACK_FREQUENCY", "Data packets between each ACK.");
        PARAM_DESCRIPTIONS.put("DATA_MESSAGE_TIMEOUT", "Time in ms before a message is declared lost.");
        PARAM_DESCRIPTIONS.put("MAX_OB_ESTABLISH_TIME", "Outbound SSU handshake timeout in ms.");
        PARAM_DESCRIPTIONS.put("MAX_IB_ESTABLISH_TIME", "Inbound SSU handshake timeout in ms.");
        PARAM_DESCRIPTIONS.put("REQUEUE_TIME", "Pumper idle wait before re-checking work, in ms.");
        PARAM_DESCRIPTIONS.put("REPLENISH_FREQUENCY", "Bandwidth token refill interval in ms.");
        PARAM_DESCRIPTIONS.put("SELECTOR_LOOP_DELAY", "NTCP selector sleep between loops in ms.");
        PARAM_DESCRIPTIONS.put("MAX_OB_MSGS_PER_PUMP", "Outbound messages batched per gateway pump.");
        PARAM_DESCRIPTIONS.put("MAX_IB_MSGS_PER_PUMP", "Inbound messages batched per gateway pump.");
        PARAM_DESCRIPTIONS.put("INITIAL_WINDOW_SIZE", "Starting congestion window in packets.");
        PARAM_DESCRIPTIONS.put("INITIAL_RTO", "Starting retransmission timeout in ms.");
        PARAM_DESCRIPTIONS.put("INITIAL_ACK_DELAY", "ACK delay for piggybacking in ms.");
        PARAM_DESCRIPTIONS.put("PASSIVE_FLUSH_DELAY", "Nagle flush delay in ms.");
        PARAM_DESCRIPTIONS.put("i2p.streaming.maxSlowStartWindow", "Max congestion window during slow start.");
        PARAM_DESCRIPTIONS.put("i2p.streaming.maxRTO", "Max retransmission timeout in ms.");
        PARAM_DESCRIPTIONS.put("i2p.streaming.maxResendDelay", "Max time between retransmissions in ms.");
        PARAM_DESCRIPTIONS.put("i2p.streaming.maxRetransmissions", "Retransmissions before dropping the stream.");
        PARAM_DESCRIPTIONS.put("CLIENT_WRITER_QUEUE_SIZE", "Max I2CP client write queue depth.");
        PARAM_DESCRIPTIONS.put("CODEL_TARGET", "CoDel drop target delay in ms.");
        PARAM_DESCRIPTIONS.put("CODEL_INTERVAL", "CoDel measurement window in ms.");
        PARAM_DESCRIPTIONS.put("WESTWOOD_DECAY_FACTOR", "Westwood EWMA smoothing factor.");
        PARAM_DESCRIPTIONS.put("crypto.x25519.precalcMin", "Min precomputed X25519 key pairs.");
        PARAM_DESCRIPTIONS.put("crypto.edh.precalcMin", "Min precomputed EDH key pairs.");
        PARAM_DESCRIPTIONS.put("crypto.mlkem.precalcMin", "Min precomputed ML-KEM key pairs for PQ.");
        PARAM_DESCRIPTIONS.put("ntcp.sendFinisher.maxThreads", "Max NTCP send finalizer threads.");
        PARAM_DESCRIPTIONS.put("ntcp.sendFinisher.queueCapacity", "Max NTCP send queue depth.");
        PARAM_DESCRIPTIONS.put("udp.packetHandler.maxThreads", "Max UDP packet handler threads.");
        PARAM_DESCRIPTIONS.put("router.peerOutboundQueueSize", "Max outbound queue depth per peer.");
        PARAM_DESCRIPTIONS.put("router.transitThrottleFactor", "Transit rejection aggressiveness (0-1).");
        PARAM_DESCRIPTIONS.put("router.throttleRejectExponent", "Steepness of the transit rejection curve.");
        PARAM_DESCRIPTIONS.put("router.maxParticipatingTunnels", "Max transit tunnels to participate in.");
        PARAM_DESCRIPTIONS.put("router.buildHandlerMaxQueue", "Max queued tunnel build requests.");
        PARAM_DESCRIPTIONS.put("i2p.tunnel.goodDeficitThrottle", "Rebuild delay when pools are healthy in ms.");
        PARAM_DESCRIPTIONS.put("router.tunnel.perTunnelBweDivisor", "Per-tunnel BWE divisor; lower = more each.");
        PARAM_DESCRIPTIONS.put("router.tunnelGrowthFactor", "Tunnel acceptance permissiveness.");
        PARAM_DESCRIPTIONS.put("netdb.searchLimit", "Peers contacted per NetDB lookup.");
        PARAM_DESCRIPTIONS.put("netdb.maxConcurrent", "Simultaneous NetDB searches.");
        PARAM_DESCRIPTIONS.put("netdb.singleSearchTime", "Per-peer NetDB reply timeout in ms.");
        PARAM_DESCRIPTIONS.put("i2np.udp.maxConcurrentEstablish", "Simultaneous SSU handshakes.");
        PARAM_DESCRIPTIONS.put("profileOrganizer.maxProfiles", "Max peer profiles in memory.");
        PARAM_DESCRIPTIONS.put("profileOrganizer.minFastPeers", "Min fast-tier peers to maintain.");
        PARAM_DESCRIPTIONS.put("i2p.tunnel.build.requestTimeout", "Build reply timeout in ms.");
        PARAM_DESCRIPTIONS.put("i2p.tunnel.build.firstHopTimeout", "First-hop build forward timeout in ms.");
        PARAM_DESCRIPTIONS.put("i2p.streaming.minResendDelay", "Min time between retransmissions in ms.");
        PARAM_DESCRIPTIONS.put("i2p.streaming.congestionAvoidanceGrowthRateFactor", "Congestion avoidance growth rate.");
        PARAM_DESCRIPTIONS.put("i2p.streaming.slowStartGrowthRateFactor", "Slow start growth rate.");
    }

    // display order for subsystems
    private static final String[] SUBSYSTEM_ORDER = {
        Tuner.SUB_TRANSPORT,
        Tuner.SUB_TUNNEL,
        Tuner.SUB_STREAMING,
        Tuner.SUB_I2CP,
        Tuner.SUB_CODEL,
        Tuner.SUB_WESTWOOD,
        Tuner.SUB_BUFFERS,
        Tuner.SUB_ROUTER,
        Tuner.SUB_NETDB,
        Tuner.SUB_PEER
    };

    /**
     * Main content: editable tables of tunable params with sparklines.
     */
    public String getTuning() {
        Tuner tuner = getTuner();
        if (tuner == null)
            return "<div class=infowarn id=tuning>" + _t("Auto-Tuning is not available") + "</div>";

        List<ParamSnapshot> snaps = tuner.getSnapshots();
        double healthScore = tuner.getHealthScore();

        // group by subsystem for ordering
        Map<String, List<ParamSnapshot>> groups = new LinkedHashMap<String, List<ParamSnapshot>>();
        for (String sub : SUBSYSTEM_ORDER)
            groups.put(sub, new ArrayList<ParamSnapshot>());
        for (ParamSnapshot s : snaps) {
            List<ParamSnapshot> list = groups.get(s.subsystem);
            if (list == null) {
                list = new ArrayList<ParamSnapshot>();
                groups.put(s.subsystem, list);
            }
            list.add(s);
        }
        // Pre-compute display names for sorting
        java.util.Map<String, String> dn = new java.util.HashMap<String, String>();
        for (ParamSnapshot s : snaps) {
            String display = DISPLAY_NAMES.get(s.name);
            dn.put(s.name, display != null ? display : s.name);
        }
        // Sort each group by display name
        for (List<ParamSnapshot> list : groups.values()) {
            java.util.Collections.sort(list, new java.util.Comparator<ParamSnapshot>() {
                public int compare(ParamSnapshot a, ParamSnapshot b) {
                    return dn.get(a.name).compareToIgnoreCase(dn.get(b.name));
                }
            });
        }

        StringBuilder buf = new StringBuilder(8192);
        buf.append("<div class=main id=tuning>");

        // System health + purpose paragraph
        buf.append("<p class=infohelp>")
           .append(_t("Auto-Tuning continuously monitors router performance and adjusts parameters to optimize throughput, reduce latency, and maintain stability."))
           .append(' ').append(_t("Uncheck the Auto box on any row to pin that parameter and prevent further adjustments."));
        if (!Double.isNaN(healthScore)) {
            String healthLabel;
            if (healthScore >= 0.8) {
                healthLabel = _t("Healthy");
            } else if (healthScore >= 0.5) {
                healthLabel = _t("Fair");
            } else {
                healthLabel = _t("Degraded");
            }
            int pct = (int)(healthScore * 100);
            buf.append(' ' ).append(_t("Current system health:")).append(" <strong>")
               .append(healthLabel).append(" (").append(pct).append("%)</strong>.");
        } else {
            buf.append(_t("System health data is not yet available."));
        }
        buf.append("</p>");

        buf.append("<form id=tuningform method=POST target=processForm>")
           .append("<input type=hidden name=nonce value=\"\">")
           .append("<div class=tablewrap><table id=tuningtable>")
           .append("<tr>")
           .append("<th class=parameter>").append(_t("Parameter")).append("</th>")
           .append("<th class=value>").append(_t("Observed / Default")).append("</th>")
           .append("<th class=min>").append(_t("Min")).append("</th>")
           .append("<th class=max>").append(_t("Max")).append("</th>")
           .append("<th class=step>").append(_t("Step")).append("</th>")
           .append("<th class=history>").append(_t("History")).append("</th>")
           .append("<th class=auto>").append(_t("Auto")).append("</th>")
           .append("</tr>");

        for (Map.Entry<String, List<ParamSnapshot>> entry : groups.entrySet()) {
            List<ParamSnapshot> params = entry.getValue();
            if (params.isEmpty()) continue;

            for (ParamSnapshot s : params) {
                String prefix = toFormPrefix(s.name);
                String sparkSvg = renderSparkline(s.valueHistory, s.defaultValue, s.currentValue);
                String currentDisplay = formatCurrentStat(s.observedStat, s.observedStatValue);

                buf.append("<tr data-prefix=\"").append(prefix).append("\" data-current=\"").append(s.currentValue).append("\">")
                   .append("<td class=parameter title=\"").append(esc(s.name)).append("\">").append(esc(getDisplayName(s.name)));
                String desc = PARAM_DESCRIPTIONS.get(s.name);
                if (desc != null)
                    buf.append("<br><span class=paramdesc>").append(esc(_t(desc))).append("</span>");
                buf.append("</td>")
                   .append("<td class=value>").append(s.currentValue).append(" / " ).append(s.defaultValue).append("</td>")
                   .append("<td class=min><input type=number size=6 name=\"").append(prefix).append("Min\" value=\"").append(s.min).append("\"></td>")
                   .append("<td class=max><input type=number size=6 name=\"").append(prefix).append("Max\" value=\"").append(s.max).append("\"></td>")
                   .append("<td class=step><input type=number size=4 name=\"").append(prefix).append("Step\" value=\"").append(s.step).append("\"></td>")
                   .append("<td class=history>").append(sparkSvg).append("</td>")
                   .append("<td class=auto>")
                   // hidden field sends current value when checkbox unchecked
                   .append("<input type=hidden name=\"").append(prefix).append("Override\" value=\"").append(s.currentValue).append("\">")
                   // checkbox sends -1 when checked (auto-tuning on)
                   .append("<input type=checkbox class=\"optbox slider\" name=\"").append(prefix).append("Override\" value=\"-1\"")
                   .append(s.autoTuning ? " checked" : "").append("></td>")
                   .append("</tr>");
            }
        }

        buf.append("<tr><td class=optionsave colspan=8><input type=submit name=action class=accept value=\"").append(_t("Save")).append("\"></td></tr>");
        buf.append("</table></form></div>");
        return buf.toString();
    }

    /**
     * Format the observed stat as a compact current value with unit.
     * e.g. "23ms", "1.2K", "98%".
     */
    private static String formatCurrentStat(String statName, double value) {
        if (statName == null || Double.isNaN(value))
            return "&mdash;";
        boolean isTime = statName.contains("Time") || statName.contains("Lag")
                         || statName.contains("Delay") || statName.contains("RTT")
                         || statName.contains("RTO") || statName.contains("Establish");
        String formatted;
        String unit = "";
        if (isTime) {
            // time stats are in ms; show as seconds when >= 1000ms
            if (value >= 1000000)
                formatted = String.format("%.1fKs", value / 1000000.0);
            else if (value >= 1000)
                formatted = String.format("%.1fs", value / 1000.0);
            else if (value == (long) value)
                formatted = String.valueOf((long) value) + "ms";
            else
                formatted = String.format("%.1fms", value);
        } else if (statName.contains("Ratio") || statName.contains("Rate") || statName.contains("Percent")) {
            formatted = String.format("%.1f%%", value * 100);
        } else if (statName.contains("Bps") || statName.contains("bps")) {
            if (value >= 1000000)
                formatted = String.format("%.1fMB/s", value / 1000000.0);
            else if (value >= 1000)
                formatted = String.format("%.1fKB/s", value / 1000.0);
            else
                formatted = String.format("%.0fB/s", value);
        } else {
            // dimensionless counts, sizes, etc.
            if (value >= 1000000)
                formatted = String.format("%.1fM", value / 1000000.0);
            else if (value >= 1000)
                formatted = String.format("%.1fK", value / 1000.0);
            else if (value == (long) value)
                formatted = String.valueOf((long) value);
            else
                formatted = String.format("%.1f", value);
        }
        return formatted + unit;
    }

    /**
     * Convert a param name like "ACK_FREQUENCY" or "i2p.streaming.maxSlowStartWindow"
     * to a form prefix like "ackFrequency" or "maxSlowStartWindow".
     * Strips domain prefixes (i2p., crypto., ntcp., udp., router., etc.).
     */
    private static String toFormPrefix(String name) {
        // strip domain prefix (e.g. "i2p.streaming." -> "maxSlowStartWindow")
        String bare = name;
        int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < name.length() - 1)
            bare = name.substring(lastDot + 1);

        String[] parts = bare.split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].toLowerCase();
            if (i == 0) {
                sb.append(p);
            } else {
                sb.append(Character.toUpperCase(p.charAt(0)))
                  .append(p.substring(1));
            }
        }
        return sb.toString();
    }

    /**
     * Return a human-readable display name for a raw param name,
     * falling back to the raw name if unmapped.
     */
    private String getDisplayName(String name) {
        String display = DISPLAY_NAMES.get(name);
        if (display == null) return name;
        return _t(display);
    }

    /**
     * Render an inline SVG sparkline.
     * Diverging bar chart: center line = default value.
     * Green bars above = value above default (tuned up).
     * Red bars below = value below default (tuned down).
     */
    private static String renderSparkline(int[] values, int defaultValue, int current) {
        int n = values.length;
        if (n < 2) return "<span class=spark>collecting...</span>";

        int pad = 4;
        int pw = SPARK_W - pad * 2;
        int ph = SPARK_H - pad * 2;
        int midY = pad + ph / 2;

        StringBuilder sb = new StringBuilder(512);
        sb.append("<svg class=\"spark\" viewBox=\"0 0 ").append(SPARK_W).append(" ").append(SPARK_H).append("\">");

        // center line = default value
        sb.append("<line x1=\"").append(pad).append("\" y1=\"").append(midY)
          .append("\" x2=\"").append(pad + pw).append("\" y2=\"").append(midY)
          .append("\" stroke=\"#888\" stroke-width=\"0.5\" opacity=\"0.5\"/>");

        // compute scale: max deviation from default in either direction
        int maxDev = 1;
        for (int i = 0; i < n; i++) {
            int dev = Math.abs(values[i] - defaultValue);
            if (dev > maxDev) maxDev = dev;
        }

        int halfH = ph / 2;

        // diverging bars: green above center, red below
        // newest on left, oldest on right
        int barW = Math.max(1, pw / n);
        for (int i = 0; i < n; i++) {
            int bx = pad + ((n - 1 - i) * pw) / n;
            int dev = values[i] - defaultValue;
            int bh = (int)((double) Math.abs(dev) / maxDev * halfH);
            if (bh < 1 && dev != 0) bh = 1;
            String color = "var(--graphbar)";
            int barY = (dev >= 0) ? midY - bh : midY;
            sb.append("<rect x=\"").append(bx).append("\" y=\"").append(barY)
              .append("\" width=\"").append(barW).append("\" height=\"").append(Math.max(1, bh))
              .append("\" fill=\"").append(color).append("\"/>");
        }

        sb.append("</svg>");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * @return the UDPTransport's Tuner, or null
     */
    private Tuner getTuner() {
        if (_context == null) return null;
        CommSystemFacade cs = _context.commSystem();
        if (cs == null) return null;
        SortedMap<String, Transport> transports = cs.getTransports();
        Transport udp = transports.get(UDPTransport.STYLE);
        if (udp instanceof UDPTransport)
            return ((UDPTransport) udp).getTuner();
        return null;
    }
}
