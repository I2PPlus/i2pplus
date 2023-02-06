package net.i2p.router.web.helpers;

import java.net.InetAddress;

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
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.TunnelId;

import net.i2p.router.CommSystemFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.CommSystemFacadeImpl;
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
import net.i2p.util.ObjectCounter;
import net.i2p.util.ObjectCounterUnsafe;


/**
 *  For /tunnels.jsp, used by TunnelHelper.
 */
class TunnelRenderer {
    private final RouterContext _context;

    private int DISPLAY_LIMIT = 200;
    private final DecimalFormat fmt = new DecimalFormat("#0.00");

    private static final String PROP_ENABLE_REVERSE_LOOKUPS = "routerconsole.enableReverseLookups";
    public boolean enableReverseLookups() {
        return _context.getBooleanProperty(PROP_ENABLE_REVERSE_LOOKUPS);
    }

    public TunnelRenderer(RouterContext ctx) {
        _context = ctx;
    }

    public void renderStatusHTML(Writer out) throws IOException {
        boolean debug = _context.getBooleanProperty(HelperBase.PROP_ADVANCED);
        TunnelManagerFacade tm = _context.tunnelManager();
        TunnelPool ei = tm.getInboundExploratoryPool();
        TunnelPool eo = tm.getOutboundExploratoryPool();
        out.write("<h3 class=tabletitle id=\"exploratory\">" + _t("Exploratory"));
        // links are set to float:right in CSS so they will be displayed in reverse order
        out.write(" <a href=\"/configtunnels#exploratory\" title=\"" +
               _t("Configure tunnels") + "\">[" + _t("configure") + "]</a>");
        writeGraphLinks(out, ei, eo);
        out.write("</h3>\n");
        renderPool(out, ei, eo);
        // add empty span so we can link to client tunnels in the sidebar
        out.write("<span id=\"client_tunnels\"></span>");

        Map<Hash, TunnelPool> clientInboundPools = tm.getInboundClientPools();
        // display name to in pool
        List<TunnelPool> sorted = new ArrayList<TunnelPool>(clientInboundPools.values());
        if (sorted.size() > 1)
            DataHelper.sort(sorted, new TPComparator());
        for (TunnelPool in : sorted) {
            Hash client = in.getSettings().getDestination();
            boolean isLocal = _context.clientManager().isLocal(client);
            if ((!isLocal) && (!debug))
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
                if (_context.clientManager().shouldPublishLeaseSet(client))
                    out.write("server ");
                else if ((getTunnelName(in).startsWith("Ping") && getTunnelName(in).contains("[")) || getTunnelName(in).equals("I2Ping"))
                    out.write("ping ");
                else
                    out.write("client ");
                out.write("tabletitle\" ");
                out.write("id=\"" + client.toBase64().substring(0,4) + "\" >");
                out.write(getTunnelName(in));
                // links are set to float:right in CSS so they will be displayed in reverse order
                if (debug /*&& (!name.startsWith("Ping") && !name.contains("[")) || !name.equals("I2Ping")*/)
                    out.write(" <a href=\"/configtunnels#" + b64 +"\" title=\"" +
                              _t("Configure tunnels for session") + "\">[" + _t("configure") + "]</a>");
                else /*if ((!name.startsWith("Ping") && !name.contains("[")) || !name.equals("I2Ping"))*/
                    out.write(" <a href=\"/i2ptunnelmgr\" title=\"" +
                              _t("Configure tunnels") + "\">[" + _t("configure") + "]</a>");
                writeGraphLinks(out, in, outPool);
                out.write(" <a class=\"lsview\" href=\"/netdb?l=1#ls_" + client.toBase32().substring(0,4) + "\">" +
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
                            if (aname == null)
                                aname = ab64;
                        out.write("<h3 class=tabletitle ");
                        out.write("id=\"" + ab64 + "\" >");
                        out.write(DataHelper.escapeHTML(_t(aname)));
                        if (debug)
                            out.write(" <a href=\"/configtunnels#" + b64 +"\" title=\"" +
                                      _t("Configure tunnels for session") + "\">[" + _t("configure") + "]</a>");
                        else
                            out.write(" <a href=\"/i2ptunnelmgr\" title=\"" +
                                      _t("Configure tunnels") + "\">[" + _t("configure") + "]</a>");
                        out.write("</h3>\n");
                        }
                    }
                }
                renderPool(out, in, outPool);
            }
        }
    }

    public void renderParticipating(Writer out, boolean bySpeed) throws IOException {
        boolean debug = _context.getBooleanProperty(HelperBase.PROP_ADVANCED);
        List<HopConfig> participating = _context.tunnelDispatcher().listParticipatingTunnels();
        if (!participating.isEmpty()) {
            out.write("<h3 class=tabletitle id=participating>");
            if (bySpeed)
                out.write(_t("Fastest Active Transit Tunnels"));
            else
                out.write(_t("Most Recent Active Transit Tunnels"));
            out.write("&nbsp;&nbsp;<a id=refreshPage class=refreshpage style=float:right href=\"/tunnelsparticipating\">" +
                      _t("Refresh") + "</a></h3>\n");
            int bwShare = getShareBandwidth();
            if (bwShare > 12) {
                if (!participating.isEmpty()) {
                    out.write("<table id=allTransit class=\"tunneldisplay tunnels_participating\" data-sortable>\n" +
                              "<thead><tr data-sort-method=thead>" +
                              "<th data-sortable>" + _t("Role") + "</th>" +
                              "<th data-sortable");
                    if (!bySpeed) {
                        out.write(" data-sort-default");
                    }
                    out.write(">" + _t("Expiry") + "</th>" +
                              "<th title=\"" + _t("Data transferred") + "\" data-sortable data-sort-method=dotsep>" + _t("Data") + "</th>" +
                              "<th data-sortable");
                    if (bySpeed) {
                        out.write(" data-sort-default");
                    }
                    out.write(">" + _t("Speed") + "</th>" +
                              "<th data-sortable>");
                    if (debug)
                        out.write(_t("Receive on") + "</th>" + "<th data-sortable data-sort-method=number>");
                    out.write(_t("From") + "</th><th data-sortable>");
                    if (debug)
                        out.write(_t("Send on") + "</th><th data-sortable data-sort-method=number>");
                    out.write(_t("To") + "</th></tr></thead>\n<tbody id=transitPeers>\n");
                }
                long processed = 0;
                RateStat rs = _context.statManager().getRate("tunnel.participatingMessageCount");
                if (rs != null)
                    processed = (long)rs.getRate(10*60*1000).getLifetimeTotalValue();
                int inactive = 0;
                int displayed = 0;
                for (int i = 0; i < participating.size(); i++) {
                    HopConfig cfg = participating.get(i);
                    int count = cfg.getProcessedMessagesCount();
                    if (count <= 0) {
                        inactive++;
                        continue;
                    }
                    if (bySpeed) {
                        DataHelper.sort(participating, new TunnelComparatorBySpeed());
                    } else {
                        DataHelper.sort(participating, new TunnelComparator());
                    }
                    Hash to = cfg.getSendTo();
                    Hash from = cfg.getReceiveFrom();
                    // everything that isn't 'recent' is already in the tunnel.participatingMessageCount stat
                    processed += cfg.getRecentMessagesCount();
                    if (++displayed > DISPLAY_LIMIT)
                        continue;
                    out.write("<tr class=lazy>");
                    if (to == null)
                        out.write("<td class=\"cells obep\" title=\"" + _t("Outbound Endpoint") + "\">" + _t("Outbound Endpoint") + "</td>");
                    else if (from == null)
                        out.write("<td class=\"cells ibgw\" title=\"" + _t("Inbound Gateway") + "\">" + _t("Inbound Gateway") + "</td>");
                    else
                        out.write("<td class=\"cells ptcp\" title=\"" + _t("Participant") + "\">" + _t("Participant") + "</td>");
                    long timeLeft = cfg.getExpiration()-_context.clock().now();
                    if (timeLeft > 0)
//                        out.write("<td class=\"cells expiry\">" + DataHelper.formatDuration2(timeLeft) + "</td>");
                        out.write("<td class=\"cells expiry\"><span class=right>" + timeLeft / 1000 +
                                  "</span><span class=left>&#8239;" + _t("sec") + "</span></td>");
                    else
                        out.write("<td><i>" + _t("grace period") + "</i></td>");
                    out.write("<td class=\"cells datatransfer\"><span class=right>" + (count * 1024 / 1000) +
                              "</span><span class=left>&#8239;KB</span></td>");
                    int lifetime = (int) ((_context.clock().now() - cfg.getCreation()) / 1000);
                    if (lifetime <= 0)
                        lifetime = 1;
                    if (lifetime > 10*60)
                        lifetime = 10*60;
//                    long bps = 1024L * count / lifetime;
                    float bps = 1024 * count / lifetime;
                    float kbps = bps / 1024;
                    out.write("<td class=\"cells bps\"><span class=right>" + fmt.format(kbps) +
                              "&#8239;</span><span class=left>KB/s</span></td>");
/*
                    out.write("<td class=\"cells bps\"><span class=right>" + DataHelper.formatSize2(bps, true).replace("i", "")
                        .replace("K", "&#8239;</span><span class=left>K").replace("M", "&#8239;</span><span class=left>M"));
                    if (bps > 1023)
                        out.write(_t("/s"));
                    else
                        out.write("</span><span class=left>" + _t("B/s"));
                    out.write("</span></td>");
*/
                    long recv = cfg.getReceiveTunnelId();
                    if (debug) {
                        if (recv != 0)
                            out.write("<td title=\"" + _t("Tunnel identity") + "\"><span class=tunnel_id>" +
                                      recv + "</span></td>");
                        else
                            out.write("<td><span hidden>&ndash;</span></td>");
                    }
                    if (from != null)
                        out.write("<td><span class=tunnel_peer>" + netDbLink(from) +
                                  "</span>");//&nbsp;<b class=tunnel_cap title=\"" + _t("Bandwidth tier") + "\">" + getCapacity(from) + "</b></td>");
                    else
                        out.write("<td><span hidden>&ndash;</span></td>");
                    long send = cfg.getSendTunnelId();
                    if (debug) {
                        if (send != 0)
                            out.write("<td title=\"" + _t("Tunnel identity") + "\"><span class=tunnel_id>" +
                                      send + "</span></td>");
                        else
                            out.write("<td><span hidden>&ndash;</span></td>");
                    }
                    if (to != null)
                        out.write("<td><span class=tunnel_peer>" + netDbLink(to) +
                                  "</span>");//&nbsp;<b class=tunnel_cap title=\"" + _t("Bandwidth tier") + "\">" + getCapacity(to) + "</b></td>");
                    else
                        out.write("<td><span hidden>&ndash;</span></td>");
                    out.write("</tr>\n");
                }
                out.write("</tbody>\n</table>\n");
                if (displayed > DISPLAY_LIMIT) {
                    if (bySpeed)
                        out.write("<div class=statusnotes><b>" + _t("Limited display to the {0} tunnels with the highest usage", DISPLAY_LIMIT)  + "</b></div>\n");
                    else
                        out.write("<div class=statusnotes><b>" + _t("Limited display to the {0} most recent tunnels", DISPLAY_LIMIT)  + "</b></div>\n");
                } else if (displayed >= 2) {
                    out.write("<div class=statusnotes><b>" + _t("Active")  + ":</b>&nbsp" + displayed);
                    if (inactive > 0) {
                        out.write("&nbsp;&bullet;&nbsp;<b>" + _t("Inactive") + ":</b>&nbsp;" + inactive + "&nbsp;&bullet;&nbsp;<b>" +
                                  _t("Total") + ":</b>&nbsp;" + (inactive + displayed));
                    }
                    out.write("</div>");
                } else if (inactive > 0) {
                    out.write("<div class=statusnotes><b>" + _t("Inactive") + ":</b>&nbsp;" + inactive + "</div>");
                }
                out.write("<div class=statusnotes><b>" + _t("Lifetime bandwidth usage") + ":</b>&nbsp;" +
                          DataHelper.formatSize2(processed*1024, true).replace("i", "") + "B</div>\n");
            } else { // bwShare < 12K/s
                out.write("<div class=\"statusnotes noparticipate\"><b>" + _t("Not enough shared bandwidth to build transit tunnels.") +
                          "</b> <a href=\"config\">[" + _t("Configure") + "]</a></div>\n");
            }
        } else if (_context.router().isHidden()) {
            out.write("<p class=infohelp>" + _t("Router is currently operating in Hidden Mode which prevents transit tunnels from being built.") + "</p>");
        } else {
            out.write("<p class=infohelp>" + _t("No transit tunnels currently active.") + "</p>");
        }
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
                    if (msgs > 0)
                        bws.add(from, msgs);
                }
                if (to != null) {
                    counts.increment(to);
                    if (msgs > 0)
                        bws.add(to, msgs);
                }
            }

            // sort and output
            out.write("<h3 class=tabletitle>Transit Tunnels by Peer (Top 50)</h3>\n");
            out.write("<table id=transitSummary class=\"tunneldisplay tunnels_participating\" data-sortable>\n" +
                      "<thead><tr data-sort-method=none>" +
                      "<th id=country data-sortable>" + _t("Country") + "</th>" +
                      "<th id=router data-sortable data-sort-method=natural>" + _t("Router") + "</th>" +
                      "<th id=version data-sortable data-sort-method=dotsep>" + _t("Version") + "</th>" +
                      "<th id=tier data-sortable data-sort=LMNOPX>" + _t("Tier") + "</th>" +
                      "<th id=address data-sortable data-sort-method=dotsep>" + _t("Address") + "</th>");
            if (enableReverseLookups()) {
                out.write("<th id=domain data-sortable>" + _t("Domain") + "</th>");
            }
            out.write("<th class=tcount data-sortable data-sort-method=number>" + _t("Tunnels") + "</th>" +
                      "<th id=data data-sortable data-sort-method=dotsep>" + _t("Data") + "</th>" +
                      //"<th data-sortable data-sort-method=number>" + _t("Speed") + "</th>" +
                      "<th id=edit data-sort-method=none>" + _t("Edit") + "</th>" +
                      "</tr></thead>\n<tbody id=transitPeers>\n");
            displayed = 0;
            List<Hash> sort = counts.sortedObjects();
            for (Hash h : sort) {
                DISPLAY_LIMIT = 50;
                int count = counts.count(h);
                char cap = getCapacity(h);
                HopConfig cfg = participating.get(count);
                int lifetime = count > 0 ? (int) ((_context.clock().now() - cfg.getCreation()) / 1000) : 1;
                RouterInfo info = _context.netDb().lookupRouterInfoLocally(h);
                //String rl = _context.namingService().reverseLookup(h);
                String truncHash = h.toBase64().substring(0,4);
                String ip = (info != null) ? net.i2p.util.Addresses.toString(CommSystemFacadeImpl.getValidIP(info)) : null;
                String rl = ip != null ? getCanonicalHostName(ip) : null;
                String v = info != null ? info.getOption("router.version") : null;
                int inactive = 0;
                if (count <= 0 && (participating.size() == 0))
                    break;
                if (++displayed > DISPLAY_LIMIT)
                    break;
                out.write("<tr class=lazy><td>");
                out.write(peerFlag(h));
                out.write("</td><td>");
                out.write("<span class=routerHash><a href=\"netdb?r=" + h.toBase64().substring(0,6) + "\">");
                out.write(truncHash);
                out.write("</td><td>");
/*
                out.write("<tr class=lazy><td>");
                out.write(netDbLink(h));
                out.write("</td><td>");
*/
                if (v != null) {
                    out.write("<span class=version title=\"" + _t("Show all routers with this version in the NetDb") +
                              "\"><a href=\"/netdb?v=" + DataHelper.stripHTML(v) + "\">" + DataHelper.stripHTML(v) +
                              "</a></span>");
                } else {
                    out.write("<span class=version\">???</span>");
                }
                out.write("</td><td><b class=tunnel_cap title=\"" + _t("Bandwidth tier") +
                          "\">" + getCapacity(h) + "</b></td>");
                out.write("<td><span class=ipaddress>");
                if (info != null && ip != null) {
                    if (!ip.toString().equals("null")) {
                        out.write("<a class=script href=\"https://gwhois.org/" + ip.toString() + "+dns\" target=_blank title=\"" +
                                  _t("Lookup address on gwhois.org") + "\">" + ip.toString() + "</a>" +
                                  "<noscript>" + ip.toString() + "</noscript>");
                    } else {
                        out.write("&ndash;");
                    }
                } else {
                    out.write("&ndash;");
                }
                out.write("</span></td>");
                if (enableReverseLookups()) {
                    out.write("<td>");
                    if (rl != null && rl.length() != 0 && !ip.toString().equals(rl)) {
                        out.write("<span class=rlookup title=\"");
                        out.write(rl);
                        out.write("\">");
                        if (!ip.toString().equals(rl)) {
                            out.write(CommSystemFacadeImpl.getDomain(rl));
                        }
                    } else {
                        out.write("<span hidden>");
                        out.write("&ndash;");
                    }
                    out.write("</span></td>");
                }
                out.write("<td class=tcount>" + count + "</td>");
                //out.write("<td>" + (bws.count(h) > 0 ? DataHelper.formatSize2(bws.count(h) * 1024) + "B": "") + "</td>\n");
                out.write("<td>");
                if (bws.count(h) > 0) {
                    out.write("<span class=data>" + fmt.format(bws.count(h)).replace(".00", "") + "KB</span>");
                } else {
                    out.write("<span class=data hidden>0KB</span>");
                }
/*
                if (lifetime <= 0)
                    lifetime = 1;
                if (lifetime > 10*60)
                    lifetime = 10*60;
                float bps = 1024 * count / lifetime;
                float kbps = bps / 1024;
                out.write("</td><td class=\"cells bps\">");
                if (kbps >= 1) {
                    out.write("<span class=right>" + fmt.format(kbps) + "&#8239;</span><span class=left>KB/s</span>");
                } else if (kbps >= 0) {
                    out.write("<span class=right><&#8239;1</span><span class=left>KB/s</span>");
                } else {
                    out.write("<span class=right hidden>0&#8239;</span><span class=left hidden>KB/s</span>");
                }
*/
                out.write("</td><td>");
                if (info != null && info.getHash() != null)
                    out.write("<a class=configpeer href=\"/configpeer?peer=" + info.getHash() + "\" title=\"Configure peer\">" +
                              _t("Edit") + "</a>");
                out.write("</td></tr>\n");
            }
            out.write("</tbody>\n</table>\n");
        } else if (_context.router().isHidden()) {
                out.write("<p class=infohelp>" + _t("Router is currently operating in Hidden Mode which prevents transit tunnels from being built."));
        } else {
                out.write("<p class=infohelp>" + _t("No transit tunnels currently active."));
        }
    }

    public void renderPeers(Writer out) throws IOException {
        // count up the peers in the local pools
        ObjectCounter<Hash> lc = new ObjectCounter();
        int tunnelCount = countTunnelsPerPeer(lc);

        // count up the peers in the participating tunnels
        ObjectCounter<Hash> pc = new ObjectCounter();
        int partCount = countParticipatingPerPeer(pc);

        Set<Hash> peers = new HashSet(lc.objects());
        peers.addAll(pc.objects());
        List<Hash> peerList = new ArrayList(peers);
        int peerCount = peerList.size();
        Collections.sort(peerList, new CountryComparator(this._context.commSystem()));

        List<HopConfig> participating = _context.tunnelDispatcher().listParticipatingTunnels();

        if (!participating.isEmpty() || tunnelCount > 0) {
            out.write("<h3 class=tabletitle id=peercount>" + _t("All Tunnels by Peer") +
                      "&nbsp;&nbsp;<a id=refreshPage class=refreshpage style=float:right href=\"/tunnelpeercount\">" + _t("Refresh") + "</a></h3>\n");
            out.write("<table id=tunnelPeerCount data-sortable>");
            out.write("<thead>\n<tr>" +
                      "<th id=country data-sortable>" + _t("Country") + "</th>" +
                      "<th id=router data-sortable data-sort-method=natural>" + _t("Router") + "</th>" +
                      "<th id=version data-sortable data-sort-method=dotsep>" + _t("Version") + "</th>" +
                      "<th id=tier data-sortable data-sort=LMNOPX>" + _t("Tier") + "</th>" +
                      "<th id=address title=\"Primary IP address\">Address</th>");
            if (enableReverseLookups()) {
                out.write("<th id=domain data-sortable>" + _t("Domain") + "</th>");
            }
            out.write("<th class=tcount colspan=2 title=\"Client and Exploratory Tunnels\" data-sortable data-sort-method=number data-sort-column-key=localCount>" +
                      _t("Local") + "</th>");
            if (!participating.isEmpty()) {
                out.write("<th class=tcount colspan=2 data-sortable data-sort-method=number data-sort-column-key=transitCount>" + _t("Transit") + "</th>");
            }
            out.write("<th id=edit data-sort-method=none>" + _t("Edit") + "</th>");
            out.write("</tr>\n</thead>\n<tbody id=allPeers>\n");
            for (Hash h : peerList) {
                char cap = getCapacity(h);
                RouterInfo info = _context.netDb().lookupRouterInfoLocally(h);
                String ip = (info != null) ? net.i2p.util.Addresses.toString(CommSystemFacadeImpl.getValidIP(info)) : null;
                String v = info != null ? info.getOption("router.version") : null;
                String truncHash = h.toBase64().substring(0,4);
                String rl = ip != null ? getCanonicalHostName(ip) : null;
                out.write("<tr class=lazy><td>");
                out.write(peerFlag(h));
                out.write("</td><td>");
                out.write("<span class=routerHash><a href=\"netdb?r=" + h.toBase64().substring(0,6) + "\">");
                out.write(truncHash);
                out.write("</td><td>");
                //out.write(netDbLink(h));
                if (v != null)
                    out.write("<span class=version title=\"" + _t("Show all routers with this version in the NetDb") +
                              "\"><a href=\"/netdb?v=" + DataHelper.stripHTML(v) + "\">" + DataHelper.stripHTML(v) +
                              "</a></span>");
                out.write("</td><td>");
                out.write("<b class=tunnel_cap title=\"" + _t("Bandwidth tier") + "\">" + cap + "</b>");
                out.write("</td><td><span class=ipaddress>");
                if (info != null && ip != null) {
                    if (!ip.toString().equals("null")) {
                        out.write("<a class=script href=\"https://gwhois.org/" + ip.toString() +
                                  "+dns\" target=_blank title=\"" + _t("Lookup address on gwhois.org") +
                                  "\">" + ip.toString() + "</a><noscript>" + ip.toString() + "</noscript>");
                    } else {
                        out.write("&ndash;");
                    }
                } else {
                    out.write("&ndash;");
                }
                out.write("</span>");
                if (enableReverseLookups()) {
                    out.write("</td><td>");
                    if (rl != null && rl.length() != 0 && !ip.toString().equals(rl)) {
                        out.write("<span class=rlookup title=\"");
                        out.write(rl);
                        out.write("\">");
                        if (!ip.toString().equals(rl)) {
                            out.write(CommSystemFacadeImpl.getDomain(rl));
                        }
                    } else {
                        out.write("<span hidden>");
                        out.write("&ndash;");
                    }
                    out.write("</span>");
                }
                out.write("</td><td class=tcount>");
                if (lc.count(h) > 0) {
                    out.write("<span>" + lc.count(h) + "</span>");
                } else {
                    out.write("<span hidden>0</span>");
                }
                out.write("</td><td class=bar data-sort-column-key=localCount>");
                if (lc.count(h) > 0) {
                    out.write("<span class=percentBarOuter><span class=percentBarInner style=\"width:");
                    out.write((lc.count(h) * 100) / tunnelCount);
                    out.write("%\"><span class=percentBarText>");
                    out.write(fmt.format((lc.count(h) * 100) / tunnelCount).replace(".00", ""));
                    out.write("%</span></span></span>");
                } else {
                    out.write("<span hidden>&ndash;</span>");
                }
                if (!participating.isEmpty()) {
                    out.write("</td><td class=tcount data-sort-column-key=transitCount>");
                    if (pc.count(h) > 0) {
                        out.write("<span>" + pc.count(h) + "</span>");
                    } else {
                        out.write("<span hidden>0</span>");
                    }
                    out.write("</td><td class=bar>");
                    if (pc.count(h) > 0) {
                        out.write("<span class=percentBarOuter><span class=percentBarInner style=\"width:");
                        out.write((pc.count(h) * 100) / partCount);
                        out.write("%\"><span class=percentBarText>");
                        out.write(fmt.format((pc.count(h) * 100) / partCount).replace(".00", ""));
                        out.write("%</span></span></span>");
                    } else {
                       out.write("<span hidden>&ndash;</span>");
                    }
                    out.write("</td>");
                } else {
                    out.write("</td>");
                }
                out.write("<td>");
                if (info != null && info.getHash() != null) {
                    out.write("<a class=configpeer href=\"/configpeer?peer=" + info.getHash() + "\" title=\"Configure peer\">" +
                              _t("Edit") + "</a>");
                }
                out.write("</td></tr>\n");
            }
            out.write("</tbody>\n<tfoot><tr class=tablefooter data-sort-method=none>" +
                      "<td colspan=4><b>" + peerCount + ' ' + _t("unique peers") + "</b></td>" +
                      "<td></td>");
            if (enableReverseLookups()) {
                out.write("<td></td>");
            }
            out.write("<td colspan=2><b>" + tunnelCount + ' ' + _t("local") + "</b>");
            if (!participating.isEmpty()) {
                out.write("<td colspan=2><b>" + partCount + ' ' + _t("transit") + "</b>");
            }
            out.write("</td><td></td></tr></tfoot>\n</table>\n");
        } else {
            out.write("<p class=infohelp>" + _t("No local or transit tunnels currently active."));
        }
    }

    public void renderGuide(Writer out) throws IOException {
        out.write("<h3 class=tabletitle>" + _t("Bandwidth Tiers") + "</h3>\n");
        out.write("<table id=tunnel_defs><tbody>");
        out.write("<tr><td>&nbsp;</td>" +
                  "<td><span class=tunnel_cap><b>L</b></span></td>" +
                  "<td>" +_t("{0} shared bandwidth", range(Router.MIN_BW_L, Router.MIN_BW_M)) + "</td>" +
                  "<td><span class=tunnel_cap><b>M</b></span></td>" +
                  "<td>" + _t("{0} shared bandwidth", range(Router.MIN_BW_M, Router.MIN_BW_N)) + "</td>" +
                  "<td>&nbsp;</td></tr>");
        out.write("<tr><td>&nbsp;</td>" +
                  "<td><span class=tunnel_cap><b>N</b></span></td>" +
                  "<td>" + _t("{0} shared bandwidth", range(Router.MIN_BW_N, Router.MIN_BW_O)) + "</td>" +
                  "<td><span class=tunnel_cap><b>O</b></span></td>" +
                  "<td>" + _t("{0} shared bandwidth", range(Router.MIN_BW_O, Router.MIN_BW_P)) + "</td>" +
                  "<td>&nbsp;</td></tr>");
        out.write("<tr><td>&nbsp;</td>" +
                  "<td><span class=tunnel_cap><b>P</b></span></td>" +
                  "<td>" + _t("{0} shared bandwidth", range(Router.MIN_BW_P, Router.MIN_BW_X)) + "</td>" +
                  "<td><span class=tunnel_cap><b>X</b></span></td>" +
                  "<td>" + _t("Over {0} shared bandwidth", Math.round(Router.MIN_BW_X * 1.024f) + " KBps") + "</td>" +
                  "<td>&nbsp;</td></tr>");
        out.write("</tbody></table>");
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
             if (le < 0)
                 le = 0;
             if (re < 0)
                 re = 0;
             if (le < re)
                 return 1;
             if (le > re)
                 return -1;
             return 0;
        }
    }

    private static class TunnelComparatorBySpeed implements Comparator<HopConfig>, Serializable {
         public int compare(HopConfig l, HopConfig r) {
             return (r.getProcessedMessagesCount() - l.getProcessedMessagesCount());
        }
    }


    /** @since 0.9.35 */
    private static class TunnelInfoComparator implements Comparator<TunnelInfo>, Serializable {
         public int compare(TunnelInfo l, TunnelInfo r) {
             long le = l.getExpiration();
             long re = r.getExpiration();
             if (le < re)
//                 return -1;
                 return 1;
             if (le > re)
//                 return 1;
                 return -1;
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
             if (rv != 0)
                 return rv;
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
            if (outPool != null)
                name = outPool.getDestinationNickname();
        }
        if (name != null)
            return DataHelper.escapeHTML(_t(name));
        return ins.getDestination().toBase32();
    }

    /** @since 0.9.35 */
    private void writeGraphLinks(Writer out, TunnelPool in, TunnelPool outPool) throws IOException {
        if (in == null || outPool == null)
            return;
        String irname = in.getRateName();
        String orname = outPool.getRateName();
        RateStat irs = _context.statManager().getRate(irname);
        RateStat ors = _context.statManager().getRate(orname);
        if (irs == null || ors == null)
            return;
        Rate ir = irs.getRate(5*60*1000L);
        Rate or = ors.getRate(5*60*1000L);
        if (ir == null || or == null)
            return;
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
        if (in == null) {
            tunnels = new ArrayList<TunnelInfo>();
        } else {
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
            if (length > maxLength)
                maxLength = length;
        }
        if (tunnels.size() != 0) {
            out.write("<table class=\"tunneldisplay tunnels_client\"><tr><th title=\"" + _t("Inbound or outbound?") + ("\">") + _t("In/Out") +
                      "</th><th>" + _t("Expiry") + "</th><th title=\"" + _t("Data transferred") + "\">" + _t("Data") + "</th><th>" + _t("Gateway") + "</th>");
            if (maxLength > 3) {
            out.write("<th colspan=\"" + (maxLength - 2));
            out.write("\">" + _t("Participants") + "</th>");
            }
            else if (maxLength == 3) {
                out.write("<th>" + _t("Participant") + "</th>");
            }
            if (maxLength > 1) {
                out.write("<th>" + _t("Endpoint") + "</th>");
            }
            out.write("</tr>\n");
        }
        final String tib = _t("Inbound");
        final String tob = _t("Outbound");
        for (int i = 0; i < tunnels.size(); i++) {
            TunnelInfo info = tunnels.get(i);
            long timeLeft = info.getExpiration()-_context.clock().now();
            if (timeLeft <= 0)
                continue; // don't display tunnels in their grace period
            live++;
            boolean isInbound = info.isInbound();
            if (isInbound)
                out.write("<tr><td><span class=inbound title=\"" + tib +
                          "\"><img src=/themes/console/images/inbound.svg alt=\"" + tib + "\"></span></td>");
            else
                out.write("<tr><td><span class=outbound title=\"" + tob +
                          "\"><img src=/themes/console/images/outbound.svg alt=\"" + tob + "\"></span></td>");
            out.write("<td>" + DataHelper.formatDuration2(timeLeft) + "</td>\n");
            int count = info.getProcessedMessagesCount() * 1024 / 1000;
            out.write("<td class=\"cells datatransfer\">");
            if (count > 0)
                out.write("<span class=right>" + count + "</span><span class=left>&#8239;KB</span>");
            out.write("</td>\n");
            int length = info.getLength();
            boolean debug = _context.getBooleanProperty(HelperBase.PROP_ADVANCED);
            for (int j = 0; j < length; j++) {
                Hash peer = info.getPeer(j);
                TunnelId id = (info.isInbound() ? info.getReceiveTunnelId(j) : info.getSendTunnelId(j));
                    char cap = getCapacity(peer);
                if (_context.routerHash().equals(peer)) {
                    if (length < maxLength && length == 1 && isInbound) {
                        // pad before inbound zero hop
                        for (int k = 1; k < maxLength; k++) {
                            out.write("<td></td>");
                        }
                    }
                    // Add empty content placeholders to force alignment.
                    out.write(" <td><span class=\"tunnel_peer tunnel_local\" title=\"" +
                              _t("Locally hosted tunnel") + "\">" + _t("Local") + "</span>");//&nbsp;" +
                              //"<b class=tunnel_cap title=\"" + _t("Bandwidth tier") + "\">" + cap + "</b>");
                    if (debug) {
                        out.write("<span class=tunnel_id title=\"" + _t("Tunnel identity") + "\">" +
                                  (id == null ? "" : "" + id) + "</span>");
                    }
                    out.write("</td>");
                } else {
                    out.write(" <td><span class=tunnel_peer>" + netDbLink(peer) +
                              "</span>");//&nbsp;<b class=tunnel_cap title=\"" + _t("Bandwidth tier") + "\">" + cap + "</b>");
                    if (debug) {
                        out.write("<span class=tunnel_id title=\"" + _t("Tunnel identity") + "\">" +
                                  (id == null ? "" : " " + id) + "</span>");
                    }
                    out.write("</td>");
                }
                if (length < maxLength && ((length == 1 && !isInbound) || j == length - 2)) {
                    // pad out outbound zero hop; non-zero-hop pads in middle
                    for (int k = length; k < maxLength; k++) {
                        out.write("<td></td>");
                    }
                }
            }
            out.write("</tr>\n");

            if (info.isInbound())
                processedIn += count;
            else
                processedOut += count;
        }
        out.write("</table>\n");
        if (live > 0 && ((in != null || (outPool != null)))) {
            List<?> pendingIn = in.listPending();
            List<?> pendingOut = outPool.listPending();
            if ((!pendingIn.isEmpty()) || (!pendingOut.isEmpty())) {
                out.write("<div class=statusnotes><center><b>" + _t("Build in progress") + ":&nbsp;");
                if (in != null) {
                    // PooledTunnelCreatorConfig
//                    List<?> pending = in.listPending();
                    if (!pendingIn.isEmpty()) {
                        out.write("&nbsp;<span class=pending>" + pendingIn.size() + " " + tib + "</span>&nbsp;");
                        live += pendingIn.size();
                    }
                }
                if (outPool != null) {
                    // PooledTunnelCreatorConfig
//                    List<?> pending = outPool.listPending();
                    if (!pendingOut.isEmpty()) {
                        out.write("&nbsp;<span class=pending>" + pendingOut.size() + " " + tob + "</span>&nbsp;");
                        live += pendingOut.size();
                    }
                }
                out.write("</b></center></div>\n");
            }
        }
        if (live <= 0)
            out.write("<div class=statusnotes><center><b>" + _t("none") + "</b></center></div>\n");
        out.write("<div class=statusnotes><center><b>" + _t("Lifetime bandwidth usage") + ":&nbsp;&nbsp;" +
                  DataHelper.formatSize2(processedIn*1024, true).replace("i", "") + "B " + _t("in") + ", " +
                  DataHelper.formatSize2(processedOut*1024, true).replace("i", "") + "B " + _t("out") + "</b></center></div>");
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
                        if (!_context.routerHash().equals(peer))
                            lc.increment(peer);
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
            if (from != null)
                pc.increment(from);
            Hash to = cfg.getSendTo();
            if (to != null)
                pc.increment(to);
        }
        return participating.size();
    }

    private static class CountryComparator implements Comparator<Hash> {
        public CountryComparator(CommSystemFacade comm) {
            this.comm = comm;
        }
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
        RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
        if (info != null) {
            String caps = info.getCapabilities();
            for (int i = 0; i < RouterInfo.BW_CAPABILITY_CHARS.length(); i++) {
                char c = RouterInfo.BW_CAPABILITY_CHARS.charAt(i);
                if (caps.indexOf(c) >= 0)
                    return c;
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
        if (irateKBps < 0 || orateKBps < 0)
            return ConfigNetHelper.DEFAULT_SHARE_KBPS;
        return (int) (pct * Math.min(irateKBps, orateKBps));
    }

    /** translate a string */
    private String _t(String s) {
        return Messages.getString(s, _context);
    }

    /** translate a string */
    public String _t(String s, Object o) {
        return Messages.getString(s, o, _context);
    }

    public String getCanonicalHostName(String hostName) {
        try {
            return InetAddress.getByName(hostName).getCanonicalHostName();
        } catch(IOException exception) {
            return hostName;
        }
    }

}
