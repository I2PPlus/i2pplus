package net.i2p.router.web.helpers;

import java.util.ArrayList;
import java.util.List;

import net.i2p.router.web.HelperBase;

/**
 * Overview tiles and mini-graphs for the debug page entry point, one tile
 * per debug tab summarizing what that tab shows, linking to it.
 *
 * @since 0.9.71
 */
public class DebugOverviewHelper extends HelperBase {

    /**
     * Helper bean that gathers the debug overview's tiles and mini-graphs.
     */
    public DebugOverviewHelper() {}

    /** Priority-ordered single-stat graph wishes, after bw.combined */
    static final String[] GRAPH_STATS = {"tunnel.buildSuccessRate", "tunnel.participatingTunnels",
                                         "router.activePeers", "jobQueue.jobLag",
                                         "router.cpuLoad", "router.memoryUsed",
                                         "tunnel.buildInboundSuccessRate", "tunnel.buildOutboundSuccessRate",
                                         "tunnel.testSuccessTime"};

    /** Number of mini-graphs shown, backfilled from other enabled stats */
    static final int GRAPH_COUNT = 4;

    /**
     * Human-readable labels for the priority stats, for the graph caption;
     * backfilled stats fall back to their raw name. Values are translated
     * at render time.
     */
    static final java.util.Map<String, String> GRAPH_LABELS;
    static {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        m.put("bw.combined", "Bandwidth");
        m.put("tunnel.buildSuccessRate", "Tunnel build success");
        m.put("tunnel.participatingTunnels", "Participating tunnels");
        m.put("router.activePeers", "Active peers");
        m.put("jobQueue.jobLag", "Job queue lag");
        m.put("router.cpuLoad", "CPU load");
        m.put("router.memoryUsed", "Memory used");
        m.put("tunnel.buildInboundSuccessRate", "Inbound build success");
        m.put("tunnel.buildOutboundSuccessRate", "Outbound build success");
        m.put("tunnel.testSuccessTime", "Tunnel test time");
        GRAPH_LABELS = java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Build the ring cells in tab order: one per data-bearing debug tab
     * (the DHT tab gets two, fill and fullest) plus one for active streams
     * before it. Null stat arrays mean the subsystem reported nothing and
     * the cell degrades to a plain link tile. The SAM and translation tabs
     * get no tile - translation is a pre-generated static report with no
     * live data.
     *
     * @param services count of listening services
     * @param apps {tracked, running, registered} app counts, or null
     * @param updates {installed, downloaded, available} counts, or null
     * @param skmRouter ratchet stats, see SessionKeyManager.getDebugStats, or null
     * @param skmClients summed ratchet stats across client managers, or null
     * @param streams {inbound, outbound} open stream sockets, or null
     * @param dht {keys, buckets, capacity, fullest} bucket stats, or null
     * @return one cell html string per tile, never null
     */
    static List<String> buildTiles(int services, int[] apps, int[] updates, long[] skmRouter,
                                   long[] skmClients, int[] streams, long[] dht) {
        List<String> rv = new ArrayList<>(8);

        rv.add(tile("/debug?d=0", RingRenderer.renderRingCell(Math.min(services / 20.0, 1.0), "Services",
                String.valueOf(services), new String[]{"Listening services", "Full at 20"},
                RingRenderer.MODE_NEUTRAL, null)));

        if (apps != null && apps[2] > 0) {
            double score = (double) apps[1] / apps[2];
            rv.add(tile("/debug?d=1", RingRenderer.renderRingCell(score, "Apps", apps[1] + "/" + apps[2],
                    new String[]{"Running of tracked " + apps[0] + ", registered " + apps[2]},
                    RingRenderer.MODE_HEALTH, null)));
        } else {
            rv.add(tile("/debug?d=1", RingRenderer.renderRingCell(-1, "Apps", "—",
                    new String[]{"No app data"}, RingRenderer.MODE_NEUTRAL, null)));
        }

        if (updates != null) {
            double score = updates[2] > 0 ? 1 : 0;
            rv.add(tile("/debug?d=2", RingRenderer.renderRingCell(score, "Updates", String.valueOf(updates[2]),
                    new String[]{"Available updates", "Installed: " + updates[0] + ", downloaded: " + updates[1]},
                    RingRenderer.MODE_ANOMALY, null)));
        } else {
            rv.add(tile("/debug?d=2", RingRenderer.renderRingCell(-1, "Updates", "—",
                    new String[]{"Update manager not running"}, RingRenderer.MODE_NEUTRAL, null)));
        }

        rv.add(tile("/debug?d=3", skmCell("Router SKM", skmRouter)));
        rv.add(tile("/debug?d=4", skmCell("Client SKMs", skmClients)));

        if (streams != null) {
            int total = streams[0] + streams[1];
            double score = Math.min(total / 100.0, 1.0);
            rv.add(tile("/streams", RingRenderer.renderRingCell(score, "Streams", String.valueOf(total),
                    new String[]{"Active streams", "Inbound: " + streams[0] + ", outbound: " + streams[1]},
                    RingRenderer.MODE_NEUTRAL, null)));
        } else {
            rv.add(tile("/streams", RingRenderer.renderRingCell(-1, "Streams", "—",
                    new String[]{"No stream data"}, RingRenderer.MODE_NEUTRAL, null)));
        }

        if (dht != null && dht[1] > 0) {
            long capacity = dht[1] * dht[2];
            double fill = capacity > 0 ? Math.min((double) dht[0] / capacity, 1.0) : 0;
            double fullest = dht[2] > 0 ? Math.min((double) dht[3] / dht[2], 1.0) : 0;
            rv.add(tile("/debug?d=5", RingRenderer.renderRingCell(fill, "DHT fill", String.valueOf(dht[0]),
                    new String[]{"Keys in " + dht[1] + " buckets", "Capacity: " + capacity +
                            ", fullest bucket: " + dht[3] + "/" + dht[2]},
                    RingRenderer.MODE_RATIO, null)));
            rv.add(tile("/debug?d=5", RingRenderer.renderRingCell(fullest, "DHT fullest", dht[3] + "/" + dht[2],
                    new String[]{"Fullest bucket fill", "Eviction pressure at capacity"},
                    RingRenderer.MODE_ANOMALY, null)));
        } else {
            rv.add(tile("/debug?d=5", RingRenderer.renderRingCell(-1, "DHT", "—",
                    new String[]{"No bucket data"}, RingRenderer.MODE_NEUTRAL, null)));
        }

        return rv;
    }

    /**
     * One ratchet-SKM cell: outbound sessions ring with tags and lifetime
     * counters in the tooltip.
     *
     * @param label the cell label
     * @param stats the ratchet stats, or null
     * @return the cell html, never null
     */
    private static String skmCell(String label, long[] stats) {
        if (stats == null) {
            return RingRenderer.renderRingCell(-1, label, "—",
                    new String[]{"No session data"}, RingRenderer.MODE_NEUTRAL, null);
        }
        double score = Math.min(stats[1] / 100.0, 1.0);
        return RingRenderer.renderRingCell(score, label, String.valueOf(stats[1]),
                new String[]{"Outbound sessions", "Inbound tags: " + stats[0] +
                        ", created: " + stats[2] + ", completed: " + stats[4] + ", misses: " + stats[5]},
                RingRenderer.MODE_NEUTRAL, null);
    }

    /**
     * Wrap a ring cell in a link to its debug tab.
     *
     * @param href the tab url
     * @param cell the ring cell html
     * @return the linked cell html, never null
     */
    private static String tile(String href, String cell) {
        return "<a class=ring-cell-link href=\"" + href + "\">" + cell + "</a>";
    }

    /**
     * Build the overview: tile grid then up to four recorded mini-graphs.
     *
     * @return the page html, never null
     */
    public String getOverviewHTML() {
        StringBuilder buf = new StringBuilder(4096);
        buf.append("<h2 id=debug_overview>Debug Overview</h2>\n<div class=ring-grid id=debugstats>\n");
        for (String cell : buildTiles(_services(), _appCounts(), _updateCounts(), _routerSKM(),
                                      _clientSKMSum(), _streamCounts(), _dhtStats())) {
            buf.append(cell).append('\n');
        }
        buf.append("</div>\n");
        net.i2p.router.web.GraphGenerator gen = net.i2p.router.web.GraphGenerator.instance(_context);
        if (gen != null) {
            java.util.Set<String> enabled = new java.util.HashSet<>();
            for (net.i2p.router.web.GraphListener lsnr : gen.getListeners()) {
                enabled.add(lsnr.getRate().getRateStat().getName());
            }
            List<String> stats = pickGraphStats(enabled);
            if (!stats.isEmpty()) {
                java.util.Map<String, String> labels = new java.util.HashMap<>();
                for (String stat : stats) {
                    String en = GRAPH_LABELS.get(stat);
                    if (en != null) {labels.put(stat, _t(en));}
                }
                buf.append(buildGraphs(stats, _context.clock().now(), labels)).append('\n');
            }
        }
        return buf.toString();
    }

    /**
     * Pick up to GRAPH_COUNT stats to graph: bw.combined first when both
     * bw rates are enabled, then the priority wish list, then a
     * deterministic backfill from any other enabled stats so the row fills
     * out. A single stat not in the router's stat.summaries list would
     * make viewstat.jsp 400, so only enabled stats are ever shown.
     *
     * @param enabled names of stats with a graph listener
     * @return the stat names to graph, at most GRAPH_COUNT, never null
     */
    static List<String> pickGraphStats(java.util.Set<String> enabled) {
        List<String> rv = new ArrayList<>(GRAPH_COUNT);
        boolean combined = enabled.contains("bw.sendRate") && enabled.contains("bw.recvRate");
        if (combined) {rv.add("bw.combined");}
        for (String stat : GRAPH_STATS) {
            if (rv.size() == GRAPH_COUNT) {return rv;}
            if (enabled.contains(stat)) {rv.add(stat);}
        }
        if (rv.size() < GRAPH_COUNT) {
            List<String> rest = new ArrayList<>(enabled);
            rest.removeAll(rv);
            if (combined) {
                // already shown as the combined graph
                rest.remove("bw.sendRate");
                rest.remove("bw.recvRate");
            }
            java.util.Collections.sort(rest);
            for (String stat : rest) {
                if (rv.size() == GRAPH_COUNT) {break;}
                rv.add(stat);
            }
        }
        return rv;
    }

    /**
     * Build the mini-graph row for the given stats, at the standard graph
     * size with grid but no legend or title - the tiny sidebar template
     * is not used here. bw.combined needs no period parameter (viewstat.jsp
     * resolves it specially) and links to the plain graphs page; a single
     * stat only draws when a period is given, so 60 one-minute buckets =
     * 60 min.
     *
     * @param stats the stats to graph, non-empty
     * @param now current time, used as a cache-buster
     * @param labels translated display labels, stat name to caption
     * @return the graph div html, never null
     */
    static String buildGraphs(List<String> stats, long now, java.util.Map<String, String> labels) {
        StringBuilder buf = new StringBuilder(stats.size() * 300 + 64);
        buf.append("<div class=debug_graphs>");
        for (String stat : stats) {
            boolean combined = "bw.combined".equals(stat);
            String label = labels.getOrDefault(stat, stat);
            buf.append("\n<div class=graph-cell><a href=\"")
               .append(combined ? "/stats" : "/stats?stat=" + stat)
               .append("\"><div class=graph-label>").append(label)
               .append("</div><img src=\"/viewstat.jsp?stat=").append(stat);
            if (!combined) {buf.append("&amp;period=60000");}
            buf.append("&amp;periodCount=60&amp;width=600&amp;height=120&amp;hideTitle=true&amp;hideLegend=true&amp;t=")
               .append(now).append("\" alt=\"").append(stat).append("\"></a></div>");
        }
        buf.append("</div>");
        return buf.toString();
    }

    private int _services() {
        return _context.portMapper().getForwardedServices().size();
    }

    private int[] _appCounts() {
        net.i2p.router.startup.RouterAppManager ram = _context.routerAppManager();
        return ram != null ? ram.getDebugCounts() : null;
    }

    private int[] _updateCounts() {
        net.i2p.app.ClientAppManager cmgr = _context.clientAppManager();
        if (cmgr == null) {return null;}
        net.i2p.app.ClientApp app = cmgr.getRegisteredApp(net.i2p.update.UpdateManager.APP_NAME);
        return app instanceof net.i2p.router.update.ConsoleUpdateManager ?
               ((net.i2p.router.update.ConsoleUpdateManager) app).getDebugCounts() : null;
    }

    private long[] _routerSKM() {
        return _context.sessionKeyManager().getDebugStats();
    }

    /**
     * Sum the ratchet stats across distinct client session key managers.
     */
    private long[] _clientSKMSum() {
        java.util.Set<net.i2p.data.Destination> clients = _context.clientManager().listClients();
        java.util.Set<net.i2p.crypto.SessionKeyManager> seen = new java.util.HashSet<>(clients.size());
        long[] sum = null;
        for (net.i2p.data.Destination dest : clients) {
            net.i2p.crypto.SessionKeyManager skm =
                _context.clientManager().getClientSessionKeyManager(dest.calculateHash());
            if (skm == null || !seen.add(skm)) {continue;}
            long[] stats = skm.getDebugStats();
            if (stats == null) {continue;}
            if (sum == null) {sum = new long[stats.length];}
            for (int i = 0; i < stats.length; i++) {sum[i] += stats[i];}
        }
        return sum;
    }

    private int[] _streamCounts() {
        return StreamHelper.countStreams(net.i2p.i2ptunnel.TunnelControllerGroup.getInstance());
    }

    private long[] _dhtStats() {
        return _context.netDb().getDebugStats();
    }
}
