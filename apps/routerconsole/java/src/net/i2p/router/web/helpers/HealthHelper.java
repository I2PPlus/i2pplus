package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;

import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
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

    /** Get a rate stat's average value over 1 minute, returning 0 if unavailable */
    private double getStatAvg(String name) {
        RateStat rs = _context.statManager().getRate(name);
        if (rs == null) return 0;
        Rate r = rs.getRate(RateConstants.ONE_MINUTE);
        return r != null ? r.getAverageValue() : 0;
    }

    /** Get a rate stat's last-event-count (events/min), returning 0 if unavailable */
    private long getStatCount(String name) {
        RateStat rs = _context.statManager().getRate(name);
        if (rs == null) return 0;
        Rate r = rs.getRate(RateConstants.ONE_MINUTE);
        return r != null ? r.getLastEventCount() : 0;
    }

    /** Get last 5 history data points from RRD for a stat, or null if unavailable */
    private double[] getStatHistory(String name) {
        RateStat rs = _context.statManager().getRate(name);
        if (rs == null) return null;
        Rate r = rs.getRate(RateConstants.ONE_MINUTE);
        if (r == null) return null;
        double[] vals = r.getLastValues(5);
        // check if all NaN
        for (double v : vals) {
            if (!Double.isNaN(v)) return vals;
        }
        return null;
    }

    private void renderHealth(Writer out) throws IOException {
        boolean isFloodfill = false;
        FloodfillNetworkDatabaseFacade ff = (FloodfillNetworkDatabaseFacade) _context.netDb();
        if (ff != null)
            isFloodfill = ff.floodfillEnabled();

        out.write("<div id=healthstats>");

        // Row 1 - Core Health

        // Build Success
        double buildRate = getStatAvg("tunnel.buildSuccessRate");
        String buildStr = buildRate > 0 ? (int) buildRate + "%" : "\u2014";
        double buildScore = buildRate > 0 ? Math.max(0, Math.min(buildRate / 70.0, 1.0)) : -1;

        // CPU
        double cpu = getStatAvg("router.cpuLoad");
        String cpuStr = cpu > 0 ? (int) cpu + "%" : "\u2014";
        double cpuScore = cpu > 0 ? Math.max(0, 1.0 - cpu / 90.0) : -1;

        // Active Peers
        double peers = getStatAvg("router.activePeers");
        String peerStr = peers > 0 ? String.valueOf((int) peers) : "\u2014";
        double peerScore = peers > 0 ? Math.min(peers / 1000.0, 1.0) : -1;

        // Message Delay
        double msgDelay = getStatAvg("transport.sendProcessingTime");
        String delayStr = msgDelay > 0 ? (msgDelay >= 1000 ? String.format("%.1fs", msgDelay / 1000) : (int) msgDelay + "ms") : "\u2014";
        double delayScore = msgDelay > 0 ? Math.max(0, 1.0 - msgDelay / 1000.0) : -1;

        // Job Lag
        double lag = getStatAvg("jobQueue.jobLag");
        String lagStr = lag > 0 ? (lag >= 1000 ? String.format("%.1fs", lag / 1000) : (int) lag + "ms") : "\u2014";
        double lagScore = lag > 0 ? Math.max(0, 1.0 - lag / 500.0) : -1;

        double[] buildHist = getStatHistory("tunnel.buildSuccessRate");
        double[] cpuHist = getStatHistory("router.cpuLoad");
        double[] peerHist = getStatHistory("router.activePeers");
        double[] delayHist = getStatHistory("transport.sendProcessingTime");
        double[] lagHist = getStatHistory("jobQueue.jobLag");

        out.write(RingRenderer.renderRingCell(buildScore, _t("Build Success"), buildStr,
                  new String[]{_t("Tunnel build success rate (1 min avg)")}, RingRenderer.MODE_HEALTH, buildHist));
        out.write(RingRenderer.renderRingCell(cpuScore, _t("CPU"), cpuStr,
                  new String[]{_t("JVM CPU load average")}, RingRenderer.MODE_HEALTH, cpuHist));
        out.write(RingRenderer.renderRingCell(peerScore, _t("Peers"), peerStr,
                  new String[]{_t("Active peers (last 60s)")}, RingRenderer.MODE_ACTIVITY, peerHist));
        out.write(RingRenderer.renderRingCell(delayScore, _t("Msg Delay"), delayStr,
                  new String[]{_t("Message send processing time (1 min avg)")}, RingRenderer.MODE_LATENCY, delayHist));
        out.write(RingRenderer.renderRingCell(lagScore, _t("Job Lag"), lagStr,
                  new String[]{_t("Job queue delay (1 min avg)")}, RingRenderer.MODE_LATENCY, lagHist));

        // Row 2 - Resources

        // Memory (stat stores raw bytes, convert to % of max)
        double memUsed = getStatAvg("router.memoryUsed");
        long maxMem = Runtime.getRuntime().maxMemory();
        double memPct = maxMem > 0 ? (memUsed / maxMem) * 100.0 : 0;
        String memStr = memPct > 0 ? (int) memPct + "%" : "\u2014";
        double memScore = memPct > 0 ? Math.max(0, 1.0 - memPct / 95.0) : -1;

        // NTCP Establish Time
        double ntcpEstab = getStatAvg("ntcp.outboundEstablishTime");
        String ntcpStr = ntcpEstab > 0 ? (ntcpEstab >= 1000 ? String.format("%.1fs", ntcpEstab / 1000) : (int) ntcpEstab + "ms") : "\u2014";
        double ntcpScore = ntcpEstab > 0 ? Math.max(0, 1.0 - ntcpEstab / 2000.0) : -1;

        // Bandwidth utilization
        double sendBps = getStatAvg("bw.sendBps");
        double recvBps = getStatAvg("bw.receiveBps");
        int outLimit = _context.bandwidthLimiter().getOutboundKBytesPerSecond() * 1024;
        int inLimit = _context.bandwidthLimiter().getInboundKBytesPerSecond() * 1024;
        double maxBw = Math.max(inLimit, outLimit);
        double bwUtil = maxBw > 0 ? (sendBps + recvBps) / maxBw : 0;
        String bwPct = bwUtil > 0 ? (int) (bwUtil * 100) + "%" : "0%";
        String bwDetail = maxBw > 0 ? formatBW((long) (sendBps + recvBps)) + " / " + formatBW(maxBw) : "\u2014";
        double bwScore = maxBw > 0 ? Math.max(0, 1.0 - bwUtil) : -1;

        double[] memHist = getStatHistory("router.memoryUsed");
        double[] ntcpHist = getStatHistory("ntcp.outboundEstablishTime");
        double[] sendHist = getStatHistory("bw.sendBps");
        double[] recvHist = getStatHistory("bw.receiveBps");
        // Combine send + recv for bandwidth history by summing pairwise
        double[] bwHist = null;
        if (sendHist != null && recvHist != null) {
            bwHist = new double[sendHist.length];
            for (int i = 0; i < sendHist.length; i++) {
                double s = sendHist[i], r = recvHist[i];
                bwHist[i] = (Double.isNaN(s) ? 0 : s) + (Double.isNaN(r) ? 0 : r);
            }
        }

        out.write(RingRenderer.renderRingCell(memScore, _t("Memory"), memStr,
                  new String[]{_t("JVM memory usage")}, RingRenderer.MODE_HEALTH, memHist));
        out.write(RingRenderer.renderRingCell(ntcpScore, _t("NTCP"), ntcpStr,
                  new String[]{_t("NTCP outbound establish time (1 min avg)")}, RingRenderer.MODE_LATENCY, ntcpHist));
        out.write(RingRenderer.renderRingCell(bwScore, _t("Bandwidth"), bwPct,
                  new String[]{_t("Throughput: ") + bwDetail}, RingRenderer.MODE_ACTIVITY, bwHist));

        // General extra rings (everybody gets these)

        // Uptime
        long uptimeMs = _context.router().getUptime();
        String uptimeStr = uptimeMs > 0 ? formatCompactDuration(uptimeMs) : "\u2014";
        double uptimeScore = uptimeMs > 0 ? Math.min(uptimeMs / (30L * 24 * 3600 * 1000), 1.0) : -1;

        // Active Clients
        int clients = _context.clientManager().listClients().size();
        String clientStr = clients > 0 ? String.valueOf(clients) : "0";
        double clientScore = clients > 0 ? Math.min(clients / 20.0, 1.0) : 0;

        // Known Peers
        int knownPeers = _context.netDb().getKnownRouters();
        String knownStr = String.valueOf(knownPeers);
        double knownScore = knownPeers > 0 ? Math.min(knownPeers / 5000.0, 1.0) : 0;

        // Participating Tunnels
        int tunnelCount = _context.tunnelDispatcher().listParticipatingTunnels().size();
        String tunnelStr = String.valueOf(tunnelCount);
        double tunnelScore = tunnelCount > 0 ? Math.min(tunnelCount / 200.0, 1.0) : 0;

        out.write(RingRenderer.renderRingCell(uptimeScore, _t("Uptime"), uptimeStr,
                  new String[]{_t("Router uptime")}, RingRenderer.MODE_HEALTH, null));
        out.write(RingRenderer.renderRingCell(clientScore, _t("Clients"), clientStr,
                  new String[]{_t("Active I2CP clients")}, RingRenderer.MODE_ACTIVITY, null));
        out.write(RingRenderer.renderRingCell(knownScore, _t("Known"), knownStr,
                  new String[]{_t("Known routers in network database")}, RingRenderer.MODE_ACTIVITY, null));
        out.write(RingRenderer.renderRingCell(tunnelScore, _t("Transit"), tunnelStr,
                  new String[]{_t("Transit tunnels hosted")}, RingRenderer.MODE_ACTIVITY, null));

        // Row 3 - Floodfill (only when enabled)
        if (isFloodfill) {
            // Stores/s
            double stores = getStatCount("netDb.storeHandled") / 60.0;
            String storeStr = stores > 0 ? String.format("%.1f/s", stores) : "\u2014";
            double storeScore = stores > 0 ? Math.min(stores / 5.0, 1.0) : -1;

            // Lookups/s
            double lookups = getStatCount("netDb.lookupsHandled") / 60.0;
            String lookupStr = lookups > 0 ? String.format("%.1f/s", lookups) : "\u2014";
            double lookupScore = lookups > 0 ? Math.min(lookups / 10.0, 1.0) : -1;

            // Lookup hit rate
            double lookupsMatched = getStatCount("netDb.lookupsMatched");
            double lookupsHandled = getStatCount("netDb.lookupsHandled");
            double hitRate = lookupsHandled > 0 ? lookupsMatched / lookupsHandled : 0;
            String hitStr = lookupsHandled > 0 ? (int) (hitRate * 100) + "%" : "\u2014";
            double hitScore = lookupsHandled > 0 ? hitRate : -1;

            // Flood verify time
            double ffVerify = getStatAvg("netDb.floodfillVerifyOK");
            String ffStr = ffVerify > 0 ? (ffVerify >= 1000 ? String.format("%.1fs", ffVerify / 1000) : (int) ffVerify + "ms") : "\u2014";
            double ffScore = ffVerify > 0 ? Math.max(0, 1.0 - ffVerify / 5000.0) : -1;

            // NetDB stored router infos (FF database size)
            int storedRI = 0;
            if (_context.netDb() instanceof FloodfillNetworkDatabaseFacade)
                storedRI = ((FloodfillNetworkDatabaseFacade) _context.netDb()).getStoredRouterInfoCount();
            String storedStr = storedRI > 0 ? String.valueOf(storedRI) : "\u2014";
            double storedScore = storedRI > 0 ? Math.min(storedRI / 100000.0, 1.0) : -1;

            // FF operations rate (stores + lookups per second combined)
            double opRate = stores + lookups;
            String opStr = opRate > 0 ? String.format("%.1f/s", opRate) : "\u2014";
            double opScore = opRate > 0 ? Math.min(opRate / 15.0, 1.0) : -1;

            double[] storeHist = getStatHistory("netDb.storeHandled");
            double[] lookupHist = getStatHistory("netDb.lookupsHandled");
            double[] hitHist = getStatHistory("netDb.lookupsMatched");
            double[] ffHist = getStatHistory("netDb.floodfillVerifyOK");

            out.write(RingRenderer.renderRingCell(storeScore, _t("Stores/s"), storeStr,
                      new String[]{_t("NetDB store messages handled per second")}, RingRenderer.MODE_ACTIVITY, storeHist));
            out.write(RingRenderer.renderRingCell(lookupScore, _t("Lookups/s"), lookupStr,
                      new String[]{_t("NetDB lookups handled per second")}, RingRenderer.MODE_ACTIVITY, lookupHist));
            out.write(RingRenderer.renderRingCell(hitScore, _t("Hit Rate"), hitStr,
                      new String[]{_t("NetDB lookup success rate")}, RingRenderer.MODE_HEALTH, hitHist));
            out.write(RingRenderer.renderRingCell(ffScore, _t("Flood Verify"), ffStr,
                      new String[]{_t("Floodfill verify time (1 min avg)")}, RingRenderer.MODE_LATENCY, ffHist));
            out.write(RingRenderer.renderRingCell(storedScore, _t("DB Size"), storedStr,
                      new String[]{_t("Stored router infos in floodfill")}, RingRenderer.MODE_HEALTH, null));
            out.write(RingRenderer.renderRingCell(opScore, _t("Op Rate"), opStr,
                      new String[]{_t("Combined store + lookup operations per second")}, RingRenderer.MODE_ACTIVITY, null));
        }

        out.write("</div>\n");
    }

    /** Compact duration: 230s, 24m, 3h, 2d, 1mo (plain text, no HTML) */
    private static String formatCompactDuration(long ms) {
        long sec = ms / 1000;
        if (sec < 60) return sec + "s";
        long min = sec / 60;
        if (min < 60) return min + "m";
        long hr = min / 60;
        if (hr < 24) return hr + "h";
        long day = hr / 24;
        if (day < 30) return day + "d";
        return (day / 30) + "mo";
    }

    /** Format bytes as B/s, KB/s or MB/s */
    private static String formatBW(double bytes) {
        if (bytes >= 1000000)
            return String.format("%.1f MB/s", bytes / 1000000);
        else if (bytes >= 1000)
            return String.format("%.1f KB/s", bytes / 1000);
        else
            return (int) bytes + " B/s";
    }
}
