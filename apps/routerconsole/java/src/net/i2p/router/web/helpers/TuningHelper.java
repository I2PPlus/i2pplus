package net.i2p.router.web.helpers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    private String _nonce;

    public void setNonce(String nonce) { _nonce = nonce; }

    // human-readable labels for raw param names
    private static final Map<String, String> DISPLAY_NAMES = new HashMap<>();
    static {
        DISPLAY_NAMES.put("ACK_FREQUENCY", "Acknowledgement Frequency");
        DISPLAY_NAMES.put("DATA_MESSAGE_TIMEOUT", "Data Message Timeout");
        DISPLAY_NAMES.put("MAX_OB_ESTABLISH_TIME", "Outbound Establish Timeout");
        DISPLAY_NAMES.put("MAX_IB_ESTABLISH_TIME", "Inbound Establish Timeout");
        DISPLAY_NAMES.put("NTCP_ESTABLISH_TIMEOUT", "NTCP Establish Timeout");
        DISPLAY_NAMES.put("REQUEUE_TIME", "Tunnel Requeue Delay");
        DISPLAY_NAMES.put("REPLENISH_FREQUENCY", "Tunnel Replenish Frequency");
        DISPLAY_NAMES.put("SELECTOR_LOOP_DELAY", "Tunnel Selector Loop Delay");
        DISPLAY_NAMES.put("MAX_OB_MSGS_PER_PUMP", "Outbound Messages Per Pump");
        DISPLAY_NAMES.put("MAX_IB_MSGS_PER_PUMP", "Inbound Messages Per Pump");
        DISPLAY_NAMES.put("INITIAL_WINDOW_SIZE", "Initial Congestion Window");
        DISPLAY_NAMES.put("INITIAL_RTO", "Initial RTO");
        DISPLAY_NAMES.put("INITIAL_ACK_DELAY", "ACK Delay");
        DISPLAY_NAMES.put("PASSIVE_FLUSH_DELAY", "Nagle Flush Delay");
        DISPLAY_NAMES.put("i2p.streaming.maxSlowStartWindow", "Slow Start Window Cap");
        DISPLAY_NAMES.put("i2p.streaming.maxRTO", "Max RTO");
        DISPLAY_NAMES.put("i2p.streaming.maxResendDelay", "Max Resend Delay");
        DISPLAY_NAMES.put("i2p.streaming.maxRetransmissions", "Max Retransmissions");
        DISPLAY_NAMES.put("CLIENT_WRITER_QUEUE_SIZE", "Writer Queue Size");
        DISPLAY_NAMES.put("CODEL_TARGET", "CoDel Target Delay");
        DISPLAY_NAMES.put("CODEL_INTERVAL", "CoDel Interval");
        DISPLAY_NAMES.put("WESTWOOD_DECAY_FACTOR", "Westwood Decay Factor");
        DISPLAY_NAMES.put("RED_MIN_THRESHOLD", "RED Minimum Queue Threshold");
        DISPLAY_NAMES.put("RED_MAX_THRESHOLD", "RED Maximum Queue Threshold");
        DISPLAY_NAMES.put("RED_MAX_DROP_PROB", "RED Maximum Drop Probability");
        DISPLAY_NAMES.put("crypto.x25519.precalcMin", "X25519 Precalculate Minimum");
        DISPLAY_NAMES.put("crypto.edh.precalcMin", "EDH Precalculate Minimum");
        DISPLAY_NAMES.put("crypto.mlkem.precalcMin", "ML-KEM Precalculate Minimum");
        DISPLAY_NAMES.put("ntcp.sendFinisher.maxThreads", "NTCP Maximum Send Threads");
        DISPLAY_NAMES.put("ntcp.sendFinisher.threads", "NTCP Send Finisher Threads");
        DISPLAY_NAMES.put("ntcp.sendFinisher.queueCapacity", "NTCP Send Queue Capacity");
        DISPLAY_NAMES.put("udp.messageReceiver.threads", "UDP Message Receiver Threads");
        DISPLAY_NAMES.put("udp.packetHandler.maxThreads", "UDP Maximum Handler Threads");
        DISPLAY_NAMES.put("rdns.corePoolSize", "rDNS Executor Threads");
        DISPLAY_NAMES.put("router.peerOutboundQueueSize", "Peer Outbound Queue Size");
        DISPLAY_NAMES.put("router.transitThrottleFactor", "Transit Throttle Factor");
        DISPLAY_NAMES.put("router.throttleRejectExponent", "Throttle Reject Exponent");
        DISPLAY_NAMES.put("router.maxParticipatingTunnels", "Maximum Participating Tunnels");
        DISPLAY_NAMES.put("router.buildHandlerMaxQueue", "Build Handler Maximum Queue");
        DISPLAY_NAMES.put("i2p.tunnel.goodDeficitThrottle", "Rebuild Throttle Interval");
        DISPLAY_NAMES.put("router.tunnel.perTunnelBweDivisor", "Per-Tunnel Bandwidth Divisor");
        DISPLAY_NAMES.put("router.tunnelGrowthFactor", "Tunnel Growth Tolerance");
        DISPLAY_NAMES.put("netdb.searchLimit", "Peers Per Search");
        DISPLAY_NAMES.put("netdb.maxConcurrent", "Max Concurrent Searches");
        DISPLAY_NAMES.put("netdb.singleSearchTime", "Search Timeout");
        DISPLAY_NAMES.put("i2np.udp.maxConcurrentEstablish", "Max Concurrent Handshakes");
        DISPLAY_NAMES.put("profileOrganizer.maxProfiles", "Maximum Peer Profiles");
        DISPLAY_NAMES.put("profileOrganizer.minFastPeers", "Minimum Fast Peers");
        DISPLAY_NAMES.put("profileOrganizer.maxFastPeers", "Maximum Fast Peers");
        DISPLAY_NAMES.put("profileOrganizer.minHighCapacityPeers", "Minimum High Capacity Peers");
        DISPLAY_NAMES.put("profileOrganizer.maxHighCapacityPeers", "Maximum High Capacity Peers");
        DISPLAY_NAMES.put("i2p.tunnel.build.requestTimeout", "Tunnel Build Request Timeout");
        DISPLAY_NAMES.put("i2p.tunnel.build.firstHopTimeout", "Tunnel Build First Hop Timeout");
        DISPLAY_NAMES.put("tunnel.build.maxConcurrent", "Max Concurrent Tunnel Builds");
        DISPLAY_NAMES.put("i2p.tunnel.build.maxLookupLimit", "Max Concurrent RI Lookups");
        DISPLAY_NAMES.put("tunnel.testJob.maxQueued", "Max Concurrent Test Jobs");
        DISPLAY_NAMES.put("tunnel.testJob.minTestDelay", "Min Delay Between Tests");
        DISPLAY_NAMES.put("tunnel.testJob.maxTestDelay", "Max Delay Between Tests");
        DISPLAY_NAMES.put("ntcp.reader.threads", "NTCP Reader Threads");
        DISPLAY_NAMES.put("ntcp.writer.threads", "NTCP Writer Threads");
        DISPLAY_NAMES.put("ntcp.failsafe.iterationFreq", "NTCP Pumper Failsafe Interval");
        DISPLAY_NAMES.put("udp.peer.concurrentMaxMessages", "Max Concurrent Peer Messages");
        DISPLAY_NAMES.put("udp.peer.initConcurrentMsgs", "Initial UDP Concurrent Messages");
        DISPLAY_NAMES.put("udp.peer.minConcurrentMsgs", "Min UDP Concurrent Messages");
        DISPLAY_NAMES.put("udp.peer.initRTO", "Initial UDP RTO");
        DISPLAY_NAMES.put("udp.peer.minRTO", "Min UDP RTO");
        DISPLAY_NAMES.put("udp.peer.maxRTO", "Max UDP RTO");
        DISPLAY_NAMES.put("udp.peer.maxSendWindow", "Max UDP Send Window");
        DISPLAY_NAMES.put("ntcp.sendPool.capacity", "NTCP Send Pool Capacity");
        DISPLAY_NAMES.put("i2cp.internalQueueSize", "Internal Queue Size");
        DISPLAY_NAMES.put("udp.establish.maxQueuedOutbound", "Max Pending Handshakes");
        DISPLAY_NAMES.put("ntcp.maxWriteBufs", "NTCP Max Write Buffers Per Connection");
        DISPLAY_NAMES.put("i2p.streaming.minResendDelay", "Min Resend Delay");
        DISPLAY_NAMES.put("i2p.streaming.congestionAvoidanceGrowthRateFactor", "Congestion Avoidance Growth Rate");
        DISPLAY_NAMES.put("i2p.streaming.slowStartGrowthRateFactor", "Slow Start Growth Rate");
        DISPLAY_NAMES.put("i2p.streaming.maxRtt", "RTT Cap");
        DISPLAY_NAMES.put("i2p.streaming.initialResendDelay", "Initial Resend Delay");
        DISPLAY_NAMES.put("i2p.streaming.immediateAckDelay", "Dup ACK Delay");
        DISPLAY_NAMES.put("i2p.router.maxDispatchAge", "Max Message Queue Age");
        DISPLAY_NAMES.put("i2p.router.handlerThreadPriority", "I/O Thread Priority");
        DISPLAY_NAMES.put("tunnel.pumper.queueCapacity", "Pumper Queue Capacity");
        DISPLAY_NAMES.put("tunnel.pumper.threads", "Pumper Threads");
        DISPLAY_NAMES.put("i2ptunnel.serverHandler.threads", "Server Handler Threads");
        DISPLAY_NAMES.put("router.buildHandlerThreads", "Build Handler Threads");
        DISPLAY_NAMES.put("i2ptunnel.clientRunner.max", "Client Runner Max Threads");
        DISPLAY_NAMES.put("i2p.tunnel.participatingThrottle.minLimit", "Participating Throttle Min");
        DISPLAY_NAMES.put("i2p.tunnel.participatingThrottle.maxLimit", "Participating Throttle Max");
        DISPLAY_NAMES.put("i2p.tunnel.participatingThrottle.percentLimit", "Participating Throttle Percent");
        DISPLAY_NAMES.put("i2p.tunnel.requestThrottle.minLimit", "Request Throttle Min");
        DISPLAY_NAMES.put("i2p.tunnel.requestThrottle.maxLimit", "Request Throttle Max");
        DISPLAY_NAMES.put("i2p.tunnel.requestThrottle.percentLimit", "Request Throttle Percent");
        DISPLAY_NAMES.put("i2p.tunnel.requestThrottle.burst1sThreshold", "Request Throttle Burst Threshold");
        DISPLAY_NAMES.put("i2p.tunnel.requestThrottle.highLoadLagMs", "High-Load Lag Threshold");
        DISPLAY_NAMES.put("i2p.tunnel.requestThrottle.highLoadCpuPct", "High-Load CPU Threshold");
        DISPLAY_NAMES.put("i2p.tunnel.requestThrottle.moderateLoadLagMs", "Moderate-Load Lag Threshold");
        DISPLAY_NAMES.put("i2p.tunnel.requestThrottle.moderateLoadCpuPct", "Moderate-Load CPU Threshold");
        DISPLAY_NAMES.put("i2p.tunnel.requestThrottle.sustainedHighLoadMs", "Sustained High-Load Window");
        DISPLAY_NAMES.put("i2p.tunnel.requestThrottle.sustainedModerateLoadMs", "Sustained Moderate-Load Window");
        DISPLAY_NAMES.put("tunnel.pool.failureThreshold", "Pool Build Failure Threshold");
        DISPLAY_NAMES.put("tunnel.pool.backoffMs", "Pool Build Backoff");
    }

    // brief purpose descriptions (<=120 chars)
    private static final Map<String, String> PARAM_DESCRIPTIONS = new HashMap<>();
    static {
        PARAM_DESCRIPTIONS.put("ACK_FREQUENCY", "Data packets between each ACK.");
        PARAM_DESCRIPTIONS.put("DATA_MESSAGE_TIMEOUT", "Time before a message is declared lost (ms).");
        PARAM_DESCRIPTIONS.put("MAX_OB_ESTABLISH_TIME", "Outbound SSU handshake timeout (ms).");
        PARAM_DESCRIPTIONS.put("MAX_IB_ESTABLISH_TIME", "Inbound SSU handshake timeout (ms).");
        PARAM_DESCRIPTIONS.put("NTCP_ESTABLISH_TIMEOUT", "NTCP2 handshake timeout (ms).");
        PARAM_DESCRIPTIONS.put("REQUEUE_TIME", "Pumper idle wait before re-checking work (ms).");
        PARAM_DESCRIPTIONS.put("REPLENISH_FREQUENCY", "Bandwidth token refill interval (ms).");
        PARAM_DESCRIPTIONS.put("SELECTOR_LOOP_DELAY", "NTCP selector sleep between loops (ms).");
        PARAM_DESCRIPTIONS.put("MAX_OB_MSGS_PER_PUMP", "Outbound messages batched per gateway pump.");
        PARAM_DESCRIPTIONS.put("MAX_IB_MSGS_PER_PUMP", "Inbound messages batched per gateway pump.");
        PARAM_DESCRIPTIONS.put("INITIAL_WINDOW_SIZE", "Starting congestion window (packets).");
        PARAM_DESCRIPTIONS.put("INITIAL_RTO", "Starting retransmission timeout (ms).");
        PARAM_DESCRIPTIONS.put("INITIAL_ACK_DELAY", "ACK delay for piggybacking (ms).");
        PARAM_DESCRIPTIONS.put("PASSIVE_FLUSH_DELAY", "Nagle flush delay (ms).");
        PARAM_DESCRIPTIONS.put("i2p.streaming.maxSlowStartWindow", "Max congestion window during slow start.");
        PARAM_DESCRIPTIONS.put("i2p.streaming.maxRTO", "Max retransmission timeout (ms).");
        PARAM_DESCRIPTIONS.put("i2p.streaming.maxResendDelay", "Max time between retransmissions (ms).");
        PARAM_DESCRIPTIONS.put("i2p.streaming.maxRetransmissions", "Retransmissions before dropping the stream.");
        PARAM_DESCRIPTIONS.put("CLIENT_WRITER_QUEUE_SIZE", "I2CP write queue depth.");
        PARAM_DESCRIPTIONS.put("CODEL_TARGET", "CoDel drop target delay (ms).");
        PARAM_DESCRIPTIONS.put("CODEL_INTERVAL", "CoDel measurement window (ms).");
        PARAM_DESCRIPTIONS.put("WESTWOOD_DECAY_FACTOR", "Westwood EWMA smoothing factor.");
        PARAM_DESCRIPTIONS.put("RED_MIN_THRESHOLD", "Min RED queue size before probabilistic drops (bytes).");
        PARAM_DESCRIPTIONS.put("RED_MAX_THRESHOLD", "Max RED queue size for full drops (bytes).");
        PARAM_DESCRIPTIONS.put("RED_MAX_DROP_PROB", "Max RED drop probability when queue is full (ppm).");
        PARAM_DESCRIPTIONS.put("crypto.x25519.precalcMin", "Precomputed X25519 key pool minimum.");
        PARAM_DESCRIPTIONS.put("crypto.edh.precalcMin", "Precomputed EDH key pool minimum.");
        PARAM_DESCRIPTIONS.put("crypto.mlkem.precalcMin", "Precomputed ML-KEM key pool minimum.");
        PARAM_DESCRIPTIONS.put("ntcp.sendFinisher.maxThreads", "Thread pool cap for NTCP send completion.");
        PARAM_DESCRIPTIONS.put("ntcp.sendFinisher.threads", "Active threads completing NTCP sends.");
        PARAM_DESCRIPTIONS.put("ntcp.sendFinisher.queueCapacity", "NTCP send finalizer queue size.");
        PARAM_DESCRIPTIONS.put("udp.packetHandler.maxThreads", "Thread pool cap for UDP packet dispatch.");
        PARAM_DESCRIPTIONS.put("udp.messageReceiver.threads", "Threads reassembling UDP messages from fragments.");
        PARAM_DESCRIPTIONS.put("rdns.corePoolSize", "Threads performing reverse DNS lookups.");
        PARAM_DESCRIPTIONS.put("router.peerOutboundQueueSize", "Per-peer outbound queue size.");
        PARAM_DESCRIPTIONS.put("router.transitThrottleFactor", "Transit rejection aggressiveness (50-100%).");
        PARAM_DESCRIPTIONS.put("router.throttleRejectExponent", "Steepness of the transit rejection curve.");
        PARAM_DESCRIPTIONS.put("router.maxParticipatingTunnels", "Transit tunnel capacity.");
        PARAM_DESCRIPTIONS.put("router.buildHandlerMaxQueue", "Max queued build requests.");
        PARAM_DESCRIPTIONS.put("i2p.tunnel.goodDeficitThrottle", "Delay between rebuild rounds when pools are healthy (ms).");
        PARAM_DESCRIPTIONS.put("router.tunnel.perTunnelBweDivisor", "Divides bandwidth among transit tunnels.");
        PARAM_DESCRIPTIONS.put("router.tunnelGrowthFactor", "How aggressively new tunnels are accepted before throttling.");
        PARAM_DESCRIPTIONS.put("netdb.searchLimit", "Peers queried per iterative search.");
        PARAM_DESCRIPTIONS.put("netdb.maxConcurrent", "Max simultaneous NetDB lookups.");
        PARAM_DESCRIPTIONS.put("netdb.singleSearchTime", "Per-peer lookup timeout (ms).");
        PARAM_DESCRIPTIONS.put("i2np.udp.maxConcurrentEstablish", "Max simultaneous SSU handshakes.");
        PARAM_DESCRIPTIONS.put("profileOrganizer.maxProfiles", "Memory cap for peer profiles.");
        PARAM_DESCRIPTIONS.put("profileOrganizer.minFastPeers", "Min fast peers in routing table.");
        PARAM_DESCRIPTIONS.put("profileOrganizer.maxFastPeers", "Max fast peers in routing table.");
        PARAM_DESCRIPTIONS.put("profileOrganizer.minHighCapacityPeers", "Min high-capacity peers in routing table.");
        PARAM_DESCRIPTIONS.put("profileOrganizer.maxHighCapacityPeers", "Max high-capacity peers in routing table.");
        PARAM_DESCRIPTIONS.put("i2p.tunnel.build.requestTimeout", "Build reply timeout (ms).");
        PARAM_DESCRIPTIONS.put("i2p.tunnel.build.firstHopTimeout", "First-hop build forward timeout (ms).");
        PARAM_DESCRIPTIONS.put("tunnel.build.maxConcurrent", "Max concurrent tunnel builds.");
        PARAM_DESCRIPTIONS.put("i2p.tunnel.build.maxLookupLimit", "Max concurrent RI lookups during builds.");
        PARAM_DESCRIPTIONS.put("tunnel.testJob.maxQueued", "Max concurrent tunnel test jobs.");
        PARAM_DESCRIPTIONS.put("tunnel.testJob.minTestDelay", "Min interval between tunnel tests (ms).");
        PARAM_DESCRIPTIONS.put("tunnel.testJob.maxTestDelay", "Max interval between tunnel tests (ms).");
        PARAM_DESCRIPTIONS.put("ntcp.reader.threads", "Threads decrypting inbound NTCP data.");
        PARAM_DESCRIPTIONS.put("ntcp.writer.threads", "Threads encrypting NTCP messages.");
        PARAM_DESCRIPTIONS.put("ntcp.failsafe.iterationFreq", "Failsafe scan interval for stuck NTCP pumps (ms).");
        PARAM_DESCRIPTIONS.put("udp.peer.concurrentMaxMessages", "In-flight message limit per UDP peer.");
        PARAM_DESCRIPTIONS.put("udp.peer.initConcurrentMsgs", "Initial concurrent messages per peer.");
        PARAM_DESCRIPTIONS.put("udp.peer.minConcurrentMsgs", "Min concurrent messages per peer.");
        PARAM_DESCRIPTIONS.put("udp.peer.initRTO", "Initial retransmission timeout (ms).");
        PARAM_DESCRIPTIONS.put("udp.peer.minRTO", "Min retransmission timeout (ms).");
        PARAM_DESCRIPTIONS.put("udp.peer.maxRTO", "Max retransmission timeout (ms).");
        PARAM_DESCRIPTIONS.put("udp.peer.maxSendWindow", "Max unacknowledged messages per peer.");
        PARAM_DESCRIPTIONS.put("ntcp.sendPool.capacity", "NTCP send pool queue size.");
        PARAM_DESCRIPTIONS.put("i2cp.internalQueueSize", "I2CP session buffer size.");
        PARAM_DESCRIPTIONS.put("udp.establish.maxQueuedOutbound", "Pending outbound handshake queue.");
        PARAM_DESCRIPTIONS.put("ntcp.maxWriteBufs", "Write buffer per NTCP connection.");
        PARAM_DESCRIPTIONS.put("i2p.streaming.minResendDelay", "Min time between retransmissions (ms).");
        PARAM_DESCRIPTIONS.put("i2p.streaming.congestionAvoidanceGrowthRateFactor", "Congestion avoidance growth rate.");
        PARAM_DESCRIPTIONS.put("i2p.streaming.slowStartGrowthRateFactor", "Window multiplier per RTT during slow start.");
        PARAM_DESCRIPTIONS.put("i2p.streaming.maxRtt", "Upper bound on RTT estimate (ms).");
        PARAM_DESCRIPTIONS.put("i2p.streaming.initialResendDelay", "Delay before first retransmit (ms).");
        PARAM_DESCRIPTIONS.put("i2p.streaming.immediateAckDelay", "Delay for dup or OOO packet ACKs (ms).");
        PARAM_DESCRIPTIONS.put("i2p.router.maxDispatchAge", "Max message age in queue before drop (ms).");
        PARAM_DESCRIPTIONS.put("i2p.router.handlerThreadPriority", "Dynamic priority for I/O handler threads.");
        PARAM_DESCRIPTIONS.put("tunnel.pumper.queueCapacity", "Gateway pumper queue size.");
        PARAM_DESCRIPTIONS.put("tunnel.pumper.threads", "Gateway pumper thread count.");
        PARAM_DESCRIPTIONS.put("i2ptunnel.serverHandler.threads", "Handler threads for incoming I2PTunnel connections.");
        PARAM_DESCRIPTIONS.put("router.buildHandlerThreads", "Thread pool for inbound build request processing.");
        PARAM_DESCRIPTIONS.put("i2ptunnel.clientRunner.max", "Ceiling for client proxy thread pool.");
        PARAM_DESCRIPTIONS.put("i2p.tunnel.participatingThrottle.minLimit", "Min transit tunnels any peer can hold.");
        PARAM_DESCRIPTIONS.put("i2p.tunnel.participatingThrottle.maxLimit", "Max transit tunnels a single peer can hold.");
        PARAM_DESCRIPTIONS.put("i2p.tunnel.participatingThrottle.percentLimit", "Max share of transit tunnels for a single peer.");
        PARAM_DESCRIPTIONS.put("i2p.tunnel.requestThrottle.minLimit", "Min inbound requests accepted from any peer.");
        PARAM_DESCRIPTIONS.put("i2p.tunnel.requestThrottle.maxLimit", "Max inbound requests accepted from any peer.");
        PARAM_DESCRIPTIONS.put("i2p.tunnel.requestThrottle.percentLimit", "Max share of tunnel requests from a single peer.");
        PARAM_DESCRIPTIONS.put("i2p.tunnel.requestThrottle.burst1sThreshold", "Burst threshold: requests per second before a ban.");
        PARAM_DESCRIPTIONS.put("i2p.tunnel.requestThrottle.highLoadLagMs", "Job queue lag triggering high-load request gating (ms).");
        PARAM_DESCRIPTIONS.put("i2p.tunnel.requestThrottle.highLoadCpuPct", "System load percent triggering high-load request gating.");
        PARAM_DESCRIPTIONS.put("i2p.tunnel.requestThrottle.moderateLoadLagMs", "Job queue lag triggering moderate-load peer disconnect (ms).");
        PARAM_DESCRIPTIONS.put("i2p.tunnel.requestThrottle.moderateLoadCpuPct", "System load percent triggering moderate-load disconnect.");
        PARAM_DESCRIPTIONS.put("i2p.tunnel.requestThrottle.sustainedHighLoadMs", "Duration high load must persist before gating requests (ms).");
        PARAM_DESCRIPTIONS.put("i2p.tunnel.requestThrottle.sustainedModerateLoadMs", "Duration moderate load must persist before disconnecting peers (ms).");
        PARAM_DESCRIPTIONS.put("tunnel.pool.failureThreshold", "Consecutive failures before pool backoff.");
        PARAM_DESCRIPTIONS.put("tunnel.pool.backoffMs", "Cooldown duration after pool failure threshold, in ms.");
    }

    // display order for subsystems (alphabetical)
    private static final String[] SUBSYSTEM_ORDER = {
        Tuner.SUB_CONGESTION,
        Tuner.SUB_CRYPTO,
        Tuner.SUB_I2CP,
        Tuner.SUB_NETDB,
        Tuner.SUB_PEER,
        Tuner.SUB_ROUTER,
        Tuner.SUB_STREAMING,
        Tuner.SUB_TUNNEL,
        Tuner.SUB_TRANSPORT
    };

    // single-word section headings
    private static final Map<String, String> SECTION_LABELS = new LinkedHashMap<>();
    static {
        SECTION_LABELS.put(Tuner.SUB_TRANSPORT, "Transport");
        SECTION_LABELS.put(Tuner.SUB_TUNNEL, "Tunnel");
        SECTION_LABELS.put(Tuner.SUB_STREAMING, "Streaming");
        SECTION_LABELS.put(Tuner.SUB_I2CP, "I2CP");
        SECTION_LABELS.put(Tuner.SUB_CONGESTION, "Congestion");
        SECTION_LABELS.put(Tuner.SUB_CRYPTO, "Crypto");
        SECTION_LABELS.put(Tuner.SUB_ROUTER, "Router");
        SECTION_LABELS.put(Tuner.SUB_NETDB, "NetDB");
        SECTION_LABELS.put(Tuner.SUB_PEER, "Peers");
    }

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
        Map<String, List<ParamSnapshot>> groups = new LinkedHashMap<>();
        for (String sub : SUBSYSTEM_ORDER)
            groups.put(sub, new ArrayList<>());
        for (ParamSnapshot s : snaps) {
            List<ParamSnapshot> list = groups.get(s.subsystem);
            if (list == null) {
                list = new ArrayList<>();
                groups.put(s.subsystem, list);
            }
            list.add(s);
        }
        // Pre-compute display names for sorting
        Map<String, String> dn = new HashMap<>();
        for (ParamSnapshot s : snaps) {
            String display = DISPLAY_NAMES.get(s.name);
            dn.put(s.name, display != null ? display : s.name);
        }
        // Sort each group by display name
        for (List<ParamSnapshot> list : groups.values()) {
            Collections.sort(list, new Comparator<ParamSnapshot>() {
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
           .append(' ').append(_t("Uncheck the Auto box on any row to pin that parameter and prevent further adjustments.")).append(" <span id=health>");
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
            buf.append(_t("Current system health:")).append(" <b>")
               .append(healthLabel).append(" (").append(pct).append("%)</b>.");
        } else {
            buf.append(_t("System health assessment is not yet available."));
        }
        buf.append("</span></p>");

        // Subsystem ring chart dashboard
        buf.append(renderSubsystemRings(tuner));

        buf.append("<form id=tuningform method=POST target=processForm>")
           .append("<input type=hidden name=nonce value=\"").append(_nonce != null ? _nonce : "").append("\">")
           .append("<div class=tablewrap><table id=tuningtable><thead>")
           .append("<tr>")
           .append("<th class=parameter>").append(_t("Parameter")).append("</th>")
           .append("<th class=value>").append(_t("Current")).append("</th>")
           .append("<th class=default>").append(_t("Default")).append("</th>")
           .append("<th class=min>").append(_t("Min")).append("</th>")
           .append("<th class=max>").append(_t("Max")).append("</th>")
           .append("<th class=step>").append(_t("Step")).append("</th>")
           .append("<th class=history>").append(_t("History")).append("</th>")
           .append("<th class=auto>").append(_t("Auto")).append("</th>")
           .append("</tr></thead>");

        for (Map.Entry<String, List<ParamSnapshot>> entry : groups.entrySet()) {
            List<ParamSnapshot> params = entry.getValue();
            if (params.isEmpty()) continue;

            String sectionLabel = SECTION_LABELS.get(entry.getKey());
            if (sectionLabel != null)
                buf.append("<thead class=section><tr><td>").append(sectionLabel).append("</td><td colspan=7></td></tr></thead>");

            buf.append("<tbody>");

            for (ParamSnapshot s : params) {
                String prefix = toFormPrefix(s.name);
                String sparkSvg = renderSparkline(s.valueHistory, s.defaultValue);

                buf.append("<tr data-prefix=\"").append(prefix).append("\" data-current=\"").append(s.currentValue).append("\">")
                   .append("<td class=parameter title=\"").append(esc(s.name)).append("\">").append(esc(getDisplayName(s.name)));
                String desc = PARAM_DESCRIPTIONS.get(s.name);
                if (desc != null) {
                    buf.append("<br><span class=paramdesc>").append(esc(_t(desc))).append("</span>");
                }
                buf.append("</td>");

                // col2: value (with parenthesized delta when different from default)
                buf.append("<td class=value>").append(s.currentValue);
                if (s.currentValue != s.defaultValue) {
                    buf.append("<br><span class=delta>").append(s.currentValue - s.defaultValue >= 0 ? "+" : "")
                       .append(s.currentValue - s.defaultValue).append("</span>");
                }
                buf.append("</td>");

                // col3: editable default value
                buf.append("<td class=default><input type=number size=6 name=\"").append(prefix).append("Default\" value=\"").append(s.defaultValue).append("\"></td>");

                buf.append("<td class=min><input type=number size=6 name=\"").append(prefix).append("Min\" value=\"").append(s.min).append("\"></td>")
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

            buf.append("</tbody>");
        }

        buf.append("<tr><td class=optionsave colspan=8><input type=submit name=action class=accept style=float:left value=\"")
           .append(_t("Restore Defaults"))
           .append("\"> <input type=submit name=action class=accept value=\"")
           .append(_t("Save"))
           .append("\"></td></tr></table></form></div>");
        return buf.toString();
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
    private static String renderSparkline(int[] values, int defaultValue) {
        int n = values.length;
        if (n < 2) return "<span class=\"spark init\">Collecting...</span>";

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
     * Ring chart dimensions — small enough for a 3×3 grid, large enough to read.
     */
    private static final int RING_SIZE = 90;
    private static final int RING_STROKE = 8;
    private static final int RING_RADIUS = (RING_SIZE - RING_STROKE) / 2;
    private static final double RING_CIRCUM = 2 * Math.PI * RING_RADIUS;

    /**
     * Render a single SVG donut/ring chart.
     *
     * @param score   0.0–1.0 fill level
     * @param label   subsystem name (center text)
     * @param pct     percentage string for the center (e.g. "93%")
     * @return inline SVG markup
     */
    private static String renderRingChart(double score, String label, String pct) {
        boolean collecting = score < 0;
        // clamp
        if (score < 0) score = 0;
        if (score > 1) score = 1;
        double offset = RING_CIRCUM * (1.0 - score);

        String cls = collecting ? "gray" : score >= 0.8 ? "green" : score >= 0.5 ? "yellow" : "red";

        return "<svg class=ring viewBox=\"0 0 " + RING_SIZE + " " + RING_SIZE + "\">"
            + "<circle class=\"ring-bg\" cx=\"" + (RING_SIZE / 2) + "\" cy=\"" + (RING_SIZE / 2)
            + "\" r=\"" + RING_RADIUS + "\"/>"
            + "<circle class=\"ring-arc " + cls + "\" cx=\"" + (RING_SIZE / 2)
            + "\" cy=\"" + (RING_SIZE / 2) + "\" r=\"" + RING_RADIUS
            + "\" stroke-dasharray=\"" + RING_CIRCUM + " " + RING_CIRCUM
            + "\" stroke-dashoffset=\"" + offset
            + "\" transform=\"rotate(-90 " + (RING_SIZE / 2) + " " + (RING_SIZE / 2)
            + ")\"/>"
            + "<text class=\"ring-pct " + cls + "\" x=\"" + (RING_SIZE / 2)
            + "\" y=\"" + (RING_SIZE / 2 - 2) + "\">"
            + esc(pct) + "</text>"
            + "<text class=ring-label x=\"" + (RING_SIZE / 2)
            + "\" y=\"" + (RING_SIZE / 2 + 12) + "\">"
            + esc(label) + "</text>"
            + "</svg>";
    }

    /**
     * Render the subsystem ring chart dashboard.
     * Returns an HTML div with a grid of SVG ring charts, one per subsystem.
     * NaN-scored subsystems show a gray "collecting..." ring.
     *
     * @since 0.9.70+
     */
    private String renderSubsystemRings(Tuner tuner) {
        List<Tuner.SubsystemScore> scores = tuner.getSubsystemScores();
        if (scores.isEmpty()) return "";

        StringBuilder buf = new StringBuilder(2048);
        buf.append("<div id=tuningstats>");

        for (Tuner.SubsystemScore ss : scores) {
            buf.append("<div class=ring-cell>");
            if (Double.isNaN(ss.score)) {
                buf.append(renderRingChart(-1, ss.label, "\u2014"));
            } else {
                int pct = (int) (ss.score * 100);
                String pctStr = pct + "%";
                buf.append(renderRingChart(ss.score, ss.label, pctStr));
            }
            // CSS tooltip
            buf.append("<div class=ring-tip>");
            for (int i = 0; i < ss.details.length; i++) {
                if (i > 0) buf.append("<br>");
                buf.append(esc(ss.details[i]));
            }
            buf.append("</div>");
            buf.append("</div>");
        }

        buf.append("</div>");
        return buf.toString();
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
        if (udp instanceof UDPTransport) {
            return ((UDPTransport) udp).getTuner();
        }
        return null;
    }
}
