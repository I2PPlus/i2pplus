package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.util.Comparator;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.data.router.RouterAddress;
import net.i2p.router.transport.Transport;
import net.i2p.router.transport.TransportManager;
import net.i2p.router.transport.ntcp.NTCPConnection;
import net.i2p.router.transport.ntcp.NTCPTransport;
import net.i2p.router.transport.udp.PeerState;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.router.web.HelperBase;
import static net.i2p.router.web.helpers.UDPSorters.*;
import net.i2p.util.SystemVersion;



public class PeerHelper extends HelperBase {
    private int _sortFlags;
    private String _urlBase;
    private String _transport;
    private boolean _graphical;

    private static final String titles[] = {
                                            _x("All Transports"),
                                             "NTCP",
                                             "SSU"
                                           };

    private static final String links[] = {
                                             "",
                                             "?transport=ntcp",
                                             "?transport=ssu",
                                           };

    // Opera doesn't have the char, TODO check UA
    //private static final String THINSP = "&thinsp;/&thinsp;";
    private static final String THINSP = " / ";

    public PeerHelper() {}

    public void setSort(String flags) {
        if (flags != null) {
            try {
                _sortFlags = Integer.parseInt(flags);
            } catch (NumberFormatException nfe) {
                _sortFlags = 0;
            }
        } else {
            _sortFlags = 0;
        }
    }

    public void setUrlBase(String base) { _urlBase = base; }

    /** @since 0.9.38 */
    public void setTransport(String t) { _transport = t; }

    /**
     *  call for non-text-mode browsers
     *  @since 0.9.38
     */
    public void allowGraphical() {
        _graphical = true;
    }

    public String getPeerSummary() {
        try {
            renderStatusHTML(_out, _urlBase, _sortFlags);
            // boring and not worth translating
            //_context.bandwidthLimiter().renderStatusHTML(_out);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return "";
    }

    /**
     *  Warning - blocking, very slow, queries the active UPnP router,
     *  will take many seconds if it has vanished.
     *
     *  @since 0.9.31 moved from TransportManager
     */
    private void renderStatusHTML(Writer out, String urlBase, int sortFlags) throws IOException {
        if (_context.commSystem().isDummy()) {
            out.write("<p class=\"infohelp\">No peer connections available (i2p.vmCommSystem=true)</p>");
            return;
        }
        renderNavBar(out);

        SortedMap<String, Transport> transports = _context.commSystem().getTransports();
        if (_transport != null && !_transport.equals("upnp")) {
            for (Map.Entry<String, Transport> e : transports.entrySet()) {
                String style = e.getKey();
                Transport t = e.getValue();
                if (style.equals("NTCP") && "ntcp".equals(_transport)) {
                    NTCPTransport nt = (NTCPTransport) t;
                    render(nt, out, urlBase, sortFlags);
                } else if (style.equals("SSU") && "ssu".equals(_transport)) {
                    UDPTransport ut = (UDPTransport) t;
                    render(ut, out, urlBase, sortFlags);
                } else {
                    // pluggable (none yet)
                    t.renderStatusHTML(out, urlBase, sortFlags);
                }
            }
        } else if (_transport == null || _transport.equals("all")) {
            StringBuilder buf = new StringBuilder(1024);
            buf.append("<p class=\"infohelp\">")
               .append(_t("Your transport connection limits are automatically set based on your configured bandwidth.")).append(" ");
            buf.append(_t("To override these limits, add the settings {0} and {1}",
                          "<code>i2np.ntcp.maxConnections=nnn</code>",
                          "<code>i2np.udp.maxConnections=nnn</code>")).append(" ");
            if (isAdvanced()) {
                buf.append(_t("on the {0}Advanced Configuration page{1}.",
                              "<a href=\"/configadvanced\">", "</a>"));
            } else {
                buf.append(_t("to your router.config file."));
            }
            buf.append("</p>\n");
            out.write(buf.toString());

            if (!transports.isEmpty() && !isAdvanced()) {
                out.write(getTransportsLegend());
            }

            for (Map.Entry<String, Transport> e : transports.entrySet()) {
            String style = e.getKey();
            Transport t = e.getValue();
            if (style.equals("NTCP")) {
                NTCPTransport nt = (NTCPTransport) t;
                render(nt, out, urlBase, sortFlags);
            } else if (style.equals("SSU")) {
                UDPTransport ut = (UDPTransport) t;
                render(ut, out, urlBase, sortFlags);
            }
        }

/**
            StringBuilder buf = new StringBuilder(4*1024);
            buf.append("<h3 id=\"transports\">").append(_t("Router Transport Addresses")).append("</h3><pre id=\"transports\">\n");
            if (!transports.isEmpty()) {
                for (Transport t : transports.values()) {
                    if (t.hasCurrentAddress()) {
                        for (RouterAddress ra : t.getCurrentAddresses()) {
                            buf.append(ra.toString());
                            buf.append("\n\n");
                        }
                    } else {
                        buf.append(_t("{0} is used for outbound connections only", t.getStyle()));
                        buf.append("\n\n");
                    }
                }
            } else {
                buf.append(_t("none"));
            }
            buf.append("</pre>\n");
            out.write(buf.toString());
        } else if ("upnp".equals(_transport)) {
            // UPnP Status
            _context.commSystem().renderStatusHTML(_out, _urlBase, _sortFlags);
        }
**/
        }
        out.flush();
    }

    /**
     *  @since 0.9.38
     */
    private int getTab() {
        if ("ntcp".equals(_transport))
            return 1;
        if ("ssu".equals(_transport))
            return 2;
        if ("all".equals(_transport))
            return 0;
//        if ("upnp".equals(_transport))
//            return 3;
        return 0;
    }

    /**
     *  @since 0.9.38
     */
    private void renderNavBar(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<div class=\"confignav\" id=\"confignav\">");
        boolean span = _graphical;
        if (!span)
            buf.append("<center>");
        int tab = getTab();
        for (int i = 0; i < titles.length; i++) {
            if (i == tab) {
                // we are there
                if (span)
                    buf.append("<span class=\"tab2\">");
                buf.append(_t(titles[i]));
            } else {
                if (i == 1) {
                    if (!_context.getBooleanPropertyDefaultTrue(TransportManager.PROP_ENABLE_NTCP))
                        continue;
                } else if (i == 2) {
                    if (!_context.getBooleanPropertyDefaultTrue(TransportManager.PROP_ENABLE_UDP))
                        continue;
                }
                // we are not there, make a link
                if (span)
                    buf.append("<span class=\"tab\">");
                buf.append("<a href=\"peers").append(links[i]).append("\">").append(_t(titles[i])).append("</a>");
            }
            if (span)
                buf.append("</span>\n");
            else if (i != titles.length - 1)
                buf.append("&nbsp;&nbsp;\n");
        }
        if (!span)
            buf.append("</center>");
        buf.append("</div>");
        out.write(buf.toString());
    }

    /**
     *  @since 0.9.31 moved from TransportManager
     */
    private final String getTransportsLegend() {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<h3 class=\"tabletitle\">").append(_t("Definitions")).append("</h3>")
           .append("<table id=\"peerdefs\">\n<tr><td>\n")
           .append("<ul><li><b id=\"def.peer\">").append(_t("Peer")).append(":</b> ")
           .append(_t("Remote peer, identified by truncated router hash")).append("</li>\n")
           .append("<li><b id=\"def.dir\">").append(_t("Dir"))
           .append(" (").append(_t("Direction")).append("):</b><br>")
           .append("<span class=\"peer_arrow outbound\"><img alt=\"Outbound\" src=\"/themes/console/images/svg/outbound.svg\"></span> ")
           .append(_t("Outbound connection")).append("<br>\n")
           .append("<span class=\"peer_arrow outbound small\"><img src=\"/themes/console/images/svg/inbound.svg\" alt=\"V\" height=\"8\" width=\"8\"></span> ")
           .append(_t("They offered to introduce us (help peers traverse our firewall)")).append("<br>\n")
           .append("<span class=\"peer_arrow inbound\"><img alt=\"Inbound\" src=\"/themes/console/images/svg/inbound.svg\"></span> ")
           .append(_t("Inbound connection")).append("<br>\n")
           .append("<span class=\"peer_arrow inbound small\"><img src=\"/themes/console/images/svg/outbound.svg\" alt=\"^\" height=\"8\" width=\"8\"></span> ")
           .append(_t("We offered to introduce them (help peers traverse their firewall)")).append("</li>\n")
           .append("<li><b id=\"def.idle\">").append(_t("Idle")).append(":</b> ")
           .append(_t("How long since a packet has been received / sent")).append("</li>")
           .append("<li><b id=\"def.rate\">").append(_t("In/Out")).append("</b>: ")
           .append(_t("Smoothed inbound / outbound transfer rate")).append(" (K/s)").append("</li>\n")
           .append("<li><b id=\"def.up\">").append(_t("Up")).append(":</b> ")
           .append(_t("How long ago connection was established")).append("</li>")
           .append("<li><b id=\"def.skew\">").append(_t("Skew")).append(":</b> ")
           .append(_t("Difference between the peer's clock and our own")).append("</li>\n");
        if (isAdvanced()) {
            buf.append("<li><b id=\"def.cwnd\">CWND").append(" (")
               .append(_t("Congestion window")).append("):</b></br>")
               .append("&nbsp;&nbsp;&bullet; ").append(_t("How many bytes can be sent without acknowledgement")).append("<br>\n")
               .append("&nbsp;&nbsp;&bullet; ").append(_t("Number of sent messages awaiting acknowledgement")).append("<br>\n")
               .append("&nbsp;&nbsp;&bullet; ").append(_t("Maximum number of concurrent messages to send")).append("<br>\n")
               .append("&nbsp;&nbsp;&bullet; ").append(_t("Number of pending sends which exceed window")).append("</li>")
               .append("<li><b id=\"def.ssthresh\">SST (")
               .append(_t("Slow start threshold")).append("):</b> ")
               .append(_t("Maximum packet size before congestion avoidance")).append("</li>\n")
               .append("<li><b id=\"def.rtt\">RTT (").append(_t("Round trip time"))
               .append("):</b> ").append(_t("How long for packet to be sent to peer and back to us")).append("</li>")
               .append("<li><b id=\"def.rto\">RTO (")
               .append(_t("Retransmit timeout")).append("):</b> ")
               .append(_t("How long before peer gives up resending lost packet")).append("</li>\n")
               .append("<li><b id=\"def.mtu\">MTU (")
               .append(_t("Maximum transmission unit")).append("):</b></br>")
               .append("&nbsp;&nbsp;&bullet; ").append(_t("Maximum send packet size")).append("<br>")
               .append("&nbsp;&nbsp;&bullet; ").append(_t("Estimated maximum receive packet size (bytes)")).append("</li>");
        }
        buf.append("<li><b id=\"def.send\">").append(_t("TX")).append(":</b> ")
           .append(_t("Messages sent to peer")).append("</li>\n")
           .append("<li><b id=\"def.recv\">").append(_t("RX")).append(":</b> ")
           .append(_t("Messages received from peer")).append("</li>")
           .append("<li><b id=\"def.resent\">").append(_t("Dup TX")).append(":</b> ")
           .append(_t("Packets retransmitted to peer")).append("</li>\n")
           .append("<li><b id=\"def.dupRecv\">").append(_t("Dup RX")).append(":</b> ")
           .append(_t("Duplicate packets received from peer")).append("</li>\n")
           .append("</ul></td></tr></table>");
        return buf.toString();
    }

    /// begin NTCP

    /**
     *  @since 0.9.31 moved from NTCPTransport
     */
    private void render(NTCPTransport nt, Writer out, String urlBase, int sortFlags) throws IOException {
        TreeSet<NTCPConnection> peers = new TreeSet<NTCPConnection>(getNTCPComparator(sortFlags));
        peers.addAll(nt.getPeers());

        long offsetTotal = 0;
        float bpsSend = 0;
        float bpsRecv = 0;
        long totalUptime = 0;
        long totalSend = 0;
        long totalRecv = 0;

//        if (!isAdvanced()) {
            for (Iterator<NTCPConnection> iter = peers.iterator(); iter.hasNext(); ) {
                 // outbound conns get put in the map before they are established
                 if (!iter.next().isEstablished())
                     iter.remove();
            }
//        }

        StringBuilder buf = new StringBuilder(4*1024);
        buf.append("<div id=\"ntcp\">\n<h3 id=\"ntcpcon\">").append(_t("NTCP connections")).append(":&nbsp; ").append(peers.size());
        buf.append(" / ").append(nt.getMaxConnections());
        //buf.append(". ").append(_t("Timeout")).append(": ").append(DataHelper.formatDuration2(_pumper.getIdleTimeout()));
        buf.append("&nbsp;<span class=\"reachability\">").append(_t("Status")).append(": ")
           .append(nt.getReachabilityStatus().toLocalizedStatusString(_context)).append("</span></h3>\n")
           .append("<div class=\"widescroll\">\n<table id=\"ntcpconnections\">\n");
        if (peers.size() != 0) {
            buf.append("<tr><th class=\"peer\">").append(_t("Peer")).append("</th>" +
                       "<th class=\"direction\" title=\"").append(_t("Direction/Introduction")).append("\">").append(_t("Dir")).append("</th>" +
                       "<th class=\"ipv6\">").append(_t("IPv6")).append("</th>" +
                       "<th class=\"idle\" title=\"").append(_t("Peer inactivity")).append("\">").append(_t("Idle")).append("</th>" +
                       "<th class=\"inout\" title=\"").append(_t("Average inbound/outbound rate (KBps)")).append("\">").append(_t("In/Out")).append ("</th>" +
                       "<th class=\"uptime\" title=\"").append(_t("Duration of connection to peer")).append("\">").append(_t("Up")).append("</th>" +
                       "<th class=\"skew\" title=\"").append(_t("Peer's clockskew relative to our clock")).append("\">").append(_t("Skew")).append("</th>" +
                       "<th class=\"tx\" title=\"").append(_t("Messages sent")).append("\">").append(_t("TX")).append("</th>" +
                       "<th class=\"rx\" title=\"").append(_t("Messages received")).append("\">").append(_t("RX")).append("</th>" +
                       "<th class=\"queue\" title=\"").append(_t("Queued messages to send to peer")).append("\">").append(_t("Out Queue")).append("</th>" +
                       "<th title=\"").append(_t("Is peer backlogged?")).append("\">").append(_t("Backlogged?")).append("</th>");
            buf.append("<th class=\"spacer\">&nbsp;</th>" +
                       //"<th>").append(_t("Reading?")).append("</th>" +
                       "</tr>\n");
        }
        out.write(buf.toString());
        buf.setLength(0);
        long now = _context.clock().now();
        for (NTCPConnection con : peers) {
            buf.append("<tr class=\"lazy\"><td class=\"cells peer\" align=\"left\" nowrap>");
            buf.append(_context.commSystem().renderPeerHTML(con.getRemotePeer().calculateHash()));
            //byte[] ip = getIP(con.getRemotePeer().calculateHash());
            //if (ip != null)
            //    buf.append(' ').append(_context.blocklist().toStr(ip));
            buf.append("</td><td class=\"cells direction\" align=\"center\">");
            if (con.isInbound())
                buf.append("<span class=\"inbound\"><img src=\"/themes/console/images/svg/inbound.svg\" alt=\"Inbound\" title=\"").append(_t("Inbound")).append("\"/></span>");
            else
                buf.append("<span class=\"outbound\"><img src=\"/themes/console/images/svg/outbound.svg\" alt=\"Outbound\" title=\"").append(_t("Outbound")).append("\"/></span>");
            buf.append("</td><td class=\"cells ipv6\" align=\"center\">");
            if (con.isIPv6())
                buf.append("<span class=\"backlogged\">&#x2713;</span>");
            else
                buf.append("");
            buf.append("</td><td class=\"cells idle\" align=\"center\"><span class=\"right\">");
            buf.append(DataHelper.formatDuration2(con.getTimeSinceReceive(now)));
            buf.append("</span>").append(THINSP).append("<span class=\"left\">").append(DataHelper.formatDuration2(con.getTimeSinceSend(now)));
            buf.append("</span></td><td class=\"cells inout\" align=\"center\">");
            String rx = formatRate(bpsRecv/1000).replace(".00", "");
            String tx = formatRate(bpsSend/1000).replace(".00", "");
            if (con.getRecvRate() >= 0.01 || con.getSendRate() >= 0.01) {
                buf.append("<span class=\"right\">");
            if (con.getTimeSinceReceive(now) < 2*60*1000) {
                    float r = con.getRecvRate();
                    buf.append(rx);
                    bpsRecv += r;
                } else {
                    buf.append("0");
                }
                buf.append("</span>").append(THINSP).append("<span class=\"left\">");
            if (con.getTimeSinceSend(now) < 2*60*1000) {
                    float r = con.getSendRate();
                    buf.append(tx);
                    bpsSend += r;
                } else {
                    buf.append("0");
               }
            buf.append("</span>");
            }
            //buf.append(" K/s");
            buf.append("</td><td class=\"cells uptime\" align=\"right\"><span>").append(DataHelper.formatDuration2(con.getUptime())).append("</span>");
            totalUptime += con.getUptime();
            offsetTotal = offsetTotal + con.getClockSkew();
            buf.append("</td><td class=\"cells skew\" align=\"right\">");
            if (con.getClockSkew() > 0) {
                buf.append("<span>").append(DataHelper.formatDuration2(1000 * con.getClockSkew())).append("</span>");
            }
            buf.append("</td>");
            buf.append("<td class=\"cells tx\" align=\"right\"><span>").append(con.getMessagesSent());
            totalSend += con.getMessagesSent();
            buf.append("</span></td><td class=\"cells rx\" align=\"right\"><span>").append(con.getMessagesReceived()).append("</span></td>");
            totalRecv += con.getMessagesReceived();
            long outQueue = con.getOutboundQueueSize();
            buf.append("<td class=\"cells queue\" align=\"center\">");
            if (outQueue > 0) {
                buf.append("<span>").append(outQueue).append("</span>");
            }
            buf.append("</td><td class=\"cells\" align=\"center\">");
            if (con.isBacklogged())
                buf.append("<span class=\"backlogged\">&#x2713;</span>");
            else
                buf.append("&nbsp;");
            //long readTime = con.getReadTime();
            //if (readTime <= 0) {
            //    buf.append("</td> <td class=\"cells\" align=\"center\">0");
            //} else {
            //    buf.append("</td> <td class=\"cells\" align=\"center\">").append(DataHelper.formatDuration(readTime));
            //}
            buf.append("</td>");
            buf.append("<td class=\"cells spacer\">&nbsp;</td>");
            buf.append("</tr>\n");
            out.write(buf.toString());
            buf.setLength(0);
        }

        if (!peers.isEmpty()) {
            buf.append("<tr class=\"tablefooter\"><td align=\"center\" class=\"peer\"><b>")
               .append(ngettext("{0} peer", "{0} peers", peers.size()));
            String rx = formatRate(bpsRecv/1000).replace(".00", "");
            String tx = formatRate(bpsSend/1000).replace(".00", "");
            buf.append("</b></td><td class=\"direction\">&nbsp;</td><td class=\"ipv6\">&nbsp;</td><td class=\"idle\">&nbsp;</td>");
            buf.append("<td class=\"inout\" align=\"center\" nowrap><span class=\"right\"><b>").append(rx).append("</b></span>");
            buf.append(THINSP).append("<span class=\"left\"><b>").append(tx).append("</b></span>");
            buf.append("</td><td class=\"uptime\" align=\"right\"><span><b>").append(DataHelper.formatDuration2(totalUptime/peers.size()));
            buf.append("</b></span></td><td class=\"skew\" align=\"right\"><span><b>").append(DataHelper.formatDuration2(offsetTotal*1000/peers.size()));
            buf.append("</b></span></td>");
            buf.append("<td class=\"tx\" align=\"right\"><span><b>").append(totalSend)
               .append("</b></span></td><td class=\"rx\" align=\"right\"><span><b>").append(totalRecv).append("</b></span></td>");
            buf.append("<td>&nbsp;</td><td>&nbsp;</td>");
            buf.append("<td class=\"spacer\">&nbsp;</td></tr>\n");
        }

        buf.append("</table>\n</div></div>\n");
        out.write(buf.toString());
        buf.setLength(0);
    }

    private static final NumberFormat _rateFmt = new DecimalFormat("#,##0.00");

    private static String formatRate(float rate) {
        synchronized (_rateFmt) { return _rateFmt.format(rate); }
    }

    private Comparator<NTCPConnection> getNTCPComparator(int sortFlags) {
        Comparator<NTCPConnection> rv = null;
        switch (Math.abs(sortFlags)) {
            default:
                rv = AlphaComparator.instance();
        }
        if (sortFlags < 0)
            rv = Collections.reverseOrder(rv);
        return rv;
    }

    private static class AlphaComparator extends PeerComparator {
        private static final AlphaComparator _instance = new AlphaComparator();
        public static final AlphaComparator instance() { return _instance; }
    }

    private static class PeerComparator implements Comparator<NTCPConnection>, Serializable {
        public int compare(NTCPConnection l, NTCPConnection r) {
            if (l == null || r == null)
                throw new IllegalArgumentException();
            // base64 retains binary ordering
            // UM, no it doesn't, but close enough
            return l.getRemotePeer().calculateHash().toBase64().compareTo(r.getRemotePeer().calculateHash().toBase64());
        }
    }

    /// end NTCP
    /// begin SSU

    /**
     *  @since 0.9.31 moved from UDPTransport
     */
    private void render(UDPTransport ut, Writer out, String urlBase, int sortFlags) throws IOException {
        TreeSet<PeerState> peers = new TreeSet<PeerState>(getComparator(sortFlags));
        peers.addAll(ut.getPeers());
        long offsetTotal = 0;

        int bpsIn = 0;
        int bpsOut = 0;
        long uptimeMsTotal = 0;
        long cwinTotal = 0;
        long rttTotal = 0;
        long rtoTotal = 0;
        long sendTotal = 0;
        long recvTotal = 0;
        long resentTotal = 0;
        long dupRecvTotal = 0;
        int numPeers = 0;
        int numRTTPeers = 0;

        StringBuilder buf = new StringBuilder(4*1024);
        buf.append("<div id=\"udp\">\n<h3 id=\"udpcon\">").append(_t("UDP connections")).append(":&nbsp; ").append(peers.size())
           .append(" / ").append(ut.getMaxConnections());
        //buf.append(". ").append(_t("Timeout")).append(": ").append(DataHelper.formatDuration2(_expireTimeout));
        final boolean isAdvanced = isAdvanced();
        if (isAdvanced()) {
            buf.append("&nbsp;<span class=\"reachability\">").append(_t("Status")).append(": ")
               .append(ut.getReachabilityStatus().toLocalizedStatusString(_context)).append("</span>");
        }
        buf.append("</h3>\n");
        buf.append("<div class=\"widescroll\">\n<table id=\"udpconnections\" ");
        if (isAdvanced()) {
            buf.append("class=\"advancedview\"");
        }
        buf.append(">\n");
        if(peers.size() != 0) {
            buf.append("<tr class=\"smallhead\"><th class=\"peer\" nowrap>").append(_t("Peer")).append("<br>");
            if (sortFlags != FLAG_ALPHA)
                appendSortLinks(buf, urlBase, sortFlags, _t("Sort by peer hash"), FLAG_ALPHA);
            buf.append("</th><th class=\"direction\" nowrap title=\"").append(_t("Direction/Introduction")).append("\">").append(_t("Dir"))
               .append("</th><th class=\"ipv6\" nowrap>").append(_t("IPv6"))
               .append("</th><th class=\"idle\" nowrap title=\"").append(_t("Peer inactivity")).append("\">").append(_t("Idle")).append("<br>");
            appendSortLinks(buf, urlBase, sortFlags, _t("Sort by idle inbound"), FLAG_IDLE_IN);
            buf.append("<span style=\"vertical-align: bottom;\"> / </span>");
            appendSortLinks(buf, urlBase, sortFlags, _t("Sort by idle outbound"), FLAG_IDLE_OUT);
            buf.append("</th>");
            buf.append("<th class=\"inout\" nowrap title=\"").append(_t("Average inbound/outbound rate (KBps)")).append("\">").append(_t("In/Out")).append("<br>");
            appendSortLinks(buf, urlBase, sortFlags, _t("Sort by inbound rate"), FLAG_RATE_IN);
            buf.append("<span style=\"vertical-align: bottom;\"> / </span>");
            appendSortLinks(buf, urlBase, sortFlags, _t("Sort by outbound rate"), FLAG_RATE_OUT);
            buf.append("</th>\n");
            buf.append("<th class=\"uptime\" nowrap title=\"").append(_t("Duration of connection to peer")).append("\">")
               .append("<span class=\"peersort\">").append(_t("Up")).append("<br>");
            appendSortLinks(buf, urlBase, sortFlags, _t("Sort by connection uptime"), FLAG_UPTIME);
            buf.append("</span></th><th class=\"skew\" nowrap title=\"").append(_t("Peer's clockskew relative to our clock")).append("\">")
               .append("<span class=\"peersort\">").append(_t("Skew")).append("<br>");
            appendSortLinks(buf, urlBase, sortFlags, _t("Sort by clock skew"), FLAG_SKEW);
            buf.append("</span></th>\n");
            buf.append("<th class=\"tx\" nowrap title=\"").append(_t("Messages sent")).append("\">")
               .append("<span class=\"peersort\">").append(_t("TX")).append("<br>");
            appendSortLinks(buf, urlBase, sortFlags, _t("Sort by packets sent"), FLAG_SEND);
            buf.append("</span></th><th class=\"rx\" nowrap title=\"").append(_t("Messages received")).append("\">")
               .append("<span class=\"peersort\">").append(_t("RX")).append("<br>");
            appendSortLinks(buf, urlBase, sortFlags, _t("Sort by packets received"), FLAG_RECV);
            buf.append("</span></th>\n");
            buf.append("<th class=\"duptx\" nowrap title=\"").append(_t("Retransmitted packets")).append("\">")
               .append("<span class=\"peersort\">").append(_t("Dup TX")).append("<br>");
            appendSortLinks(buf, urlBase, sortFlags, _t("Sort by packets retransmitted"), FLAG_RESEND);
            buf.append("</span></th><th class=\"duprx\" nowrap title=\"").append(_t("Received duplicate packets")).append("\">")
               .append("<span class=\"peersort\">").append(_t("Dup RX")).append("<br>");
            appendSortLinks(buf, urlBase, sortFlags, _t("Sort by packets received more than once"), FLAG_DUP);
            buf.append("</span></th>");
            if (!isAdvanced()) {
                buf.append("<th class=\"spacer\">&nbsp;</th>");
            }
            if (isAdvanced()) {
                buf.append("<th class=\"cwnd\" nowrap title=\"").append(_t("Congestion window")).append("\">CWND<br>");
                appendSortLinks(buf, urlBase, sortFlags, _t("Sort by congestion window"), FLAG_CWND);
                buf.append("</th><th class=\"sst\" nowrap title=\"").append(_t("Slow start threshold")).append("\">")
                   .append("<span class=\"peersort\">SST<br>");
                appendSortLinks(buf, urlBase, sortFlags, _t("Sort by slow start threshold"), FLAG_SSTHRESH);
                buf.append("</span></th>\n");
                buf.append("<th class=\"rtt\" nowrap title=\"").append(_t("Round trip time")).append("\">")
                   .append("<span class=\"peersort\">RTT<br>");
                appendSortLinks(buf, urlBase, sortFlags, _t("Sort by round trip time"), FLAG_RTT);
                //buf.append("</th><th nowrap><a href=\"#def.dev\">").append(_t("Dev")).append("</a><br>");
                //appendSortLinks(buf, urlBase, sortFlags, _t("Sort by round trip time deviation"), FLAG_DEV);
                buf.append("</span></th><th class=\"rto\" nowrap title=\"").append(_t("Retransmission timeout")).append("\">")
                   .append("<span class=\"peersort\">RTO<br>");
                appendSortLinks(buf, urlBase, sortFlags, _t("Sort by retransmission timeout"), FLAG_RTO);
                buf.append("</span></th>\n");
                buf.append("<th class=\"mtu\" nowrap title=\"").append(_t("Maximum transmission unit")).append("\">MTU<br>");
                appendSortLinks(buf, urlBase, sortFlags, _t("Sort by outbound maximum transmit unit"), FLAG_MTU);
                buf.append("</th>");
            }
            buf.append("</tr>\n");
        }
        out.write(buf.toString());
        buf.setLength(0);
        long now = _context.clock().now();
        for (PeerState peer : peers) {
//            if (now-peer.getLastReceiveTime() > 60*60*1000)
            if (now-peer.getLastReceiveTime() > 20*60*1000)
                continue; // don't include old peers
            buf.append("<tr class=\"lazy\"><td class=\"cells peer\" align=\"left\" nowrap>");
            buf.append(_context.commSystem().renderPeerHTML(peer.getRemotePeer()));
            //byte ip[] = peer.getRemoteIP();
            //if (ip != null)
            //    buf.append(' ').append(_context.blocklist().toStr(ip));
            buf.append("</td><td class=\"cells direction\" nowrap align=\"left\">");
            if (peer.isInbound())
                buf.append("<span class=\"inbound\"><img src=\"/themes/console/images/svg/inbound.svg\" alt=\"Inbound\" title=\"").append(_t("Inbound"));
            else
                buf.append("<span class=\"outbound\"><img src=\"/themes/console/images/svg/outbound.svg\" alt=\"Outbound\" title=\"").append(_t("Outbound"));
            buf.append("\"></span>");
            if (peer.getWeRelayToThemAs() > 0)
                buf.append("&nbsp;&nbsp;<span class=\"inbound small\"><img src=\"/themes/console/images/svg/outbound.svg\" height=\"8\" width=\"8\" alt=\"^\" title=\"").append(_t("We offered to introduce them")).append("\">");
            if (peer.getTheyRelayToUsAs() > 0)
                buf.append("&nbsp;&nbsp;<span class=\"outbound small\"><img src=\"/themes/console/images/svg/inbound.svg\" height=\"8\" width=\"8\" alt=\"V\" title=\"").append(_t("They offered to introduce us")).append("\">");
            if (peer.getWeRelayToThemAs() > 0 || peer.getTheyRelayToUsAs() > 0)
                buf.append("</span>");
            if (isAdvanced) {
                boolean appended = false;
                //if (_activeThrottle.isChoked(peer.getRemotePeer())) {
                //    buf.append("<br><i>").append(_t("Choked")).append("</i>");
                //    appended = true;
                //}
                int cfs = peer.getConsecutiveFailedSends();
                if (cfs > 0) {
                    if (!appended) buf.append("<br>");
                    buf.append(" <i>");
                    buf.append(ngettext("{0} fail", "{0} fails", cfs));
                    buf.append("</i>");
                    appended = true;
                }
                if (_context.banlist().isBanlisted(peer.getRemotePeer(), "SSU")) {
                    if (!appended) buf.append("<br>");
                    buf.append(" <i>").append(_t("Banned")).append("</i>");
                }
                //byte[] ip = getIP(peer.getRemotePeer());
                //if (ip != null)
                //    buf.append(' ').append(_context.blocklist().toStr(ip));
            }
            buf.append("</td>");

            buf.append("<td class=\"cells ipv6\" align=\"center\">");
            if (peer.isIPv6())
                buf.append("&#x2713;");
            else
                buf.append("");
            buf.append("</td>");

            long idleIn = Math.max(now-peer.getLastReceiveTime(), 0);
            long idleOut = Math.max(now-peer.getLastSendTime(), 0);

            buf.append("<td class=\"cells idle\" align=\"center\"><span class=\"right\">");
            buf.append(DataHelper.formatDuration2(idleIn));
            buf.append("</span>").append(THINSP);
            buf.append("<span class=\"left\">").append(DataHelper.formatDuration2(idleOut));
            buf.append("</span></td>");

            int recvBps = (idleIn > 15*1000 ? 0 : peer.getReceiveBps());
            int sendBps = (idleOut > 15*1000 ? 0 : peer.getSendBps());

            buf.append("<td class=\"cells inout\" align=\"center\" nowrap>");
            String rx = formatKBps(recvBps).replace(".00", "");
            String tx = formatKBps(sendBps).replace(".00", "");
            if (recvBps > 0 || sendBps > 0) {
                buf.append("<span class=\"right\">").append(rx);
                buf.append("</span>").append(THINSP);
                buf.append("<span class=\"left\">").append(tx).append("</span>");
            }
            //buf.append(" K/s");
            //buf.append(formatKBps(peer.getReceiveACKBps()));
            //buf.append("K/s/");
            //buf.append(formatKBps(peer.getSendACKBps()));
            //buf.append("K/s ");
            buf.append("</td>");

            long uptime = now - peer.getKeyEstablishedTime();

            buf.append("<td class=\"cells uptime\" align=\"right\">");
            buf.append(DataHelper.formatDuration2(uptime));
            buf.append("</td>");

            buf.append("<td class=\"cells skew\" align=\"right\">");
            long skew = peer.getClockSkew();
            buf.append(DataHelper.formatDuration2(skew));
            buf.append("</td>");
            offsetTotal = offsetTotal + skew;
            long sent = peer.getMessagesSent();
            long recv = peer.getMessagesReceived();
            buf.append("<td class=\"cells tx\" align=\"right\"><span class=\"right\">");
            buf.append(sent);
            buf.append("</span></td>");

            buf.append("<td class=\"cells rx\" align=\"right\"><span class=\"right\">");
            buf.append(recv);
            buf.append("</span></td>");
            //double sent = (double)peer.getPacketsPeriodTransmitted();
            //double sendLostPct = 0;
            //if (sent > 0)
            //    sendLostPct = (double)peer.getPacketsRetransmitted()/(sent);
            long resent = peer.getPacketsRetransmitted();
            long dupRecv = peer.getPacketsReceivedDuplicate();
            buf.append("<td class=\"cells duptx\" align=\"right\">");
            //buf.append(formatPct(sendLostPct));
            if (resent > 0) {
                buf.append("<span class=\"right\">").append(resent).append("</span>"); // + "/" + peer.getPacketsPeriodRetransmitted() + "/" + sent);
            }
            //buf.append(peer.getPacketRetransmissionRate());
            buf.append("</td>");

            buf.append("<td class=\"cells duprx\" align=\"right\">");
            if (dupRecv > 0) {
                buf.append("<span class=\"right\">").append(dupRecv).append("</span>"); //formatPct(recvDupPct));
            }
            buf.append("</td>");
            if (!isAdvanced()) {
                buf.append("<td class=\"cells spacer\">&nbsp;</td>");
            }
            long sendWindow = peer.getSendWindowBytes();
            int rtt = peer.getRTT();
            int rto = peer.getRTO();
        if (isAdvanced()) {
            buf.append("<td class=\"cells cwnd\" align=\"center\"><span class=\"right\">");
            buf.append(sendWindow/1024);
            buf.append("K");
            buf.append("</span>").append(THINSP).append("<span class=\"right\">").append(peer.getConcurrentSends());
            buf.append("</span>").append(THINSP).append("<span class=\"right\">").append(peer.getConcurrentSendWindow());
            buf.append("</span>").append(THINSP).append("<span class=\"left\">").append(peer.getConsecutiveSendRejections()).append("</span>");
            if (peer.isBacklogged())
                buf.append("<br><span class=\"peerBacklogged\">").append(_t("backlogged")).append("</span>");
            buf.append("</td>");

            buf.append("<td class=\"cells sst\" align=\"right\">");
            buf.append(peer.getSlowStartThreshold()/1024);
            buf.append("K</td>");

            buf.append("<td class=\"cells rtt\" align=\"right\">");
            if (rtt > 0)
                buf.append(DataHelper.formatDuration2(rtt));
            else
                buf.append("n/a");
            buf.append("</td>");

            //buf.append("<td class=\"cells\" align=\"right\">");
            //buf.append(DataHelper.formatDuration2(peer.getRTTDeviation()));
            //buf.append("</td>");

            buf.append("<td class=\"cells rto\" align=\"right\">");
            buf.append(DataHelper.formatDuration2(rto));
            buf.append("</td>");

            buf.append("<td class=\"cells mtu\" align=\"center\"><span class=\"right\">");
            buf.append(peer.getMTU()).append("</span>").append(THINSP);
            buf.append("<span class=\"left\">").append(peer.getReceiveMTU());

            //.append('/');
            //buf.append(peer.getMTUIncreases()).append('/');
            //buf.append(peer.getMTUDecreases());
            buf.append("</span></td>");
        }

            buf.append("</tr>\n");
            out.write(buf.toString());
            buf.setLength(0);

            bpsIn += recvBps;
            bpsOut += sendBps;

            uptimeMsTotal += uptime;
            cwinTotal += sendWindow;
            if (rtt > 0) {
                rttTotal += rtt;
                numRTTPeers++;
            }
            rtoTotal += rto;

            sendTotal += sent;
            recvTotal += recv;
            resentTotal += resent;
            dupRecvTotal += dupRecv;

            numPeers++;
        }

      if (numPeers > 0) {
        buf.append("<tr class=\"tablefooter\"><td align=\"center\" class=\"peer\"><b>")
           .append(ngettext("{0} peer", "{0} peers", peers.size()))
           .append("</b></td><td class=\"direction\">&nbsp;</td><td class=\"ipv6\">&nbsp;</td><td class=\"idle\">&nbsp;</td>" +
                   "<td align=\"center\" class=\"inout\" nowrap><span class=\"right\"><b>");
        String bwin = formatKBps(bpsIn).replace(".00", "");
        String bwout = formatKBps(bpsOut).replace(".00", "");
        buf.append(bwin).append("</b></span>").append(THINSP);
        buf.append("<span class=\"left\"><b>").append(bwout);
        long x = uptimeMsTotal/numPeers;
        buf.append("</b></span></td>" +
                   "<td class=\"uptime\" align=\"right\"><b>").append(DataHelper.formatDuration2(x));
        x = offsetTotal/numPeers;
        buf.append("</b></td><td class=\"skew\" align=\"right\"><b>").append(DataHelper.formatDuration2(x)).append("</b></td>\n");
        buf.append("<td class=\"tx\" align=\"right\"><b>");
        buf.append(sendTotal).append("</b></td><td class=\"rx\" align=\"right\"><b>").append(recvTotal).append("</b></td>\n" +
                   "<td class=\"duptx\" align=\"right\"><b>").append(resentTotal);
        buf.append("</b></td><td class=\"duprx\" align=\"right\"><b>").append(dupRecvTotal).append("</b></td>");
        if (!isAdvanced()) {
            buf.append("<td class=\"spacer\">&nbsp;</td>");
        }
    if (isAdvanced()) {
        buf.append("<td class=\"cwnd\" align=\"center\"><b>");
        buf.append(cwinTotal/(numPeers*1024) + "K");
        buf.append("</b></td><td class=\"sst\">&nbsp;</td>\n" +
                   "<td class=\"rtt\" align=\"right\"><b>");
        if (numRTTPeers > 0)
            buf.append(DataHelper.formatDuration2(rttTotal/numRTTPeers));
        else
            buf.append("n/a");
        buf.append("</b></td><td class=\"rto\" align=\"right\"><b>");
        buf.append(DataHelper.formatDuration2(rtoTotal/numPeers));
        buf.append("</b></td><td class=\"mtu\" align=\"center\"><b>").append(ut.getMTU(false)).append("</b></td>");
    }
        buf.append("</tr>\n");
/****
        if (sortFlags == FLAG_DEBUG) {
            buf.append("<tr><td colspan=\"16\">");
            buf.append("peersByIdent: ").append(_peersByIdent.size());
            buf.append(" peersByRemoteHost: ").append(_peersByRemoteHost.size());
            int dir = 0;
            int indir = 0;
            for (RemoteHostId rhi : _peersByRemoteHost.keySet()) {
                 if (rhi.getIP() != null)
                     dir++;
                 else
                     indir++;
            }
            buf.append(" pBRH direct: ").append(dir).append(" indirect: ").append(indir);
            buf.append("</td></tr>");
        }
****/
     }  // numPeers > 0
        buf.append("</table>\n</div>\n</div>\n");

      /*****
        long bytesTransmitted = _context.bandwidthLimiter().getTotalAllocatedOutboundBytes();
        // NPE here early
        double averagePacketSize = _context.statManager().getRate("udp.sendPacketSize").getLifetimeAverageValue();
        // lifetime value, not just the retransmitted packets of current connections
        resentTotal = (long)_context.statManager().getRate("udp.packetsRetransmitted").getLifetimeEventCount();
        double nondupSent = ((double)bytesTransmitted - ((double)resentTotal)*averagePacketSize);
        double bwResent = (nondupSent <= 0 ? 0d : ((((double)resentTotal)*averagePacketSize) / nondupSent));
        buf.append("<h3>Percentage of bytes retransmitted (lifetime): ").append(formatPct(bwResent));
        buf.append("</h3><i>(Includes retransmission required by packet loss)</i>\n");
      *****/

        out.write(buf.toString());
        buf.setLength(0);
    }

    private static final DecimalFormat _fmt = new DecimalFormat("#,##0.00");

    private static final String formatKBps(int bps) {
        synchronized (_fmt) {
            return _fmt.format((float)bps/1000);
        }
    }
}
