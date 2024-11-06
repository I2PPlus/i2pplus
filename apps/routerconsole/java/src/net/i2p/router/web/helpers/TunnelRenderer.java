package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.text.Collator;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.CommSystemFacadeImpl;
import net.i2p.router.transport.TransportImpl;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
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
    private final RouterContext _context;
    private final Log _log;

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

    public void renderStatusHTML(Writer out) throws IOException {
        boolean isAdvanced = _context.getBooleanProperty(HelperBase.PROP_ADVANCED);
        TunnelManagerFacade tm = _context.tunnelManager();
        TunnelPool ei = tm.getInboundExploratoryPool();
        TunnelPool eo = tm.getOutboundExploratoryPool();
        out.write("<h3 class=tabletitle id=exploratory>" + _t("Exploratory"));
        // links are set to float:right in CSS so they will be displayed in reverse order
        out.write(" <a href=\"/configtunnels#exploratory\" title=\"" +
               _t("Configure tunnels") + "\">[" + _t("configure") + "]</a>");
        writeGraphLinks(out, ei, eo);
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
            String b64 = client.toBase64().substring(0,4);
            if (isLocal) {
                out.write("<h3 class=\"");
                if (_context.clientManager().shouldPublishLeaseSet(client)) {out.write("server ");}
                else if ((getTunnelName(in).startsWith("Ping") && getTunnelName(in).contains("[")) || getTunnelName(in).equals("I2Ping")) {
                    out.write("ping ");
                } else {out.write("client ");}
                out.write("tabletitle\" ");
                out.write("id=\"" + client.toBase64().substring(0,4) + "\" >");
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
                out.write(" <a class=\"lsview\" href=\"/netdb?l=3#ls_" + client.toBase32().substring(0,4) + "\">" +
                          "<span class=\"b32\" title=\"" + _t("View LeaseSet") + "\">" +
                          client.toBase32().substring(0,4) + "</span></a>");
                out.write("</h3>\n");

                // list aliases
                Set<Hash> aliases = in.getSettings().getAliases();
                if (aliases != null) {
                    for (Hash a : aliases) {
                        TunnelPool ain = clientInboundPools.get(a);
                        if (ain != null) {
                            String aname = ain.getSettings().getDestinationNickname();
                            String ab64 = a.toBase64().substring(0, 4);
                            if (aname == null) {aname = ab64;}
                            out.write("<h3 class=tabletitle ");
                            out.write("id=\"" + ab64 + "\" >");
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
        if (!participating.isEmpty()) {
            sb.append("<h3 class=tabletitle id=participating>");
            if (bySpeed) {sb.append(_t("Fastest Active Transit Tunnels"));}
            else {sb.append(_t("Most Recent Active Transit Tunnels"));}
            sb.append("&nbsp;&nbsp;<a id=refreshPage class=refreshpage style=float:right href=\"/transit\">")
              .append(_t("Refresh")).append("</a></h3>\n");
            int bwShare = getShareBandwidth();
            if (bwShare > 12) {
                if (!participating.isEmpty()) {
                    sb.append("<table id=allTransit class=\"tunneldisplay tunnels_participating\" data-sortable>\n")
                      .append("<thead><tr data-sort-method=thead>").append("<th data-sortable>").append(_t("Role")).append("</th>")
                      .append("<th data-sortable");
                    if (!bySpeed) {sb.append(" data-sort-default");}
                    sb.append(">").append(_t("Expiry")).append("</th>").append("<th title=\"").append(_t("Data transferred"))
                      .append("\" data-sortable data-sort-method=dotsep>").append(_t("Data")).append("</th>").append("<th data-sortable");
                    if (bySpeed) {sb.append(" data-sort-default");}
                    sb.append(">").append(_t("Speed")).append("</th>").append("<th data-sortable>");
                    if (isAdvanced) {sb.append(_t("Receive on")).append("</th>").append("<th data-sortable data-sort-method=number>");}
                    sb.append(_t("From")).append("</th><th data-sortable>");
                    if (isAdvanced) {sb.append(_t("Send on")).append("</th><th data-sortable data-sort-method=number>");}
                    sb.append(_t("To")).append("</th></tr></thead>\n<tbody id=transitPeers>\n");
                }
                long processed = 0;
                RateStat rs = _context.statManager().getRate("tunnel.participatingMessageCount");
                if (rs != null) {processed = (long)rs.getRate(10*60*1000).getLifetimeTotalValue();}
                int inactive = 0;
                displayed = 0;
                for (int i = 0; i < participating.size(); i++) {
                    HopConfig cfg = participating.get(i);
                    int count = cfg.getProcessedMessagesCount();
                    if (count <= 0) {
                        inactive++;
                        continue;
                    }
                    if (bySpeed) {DataHelper.sort(participating, new TunnelComparatorBySpeed());}
                    else {DataHelper.sort(participating, new TunnelComparator());}
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
                    if (timeLeft > 0) {
                        sb.append("<td class=\"cells expiry\"><span class=right>").append(timeLeft / 1000)
                          .append("</span><span class=left>&#8239;").append(_t("sec")).append("</span></td>");
                    } else {
                        sb.append("<td><i>").append(_t("grace period")).append("</i></td>");
                    }
                    sb.append("<td class=\"cells datatransfer\"><span class=right>").append((count * 1024 / 1000))
                      .append("</span><span class=left>&#8239;KB</span></td>");

                    int lifetime = (int) ((_context.clock().now() - cfg.getCreation()) / 1000);
                    if (lifetime <= 0) {lifetime = 1;}
                    else if (lifetime > 10*60) {lifetime = 10*60;}
                    float bps = 1024 * count / lifetime;
                    float kbps = bps / 1024;
                    sb.append("<td class=\"cells bps\"><span class=right>").append(fmt.format(kbps)).append("&#8239;</span>")
                      .append("<span class=left>KB/s</span></td>");

                    long recv = cfg.getReceiveTunnelId();
                    if (isAdvanced) {
                        if (recv != 0) {
                            sb.append("<td title=\"").append(_t("Tunnel identity")).append("\"><span class=tunnel_id>")
                              .append(recv).append("</span></td>");
                        } else {
                            sb.append("<td><span hidden>&ndash;</span></td>");
                        }
                    }
                    if (from != null) {sb.append("<td><span class=tunnel_peer>").append(netDbLink(from)).append("</span></td>");}
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
                    if (to != null) {sb.append("<td><span class=tunnel_peer>").append(netDbLink(to)).append("</span></td>");}
                    else {sb.append("<td><span hidden>&ndash;</span></td>");}
                    sb.append("</tr>\n");
                }
                sb.append("</tbody>\n</table>\n");
                if (displayed > DISPLAY_LIMIT) {
                    if (bySpeed) {
                        sb.append("<div class=statusnotes><b>")
                          .append(_t("Limited display to the {0} tunnels with the highest usage", DISPLAY_LIMIT))
                          .append("</b></div>\n");
                    } else {
                        sb.append("<div class=statusnotes><b>")
                        .append(_t("Limited display to the {0} most recent tunnels", DISPLAY_LIMIT))
                        .append("</b></div>\n");
                    }
                } else if (displayed >= 2) {
                    sb.append("<div class=statusnotes><b>").append(_t("Active") ).append(":</b>&nbsp").append(displayed);
                    if (inactive > 0) {
                        sb.append("&nbsp;&bullet;&nbsp;<b>").append(_t("Inactive")).append(":</b>&nbsp;").append(inactive)
                          .append("&nbsp;&bullet;&nbsp;<b>").append(_t("Total")).append(":</b>&nbsp;").append((inactive + displayed));
                    }
                    sb.append("</div>");
                } else if (inactive > 0) {
                    sb.append("<div class=statusnotes><b>").append(_t("Inactive")).append(":</b>&nbsp;").append(inactive).append("</div>");
                }
                sb.append("<div class=statusnotes><b>").append(_t("Lifetime bandwidth usage")).append(":</b>&nbsp;")
                  .append(DataHelper.formatSize2(processed*1024, true).replace("i", "")).append("B</div>\n");
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

    public void renderTransitSummary(Writer out) throws IOException {
        List<HopConfig> participating = _context.tunnelDispatcher().listParticipatingTunnels();
        if (!participating.isEmpty() && participating.size() > 1) {
            int displayed = 0;
            // peer table sorted by number of tunnels
            ObjectCounterUnsafe<Hash> counts = new ObjectCounterUnsafe<Hash>();
            ObjectCounterUnsafe<Hash> bws = new ObjectCounterUnsafe<Hash>();
            for (int i = 0; i < participating.size(); i++) {
                HopConfig cfg = participating.get(i);
                Hash from = cfg.getReceiveFrom();
                Hash to = cfg.getSendTo();
                int msgs = cfg.getProcessedMessagesCount();
                if (from != null) {
                    counts.increment(from);
                    if (msgs > 0) {bws.add(from, msgs);}
                }
                if (to != null) {
                    counts.increment(to);
                    if (msgs > 0) {bws.add(to, msgs);}
                }
            }

            // sort and output
            StringBuilder tbuf = new StringBuilder(3*512);
            tbuf.append("<h3 class=tabletitle>").append(_t("Transit Tunnels by Peer (Top 50)")).append("</h3>\n")
                .append("<table id=transitSummary class=\"tunneldisplay tunnels_participating\" data-sortable>\n")
                .append("<thead><tr data-sort-method=none>")
                .append("<th id=country data-sortable>").append(_t("Country")).append("</th>")
                .append("<th id=router data-sortable data-sort-method=natural>").append(_t("Router")).append("</th>")
                .append("<th id=version data-sortable data-sort-method=dotsep>").append(_t("Version")).append("</th>")
                .append("<th id=tier data-sortable data-sort=LMNOPX>").append(_t("Tier")).append("</th>")
                .append("<th id=address data-sortable>").append(_t("Address") + "</th>");
            if (enableReverseLookups()) {tbuf.append("<th id=domain data-sortable>").append(_t("Domain")).append("</th>");}
            tbuf.append("<th class=tcount data-sortable data-sort-method=number data-sort-default>").append(_t("Tunnels")).append("</th>")
                .append("<th id=data data-sortable data-sort-method=filesize>").append(_t("Data")).append("</th>")
                //.append("<th data-sortable data-sort-method=number>").append(_t("Speed")).append("</th>")
                .append("<th id=banned data-sortable hidden>" + _t("Banned")).append("</th>")
                .append("<th id=edit data-sort-method=none>" + _t("Edit")).append("</th>")
                .append("</tr></thead>\n<tbody id=transitPeers>\n");
            out.write(tbuf.toString());

            displayed = 0;
            List<Hash> sort = counts.sortedObjects();
            long uptime = _context.router().getUptime();
            int bannedCount = 0;
            StringBuilder sb = new StringBuilder(1024);
            for (Hash h : sort) {
                DISPLAY_LIMIT = 50;
                int count = counts.count(h);
                //char cap = getCapacity(h);
                HopConfig cfg = participating.get(count);
                int lifetime = count > 0 ? (int) ((_context.clock().now() - cfg.getCreation()) / 1000) : 1;
                RouterInfo info = _context.netDb().lookupRouterInfoLocally(h);
                String truncHash = h.toBase64().substring(0,4);
                byte[] direct = TransportImpl.getIP(h);
                String directIP = "";
                if (direct != null) {directIP = Addresses.toString(direct);}
                String ip = !directIP.equals("") ? directIP : (info != null) ? net.i2p.util.Addresses.toString(CommSystemFacadeImpl.getValidIP(info)) : null;
                String rl = (ip != null && enableReverseLookups() && uptime > 30*1000) ? _context.commSystem().getCanonicalHostName(ip) : null;
                String v = info != null ? info.getOption("router.version") : null;
                boolean isBanned = _context.banlist().isBanlisted(h);
                int inactive = 0;
                if (count <= 0 && (participating.size() == 0) || ++displayed > DISPLAY_LIMIT) {break;}
                sb.append("<tr class=lazy><td>").append(peerFlag(h)).append("</td><td>")
                  .append("<span class=routerHash><a href=\"netdb?r=").append(h.toBase64()).append("\">")
                  .append(truncHash).append("</a></span></td><td>");
                if (v != null) {
                    sb.append("<span class=version title=\"").append(_t("Show all routers with this version in the NetDb"))
                      .append("\"><a href=\"/netdb?v=").append(DataHelper.stripHTML(v)).append("\">")
                      .append(DataHelper.stripHTML(v)).append("</a></span>");
                } else if (isBanned) {sb.append("<span class=banlisted title=\"").append(_t("Router is banlisted")).append("\">???</span>");}
                else {sb.append("<span>???</span>");}
                sb.append("</td><td>");
                if (info != null) {sb.append(_context.commSystem().renderPeerCaps(h, false));}
                else {sb.append("<table class=\"rid ric\"><tr><td class=rbw>?</td></tr></table>");}
                sb.append("</td><td><span class=ipaddress>");
                if (info != null && ip != null) {
                    if (!ip.toString().equals("null")) {
                        if (ip.toString().contains(":")) {sb.append("<span hidden>[IPv6]</span>").append(ip.toString());}
                        else {sb.append(ip.toString());}
/*
                        sb.append("<a class=script href=\"https://gwhois.org/" + ip.toString() + "+dns\" target=_blank title=\"" +
                                  _t("Lookup address on gwhois.org") + "\">" + ip.toString() + "</a>" +
                                  "<noscript>" + ip.toString() + "</noscript>");
*/
                    } else {sb.append("&ndash;");}
                } else {sb.append("&ndash;");}
                sb.append("</span></td>");
                if (enableReverseLookups()) {
                    sb.append("<td>");
                    if (rl != null && rl.length() != 0 && !ip.toString().equals(rl)) {
                        sb.append("<span class=rlookup title=\"").append(rl).append("\">");
                        if (!ip.toString().equals(rl)) {sb.append(CommSystemFacadeImpl.getDomain(rl));}
                        else {sb.append("<span hidden>").append("&ndash;").append("</span>");}
                    } else {sb.append("<span>").append(_t("unknown"));}
                    sb.append("</span></td>");
                }
                sb.append("<td class=tcount>" + count + "</td>");
                //sb.append("<td>" + (bws.count(h) > 0 ? DataHelper.formatSize2(bws.count(h) * 1024) + "B": "") + "</td>\n");
                sb.append("<td>");
                if (bws.count(h) > 0) {sb.append("<span class=data>" + fmt.format(bws.count(h)).replace(".00", "") + "KB</span>");}
                else {sb.append("<span class=data hidden>0KB</span>");}
/*
                if (lifetime <= 0)
                    lifetime = 1;
                if (lifetime > 10*60)
                    lifetime = 10*60;
                float bps = 1024 * count / lifetime;
                float kbps = bps / 1024;
                sb.append("</td><td class=\"cells bps\">");
                if (kbps >= 1) {
                    sb.append("<span class=right>" + fmt.format(kbps) + "&#8239;</span><span class=left>KB/s</span>");
                } else if (kbps >= 0) {
                    sb.append("<span class=right><&#8239;1</span><span class=left>KB/s</span>");
                } else {
                    sb.append("<span class=right hidden>0&#8239;</span><span class=left hidden>KB/s</span>");
                }
*/
                sb.append("</td><td class=isBanned hidden>");
                if (isBanned) {
                    sb.append("<span hidden>ban</span><a class=banlisted href=\"/profiles?f=3\" title=\"")
                      .append(_t("Router is banlisted")).append("\">Banned</a> ");
                    bannedCount++;
                }
                sb.append("</td><td>");
                if (info != null && info.getHash() != null) {
                    sb.append("<a class=configpeer href=\"/configpeer?peer=").append(info.getHash())
                      .append("\" title=\"Configure peer\">").append(_t("Edit")).append("</a>");
                }
                sb.append("</td></tr>\n");
            }
            sb.append("</tbody>\n</table>\n");
            //if (bannedCount == 0) {sb.append("<style>th#banned,td.isBanned{display:none!important}</style>\n");}
            out.write(sb.toString());
            out.flush();
            sb.setLength(0);
        } else if (_context.router().isHidden()) {
            out.write("<p class=infohelp>");
            out.write(_t("Router is currently operating in Hidden Mode which prevents transit tunnels from being built."));
        } else {
            out.write("<p class=infohelp>");
            out.write(_t("No transit tunnels currently active."));
        }
    }

    public void renderPeers(Writer out) throws IOException {
        long uptime = _context.router().getUptime();
        // count up the peers in the local pools
        ObjectCounter<Hash> localCount = new ObjectCounter<>();
        int tunnelCount = countTunnelsPerPeer(localCount);

        // count up the peers in the participating tunnels
        ObjectCounter<Hash> transitCount = new ObjectCounter<>();
        int partCount = countParticipatingPerPeer(transitCount);

        Set<Hash> peers = new HashSet<>(localCount.objects());
        peers.addAll(transitCount.objects());
        List<Hash> peerList = new ArrayList<>(peers);
        int peerCount = peerList.size();
        Collections.sort(peerList, new CountryComparator(this._context.commSystem()));

        List<HopConfig> participating = _context.tunnelDispatcher().listParticipatingTunnels();

        if (!participating.isEmpty() || tunnelCount > 0) {
            StringBuilder sb = new StringBuilder(peerCount*640+1024);
            sb.append("<h3 class=tabletitle id=peercount>").append(_t("All Tunnels by Peer"))
              .append("&nbsp;&nbsp;<a id=refreshPage class=refreshpage style=float:right href=\"/tunnelpeercount\">")
              .append(_t("Refresh")).append("</a></h3>\n")
              .append("<table id=tunnelPeerCount data-sortable>")
              .append("<thead>\n<tr>")
              .append("<th id=country data-sortable>").append(_t("Country")).append("</th>")
              .append("<th id=router data-sortable data-sort-method=natural>").append(_t("Router")).append("</th>")
              .append("<th id=version data-sortable data-sort-method=dotsep>").append(_t("Version")).append("</th>")
              .append("<th id=tier data-sortable data-sort=LMNOPX>").append(_t("Tier")).append("</th>")
              .append("<th id=address data-sortable title=\"").append(_t("Primary IP address")).append("\">")
              .append(_t("Address")).append("</th>");
            if (enableReverseLookups()) {
                sb.append("<th id=domain data-sortable>").append(_t("Domain")).append("</th>");
            }
            sb.append("<th class=tcount colspan=2 title=\"Client and Exploratory Tunnels\" ")
              .append("data-sortable data-sort-method=natural data-sort-column-key=localCount>")
              .append(_t("Local")).append("</th>");
            if (!participating.isEmpty()) {
                sb.append("<th class=tcount colspan=2 data-sortable data-sort-method=natural data-sort-column-key=transitCount>")
                  .append(_t("Transit")).append("</th>");
            } else {
                sb.append("<th></th>");
            }
            sb.append("<th id=edit data-sort-method=none>").append(_t("Edit")).append("</th>");
            sb.append("</tr>\n</thead>\n<tbody id=allPeers>\n");
            for (Hash h : peerList) {
                RouterInfo info = _context.netDb().lookupRouterInfoLocally(h);
                if (info == null) {
                    continue;
                }
                int localTunnelCount = localCount.count(h);
                byte[] direct = TransportImpl.getIP(h);
                String directIP = "";
                if (direct != null) {directIP = Addresses.toString(direct);}
                String ip = !directIP.equals("") ? directIP : net.i2p.util.Addresses.toString(CommSystemFacadeImpl.getValidIP(info));
                String v = info.getOption("router.version");
                String truncHash = h.toBase64().substring(0,4);
                String rl = "";
                if (ip != null && enableReverseLookups() && uptime > 30 * 1000) {
                    rl = _context.commSystem().getCanonicalHostName(ip);
                }
                sb.append("<tr class=lazy><td>")
                  .append(peerFlag(h))
                  .append("</td><td><span class=routerHash><a href=\"netdb?r=")
                  .append(h.toBase64())
                  .append("\">")
                  .append(truncHash)
                  .append("</a></span></td><td>");
                if (v != null) {
                    sb.append("<span class=version title=\"Show all routers with this version in the NetDb\">")
                      .append("<a href=\"/netdb?v=").append(DataHelper.stripHTML(v)).append("\">")
                      .append(DataHelper.stripHTML(v)).append("</a></span>");
                }
                sb.append("</td><td>")
                  .append(_context.commSystem().renderPeerCaps(h, false))
                  .append("</td><td><span class=ipaddress>");
                if (ip != null && !ip.isEmpty()) {
                    if (ip.contains(":")) {
                        sb.append("<span hidden>[IPv6]</span>");
                    }
                    sb.append(ip);
                } else {
                    sb.append("&ndash;");
                }
                sb.append("</span>");
                if (enableReverseLookups() && rl != null && !rl.isEmpty() && !ip.equals(rl)) {
                    sb.append(String.format("</td><td><span class=rlookup title=\"%s\">", rl))
                      .append(CommSystemFacadeImpl.getDomain(rl))
                      .append("</span>");
                } else if (enableReverseLookups()) {
                    sb.append("<td></td>");
                }
                sb.append(String.format("<td class=tcount data-sort-column-key=localCount>%d</td><td class=bar data-sort-column-key=localCount>", localTunnelCount));
                if (localTunnelCount > 0) {
                    sb.append(String.format("<span class=percentBarOuter><span class=percentBarInner style=\"width:%s%%\">" +
                                            "<span class=percentBarText>%d%%</span></span></span>",
                                            fmt.format(localTunnelCount * 100 / tunnelCount).replace(".00", ""),
                                            localTunnelCount * 100 / tunnelCount));
                } else {
                    sb.append("<span hidden>&ndash;</span>");
                }
                sb.append("</td>");
                if (!participating.isEmpty()) {
                    int transitTunnelCount = transitCount.count(h);
                    if (transitTunnelCount > 0) {
                        sb.append(String.format("<td class=tcount data-sort-column-key=transitCount>%d</td><td class=bar data-sort-column-key=transitCount>", transitTunnelCount));
                        sb.append(String.format("<span class=percentBarOuter><span class=percentBarInner style=\"width:%s%%\">" +
                                                "<span class=percentBarText>%d%%</span></span></span>",
                                                fmt.format(transitTunnelCount * 100 / partCount).replace(".00", ""),
                                                transitTunnelCount * 100 / partCount));
                        sb.append("</td>");
                    } else {
                        sb.append("<td></td><td></td>");
                    }
                } else {
                    sb.append("<td></td>");
                }
                sb.append(String.format("<td><a class=configpeer href=\"/configpeer?peer=%s\" title=\"" +
                  _t("Configure peer") + "\">%s</a></td></tr>\n", info.getHash(), _t("Edit")));
            }
            sb.append("</tbody>\n<tfoot><tr class=tablefooter data-sort-method=none>")
              .append("<td colspan=4><b>").append(peerCount).append(" ").append(_t("unique peers")).append("</b></td>")
              .append("<td></td>");
            if (enableReverseLookups()) {
                sb.append("<td></td>");
            }
            sb.append("<td colspan=2><b>").append(tunnelCount).append(" ").append(_t("local")).append("</b></td>");
            if (!participating.isEmpty()) {
                sb.append("<td colspan=2><b>").append(partCount).append(" ").append(_t("transit")).append("</b></td>");
            } else {
              sb.append("<td></td>");
            }
            sb.append("<td></td></tr>\n</tfoot>\n</table>\n");
            out.write(sb.toString());
            out.flush();
            sb.setLength(0);
        } else {
            out.write("<p class=infohelp>");
            out.write(_t("No local or transit tunnels currently active."));
            out.write("</p>\n");
        }
    }

    public void renderGuide(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<h3 class=tabletitle>" + _t("Bandwidth Tiers") + "</h3>\n")
           .append("<table id=tunnel_defs><tbody>")
           .append("<tr><td>&nbsp;</td>")
           .append("<td><span class=tunnel_cap><b>L</b></span></td>")
           .append("<td>").append(_t("{0} shared bandwidth", range(Router.MIN_BW_L, Router.MIN_BW_M))).append("</td>")
           .append("<td><span class=tunnel_cap><b>M</b></span></td>")
           .append("<td>").append(_t("{0} shared bandwidth", range(Router.MIN_BW_M, Router.MIN_BW_N))).append("</td>")
           .append("<td>&nbsp;</td></tr>")
           .append("<tr><td>&nbsp;</td>")
           .append("<td><span class=tunnel_cap><b>N</b></span></td>")
           .append("<td>").append(_t("{0} shared bandwidth", range(Router.MIN_BW_N, Router.MIN_BW_O))).append("</td>")
           .append("<td><span class=tunnel_cap><b>O</b></span></td>")
           .append("<td>").append(_t("{0} shared bandwidth", range(Router.MIN_BW_O, Router.MIN_BW_P))).append("</td>")
           .append("<td>&nbsp;</td></tr>")
           .append("<tr><td>&nbsp;</td>")
           .append("<td><span class=tunnel_cap><b>P</b></span></td>")
           .append("<td>").append(_t("{0} shared bandwidth", range(Router.MIN_BW_P, Router.MIN_BW_X))).append("</td>")
           .append("<td><span class=tunnel_cap><b>X</b></span></td>")
           .append("<td>").append(_t("Over {0} shared bandwidth", Math.round(Router.MIN_BW_X * 1.024f))).append("KBps").append("</td>")
           .append("<td>&nbsp;</td></tr>")
           .append("</tbody></table>");
        out.write(buf.toString());
        out.flush();
        buf.setLength(0);
    }

    /** @since 0.9.33 */
    static String range(int f, int t) {
        return Math.round(f * 1.024f) + " - " + (Math.round(t * 1.024f) - 1) + " KBps";
    }

    private static class TunnelComparator implements Comparator<HopConfig>, Serializable {
         public int compare(HopConfig l, HopConfig r) {
//             return (r.getProcessedMessagesCount() - l.getProcessedMessagesCount());
//             return (l.getProcessedMessagesCount() - r.getProcessedMessagesCount());
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
             return (r.getProcessedMessagesCount() - l.getProcessedMessagesCount());
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
        Rate ir = irs.getRate(5*60*1000L);
        Rate or = ors.getRate(5*60*1000L);
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
            buf.append("<table class=\"tunneldisplay tunnels_client\">\n")
               .append("<tr><th title=\"").append(_t("Inbound or outbound?")).append("\">").append(_t("In/Out")).append("</th>")
               .append("<th>").append(_t("Expiry")).append("</th>")
               .append("<th title=\"").append(_t("Data transferred")).append("\">").append(_t("Data")).append("</th>")
               .append("<th>").append(_t("Gateway")).append("</th>");
            if (maxLength > 3) {
                buf.append("<th colspan=\"").append(maxLength - 2).append("\">").append(_t("Participants")).append("</th>");
            } else if (maxLength == 3) {buf.append("<th>").append(_t("Participant")).append("</th>");}
            if (maxLength > 1) {buf.append("<th>").append(_t("Endpoint")).append("</th>");}
            buf.append("</tr>\n");
        }
        final String tib = _t("Inbound");
        final String tob = _t("Outbound");
        for (int i = 0; i < tunnels.size(); i++) {
            TunnelInfo info = tunnels.get(i);
            long timeLeft = info.getExpiration()-_context.clock().now();
            if (timeLeft <= 0) {continue;} // don't display tunnels in their grace period
            live++;
            boolean isInbound = info.isInbound();
            if (isInbound) {
                buf.append("<tr><td><span class=inbound title=\"").append(tib).append("\">")
                   .append("<img src=/themes/console/images/inbound.svg alt=\"").append(tib).append("\"></span></td>");
            } else {
                buf.append("<tr><td><span class=outbound title=\"").append(tob).append("\">")
                   .append("<img src=/themes/console/images/outbound.svg alt=\"").append(tob).append("\"></span></td>");
            }
            buf.append("<td>").append(DataHelper.formatDuration2(timeLeft)).append("</td>");
            int count = info.getProcessedMessagesCount() * 1024 / 1000;
            buf.append("<td class=\"cells datatransfer\">");
            if (count > 0) {
                buf.append("<span class=right>").append(count).append("</span><span class=left>&#8239;KB</span>");
            }
            buf.append("</td>\n");
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
                    // Add empty content placeholders to force alignment.
                    buf.append(" <td><span class=\"tunnel_peer tunnel_local\" title=\"")
                       .append(_t("Locally hosted tunnel")).append("\">").append(_t("Local")).append("</span>");
                    if (isAdvanced) {
                        buf.append("<span class=tunnel_id title=\"").append(_t("Tunnel identity")).append("\">")
                           .append((id == null ? "" : "" + id)).append("</span>");
                    }
                    buf.append("</td>");
                } else {
                    buf.append(" <td><span class=tunnel_peer>").append(netDbLink(peer)).append("</span>");
                    if (isAdvanced) {
                        buf.append("<span class=tunnel_id title=\"").append(_t("Tunnel identity")).append("\">")
                           .append((id == null ? "" : " " + id)).append("</span>");
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
        buf.append("</table>\n");
        if (live > 0 && ((in != null || (outPool != null)))) {
            List<?> pendingIn = in.listPending();
            List<?> pendingOut = outPool.listPending();
            if ((!pendingIn.isEmpty()) || (!pendingOut.isEmpty())) {
                buf.append("<div class=\"statusnotes building\"><center><b>").append(_t("Build in progress")).append(":&nbsp;");
                if (in != null) {
                    // PooledTunnelCreatorConfig
                    if (!pendingIn.isEmpty()) {
                        buf.append("&nbsp;<span class=pending>").append(pendingIn.size()).append(" ")
                           .append(tib).append("</span>&nbsp;");
                        live += pendingIn.size();
                    }
                }
                if (outPool != null) {
                    // PooledTunnelCreatorConfig
                    if (!pendingOut.isEmpty()) {
                        buf.append("&nbsp;<span class=pending>").append(pendingOut.size())
                           .append(" ").append(tob).append("</span>&nbsp;");
                        live += pendingOut.size();
                    }
                }
                buf.append("</b></center></div>\n");
            }
        }
        if (live <= 0) {
            buf.append("<div class=statusnotes><center><b>").append(_t("none")).append("</b></center></div>\n");
        } else {
        buf.append("<div class=statusnotes><center><b>").append(_t("Lifetime bandwidth usage")).append(":&nbsp;&nbsp;")
           .append(DataHelper.formatSize2(processedIn*1024, true).replace("i", ""))
           .append("B ").append(_t("in")).append(", ")
           .append(DataHelper.formatSize2(processedOut*1024, true).replace("i", ""))
           .append("B ").append(_t("out")).append("</b></center></div>\n");
        }
        out.write(buf.toString());
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
        out.write(buf.toString());
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
