package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.text.DecimalFormat;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.DBHistory;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.Messages;
import static net.i2p.router.web.helpers.TunnelRenderer.range;
import net.i2p.stat.Rate;
import net.i2p.stat.RateAverages;
import net.i2p.stat.RateStat;

/**
 * Helper class to refactor the HTML rendering from out of the ProfileOrganizer
 *
 */
class ProfileOrganizerRenderer {
    private final RouterContext _context;
    private final ProfileOrganizer _organizer;

    public ProfileOrganizerRenderer(ProfileOrganizer organizer, RouterContext context) {
        _context = context;
        _organizer = organizer;
    }

    /**
     *  @param mode 0 = all; 1 = high cap; 2 = floodfill
     */
    public void renderStatusHTML(Writer out, int mode) throws IOException {
        boolean full = mode == 0;
        Set<Hash> peers = _organizer.selectAllPeers();

        long now = _context.clock().now();
//        long hideBefore = now - 90*60*1000;
        long hideBefore = now - 60*1000;

        Set<PeerProfile> order = new TreeSet<PeerProfile>(mode == 2 ? new HashComparator() : new ProfileComparator());
        int older = 0;
        int standard = 0;
        for (Hash peer : peers) {
            if (_organizer.getUs().equals(peer)) continue;
            PeerProfile prof = _organizer.getProfileNonblocking(peer);
            if (prof == null)
                continue;
            if (mode == 2) {
                RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
                if (info != null && info.getCapabilities().indexOf('f') >= 0 && prof.getLastHeardFrom() <= hideBefore && prof.getLastHeardFrom() > 0)
                    order.add(prof);
                continue;
            }
            if (prof.getLastSendSuccessful() <= hideBefore) {
                older++;
                continue;
            }
            if ((!full) && !_organizer.isHighCapacity(peer)) {
                standard++;
                continue;
            }
            order.add(prof);
        }

        int fast = 0;
        int reliable = 0;
        int integrated = 0;
        boolean isAdvanced = _context.getBooleanProperty("routerconsole.advanced");
        StringBuilder buf = new StringBuilder(16*1024);

        if (mode < 2) {
            buf.append("<p id=\"profiles_overview\" class=\"infohelp\">");
            buf.append(ngettext("Showing {0} recent profile.", "Showing {0} recent profiles.", order.size()).replace(".", " (active in the last minute).")).append('\n');
            if (older > 0)
                buf.append(ngettext("Hiding {0} older profile.", "Hiding {0} older profiles.", older)).append('\n');
            if (standard > 0)
                buf.append("<a href=\"/profiles\">").append(ngettext("Hiding {0} standard profile.", "Hiding {0} standard profiles.", standard)).append("</a>\n");
            buf.append(_t("Note that the profiler relies on sustained client tunnel usage to accurately profile peers.")).append("</p>");

            buf.append("<div class=\"widescroll\" id=\"peerprofiles\">\n<table id=\"profilelist\">\n");
            buf.append("<thead>\n<tr>");
            buf.append("<th>").append(_t("Peer")).append("</th>");
            buf.append("<th>").append(_t("Caps")).append("</th>");
            buf.append("<th>").append(_t("Version")).append("</th>");
            buf.append("<th>").append(_t("Status")).append("</th>");
            buf.append("<th>").append(_t("Groups")).append("</th>");
            buf.append("<th>").append(_t("Speed")).append("</th>");
            buf.append("<th>").append(_t("Low Latency")).append("</th>");
            buf.append("<th>").append(_t("Capacity")).append("</th>");
            buf.append("<th>").append(_t("Integration")).append("</th>");
            buf.append("<th>").append(_t("View/Edit")).append("</th>");
            buf.append("</tr>\n</thead>\n");
            int prevTier = 1;
            for (PeerProfile prof : order) {
                Hash peer = prof.getPeer();
                int tier = 0;
                boolean isIntegrated = false;
                if (_organizer.isFast(peer)) {
                    tier = 1;
                    fast++;
                    reliable++;
                } else if (_organizer.isHighCapacity(peer)) {
                    tier = 2;
                    reliable++;
                } else {
                    tier = 3;
                }

                if (_organizer.isWellIntegrated(peer)) {
                    isIntegrated = true;
                    integrated++;
                }

                if (tier != prevTier)
                    buf.append("<tr><td colspan=\"10\" class=\"separator\"><hr></td></tr>\n");
                prevTier = tier;

                buf.append("<tr class=\"lazy\"><td align=\"center\" nowrap>");
                buf.append(_context.commSystem().renderPeerHTML(peer));
                // debug
                //if(prof.getIsExpandedDB())
                //   buf.append(" ** ");
                RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
                if (info != null) {
                    // prevent HTML injection in the caps and version
                    // remove superfluous O class from P + X, add spans, trail ff, add links to netdb search + tooltip
                    String tooltip = "\" title=\"" + _t("Show all routers with this capability in the NetDb") + "\"><span";
                    String caps = DataHelper.stripHTML(info.getCapabilities())
                        .replace("XO", "X")
                        .replace("PO", "P")
                        .replace("fR", "Rf")
                        .replace("fU", "Uf")
                        .replace("f", "<a href=\"/netdb?caps=f\"><span class=\"ff\">F</span></a>")
                        .replace("B", "<a href=\"/netdb?caps=B\"><span class=\"testing\">B</span></a>")
                        .replace("C", "<a href=\"/netdb?caps=C\"><span class=\"ssuintro\">C</span></a>")
                        .replace("H", "<a href=\"/netdb?caps=H\"><span class=\"hidden\">H</span></a>")
                        .replace("R", "<a href=\"/netdb?caps=R\"><span class=\"reachable\">R</span></a>")
                        .replace("U", "<a href=\"/netdb?caps=U\"><span class=\"unreachable\">U</span></a>")
                        .replace("K", "<a href=\"/netdb?caps=K\"><span class=\"tier\">K</span></a>")
                        .replace("L", "<a href=\"/netdb?caps=L\"><span class=\"tier\">L</span></a>")
                        .replace("M", "<a href=\"/netdb?caps=M\"><span class=\"tier\">M</span></a>")
                        .replace("N", "<a href=\"/netdb?caps=N\"><span class=\"tier\">N</span></a>")
                        .replace("O", "<a href=\"/netdb?caps=O\"><span class=\"tier\">O</span></a>")
                        .replace("P", "<a href=\"/netdb?caps=P\"><span class=\"tier\">P</span></a>")
                        .replace("X", "<a href=\"/netdb?caps=X\"><span class=\"tier\">X</span></a>")
                        .replace("\"><span", tooltip);
                    buf.append("<td align=\"left\">").append(caps);
                } else {
                    buf.append("<td align=\"left\"><i>").append(_t("unknown")).append("</i>");
                }
                buf.append("</td>");
                buf.append("<td>");
                String v = info != null ? info.getOption("router.version") : null;
                if (v != null)
                    buf.append("<span class=\"version\" title=\"").append(_t("Show all routers with this version in the NetDb"))
                       .append("\"><a href=\"/netdb?v=").append(DataHelper.stripHTML(v)).append("\">").append(DataHelper.stripHTML(v));
                buf.append("</a></span></td>");
                buf.append("<td align=\"center\">");
                boolean ok = true;
                if (_context.banlist().isBanlisted(peer)) {
                    buf.append(_t("Banned"));
                    ok = false;
                }
                if (prof.getIsFailing()) {
                    buf.append(" &bullet; ").append(_t("Failing"));
                    ok = false;
                }
                if (_context.commSystem().wasUnreachable(peer)) {
                    buf.append(" &bullet; ").append(_t("Unreachable"));
                    ok = false;
                }
                RateAverages ra = RateAverages.getTemp();
                Rate failed = prof.getTunnelHistory().getFailedRate().getRate(60*60*1000);
                long fails = failed.computeAverages(ra, false).getTotalEventCount();
                long bonus = prof.getSpeedBonus();
                long capBonus = prof.getCapacityBonus();
                if (ok && fails == 0) {
                    buf.append("<span class=\"ok\">").append(_t("OK")).append("</span>");
                } else if (fails > 0) {
                    Rate accepted = prof.getTunnelCreateResponseTime().getRate(60*60*1000);
                    long total = fails + accepted.computeAverages(ra, false).getTotalEventCount();
                    if (total / fails <= 5) { // don't demote if less than 5%
                        if (bonus == 9999999)
                            prof.setSpeedBonus(0);
                        prof.setCapacityBonus(-30);
                    }
                    if (total / fails <= 10) {  // hide if < 10%
                        buf.append(" &bullet; ").append(fails).append('/').append(total).append(' ').append(_t("Test Fails"));
                    }
                }

                buf.append("</td>");
                buf.append("<td align=\"center\">");
                buf.append("<span class=\"");
                if (isIntegrated) buf.append("integrated ");
                switch (tier) {
                    case 1: buf.append("fast\">").append(_t("Fast, High Capacity")); break;
                    case 2: buf.append("highcap\">").append(_t("High Capacity")); break;
                    case 3: buf.append("standard\">").append(_t("Standard")); break;
                    default: buf.append("failing\">").append(_t("Failing")); break;
                }
                if (isIntegrated) buf.append(", ").append(_t("Integrated"));
                buf.append("</span></td><td>");
                String spd = num(Math.round(prof.getSpeedValue())).replace(",", "");
                String speedApprox = spd.substring(0, spd.indexOf("."));
                int speed = Integer.parseInt(speedApprox);
                if (prof.getSpeedValue() > 0.1) {
                    buf.append("<span class=\"");
                    if (bonus >= 9999999)
                        buf.append("testOK ");
                    else if (capBonus == -30)
                        buf.append("testFail ");
                    if (speed >= 9999999)
                        speed = speed - 9999999;
                    if (speed > 1025) {
                        speed = speed / 1024;
                        buf.append("kilobytes\">");
                        buf.append(speed).append("&#8239;K/s");
                    } else {
                        buf.append("bytes\">");
                        buf.append(speed).append("&#8239;B/s");
                    }
                    if (bonus != 0 && bonus != 9999999) {
                        if (bonus > 0)
                            buf.append(" (+");
                        else
                            buf.append(" (");
                        buf.append(bonus).append(')');
                    }
                    buf.append("</span>");
                } else {
                    buf.append("<span class=\"");
                    if (bonus == 9999999)
                        buf.append("testOK ");
                    else if (capBonus == -30)
                        buf.append("testFail ");
                    buf.append("nospeed\">‒</span>");
                }
                buf.append("</td>");

                buf.append("<td align=\"center\">");

                if (bonus >= 9999999)
                    buf.append("<span class=\"lowlatency\">✔</span>");
                else if (capBonus == -30)
                    buf.append("<span class=\"highlatency\">✖</span>");
                buf.append("</td>");

                buf.append("<td><span>").append(num(Math.round(prof.getCapacityValue())).replace(".00", ""));
                if (capBonus != 0 && capBonus != -30) {
                    if (capBonus > 0)
                        buf.append(" (+");
                    else
                        buf.append(" (");
                    buf.append(capBonus).append(')');
                }
                buf.append("</span>");
                buf.append("</td><td>");

                String integration = num(prof.getIntegrationValue()).replace(".00", "");
                if (prof.getIntegrationValue() > 0) {
                    buf.append("<span>").append(integration).append("</span>");
                }
                buf.append("</td><td nowrap align=\"center\" class=\"viewedit\">");
                buf.append("<a class=\"viewprofile\" href=\"/viewprofile?peer=").append(peer.toBase64()).append("\" title=\"").append(_t("View profile"))
                   .append("\" alt=\"[").append(_t("View profile")).append("]\">").append(_t("Profile")).append("</a>");
                buf.append("<br><a class=\"configpeer\" href=\"/configpeer?peer=").append(peer.toBase64()).append("\" title=\"").append(_t("Configure peer"))
                   .append("\" alt=\"[").append(_t("Configure peer")).append("]\">").append(_t("Edit")).append("</a>");
                buf.append("</td></tr>\n");
                // let's not build the whole page in memory (~500 bytes per peer)
                out.write(buf.toString());
                buf.setLength(0);
            }
            buf.append("</table>\n");

            buf.append("<div id=\"peer_thresholds\">\n<h3 class=\"tabletitle\">").append(_t("Thresholds")).append("</h3>\n")
               .append("<table id=\"thresholds\">\n")
               .append("<thead><tr><th><b>")
               .append(_t("Speed")).append(": </b>");
            String spd = (num(_organizer.getSpeedThreshold()).replace(",",""));
            String speedApprox = spd.substring(0, spd.indexOf("."));
            int speed = Integer.parseInt(speedApprox);
            if (speed < -10240)
                speed += 10240;
            else if (speed < 0)
                speed = 0;
            if (speed > 1025) {
                speed = speed / 1024;
                buf.append(speed).append(' ' ).append("KB");
            } else {
                buf.append(speed).append(' ' ).append("B");
            }
            buf.append("ps</th><th><b>")
               .append(_t("Capacity")).append(": </b>");
            String capThresh = num(Math.round(_organizer.getCapacityThreshold())).replace(".00", "");
            buf.append(capThresh).append(' ').append(_t("tunnels per hour")).append("</th><th><b>")
               .append(_t("Integration")).append(": </b>").append(capThresh).append(' ');
            if (capThresh.equals("1"))
                buf.append(_t("peer"));
            else
                buf.append(_t("peers"));
            buf.append("</th></tr></thead>\n<tbody>\n<tr><td>")
               .append(ngettext("{0} fast peer", "{0} fast peers", fast))
               .append("</td><td>")
               .append(ngettext("{0} high capacity peer", "{0} high capacity peers", reliable))
               .append("</td><td>")
               .append(ngettext("{0} integrated peer", "{0} integrated peers", integrated))
               .append("</td></tr>\n</tbody>\n</table>\n</div>\n"); // thresholds
            buf.append("</div>\n");

        } else {

            buf.append("<div class=\"widescroll\" id=\"ff\">\n<table id=\"floodfills\" data-sortable>\n");
            buf.append("<thead>\n<tr class=\"smallhead\">");
            buf.append("<th>").append(_t("Peer")).append("</th>");
            buf.append("<th>").append(_t("Caps")).append("</th>");
            buf.append("<th>").append(_t("Integ. Value")).append("</th>");
            buf.append("<th>").append(_t("Last Heard About")).append("</th>");
            buf.append("<th>").append(_t("Last Heard From")).append("</th>");
            buf.append("<th>").append(_t("Last Good Send")).append("</th>");
            buf.append("<th>").append(_t("Last Bad Send")).append("</th>");
//            buf.append("<th>").append(_t("10m Resp. Time")).append("</th>");
            buf.append("<th>").append(_t("1h Resp. Time")).append("</th>");
            buf.append("<th>").append(_t("1d Resp. Time")).append("</th>");
            buf.append("<th>").append(_t("Last Good Lookup")).append("</th>");
            buf.append("<th>").append(_t("Last Bad Lookup")).append("</th>");
            buf.append("<th>").append(_t("Last Good Store")).append("</th>");
            buf.append("<th>").append(_t("Last Bad Store")).append("</th>");
            buf.append("<th>").append(_t("1h Fail Rate").replace("Rate","")).append("</th>");
            buf.append("<th>").append(_t("1d Fail Rate").replace("Rate","")).append("</th>");
            buf.append("</tr>\n</thead>\n");
            RateAverages ra = RateAverages.getTemp();
            for (PeerProfile prof : order) {
                Hash peer = prof.getPeer();
                DBHistory dbh = prof.getDBHistory();
                buf.append("<tr class=\"lazy\"><td align=\"center\" nowrap>");
                buf.append(_context.commSystem().renderPeerHTML(peer));
                buf.append("</td>");
                RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
                if (info != null) {
                    // remove superfluous O class from P + X, remove F class (everyone's a ff), add spans
                    String tooltip = "\" title=\"" + _t("Show all routers with this capability in the NetDb") + "\"><span";
                    String caps = DataHelper.stripHTML(info.getCapabilities())
                        .replace("XO", "X")
                        .replace("PO", "P")
                        .replace("f", "")
                        .replace("B", "<a href=\"/netdb?caps=B\"><span class=\"ssutesting\">B</span></a>") // unneeded?
                        .replace("C", "<a href=\"/netdb?caps=C\"><span class=\"ssuintro\">C</span></a>") // unneeded?
                        .replace("H", "<a href=\"/netdb?caps=H\"><span class=\"hidden\">H</span></a>") // unneeded?
                        .replace("R", "<a href=\"/netdb?caps=R\"><span class=\"reachable\">R</span></a>") // unneeded?
                        .replace("U", "<a href=\"/netdb?caps=U\"><span class=\"unreachable\">U</span></a>") // unneeded?
                        .replace("K", "<a href=\"/netdb?caps=K\"><span class=\"tier\">K</span></a>") // unneeded?
                        .replace("L", "<a href=\"/netdb?caps=L\"><span class=\"tier\">L</span></a>")
                        .replace("M", "<a href=\"/netdb?caps=M\"><span class=\"tier\">M</span></a>")
                        .replace("N", "<a href=\"/netdb?caps=N\"><span class=\"tier\">N</span></a>")
                        .replace("O", "<a href=\"/netdb?caps=O\"><span class=\"tier\">O</span></a>")
                        .replace("P", "<a href=\"/netdb?caps=P\"><span class=\"tier\">P</span></a>")
                        .replace("X", "<a href=\"/netdb?caps=X\"><span class=\"tier\">X</span></a>")
                        .replace("\"><span", tooltip);
                    buf.append("<td align=\"left\">").append(caps).append("</td>");
                } else {
                    buf.append("<td>&nbsp;</td>");
                }
                String integration = num(prof.getIntegrationValue()).replace(".00", "");
                buf.append("<td align=\"right\">");
                if (prof.getIntegrationValue() > 0) {
                    buf.append("<span>").append(integration).append("</span>");
                }
                buf.append("</td>");
                buf.append("<td align=\"right\">").append(formatInterval(now, prof.getLastHeardAbout())).append("</td>");
                buf.append("<td align=\"right\">").append(formatInterval(now, prof.getLastHeardFrom())).append("</td>");
                buf.append("<td align=\"right\">").append(formatInterval(now, prof.getLastSendSuccessful())).append("</td>");
                buf.append("<td align=\"right\">").append(formatInterval(now, prof.getLastSendFailed())).append("</td>");
//                buf.append("<td align=\"right\">").append(avg(prof, 10*60*1000l, ra)).append("</td>");
                buf.append("<td align=\"right\">").append(avg(prof, 60*60*1000l, ra)).append("</td>");
                buf.append("<td align=\"right\">").append(avg(prof, 24*60*60*1000l, ra)).append("</td>");
                if (dbh != null) {
                    buf.append("<td align=\"right\">").append(formatInterval(now, dbh.getLastLookupSuccessful())).append("</td>");
                    buf.append("<td align=\"right\">").append(formatInterval(now, dbh.getLastLookupFailed())).append("</td>");
                    buf.append("<td align=\"right\">").append(formatInterval(now, dbh.getLastStoreSuccessful())).append("</td>");
                    buf.append("<td align=\"right\">").append(formatInterval(now, dbh.getLastStoreFailed())).append("</td>");
                    String hourfail = davg(dbh, 60*60*1000l, ra);
                    String dayfail = davg(dbh, 24*60*60*1000l, ra);
                    buf.append("<td align=\"center\"><span class=\"percentBarOuter\"><span class=\"percentBarInner\" style=\"width:" +
                               hourfail + "\"><span class=\"percentBarText\">").append(hourfail).append("</span></span></span>").append("</td>");
                    buf.append("<td align=\"center\"><span class=\"percentBarOuter\"><span class=\"percentBarInner\" style=\"width:" +
                               dayfail + "\"><span class=\"percentBarText\">").append(dayfail).append("</span></span></span>").append("</td>");
                } else {
                    for (int i = 0; i < 6; i++)
                        buf.append("<td align=\"right\">").append(_t(NA));
                }
                buf.append("</tr>\n");
            }
            buf.append("</table>\n");
            buf.append("</div>\n");
        }

        if (mode < 2) {
            buf.append("<h3 class=\"tabletitle\">").append(_t("Definitions")).append("</h3>\n")
               .append("<table id=\"profile_defs\">\n<tbody>\n");
            buf.append("<tr><td><b>")
               .append(_t("caps")).append(":</b></td><td>").append(_t("Capabilities in the NetDb, not used to determine profiles"))
               .append("</td></tr>\n");
            buf.append("<tr id=\"capabilities_key\"><td></td><td><table><tbody>");
/*
            buf.append("<tr>")
               .append("<td><a href=\"/netdb?caps=B\" title=\"" + _t("Show all routers with this capability in the NetDb") +
                   "\"><b class=\"ssutesting\">B</b></a></td><td>").append(_t("SSU Testing")).append("</td>")
               .append("<td><a href=\"/netdb?caps=C\" title=\"" + _t("Show all routers with this capability in the NetDb") +
                       "\"><b class=\"ssuintro\">C</b></a></td><td>").append(_t("SSU Introducer")).append("</td>")
               .append("</tr>\n");
*/
            buf.append("<tr>")
               .append("<td><a href=\"/netdb?caps=f\" title=\"" + _t("Show all routers with this capability in the NetDb") +
                       "\"><b class=\"ff\">F</b></a></td><td>").append(_t("Floodfill")).append("</td>")
               .append("<td><a href=\"/netdb?caps=R\" title=\"" + _t("Show all routers with this capability in the NetDb") +
                       "\"><b class=\"reachable\">R</b></a></td><td>").append(_t("Reachable")).append("</td>")
/*
               .append("<td><a href=\"/netdb?caps=H\" title=\"" + _t("Show all routers with this capability in the NetDb") +
                       "\"><b class=\"hidden\">H</b></a></td><td>").append(_t("Hidden")).append("</td>")
*/

               .append("</tr>\n");
/*
            buf.append("<tr>")
               .append("<td><a href=\"/netdb?caps=U\" title=\"" + _t("Show all routers with this capability in the NetDb") +
                       "\"><b class=\"unreachable\">U</b></a></td><td>").append(_t("Unreachable")).append("</td>")
               .append("</tr>\n");
            buf.append("<tr>")
               .append("<td><a href=\"/netdb?caps=K\" title=\"" + _t("Show all routers with this capability in the NetDb") +
                       "\"><b>K</b></a></td><td>").append(_t("Under {0} shared bandwidth", Router.MIN_BW_L + " KBps")).append("</td>")
               .append("<td><a href=\"/netdb?caps=L\" title=\"" + _t("Show all routers with this capability in the NetDb") +
                       "\"><b>L</b></a></td><td>").append(_t("{0} shared bandwidth", range(Router.MIN_BW_L, Router.MIN_BW_M))).append("</td>")
               .append("</tr>\n");
*/
            buf.append("<tr>")
/*
               .append("<td><a href=\"/netdb?caps=M\" title=\"" + _t("Show all routers with this capability in the NetDb") +
                       "\"><b>M</b></a></td><td>").append(_t("{0} shared bandwidth", range(Router.MIN_BW_M, Router.MIN_BW_N))).append("</td>")
*/
               .append("<td><a href=\"/netdb?caps=N\" title=\"" + _t("Show all routers with this capability in the NetDb") +
                       "\"><b>N</b></a></td><td>").append(_t("{0} shared bandwidth", range(Router.MIN_BW_N, Router.MIN_BW_O))).append("</td>")
               .append("<td><a href=\"/netdb?caps=O\" title=\"" + _t("Show all routers with this capability in the NetDb") +
                       "\"><b>O</b></a></td><td>").append(_t("{0} shared bandwidth", range(Router.MIN_BW_O, Router.MIN_BW_P))).append("</td>")
               .append("</tr>\n");
            buf.append("<tr>")
               .append("<td><a href=\"/netdb?caps=P\" title=\"" + _t("Show all routers with this capability in the NetDb") +
                       "\"><b>P</b></a></td><td>").append(_t("{0} shared bandwidth", range(Router.MIN_BW_P, Router.MIN_BW_X))).append("</td>")
               .append("<td><a href=\"/netdb?caps=X\" title=\"" + _t("Show all routers with this capability in the NetDb") +
                       "\"><b>X</b></a></td><td>").append(_t("Over {0} shared bandwidth", Math.round(Router.MIN_BW_X * 1.024f) + " KBps")).append("</td>")
               .append("</tr>\n");
/*
            buf.append("<tr>")
               .append("<td>&nbsp;</td><td>&nbsp;</td></tr>\n");
*/
            buf.append("</tbody>\n</table>\n</td></tr>\n"); // profile_defs
            buf.append("<tr><td><b>")
               .append(_t("status"))
               .append(":</b></td><td>")
               .append(_t("is the peer banned, or unreachable, or failing tunnel tests?"))
               .append("</td></tr>\n");
            buf.append("<tr><td><b>")
               .append(_t("groups")).append(":</b></td><td>")
               .append(_t("Note: Peers are categorized by the profile organizer based on observable performance, not from capabilities they advertise in the NetDB.")).append("<br>")
               .append("<span class=\"profilegroup fast\"><b>").append(_t("Fast")).append(":</b>&nbsp; ")
               .append(_t("Peers marked as high capacity that also meet or exceed speed average for all profiled peers.")).append("</span><br>")
               .append("<span class=\"profilegroup highcap\"><b>").append(_t("High Capacity")).append(":</b>&nbsp; ")
               .append(_t("Peers that meet or exceed tunnel build rate average for all profiled peers.")).append("</span><br>")
               .append("<span class=\"profilegroup integrated\"><b>").append(_t("Integrated")).append(":</b>&nbsp; ")
               .append(_t("Floodfill peers currently available for NetDb inquiries.")).append("</span><br>")
               .append("<span class=\"profilegroup standard\"><b>").append(_t("Standard")).append(":</b>&nbsp; ")
               .append(_t("Peers not profiled as high capacity (lower build rate than average peer).")).append("</span>")
               .append("</td></tr>\n");
            buf.append("<tr><td><b>")
               .append(_t("speed"))
               .append(":</b></td><td>")
               .append(_t("Peak throughput (bytes per second) over a 1 minute period that the peer has sustained in a single tunnel."))
               .append("</td></tr>\n");
            buf.append("<tr><td><b>")
               .append(_t("latency"))
               .append(":</b></td><td>")
               .append(_t("Is the peer responding to tests in a timely fashion? To configure the timeout value: <code>router.peerTestTimeout={n}</code> (value is milliseconds, default 1000ms)"))
               .append("</td></tr>\n");
            buf.append("<tr><td><b>")
               .append(_t("capacity"))
               .append(":</b></td><td>")
               .append(_t("how many tunnels can we ask them to join in an hour?"))
               .append("</td></tr>\n");
            buf.append("<tr><td><b>")
               .append(_t("integration"))
               .append(":</b></td><td>")
               .append(_t("how many new peers have they told us about lately?"))
               .append("</td></tr>\n");
            buf.append("</tbody>\n</table>\n");

        }  // mode < 2

        out.write(buf.toString());
        out.flush();
    }

    private class ProfileComparator extends HashComparator {
        public int compare(PeerProfile left, PeerProfile right) {
            if (_context.profileOrganizer().isFast(left.getPeer())) {
                if (_context.profileOrganizer().isFast(right.getPeer())) {
                    return super.compare(left, right);
                } else {
                    return -1; // fast comes first
                }
            } else if (_context.profileOrganizer().isHighCapacity(left.getPeer())) {
                if (_context.profileOrganizer().isFast(right.getPeer())) {
                    return 1;
                } else if (_context.profileOrganizer().isHighCapacity(right.getPeer())) {
                    return super.compare(left, right);
                } else {
                    return -1;
                }
            } else if (_context.profileOrganizer().isFailing(left.getPeer())) {
                if (_context.profileOrganizer().isFailing(right.getPeer())) {
                    return super.compare(left, right);
                } else {
                    return 1;
                }
            } else {
                // left is not failing
                if (_context.profileOrganizer().isFast(right.getPeer())) {
                    return 1;
                } else if (_context.profileOrganizer().isHighCapacity(right.getPeer())) {
                    return 1;
                } else if (_context.profileOrganizer().isFailing(right.getPeer())) {
                    return -1;
                } else {
                    return super.compare(left, right);
                }
            }
        }
    }

    /**
     *  Used for floodfill-only page
     *  As of 0.9.29, sorts in true binary order, not base64 string
     *  @since 0.9.8
     */
    private static class HashComparator implements Comparator<PeerProfile>, Serializable {
        public int compare(PeerProfile left, PeerProfile right) {
            return DataHelper.compareTo(left.getPeer().getData(), right.getPeer().getData());
        }

    }

    private final static DecimalFormat _fmt = new DecimalFormat("###,##0.00");
    private final static String num(double num) { synchronized (_fmt) { return _fmt.format(num); } }
    private final static String NA = "";

    private String avg (PeerProfile prof, long rate, RateAverages ra) {
            RateStat rs = prof.getDbResponseTime();
            if (rs == null)
                return _t(NA);
            Rate r = rs.getRate(rate);
            if (r == null)
                return _t(NA);
            r.computeAverages(ra, false);
            if (ra.getTotalEventCount() == 0)
                return _t(NA);
            return DataHelper.formatDuration2(Math.round(ra.getAverage()));
    }

    private String davg (DBHistory dbh, long rate, RateAverages ra) {
            RateStat rs = dbh.getFailedLookupRate();
            if (rs == null)
                return "0%";
            Rate r = rs.getRate(rate);
            if (r == null)
                return "0%";
            r.computeAverages(ra, false);
            if (ra.getTotalEventCount() <= 0)
                return "0%";
            double avg = 0.5 + 100 * ra.getAverage();
            return ((int) avg) + "%";
    }

    /** @since 0.9.21 */
    private String formatInterval(long now, long then) {
        if (then <= 0)
            return _t(NA);
        // avoid 0 or negative
        if (now <= then)
            return DataHelper.formatDuration2(1);
        return DataHelper.formatDuration2(now - then);
    }

    /** translate a string */
    private String _t(String s) {
        return Messages.getString(s, _context);
    }

    private String _t(String s, Object o) {
        return Messages.getString(s, o, _context);
    }

    /** translate (ngettext) @since 0.8.5 */
    public String ngettext(String s, String p, int n) {
        return Messages.getString(n, s, p, _context);
    }

}
