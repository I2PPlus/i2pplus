package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.math.RoundingMode;
import net.i2p.stat.RateConstants;
import java.text.Collator;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.TunnelTestStatus;
import net.i2p.router.transport.CommSystemFacadeImpl;
import net.i2p.router.transport.TransportImpl;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.pool.TunnelPool;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.Messages;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Addresses;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.util.ObjectCounterUnsafe;

/**
 *  For /tunnels.jsp, used by TunnelHelper.
 */
class TunnelRenderer {
    private static final Pattern TUNNEL_PAREN = Pattern.compile("\\([^)]+\\)");
    private final RouterContext _context;
    private final Log _log;

    /**
     *  A bounded LRU cache extending LinkedHashMap with computeIfAbsent support.
     */
    @SuppressWarnings("java:S2975")
    private static class BoundedCache<K, V> extends LinkedHashMap<K, V> {
        private final int _maxSize;

        /**
         * BoundedCache.
         */
        public BoundedCache(int maxSize) {
            super(maxSize, 0.75f, true);
            _maxSize = maxSize;
        }

        /**
         * removeEldestEntry.
         */
        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > _maxSize;
        }

        /**
         * clone.
         */
        @Override
        public Object clone() {
            return super.clone();
        }

        /**
         * computeIfAbsent.
         */
        public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
            V value = get(key);
            if (value == null) {
                value = mappingFunction.apply(key);
                if (value != null) {
                    put(key, value);
                }
            }
            return value;
        }
    }

    private int DISPLAY_LIMIT = 100;
    /** Render rows in a single write up to this count; above it, flush every {@link #STREAM_BATCH} rows so the page paints progressively. */
    private static final int MAX_BEFORE_STREAMING = 100;
    /** Rows rendered per flush when streaming a large table. */
    private static final int STREAM_BATCH = 100;
    /** Emit a per-row data-key (peer hash) for worker-side row diffing; fragment renders only, so full pages stay byte-for-byte identical. */
    private boolean _fragmentKeys;
    /** Length of the truncated base64 hash used as a per-row data-key in fragment mode. */
    private static final int KEY_LEN = 16;
    private int displayed;
    private static final DecimalFormat TWO_DECIMALS = new DecimalFormat("#0.00");
    private static String fmt(double val) { synchronized (TWO_DECIMALS) { return TWO_DECIMALS.format(val); } }
    private static final DecimalFormat ZERO_DECIMALS = new DecimalFormat("#0");
    static {ZERO_DECIMALS.setRoundingMode(RoundingMode.HALF_UP);}
    private static String fmt0(double val) { synchronized (ZERO_DECIMALS) { return ZERO_DECIMALS.format(val); } }

    private static final String PROP_ENABLE_REVERSE_LOOKUPS = "routerconsole.enableReverseLookups";
    /**
     * enableReverseLookups.
     */
    public boolean enableReverseLookups() {
        return _context.getBooleanProperty(PROP_ENABLE_REVERSE_LOOKUPS);
    }

    /**
     * TunnelRenderer.
     */
    public TunnelRenderer(RouterContext ctx) {
        _context = ctx;
        _log = _context.logManager().getLog(TunnelRenderer.class);
    }

    private final BoundedCache<Hash, RouterInfo> routerInfoCache = new BoundedCache<>(5000);
    private final BoundedCache<Hash, ReverseLookupResult> reverseLookupResults = new BoundedCache<>(5000);
    private final BoundedCache<Hash, String> peerToIP = new BoundedCache<>(5000);

    /**
     * renderStatusHTML.
     */
    public void renderStatusHTML(Writer out) throws IOException {
        boolean isAdvanced = _context.getBooleanProperty(HelperBase.PROP_ADVANCED);
        TunnelManagerFacade tm = _context.tunnelManager();
        TunnelPool ei = tm.getInboundExploratoryPool();
        TunnelPool eo = tm.getOutboundExploratoryPool();
        out.write("<div class=tablewrap>\n<h3 class=tabletitle id=exploratory>" + _t("Exploratory"));
        // links are set to float:right in CSS so they will be displayed in reverse order
        out.write(" <a href=\"/configtunnels#exploratory\" title=\"" +
               _t("Configure tunnels") + "\">[" + _t("configure") + "]</a>");
        out.write(" <a class=lsview style=pointer-events:none><span class=b32>EXPL</span></a>");
        out.write("</h3>\n");
        renderPoolSummary(out, ei, eo, null);
        renderPool(out, ei, eo);
        out.write("</div>\n");
        // add empty span so we can link to client tunnels in the sidebar
        out.write("<span id=client_tunnels></span>");

        Map<Hash, TunnelPool> clientInboundPools = tm.getInboundClientPools();
        // display name to in pool
        List<TunnelPool> sorted = new ArrayList<>(clientInboundPools.values());
        if (sorted.size() > 1)
            DataHelper.sort(sorted, new TPComparator());
        for (TunnelPool in : sorted) {
            Hash client = in.getSettings().getDestination();
            boolean isLocal = _context.clientManager().isLocal(client);
            if ((!isLocal && !isAdvanced) ||
                (getTunnelName(in).startsWith("Ping") && getTunnelName(in).contains("[")) ||
                getTunnelName(in).equals("I2Ping")) {
                continue;
            }
            TunnelPool outPool = tm.getOutboundPool(client);
            if (in.getSettings().getAliasOf() != null ||
                (outPool != null && outPool.getSettings().getAliasOf() != null)) {
                // skip aliases, we will print a header under the main tunnel pool below
                continue;
            }
            // Skip Hostchecker/ping tunnels - they have shouldTest=false
            if (!in.getSettings().shouldTest() ||
                (outPool != null && !outPool.getSettings().shouldTest())) {
                continue;
            }
            String b64 = client.toBase64().substring(0,4);
            if (isLocal) {
                String tname = getTunnelName(in);
                String tip = getPoolTip(in);
                out.write("<div class=tablewrap>\n");
                out.write("<h3 class=\"");
                if (_context.clientManager().shouldPublishLeaseSet(client)) {
                    out.write("server ");
                    if (tname.equals(_t("I2PSnark")) || tname.startsWith("I2PSnark -")) {
                    	   out.write("snark ");
                    }
                    else if ("messenger".equalsIgnoreCase(tname) ||
                             "i2pchat".equalsIgnoreCase(tname)) {
                        out.write("i2pchat ");
                    }
                }
                else {out.write("client ");}
                out.write("tabletitle\" ");
                if (tip != null) {
                    out.write("data-tip=\"" + tip + "\" ");
                }
                out.write("id=\"" + b64 + "\">");
                out.write(tname);
                // links are set to float:right in CSS so they will be displayed in reverse order
                if (isAdvanced) {
                    out.write(" <a href=\"/configtunnels#" + b64 +"\" title=\"" +
                              _t("Configure tunnels for session") + "\">[" + _t("configure") + "]</a>");
                } else {
                    out.write(" <a href=\"/tunnelmanager\" title=\"" +
                              _t("Configure tunnels") + "\">[" + _t("configure") + "]</a>");
                }
                out.write(" <a class=\"lsview\" href=\"/netdb?l=3#ls_" + client.toBase32().substring(0,4) + "\">" +
                          "<span class=\"b32\" title=\"" + _t("View LeaseSet") + "\">" +
                          client.toBase32().substring(0,4) + "</span></a>");
                out.write("</h3>\n");
                renderPoolSummary(out, in, outPool, client);

                // list aliases
                Set<Hash> aliases = in.getSettings().getAliases();
                if (aliases != null) {
                    for (Hash a : aliases) {
                        TunnelPool ain = clientInboundPools.get(a);
                        if (ain != null) {
                            String aname = ain.getSettings().getDestinationNickname();
                            String ab64 = a.toBase64().substring(0,4);
                            if (aname == null) {aname = ab64;}
                            out.write("<h3 class=tabletitle ");
                            out.write("id=\"" + ab64 + "\">");
                            out.write(DataHelper.escapeHTML(_t(aname)));
                            if (isAdvanced) {
                                out.write(" <a href=\"/configtunnels#" + b64 +"\" title=\"" +
                                          _t("Configure tunnels for session") + "\">[" + _t("configure") + "]</a>");
                            } else {
                                out.write(" <a href=\"/tunnelmanager\" title=\"" +
                                          _t("Configure tunnels") + "\">[" + _t("configure") + "]</a>");
                            }
                            out.write("</h3>\n");
                        }
                    }
                }
                renderPool(out, in, outPool);
                out.write("</div>\n");
            }
        }
    }

    /**
     * renderParticipating.
     */
    @SuppressWarnings("PMD.UnsynchronizedStaticFormatter")
    public synchronized void renderParticipating(Writer out, boolean bySpeed) throws IOException {
        boolean isAdvanced = _context.getBooleanProperty(HelperBase.PROP_ADVANCED);
        List<HopConfig> participating = _context.tunnelDispatcher().listParticipatingTunnels();
        StringBuilder sb = new StringBuilder(Math.max(32*1024, displayed*1024));
        boolean hasTransit = !participating.isEmpty();
        if (hasTransit) {
            sb.append("<div class=tablewrap>\n<h3 class=tabletitle id=participating>");
            if (bySpeed) {sb.append(_t("Fastest Active Transit Tunnels"));}
            else {sb.append(_t("Most Recent Active Transit Tunnels"));}
            sb.append("&nbsp;&nbsp;<a id=refreshPage class=refreshpage style=float:right href=/transit>")
              .append(_t("Refresh")).append("</a></h3>\n");
            int bwShare = getShareBandwidth();
            if (bwShare > 12) {
                sb.append("<table id=allTransit class=\"tunneldisplay tunnels_participating\">\n<thead><tr data-sort-method=thead><th class=role>")
                  .append(_t("Role")).append("</th><th class=expiry");
                if (!bySpeed) {sb.append(" data-sort-default");}
                sb.append(" data-sort-method=number>")
                  .append(_t("Expiry"))
                  .append("</th><th class=data title=\"")
                  .append(_t("Data transferred"))
                  .append("\" data-sort-method=number>")
                  .append(_t("Data"))
                  .append("</th><th class=speed");
                if (bySpeed) {sb.append(" data-sort-default");}
                sb.append(" data-sort-method=number>").append(_t("Speed")).append("</th>");
                if (isAdvanced) {
                  //sb.append("<th class=limit data-sort-method=number>").append(_t("Limit")).append("</th>");
                  sb.append("<th class=rx data-sort-method=number>")
                    .append(_t("Receive on"))
                    .append("</th>");
                }
                sb.append("<th class=from>")
                   .append(_t("From"))
                   .append("</th>");
                if (isAdvanced) {sb.append("<th class=tx>").append(_t("Send on")).append("</th>");}
                sb.append("<th class=to>")
                   .append(_t("To"))
                   .append("</th></tr>\n</thead>\n<tbody id=transitPeers>\n");
                boolean stream = participating.size() > MAX_BEFORE_STREAMING;
                if (stream) {
                    out.write(sb.toString());
                    out.flush();
                    sb.setLength(0);
                }
                int rowsSinceFlush = 0;
                long processed = 0;
                RateStat rs = _context.statManager().getRate("tunnel.participatingMessageCount");
                if (rs != null) {processed = (long)rs.getRate(RateConstants.TEN_MINUTES).getLifetimeTotalValue();}
                int inactive = 0;
                displayed = 0;
                if (bySpeed) {DataHelper.sort(participating, new TunnelComparatorBySpeed());}
                else {DataHelper.sort(participating, new TunnelComparator());}
                final String outboundEndpoint = _t("Outbound Endpoint");
                final String inboundGateway = _t("Inbound Gateway");
                final String participant = _t("Participant");
                final String gracePeriodTip = _t("grace period");
                final String tunnelIdTip = _t("Tunnel identity");
                long now = _context.clock().now();
                for (int i = 0; i < participating.size(); i++) {
                    HopConfig cfg = participating.get(i);
                    int count = cfg.getProcessedMessagesCount();
                    if (count <= 0) {
                        inactive++;
                        continue;
                    }
                    Hash to = cfg.getSendTo();
                    Hash from = cfg.getReceiveFrom();
                    // everything that isn't 'recent' is already in the tunnel.participatingMessageCount stat
                    processed += cfg.getRecentMessagesCount();
                    if (++displayed > DISPLAY_LIMIT) {continue;}
                    sb.append("<tr>");
                    if (to == null) {
                        sb.append("<td class=\"role obep\" title=\"").append(outboundEndpoint).append("\">")
                          .append(outboundEndpoint).append("</td>");
                    } else if (from == null) {
                        sb.append("<td class=\"role ibgw\" title=\"").append(inboundGateway)
                          .append("\">").append(inboundGateway).append("</td>");
                    } else {
                        sb.append("<td class=\"role ptcp\" title=\"").append(participant)
                          .append("\">").append(participant).append("</td>");
                    }
                    long timeLeft = cfg.getExpiration()-now;
                    sb.append("<td class=expiry data-sort=").append(timeLeft).append(">");
                    if (timeLeft > 0) {
                        sb.append(renderExpiryBar(timeLeft));
                    } else {
                        sb.append("<i>").append(gracePeriodTip).append("</i>");
                    }
                    sb.append("</td>");

                    double sizeInKB = count * 1024.0 / 1000.0;
                    double sizeInMB = sizeInKB / 1024.0;
                    sb.append("<td class=data data-sort=")
                      .append(count).append("><span class=right>")
                      .append(sizeInKB >= 1024 ? fmt(sizeInMB) : fmt0(sizeInKB))
                      .append("</span><span class=left>&#8239;")
                      .append(sizeInKB >= 1024 ? "MB" : "KB")
                      .append("</span></td>");

                    int lifetime = (int) ((now - cfg.getCreation()) / 1000);
                    if (lifetime <= 0) {lifetime = 1;}
                    else if (lifetime > 10*60) {lifetime = 10*60;}
                    float bps = 1024f * count / lifetime;
                    float kbps = bps / 1024;
                    sb.append("<td class=speed data-sort=").append(bps).append("><span class=right>")
                      .append(fmt(kbps)).append("&#8239;</span><span class=left>KB/s</span></td>");

                    long recv = cfg.getReceiveTunnelId();
                    if (isAdvanced) {
                        //sb.append("<td class=limit data-sort=").append(cfg.getAllocatedBW()).append(">");
                        //    sb.append("<span>").append(DataHelper.formatSize2Decimal(cfg.getAllocatedBW())).append("B/s").append("</span>");
                        //sb.append("</td>");
                        if (recv != 0) {
                            sb.append("<td class=rx title=\"").append(tunnelIdTip).append("\"><span class=tunnel_id>")
                              .append(recv).append("</span></td>");
                        } else {sb.append("<td class=rx><span hidden>&ndash;</span></td>");}
                    }
                    if (from != null) {sb.append("<td class=from><div class=tunnel_peer>").append(netDbLink(from)).append("</div></td>");}
                    else {sb.append("<td class=from><span hidden>&ndash;</span></td>");}
                    long send = cfg.getSendTunnelId();
                    if (isAdvanced) {
                        if (send != 0) {
                            sb.append("<td class=tx title=\"").append(tunnelIdTip).append("\"><span class=tunnel_id>")
                              .append(send).append("</span></td>");
                        } else {
                            sb.append("<td class=tx><span hidden>&ndash;</span></td>");
                        }
                    }
                    if (to != null) {sb.append("<td class=to><div class=tunnel_peer>").append(netDbLink(to)).append("</div></td>");}
                    else {sb.append("<td class=to><span hidden>&ndash;</span></td>");}
                    sb.append("</tr>\n");
                    if (stream && ++rowsSinceFlush >= STREAM_BATCH) {
                        out.write(sb.toString());
                        out.flush();
                        sb.setLength(0);
                        rowsSinceFlush = 0;
                    }
                }
                sb.append("</tbody>\n<tfoot id=statusnotes><tr><td colspan=8>");
                if (displayed >= 2) {
                    sb.append("<b>").append(_t("Active") ).append(":</b>&nbsp;").append(displayed);
                    if (inactive > 0) {
                        sb.append("&nbsp;&bullet;&nbsp;<b>").append(_t("Inactive")).append(":</b>&nbsp;").append(inactive)
                          .append("&nbsp;&bullet;&nbsp;<b>").append(_t("Total")).append(":</b>&nbsp;").append((inactive + displayed));
                    }
                } else if (inactive > 0) {
                    sb.append("<b>").append(_t("Inactive")).append(":</b>&nbsp;").append(inactive);
                }
                sb.append("</td></tr>\n<tr class=bwUsage><td colspan=8>")
                  .append("<b>").append(_t("Lifetime bandwidth usage")).append(":</b>&nbsp;")
                  .append(DataHelper.formatSize2(processed*1024, true).replace("i", "")).append("B")
                  .append("</td></tr></tfoot>\n</table>\n</div>\n");
            } else { // bwShare < 12K/s
                sb.append("<div class=\"statusnotes noparticipate\"><b>")
                  .append(_t("Not enough shared bandwidth to build transit tunnels.")).append("</b> <a href=\"config\">[")
                  .append(_t("Configure")).append("]</a>\n</div>\n");
            }
        } else if (_context.router().isHidden()) {
            sb.append("<p class=infohelp>")
              .append(_t("Router is currently operating in Hidden Mode which prevents transit tunnels from being built.")).append("</p>");
        } else {
            sb.append("<p class=infohelp>").append(_t("No transit tunnels currently active.")).append("</p>");
        }

        out.write(sb.toString());
        out.flush();
        sb.setLength(0);
    }

    /**
     * renderTransitSummary.
     */
    @SuppressWarnings("PMD.UnsynchronizedStaticFormatter")
    public synchronized void renderTransitSummary(Writer out) throws IOException {
        List<HopConfig> participating = _context.tunnelDispatcher().listParticipatingTunnels();
        if (!participating.isEmpty() && participating.size() > 1) {
            // Counters for tunnels and bandwidth by peer
            ObjectCounterUnsafe<Hash> counts = new ObjectCounterUnsafe<>();
            ObjectCounterUnsafe<Hash> bws = new ObjectCounterUnsafe<>();
            for (HopConfig cfg : participating) {
                Hash from = cfg.getReceiveFrom();
                Hash to = cfg.getSendTo();
                int msgs = cfg.getProcessedMessagesCount();
                if (from != null) {
                    counts.increment(from);
                    if (msgs > 0) bws.add(from, msgs);
                }
                if (to != null) {
                    counts.increment(to);
                    if (msgs > 0) bws.add(to, msgs);
                }
            }

            StringBuilder tbuf = new StringBuilder(3 * 512);
            tbuf.append("<div class=tablewrap>\n<h3 class=tabletitle>")
                .append(_t("Transit Tunnels by Peer (Top {0})", DISPLAY_LIMIT*10))
                .append("</h3>\n<table id=transitSummary class=\"tunneldisplay tunnels_participating\">\n<thead><tr><th id=country data-sort-direction=ascending>")
                .append(_t("Country"))
                .append("</th><th id=router data-sort-direction=ascending>")
                .append(_t("Router"))
                .append("</th><th id=version>")
                .append(_t("Version"))
                .append("</th><th id=tier data-sort=LMNOPX>")
                .append(_t("Tier"))
                .append("</th><th id=address>")
                .append(_t("Address"))
                .append("</th>")
                .append("<th id=domain data-sort-method=string data-sort-caseinsensitive>").append(_t("Domain")).append("</th>");
            tbuf.append("<th class=tcount data-sort-method=number data-sort-default>")
                .append(_t("Tunnels"))
                .append("</th><th id=data data-sort-method=number>")
                .append(_t("Data"))
                .append("</th><th id=edit data-sort-method=none>")
                .append(_t("Edit"))
                .append("</th></tr></thead>\n<tbody id=transitPeers>\n");
            out.write(tbuf.toString());

            int displayed = 0;
            List<Hash> sorted = counts.sortedObjects();
            long uptime = _context.router().getUptime();
            int bannedCount = 0;
            StringBuilder sb = new StringBuilder(4 * 512);
            boolean stream = sorted.size() > MAX_BEFORE_STREAMING;
            if (stream) {
                out.flush();
            }
            int rowsSinceFlush = 0;
            final String versionTip = _t("Show all routers with this version in the NetDb");
            final String banlistedTip = _t("Router is banlisted");
            final String unknownLabel = _t("unknown");
            final String configurePeerTip = _t("Configure peer");
            final String editLabel = _t("Edit");

            for (Hash h : sorted) {
                int count = counts.count(h);

                // Defensive - skip if count <= 0 (unlikely due to sorted list but just in case)
                if (count <= 0) continue;

                if (++displayed > DISPLAY_LIMIT*10) break;

                //RouterInfo info = _context.netDb().lookupRouterInfoLocally(h);
                RouterInfo info = routerInfoCache.computeIfAbsent(h, hash -> (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(hash));

                String hB64 = h.toBase64();
                String truncHash = hB64.substring(0,4);

                String ip = peerToIP.get(h);
                if (ip == null) {
                    byte[] direct = TransportImpl.getIP(h);
                    String directIP = (direct != null) ? Addresses.toString(direct) : "";
                    ip = !directIP.isEmpty() ? directIP : (info != null ? Addresses.toString(CommSystemFacadeImpl.getValidIP(info)) : null);
                    if (ip != null) {
                        peerToIP.put(h, ip);
                    }
                }

                String version = (info != null) ? info.getOption("router.version") : null;
                ReverseLookupResult rlResult = getReverseLookupInfo(h, info, uptime);
                boolean isBanned = _context.banlist().isBanlisted(h) ||
                                   _context.banlist().isBanlistedHostile(h);

                appendPeerIdentity(sb, h, hB64, truncHash, version, info, ip, rlResult,
                                   versionTip, banlistedTip, unknownLabel, isBanned);

                sb.append("<td class=tcount>").append(count).append("</td>");

                long bw = bws.count(h);
                sb.append("<td data-sort=").append(bw).append(">");
                if (bw > 0) {
                    sb.append("<span class=data>").append(fmt(bw).replace(".00", "")).append("KB</span>");
                } else {sb.append("<span class=data hidden>0KB</span>");}
                sb.append("</td>");

                sb.append("<td class=isBanned hidden>");
                if (isBanned) {
                    sb.append("<span hidden>ban</span><a class=banlisted href=\"/profiles?show=banned\" title=\"")
                      .append(banlistedTip).append("\">Banned</a> ");
                    bannedCount++;
                }
                sb.append("</td>");

                sb.append("<td>");
                if (info != null && info.getHash() != null) {
                    sb.append("<a class=configpeer href=\"/configpeer?peer=")
                      .append(info.getHash())
                      .append("\" title=\"").append(configurePeerTip).append("\">")
                      .append(editLabel)
                      .append("</a>");
                }
                sb.append("</td></tr>\n");
                if (stream && ++rowsSinceFlush >= STREAM_BATCH) {
                    out.write(sb.toString());
                    out.flush();
                    sb.setLength(0);
                    rowsSinceFlush = 0;
                }
            }

            sb.append("</tbody>\n</table>\n</div>\n");
            out.write(sb.toString());
            out.flush();
            sb.setLength(0);
        } else if (_context.router().isHidden()) {
            out.write("<p class=infohelp>");
            out.write(_t("Router is currently operating in Hidden Mode which prevents transit tunnels from being built."));
            out.write("</p>\n");
        } else {
            out.write("<p class=infohelp>");
            out.write(_t("No transit tunnels currently active."));
            out.write("</p>\n");
        }
    }

    /**
     *  Render the tunnel peer count table, the "all peers" tbody, or the
     *  totals footer row.
     *  <p>
     *  Full-page mode renders header, rows, and footer as one table; fragment
     *  mode (contentonly) renders just the named element so the page can
     *  refresh the tbody rows and footer without re-sending the full table.
     *
     *  @since 0.9.70+
     */
    @SuppressWarnings("PMD.UnsynchronizedStaticFormatter")
    public synchronized void renderPeers(Writer out) throws IOException {
        PeerRows pr = preparePeerRows();
        if (!pr.validPeerList.isEmpty() && (pr.tunnelCount > 0 || pr.partCount > 0)) {
            StringBuilder headerSb = new StringBuilder(pr.validPeerList.size() * 640 + 2048);
            headerSb.append("<div class=tablewrap>\n<h3 class=tabletitle id=peercount>")
                  .append(_t("All Tunnels by Peer"))
                  .append("&nbsp;&nbsp;<a id=refreshPage class=refreshpage style=float:right href=/tunnelpeercount>")
                  .append(_t("Refresh"))
                  .append("</a></h3>\n<table id=tunnelPeerCount><thead>\n<tr><th id=country data-sort-direction=ascending>")
                  .append(_t("Country")).append("</th><th id=router>")
                  .append(_t("Router")).append("</th><th id=version>")
                  .append(_t("Version")).append("</th><th id=tier data-sort=LMNOPX>")
                  .append(_t("Tier")).append("</th><th id=address title=\"")
                  .append(_t("Primary IP address"))
                  .append("\">").append(_t("Address")).append("</th>");
            headerSb.append("<th id=domain data-sort-method=string data-sort-caseinsensitive>").append(_t("Domain")).append("</th>");
            headerSb.append("<th class=tcount colspan=2 title=\"Client and Exploratory Tunnels\" data-sort-method=number data-sort-column-key=localCount>")
                    .append(_t("Local"))
                    .append("</th><th class=tcount colspan=2 data-sort-method=number data-sort-column-key=transitCount>")
                    .append(_t("Transit"))
                    .append("</th><th id=edit data-sort-method=none>")
                    .append(_t("Edit"))
                    .append("</th></tr>\n</thead>\n<tbody id=allPeers>\n");
            out.write(headerSb.toString());
            out.flush();

            final String versionTip = _t("Show all routers with this version in the NetDb");
            final String unknownLabel = _t("unknown");
            final String configurePeerTip = _t("Configure peer");
            final String editLabel = _t("Edit");
            final int chunkSize = 50;
            for (int start = 0; start < pr.validPeerList.size(); start += chunkSize) {
                int end = Math.min(start + chunkSize, pr.validPeerList.size());
                StringBuilder chunkSb = new StringBuilder();
                for (int i = start; i < end; i++) {
                    Hash h = pr.validPeerList.get(i);
                    RouterInfo info = routerInfoCache.get(h);
                    appendPeerRow(chunkSb, h, info, pr.tunnelCount, pr.partCount,
                                  pr.localCount.count(h), pr.transitCount.count(h),
                                  versionTip, unknownLabel, configurePeerTip, editLabel);
                }
                out.write(chunkSb.toString());
                out.flush();
            }
            StringBuilder footerSb = new StringBuilder();
            appendPeerFooter(footerSb, pr.validPeerList.size(), pr.tunnelCount, pr.partCount);
            out.write(footerSb.toString());
            out.flush();
        } else {
            out.write("<p class=infohelp>");
            out.write(_t("No local or transit tunnels currently active."));
            out.write("</p>\n");
        }
    }

    /**
     *  Render a single named element for the contentonly fragment mode of the
     *  tunnel peer count page: the "all peers" tbody with data-key rows, or
     *  the totals footer row. Renders nothing for unknown ids.
     *
     *  @param out the writer to render to
     *  @param id the element id
     *  @throws IOException if writing fails
     *  @since 0.9.70+
     */
    public void renderPeerFragment(Writer out, String id) throws IOException {
        PeerRows pr = preparePeerRows();
        if (pr.validPeerList.isEmpty()) {return;}
        if ("allPeers".equals(id)) {
            _fragmentKeys = true;
            final String versionTip = _t("Show all routers with this version in the NetDb");
            final String unknownLabel = _t("unknown");
            final String configurePeerTip = _t("Configure peer");
            final String editLabel = _t("Edit");
            StringBuilder sb = new StringBuilder(4 * 1024);
            sb.append("<tbody id=allPeers>\n");
            for (Hash h : pr.validPeerList) {
                RouterInfo info = routerInfoCache.get(h);
                appendPeerRow(sb, h, info, pr.tunnelCount, pr.partCount,
                              pr.localCount.count(h), pr.transitCount.count(h),
                              versionTip, unknownLabel, configurePeerTip, editLabel);
                if (sb.length() > 64 * 1024) {out.write(sb.toString()); out.flush(); sb.setLength(0);}
            }
            sb.append("</tbody>\n");
            out.write(sb.toString());
        } else if ("tableFooter".equals(id)) {
            StringBuilder sb = new StringBuilder(512);
            sb.append("<table><tbody>");
            appendPeerFooterRow(sb, pr.validPeerList.size(), pr.tunnelCount, pr.partCount);
            sb.append("</tbody></table>\n");
            out.write(sb.toString());
        }
    }

    /**
     *  Data for the tunnel peer count page: the counts per peer plus the
     *  validated, sorted peer list shared by the full and fragment renders.
     */
    private static class PeerRows {
        final ObjectCounter<Hash> localCount = new ObjectCounter<>();
        final ObjectCounter<Hash> transitCount = new ObjectCounter<>();
        int tunnelCount;
        int partCount;
        final List<Hash> validPeerList = new ArrayList<>();
    }

    /**
     *  Count tunnels per peer, resolve RouterInfos and reverse lookups, and
     *  build the validated peer list shared by the full and fragment renders.
     *
     *  @return the prepared counts and peer list
     *  @since 0.9.70+
     */
    private PeerRows preparePeerRows() {
        PeerRows pr = new PeerRows();
        pr.tunnelCount = countTunnelsPerPeer(pr.localCount);
        pr.partCount = countParticipatingPerPeer(pr.transitCount);
        long uptime = _context.router().getUptime();
        Set<Hash> peers = new HashSet<>();
        peers.addAll(pr.localCount.objects());
        peers.addAll(pr.transitCount.objects());
        List<Hash> peerList = new ArrayList<>(peers);
        Collections.sort(peerList, new CountryComparator(_context.commSystem()));
        for (Hash h : peerList) {
            RouterInfo info = routerInfoCache.computeIfAbsent(h, hash -> (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(hash));
            if (info == null) {continue;}
            pr.validPeerList.add(h);
            byte[] direct = TransportImpl.getIP(h);
            String directIP = (direct != null) ? Addresses.toString(direct) : "";
            String ip = !directIP.isEmpty() ? directIP : Addresses.toString(CommSystemFacadeImpl.getValidIP(info));
            peerToIP.put(h, ip);
            ReverseLookupResult rlr = getReverseLookupInfo(h, info, uptime);
            reverseLookupResults.put(h, rlr);
        }
        return pr;
    }

    /**
     *  Append one tunnel peer count row: identity cells plus local and transit
     *  tunnel bars and the edit link.
     *
     *  @since 0.9.70+
     */
    private void appendPeerRow(StringBuilder chunkSb, Hash h, RouterInfo info, int tunnelCount, int partCount,
                               int localTunnelCount, int transitTunnelCount, String versionTip,
                               String unknownLabel, String configurePeerTip, String editLabel) {
        String version = info != null ? info.getOption("router.version") : null;
        String ip = peerToIP.get(h);
        String hB64 = h.toBase64();
        String truncHash = hB64.substring(0, 4);
        ReverseLookupResult rlResult = reverseLookupResults.get(h);

        appendPeerIdentity(chunkSb, h, hB64, truncHash, version, info, ip, rlResult,
                           versionTip, null, unknownLabel, false);

        if (localTunnelCount > 0) {
            chunkSb.append("<td class=tcount data-sort-column-key=localCount data-sort=")
                   .append(localTunnelCount)
                   .append(">").append(localTunnelCount)
                   .append("</td><td class=bar data-sort-column-key=localCount>")
                   .append("<span class=percentBarOuter><span class=percentBarInner style=\"width:")
                   .append(fmt(localTunnelCount * 100.0 / tunnelCount).replace(".00", ""))
                   .append("%\"><span class=percentBarText>").append(localTunnelCount * 100 / tunnelCount)
                   .append("%</span></span></span>");
        } else {
            chunkSb.append("<td class=tcount colspan=2 data-sort-column-key=localCount data-sort=0></td>");
        }
        chunkSb.append("</td>");
        if (transitTunnelCount > 0) {
            chunkSb.append("<td class=tcount data-sort-column-key=transitCount data-sort=")
                   .append(transitTunnelCount)
                   .append(">").append(transitTunnelCount)
                   .append("</td><td class=bar data-sort-column-key=transitCount>")
                   .append("<span class=percentBarOuter><span class=percentBarInner style=\"width:")
                   .append(fmt(transitTunnelCount * 100.0 / partCount).replace(".00", ""))
                   .append("%\"><span class=percentBarText>").append(transitTunnelCount * 100 / partCount)
                   .append("%</span></span></span>")
                   .append("</td>");
        } else {
            chunkSb.append("<td class=tcount colspan=2 data-sort-column-key=transitCount data-sort=0></td>");
        }
        chunkSb.append("<td><a class=configpeer href=\"/configpeer?peer=")
               .append(info.getHash())
               .append("\" title=\"").append(configurePeerTip).append("\">")
               .append(editLabel)
               .append("</a></td></tr>\n");
    }

    /**
     *  Append the totals footer row of the tunnel peer count table.
     *
     *  @since 0.9.70+
     */
    private void appendPeerFooterRow(StringBuilder footerSb, int validPeerCount, int tunnelCount, int partCount) {
        footerSb.append("<tr class=tablefooter data-sort-method=none><td colspan=4><b>")
                .append(validPeerCount)
                .append(" ")
                .append(_t("unique peers"))
                .append("</b></td><td></td>");
        footerSb.append("<td></td>");
        footerSb.append("<td colspan=2><b>")
                .append(tunnelCount)
                .append(" ")
                .append(_t("local"))
                .append("</b></td>");
        if (partCount > 0) {
            footerSb.append("<td colspan=2><b>")
                    .append(partCount)
                    .append(" ")
                    .append(_t("transit"))
                    .append("</b></td>");
        } else {
            footerSb.append("<td></td>");
        }
        footerSb.append("<td></td></tr>");
    }

    /**
     *  Append the closing tfoot and wrapper for the tunnel peer count table.
     *
     *  @since 0.9.70+
     */
    private void appendPeerFooter(StringBuilder footerSb, int validPeerCount, int tunnelCount, int partCount) {
        footerSb.append("</tbody>\n<tfoot>");
        appendPeerFooterRow(footerSb, validPeerCount, tunnelCount, partCount);
        footerSb.append("</tfoot>\n</table>\n</div>\n");
    }

    /**
     * Encapsulates results of a reverse DNS lookup and related domain information
     * for a router peer IP address.
     * @since 0.9.68+
     */
    private static class ReverseLookupResult {
        String ip;
        String canonicalHostName;
        String domain;
        String whois;
    }

    /**
     * Performs a cached reverse lookup for the given router hash and its RouterInfo,
     * returning canonical hostname, domain, and cleaned WHOIS data if available.
     * <p>
     * Uses internal cache to avoid repeated DNS lookups and performs string cleanup
     * on WHOIS data for better display. Reverse lookups occur only if enabled
     * and uptime exceeds 30 seconds.
     *
     * @param h the router's hash identifier
     * @param info the RouterInfo instance for the router (may be null)
     * @param uptime current router uptime in milliseconds
     * @return a ReverseLookupResult holding the IP, canonical hostname, domain, and WHOIS info
     */
    private ReverseLookupResult getReverseLookupInfo(Hash h, RouterInfo info, long uptime) {
        ReverseLookupResult result = new ReverseLookupResult();

        byte[] direct = TransportImpl.getIP(h);
        String directIP = (direct != null) ? Addresses.toString(direct) : "";
        String ip = !directIP.isEmpty() ? directIP : (info != null ? Addresses.toString(CommSystemFacadeImpl.getValidIP(info)) : null);
        result.ip = ip;

        if (ip != null && uptime > 30 * 1000) {
            String rl = _context.commSystem().getLocalHostName(ip);
            result.canonicalHostName = rl;

            if (rl != null && rl.contains(" ")) {
                String whois = rl.replace("Administered by ", "")
                    .replace("Asia Pacific Network Information Centre (APNIC)", "APNIC")
                    .replace("Latin American and Caribbean IP address Regional Registry (LACNIC)", "LACNIC")
                    .replace("African Network Information Center (AFRINIC)", "AFRINIC")
                    .replace("RIPE Network Coordination Centre (RIPE)", "RIPE")
                    .replace("RIPE NCC", "RIPE")
                    .replace("Charter Communications Inc (CC-3517)", "CHARTER")
                    .replace("Google Fiber Inc. (GF)", "GOOGLE-FIBER")
                    .replace("Oracle Corporation (ORACLE-4)", "ORACLE")
                    .replace("FIBERNETICS CORPORATION (FC-1108)", "GIBERNETICS CORP")
                    .replace("FranTech Solutions (SYNDI-5)", "FRANTECH")
                    .replace("StormyCloud Inc (STORM-17)", "STORMYCLOUD")
                    .replace("T-Mobile USA, Inc. (TMOBI)", "T-MOBILE USA")
                    .replace("Data Bridge Limited (DBL-136)", "DATA BRIDGE LTD")
                    .replace("Mediacom Communications Corp (MCC-244)", "MEDIACOM")
                    .replace("AT&T Enterprises, LLC (AEL-360)", "AT&T")
                    .replace("AT&T Services, Inc.", "AT&T")
                    .replace("YELCOT TELEPHONE COMPANY (YELCOT)", "YELCOT")
                    .replace("State University of New York at Stony Brook (SUNYASB-Z)", "SUNYASB")
                    .replace("Cloudflare, Inc. (CLOUD14)", "CLOUDFLARE")
                    .replace("DigitalOcean, LLC (DO-13)", "DIGITALOCEAN")
                    .replace("Nortex Communications Company", "NORTEX")
                    .replace("ROOT", _t("PRIVATE IP ADDRESS"))
                    .replace("NON-RIPE-NCC-MANAGED-ADDRESS-BLOCK", "unknown")
                    .replace("unknown", _t("unknown"))
                    .replaceAll(TUNNEL_PAREN.pattern(), "")
                    .replaceAll("(?i)AT\\s*&\\s*T\\s+.*", "AT&T")
                    .replaceAll("(?i)&\\s*T\\b.*", "AT&T")
                    .trim();
                result.whois = whois;
                result.domain = null;
            } else if (rl != null) {
                result.domain = CommSystemFacadeImpl.getDomain(rl);
                result.whois = null;
            } else {
                result.domain = null;
                result.whois = null;
            }
        }

        return result;
    }

    /**
     * Renders an HTML table describing the bandwidth tiers used for tunnels,
     * including their ranges and labels, and writes the output to the provided Writer.
     *
     * @param out the Writer to which the HTML content is written
     * @throws IOException if an I/O error occurs during writing
     */
    public void renderGuide(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<div class=tablewrap>\n<h3 class=tabletitle id=defs>")
           .append(_t("Bandwidth Tiers"))
           .append("</h3>\n<table id=tunnel_defs>\n<tbody><tr><td>&nbsp;</td><td><span class=tunnel_cap><b>L</b></span></td><td>")
           .append(_t("{0} shared bandwidth", range(Router.MIN_BW_L, Router.MIN_BW_M)))
           .append("</td><td><span class=tunnel_cap><b>M</b></span></td><td>")
           .append(_t("{0} shared bandwidth", range(Router.MIN_BW_M, Router.MIN_BW_N)))
           .append("</td><td>&nbsp;</td></tr>\n<tr><td>&nbsp;</td><td><span class=tunnel_cap><b>N</b></span></td><td>")
           .append(_t("{0} shared bandwidth", range(Router.MIN_BW_N, Router.MIN_BW_O)))
           .append("</td><td><span class=tunnel_cap><b>O</b></span></td><td>")
           .append(_t("{0} shared bandwidth", range(Router.MIN_BW_O, Router.MIN_BW_P)))
           .append("</td><td>&nbsp;</td></tr>\n<tr><td>&nbsp;</td><td><span class=tunnel_cap><b>P</b></span></td><td>")
           .append(_t("{0} shared bandwidth", range(Router.MIN_BW_P, Router.MIN_BW_X)))
           .append("</td><td><span class=tunnel_cap><b>X</b></span></td><td>")
           .append(_t("Over {0} shared bandwidth", Math.round(Router.MIN_BW_X * 1.024f) + "KB/s"))
           .append("</td><td></td></tr></tbody>\n</table>\n</div>\n");
        out.append(buf);
        out.flush();
        buf.setLength(0);
    }

    /** @since 0.9.33 */
    static String range(int f, int t) {
        return Math.round(f * 1.024f) + " - " + (Math.round(t * 1.024f) - 1) + " KB/s";
    }

    private static class TunnelComparator implements Comparator<HopConfig>, Serializable {
          /**
           * compare.
           */
          @Override
          public int compare(HopConfig l, HopConfig r) {
             long le = l.getExpiration();
             long re = r.getExpiration();
             if (le < 0) {le = 0;}
             if (re < 0) {re = 0;}
             if (le < re) {return 1;}
             if (le > re) {return -1;}
             return 0;
        }
    }

    /** @since 0.9.35 */
    private static class TunnelComparatorBySpeed implements Comparator<HopConfig>, Serializable {
          /**
           * compare.
           */
          @Override
          public int compare(HopConfig l, HopConfig r) {
             long now = System.currentTimeMillis();
             int countL = l.getProcessedMessagesCount();
             int countR = r.getProcessedMessagesCount();
             int lifeL = (int) Math.min((now - l.getCreation()) / 1000, 600);
             int lifeR = (int) Math.min((now - r.getCreation()) / 1000, 600);
             if (lifeL <= 0) lifeL = 1;
             if (lifeR <= 0) lifeR = 1;
             float bpsL = 1024f * countL / lifeL;
             float bpsR = 1024f * countR / lifeR;
             return Float.compare(bpsR, bpsL);
        }
    }

    private static class TunnelInfoComparator implements Comparator<TunnelInfo>, Serializable {
          /**
           * compare.
           */
          @Override
          public int compare(TunnelInfo l, TunnelInfo r) {
             long le = l.getExpiration();
             long re = r.getExpiration();
             if (le < re) {return 1;}
             if (le > re) {return -1;}
             return 0;
        }
    }

    /**
     *  Sort tunnels by the name of the tunnel
     *  @since 0.9.57
     */
    private class TPComparator implements Comparator<TunnelPool> {
          private final Collator _comp = Collator.getInstance();
          /**
           * compare.
           */
          @Override
          public int compare(TunnelPool l, TunnelPool r) {
             int rv = _comp.compare(getTunnelName(l), getTunnelName(r));
             if (rv != 0) {return rv;}
             rv = l.getSettings().getDestination().toBase32().compareTo(r.getSettings().getDestination().toBase32());
             if (rv != 0) {return rv;}
             long lexp = getNextExpiry(l);
             long rexp = getNextExpiry(r);
             if (lexp != rexp) {
                 return lexp > rexp ? 1 : -1;
             }
             return 0;
         }
    }

    /**
     * Get display name for the tunnel
     * @return the tunnel name
     * @since 0.9.57
     */
    private String getTunnelName(TunnelPool in) {
        TunnelPoolSettings ins = in.getSettings();
        String name = ins.getDestinationNickname();
        if (name == null) {
            TunnelPoolSettings outPool = _context.tunnelManager().getOutboundSettings(ins.getDestination());
            if (outPool != null) {name = outPool.getDestinationNickname();}
        }
        if (name != null) {return DataHelper.escapeHTML(_t(name));}
        return ins.getDestination().toBase32();
    }

    /**
     *  Get the pool's torrent names for the tooltip, when the tunnel is a shared
     *  I2PSnark pool destination and the i2psnark.poolMembers session property is set.
     *  Single-torrent pools have the torrent's name for a nickname, so the check matches
     *  all I2PSnark tunnels; dedicated destinations have no poolMembers property.
     *
     *  @param in the tunnel pool
     *  @return the escaped tooltip text, or null for a dedicated destination
     */
    private String getPoolTip(TunnelPool in) {
        String name = in.getSettings().getDestinationNickname();
        if (name == null || !name.startsWith("I2PSnark -")) {
            return null;
        }
        String members = in.getSettings().getUnknownOptions().getProperty("i2psnark.poolMembers");
        if (members == null || members.length() == 0) {
            return null;
        }
        return DataHelper.escapeHTML(members);
    }

    private void renderPool(Writer out, TunnelPool in, TunnelPool outPool) throws IOException {
        Comparator<TunnelInfo> comp = new TunnelInfoComparator();
        List<TunnelInfo> tunnels;
        if (in == null) {tunnels = new ArrayList<>();}
        else {
            tunnels = in.listTunnels();
            Collections.sort(tunnels, comp);
        }
        if (outPool != null) {
            List<TunnelInfo> otunnels = outPool.listTunnels();
            Collections.sort(otunnels, comp);
            tunnels.addAll(otunnels);
        }

        long processedIn = (in != null ? in.getLifetimeProcessed() : 0);
        long processedOut = (outPool != null ? outPool.getLifetimeProcessed() : 0);

        int live = 0;
        int maxLength = 1;
        for (int i = 0; i < tunnels.size(); i++) {
            TunnelInfo info = tunnels.get(i);
            int length = info.getLength();
            if (length > maxLength) {maxLength = length;}
        }
        StringBuilder buf = new StringBuilder(32*1024);
        if (!tunnels.isEmpty()) {
            appendTableHeader(buf, maxLength);
        }
        final String tib = _t("Inbound");
        final String tob = _t("Outbound");
        final String localHopTip = _t("Locally hosted tunnel");
        final String localLabel = _t("Local");
        final String tunnelIdTip = _t("Tunnel identity");
        boolean stream = tunnels.size() > MAX_BEFORE_STREAMING;
        if (stream) {
            flushBuf(out, buf);
        }
        int rowsSinceFlush = 0;
        long now = _context.clock().now();
        for (int i = 0; i < tunnels.size(); i++) {
            TunnelInfo info = tunnels.get(i);
            long timeLeft = info.getExpiration()-now;
            if (timeLeft <= 0) {continue;} // don't display tunnels in their grace period
            live++;
            int count = renderTunnelRow(buf, info, timeLeft, maxLength, tib, tob, localHopTip, localLabel, tunnelIdTip);
            if (info.isInbound()) {processedIn += count;}
            else {processedOut += count;}
            if (stream && ++rowsSinceFlush >= STREAM_BATCH) {
                flushBuf(out, buf);
                rowsSinceFlush = 0;
            }
        }

        if (live > 0) {
            int colCount = 5 + maxLength;
            buf.append("<tfoot id=statusnotes>")
               .append("<tr class=bwUsage><td colspan=").append(colCount)
               .append(" class=center><b>").append(_t("Lifetime bandwidth usage")).append(":&nbsp;&nbsp;")
               .append(DataHelper.formatSize2(processedIn*1024, true).replace("i", ""))
               .append("B ").append(_t("in")).append(", ")
               .append(DataHelper.formatSize2(processedOut*1024, true).replace("i", ""))
               .append("B ").append(_t("out")).append("</b></td></tr></tfoot>\n");
        }

        buf.append("</table>\n");
        flushBuf(out, buf);
    }

    /**
     *  Append the tunnel table header row: In/Out, Status, Expiry, Latency,
     *  Data, Gateway, Participants, and Endpoint columns. The Participants
     *  column spans the longest tunnel minus the first and last hops.
     *
     *  @since 0.9.70+
     */
    private void appendTableHeader(StringBuilder buf, int maxLength) {
        buf.append("<table class=\"tunneldisplay tunnels_client\">\n<tr><th title=\"")
           .append(_t("Inbound or outbound?"))
           .append("\">")
           .append(_t("In/Out"))
           .append("</th><th class=status title=\"")
           .append(_t("Tunnel test status"))
           .append("\">")
           .append(_t("Status"))
           .append("</th><th>")
           .append(_t("Expiry"))
           .append("</th><th class=latency title=\"")
           .append(_t("Latency"))
           .append("\">")
           .append(_t("Latency"))
           .append("</th><th title=\"")
           .append(_t("Data transferred"))
           .append("\">")
           .append(_t("Data"))
           .append("</th><th>")
           .append(_t("Gateway"))
           .append("</th>");
        if (maxLength > 3) {
            buf.append("<th colspan=\"")
               .append(maxLength - 2)
               .append("\">")
               .append(_t("Participants"))
               .append("</th>");
        } else if (maxLength == 3) {buf.append("<th>").append(_t("Participant")).append("</th>");}
        if (maxLength > 1) {buf.append("<th>").append(_t("Endpoint")).append("</th>");}
        buf.append("</tr>\n");
    }

    /**
     *  Append one tunnel row: direction badge, test status, expiry bar,
     *  latency, data transferred, and the peer cells, terminated with the
     *  row and table body closers.
     *
     *  @return the processed message count, for the bandwidth footer
     *  @since 0.9.70+
     */
    private int renderTunnelRow(StringBuilder buf, TunnelInfo info, long timeLeft, int maxLength,
                                String tib, String tob, String localHopTip, String localLabel, String tunnelIdTip) {
        TunnelTestStatus testStatus = info.getTestStatus();
        boolean isInbound = info.isInbound();
        boolean isFailed = (testStatus == TunnelTestStatus.FAILED ||
                            testStatus == TunnelTestStatus.TOO_SLOW ||
                            testStatus == TunnelTestStatus.OVER_BUDGET);
        boolean isFailing = (testStatus == TunnelTestStatus.FAILING);
        boolean isGood = (testStatus == TunnelTestStatus.GOOD);
        boolean isTesting = (testStatus == TunnelTestStatus.TESTING);
        String rowClass = isFailed ? " class=failed" :
                          isFailing ? " class=failing" :
                          isGood ? " class=good" :
                          isTesting ? " class=testing" :
                          " class=untested";
        if (isInbound) {
            buf.append("<tr").append(rowClass).append("><td class=direction data-sort=in><span class=inbound title=\"")
               .append(tib)
               .append("\"><img src=/themes/console/images/inbound.svg alt=\"")
               .append(tib)
               .append("\"></span></td>");
        } else {
            buf.append("<tr").append(rowClass).append("><td class=direction data-sort=out><span class=outbound title=\"")
               .append(tob)
               .append("\"><img src=/themes/console/images/outbound.svg alt=\"")
               .append(tob)
               .append("\"></span></td>");
        }
        renderTestStatus(buf, info);
        buf.append("<td class=expiry>").append(renderExpiryBar(timeLeft)).append("</td>");

        int latency = info.getLastLatency();
        buf.append("<td class=latency data-sort=").append(latency).append(">");
        if (latency >= 0) {
            buf.append("<span>").append(latency).append("</span><span class=left>&#8239;ms</span>");
        }
        buf.append("</td>");

        int count = info.getProcessedMessagesCount() * 1024 / 1000;
        double sizeInKB = count * 1024.0 / 1000.0;
        double sizeInMB = sizeInKB / 1024.0;
        buf.append("<td class=data data-sort=").append(count).append(">");
        if (count > 0) {
            buf.append("<span class=right>")
               .append(sizeInKB >= 1024 ? fmt(sizeInMB) : fmt0(sizeInKB))
               .append("</span><span class=left>&#8239;")
               .append(sizeInKB >= 1024 ? "MB" : "KB")
               .append("</span>");
        }
        buf.append("</td>");
        int length = info.getLength();
        boolean isAdvanced = _context.getBooleanProperty(HelperBase.PROP_ADVANCED);
        appendPeerCells(buf, info, length, maxLength, isAdvanced, localHopTip, localLabel, tunnelIdTip);
        buf.append("</tr>\n</tbody>\n");
        return count;
    }

    /**
     *  Append the peer cells for one tunnel: the local hop renders as a
     *  "Local" badge with the tunnel id when advanced mode is on, other hops
     *  render as netdb links with their tunnel ids. Zero-hop and short tunnels
     *  are padded with empty cells to align the table columns.
     *
     *  @since 0.9.70+
     */
    private void appendPeerCells(StringBuilder buf, TunnelInfo info, int length, int maxLength,
                                 boolean isAdvanced, String localHopTip, String localLabel, String tunnelIdTip) {
        boolean isInbound = info.isInbound();
        for (int j = 0; j < length; j++) {
            Hash peer = info.getPeer(j);
            TunnelId id = (info.isInbound() ? info.getReceiveTunnelId(j) : info.getSendTunnelId(j));
            if (_context.routerHash().equals(peer)) {
                if (length < maxLength && length == 1 && isInbound) {
                    for (int k = 1; k < maxLength; k++) { // pad before inbound zero hop
                        buf.append("<td></td>");
                    }
                }
                buf.append(" <td><span class=\"tunnel_peer tunnel_local\" title=\"")
                   .append(localHopTip).append("\">").append(localLabel).append("</span>");
                if (isAdvanced) {
                    buf.append("<span class=tunnel_id title=\"").append(tunnelIdTip).append("\">")
                       .append((id == null ? "" : "" + id)).append("</span>");
                }
                buf.append("</td>");
            } else {
                buf.append(" <td><div class=tunnel_peer>").append(netDbLink(peer)).append("</div>");
                if (isAdvanced) {
                    buf.append("<span class=tunnel_id title=\"").append(tunnelIdTip).append("\">")
                       .append((id == null ? "" : " " + id)).append("</span>");
                }
                buf.append("</td>");
            }
            if (length < maxLength && ((length == 1 && !isInbound) || j == length - 2)) {
                // pad out outbound zero hop; non-zero-hop pads in middle
                for (int k = length; k < maxLength; k++) {buf.append("<td></td>");}
            }
        }
    }

    /**
     *  Write the buffered HTML to the output writer and clear the buffer,
     *  used to flush large tables in batches so the page paints progressively.
     *
     *  @since 0.9.70+
     */
    private void flushBuf(Writer out, StringBuilder buf) throws IOException {
        out.append(buf);
        out.flush();
        buf.setLength(0);
    }

    /**
     * Append the tunnel test status cell, with the status-specific tooltip.
     *
     * @since 0.9.70+
     */
    private void renderTestStatus(StringBuilder buf, TunnelInfo info) {
        TunnelTestStatus testStatus = info.getTestStatus();
        buf.append("<td class=status>");
        switch (testStatus) {
            case GOOD:
                buf.append("<span class=ok title=\"").append(_t("Test successful")).append("\"></span>");
                break;
            case TESTING:
                buf.append("<span class=testing title=\"").append(_t("Test in progress")).append("\"></span>");
                break;
            case FAILING:
                int fails = info.getConsecutiveFailures();
                buf.append("<span class=failing title=\"").append(_t("Test failing (failures: {0})", fails)).append("\"></span>");
                break;
            case FAILED:
                buf.append("<span class=failed title=\"").append(_t("Test failed (3 consecutive failures)")).append("\"></span>");
                break;
            case TOO_SLOW:
                buf.append("<span class=failed title=\"").append(_t("Tunnel too slow - scheduled for early expiry")).append("\"></span>");
                break;
            case OVER_BUDGET:
                buf.append("<span class=failed title=\"").append(_t("Pool over budget - scheduled for early expiry")).append("\"></span>");
                break;
            default:
                buf.append("<span class=untested title=\"").append(_t("Not yet tested")).append("\"></span>");
                break;
        }
        buf.append("</td>");
    }

    /**
     * renderLifetimeBandwidth.
     */
    public void renderLifetimeBandwidth(Writer out, TunnelPool in, TunnelPool outPool) throws IOException {
        Comparator<TunnelInfo> comp = new TunnelInfoComparator();
        List<TunnelInfo> tunnels;
        if (in == null) {tunnels = new ArrayList<>();}
        else {
            tunnels = in.listTunnels();
            Collections.sort(tunnels, comp);
        }
        if (outPool != null) {
            List<TunnelInfo> otunnels = outPool.listTunnels();
            Collections.sort(otunnels, comp);
            tunnels.addAll(otunnels);
        }
        int maxLength = 1;
        for (int i = 0; i < tunnels.size(); i++) {
            TunnelInfo info = tunnels.get(i);
            int length = info.getLength();
            if (length > maxLength) {maxLength = length;}
        }
        StringBuilder buf = new StringBuilder(32*1024);
        if (!tunnels.isEmpty()) {
            buf.append("<table id=tunnelbandwidth><tr><th>")
               .append(_t("Tunnel Name")).append("</th><th>").append(_t("Data In"))
               .append("</th><th>").append(_t("Data Out")).append("</th></tr>\n");
        }
        long lifetimeIn = 0;
        long lifetimeOut = 0;
        for (int i = 0; i < tunnels.size(); i++) {
            TunnelInfo info = tunnels.get(i);
            int count = info.getProcessedMessagesCount() * 1024;
            if (info.isInbound()) {lifetimeIn += count;}
            else {lifetimeOut += count;}
            String nickname = getTunnelName(in);
            String tunnelName = nickname != null ? nickname : _t("Unknown");
            buf.append("<tr><td>").append(tunnelName).append("</td><td>")
               .append(DataHelper.formatSize2(lifetimeIn, true)).append("</td><td>")
               .append(DataHelper.formatSize2(lifetimeOut, true)).append("</td></tr>\n");
        }
        buf.append("</table>\n");
        out.append(buf);
        out.flush();
        buf.setLength(0);
    }

    /* duplicate of that in tunnelPoolManager for now */
    /** @return total number of non-fallback expl. + client tunnels */

    private int countTunnelsPerPeer(ObjectCounter<Hash> lc) {
        List<TunnelPool> pools = new ArrayList();
        _context.tunnelManager().listPools(pools);
        int tunnelCount = 0;
        for (TunnelPool tp : pools) {
            for (TunnelInfo info : tp.listTunnels()) {
                if (info.getLength() > 1) {
                    tunnelCount++;
                    for (int j = 0; j < info.getLength(); j++) {
                        Hash peer = info.getPeer(j);
                        if (!_context.routerHash().equals(peer)) {lc.increment(peer);}
                    }
                }
            }
        }
        return tunnelCount;
    }

    /** @return total number of part. tunnels */

    private int countParticipatingPerPeer(ObjectCounter<Hash> pc) {
        List<HopConfig> participating = _context.tunnelDispatcher().listParticipatingTunnels();
        for (HopConfig cfg : participating) {
            Hash from = cfg.getReceiveFrom();
            if (from != null) {pc.increment(from);}
            Hash to = cfg.getSendTo();
            if (to != null) {pc.increment(to);}
        }
        return participating.size();
    }

    private static class CountryComparator implements Comparator<Hash> {
        /**
         * CountryComparator.
         */
        public CountryComparator(CommSystemFacade comm) {this.comm = comm;}
        /**
         * compare.
         */
        @Override
        public int compare(Hash l, Hash r) {
            // get both countries
            String lc = this.comm.getCountry(l);
            String rc = this.comm.getCountry(r);

            // make them non-null
            lc = (lc == null) ? "zzzz" : lc;
            rc = (rc == null) ? "zzzz" : rc;

            // let String handle the rest
            return lc.compareTo(rc);
        }

        private CommSystemFacade comm;
    }

    private String netDbLink(Hash peer) {
        return _context.commSystem().renderPeerHTML(peer, true);
    }

    private String peerFlag(Hash peer) {
        return _context.commSystem().renderPeerFlag(peer);
    }

    /**
     *  Append the identity cells shared by the peer tables: country flag,
     *  router hash link, version with netdb link, tier caps, IP address,
     *  and reverse-lookup domain. The callers append the data cells and
     *  the row terminator.
     *
     *  @param sb target buffer
     *  @param h peer hash
     *  @param hB64 base64 of the peer hash for the netdb link
     *  @param truncHash short hash label
     *  @param version router version, may be null
     *  @param info RouterInfo for the caps cell, may be null
     *  @param ip primary IP address, may be null
     *  @param rl reverse lookup result, may be null
     *  @param versionTip tooltip for the version link
     *  @param banlistedTip tooltip for the banlisted marker
     *  @param unknownLabel fallback label for missing domains
     *  @param isBanned whether the peer is banlisted
     */
    private void appendPeerIdentity(StringBuilder sb, Hash h, String hB64, String truncHash,
                                    String version, RouterInfo info, String ip, ReverseLookupResult rl,
                                    String versionTip, String banlistedTip, String unknownLabel,
                                    boolean isBanned) {
        sb.append("<tr");
        if (_fragmentKeys) {sb.append(" data-key=\"").append(hB64, 0, KEY_LEN).append("\"");}
        sb.append("><td>")
          .append(peerFlag(h))
          .append("</td><td><span class=routerHash><a href=\"netdb?r=")
          .append(hB64)
          .append("\">")
          .append(truncHash)
           .append("</a></span></td><td data-sort=")
           .append(version != null ? DataHelper.stripHTML(version) : "").append(">");
        if (version != null) {
            sb.append("<span class=version title=\"")
              .append(versionTip)
              .append("\"><a href=\"/netdb?v=")
              .append(DataHelper.stripHTML(version))
              .append("\">")
              .append(DataHelper.stripHTML(version))
              .append("</a></span>");
        } else if (isBanned) {
            sb.append("<span class=banlisted title=\"")
              .append(banlistedTip)
              .append("\">???</span>");
        } else {sb.append("<span>???</span>");}
        sb.append("</td><td>");
        if (info != null) {
            sb.append(_context.commSystem().renderPeerCaps(h, false));
        } else {
            sb.append("<table class=\"rid ric\"><tr><td class=rbw>?</td></tr></table>");
        }
        sb.append("</td><td><span class=ipaddress>");
        if (ip != null && !ip.isEmpty() && !"null".equals(ip)) {
            if (ip.contains(":")) {sb.append("<span hidden>[IPv6]</span>");}
            sb.append(ip);
        } else {sb.append("&ndash;");}
        sb.append("</span></td>");
        sb.append("<td>");
        if (rl != null && rl.canonicalHostName != null &&
            !rl.canonicalHostName.isEmpty() && !rl.ip.equals(rl.canonicalHostName)) {
            String display = (rl.whois != null) ? rl.whois : rl.domain;
            if (display == null) {display = unknownLabel;}
            sb.append("<span class=rlookup title=\"").append(DataHelper.escapeHTML(rl.canonicalHostName)).append("\">")
              .append(DataHelper.escapeHTML(display)).append("</span>");
        } else {
            sb.append("&ndash;");
        }
        sb.append("</td>");
    }

    /**
     * Copied from ConfigNetHelper.
     * @return in KBytes per second
     * @since 0.9.32
     */
    private int getShareBandwidth() {
        int irateKBps = _context.bandwidthLimiter().getInboundKBytesPerSecond();
        int orateKBps = _context.bandwidthLimiter().getOutboundKBytesPerSecond();
        double pct = _context.router().getSharePercentage();
        if (irateKBps < 0 || orateKBps < 0) {return ConfigNetHelper.DEFAULT_SHARE_KBPS;}
        return (int) (pct * Math.min(irateKBps, orateKBps));
    }

    /** translate a string */
    private String _t(String s) {return Messages.getString(s, _context);}

    /** translate a string */
    public String _t(String s, Object o) {return Messages.getString(s, o, _context);}

    /** Generate a percentage bar for expiry time */
    private String renderExpiryBar(long timeLeft) {
        if (timeLeft <= 0) {timeLeft = 0;}
        boolean fiveLeft = timeLeft < 5*60*1000;
        boolean threeLeft = timeLeft < 3*60*1000;
        boolean oneLeft = timeLeft < 60*1000;
        String expiry = fiveLeft ? " 5m" : threeLeft ? " 3m" : oneLeft ? " 1m" : "";
        int percent = (int) Math.min(100, timeLeft * 100.0 / 600000);
        String timeStr = DataHelper.formatDuration2(timeLeft);
        return "<span class=\"percentBarOuter" + expiry + "\">" +
               "<span class=percentBarInner style=width:" + percent + "%>" +
               "<span class=percentBarText>" + timeStr + "</span></span></span>";
    }

    /**
     *  Render a summary table for a tunnel pool.
     *  @since 0.9.68+
     */
    private void renderPoolSummary(Writer out, TunnelPool in, TunnelPool outPool, Hash client) throws IOException {
        int inCount = in.getActiveTunnelCount();
        int outCount = outPool != null ? outPool.getActiveTunnelCount() : 0;
        int inWanted = in.getSettings().getQuantity() + in.getSettings().getBackupQuantity();
        int outWanted = outPool != null ? outPool.getSettings().getQuantity() + outPool.getSettings().getBackupQuantity() : 0;
        int inBuilding = in.getInProgressCount() + in.getTestingTunnelCount();
        int outBuilding = outPool != null ? outPool.getInProgressCount() + outPool.getTestingTunnelCount() : 0;

        String sep = " / ";
        boolean buildIn = inBuilding > 0;
        boolean buildOut = outBuilding > 0;

        out.write("<table class=poolsummary>\n");
        out.write("<thead><tr><th></th>");
        out.write("<th class=inCount title=\"" + _t("Inbound") + ": " + _t("Active / Configured") + "\">" +
                   inCount + sep + inWanted + "</th>");
        out.write("<th class=outCount title=\"" + _t("Outbound") + ": " + _t("Active / Configured") + "\">" +
                   outCount + sep + outWanted + "</th><th id=sep></th>");
        out.write("<th class=\"inBuild" + (buildIn ? " building" : "") + "\" title=\"" + _t("Inbound") +
                  ": " + _t("Building") + "&hellip;\">" + inBuilding + "</th>");
        out.write("<th class=\"outBuild" + (buildOut ? " building" : "") + "\"  title=\"" + _t("Outbound") +
                  ": " + _t("Building") + "&hellip;\">" + outBuilding + "</th>");
        out.write("</tr></thead>\n</table>\n");
    }

    /** Get next tunnel expiry from pool (excludes failed tunnels) */
    private long getNextExpiry(TunnelPool pool) {
        long next = 0;
        for (TunnelInfo ti : pool.listTunnels()) {
            // Skip failed tunnels - they should not affect expiry sorting
            if (ti.getTunnelFailed() ||
                ti.getTestStatus() == TunnelTestStatus.FAILED ||
                ti.getConsecutiveFailures() > 1) {
                continue;
            }
            long exp = ti.getExpiration();
            if (next == 0 || exp < next) {
                next = exp;
            }
        }
        return next;
    }

}
