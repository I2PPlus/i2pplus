package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.text.DecimalFormat;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.pool.TunnelPool;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.Messages;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;

import net.i2p.router.CommSystemFacade;
import net.i2p.router.transport.CommSystemFacadeImpl;
import net.i2p.util.ObjectCounter;

import java.util.HashSet;

import net.i2p.data.router.RouterAddress;
import net.i2p.util.Addresses;

/**
 *  For /tunnels.jsp, used by TunnelHelper.
 */
class TunnelRenderer {
    private final RouterContext _context;

    private static final int DISPLAY_LIMIT = 500;
    private final DecimalFormat fmt = new DecimalFormat("#0.00");

    public TunnelRenderer(RouterContext ctx) {
        _context = ctx;
    }

    public void renderStatusHTML(Writer out) throws IOException {
        boolean debug = _context.getBooleanProperty(HelperBase.PROP_ADVANCED);
        TunnelPool ei = _context.tunnelManager().getInboundExploratoryPool();
        TunnelPool eo = _context.tunnelManager().getOutboundExploratoryPool();
        out.write("<h3 class=\"tabletitle\" id=\"exploratory\">" + _t("Exploratory"));
        // links are set to float:right in CSS so they will be displayed in reverse order
        out.write(" <a href=\"/configtunnels#exploratory\" title=\"" +
               _t("Configure tunnels") + "\">[" + _t("configure") + "]</a>");
        writeGraphLinks(out, ei, eo);
        out.write("</h3>\n");
        renderPool(out, ei, eo);

        List<Hash> destinations = null;
        Map<Hash, TunnelPool> clientInboundPools = _context.tunnelManager().getInboundClientPools();
        Map<Hash, TunnelPool> clientOutboundPools = _context.tunnelManager().getOutboundClientPools();
        destinations = new ArrayList<Hash>(clientInboundPools.keySet());
        for (int i = 0; i < destinations.size(); i++) {
            Hash client = destinations.get(i);
            boolean isLocal = _context.clientManager().isLocal(client);
            if ((!isLocal) && (!debug))
                continue;
            TunnelPool in = clientInboundPools.get(client);
            TunnelPool outPool = clientOutboundPools.get(client);
            if ((in != null && in.getSettings().getAliasOf() != null) ||
                (outPool != null && outPool.getSettings().getAliasOf() != null)) {
                // skip aliases, we will print a header under the main tunnel pool below
                continue;
            }
            // TODO the following code is duplicated in SummaryHelper
            String name = (in != null) ? in.getSettings().getDestinationNickname() : null;
            if ( (name == null) && (outPool != null) )
                name = outPool.getSettings().getDestinationNickname();
            String b64 = client.toBase64().substring(0, 4);
            String dname;
            if (name == null) {
                name = b64;
                dname = client.toBase32();
            } else {
                dname = DataHelper.escapeHTML(_t(name));
            }
            if (isLocal) {
                out.write("<h3 class=\"");
                if (_context.clientManager().shouldPublishLeaseSet(client))
                    out.write("server ");
                else if ((name.startsWith("Ping") && name.contains("[")) || name.equals("I2Ping"))
                    out.write("ping ");
                else
                    out.write("client ");
                out.write("tabletitle\" ");
                out.write("id=\"" + client.toBase64().substring(0,4) + "\" >");
                out.write(dname);
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
                if (in != null) {
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
                            out.write("<h3 class=\"tabletitle\" ");
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
                }
                renderPool(out, in, outPool);
            }
        }
    }

    public void renderParticipating(Writer out) throws IOException {
        boolean debug = _context.getBooleanProperty(HelperBase.PROP_ADVANCED);
        List<HopConfig> participating = _context.tunnelDispatcher().listParticipatingTunnels();
        if (!participating.isEmpty()) {
            out.write("<h3 class=\"tabletitle\" id=\"participating\">");
            out.write(_t("Participating"));
            if (!debug)
                out.write(' ' + _t("tunnels"));
            out.write("&nbsp;&nbsp;<a class=\"refreshpage\" style=\"float: right;\" href=\"/tunnelsparticipating\">" + _t("Refresh") + "</a></h3>\n");
            int bwShare = getShareBandwidth();
            if (bwShare > 12) {
                if (!participating.isEmpty()) {
                    out.write("<table class=\"tunneldisplay tunnels_participating\" id=\"tunnels_part\" data-sortable><thead><tr><th>" +
                              _t("Role") + "</th><th>" + _t("Expiry") + "</th><th title=\"" + _t("Data transferred") + "\">" +
                              _t("Usage") + "</th><th>" + _t("Rate") + "</th><th>");
                    if (debug)
                        out.write(_t("Receive on") + "</th><th data-sort-method=\"none\">");
                    out.write(_t("From") + "</th><th>");
                    if (debug)
                        out.write(_t("Send on") + "</th><th data-sort-method=\"none\">");
                    out.write(_t("To") + "</th></tr></thead>\n");
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
                    DataHelper.sort(participating, new TunnelComparator());
                    // everything that isn't 'recent' is already in the tunnel.participatingMessageCount stat
                    processed += cfg.getRecentMessagesCount();
                    if (++displayed > DISPLAY_LIMIT)
                        continue;
                    out.write("<tr class=\"lazy\">");
                    if (cfg.getSendTo() == null)
                        out.write("<td class=\"cells obep\" align=\"center\" title=\"" + _t("Outbound Endpoint") + "\">" + _t("Outbound Endpoint") + "</td>");
                    else if (cfg.getReceiveFrom() == null)
                        out.write("<td class=\"cells ibgw\" align=\"center\" title=\"" + _t("Inbound Gateway") + "\">" + _t("Inbound Gateway") + "</td>");
                    else
                        out.write("<td class=\"cells ptcp\" align=\"center\" title=\"" + _t("Participant") + "\">" + _t("Participant") + "</td>");
                    long timeLeft = cfg.getExpiration()-_context.clock().now();
                    if (timeLeft > 0)
//                        out.write("<td class=\"cells expiry\" align=\"center\">" + DataHelper.formatDuration2(timeLeft) + "</td>");
                        out.write("<td class=\"cells expiry\" align=\"center\"><span class=\"right\">" + timeLeft / 1000 +
                                  "</span><span class=\"left\">&#8239;" + _t("sec") + "</span></td>");
                    else
                        out.write("<td class=\"cells\" align=\"center\"><i>" + _t("grace period") + "</i></td>");
                    out.write("<td class=\"cells datatransfer\" align=\"center\"><span class=\"right\">" + (count * 1024 / 1000) +
                              "</span><span class=\"left\">&#8239;KB</span></td>");
                    int lifetime = (int) ((_context.clock().now() - cfg.getCreation()) / 1000);
                    if (lifetime <= 0)
                        lifetime = 1;
                    if (lifetime > 10*60)
                        lifetime = 10*60;
//                    long bps = 1024L * count / lifetime;
                    float bps = 1024 * count / lifetime;
                    float kbps = bps / 1024;
                    out.write("<td class=\"cells bps\" align=\"center\"><span class=\"right\">" + fmt.format(kbps) +
                              "&#8239;</span><span class=\"left\">KB/s</span></td>");
/*
                    out.write("<td class=\"cells bps\" align=\"center\"><span class=\"right\">" + DataHelper.formatSize2(bps, true).replace("i", "")
                        .replace("K", "&#8239;</span><span class=\"left\">K").replace("M", "&#8239;</span><span class=\"left\">M"));
                    if (bps > 1023)
                        out.write(_t("/s"));
                    else
                        out.write("</span><span class=\"left\">" + _t("B/s"));
                    out.write("</span></td>");
*/
                    long recv = cfg.getReceiveTunnelId();
                    if (debug) {
                        if (recv != 0)
                            out.write("<td class=\"cells\" align=\"center\" title=\"" + _t("Tunnel identity") + "\"><span class=\"tunnel_id\">" +
                                      recv + "</span></td>");
                        else
                            out.write("<td class=\"cells\" align=\"center\">" + _t("n/a") + "</td>");
                    }
                    if (cfg.getReceiveFrom() != null)
                        out.write("<td class=\"cells\" align=\"center\"><span class=\"tunnel_peer\">" + netDbLink(cfg.getReceiveFrom()) +
                                  "</span>&nbsp;<b class=\"tunnel_cap\" title=\"" + _t("Bandwidth tier") + "\">" + getCapacity(cfg.getReceiveFrom()) + "</b></td>");
                    else
                        out.write("<td class=\"cells\"></td>");
                    long send = cfg.getSendTunnelId();
                    if (debug) {
                        if (send != 0)
                            out.write("<td class=\"cells\" align=\"center\" title=\"" + _t("Tunnel identity") + "\"><span class=\"tunnel_id\">" +
                                      send + "</span></td>");
                        else
                            out.write("<td class=\"cells\"></td>");
                    }
                    if (cfg.getSendTo() != null)
                        out.write("<td class=\"cells\" align=\"center\"><span class=\"tunnel_peer\">" + netDbLink(cfg.getSendTo()) +
                                  "</span>&nbsp;<b class=\"tunnel_cap\" title=\"" + _t("Bandwidth tier") + "\">" + getCapacity(cfg.getSendTo()) + "</b></td>");
                    else
                        out.write("<td class=\"cells\"></td>");
                    out.write("</tr>\n");
                }
                out.write("</table>\n");
                if (displayed > DISPLAY_LIMIT) {
//                    out.write("<div class=\"statusnotes\"><b>" + _t("Limited display to the {0} tunnels with the highest usage", DISPLAY_LIMIT)  + "</b></div>\n");
                    out.write("<div class=\"statusnotes\"><b>" + _t("Limited display to the {0} most recent tunnels", DISPLAY_LIMIT)  + "</b></div>\n");
                } else if (displayed >= 2) {
                    out.write("<div class=\"statusnotes\"><b>" + _t("Active")  + ":</b>&nbsp" + displayed);
                    if (inactive > 0) {
                        out.write("&nbsp;&bullet;&nbsp;<b>" + _t("Inactive") + ":</b>&nbsp;" + inactive + "&nbsp;&bullet;&nbsp;<b>" +
                                  _t("Total") + ":</b>&nbsp;" + (inactive + displayed));
                    }
                    out.write("</div>");
                } else if (inactive > 0) {
                    out.write("<div class=\"statusnotes\"><b>" + _t("Inactive") + ":</b>&nbsp;" + inactive + "</div>");
                }
                out.write("<div class=\"statusnotes\"><b>" + _t("Lifetime bandwidth usage") + ":</b>&nbsp;" +
                          DataHelper.formatSize2(processed*1024, true).replace("i", "") + "B</div>\n");
            } else { // bwShare < 12K/s
                out.write("<div class=\"statusnotes noparticipate\"><b>" + _t("Not enough shared bandwidth to build participating tunnels.") +
                          "</b> <a href=\"config\">[" + _t("Configure") + "]</a></div>\n");
            }
        } else if (_context.router().isHidden()) {
            out.write("<p class=\"infohelp\">" + _t("Router is currently operating in Hidden Mode which prevents participating tunnels from being built."));
        } else {
            out.write("<p class=\"infohelp\">" + _t("No participating tunnels currently active."));
        }
        out.write("</p>");
    }

    public void renderGuide(Writer out) throws IOException {
        out.write("<h3 class=\"tabletitle\">" + _t("Bandwidth Tiers") + "</h3>\n");
        out.write("<table id=\"tunnel_defs\"><tbody>");
        out.write("<tr><td>&nbsp;</td>" +
                  "<td><span class=\"tunnel_cap\"><b>L</b></span></td>" +
                  "<td>" +_t("{0} shared bandwidth", range(Router.MIN_BW_L, Router.MIN_BW_M)) + "</td>" +
                  "<td><span class=\"tunnel_cap\"><b>M</b></span></td>" +
                  "<td>" + _t("{0} shared bandwidth", range(Router.MIN_BW_M, Router.MIN_BW_N)) + "</td>" +
                  "<td>&nbsp;</td></tr>");
        out.write("<tr><td>&nbsp;</td>" +
                  "<td><span class=\"tunnel_cap\"><b>N</b></span></td>" +
                  "<td>" + _t("{0} shared bandwidth", range(Router.MIN_BW_N, Router.MIN_BW_O)) + "</td>" +
                  "<td><span class=\"tunnel_cap\"><b>O</b></span></td>" +
                  "<td>" + _t("{0} shared bandwidth", range(Router.MIN_BW_O, Router.MIN_BW_P)) + "</td>" +
                  "<td>&nbsp;</td></tr>");
        out.write("<tr><td>&nbsp;</td>" +
                  "<td><span class=\"tunnel_cap\"><b>P</b></span></td>" +
                  "<td>" + _t("{0} shared bandwidth", range(Router.MIN_BW_P, Router.MIN_BW_X)) + "</td>" +
                  "<td><span class=\"tunnel_cap\"><b>X</b></span></td>" +
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
                      "<img src=\"/themes/console/images/svg/outbound.svg\" alt=\"" + tgd + "\" title=\"" + tgd + "\"></a>");
        } else {
            out.write("<a href=\"configstats#" + orname + "\">" +
                      "<img src=\"/themes/console/images/svg/outbound.svg\" alt=\"" + tcg + "\" title=\"" + tcg + "\"></a>");
        }
        if (ir.getSummaryListener() != null) {
            out.write("<a href=\"graph?stat=" + irname + ".300000&amp;w=600&amp;h=200\">" +
                      "<img src=\"/themes/console/images/svg/inbound.svg\" alt=\"" + tgd + "\" title=\"" + tgd + "\"></a> ");
        } else {
            out.write("<a href=\"configstats#" + irname + "\">" +
                      "<img src=\"/themes/console/images/svg/inbound.svg\" alt=\"" + tcg + "\" title=\"" + tcg + "\"></a>");
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
                      "</th><th>" + _t("Expiry") + "</th><th title=\"" + _t("Data transferred") + "\">" + _t("Usage") + "</th><th>" + _t("Gateway") + "</th>");
            if (maxLength > 3) {
            out.write("<th align=\"center\" colspan=\"" + (maxLength - 2));
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
                out.write("<tr><td class=\"cells\" align=\"center\"><span class=\"inbound\" title=\"" + tib +
                          "\"><img src=\"/themes/console/images/svg/inbound.svg\" alt=\"" + tib + "\"></span></td>");
            else
                out.write("<tr><td class=\"cells\" align=\"center\"><span class=\"outbound\" title=\"" + tob +
                          "\"><img src=\"/themes/console/images/svg/outbound.svg\" alt=\"" + tob + "\"></span></td>");
            out.write("<td class=\"cells\" align=\"center\">" + DataHelper.formatDuration2(timeLeft) + "</td>\n");
            int count = info.getProcessedMessagesCount() * 1024 / 1000;
            out.write("<td class=\"cells datatransfer\" align=\"center\">");
            if (count > 0)
                out.write("<span class=\"right\">" + count + "</span><span class=\"left\">&#8239;KB</span>");
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
                            out.write("<td class=\"cells\" align=\"center\"></td>");
                        }
                    }
                    // Add empty content placeholders to force alignment.
                    out.write(" <td class=\"cells\" align=\"center\"><span class=\"tunnel_peer tunnel_local\" title=\"" +
                              _t("Locally hosted tunnel") + "\">" + _t("Local") + "</span>&nbsp;" +
                              "<b class=\"tunnel_cap\" title=\"" + _t("Bandwidth tier") + "\">" + cap + "</b>");
                    if (debug) {
                        out.write("<span class=\"tunnel_id\" title=\"" + _t("Tunnel identity") + "\">" +
                                  (id == null ? "" : "" + id) + "</span>");
                    }
                    out.write("</td>");
                } else {
                    out.write(" <td class=\"cells\" align=\"center\"><span class=\"tunnel_peer\">" + netDbLink(peer) +
                              "</span>&nbsp;<b class=\"tunnel_cap\" title=\"" + _t("Bandwidth tier") + "\">" + cap + "</b>");
                    if (debug) {
                        out.write("<span class=\"tunnel_id\" title=\"" + _t("Tunnel identity") + "\">" +
                                  (id == null ? "" : " " + id) + "</span>");
                    }
                    out.write("</td>");
                }
                if (length < maxLength && ((length == 1 && !isInbound) || j == length - 2)) {
                    // pad out outbound zero hop; non-zero-hop pads in middle
                    for (int k = length; k < maxLength; k++) {
                        out.write("<td class=\"cells\" align=\"center\"></td>");
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
                out.write("<div class=\"statusnotes\"><center><b>" + _t("Build in progress") + ":&nbsp;");
                if (in != null) {
                    // PooledTunnelCreatorConfig
//                    List<?> pending = in.listPending();
                    if (!pendingIn.isEmpty()) {
                        out.write("&nbsp;<span class=\"pending\">" + pendingIn.size() + " " + tib + "</span>&nbsp;");
                        live += pendingIn.size();
                    }
                }
                if (outPool != null) {
                    // PooledTunnelCreatorConfig
//                    List<?> pending = outPool.listPending();
                    if (!pendingOut.isEmpty()) {
                        out.write("&nbsp;<span class=\"pending\">" + pendingOut.size() + " " + tob + "</span>&nbsp;");
                        live += pendingOut.size();
                    }
                }
                out.write("</b></center></div>\n");
            }
        }
        if (live <= 0)
            out.write("<div class=\"statusnotes\"><center><b>" + _t("none") + "</b></center></div>\n");
        out.write("<div class=\"statusnotes\"><center><b>" + _t("Lifetime bandwidth usage") + ":&nbsp;&nbsp;" +
                  DataHelper.formatSize2(processedIn*1024, true).replace("i", "") + "B " + _t("in") + ", " +
                  DataHelper.formatSize2(processedOut*1024, true).replace("i", "") + "B " + _t("out") + "</b></center></div>");
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

        out.write("<h3 class=\"tabletitle\" id=\"peercount\">" + _t("Tunnel Count By Peer") +
                  "&nbsp;&nbsp;<a class=\"refreshpage\" style=\"float: right;\" href=\"/tunnelpeercount\">" + _t("Refresh") + "</a></h3>\n");
        out.write("<table id=\"tunnelPeerCount\" data-sortable>");
        out.write("<thead>\n<tr><th>" + _t("Peer") + "</th><th title=\"Primary IP address\">Address</th><th title=\"Client and Exploratory Tunnels\">" +
                  _t("Local") + "</th><th class=\"bar\">" + _t("% of total") + "</th>");
        if (!participating.isEmpty())
            out.write("<th>" + _t("Participating") + "</th><th class=\"bar\">" + _t("% of total") + "</th>");
        out.write("</tr>\n</thead>\n");
        for (Hash h : peerList) {
            char cap = getCapacity(h);
            RouterInfo info = _context.netDb().lookupRouterInfoLocally(h);
            String ip = info != null ? net.i2p.util.Addresses.toString(CommSystemFacadeImpl.getValidIP(info)) : null;
            String v = info != null ? info.getOption("router.version") : null;
            out.write("<tr class=\"lazy\"><td class=\"cells\" align=\"center\">");
            out.write(netDbLink(h) + "<b class=\"tunnel_cap\" title=\"" + _t("Bandwidth tier") + "\">" + cap + "</b>");
            if (v != null)
                out.write("<span class=\"version\" title=\"" + _t("Show all routers with this version in the NetDb") +
                          "\"><a href=\"/netdb?v=" + DataHelper.stripHTML(v) + "\">" + DataHelper.stripHTML(v) +
                          "</a></span>");
            if (info != null && info.getHash() != null)
                out.write("<a class=\"configpeer\" href=\"/configpeer?peer=" + info.getHash() + "\" title=\"Configure peer\">" +
                          _t("Edit") + "</a>");
            out.write("</td><td class=\"cells\"><span class=\"ipaddress\">");
            if (info != null && ip != null) {
                if (!ip.toString().equals("null"))
                    out.write("<a class=\"script\" href=\"https://gwhois.org/" + ip.toString() + "+dns\" target=\"blank\" title=\"" + _t("Lookup address on gwhois.org") +
                              "\">" + ip.toString() + "</a><noscript>" + ip.toString() + "</noscript>");
                else
                    out.write("<i>" + _t("unknown") + "</i>");
            } else {
                out.write("<i>" + _t("unknown") + "</i>");
            }
            out.write("</span></td><td class=\"cells\" align=\"center\">");
            if (lc.count(h) > 0)
                out.write("" + lc.count(h));
            out.write("</td><td class=\"cells bar\" align=\"center\">");
            if (lc.count(h) > 0) {
                out.write("<span class=\"percentBarOuter\"><span class=\"percentBarInner\" style=\"width:");
                out.write("" + (lc.count(h) * 100) / tunnelCount);
                out.write("%\"><span class=\"percentBarText\">");
                out.write("" + fmt.format((lc.count(h) * 100) / tunnelCount).replace(".00", ""));
                out.write("%</span></span></span>");
            }
            if (!participating.isEmpty()) {
                out.write("</td><td class=\"cells\" align=\"center\">");
                if (pc.count(h) > 0)
                    out.write("" + pc.count(h));
                out.write("</td><td class=\"cells bar\" align=\"center\">");
                if (pc.count(h) > 0) {
                    out.write("<span class=\"percentBarOuter\"><span class=\"percentBarInner\" style=\"width:");
                    out.write("" + (pc.count(h) * 100) / partCount);
                    out.write("%\"><span class=\"percentBarText\">");
                    out.write("" + fmt.format((pc.count(h) * 100) / partCount).replace(".00", ""));
                    out.write("%</span></span></span>");
                }
                out.write("</td>");
            } else {
                out.write("</td>");
            }
            out.write("</tr>\n");
        }
        out.write("<tr class=\"tablefooter\" data-sort-method=\"none\"><td align=\"center\" data-sort-method=\"none\"><b>" + peerCount + ' ' + _t("unique peers") +
                  "</b></td><td></td><td align=\"center\" data-sort-method=\"none\"><b>" + tunnelCount + ' ' + _t("local") +
                  "</b></td><td data-sort-method=\"none\"></td>");
        if (!participating.isEmpty())
            out.write("<td align=\"center\" data-sort-method=\"none\"><b>" + partCount + ' ' + _t("participating") +
                      "</b></td><td data-sort-method=\"none\"></td>");
        out.write("</tr>\n</table>\n");
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
        return _context.commSystem().renderPeerHTML(peer);
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
}
