package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;

import java.util.List;
import java.util.Set;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.I2PTunnelServer;
import net.i2p.i2ptunnel.I2PTunnelTask;
import net.i2p.i2ptunnel.TunnelController;
import net.i2p.i2ptunnel.TunnelControllerGroup;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.networkdb.kademlia.KademliaNetworkDatabaseFacade;
import net.i2p.router.web.HelperBase;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.stat.RateConstants;

/**
 * Helper for the /health page - renders router health rings
 * using RingRenderer for a quick visual overview.
 *
 * @since 0.9.70+
 */
public class HealthHelper extends HelperBase {

    public HealthHelper() {}

    /** @since 0.9.70+ */
    public boolean isFloodfill() {
        if (_context == null) return false;
        FloodfillNetworkDatabaseFacade ff = (FloodfillNetworkDatabaseFacade) _context.netDb();
        return ff != null && ff.floodfillEnabled();
    }

    public String getHealthContent() {
        try {
            if (_out != null) {
                renderHealth(_out);
                return "";
            } else {
                StringWriter sw = new StringWriter(4096);
                renderHealth(sw);
                return sw.toString();
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return "";
        }
    }

    /** @since 2.12.0 */
    public String getPerfRings() {
        try {
            StringWriter sw = new StringWriter(2048);
            renderPerfSection(sw);
            return sw.toString();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return "";
        }
    }

    /** @since 2.12.0 */
    public String getTransportRings() {
        try {
            StringWriter sw = new StringWriter(2048);
            renderTransportSection(sw);
            return sw.toString();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return "";
        }
    }

    /** @since 2.12.0 */
    public String getNetworkRings() {
        try {
            StringWriter sw = new StringWriter(2048);
            renderNetworkSection(sw);
            return sw.toString();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return "";
        }
    }

    /** @since 2.12.0 */
    public String getFFRings() {
        try {
            StringWriter sw = new StringWriter(2048);
            renderFFSection(sw);
            return sw.toString();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return "";
        }
    }

    /** Get a rate stat's average value, trying wider windows (1m, 10m, 1h) until data is found. Returns 0 if unavailable */
    private double getStatAvg(String name) {
        RateStat rs = _context.statManager().getRate(name);
        if (rs == null) return 0;
        // Prefer current-interval data (1m, 10m, 1h windows).
        // getLastEventCount() only reflects completed intervals, so
        // use getAverageValue() directly — it checks the live interval.
        long[] windows = {RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR};
        for (long window : windows) {
            Rate r = rs.getRate(window);
            if (r == null) continue;
            double avg = r.getAverageValue();
            if (avg > 0 && !Double.isNaN(avg) && !Double.isInfinite(avg))
                return avg;
        }
        // Fall back to session lifetime average for early uptime
        double lft = rs.getLifetimeAverageValue();
        if (lft > 0 && !Double.isNaN(lft) && !Double.isInfinite(lft))
            return lft;
        return 0;
    }

    /** Get a rate stat's event count (narrowest 1-minute window only, for throughput calc), returning 0 if unavailable */
    private long getStatCount(String name) {
        RateStat rs = _context.statManager().getRate(name);
        if (rs == null) return 0;
        Rate r = rs.getRate(RateConstants.ONE_MINUTE);
        return r != null ? r.getLastEventCount() : 0;
    }

    /** Get last 5 history data points from RRD for a stat, trying wider windows if needed, or null if unavailable */
    private double[] getStatHistory(String name) {
        RateStat rs = _context.statManager().getRate(name);
        if (rs == null) return null;
        long[] windows = {RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR};
        for (long window : windows) {
            Rate r = rs.getRate(window);
            if (r == null) return null;
            double[] vals = r.getLastValues(5);
            for (double v : vals) {
                if (!Double.isNaN(v)) return vals;
            }
        }
        return null;
    }

    /**
     * Legacy: renders all rings in a single div.
     * Kept for backward compat with getHealthContent() / storeWriter path.
     */
    private void renderHealth(Writer out) throws IOException {
        out.write("<div id=healthstats>");
        renderPerfSection(out);
        renderTransportSection(out);
        renderNetworkSection(out);
        if (isFloodfill())
            renderFFSection(out);
        out.write("</div>\n");
    }

    /**
     * Section 1 – Performance &amp; Load (8 rings)
     * Resource: Bandwidth, CPU, Memory | Latency: BW Delay, Job Lag, Msg Lag | Job: Job Queue, Threads
     */
    private void renderPerfSection(Writer out) throws IOException {
        // Bandwidth utilization
        double sendBps = getStatAvg("bw.sendBps");
        if (Double.isNaN(sendBps) || Double.isInfinite(sendBps)) sendBps = 0;
        double recvBps = getStatAvg("bw.receiveBps");
        if (Double.isNaN(recvBps) || Double.isInfinite(recvBps)) recvBps = 0;
        int outLimit = _context.bandwidthLimiter().getOutboundKBytesPerSecond() * 1024;
        int inLimit = _context.bandwidthLimiter().getInboundKBytesPerSecond() * 1024;
        double maxBw = Math.max(inLimit, outLimit);
        double bwUtil = maxBw > 0 ? (sendBps + recvBps) / maxBw : 0;
        String bwPct = bwUtil > 0 ? (int) (bwUtil * 100) + "%" : "0%";
        String bwDetail = maxBw > 0 ? formatBW((long) (sendBps + recvBps)) + " / " + formatBW(maxBw) : "\u2014";
        double bwScore = maxBw > 0 ? Math.max(0, 1.0 - bwUtil) : -1;
        double[] sendHist = getStatHistory("bw.sendBps");
        double[] recvHist = getStatHistory("bw.receiveBps");
        double[] bwHist = null;
        if (sendHist != null && recvHist != null) {
            bwHist = new double[sendHist.length];
            for (int i = 0; i < sendHist.length; i++) {
                double s = sendHist[i], r = recvHist[i];
                bwHist[i] = (Double.isNaN(s) ? 0 : s) + (Double.isNaN(r) ? 0 : r);
            }
        }

        // CPU
        double cpu = getStatAvg("router.cpuLoad");
        if (Double.isNaN(cpu) || Double.isInfinite(cpu)) cpu = 0;
        String cpuStr = cpu > 0 ? (int) cpu + "%" : "\u2014";
        double cpuScore = cpu > 0 ? Math.max(0, 1.0 - cpu / 90.0) : -1;
        double[] cpuHist = getStatHistory("router.cpuLoad");

        // Memory
        double memUsed = getStatAvg("router.memoryUsed");
        long maxMem = Runtime.getRuntime().maxMemory();
        double memPct = maxMem > 0 ? (memUsed / maxMem) * 100.0 : 0;
        String memStr = memPct > 0 ? (int) memPct + "%" : "\u2014";
        double memScore = memPct > 0 ? Math.max(0, 1.0 - memPct / 95.0) : -1;
        double[] memHist = getStatHistory("router.memoryUsed");

        // Job Lag
        double lag = getStatAvg("jobQueue.jobLag");
        String lagStr = lag > 0 ? (lag >= 1000 ? String.format("%.1f", lag / 1000) : String.valueOf((int) lag)) : "\u2014";
        double lagScore = lag > 0 ? Math.max(0, 1.0 - lag / 500.0) : -1;
        double[] lagHist = getStatHistory("jobQueue.jobLag");

        // Message Delay
        double msgDelay = getStatAvg("transport.sendProcessingTime");
        String delayStr = msgDelay > 0 ? (msgDelay >= 1000 ? String.format("%.1f", msgDelay / 1000) : String.valueOf((int) msgDelay)) : "\u2014";
        double delayScore = msgDelay > 0 ? Math.max(0, 1.0 - msgDelay / 1000.0) : -1;
        double[] delayHist = getStatHistory("transport.sendProcessingTime");

        // Active Threads (new)
        double threads = getStatAvg("router.activeThreads");
        String threadStr = threads > 0 ? String.valueOf((int) threads) : "\u2014";
        double threadScore = threads > 0 ? Math.max(0, 1.0 - threads / 500.0) : -1;
        double[] threadHist = getStatHistory("router.activeThreads");

        // Ready Jobs (new)
        double readyJobs = getStatAvg("jobQueue.readyJobs");
        String readyStr = readyJobs > 0 ? String.valueOf((int) readyJobs) : "\u2014";
        double readyScore = readyJobs > 0 ? Math.max(0, 1.0 - readyJobs / 500.0) : -1;
        double[] readyHist = getStatHistory("jobQueue.readyJobs");

        // Active Streams (replaces BW Delay)
        int[] streamCounts = countActiveStreams();
        int totalStreams = streamCounts[0] + streamCounts[1];
        String streamStr = totalStreams > 0 ? String.valueOf(totalStreams) : "\u2014";
        String streamDetail = "In: " + streamCounts[0] + " / Out: " + streamCounts[1];
        double streamScore = totalStreams > 0 ? Math.min(totalStreams / 50.0, 1.0) : -1;
        double[] streamHist = getStatHistory("stream.connectionCreated");

        out.write(RingRenderer.renderRingCell(bwScore, _t("Bandwidth"), bwPct,
                  new String[]{bwDetail}, RingRenderer.MODE_ACTIVITY, bwHist));
        out.write(RingRenderer.renderRingCell(cpuScore, _t("CPU"), cpuStr,
                  new String[]{_t("CPU load average")}, RingRenderer.MODE_HEALTH, cpuHist));
        out.write(RingRenderer.renderRingCell(memScore, _t("Memory"), memStr,
                  new String[]{_t("Memory usage")}, RingRenderer.MODE_HEALTH, memHist));
        out.write(RingRenderer.renderRingCell(streamScore, _t("Active Streams"), streamStr,
                  new String[]{streamDetail}, RingRenderer.MODE_ACTIVITY, streamHist));
        out.write(RingRenderer.renderRingCell(lagScore, _t("Job Lag"), withUnit(lagStr, _t("ms")),
                  new String[]{_t("Job queue delay")}, RingRenderer.MODE_LATENCY, lagHist));
        out.write(RingRenderer.renderRingCell(delayScore, _t("Msg Lag"), withUnit(delayStr, _t("ms")),
                  new String[]{_t("Message send processing time")}, RingRenderer.MODE_LATENCY, delayHist));
        out.write(RingRenderer.renderRingCell(readyScore, _t("Job Queue"), readyStr,
                  new String[]{_t("Ready jobs waiting in queue")}, RingRenderer.MODE_LATENCY, readyHist));
        out.write(RingRenderer.renderRingCell(threadScore, _t("Threads"), threadStr,
                  new String[]{_t("Active JVM threads")}, RingRenderer.MODE_HEALTH, threadHist));
    }

    /**
     * Section 2 – Transport &amp; Connectivity (8 rings)
     * NTCP: NTCP Estab | SSU: SSU Estab, SSU RTO | Throughput: Conns/s, Msgs/s | Quality: RTT, Timeout%, Tunnel Build
     */
    private void renderTransportSection(Writer out) throws IOException {
        // NTCP Establish Time
        double ntcpEstab = getStatAvg("ntcp.outboundEstablishTime");
        String ntcpStr = ntcpEstab > 0 ? (ntcpEstab >= 1000 ? String.format("%.1f", ntcpEstab / 1000) : String.valueOf((int) ntcpEstab)) : "\u2014";
        double ntcpScore = ntcpEstab > 0 ? Math.max(0, 1.0 - ntcpEstab / 2000.0) : -1;
        double[] ntcpHist = getStatHistory("ntcp.outboundEstablishTime");

        // RTO
        double rto = getStatAvg("udp.avgRTO");
        String rtoStr = rto > 0 ? String.valueOf((int) rto) : "\u2014";
        double rtoScore = rto > 0 ? Math.max(0, 1.0 - rto / 3000.0) : -1;
        double[] rtoHist = getStatHistory("udp.avgRTO");

        // Connections/s
        double connTotal = getStatCount("ntcp.connectSuccessful") + getStatCount("ntcp.inboundEstablished");
        double connPerSec = connTotal > 0 ? connTotal / 60.0 : 0;
        String connStr = connPerSec > 0 ? String.format("%.0f", connPerSec) : "\u2014";
        double connScore = connPerSec > 0 ? Math.min(connPerSec / 2.0, 1.0) : -1;

        // Messages/s
        double msgCount = getStatCount("transport.messagesDelivered");
        double msgPerSec = msgCount > 0 ? msgCount / 60.0 : 0;
        String msgStr = msgPerSec > 0 ? String.format("%.0f", msgPerSec) : "\u2014";
        double msgScore = msgPerSec > 0 ? Math.min(msgPerSec / 30.0, 1.0) : -1;

        // RTT
        double rtt = getStatAvg("client.sendAckTime");
        String rttStr = rtt > 0 ? (rtt >= 1000 ? String.format("%.1f", rtt / 1000) : String.valueOf((int) rtt)) : "\u2014";
        double rttScore = rtt > 0 ? Math.max(0, 1.0 - rtt / 5000.0) : -1;
        double[] rttHist = getStatHistory("client.sendAckTime");

        // Build Time
        double buildTime = getStatAvg("tunnel.buildClientSuccess");
        String buildTimeStr = buildTime > 0 ? (buildTime >= 1000 ? String.format("%.1f", buildTime / 1000) : String.valueOf((int) buildTime)) : "\u2014";
        double buildTimeScore = buildTime > 0 ? Math.max(0, 1.0 - buildTime / 10000.0) : -1;
        double[] buildTimeHist = getStatHistory("tunnel.buildClientSuccess");

        // SSU Outbound Establish Time (new)
        double ssuEstab = getStatAvg("udp.outboundEstablishTime");
        String ssuStr = ssuEstab > 0 ? (ssuEstab >= 1000 ? String.format("%.1f", ssuEstab / 1000) : String.valueOf((int) ssuEstab)) : "\u2014";
        double ssuScore = ssuEstab > 0 ? Math.max(0, 1.0 - ssuEstab / 5000.0) : -1;
        double[] ssuHist = getStatHistory("udp.outboundEstablishTime");

        // Build Timeout Rate (new)
        double buildTimeout = getStatAvg("tunnel.buildTimeoutRate");
        String timeoutStr = buildTimeout > 0 ? (int) buildTimeout + "%" : "\u2014";
        double timeoutScore = buildTimeout > 0 ? Math.max(0, 1.0 - buildTimeout / 30.0) : -1;
        double[] timeoutHist = getStatHistory("tunnel.buildTimeoutRate");

        out.write(RingRenderer.renderRingCell(ntcpScore, _t("NTCP Estab"), withUnit(ntcpStr, _t("ms")),
                  new String[]{_t("NTCP outbound establish time")}, RingRenderer.MODE_LATENCY, ntcpHist));
        out.write(RingRenderer.renderRingCell(ssuScore, _t("SSU Estab"), withUnit(ssuStr, _t("ms")),
                  new String[]{_t("SSU outbound establish time")}, RingRenderer.MODE_LATENCY, ssuHist));
        out.write(RingRenderer.renderRingCell(rtoScore, _t("SSU RTO"), withUnit(rtoStr, _t("ms")),
                  new String[]{_t("SSU retransmission timeout")}, RingRenderer.MODE_HEALTH, rtoHist));
        out.write(RingRenderer.renderRingCell(connScore, _t("Conns/s"), connStr,
                  new String[]{_t("NTCP connections established per second")}, RingRenderer.MODE_ACTIVITY, null));
        out.write(RingRenderer.renderRingCell(msgScore, _t("Msgs/s"), msgStr,
                  new String[]{_t("Messages delivered per second")}, RingRenderer.MODE_ACTIVITY, null));
        out.write(RingRenderer.renderRingCell(rttScore, _t("RTT"), withUnit(rttStr, _t("ms")),
                  new String[]{_t("End-to-end message round trip time")}, RingRenderer.MODE_LATENCY, rttHist));
        out.write(RingRenderer.renderRingCell(timeoutScore, _t("Timeout"), timeoutStr,
                  new String[]{_t("Tunnel build timeout rate")}, RingRenderer.MODE_HEALTH, timeoutHist));
        out.write(RingRenderer.renderRingCell(buildTimeScore, _t("Tunnel Build"), withUnit(buildTimeStr, _t("ms")),
                  new String[]{_t("Client tunnel build latency")}, RingRenderer.MODE_LATENCY, buildTimeHist));
    }

    /**
     * Section 3 – Network &amp; Participation (8 rings)
     * Peers: Active Peers, Known Peers | Role: Clients, Transit | Health: Build Success, NetDB, Uptime | Security: Banned
     */
    private void renderNetworkSection(Writer out) throws IOException {
        // Known Peers (in-memory / on-disk)
        int knownPeers = _context.netDb().getKnownRouters();
        int storedRouters = 0;
        KademliaNetworkDatabaseFacade kf = (KademliaNetworkDatabaseFacade) _context.netDb();
        String knownStr = String.valueOf(knownPeers);
        double knownScore = knownPeers > 0 ? Math.min(knownPeers / 5000.0, 1.0) : 0;

        // Active Peers
        double peers = getStatAvg("router.activePeers");
        String peerStr = peers > 0 ? String.valueOf((int) peers) : "\u2014";
        double peerScore = peers > 0 ? Math.min(peers / 1000.0, 1.0) : -1;
        double[] peerHist = getStatHistory("router.activePeers");

        // Participating Tunnels
        int tunnelCount = _context.tunnelDispatcher().listParticipatingTunnels().size();
        String tunnelStr = String.valueOf(tunnelCount);
        double tunnelScore = tunnelCount > 0 ? Math.min(tunnelCount / 200.0, 1.0) : 0;

        // Active Clients
        int clients = _context.clientManager().listClients().size();
        String clientStr = clients > 0 ? String.valueOf(clients) : "0";
        double clientScore = clients > 0 ? Math.min(clients / 20.0, 1.0) : 0;

        // Uptime
        long uptimeMs = _context.router().getUptime();
        String uptimeStr = uptimeMs > 0 ? formatCompactDuration(uptimeMs) : "\u2014";
        double uptimeScore = uptimeMs > 0 ? Math.min(uptimeMs / (24L * 3600 * 1000), 1.0) : -1;

        // NetDB lookup time
        double netdb = getStatAvg("netDb.successTime");
        String netdbStr = netdb > 0 ? (netdb >= 1000 ? String.format("%.1f", netdb / 1000) : String.valueOf((int) netdb)) : "\u2014";
        double netdbScore = netdb > 0 ? Math.max(0, 1.0 - netdb / 3000.0) : -1;
        double[] netdbHist = getStatHistory("netDb.successTime");

        // Build Success
        double buildRate = getStatAvg("tunnel.buildSuccessRate");
        String buildStr = buildRate > 0 ? (int) buildRate + "%" : "\u2014";
        double buildScore = buildRate > 0 ? Math.max(0, Math.min(buildRate / 70.0, 1.0)) : -1;
        double[] buildHist = getStatHistory("tunnel.buildSuccessRate");

        // Banned Peers (new)
        double banned = getStatAvg("router.bannedPeers");
        String bannedStr = banned > 0 ? String.valueOf((int) banned) : "0";
        double bannedScore = banned > 0 ? Math.max(0, 1.0 - banned / 100.0) : 1.0;

        out.write(RingRenderer.renderRingCell(peerScore, _t("Active Peers"), peerStr,
                  new String[]{_t("Active peers (last 60s)")}, RingRenderer.MODE_ACTIVITY, peerHist));
        out.write(RingRenderer.renderRingCell(knownScore, _t("Known Peers"), knownStr,
                  new String[]{_t("Known routers in network database")}, RingRenderer.MODE_ACTIVITY, null));
        out.write(RingRenderer.renderRingCell(clientScore, _t("Clients"), clientStr,
                  new String[]{_t("Active I2CP clients")}, RingRenderer.MODE_ACTIVITY, null));
        out.write(RingRenderer.renderRingCell(tunnelScore, _t("Transit"), tunnelStr,
                  new String[]{_t("Transit tunnels hosted")}, RingRenderer.MODE_ACTIVITY, null));
        out.write(RingRenderer.renderRingCell(buildScore, _t("Build Success"), buildStr,
                  new String[]{_t("Tunnel build success rate")}, RingRenderer.MODE_HEALTH, buildHist));
        out.write(RingRenderer.renderRingCell(netdbScore, _t("NetDB"), withUnit(netdbStr, _t("ms")),
                  new String[]{_t("NetDB lookup time")}, RingRenderer.MODE_LATENCY, netdbHist));
        out.write(RingRenderer.renderRingCell(uptimeScore, _t("Uptime"), uptimeStr,
                  new String[]{_t("Router uptime")}, RingRenderer.MODE_NEUTRAL, null));
        out.write(RingRenderer.renderRingCell(bannedScore, _t("Banned"), bannedStr,
                  new String[]{_t("Total banned peers")}, RingRenderer.MODE_NEUTRAL, null));
    }

    /**
     * Section 4 – Floodfill / NetDB (8 rings, shown when floodfill enabled)
     * Capacity: LeaseSets | Perf: Cache Hit, Flood Verify, NetDB ACK | Throughput: Lookups/s, Requests/s, Stores/s | Errors: LS Timeout
     */
    private void renderFFSection(Writer out) throws IOException {
        // NetDB ACK Time (new)
        double ackTime = getStatAvg("netDb.ackTime");
        String ackStr = ackTime > 0 ? (ackTime >= 1000 ? String.format("%.1f", ackTime / 1000) : String.valueOf((int) ackTime)) : "\u2014";
        double ackScore = ackTime > 0 ? Math.max(0, 1.0 - ackTime / 2000.0) : -1;
        double[] ackHist = getStatHistory("netDb.ackTime");

        // LS Timeout Rate (new)
        long lsTimeout = getStatCount("client.requestLeaseSetTimeout");
        String lsStr = lsTimeout > 0 ? String.valueOf(lsTimeout) : "0";
        double lsScore = lsTimeout > 0 ? Math.max(0, 1.0 - lsTimeout / 10.0) : 1.0;

        FloodfillNetworkDatabaseFacade ff = (FloodfillNetworkDatabaseFacade) _context.netDb();

        // Stored LeaseSets (seperate from router infos)
        int lsStored = 0;
        if (ff != null)
            lsStored = ff.getKnownLeaseSets();
        String leaseSetStr = lsStored > 0 ? String.valueOf(lsStored) : "\u2014";
        double leaseSetScore = lsStored > 0 ? Math.min(lsStored / 300.0, 1.0) : -1;

        // Flood Verify
        double ffVerify = getStatAvg("netDb.floodfillVerifyOK");
        if (Double.isNaN(ffVerify) || Double.isInfinite(ffVerify)) ffVerify = 0;
        String ffStr = ffVerify > 0 ? (ffVerify >= 1000 ? String.format("%.1f", ffVerify / 1000) : String.valueOf((int) ffVerify)) : "\u2014";
        double ffScore = ffVerify > 0 ? Math.max(0, 1.0 - ffVerify / 5000.0) : -1;
        double[] ffHist = getStatHistory("netDb.floodfillVerifyOK");

        // Hit Rate
        double lookupsMatched = getStatCount("netDb.lookupsMatched");
        double lookupsHandled = getStatCount("netDb.lookupsHandled");
        double hitRate = lookupsHandled > 0 ? lookupsMatched / lookupsHandled : 0;
        String hitStr = lookupsHandled > 0 ? (int) (hitRate * 100) + "%" : "\u2014";
        double hitScore = lookupsHandled > 0 ? hitRate : -1;
        double[] hitHist = getStatHistory("netDb.lookupsMatched");

        // Lookups/s
        double lookups = getStatCount("netDb.lookupsHandled") / 60.0;
        String lookupStr = lookups > 0 ? String.format("%.0f", lookups) : "\u2014";
        double lookupScore = lookups > 0 ? Math.min(lookups / 10.0, 1.0) : -1;
        double[] lookupHist = getStatHistory("netDb.lookupsHandled");

        // Op Rate
        double stores = getStatCount("netDb.storeHandled") / 60.0;
        double opRate = stores + lookups;
        String opStr = opRate > 0 ? String.format("%.0f", opRate) : "\u2014";
        double opScore = opRate > 0 ? Math.min(opRate / 15.0, 1.0) : -1;

        // Stores/s
        String storeStr = stores > 0 ? String.format("%.0f", stores) : "\u2014";
        double storeScore = stores > 0 ? Math.min(stores / 5.0, 1.0) : -1;
        double[] storeHist = getStatHistory("netDb.storeHandled");

        out.write(RingRenderer.renderRingCell(leaseSetScore, _t("LeaseSets"), leaseSetStr,
                  new String[]{_t("Stored LeaseSets in floodfill")}, RingRenderer.MODE_ACTIVITY, null));
        out.write(RingRenderer.renderRingCell(hitScore, _t("Cache Hit"), hitStr,
                  new String[]{_t("NetDB lookup success rate")}, RingRenderer.MODE_HEALTH, hitHist));
        out.write(RingRenderer.renderRingCell(ffScore, _t("Flood Verify"), withUnit(ffStr, _t("ms")),
                  new String[]{_t("Floodfill verify time")}, RingRenderer.MODE_LATENCY, ffHist));
        out.write(RingRenderer.renderRingCell(ackScore, _t("NetDB ACK"), withUnit(ackStr, _t("ms")),
                  new String[]{_t("NetDB peer acknowledge time")}, RingRenderer.MODE_LATENCY, ackHist));
        out.write(RingRenderer.renderRingCell(lookupScore, _t("Lookups/s"), lookupStr,
                  new String[]{_t("NetDB lookups handled per second")}, RingRenderer.MODE_ACTIVITY, lookupHist));
        out.write(RingRenderer.renderRingCell(opScore, _t("Requests/s"), opStr,
                  new String[]{_t("Combined store + lookup operations per second")}, RingRenderer.MODE_ACTIVITY, null));
        out.write(RingRenderer.renderRingCell(storeScore, _t("Stores/s"), storeStr,
                  new String[]{_t("NetDB store messages handled per second")}, RingRenderer.MODE_ACTIVITY, storeHist));
        out.write(RingRenderer.renderRingCell(lsScore, _t("LS Timeout"), withUnit(lsStr, _t("ms")),
                  new String[]{_t("LeaseSet request timeouts (last minute)")}, RingRenderer.MODE_HEALTH, null));
    }

    /** Count active I2P streaming connections across all tunnel controllers. Returns [inCount, outCount] */
    private int[] countActiveStreams() {
        TunnelControllerGroup tcg = TunnelControllerGroup.getInstance();
        if (tcg == null) return new int[2];
        List<TunnelController> controllers = tcg.getControllers();
        if (controllers == null) return new int[2];
        int inCount = 0, outCount = 0;
        for (TunnelController controller : controllers) {
            I2PTunnel tunnel = controller.getTunnel();
            if (tunnel == null) continue;
            List<I2PTunnelTask> tasks = tunnel.getTasks();
            if (tasks == null) continue;
            for (I2PTunnelTask task : tasks) {
                if (task == null || !task.isOpen()) continue;
                I2PSocketManager mgr = task.getSocketManager();
                if (mgr == null) continue;
                Set<I2PSocket> sockets = mgr.listSockets();
                if (sockets == null || sockets.isEmpty()) continue;
                boolean inbound = (task instanceof I2PTunnelServer);
                for (I2PSocket sock : sockets) {
                    if (sock == null || sock.isClosed()) continue;
                    if (inbound) inCount++;
                    else outCount++;
                }
            }
        }
        return new int[]{inCount, outCount};
    }

    /** Append unit only when value is present (not em-dash) */
    private static String withUnit(String val, String unit) {
        if ("\u2014".equals(val))
            return val;
        return val + unit;
    }

    /** Compact duration: 230s, 24m, 3h, 2d, 1mo (plain text, no HTML) */
    private static String formatCompactDuration(long ms) {
        long sec = ms / 1000;
        if (sec < 60) return sec + "s";
        long min = sec / 60;
        if (min < 60) return min + "m";
        long hr = min / 60;
        if (hr < 24) return hr + "h " + (min % 60) + "m";
        long day = hr / 24;
        if (day < 30) return day + "d " + (hr % 24) + "h";
        return (day / 30) + "mo " + (day % 30) + "d";
    }

    /** Format bytes as B/s, KB/s or MB/s */
    private static String formatBW(double bytes) {
        if (bytes >= 1000000)
            return String.format("%.0f MB/s", bytes / 1000000);
        else if (bytes >= 10000)
            return String.format("%.0f KB/s", bytes / 1000);
        else if (bytes >= 1000)
            return String.format("%.1f KB/s", bytes / 1000);
        else
            return (int) bytes + " B/s";
    }
}
