package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.net.InetAddress;
import java.text.DecimalFormat;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.peermanager.DBHistory;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.CommSystemFacadeImpl;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.Messages;
import static net.i2p.router.web.helpers.TunnelRenderer.range;

import net.i2p.stat.Rate;
import net.i2p.stat.RateAverages;
import net.i2p.stat.RateStat;

import net.i2p.util.Addresses;

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

    private static final String PROP_ENABLE_REVERSE_LOOKUPS = "routerconsole.enableReverseLookups";
    public boolean enableReverseLookups() {
        return _context.getBooleanProperty(PROP_ENABLE_REVERSE_LOOKUPS);
    }

    /**
     *  @param mode 0 = high cap; 1 = all; 2 = floodfill; 3 = banned; 4 = ban summary by hash
     */
    public void renderStatusHTML(Writer out, int mode) throws IOException {
        boolean full = mode == 0;
        Hash us = _context.routerHash();
        RouterInfo local = _context.netDb().lookupRouterInfoLocally(us);
        boolean ffmode = local != null && local.getCapabilities().indexOf('f') >= 0;
        Set<Hash> peers = _organizer.selectAllPeers();
        long now = _context.clock().now();
        long hideBefore = ffmode ? now - 15*60*1000 : !ffmode && mode == 2 ? now - 60*60*1000 : now - 15*60*1000;

        Set<PeerProfile> order = new TreeSet<PeerProfile>(mode == 2 ? new HashComparator() : new ProfileComparator());
        int older = 0;
        int standard = 0;
        int ff = 0;
        for (Hash peer : peers) {
            if (_organizer.getUs().equals(peer)) continue;
            PeerProfile prof = _organizer.getProfileNonblocking(peer);
            RouterInfo info = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(peer);
            boolean isFF = info != null && info.getCapabilities().indexOf('f') >= 0;
            if (prof == null)
                continue;
            if (mode == 2 && isFF) {
                order.add(prof);
                ff++;
                continue;
            }
//            if (prof.getLastSendSuccessful() <= hideBefore) {
            if (mode != 2 && (prof.getLastHeardFrom() <= hideBefore || prof.getLastSendSuccessful() <= hideBefore) && !prof.getIsActive()) {
                older++;
                continue;
            }
            if (!full && !_organizer.isHighCapacity(peer)) {
                standard++;
                continue;
            }
            order.add(prof);
        }

        int fast = 0;
        int reliable = 0;
        int integrated = 0;
        boolean isAdvanced = _context.getBooleanProperty("routerconsole.advanced");
//        StringBuilder buf = new StringBuilder(16*1024);
        StringBuilder buf = new StringBuilder(32*1024);

        if (mode < 2) {
            buf.append("<p id=profiles_overview class=infohelp>");
            buf.append(ngettext("Showing {0} recent profile.", "Showing {0} recent profiles.",
                                order.size()).replace(".", " (active in the last 15 minutes).")).append('\n');
            if (older > 0)
                buf.append(ngettext("Hiding {0} older profile.", "Hiding {0} older profiles.", older)).append('\n');
            if (standard > 0)
                buf.append("<a href=\"/profiles\">").append(ngettext("Hiding {0} standard profile.", "Hiding {0} standard profiles.", standard)).append("</a>\n");
            buf.append(_t("Note that the profiler relies on sustained client tunnel usage to accurately profile peers.")).append("</p>");

            buf.append("<div class=widescroll id=peerprofiles>\n<table id=profilelist>\n")
               .append("<colgroup></colgroup><colgroup></colgroup><colgroup></colgroup><colgroup>" +
                       "</colgroup><colgroup></colgroup><colgroup></colgroup><colgroup></colgroup>" +
                       "<colgroup></colgroup><colgroup></colgroup><colgroup></colgroup>")
               .append("<thead>\n<tr>")
               .append("<th>").append(_t("Peer")).append("</th>")
               .append("<th>").append(_t("Caps")).append("</th>")
               .append("<th>").append(_t("Version")).append("</th>");
            if (enableReverseLookups()) {
                buf.append("<th>").append(_t("Host")).append(" / ").append(_t("Domain")).append("</th>");
            } else {
                buf.append("<th>").append(_t("Host")).append("</th>");
            }
            buf.append("<th>").append(_t("Status")).append("</th>")
               .append("<th class=groups>").append(_t("Groups")).append("</th>")
               .append("<th>").append(_t("Speed")).append("</th>")
               .append("<th class=latency>").append(_t("Low Latency")).append("</th>")
               //.append("<th title=\"").append(_t("Time taken for peer test")).append("\">")
               //.append(_t("Test Avg (ms)")).append("</th>")
               .append("<th>").append(_t("Capacity")).append("</th>")
               .append("<th title=\"").append(_t("Tunnels peer has agreed to participate in"))
               .append("\">").append(_t("Accepted")).append("</th>")
               .append("<th title=\"").append(_t("Tunnels peer has refused to participate in"))
               .append("\">").append(_t("Rejected")).append("</th>")
               .append("<th>").append(_t("Integration")).append("</th>")
               .append("<th>").append(_t("First Heard About")).append("</th>")
               .append("<th>").append(_t("View/Edit")).append("</th>")
               .append("</tr>\n</thead>\n<tbody id=pbody>\n");
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
                buf.append("<tr class=lazy><td nowrap>");
                buf.append(_context.commSystem().renderPeerHTML(peer, false));
                // debug
                //if(prof.getIsExpandedDB())
                //   buf.append(" ** ");
                RouterInfo info = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(peer);
                buf.append("<td>");
                if (info != null) {
                    buf.append(_context.commSystem().renderPeerCaps(peer, false));
                }
                buf.append("</td><td>");
                String v = info != null ? info.getOption("router.version") : null;
                if (v != null) {
                    buf.append("<span class=version title=\"").append(_t("Show all routers with this version in the NetDb"))
                       .append("\"><a href=\"/netdb?v=").append(DataHelper.stripHTML(v)).append("\">").append(DataHelper.stripHTML(v))
                       .append("</a></span>");
                } else {
                    buf.append("<span>&ensp;</span>");
                }
                buf.append("</td><td>");
                String ip = (info != null) ? Addresses.toString(CommSystemFacadeImpl.getValidIP(info)) : null;
                String rl = ip != null ? getCanonicalHostName(ip) : null;
                if (enableReverseLookups()) {
                    if (rl != null && rl != "null" && rl.length() != 0 && !ip.toString().equals(rl)) {
                        buf.append("<span hidden>[XHost]</span><span class=rlookup title=\"").append(rl).append("\">");
                        buf.append(CommSystemFacadeImpl.getDomain(rl.replace("null", "unknown")));
                    } else if (ip == "null" || ip == null) {
                        buf.append("<span>").append(_t("unknown"));
                    } else {
                        if (ip != null && ip.contains(":"))
                            buf.append("<span hidden>[IPv6]</span>");
                        buf.append("<span>").append(ip);
                    }
                    buf.append("</span>");
                } else {
                    buf.append(ip != null ? ip : _t("unknown"));
                }
                buf.append("</td><td>");
                boolean ok = true;
                if (_context.banlist().isBanlisted(peer)) {
                    buf.append(_t("Banned"));
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
                    buf.append("<span class=ok>").append(_t("OK")).append("</span>");
                } else if (fails > 0) {
                    Rate accepted = prof.getTunnelCreateResponseTime().getRate(60*60*1000);
                    long total = fails + accepted.computeAverages(ra, false).getTotalEventCount();
                    if (total / fails <= 5) { // don't demote if less than 5%
                        if (bonus == 9999999) {
                            prof.setSpeedBonus(0);
                        }
                        prof.setCapacityBonus(-30);
                    }
                    if (total / fails <= 10) {  // hide if < 10%
                        buf.append(" &bullet; ").append(fails).append('/').append(total).append(' ').append(_t("Test Fails"));
                    }
                } else {
                    buf.append("<span>&ensp;</span>");
                }
                buf.append("</td>");
                buf.append("<td class=groups>");
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
                    buf.append("<span hidden>0</span>");
                    buf.append("<span class=\"");
                    if (bonus >= 9999999)
                        buf.append("testOK ");
                    else if (capBonus <= -30)
                        buf.append("testFail ");
                    buf.append("nospeed\">&ensp;</span>");
                }
                buf.append("</td>");
                buf.append("<td class=latency>");
                if (bonus >= 9999999)
                    buf.append("<span class=lowlatency>✔</span>");
                else if (capBonus == -30)
                    buf.append("<span class=highlatency>✖</span>");
                else
                    buf.append("<span>&ensp;</span>");
/*
                buf.append("</td><td>");
                if (prof.getPeerTestTimeAverage() > 0)
                    buf.append(Math.round(prof.getPeerTestTimeAverage()));
                else {
                    buf.append("&ensp;");
                }
*/
                buf.append("</td><td><span>");
                if (capBonus != 0 && capBonus != -30) {
                    buf.append(num(Math.round(prof.getCapacityValue())).replace(".00", ""));
                    if (capBonus > 0)
                        buf.append(" (+");
                    else
                        buf.append(" (");
                    buf.append(capBonus).append(')');
                } else {
                    buf.append("&ensp;");
                }
                buf.append("</span>");
                buf.append("</td><td>");
                int agreed = Math.round(prof.getTunnelHistory().getLifetimeAgreedTo());
                int rejected = Math.round(prof.getTunnelHistory().getLifetimeRejected());
                if (agreed > 0)
                    buf.append(agreed);
                else
                    buf.append("<span hidden>0</span>");
                buf.append("</td><td>");
                if (rejected > 0)
                    buf.append(rejected);
                else
                    buf.append("<span hidden>0</span>");
                buf.append("</td><td>");
                String integration = num(prof.getIntegrationValue()).replace(".00", "");
                if (prof.getIntegrationValue() > 0) {
                    buf.append("<span>").append(integration).append("</span>");
                } else {
                    buf.append("<span>&ensp;</span>");
                }
                buf.append("</td><td>");
                now = _context.clock().now();
                if (prof.getFirstHeardAbout() > 0) {
                    buf.append("<span hidden>[").append(prof.getFirstHeardAbout()).append("]</span>")
                       .append(formatInterval(now, prof.getFirstHeardAbout()));
                } else {
                    buf.append("<span hidden>[").append(prof.getLastHeardFrom()).append("]</span>")
                       .append(formatInterval(now, prof.getLastHeardFrom()));
                }
                buf.append("</td><td nowrap class=viewedit>");
                if (prof != null) {
                    buf.append("<a class=viewprofile href=\"/viewprofile?peer=").append(peer.toBase64()).append("\" title=\"").append(_t("View profile"))
                       .append("\" alt=\"[").append(_t("View profile")).append("]\">").append(_t("Profile")).append("</a>");
                }
                buf.append("<br><a class=configpeer href=\"/configpeer?peer=").append(peer.toBase64()).append("\" title=\"").append(_t("Configure peer"))
                   .append("\" alt=\"[").append(_t("Configure peer")).append("]\">").append(_t("Edit")).append("</a>");
                buf.append("</td></tr>\n");
                // let's not build the whole page in memory (~500 bytes per peer)
                out.write(buf.toString());
                buf.setLength(0);
            }
            buf.append("</tbody>\n</table>\n");

            buf.append("<div id=peer_thresholds>\n" +
                       "<h3 class=tabletitle>" + _t("Thresholds") + "</h3>\n" +
                       "<table id=thresholds>\n" +
                       "<thead><tr><th><b>" + _t("Speed") + ": </b>");
            double speed = Math.max(1, _organizer.getSpeedThreshold());
            //String speedApprox = spd.substring(0, spd.indexOf("."));
            //int speed = Integer.parseInt(speedApprox);
            if (speed < -10240)
                speed += 10240;
            else if (speed < 0)
                speed = 0;
            if (speed > 1025) {
                speed = speed / 1024;
                buf.append((int)speed).append(' ' ).append("KB");
            } else {
                buf.append((int)speed).append(' ' ).append("B");
            }
            buf.append("ps</th><th><b>").append(_t("Capacity")).append(": </b>");

            double capThresh = Math.max(1, Math.round(_organizer.getCapacityThreshold()));
            double integThresh = Math.max(1, _organizer.getIntegrationThreshold());
            buf.append((int)Math.round(capThresh)).append(' ').append(_t("tunnels per hour")).append("</th><th><b>")
               .append(_t("Integration")).append(": </b>").append((int)Math.round(integThresh)).append(' ');
            if (capThresh > 0 && capThresh < 2)
                buf.append(_t("peer"));
            else
                buf.append(_t("peers"));
            buf.append("</th></tr></thead>\n")
               .append("<tbody>\n<tr><td>")
               .append(ngettext("{0} fast peer", "{0} fast peers", fast))
               .append("</td><td>")
               .append(ngettext("{0} high capacity peer", "{0} high capacity peers", reliable))
               .append("</td><td>")
               .append(ngettext("{0} integrated peer", "{0} integrated peers", integrated))
               .append("</td></tr>\n")
               .append("</tbody>\n</table>\n</div>\n"); // thresholds
            buf.append("</div>\n");

        } else if (mode == 2) {

            buf.append("<div class=widescroll id=ff>\n")
               .append("<table id=floodfills data-sortable>\n")
               .append("<colgroup></colgroup><colgroup></colgroup><colgroup></colgroup><colgroup></colgroup><colgroup></colgroup>" +
                       "<colgroup class=good></colgroup><colgroup class=good></colgroup><colgroup class=good></colgroup><colgroup class=good></colgroup>" +
                       "<colgroup class=bad></colgroup><colgroup class=bad></colgroup><colgroup class=bad></colgroup><colgroup class=bad></colgroup>")
               .append("<thead class=smallhead><tr>")
               .append("<th>").append(_t("Peer")).append("</th>")
               .append("<th>").append(_t("1h Fail Rate").replace("Rate","")).append("</th>")
               .append("<th>").append(_t("1h Resp. Time")).append("</th>")
               .append("<th>").append(_t("First Heard About")).append("</th>")
               .append("<th>").append(_t("Last Heard From")).append("</th>")
               .append("<th>").append(_t("Good Lookups")).append("</th>")
               .append("<th>").append(_t("Last Good Lookup")).append("</th>")
               .append("<th>").append(_t("Last Good Send")).append("</th>")
               .append("<th>").append(_t("Last Good Store")).append("</th>")
               .append("<th>").append(_t("Bad Lookups")).append("</th>")
               .append("<th>").append(_t("Last Bad Lookup")).append("</th>")
               .append("<th>").append(_t("Last Bad Send")).append("</th>")
               .append("<th>").append(_t("Last Bad Store")).append("</th>")
               .append("</tr></thead>\n<tbody id=ffProfiles>\n");
            RateAverages ra = RateAverages.getTemp();
            for (PeerProfile prof : order) {
                Hash peer = prof.getPeer();
                DBHistory dbh = prof.getDBHistory();
                RouterInfo info = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(peer);
                boolean isBanned = _context.banlist().isBanlisted(peer);
                boolean isUnreachable = info != null && info.getCapabilities().indexOf('U') >= 0;
                boolean isFF = info != null && info.getCapabilities().indexOf('f') >= 0;
                boolean hasSalt = info != null && info.getCapabilities().contains("salt");
                int displayed = 0;
                boolean isResponding = prof.getDbResponseTime() != null;
                boolean isGood = prof.getLastSendSuccessful() > 0 && isResponding &&
                                 (dbh != null && dbh.getLastStoreSuccessful() > 0 ||
                                 dbh.getLastLookupSuccessful() > 0);

                //if (dbh != null && isFF && !isUnreachable && !isBanned && isGood) {
                if (dbh != null && isFF && !isUnreachable && !isBanned) {
                    displayed++;
                    buf.append("<tr class=lazy><td nowrap>");
                    buf.append(_context.commSystem().renderPeerHTML(peer, true));
                    buf.append("</td>");
                    String integration = num(prof.getIntegrationValue()).replace(".00", "");
                    String hourfail = davg(dbh, 60*60*1000l, ra);
                    String dayfail = davg(dbh, 24*60*60*1000l, ra);
                    buf.append("<td><span class=\"percentBarOuter");
                    if (hourfail.equals("0%")) {
                        buf.append(" nofail");
                    }
                    buf.append("\"><span class=percentBarInner style=\"width:" + hourfail + "\">" +
                               "<span class=percentBarText>" + hourfail + "</span></span></span>");
                    buf.append("</td>");
                    buf.append("<td><span hidden>[").append(avg(prof, 60*60*1000l, ra)).append("]</span>");
                    buf.append(avg(prof, 60*60*1000l, ra));
                    buf.append("</td>");
                    now = _context.clock().now();
                    long heard = prof.getFirstHeardAbout();
                    buf.append("<td><span hidden>[").append(heard).append("]</span>")
                       .append(formatInterval(now, heard)).append("</td>");
                    buf.append("<td><span hidden>[").append(prof.getLastHeardFrom()).append("]</span>")
                       .append(formatInterval(now, prof.getLastHeardFrom())).append("</td>");
                    buf.append("<td><span hidden>[").append(dbh.getSuccessfulLookups()).append("]</span>")
                       .append(dbh.getSuccessfulLookups()).append("</td>");
                    buf.append("<td><span hidden>[").append(dbh.getLastLookupSuccessful()).append("]</span>")
                       .append(formatInterval(now, dbh.getLastLookupSuccessful())).append("</td>");
                    buf.append("<td><span hidden>[").append(prof.getLastSendSuccessful()).append("]</span>")
                       .append(formatInterval(now, prof.getLastSendSuccessful())).append("</td>");
                    buf.append("<td><span hidden>[").append(dbh.getLastStoreSuccessful()).append("]</span>")
                       .append(formatInterval(now, dbh.getLastStoreSuccessful())).append("</td>");
                    buf.append("<td><span hidden>[").append(dbh.getFailedLookups()).append("]</span>")
                       .append(dbh.getFailedLookups()).append("</td>");
                    buf.append("<td><span hidden>[").append(dbh.getLastLookupFailed()).append("]</span>")
                       .append(formatInterval(now, dbh.getLastLookupFailed())).append("</td>");
                    buf.append("<td><span hidden>[").append(prof.getLastSendFailed()).append("]</span>")
                       .append(formatInterval(now, prof.getLastSendFailed())).append("</td>");
                    buf.append("<td><span hidden>[").append(dbh.getLastStoreFailed()).append("]</span>")
                       .append(formatInterval(now, dbh.getLastStoreFailed())).append("</td>");
                    buf.append("</tr>\n");
                }
            }
            buf.append("</tbody>\n</table>\n");
            buf.append("</div>\n");
        }
        if (mode == 0 && !isAdvanced) {
            buf.append("<h3 class=tabletitle>").append(_t("Definitions")).append("</h3>\n")
               .append("<table id=profile_defs>\n<tbody>\n");
            buf.append("<tr><td><b>")
               .append(_t("caps")).append(":</b></td><td>").append(_t("Capabilities in the NetDb, not used to determine profiles"))
               .append("</td></tr>\n");
            buf.append("<tr id=capabilities_key><td></td><td><table><tbody>");
            buf.append("<tr>")
               .append("<td><a href=\"/netdb?caps=f\" title=\"" + _t("Show all routers with this capability in the NetDb") +
                       "\"><b class=ff>F</b></a></td><td>").append(_t("Floodfill")).append("</td>")
               .append("<td><a href=\"/netdb?caps=R\" title=\"" + _t("Show all routers with this capability in the NetDb") +
                       "\"><b class=reachable>R</b></a></td><td>").append(_t("Reachable")).append("</td>")
               .append("</tr>\n");
            buf.append("<tr>")
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
            buf.append("</tbody>\n</table>\n</td></tr>\n"); // profile_defs
            buf.append("<tr><td><b>")
               .append(_t("status"))
               .append(":</b></td><td>")
               .append(_t("is the peer banned, or unreachable, or failing tunnel tests?"))
               .append("</td></tr>\n");
            buf.append("<tr><td><b>")
               .append(_t("groups")).append(":</b></td><td>")
               .append(_t("Note: Peers are categorized by the profile organizer based on observable performance, " +
                          "not from capabilities they advertise in the NetDB.")).append("<br>")
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
               .append(_t("Is the peer responding to tests in a timely fashion? To configure the timeout value: " +
                          "<code>router.peerTestTimeout={n}</code> (value is milliseconds, default 1000ms)"))
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
            } else {
                if (_context.profileOrganizer().isFast(right.getPeer())) {
                    return 1;
                } else if (_context.profileOrganizer().isHighCapacity(right.getPeer())) {
                    return 1;
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
    private final static String NA = "&ensp;";

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
            RateStat rs = dbh != null ? dbh.getFailedLookupRate() : null;
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

    /** @since 0.9.58+ */
    public String getCanonicalHostName(String hostName) {
        try {
            return InetAddress.getByName(hostName).getCanonicalHostName();
        } catch(IOException exception) {
            return hostName;
        }
    }

}
