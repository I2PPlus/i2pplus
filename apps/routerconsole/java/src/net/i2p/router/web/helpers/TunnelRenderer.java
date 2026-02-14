package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
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
import net.i2p.router.transport.CommSystemFacadeImpl;
import net.i2p.router.transport.TransportImpl;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.pool.TunnelPool;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.Messages;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.util.Addresses;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.util.ObjectCounterUnsafe;

/**
 *  For /tunnels.jsp, used by TunnelHelper.
 */
class TunnelRenderer {
    private final RouterContext _context;
    private final Log _log;

    /**
     *  A bounded LRU cache extending LinkedHashMap with computeIfAbsent support.
     */
    private static class BoundedCache<K, V> extends LinkedHashMap<K, V> {
        private final int _maxSize;

        public BoundedCache(int maxSize) {
            super(maxSize, 0.75f, true);
            _maxSize = maxSize;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > _maxSize;
        }

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
    private int displayed;
    private final DecimalFormat fmt = new DecimalFormat("#0.00");

    private static final String PROP_ENABLE_REVERSE_LOOKUPS = "routerconsole.enableReverseLookups";
    public boolean enableReverseLookups() {
        return _context.getBooleanProperty(PROP_ENABLE_REVERSE_LOOKUPS);
    }

    public TunnelRenderer(RouterContext ctx) {
        _context = ctx;
        _log = _context.logManager().getLog(TunnelRenderer.class);
    }

    private final BoundedCache<Hash, RouterInfo> routerInfoCache = new BoundedCache<>(5000);
    private final BoundedCache<Hash, ReverseLookupResult> reverseLookupResults = new BoundedCache<>(5000);
    private final BoundedCache<Hash, String> peerToIP = new BoundedCache<>(5000);

    public void renderStatusHTML(Writer out) throws IOException {
        boolean isAdvanced = _context.getBooleanProperty(HelperBase.PROP_ADVANCED);
        TunnelManagerFacade tm = _context.tunnelManager();
        TunnelPool ei = tm.getInboundExploratoryPool();
        TunnelPool eo = tm.getOutboundExploratoryPool();
        out.write("<div class=tablewrap>\n<h3 class=tabletitle id=exploratory>" + _t("Exploratory"));
        // links are set to float:right in CSS so they will be displayed in reverse order
        out.write(" <a href=\"/configtunnels#exploratory\" title=\"" +
                  _t("Configure tunnels") + "\">[" + _t("configure") + "]</a>");
        writeGraphLinks(out, ei, eo);
        out.write(" <a class=lsview style=pointer-events:none><span class=b32>EXPL</span></a>");
        out.write("</h3>\n");
        renderPool(out, ei, eo);
        // add empty span so we can link to client tunnels in the sidebar
        out.write("<span id=client_tunnels></span>");

        Map<Hash, TunnelPool> clientInboundPools = tm.getInboundClientPools();
        // display name to in pool
        List<TunnelPool> sorted = new ArrayList<TunnelPool>(clientInboundPools.values());
        if (sorted.size() > 1)
            DataHelper.sort(sorted, new TPComparator());
        for (TunnelPool in : sorted) {
            Hash client = in.getSettings().getDestination();
            boolean isLocal = _context.clientManager().isLocal(client);
            if ((!isLocal) && (!isAdvanced))
                continue;
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
                out.write("<div class=tablewrap><h3 class=\"");
                if (_context.clientManager().shouldPublishLeaseSet(client)) {
                    out.write("server ");
                    if (getTunnelName(in).equals(_t("I2PSnark"))) {out.write("snark ");}
                    else if (getTunnelName(in).toLowerCase().equals("messenger") ||
                             getTunnelName(in).toLowerCase().equals("i2pchat")) {
                        out.write("i2pchat ");
                    }
                }
                else if ((getTunnelName(in).startsWith("Ping") && getTunnelName(in).contains("[")) || getTunnelName(in).equals("I2Ping")) {
                    continue;
                } else {out.write("client ");}
                out.write("tabletitle\" ");
                out.write("id=\"" + client.toBase64().substring(0,4) + "\">");
                out.write(getTunnelName(in));
                // links are set to float:right in CSS so they will be displayed in reverse order
                if (isAdvanced) {
                    out.write(" <a href=\"/configtunnels#" + b64 +"\" title=\"" +
                              _t("Configure tunnels for session") + "\">[" + _t("configure") + "]</a>");
                } else {
                    out.write(" <a href=\"/tunnelmanager\" title=\"" +
                              _t("Configure tunnels") + "\">[" + _t("configure") + "]</a>");
                }
                writeGraphLinks(out, in, outPool);
                out.write(" <a class=lsview href=\"/netdb?l=3#ls_" + client.toBase32().substring(0,4) + "\">" +
                          "<span class=b32 title=\"" + _t("View LeaseSet") + "\">" +
                          client.toBase32().substring(0,4) + "</span></a>");
                out.write("</h3>\n");

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
            }
        }
    }

    public void renderParticipating(Writer out, boolean bySpeed) throws IOException {
        boolean isAdvanced = _context.getBooleanProperty(HelperBase.PROP_ADVANCED);
        List<HopConfig> participating = _context.tunnelDispatcher().listParticipatingTunnels();
        StringBuilder sb = new StringBuilder(Math.max(32*1024, displayed*1024));
        boolean hasTransit = !participating.isEmpty();
        if (hasTransit) {
            sb.append("<div class=tablewrap><h3 class=tabletitle id=participating>");
            if (bySpeed) {sb.append(_t("Fastest Active Transit Tunnels"));}
            else {sb.append(_t("Most Recent Active Transit Tunnels"));}
            sb.append("&nbsp;&nbsp;<a id=refreshPage class=refreshpage style=float:right href=/transit>")
              .append(_t("Refresh")).append("</a></h3>\n");
            int bwShare = getShareBandwidth();
            if (bwShare > 12) {
                sb.append("<table id=allTransit class=\"tunneldisplay tunnels_participating\">\n<thead><tr data-sort-method=thead><th>")
                  .append(_t("Role")).append("</th><th");
                if (!bySpeed) {sb.append(" data-sort-default");}
                sb.append(" data-sort-method=number>")
                  .append(_t("Expiry"))
                  .append("</th><th title=\"")
                  .append(_t("Data transferred"))
                  .append("\" data-sort-method=number>")
                  .append(_t("Data"))
                  .append("</th><th");
                if (bySpeed) {sb.append(" data-sort-default");}
                sb.append(" data-sort-method=number>").append(_t("Speed")).append("</th>");
                if (isAdvanced) {
                  //sb.append("<th class=limit data-sort-method=number>").append(_t("Limit")).append("</th>");
                  sb.append("<th data-sort-method=number>")
                    .append(_t("Receive on"))
                    .append("</th>");
                }
                sb.append("<th data-sort-method=number>")
                  .append(_t("From"))
                  .append("</th>");
                if (isAdvanced) {sb.append("<th>").append(_t("Send on")).append("</th>");}
                sb.append("<th data-sort-method=number>")
                  .append(_t("To"))
                  .append("</th></tr>\n</thead>\n<tbody id=transitPeers>\n");
                long processed = 0;
                RateStat rs = _context.statManager().getRate("tunnel.participatingMessageCount");
                if (rs != null) {processed = (long)rs.getRate(RateConstants.TEN_MINUTES).getLifetimeTotalValue();}
                int inactive = 0;
                displayed = 0;
                if (bySpeed) {DataHelper.sort(participating, new TunnelComparatorBySpeed());}
                else {DataHelper.sort(participating, new TunnelComparator());}
                for (int i = 0; i < participating.size(); i++) {
                    HopConfig cfg = participating.get(i);
                    long count = cfg.getProcessedMessagesCount();
                    if (count <= 0) {
                        inactive++;
                        continue;
                    }
                    Hash to = cfg.getSendTo();
                    Hash from = cfg.getReceiveFrom();
                    // everything that isn't 'recent' is already in the tunnel.participatingMessageCount stat
                    processed += cfg.getRecentMessagesCount();
                    if (++displayed > DISPLAY_LIMIT) {continue;}
                    sb.append("<tr class=lazy>");
                    if (to == null) {
                        sb.append("<td class=\"cells obep\" title=\"").append(_t("Outbound Endpoint")).append("\">")
                          .append(_t("Outbound Endpoint")).append("</td>");
                    } else if (from == null) {
                        sb.append("<td class=\"cells ibgw\" title=\"").append(_t("Inbound Gateway"))
                          .append("\">").append(_t("Inbound Gateway")).append("</td>");
                    } else {
                        sb.append("<td class=\"cells ptcp\" title=\"").append(_t("Participant"))
                          .append("\">").append(_t("Participant")).append("</td>");
                    }
                    long timeLeft = cfg.getExpiration()-_context.clock().now();
                    sb.append("<td class=\"cells expiry\" data-sort=").append(timeLeft).append(">");
                    if (timeLeft > 0) {
                      sb.append("<span class=right>")
                        .append(timeLeft / 1000)
                        .append("</span><span class=left>&#8239;")
                        .append(_t("sec"))
                        .append("</span>");
                    } else {
                        sb.append("<i>").append(_t("grace period")).append("</i>");
                    }
                    sb.append("</td>");

                    double sizeInKB = count * 1024.0 / 1000.0;
                    double sizeInMB = sizeInKB / 1024.0;
                    sb.append("<td class=\"cells datatransfer\" data-sort=")
                      .append(count).append("><span class=right>")
                      .append(sizeInKB >= 1024 ? String.format("%.2f", sizeInMB) : String.format("%.0f", sizeInKB))
                      .append("</span><span class=left>&#8239;")
                      .append(sizeInKB >= 1024 ? "MB" : "KB")
                      .append("</span></td>");

                    int lifetime = (int) ((_context.clock().now() - cfg.getCreation()) / 1000);
                    if (lifetime <= 0) {lifetime = 1;}
                    else if (lifetime > 10*60) {lifetime = 10*60;}
                    float bps = 1024 * count / lifetime;
                    float kbps = bps / 1024;
                    sb.append("<td class=\"cells bps\" data-sort=").append(bps).append("><span class=right>")
                      .append(fmt.format(kbps)).append("&#8239;</span><span class=left>KB/s</span></td>");

                    long recv = cfg.getReceiveTunnelId();
                    if (isAdvanced) {
                        //sb.append("<td class=\"cells limit\" data-sort=").append(cfg.getAllocatedBW()).append(">");
                        //if (cfg.getAllocatedBW() > 0) {
                        //    sb.append("<span>").append(DataHelper.formatSize2Decimal(cfg.getAllocatedBW())).append("B/s").append("</span>");
                        //}
                        //sb.append("</td>");
                        if (recv != 0) {
                            sb.append("<td title=\"").append(_t("Tunnel identity")).append("\"><span class=tunnel_id>")
                              .append(recv).append("</span></td>");
                        } else {sb.append("<td><span hidden>&ndash;</span></td>");}
                    }
                    if (from != null) {sb.append("<td><div class=tunnel_peer>").append(netDbLink(from)).append("</div></td>");}
                    else {sb.append("<td><span hidden>&ndash;</span></td>");}
                    long send = cfg.getSendTunnelId();
                    if (isAdvanced) {
                        if (send != 0) {
                            sb.append("<td title=\"").append(_t("Tunnel identity")).append("\"><span class=tunnel_id>")
                              .append(send).append("</span></td>");
                        } else {
                            sb.append("<td><span hidden>&ndash;</span></td>");
                        }
                    }
                    if (to != null) {sb.append("<td><div class=tunnel_peer>").append(netDbLink(to)).append("</div></td>");}
                    else {sb.append("<td><span hidden>&ndash;</span></td>");}
                    sb.append("</tr>\n");
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
                  .append("</td></tr></tfoot>\n</table></div>\n");
            } else { // bwShare < 12K/s
                sb.append("<div class=\"statusnotes noparticipate\"><b>")
                  .append(_t("Not enough shared bandwidth to build transit tunnels.")).append("</b> <a href=\"config\">[")
                  .append(_t("Configure")).append("]</a></div>\n");
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

    private final BoundedCache<String, String> reverseLookupCache = new BoundedCache<>(1000);

    public void renderTransitSummary(Writer out) throws IOException {
        List<HopConfig> participating = _context.tunnelDispatcher().listParticipatingTunnels();
        if (!participating.isEmpty() && participating.size() > 1) {
            // Counters for tunnels, bandwidth, and messages by peer
            ObjectCounterUnsafe<Hash> counts = new ObjectCounterUnsafe<>();
            ObjectCounterUnsafe<Hash> bws = new ObjectCounterUnsafe<>();
            ObjectCounterUnsafe<Hash> msgs = new ObjectCounterUnsafe<>();
            for (HopConfig cfg : participating) {
                Hash from = cfg.getReceiveFrom();
                Hash to = cfg.getSendTo();
                long msgsCount = cfg.getProcessedMessagesCount();
                if (from != null) {
                    counts.increment(from);
                    if (msgsCount > 0) bws.add(from, (int) msgsCount);
                    msgs.add(from, (int) msgsCount);
                }
                if (to != null) {
                    counts.increment(to);
                    if (msgsCount > 0) bws.add(to, (int) msgsCount);
                    msgs.add(to, (int) msgsCount);
                }
            }

            StringBuilder tbuf = new StringBuilder(3 * 512);
            tbuf.append("<div class=tablewrap>\n<h3 class=tabletitle>")
                .append(_t("Transit Tunnels by Peer (Top {0})", DISPLAY_LIMIT))
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
                .append("</th>");
            if (enableReverseLookups()) {
                tbuf.append("<th id=domain>").append(_t("Domain")).append("</th>");
            }
            tbuf.append("<th class=tcount data-sort-method=number data-sort-default>")
                .append(_t("Tunnels"))
                .append("</th><th id=data data-sort-method=number>")
                .append(_t("Data"))
                .append("</th><th id=msgs data-sort-method=number>")
                .append(_t("Messages"))
                .append("</th><th id=edit data-sort-method=none>")
                .append(_t("Edit"))
                .append("</th></tr></thead>\n<tbody id=transitPeers>\n");
            out.write(tbuf.toString());

            int displayed = 0;
            List<Hash> sorted = counts.sortedObjects();
            long uptime = _context.router().getUptime();
            int bannedCount = 0;
            StringBuilder sb = new StringBuilder(4 * 512);

            for (Hash h : sorted) {
                int count = counts.count(h);

                // Defensive - skip if count <= 0 (unlikely due to sorted list but just in case)
                if (count <= 0) continue;

                if (++displayed > DISPLAY_LIMIT) break;

                //RouterInfo info = _context.netDb().lookupRouterInfoLocally(h);
                RouterInfo info = routerInfoCache.computeIfAbsent(h, hash -> (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(hash));

                String truncHash = h.toBase64().substring(0,4);

                String ip = peerToIP.get(h);
                if (ip == null) {
                    byte[] direct = TransportImpl.getIP(h);
                    String directIP = (direct != null) ? Addresses.toString(direct) : "";
                    ip = !directIP.isEmpty() ? directIP : (info != null ? Addresses.toString(CommSystemFacadeImpl.getCompatibleIP(info)) : null);
                    if (ip != null) {
                        peerToIP.put(h, ip);
                    }
                }

                String version = (info != null) ? info.getOption("router.version") : null;
                ReverseLookupResult rlResult = getReverseLookupInfo(h, info, uptime);
                boolean isBanned = _context.banlist().isBanlisted(h) ||
                                   _context.banlist().isBanlistedHostile(h);

                sb.append("<tr class=lazy><td>")
                  .append(peerFlag(h))
                  .append("</td><td><span class=routerHash><a href=\"netdb?r=")
                  .append(h.toBase64())
                  .append("\">")
                  .append(truncHash)
                  .append("</a></span></td><td data-sort=")
                  .append(version != null ? DataHelper.stripHTML(version) : "0").append(">");

                if (version != null) {
                    sb.append("<span class=version title=\"")
                      .append(_t("Show all routers with this version in the NetDb"))
                      .append("\"><a href=\"/netdb?v=")
                      .append(DataHelper.stripHTML(version))
                      .append("\">")
                      .append(DataHelper.stripHTML(version))
                      .append("</a></span>");
                } else if (isBanned) {
                    sb.append("<span class=banlisted title=\"")
                      .append(_t("Router is banlisted"))
                      .append("\">???</span>");
                } else {sb.append("<span>???</span>");}
                sb.append("</td><td>");
                if (info != null) {
                    sb.append(_context.commSystem().renderPeerCaps(h, false));
                } else {
                    sb.append("<table class=\"rid ric\"><tr><td class=rbw>?</td></tr></table>");
                }
                sb.append("</td><td><span class=ipaddress>");
                if (ip != null && !"null".equals(ip)) {
                    if (ip.contains(":")) sb.append("<span hidden>[IPv6]</span>");
                    sb.append(ip);
                } else {sb.append("&ndash;");}
                sb.append("</span></td>");

                if (enableReverseLookups()) {
                    sb.append("<td>");
                    if (rlResult != null && rlResult.canonicalHostName != null &&
                        !rlResult.canonicalHostName.isEmpty() && !rlResult.ip.equals(rlResult.canonicalHostName)) {
                        String display = (rlResult.whois != null) ? rlResult.whois : rlResult.domain;
                        if (display == null) display = _t("unknown");
                        sb.append("<span class=rlookup title=\"").append(rlResult.canonicalHostName).append("\">")
                          .append(display).append("</span>");
                    } else {
                        sb.append("<span>").append(_t("unknown")).append("</span>");
                    }
                    sb.append("</td>");
                }

                sb.append("<td class=tcount>").append(count).append("</td>");

                long bw = bws.count(h);
                sb.append("<td data-sort=").append(bw).append(">");
                if (bw > 0) {
                    sb.append("<span class=data>").append(fmt.format(bw).replace(".00", "")).append("KB</span>");
                } else {sb.append("<span class=data hidden>0KB</span>");}
                sb.append("</td>");

                long msgCount = msgs.count(h);
                sb.append("<td data-sort=").append(msgCount).append(">");
                if (msgCount > 0) {
                    sb.append("<span class=msgs>").append(msgCount).append("</span>");
                } else {sb.append("<span class=msgs>0</span>");}
                sb.append("</td>");

                sb.append("<td class=isBanned hidden>");
                if (isBanned) {
                    sb.append("<span hidden>ban</span><a class=banlisted href=\"/profiles?f=3\" title=\"")
                      .append(_t("Router is banlisted")).append("\">Banned</a> ");
                    bannedCount++;
                }
                sb.append("</td>");

                sb.append("<td>");
                if (info != null && info.getHash() != null) {
                    sb.append("<a class=configpeer href=\"/configpeer?peer=")
                      .append(info.getHash())
                      .append("\" title=\"Configure peer\">")
                      .append(_t("Edit"))
                      .append("</a>");
                }
                sb.append("</td></tr>\n");
            }

            sb.append("</tbody>\n</table>\n</div>");
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

    public void renderPeers(Writer out) throws IOException {
        long uptime = _context.router().getUptime();
        final boolean doReverseLookups = enableReverseLookups();

        // Count tunnels per peer local and transit
        ObjectCounter<Hash> localCount = new ObjectCounter<>();
        int tunnelCount = countTunnelsPerPeer(localCount);
        ObjectCounter<Hash> transitCount = new ObjectCounter<>();
        int partCount = countParticipatingPerPeer(transitCount);

        Set<Hash> peers = new HashSet<>();
        peers.addAll(localCount.objects());
        peers.addAll(transitCount.objects());
        List<Hash> peerList = new ArrayList<>(peers);
        Collections.sort(peerList, new CountryComparator(_context.commSystem()));

        if (!peerList.isEmpty() && (tunnelCount > 0 || partCount > 0)) {
            StringBuilder headerSb = new StringBuilder(peerList.size() * 640 + 2048);
            headerSb.append("<div class=tablewrap>\n<h3 class=tabletitle id=peercount>")
                  .append(_t("All Tunnels by Peer"))
                  .append("&nbsp;&nbsp;<a id=refreshPage class=refreshpage style=float:right href=/tunnelpeercount>")
                  .append(_t("Refresh"))
                  .append("</a></h3>\n<table id=tunnelPeerCount><thead class=lazy>\n<tr><th id=country data-sort-direction=ascending>")
                  .append(_t("Country")).append("</th><th id=router>")
                  .append(_t("Router")).append("</th><th id=version>")
                  .append(_t("Version")).append("</th><th id=tier data-sort=LMNOPX>")
                  .append(_t("Tier")).append("</th><th id=address title=\"")
                  .append(_t("Primary IP address"))
                  .append("\">").append(_t("Address")).append("</th>");
            if (doReverseLookups) {
                headerSb.append("<th id=domain>").append(_t("Domain")).append("</th>");
            }
            headerSb.append("<th class=tcount colspan=2 title=\"Client and Exploratory Tunnels\" data-sort-method=number data-sort-column-key=localCount>")
                  .append(_t("Local"))
                  .append("</th>");
            if (partCount > 0) {
                headerSb.append("<th class=tcount colspan=2 data-sort-method=number data-sort-column-key=transitCount>")
                      .append(_t("Transit"))
                      .append("</th>");
            } else {
                headerSb.append("<th></th>");
            }
            headerSb.append("<th id=edit data-sort-method=none>")
                  .append(_t("Edit"))
                  .append("</th></tr>\n</thead>\n<tbody id=allPeers>\n");
            out.write(headerSb.toString());
            out.flush();

            List<Hash> validPeerList = new ArrayList<>(peerList.size());
            for (Hash h : peerList) {
                RouterInfo info = routerInfoCache.computeIfAbsent(h, hash -> (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(hash));
                if (info == null) continue;
                validPeerList.add(h);
                byte[] direct = TransportImpl.getIP(h);
                String directIP = (direct != null) ? Addresses.toString(direct) : "";
                String ip = !directIP.isEmpty() ? directIP : Addresses.toString(CommSystemFacadeImpl.getCompatibleIP(info));
                peerToIP.put(h, ip);

                if (doReverseLookups) {
                    ReverseLookupResult rlr = getReverseLookupInfo(h, info, uptime);
                    reverseLookupResults.put(h, rlr);
                }
            }

            final int chunkSize = 50;
            for (int start = 0; start < validPeerList.size(); start += chunkSize) {
                int end = Math.min(start + chunkSize, validPeerList.size());

                StringBuilder chunkSb = new StringBuilder();
                for (int i = start; i < end; i++) {
                    Hash h = validPeerList.get(i);
                    RouterInfo info = routerInfoCache.get(h);

                    int localTunnelCount = localCount.count(h);
                    int transitTunnelCount = transitCount.count(h);
                    String ip = peerToIP.get(h);
                    String version = info.getOption("router.version");
                    String truncHash = h.toBase64().substring(0,4);
                    ReverseLookupResult rlResult = doReverseLookups ? reverseLookupResults.get(h) : null;

                    chunkSb.append("<tr class=lazy><td>")
                           .append(peerFlag(h))
                           .append("</td><td><span class=routerHash><a href=\"netdb?r=")
                           .append(h.toBase64())
                           .append("\">")
                           .append(truncHash)
                           .append("</a></span></td><td data-sort=")
                           .append(version != null ? DataHelper.stripHTML(version) : "0")
                           .append(">");
                    if (version != null) {
                        chunkSb.append("<span class=version title=\"")
                               .append(_t("Show all routers with this version in the NetDb"))
                               .append("\"><a href=\"/netdb?v=")
                               .append(DataHelper.stripHTML(version))
                               .append("\">")
                               .append(DataHelper.stripHTML(version))
                               .append("</a></span>");
                    }
                    chunkSb.append("</td><td>")
                           .append(_context.commSystem().renderPeerCaps(h, false))
                           .append("</td><td><span class=ipaddress>");
                    if (ip != null && !ip.isEmpty()) {
                        if (ip.contains(":")) {chunkSb.append("<span hidden>[IPv6]</span>");}
                        chunkSb.append(ip);
                    } else {chunkSb.append("&ndash;");}
                    chunkSb.append("</span>");

                    if (doReverseLookups) {
                        chunkSb.append("<td>");
                        if (rlResult != null && rlResult.canonicalHostName != null &&
                            !rlResult.canonicalHostName.isEmpty() && !rlResult.ip.equals(rlResult.canonicalHostName)) {
                            String display = (rlResult.whois != null) ? rlResult.whois : rlResult.domain;
                            if (display == null) display = _t("unknown");
                            chunkSb.append("<span class=rlookup title=\"").append(rlResult.canonicalHostName).append("\">")
                                   .append(display).append("</span>");
                        } else {
                            chunkSb.append("&ndash;");
                        }
                        chunkSb.append("</td>");
                    }

                    if (localTunnelCount > 0) {
                        chunkSb.append(String.format(
                                       "<td class=tcount data-sort-column-key=localCount data-sort=%d>%d</td><td class=bar data-sort-column-key=localCount>",
                                       localTunnelCount, localTunnelCount)
                                      );
                        chunkSb.append(String.format(
                                       "<span class=percentBarOuter><span class=percentBarInner style=\"width:%s%%\"><span class=percentBarText>%d%%</span></span></span>",
                                       fmt.format(localTunnelCount * 100.0 / tunnelCount).replace(".00", ""),
                                       localTunnelCount * 100 / tunnelCount));
                    } else {
                        chunkSb.append("<td class=tcount colspan=2 data-sort=0></td>");
                    }
                    chunkSb.append("</td>");
                    if (!peerList.isEmpty()) {
                        if (transitTunnelCount > 0) {
                            chunkSb.append(String.format(
                                "<td class=tcount data-sort-column-key=transitCount data-sort=%d>%d</td><td class=bar data-sort-column-key=transitCount>",
                                transitTunnelCount, transitTunnelCount))
                                   .append(String.format("<span class=percentBarOuter><span class=percentBarInner style=\"width:%s%%\"><span class=percentBarText>%d%%</span></span></span>",
                                           fmt.format(transitTunnelCount * 100.0 / partCount).replace(".00", ""),
                                           transitTunnelCount * 100 / partCount))
                                   .append("</td>");
                        } else {
                             chunkSb.append("<td class=tcount colspan=2 data-sort=0></td>");
                        }
                    } else {
                        chunkSb.append("<td></td>");
                    }
                    chunkSb.append(String.format("<td><a class=configpeer href=\"/configpeer?peer=%s\" title=\"%s\">%s</a></td></tr>\n",
                        info.getHash(),
                        _t("Configure peer"),
                        _t("Edit")));
                }
                out.write(chunkSb.toString());
                out.flush();
            }
            StringBuilder footerSb = new StringBuilder();
            footerSb.append("</tbody>\n<tfoot class=lazy><tr class=tablefooter data-sort-method=none><td colspan=4><b>")
                  .append(validPeerList.size())
                  .append(" ")
                  .append(_t("unique peers"))
                  .append("</b></td><td></td>");
            if (doReverseLookups) {
                footerSb.append("<td></td>");
            }
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
            footerSb.append("<td></td></tr>\n</tfoot>\n</table>\n</div>\n");
            out.write(footerSb.toString());
            out.flush();
        } else {
            out.write("<p class=infohelp>");
            out.write(_t("No local or transit tunnels currently active."));
            out.write("</p>\n");
        }
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
        String ip = !directIP.isEmpty() ? directIP : (info != null ? Addresses.toString(CommSystemFacadeImpl.getCompatibleIP(info)) : null);
        result.ip = ip;

        if (ip != null && enableReverseLookups() && uptime > 30 * 1000) {
            String rl = reverseLookupCache.computeIfAbsent(ip, k -> _context.commSystem().getCanonicalHostName(k));
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
                    .replace("YELCOT TELEPHONE COMPANY (YELCOT)", "YELCOT")
                    .replace("State University of New York at Stony Brook (SUNYASB-Z)", "SUNYASB")
                    .replace("Cloudflare, Inc. (CLOUD14)", "CLOUDFLARE")
                    .replace("DigitalOcean, LLC (DO-13)", "DIGITALOCEAN")
                    .replace("Nortex Communications Company", "NORTEX")
                    .replace("ROOT", _t("PRIVATE IP ADDRESS"))
                    .replace("NON-RIPE-NCC-MANAGED-ADDRESS-BLOCK", "unknown")
                    .replace("unknown", _t("unknown"))
                    .replaceAll("\\(.*?\\)", "")
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
        buf.append("<div class=tablewrap id=tiers>\n<h3 class=tabletitle>")
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
           .append("</td><td></td></tr></tbody>\n</table></div>\n");
        out.append(buf);
        out.flush();
        buf.setLength(0);
    }

    /** @since 0.9.33 */
    static String range(int f, int t) {
        return Math.round(f * 1.024f) + " - " + (Math.round(t * 1.024f) - 1) + " KB/s";
    }

    private static class TunnelComparator implements Comparator<HopConfig>, Serializable {
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
         public int compare(HopConfig l, HopConfig r) {
             long diff = r.getProcessedMessagesCount() - l.getProcessedMessagesCount();
             return diff > 0 ? 1 : diff < 0 ? -1 : 0;
         }
    }

    private static class TunnelInfoComparator implements Comparator<TunnelInfo>, Serializable {
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
         public int compare(TunnelPool l, TunnelPool r) {
             int rv = _comp.compare(getTunnelName(l), getTunnelName(r));
             if (rv != 0) {return rv;}
             return l.getSettings().getDestination().toBase32().compareTo(r.getSettings().getDestination().toBase32());
        }
    }

    /**
     *  Get display name for the tunnel
     *  @since 0.9.57
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

    /** @since 0.9.35 */
    private void writeGraphLinks(Writer out, TunnelPool in, TunnelPool outPool) throws IOException {
        if (in == null || outPool == null) {return;}
        String irname = in.getRateName();
        String orname = outPool.getRateName();
        RateStat irs = _context.statManager().getRate(irname);
        RateStat ors = _context.statManager().getRate(orname);
        if (irs == null || ors == null) {return;}
        Rate ir = irs.getRate(RateConstants.ONE_HOUR);
        Rate or = ors.getRate(RateConstants.ONE_HOUR);
        if (ir == null || or == null) {return;}
        final String tgd = _t("Graph Data");
        final String tcg = _t("Configure Graph Display");
        // links are set to float:right in CSS so they will be displayed in reverse order
        if (or.getSummaryListener() != null) {
            out.write("<a href=\"graph?stat=" + orname + ".300000&amp;w=600&amp;h=200\">" +
                      "<img src=/themes/console/images/outbound.svg alt=\"" + tgd + "\" title=\"" + tgd + "\"></a>");
        } else {
            out.write("<a href=\"configstats#" + orname + "\">" +
                      "<img src=/themes/console/images/outbound.svg alt=\"" + tcg + "\" title=\"" + tcg + "\"></a>");
        }
        if (ir.getSummaryListener() != null) {
            out.write("<a href=\"graph?stat=" + irname + ".300000&amp;w=600&amp;h=200\">" +
                      "<img src=/themes/console/images/inbound.svg alt=\"" + tgd + "\" title=\"" + tgd + "\"></a> ");
        } else {
            out.write("<a href=\"configstats#" + irname + "\">" +
                      "<img src=/themes/console/images/inbound.svg alt=\"" + tcg + "\" title=\"" + tcg + "\"></a>");
        }
    }

    private void renderPool(Writer out, TunnelPool in, TunnelPool outPool) throws IOException {
        Comparator<TunnelInfo> comp = new TunnelInfoComparator();
        List<TunnelInfo> tunnels;
        if (in == null) {tunnels = new ArrayList<TunnelInfo>();}
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
        if (tunnels.size() != 0) {
            buf.append("<table class=\"tunneldisplay tunnels_client\">\n<thead><tr><th class=direction title=\"")
               .append(_t("Inbound or outbound?"))
               .append("\">")
               .append(_t("In/Out"))
               .append("</th><th class=status title=\"")
               .append(_t("Tunnel test status"))
               .append("\">")
                .append(_t("Status"))
                .append("</th><th class=latency title=\"")
                .append(_t("Round trip time for last test"))
                .append("\">")
                .append(_t("Latency"))
                .append("</th><th class=expiry>")
                .append(_t("Expiry"))
               .append("</th><th class=transferred title=\"")
               .append(_t("Data transferred"))
               .append("\">")
               .append(_t("Data"))
               .append("</th><th>")
               .append(_t("Gateway"))
               .append("</th>");
            if (maxLength > 3) {
                buf.append("<th colspan=").append(maxLength - 2).append(">").append(_t("Participants")).append("</th>");
            } else if (maxLength == 3) {buf.append("<th>").append(_t("Participant")).append("</th>");}
            if (maxLength > 1) {buf.append("<th>").append(_t("Endpoint")).append("</th>");}
            buf.append("</tr></thead>\n<tbody>");
        }
        final String tib = _t("Inbound");
        final String tob = _t("Outbound");
        for (int i = 0; i < tunnels.size(); i++) {
            TunnelInfo info = tunnels.get(i);
            long timeLeft = info.getExpiration()-_context.clock().now();
            if (timeLeft <= 0) {continue;} // don't display tunnels in their grace period
            live++;
            boolean isInbound = info.isInbound();

            // Get test status and determine if tunnel has failed
            net.i2p.router.TunnelTestStatus testStatus = info.getTestStatus();
            boolean isFailed = (testStatus == net.i2p.router.TunnelTestStatus.FAILED);
            boolean isFailing = (testStatus == net.i2p.router.TunnelTestStatus.FAILING);
            boolean isGood = (testStatus == net.i2p.router.TunnelTestStatus.GOOD);
            boolean isTesting = (testStatus == net.i2p.router.TunnelTestStatus.TESTING);
            // Check if tunnel was rejected as duplicate @since 0.9.68+
            boolean isDuplicate = (info instanceof net.i2p.router.tunnel.pool.PooledTunnelCreatorConfig) &&
                                  ((net.i2p.router.tunnel.pool.PooledTunnelCreatorConfig) info).isDuplicate();

            // Set row class according to tunnel test status
            String rowClass = isDuplicate ? " class=failed" :
                              isFailed ? " class=failed" :
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

            buf.append("<td class=status>");
            if (isDuplicate) {
                buf.append("<span title=\"").append(_t("Rejected as duplicate")).append("\">");
            } else {
                switch (testStatus) {
                    case GOOD:
                        buf.append("<span title=\"").append(_t("Test successful")).append("\"></span>");
                        break;
                    case TESTING:
                        buf.append("<span title=\"").append(_t("Test in progress")).append("\"></span>");
                        break;
                    case FAILING:
                        buf.append("<span title=\"").append(_t("Test failing (1 failure)")).append("\"></span>");
                        break;
                    case FAILED:
                        buf.append("<span title=\"").append(_t("Test failed (2 consecutive failures)")).append("\"></span>");
                        break;
                    default:
                        buf.append("<span title=\"").append(_t("Not yet tested")).append("\"></span>");
                        break;
                }
                buf.append("</td>");
            }

            int latency = info.getLastLatency();
            buf.append("<td class=latency data-sort=").append(latency).append(">");
            if (latency > 0) {
                buf.append("<span>").append(latency).append("</span><span class=left>&#8239;ms</span>");
            }
            buf.append("</td>");

            buf.append("<td class=expiry><span>").append(DataHelper.formatDuration2(timeLeft)).append("</span></td>");

            long count = info.getProcessedMessagesCount() * 1024 / 1000;
            double sizeInKB = count * 1024.0 / 1000.0;
            double sizeInMB = sizeInKB / 1024.0;
            buf.append("<td class=\"cells transferred\" data-sort=").append(count).append(">");
            if (count > 0) {
                buf.append("<span class=right>")
                   .append(sizeInKB >= 1024 ? String.format("%.2f", sizeInMB) : String.format("%.0f", sizeInKB))
                   .append("</span><span class=left>&#8239;")
                   .append(sizeInKB >= 1024 ? "MB" : "KB")
                   .append("</span>");
            }
            buf.append("</td>");

            int length = info.getLength();
            boolean isAdvanced = _context.getBooleanProperty(HelperBase.PROP_ADVANCED);

            for (int j = 0; j < length; j++) {
                Hash peer = info.getPeer(j);
                TunnelId id = (info.isInbound() ? info.getReceiveTunnelId(j) : info.getSendTunnelId(j));
                char cap = getCapacity(peer);
                if (_context.routerHash().equals(peer)) {
                    if (length < maxLength && length == 1 && isInbound) {
                        for (int k = 1; k < maxLength; k++) { // pad before inbound zero hop
                            buf.append("<td></td>");
                        }
                    }
                    // Add empty content placeholders to force alignment
                    buf.append(" <td><span class=\"tunnel_peer tunnel_local\" title=\"")
                       .append(_t("Locally hosted tunnel")).append("\">").append(_t("Local")).append("</span>");
                    if (isAdvanced) {
                        buf.append("<span class=tunnel_id title=\"").append(_t("Tunnel identity")).append("\">")
                           .append(id == null ? "" : "" + id).append("</span>");
                    }
                    buf.append("</td>");
                } else {
                    buf.append(" <td><div class=tunnel_peer>").append(netDbLink(peer)).append("</div>");
                    if (isAdvanced) {
                        buf.append("<span class=tunnel_id title=\"").append(_t("Tunnel identity")).append("\">")
                           .append(id == null ? "" : " " + id).append("</span>");
                    }
                    buf.append("</td>");
                }
                if (length < maxLength && ((length == 1 && !isInbound) || j == length - 2)) {
                    // pad out outbound zero hop; non-zero-hop pads in middle
                    for (int k = length; k < maxLength; k++) {buf.append("<td></td>");}
                }
            }

            buf.append("</tr>\n");

            if (info.isInbound()) {processedIn += count;}
            else {processedOut += count;}
        }

        if (tunnels.size() != 0) {
            buf.append("</tbody>\n<tfoot id=statusnotes>");
            int colCount = 5 + maxLength;

            if (live > 0 && (in != null || outPool != null)) {
                List<?> pendingIn = (in != null) ? in.listPending() : Collections.emptyList();
                List<?> pendingOut = (outPool != null) ? outPool.listPending() : Collections.emptyList();
                if (!pendingIn.isEmpty() || !pendingOut.isEmpty()) {
                    buf.append("<tr class=building><td colspan=")
                       .append(colCount).append(" class=center><b>")
                       .append(_t("Build in progress")).append(":&nbsp;");
                    if (!pendingIn.isEmpty()) {
                        buf.append("&nbsp;<span class=pending>").append(pendingIn.size())
                           .append(" ").append(tib).append("</span>&nbsp;");
                    }
                    if (!pendingOut.isEmpty()) {
                        buf.append("&nbsp;<span class=pending>").append(pendingOut.size())
                           .append(" ").append(tob).append("</span>&nbsp;");
                    }
                    buf.append("</b></td></tr>\n");
                }
            }

            if (live > 0) {
                buf.append("<tr class=bwUsage><td colspan=").append(colCount)
                   .append(" class=center><b>").append(_t("Lifetime bandwidth usage")).append(":&nbsp;&nbsp;")
                   .append(DataHelper.formatSize2(processedIn*1024, true).replace("i", ""))
                   .append("B ").append(_t("in")).append(", ")
                   .append(DataHelper.formatSize2(processedOut*1024, true).replace("i", ""))
                   .append("B ").append(_t("out")).append("</b></td></tr>\n");
            }
            buf.append("</tfoot>\n</table>\n");
        }

        buf.append("</div>\n");
        out.append(buf);
        out.flush();
        buf.setLength(0);
    }

    public void renderLifetimeBandwidth(Writer out, TunnelPool in, TunnelPool outPool) throws IOException {
        Comparator<TunnelInfo> comp = new TunnelInfoComparator();
        List<TunnelInfo> tunnels;
        if (in == null) {tunnels = new ArrayList<TunnelInfo>();}
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
        if (tunnels.size() != 0) {
            buf.append("<table id=tunnelbandwidth><tr><th>")
               .append(_t("Tunnel Name")).append("</th><th>").append(_t("Data In"))
               .append("</th><th>").append(_t("Data Out")).append("</th></tr>\n");
        }
        long lifetimeIn = 0;
        long lifetimeOut = 0;
        for (int i = 0; i < tunnels.size(); i++) {
            TunnelInfo info = tunnels.get(i);
            long count = info.getProcessedMessagesCount() * 1024;
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
        public CountryComparator(CommSystemFacade comm) {this.comm = comm;}
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


    /** @return cap char or '?' */
    private char getCapacity(Hash peer) {
        RouterInfo info = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(peer);
        if (info != null) {
            String caps = info.getCapabilities();
            for (int i = 0; i < RouterInfo.BW_CAPABILITY_CHARS.length(); i++) {
                char c = RouterInfo.BW_CAPABILITY_CHARS.charAt(i);
                if (caps.indexOf(c) >= 0) {return c;}
            }
        }
        return '?';
    }

    private String netDbLink(Hash peer) {
        return _context.commSystem().renderPeerHTML(peer, true);
    }

    private String peerFlag(Hash peer) {
        return _context.commSystem().renderPeerFlag(peer);
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

}
