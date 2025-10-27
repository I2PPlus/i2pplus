package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.math.RoundingMode;
import java.util.Comparator;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.router.transport.Transport;
import net.i2p.router.transport.TransportImpl;
import net.i2p.router.transport.TransportManager;
import net.i2p.router.transport.TransportUtil;
import net.i2p.router.transport.ntcp.NTCPConnection;
import net.i2p.router.transport.ntcp.NTCPTransport;
import net.i2p.router.transport.udp.PeerState;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.router.web.HelperBase;
import static net.i2p.router.web.helpers.UDPSorters.*;
import net.i2p.util.Addresses;
import net.i2p.util.AddressType;


public class PeerHelper extends HelperBase {
    private int _sortFlags;
    private String _urlBase;
    private String _transport;
    private boolean _graphical;

    private static final String titles[] = {
                                            _x("Summary"),
                                             "NTCP",
                                             "SSU"
                                             //"SSU (Advanced)"
                                           };

    private static final String links[] = {
                                             "",
                                             "?transport=ntcp",
                                             "?transport=ssu",
                                             "?transport=ssudebug"
                                           };

    // Opera doesn't have the char, TODO check UA
    private static final String THINSP = " / ";

    public PeerHelper() {}

    public void setSort(String flags) {
        if (flags != null) {
            try {_sortFlags = Integer.parseInt(flags);}
            catch (NumberFormatException nfe) {_sortFlags = 0;}
        } else {_sortFlags = 0;}
    }

    public void setUrlBase(String base) {_urlBase = base;}

    /** @since 0.9.38 */
    public void setTransport(String t) {_transport = t;}

    /**
     *  call for non-text-mode browsers
     *  @since 0.9.38
     */
    public void allowGraphical() {_graphical = true;}

    public String getPeerSummary() {
        try {renderStatusHTML(_out, _urlBase, _sortFlags);}
        catch (IOException ioe) {ioe.printStackTrace();}
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
            out.write("<p class=infohelp>");
            out.write(_t("No peer connections available"));
            out.write(": <code>i2p.vmCommSystem=true</code></p>");
            return;
        }
        renderNavBar(out);

        SortedMap<String, Transport> transports = _context.commSystem().getTransports();
        if (_transport != null) {
            boolean rendered = false;
            for (Map.Entry<String, Transport> e : transports.entrySet()) {
                String style = e.getKey();
                Transport t = e.getValue();
                if (style.equals("NTCP") && "ntcp".equals(_transport)) {
                    NTCPTransport nt = (NTCPTransport) t;
                    render(nt, out, urlBase, sortFlags);
                    rendered = true;
                    break;
                } else if (style.contains("SSU") && "ssu".equals(_transport)) {
                    UDPTransport ut = (UDPTransport) t;
                    render(ut, out, urlBase, sortFlags, false);
                    rendered = true;
                    break;
                } else if (style.contains("SSU") && "ssudebug".equals(_transport)) {
                    UDPTransport ut = (UDPTransport) t;
                    render(ut, out, urlBase, sortFlags, true);
                    rendered = true;
                    break;
                } else if (style.equals(_transport)) {
                    // pluggable (none yet)
                    t.renderStatusHTML(out, urlBase, sortFlags);
                    rendered = true;
                    break;
                }
            }
        } else if (_transport == null) {
            StringBuilder buf = new StringBuilder(1024);
            buf.append("<p class=infohelp>")
               .append(_t("Your transport connection limits are automatically set based on your configured bandwidth.")).append(" ")
               .append(_t("To override these limits, add the settings {0} and {1}",
                          "<code>i2np.ntcp.maxConnections=nnn</code>",
                          "<code>i2np.udp.maxConnections=nnn</code>")).append(" ");
            if (isAdvanced()) {
                buf.append(_t("on the {0}Advanced Configuration page{1}.", "<a href=\"/configadvanced\">", "</a>"));
            } else {buf.append(_t("to your router.config file."));}
            buf.append("</p>\n");
            out.append(buf);
            renderSummary(out);
        }
        out.flush();
    }

    /**
     *  @since 0.9.38
     */
    private int getTab() {
        if ("ntcp".equals(_transport)) {return 1;}
        if ("ssu".equals(_transport)) {return 2;}
        if ("ssudebug".equals(_transport)) {return 3;}
        return 0;
    }

    /**
     *  @since 0.9.56
     */
    private void renderSummary(Writer out) throws IOException {
        Set<AddressType> connected = Addresses.getConnectedAddressTypes();
        TransportUtil.IPv6Config ntcpConfig = TransportUtil.getIPv6Config(_context, "NTCP");
        TransportUtil.IPv6Config ssuConfig = TransportUtil.getIPv6Config(_context, "SSU");
        boolean showIPv4 = connected.contains(AddressType.IPV4) &&
                           (ntcpConfig != TransportUtil.IPv6Config.IPV6_ONLY ||
                            ssuConfig != TransportUtil.IPv6Config.IPV6_ONLY);
        boolean showIPv6 = connected.contains(AddressType.IPV6) &&
                           (ntcpConfig != TransportUtil.IPv6Config.IPV6_DISABLED ||
                            ssuConfig != TransportUtil.IPv6Config.IPV6_DISABLED);

        StringBuilder buf = new StringBuilder(6*1024);
        buf.append("<h3 id=transports>").append(_t("Peer Connections")).append("</h3>\n")
           .append("<table id=transportSummary>\n<thead><tr>")
           .append("<th>").append(_t("Transport")).append("</th>");

        if (showIPv4) {
            buf.append("<th class=\"ipv4 in\">").append(_t("IPv4")).append("&nbsp;<span>").append(_t("Inbound")).append("</span></th>")
               .append("<th class=\"ipv4 out\">").append(_t("IPv4")).append("&nbsp;<span>").append(_t("Outbound")).append("</span></th>");
        }
        if (showIPv6) {
            buf.append("<th class=\"ipv6 in\">").append(_t("IPv6")).append("&nbsp;<span>").append(_t("Inbound")).append("</span></th>")
               .append("<th class=\"ipv6 out\">").append(_t("IPv6")).append("&nbsp;<span>").append(_t("Outbound")).append("</span></th>");
        }

        buf.append("<th title=\"").append(_t("Active connections / Max permitted")).append("\">")
           .append(_t("Connections / Limit")).append("</th>")
           .append("</tr></thead>\n<tbody>\n");

        boolean warnInbound = !_context.router().isHidden() && _context.router().getUptime() > 15*60*1000;
        int[] totals = new int[5];
        int totalLimits = 0;
        int rows = 0;

        SortedMap<String, Transport> transports = _context.commSystem().getTransports();
        for (Map.Entry<String, Transport> e : transports.entrySet()) {
            String style = e.getKey();
            Transport t = e.getValue();
            int[] counts = t.getPeerCounts();

            for (int idx = 0; idx < 8; idx += 4) {
                if (style.equals("NTCP") && idx == 0) {continue;}
                if (style.equals("SSU") && idx == 0) {continue;}

                rows++;
                buf.append("<tr><td><b>").append(style).append(1 + (idx / 4)).append("</b></td>");

                int total = 0;
                for (int i = 0; i < 4; i++) {total += counts[idx + i];}

                for (int i = 0; i < 4; i++) {
                    if (!showIPv4 && i < 2) {continue;}
                    if (!showIPv6 && i >= 2) {break;}

                    int cnt = counts[idx + i];
                    buf.append("<td");
                    if (cnt <= 0 && ((i & 0x01) != 0 || warnInbound)) {buf.append(" class=notice");}
                    else {totals[i + 1] += cnt;}
                    buf.append(">").append(cnt);
                }

                int limit = TransportImpl.getTransportMaxConnections(_context, style);
                totalLimits += limit;

                int percent = limit > 0 ? (int) Math.min(100L * total / limit, 100) : 0;

                buf.append("<td><span class=\"percentBarOuter\">")
                   .append("<span class=\"percentBarInner\" style=\"width:").append(percent).append("%\">")
                   .append("<span class=\"percentBarText\">").append(total).append(" / ").append(limit).append("</span>")
                   .append("</span></span></td></tr>\n");
            }
        }

        buf.append("</tbody>\n");

        if (rows > 1) {
            buf.append("<tfoot><tr class=tablefooter><td><b>").append(_t("Total")).append("</b>");

            for (int i = 1; i < 5; i++) {
                if (!showIPv4 && i > 0 && i < 3) { continue; }
                if (!showIPv6 && i >= 3) { break; }

                int cnt = totals[i];
                buf.append("</td><td");
                if (cnt <= 0 && ((i & 0x01) == 0 || warnInbound)) {buf.append(" class=warn");}
                buf.append(">").append(cnt);
            }

            int totalConnections = 0;
            for (int i = 1; i < 5; i++) {totalConnections += totals[i];}
            int percentTotal = totalLimits > 0 ? (int) Math.min(100L * totalConnections / totalLimits, 100) : 0;

            buf.append("</td><td>").append(totalConnections).append(" / ").append(totalLimits).append("</td></tr></tfoot>\n");
        }
        buf.append("</table>\n");
        out.append(buf);
    }

    /**
     *  @since 0.9.38
     */
    private void renderNavBar(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<div class=confignav id=peerNav>");
        int tab = getTab();
        for (int i = 0; i < titles.length; i++) {
            if (i == tab) {buf.append("<span class=tab2>").append(_t(titles[i]));} // we are there
            else {
                if (i == 1) {
                    if (!_context.getBooleanPropertyDefaultTrue(TransportManager.PROP_ENABLE_NTCP)) {continue;}
                } else if (i == 2) {
                    if (!_context.getBooleanPropertyDefaultTrue(TransportManager.PROP_ENABLE_UDP)) {continue;}
                } else if (i == 3 && !isAdvanced()) {continue;}
                // we are not there, make a link
                buf.append("<span class=tab>").append("<a href=\"peers")
                   .append(links[i]).append("\">").append(_t(titles[i])).append("</a>");
            }
            buf.append("</span>\n");
        }
        buf.append("</div>");
        out.append(buf);
    }

    /// begin NTCP

    /**
     *  @since 0.9.31 moved from NTCPTransport
     */
    private void render(NTCPTransport nt, Writer out, String urlBase, int sortFlags) throws IOException {
        boolean IPv6Enabled = _context.getBooleanProperty("i2np.ntcp.ipv6") != false ||
                              _context.getBooleanProperty("i2np.udp.ipv6") != false;
        TreeSet<NTCPConnection> peers = new TreeSet<NTCPConnection>(getNTCPComparator(sortFlags));
        peers.addAll(nt.getPeers());

        long offsetTotal = 0;
        float bpsSend = 0;
        float bpsRecv = 0;
        long totalUptime = 0;
        long totalSend = 0;
        long totalRecv = 0;
        int notEstablished = 0;

        for (Iterator<NTCPConnection> iter = peers.iterator(); iter.hasNext();) {
             // outbound conns get put in the map before they are established
             if (!iter.next().isEstablished()) {
                 iter.remove();
                 notEstablished++;
             }
        }

        StringBuilder buf = new StringBuilder(4*1024);
        buf.append("<div id=ntcp>\n<h3 id=ntcpcon title=\"")
           .append(_t("Current / maximum permitted"))
           .append("\">")
           .append(_t("NTCP connections"))
           .append(":&nbsp; ")
           .append(nt.countActivePeers())
           .append(" / ")
           .append(nt.getMaxConnections())
           .append("<span id=topCount hidden></span></h3>\n<div class=widescroll>\n<table id=ntcpconnections class=cells data-sortable>\n");
        if (peers.size() != 0) {
            buf.append("<thead><tr><th class=peer>")
               .append(_t("Peer"))
               .append("</th><th class=caps title=\"")
               .append(_t("Peer capabilities"))
               .append("\">")
               .append(_t("Caps"))
               .append("</th><th class=direction title=\"")
               .append(_t("Direction/Introduction"))
               .append("\">")
               .append(_t("Dir"))
               .append("</th>");
            if (IPv6Enabled) {
                buf.append("<th class=ipv6>")
                   .append(_t("IPv6"))
                   .append("</th>");
            }
            buf.append("<th class=idle title=\"")
               .append(_t("Peer inactivity"))
               .append("\">")
               .append(_t("Idle"))
               .append("</th><th class=inout title=\"")
               .append(_t("Average inbound/outbound rate (KBps)"))
               .append("\">")
               .append(_t("In/Out"))
               .append ("</th><th class=uptime title=\"")
               .append(_t("Duration of connection to peer"))
               .append("\">")
               .append(_t("Up"))
               .append("</th><th class=skew title=\"")
               .append(_t("Peer's clockskew relative to our clock"))
               .append("\">")
               .append(_t("Skew"))
               .append("</th><th class=tx title=\"")
               .append(_t("Messages sent"))
               .append("\">")
               .append(_t("TX"))
               .append("</th><th class=rx title=\"")
               .append(_t("Messages received"))
               .append("\">")
               .append(_t("RX"))
               .append("</th><th class=queue title=\"")
               .append(_t("Queued messages to send to peer"))
               .append("\">")
               .append(_t("Out Queue"))
               .append("</th><th class=edit></th></tr></thead>\n<tbody id=peersNTCP>\n");
        }
        out.append(buf);
        buf.setLength(0);

        String inboundLabel = _t("Inbound");
        String outboundLabel = _t("Outbound");
        long now = _context.clock().now();
        int inactive = 0;

        // Threshold logic
        long idleThreshold;
        if (peers.size() >= 300)
            idleThreshold = 60 * 1000;
        else if (peers.size() >= 100)
            idleThreshold = 3 * 60 * 1000;
        else
            idleThreshold = 10 * 60 * 1000;

        List<String> rowBuffers = new ArrayList<>();

        for (NTCPConnection con : peers) {
            if (con.getTimeSinceReceive(now) > idleThreshold || con.getTimeSinceSend(now) > idleThreshold) {
                inactive++;
                continue;
            }

            StringBuilder rowBuf = new StringBuilder(512); // preallocate enough for one row
            renderNTCPPeerRow(con, rowBuf, now, IPv6Enabled, inboundLabel, outboundLabel);
            rowBuffers.add(rowBuf.toString());

            // Accumulate totals
            float recvRate = con.getRecvRate();
            float sendRate = con.getSendRate();
            if (recvRate >= 0.01) bpsRecv += recvRate;
            if (sendRate >= 0.01) bpsSend += sendRate;
            totalUptime += con.getUptime();
            offsetTotal += con.getClockSkew();
            totalSend += con.getMessagesSent();
            totalRecv += con.getMessagesReceived();
        }

        // Append all rows at once
        for (String row : rowBuffers) {
            out.append(row);
        }
        buf.append("</tbody>");

        if (!peers.isEmpty()) {
            String rx = formatRate(bpsRecv/1000).replace(".00", "");
            String tx = formatRate(bpsSend/1000).replace(".00", "");
            buf.append("<tfoot><tr class=tablefooter><td class=peer colspan=")
               .append(IPv6Enabled ? "5" : "4")
               .append("><b>")
               .append(ngettext("{0} peer", "{0} peers", nt.countActivePeers()))
               .append("</b></td><td class=inout nowrap><span class=right><b>")
               .append(rx)
               .append("</b></span>")
               .append(THINSP)
               .append("<span class=left><b>")
               .append(tx)
               .append("</b></span></td><td class=uptime><span><b>")
               .append(DataHelper.formatDuration2(totalUptime/peers.size()))
               .append("</b></span></td><td class=skew><span><b>")
               .append(DataHelper.formatDuration2(offsetTotal*1000/peers.size()))
               .append("</b></span></td><td class=tx><span><b>")
               .append(totalSend)
               .append("</b></span></td><td class=rx><span><b>")
               .append(totalRecv)
               .append("</b></span></td><td colspan=2></td></tr>\n");
        }
        buf.append("</tfoot></table>\n</div></div>\n");
        out.append(buf);
        buf.setLength(0);
    }

    /**
     * Render a single NTCP peer connection row into the buffer.
     */
    private void renderNTCPPeerRow(NTCPConnection con, StringBuilder buf, long now, boolean IPv6Enabled,
                                   String inboundLabel, String outboundLabel) {
        Hash h = con.getRemotePeer().calculateHash();

        buf.append("<tr class=lazy><td class=peer>")
           .append(_context.commSystem().renderPeerHTML(h, false))
           .append("</td><td class=caps>")
           .append(_context.commSystem().renderPeerCaps(h, false))
           .append("</td><td class=direction>");

        if (con.isInbound()) {
            buf.append("<span class=inbound><img src=/themes/console/images/inbound.svg alt=\"")
               .append(inboundLabel)
               .append("\" title=\"")
               .append(inboundLabel)
               .append("\"/></span>");
        } else {
            buf.append("<span class=outbound><img src=/themes/console/images/outbound.svg alt=\"")
               .append(outboundLabel)
               .append("\" title=\"")
               .append(outboundLabel)
               .append("\"/></span>");
        }

        buf.append("</td>");

        if (IPv6Enabled) {
            buf.append("<td class=ipv6>");
            if (con.isIPv6()) {
                buf.append("<span class=isIPv6>&#x2713;</span>");
            }
            buf.append("</td>");
        }

        long idleRecv = con.getTimeSinceReceive(now);
        long idleSend = con.getTimeSinceSend(now);
        float recvRate = con.getRecvRate();
        float sendRate = con.getSendRate();

        buf.append("<td class=idle><span class=right>")
           .append(DataHelper.formatDuration2(idleRecv))
           .append("</span>")
           .append(THINSP)
           .append("<span class=left>")
           .append(DataHelper.formatDuration2(idleSend))
           .append("</span></td><td class=inout>");

        if (recvRate >= 0.01 || sendRate >= 0.01) {
            buf.append("<span class=right>")
               .append(formatRate(recvRate).replace(".00", ""))
               .append("</span>")
               .append(THINSP)
               .append("<span class=left>")
               .append(formatRate(sendRate).replace(".00", ""))
               .append("</span>");
        } else {
            buf.append("--");
        }

        buf.append("</td><td class=uptime><span>")
           .append(DataHelper.formatDuration2(con.getUptime()))
           .append("</span></td><td class=skew>");

        long skew = con.getClockSkew();
        if (skew > 0) {
            buf.append("<span>")
               .append(DataHelper.formatDuration2(1000 * skew))
               .append("</span>");
        }

        long messagesSent = con.getMessagesSent();
        long messagesReceived = con.getMessagesReceived();
        long outQueue = con.getOutboundQueueSize();

        buf.append("</td><td class=tx><span>")
           .append(messagesSent)
           .append("</span></td><td class=rx><span>")
           .append(messagesReceived)
           .append("</span></td><td class=queue>");

        if (outQueue > 0) {
            buf.append("<span class=qmsg title=\"Queued messages: ")
               .append(outQueue)
               .append("\">")
               .append(outQueue)
               .append("</span>");
        }

        if (con.isBacklogged()) {
            buf.append("&nbsp;<span class=backlogged title=\"")
               .append(_t("Connection is backlogged"))
               .append("\">!!</span>");
        }

        buf.append("</td><td class=edit><a class=configpeer href=\"/configpeer?peer=")
           .append(h.toBase64())
           .append("\" title=\"")
           .append(_t("Configure peer"))
           .append("\" alt=\"[")
           .append(_t("Configure peer"))
           .append("]\">")
           .append(_t("Edit"))
           .append("</a></td></tr>\n");
    }

    private static final NumberFormat _rateFmt = new DecimalFormat("#,##0.00");
    static {_rateFmt.setRoundingMode(RoundingMode.HALF_UP);}

    private static String formatRate(float rate) {
        if (rate < 0.005f) {return "0";}
        synchronized (_rateFmt) {return _rateFmt.format(rate);}
    }

    private Comparator<NTCPConnection> getNTCPComparator(int sortFlags) {
        Comparator<NTCPConnection> rv = null;
        switch (Math.abs(sortFlags)) {
            default:
                rv = AlphaComparator.instance();
        }
        if (sortFlags < 0) {rv = Collections.reverseOrder(rv);}
        return rv;
    }

    private static class AlphaComparator extends PeerComparator {
        private static final AlphaComparator _instance = new AlphaComparator();
        public static final AlphaComparator instance() {return _instance;}
    }

    private static class PeerComparator implements Comparator<NTCPConnection>, Serializable {
        public int compare(NTCPConnection l, NTCPConnection r) {
            if (l == null || r == null) {throw new IllegalArgumentException();}
            return HashComparator.comp(l.getRemotePeer().calculateHash(), r.getRemotePeer().calculateHash());
        }
    }

    /// end NTCP
    /// begin SSU

    /**
     *  @since 0.9.31 moved from UDPTransport
     */
    private void render(UDPTransport ut, Writer out, String urlBase, int sortFlags, boolean debugmode) throws IOException {
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
        buf.append("<div id=udp>\n<h3 id=udpcon title=\"")
           .append(_t("Current / maximum permitted"))
           .append("\">")
           .append(_t("UDP connections"))
           .append(":&nbsp; ")
           .append(ut.countActivePeers())
           .append(" / ")
           .append(ut.getMaxConnections());
        if (!debugmode) {
            buf.append("&nbsp;<span id=ssuadv><a href=\"/peers?transport=ssudebug\">[")
               .append(_t("Advanced View"))
               .append("]</a></span>");
        }
        buf.append("<span id=topCount hidden></span></h3>\n<div class=widescroll>\n<table id=udpconnections class=\"");
        if (debugmode) {buf.append("advancedview ");}
        buf.append("cells\" data-sortable>\n");
        if (peers.size() != 0) {
            buf.append("<thead><tr class=smallhead><th class=peer>")
               .append(_t("Peer"));
            if (debugmode) {
                buf.append("<br>");
                if (sortFlags != FLAG_ALPHA) {
                    appendSortLinks(buf, urlBase, sortFlags, _t("Sort by peer hash"), FLAG_ALPHA);
                }
            }
            buf.append("</th><th class=caps>")
               .append(_t("Caps"))
               .append("</th><th class=direction title=\"")
               .append(_t("Direction/Introduction"))
               .append("\">")
               .append(_t("Dir"));
            if (debugmode) {
                buf.append("</th><th class=ipv6>")
                   .append(_t("IPv6"));
            }
            buf.append("</th><th class=idle title=\"")
               .append(_t("Peer inactivity"))
               .append("\">")
               .append(_t("Idle"));
            if (debugmode) {
                buf.append("<br>");
                appendSortLinks(buf, urlBase, sortFlags, _t("Sort by idle inbound"), FLAG_IDLE_IN);
                buf.append("<span style=vertical-align:bottom> / </span>");
                appendSortLinks(buf, urlBase, sortFlags, _t("Sort by idle outbound"), FLAG_IDLE_OUT);
            }
            buf.append("</th><th class=inout title=\"")
               .append(_t("Average inbound/outbound rate (KBps)"))
               .append("\">")
               .append(_t("In/Out"));
            if (debugmode) {
                buf.append("<br>");
                appendSortLinks(buf, urlBase, sortFlags, _t("Sort by inbound rate"), FLAG_RATE_IN);
                buf.append("<span style=vertical-align:bottom> / </span>");
                appendSortLinks(buf, urlBase, sortFlags, _t("Sort by outbound rate"), FLAG_RATE_OUT);
            }
            buf.append("</th><th class=uptime title=\"")
               .append(_t("Duration of connection to peer"))
               .append("\"><div class=peersort>")
               .append(_t("Up"));
            if (debugmode) {
                buf.append("<br>");
                appendSortLinks(buf, urlBase, sortFlags, _t("Sort by connection uptime"), FLAG_UPTIME);
            }
            buf.append("</div></th><th class=skew title=\"")
               .append(_t("Peer's clockskew relative to our clock"))
               .append("\"><div class=peersort>")
               .append(_t("Skew"));
            if (debugmode) {
                buf.append("<br>");
                appendSortLinks(buf, urlBase, sortFlags, _t("Sort by clock skew"), FLAG_SKEW);
            }
            buf.append("</div></th><th class=tx title=\"")
               .append(_t("Messages sent"))
               .append("\"><div class=peersort>")
               .append(_t("TX"));
            if (debugmode) {
                buf.append("<br>");
                appendSortLinks(buf, urlBase, sortFlags, _t("Sort by packets sent"), FLAG_SEND);
            }
            buf.append("</div></th><th class=rx title=\"")
               .append(_t("Messages received"))
               .append("\"><div class=peersort>")
               .append(_t("RX"));
            if (debugmode) {
                buf.append("<br>");
                appendSortLinks(buf, urlBase, sortFlags, _t("Sort by packets received"), FLAG_RECV);
            }
            buf.append("</div></th><th class=duptx title=\"")
               .append(_t("Retransmitted packets"))
               .append("\"><div class=peersort>")
               .append(_t("Dup TX"));
            if (debugmode) {
                buf.append("<br>");
                appendSortLinks(buf, urlBase, sortFlags, _t("Sort by packets retransmitted"), FLAG_RESEND);
            }
            buf.append("</div></th><th class=duprx title=\"")
               .append(_t("Received duplicate packets"))
               .append("\"><div class=peersort>")
               .append(_t("Dup RX"));
            if (debugmode) {
                buf.append("<br>");
                appendSortLinks(buf, urlBase, sortFlags, _t("Sort by packets received more than once"), FLAG_DUP);
            }
            buf.append("</div></th>");
            if (debugmode) {
                buf.append("<th class=cwnd title=\"")
                   .append(_t("Congestion window"))
                   .append("\">CWND<br>");
                appendSortLinks(buf, urlBase, sortFlags, _t("Sort by congestion window"), FLAG_CWND);
                buf.append("</th><th class=sst title=\"")
                   .append(_t("Slow start threshold"))
                   .append("\"><div class=peersort>SST<br>");
                appendSortLinks(buf, urlBase, sortFlags, _t("Sort by slow start threshold"), FLAG_SSTHRESH);
                buf.append("</div></th>\n<th class=rtt title=\"")
                   .append(_t("Round trip time"))
                   .append("\"><div class=peersort>RTT<br>");
                appendSortLinks(buf, urlBase, sortFlags, _t("Sort by round trip time"), FLAG_RTT);
                buf.append("</div></th><th class=rto title=\"")
                   .append(_t("Retransmission timeout"))
                   .append("\"><div class=peersort>RTO<br>");
                appendSortLinks(buf, urlBase, sortFlags, _t("Sort by retransmission timeout"), FLAG_RTO);
                buf.append("</div></th>\n<th class=mtu title=\"")
                   .append(_t("Maximum transmission unit"))
                   .append("\">MTU<br>");
                appendSortLinks(buf, urlBase, sortFlags, _t("Sort by outbound maximum transmit unit"), FLAG_MTU);
                buf.append("</th>");
            }
            buf.append("<th class=edit data-sort-method=none></th></tr></thead>\n<tbody id=peersSSU>\n");
        }
        out.append(buf);
        buf.setLength(0);
        long now = _context.clock().now();
        String inboundLabel = _t("Inbound");
        String outboundLabel = _t("Outbound");
        List<String> rowBuffers = new ArrayList<>();

        for (PeerState peer : peers) {
            if (peers.size() >= 300 && now - peer.getLastReceiveTime() > 60 * 1000) {
                continue; // skip
            }
            if (peers.size() >= 100 && now - peer.getLastReceiveTime() > 3 * 60 * 1000) {
                continue; // skip
            }
            if (now - peer.getLastReceiveTime() > 10 * 60 * 1000) {
                continue; // skip
            }

            StringBuilder rowBuf = new StringBuilder(512);
            renderUDPPeerRow(peer, rowBuf, now, debugmode, inboundLabel, outboundLabel);
            rowBuffers.add(rowBuf.toString());

            // Accumulate totals
            int recvBps = peer.getReceiveBps(now);
            int sendBps = peer.getSendBps(now);
            bpsIn += recvBps;
            bpsOut += sendBps;
            uptimeMsTotal += now - peer.getKeyEstablishedTime();
            cwinTotal += peer.getSendWindowBytes();
            if (peer.getRTT() > 0) {
                rttTotal += peer.getRTT();
                numRTTPeers++;
            }
            rtoTotal += peer.getRTO();
            sendTotal += peer.getMessagesSent();
            recvTotal += peer.getMessagesReceived();
            resentTotal += peer.getPacketsRetransmitted();
            dupRecvTotal += peer.getPacketsReceivedDuplicate();
            numPeers++;
        }

        for (String row : rowBuffers) {out.append(row);}
        buf.append("</tbody>\n");

        if (numPeers > 0) {
            String bwin = formatKBps(bpsIn).replace(".00", "");
            String bwout = formatKBps(bpsOut).replace(".00", "");
            buf.append("<tfoot><tr class=tablefooter><td class=peer colspan=");
            long x = uptimeMsTotal/numPeers;
            if (debugmode) {buf.append("5");}
            else {buf.append("4");}
            buf.append("><b>")
               .append(ngettext("{0} peer", "{0} peers", ut.countActivePeers()))
               .append("</b></td><td class=inout nowrap><span class=right><b>")
               .append(bwin)
               .append("</b></span>")
               .append(THINSP)
               .append("<span class=left><b>")
               .append(bwout)
               .append("</b></span></td><td class=uptime><b>")
               .append(DataHelper.formatDuration2(x));
            x = offsetTotal/numPeers;
            buf.append("</b></td><td class=skew><b>")
               .append(DataHelper.formatDuration2(x))
               .append("</b></td>\n<td class=tx><b>")
               .append(sendTotal)
               .append("</b></td><td class=rx><b>")
               .append(recvTotal)
               .append("</b></td>\n<td class=duptx><b>")
               .append(resentTotal)
               .append("</b></td><td class=duprx><b>")
               .append(dupRecvTotal)
               .append("</b></td>");
            if (debugmode) {
                buf.append("<td class=cwnd><b>")
                   .append(cwinTotal/(numPeers*1024))
                   .append("K</b></td><td class=sst>&nbsp;</td><td class=rtt><b>");
                if (numRTTPeers > 0) {
                    buf.append(DataHelper.formatDuration2(rttTotal/numRTTPeers));
                } else {buf.append("n/a");}
                buf.append("</b></td><td class=rto><b>")
                   .append(DataHelper.formatDuration2(rtoTotal/numPeers))
                   .append("</b></td><td class=mtu><b>")
                   .append(ut.getMTU(false))
                   .append("</b></td>");
            }
            buf.append("<td class=edit></td></tr></tfoot>\n");
        }  // numPeers > 0
        buf.append("</table>\n</div>\n</div>\n");
        out.append(buf);
        buf.setLength(0);
    }

    /**
     * Render a single UDP peer row into the buffer.
     */
    private void renderUDPPeerRow(PeerState peer, StringBuilder buf, long now, boolean debugmode,
                                  String inboundLabel, String outboundLabel) {
        Hash h = peer.getRemotePeer().calculateHash();

        buf.append("<tr class=lazy><td class=peer nowrap>")
           .append(_context.commSystem().renderPeerHTML(peer.getRemotePeer(), false))
           .append("</td><td class=caps>")
           .append(_context.commSystem().renderPeerCaps(peer.getRemotePeer(), false))
           .append("</td><td class=direction nowrap>");

        if (peer.isInbound()) {
            buf.append("<span class=inbound><img src=/themes/console/images/inbound.svg alt=\"")
               .append(inboundLabel)
               .append("\" title=\"")
               .append(inboundLabel)
               .append("\">");
        } else {
            buf.append("<span class=outbound><img src=/themes/console/images/outbound.svg alt=\"")
               .append(outboundLabel)
               .append("\" title=\"")
               .append(outboundLabel)
               .append("\">");
        }

        buf.append("</span>");

        if (peer.getWeRelayToThemAs() > 0) {
            buf.append("&nbsp;&nbsp;<span class=\"inbound small\"><img src=/themes/console/images/outbound.svg width=8 height=8 alt=\"^\" title=\"")
               .append(_t("We offered to introduce them"))
               .append("\">");
        }
        if (peer.getTheyRelayToUsAs() > 0) {
            buf.append("&nbsp;&nbsp;<span class=\"outbound small\"><img src=/themes/console/images/inbound.svg width=8 height=8 alt=\"V\" title=\"")
               .append(_t("They offered to introduce us"))
               .append("\">");
        }

        if (peer.getWeRelayToThemAs() > 0 || peer.getTheyRelayToUsAs() > 0) {
            buf.append("</span>");
        }

        if (debugmode) {
            boolean appended = false;
            int cfs = peer.getConsecutiveFailedSends();
            if (cfs > 0) {
                buf.append("<br><i>")
                   .append(ngettext("{0} fail", "{0} fails", cfs))
                   .append("</i>");
                appended = true;
            }
            if (_context.banlist().isBanlisted(peer.getRemotePeer(), "SSU")) {
                if (!appended) {
                    buf.append("<br>");
                }
                buf.append(" <i>")
                   .append(_t("Banned"))
                   .append("</i>");
            }
        }

        buf.append("</td>");

        if (debugmode) {
            buf.append("<td class=ipv6>");
            if (peer.isIPv6()) {
                buf.append("&#x2713;");
            } else {
                buf.append("&nbsp;");
            }
            buf.append("</td>");
        }

        long idleIn = Math.max(now - peer.getLastReceiveTime(), 0);
        long idleOut = Math.max(now - peer.getLastSendTime(), 0);
        int recvBps = peer.getReceiveBps(now);
        int sendBps = peer.getSendBps(now);
        String rx = formatKBps(recvBps).replace(".00", "");
        String tx = formatKBps(sendBps).replace(".00", "");

        buf.append("<td class=idle><span class=right>")
           .append(DataHelper.formatDuration2(idleIn))
           .append("</span>")
           .append(THINSP)
           .append("<span class=left>")
           .append(DataHelper.formatDuration2(idleOut))
           .append("</span></td><td class=inout nowrap>");

        if (recvBps > 0 || sendBps > 0) {
            buf.append("<span class=right>")
               .append(rx)
               .append("</span>")
               .append(THINSP)
               .append("<span class=left>")
               .append(tx)
               .append("</span>");
        }

        long uptime = now - peer.getKeyEstablishedTime();
        long skew = peer.getClockSkew();
        long sent = peer.getMessagesSent();
        long recv = peer.getMessagesReceived();
        long resent = peer.getPacketsRetransmitted();
        long dupRecv = peer.getPacketsReceivedDuplicate();

        buf.append("</td><td class=uptime>")
           .append(DataHelper.formatDuration2(uptime))
           .append("</td><td class=skew>")
           .append(DataHelper.formatDuration2(skew))
           .append("</td><td class=tx><span class=right>")
           .append(sent)
           .append("</span></td><td class=rx><span class=right>")
           .append(recv)
           .append("</span></td><td class=duptx>");

        if (resent > 0) {
            buf.append("<span class=right>").append(resent).append("</span>");
        }

        buf.append("</td><td class=duprx>");

        if (dupRecv > 0) {
            buf.append("<span class=right>").append(dupRecv).append("</span>");
        }

        buf.append("</td>");

        long sendWindow = peer.getSendWindowBytes();
        int rtt = peer.getRTT();
        int rto = peer.getRTO();

        if (debugmode) {
            buf.append("<td class=cwnd><span class=right>")
               .append(sendWindow / 1024)
               .append("K</span>")
               .append(THINSP)
               .append("<span class=right>")
               .append(peer.getConcurrentSends())
               .append("</span>")
               .append(THINSP)
               .append("<span class=right>")
               .append(peer.getConcurrentSendWindow())
               .append("</span>")
               .append(THINSP)
               .append("<span class=left>")
               .append(peer.getConsecutiveSendRejections())
               .append("</span>");

            if (peer.isBacklogged()) {
                buf.append("<br><span class=peerBacklogged>")
                   .append(_t("backlogged"))
                   .append("</span>");
            }

            buf.append("</td><td class=sst>")
               .append(peer.getSlowStartThreshold() / 1024)
               .append("K</td><td class=rtt>");

            if (rtt > 0) {
                buf.append(DataHelper.formatDuration2(rtt));
            } else {
                buf.append("n/a");
            }

            buf.append("</td><td class=rto>")
               .append(DataHelper.formatDuration2(rto))
               .append("</td><td class=mtu><span class=right>")
               .append(peer.getMTU())
               .append("</span>")
               .append(THINSP)
               .append("<span class=left>")
               .append(peer.getReceiveMTU())
               .append("</span></td>");
        }

        buf.append("<td class=edit><a class=configpeer href=\"/configpeer?peer=")
           .append(h.toBase64())
           .append("\" title=\"")
           .append(_t("Configure peer"))
           .append("\" alt=\"[")
           .append(_t("Configure peer"))
           .append("]\">")
           .append(_t("Edit"))
           .append("</a></td></tr>\n");
    }

    /**
     *  @since 0.9.31 moved from TransportManager
     */
    private final String getTransportsLegend() {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<h3 class=tabletitle>")
           .append(_t("Definitions"))
           .append("</h3><table id=peerdefs>\n<tr><td>\n<ul><li><b id=def.peer>")
           .append(_t("Peer"))
           .append(":</b> ")
           .append(_t("Remote peer, identified by truncated router hash"))
           .append("</li>\n<li><b id=def.dir>")
           .append(_t("Dir"))
           .append(" (")
           .append(_t("Direction"))
           .append("):</b><br><span class=\"peer_arrow outbound\"><img alt=Outbound src=/themes/console/images/outbound.svg></span> ")
           .append(_t("Outbound connection"))
           .append("<br>\n<span class=\"peer_arrow outbound small\"><img src=/themes/console/images/inbound.svg alt=\"V\" width=8 height=8></span> ")
           .append(_t("They offered to introduce us (help peers traverse our firewall)"))
           .append("<br>\n<span class=\"peer_arrow inbound\"><img alt=Inbound src=/themes/console/images/inbound.svg></span> ")
           .append(_t("Inbound connection"))
           .append("<br>\n<span class=\"peer_arrow inbound small\"><img src=/themes/console/images/outbound.svg alt=\"^\" width=8 height=8></span> ")
           .append(_t("We offered to introduce them (help peers traverse their firewall)"))
           .append("</li>\n<li><b id=def.idle>")
           .append(_t("Idle"))
           .append(":</b> ")
           .append(_t("How long since a packet has been received / sent"))
           .append("</li><li><b id=def.rate>")
           .append(_t("In/Out"))
           .append("</b>: ")
           .append(_t("Smoothed inbound / outbound transfer rate"))
           .append(" (K/s)")
           .append("</li>\n<li><b id=def.up>")
           .append(_t("Up"))
           .append(":</b> ")
           .append(_t("How long ago connection was established"))
           .append("</li><li><b id=def.skew>")
           .append(_t("Skew"))
           .append(":</b> ")
           .append(_t("Difference between the peer's clock and our own"))
           .append("</li>\n");
        if (isAdvanced()) {
            buf.append("<li><b id=def.cwnd>CWND (")
               .append(_t("Congestion window"))
               .append("):</b></br>&nbsp;&nbsp;&bullet; ")
               .append(_t("How many bytes can be sent without acknowledgement"))
               .append("<br>\n&nbsp;&nbsp;&bullet; ")
               .append(_t("Number of sent messages awaiting acknowledgement"))
               .append("<br>\n&nbsp;&nbsp;&bullet; ")
               .append(_t("Maximum number of concurrent messages to send"))
               .append("<br>\n&nbsp;&nbsp;&bullet; ")
               .append(_t("Number of pending sends which exceed window"))
               .append("</li><li><b id=def.ssthresh>SST (")
               .append(_t("Slow start threshold"))
               .append("):</b> ")
               .append(_t("Maximum packet size before congestion avoidance"))
               .append("</li>\n<li><b id=def.rtt>RTT (")
               .append(_t("Round trip time"))
               .append("):</b> ")
               .append(_t("How long for packet to be sent to peer and back to us"))
               .append("</li><li><b id=def.rto>RTO (")
               .append(_t("Retransmit timeout"))
               .append("):</b> ")
               .append(_t("How long before peer gives up resending lost packet"))
               .append("</li>\n<li><b id=def.mtu>MTU (")
               .append(_t("Maximum transmission unit"))
               .append("):</b></br>&nbsp;&nbsp;&bullet; ")
               .append(_t("Maximum send packet size"))
               .append("<br>&nbsp;&nbsp;&bullet; ")
               .append(_t("Estimated maximum receive packet size (bytes)"))
               .append("</li>");
        }
        buf.append("<li><b id=def.send>")
           .append(_t("TX"))
           .append(":</b> ")
           .append(_t("Messages sent to peer"))
           .append("</li>\n<li><b id=def.recv>")
           .append(_t("RX"))
           .append(":</b> ")
           .append(_t("Messages received from peer"))
           .append("</li><li><b id=def.resent>")
           .append(_t("Dup TX"))
           .append(":</b> ")
           .append(_t("Packets retransmitted to peer"))
           .append("</li>\n<li><b id=def.dupRecv>")
           .append(_t("Dup RX"))
           .append(":</b> ")
           .append(_t("Duplicate packets received from peer"))
           .append("</li>\n</ul></td></tr></table>");
        return buf.toString();
    }

    private static final DecimalFormat _fmt = new DecimalFormat("#,##0.00");
    static {_fmt.setRoundingMode(RoundingMode.HALF_UP);}

    private static final String formatKBps(int bps) {
        if (bps < 5) {return "0";}
        synchronized (_fmt) {return _fmt.format((float)bps/1000);}
    }

}
