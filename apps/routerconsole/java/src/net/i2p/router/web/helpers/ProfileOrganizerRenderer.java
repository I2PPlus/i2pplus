package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.text.DecimalFormat;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
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
import static net.i2p.router.web.helpers.TunnelRenderer.range;
import net.i2p.router.web.Messages;
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
    public boolean enableReverseLookups() {return _context.getBooleanProperty(PROP_ENABLE_REVERSE_LOOKUPS);}

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
        long hideBefore = now - 4*60*60*1000;
        Set<PeerProfile> order = new TreeSet<PeerProfile>(mode == 2 ? new ProfComparator() : new ProfileComparator());
        int older = 0;
        int standard = 0;
        int ff = 0;

        for (Hash peer : peers) {
            PeerProfile prof = _organizer.getProfileNonblocking(peer);
            if (prof == null) {break;}
            int agreed = Math.round(prof.getTunnelHistory().getLifetimeAgreedTo());
            int rejected = Math.round(prof.getTunnelHistory().getLifetimeRejected());
            RouterInfo info = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(peer);
            boolean isFF = info != null && info.getCapabilities().indexOf('f') >= 0;
            if (_organizer.getUs().equals(peer) || prof.getLastHeardFrom() <= 0 || (agreed <= 0 && rejected <= 0)) {continue;}
            if (mode == 2) {
                if (isFF) {
                    order.add(prof);
                    ff++;
                    continue;
                }
            }
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

        boolean isAdvanced = _context.getBooleanProperty("routerconsole.advanced");
        StringBuilder buf = new StringBuilder(32*1024);

        // Cache for reverse DNS lookups
        Map<String, String> reverseLookupCache = new HashMap<>();

        if (mode < 2) {
            buf.append("<p id=profiles_overview class=infohelp>")
               .append(ngettext("Showing {0} recent profile.", "Showing {0} recent profiles.", order.size())).append('\n');
            if (older > 0) {
                buf.append(ngettext("Hiding {0} older profile.", "Hiding {0} older profiles.", older)).append('\n');
            }
            if (standard > 0) {
                buf.append("<a href=\"/profiles\">")
                   .append(ngettext("Hiding {0} standard profile.","Hiding {0} standard profiles.", standard))
                   .append("</a>\n");
            }
            buf.append(_t("Note that the profiler relies on sustained client tunnel usage to accurately profile peers.")).append("</p>");
            buf.append("<div class=widescroll id=peerprofiles>\n<table id=profilelist>\n")
               .append("<colgroup></colgroup><colgroup></colgroup><colgroup></colgroup><colgroup>")
               .append("</colgroup><colgroup></colgroup><colgroup></colgroup><colgroup></colgroup>")
               .append("<colgroup></colgroup><colgroup></colgroup><colgroup></colgroup>")
               .append("<thead>\n<tr>")
               .append("<th>").append(_t("Peer")).append("</th>")
               .append("<th>").append(_t("Caps")).append("</th>")
               .append("<th>").append(_t("Version")).append("</th>");
            if (enableReverseLookups()) {
                buf.append("<th class=host>").append(_t("Host")).append(" / ").append(_t("Domain")).append("</th>");
            } else {
                buf.append("<th class=host>").append(_t("Host")).append("</th>");
            }
            buf.append("<th>").append(_t("Status")).append("</th>")
               .append("<th class=groups>").append(_t("Groups")).append("</th>")
               .append("<th>").append(_t("Speed")).append("</th>")
               .append("<th class=latency>").append(_t("Low Latency")).append("</th>")
               .append("<th title=\"").append(_t("Tunnels peer has agreed to participate in"))
               .append("\">").append(_t("Accepted")).append("</th>")
               .append("<th title=\"").append(_t("Tunnels peer has refused to participate in"))
               .append("\">").append(_t("Rejected")).append("</th>")
               .append("<th>").append(_t("First Heard About")).append("</th>")
               .append("<th>").append(_t("Last Heard From")).append("</th>")
               .append("<th>").append(_t("View/Edit")).append("</th>")
               .append("</tr>\n</thead>\n<tbody id=pbody>\n");

            int[] counters = new int[3]; // fast, reliable, integrated
            for (PeerProfile prof : order) {appendPeerTableRow(buf, prof, reverseLookupCache, now, counters);}
            int fast = counters[0];
            int reliable = counters[1];
            int integrated = counters[2];
            buf.append("</tbody>\n</table>\n");
            buf.append("<div id=peer_thresholds>\n<h3 class=tabletitle>").append(_t("Thresholds")).append("</h3>\n")
               .append("<table id=thresholds>\n<thead><tr><th><b>").append(_t("Speed")).append(": </b>");
            double speed = Math.max(1, _organizer.getSpeedThreshold());
            if (speed < -10240) {speed += 10240;}
            else if (speed < 0) {speed = 0;}
            if (speed > 1025) {
                speed = speed / 1024;
                buf.append((int)speed).append(' ' ).append("KB");
            } else {buf.append((int)speed).append(' ' ).append("B");}
            buf.append("ps</th><th><b>").append(_t("Capacity")).append(": </b>");
            double capThresh = Math.max(1, Math.round(_organizer.getCapacityThreshold()));
            double integThresh = Math.max(1, _organizer.getIntegrationThreshold());
            buf.append((int)Math.round(capThresh)).append(' ').append(_t("tunnels per hour")).append("</th><th><b>")
               .append(_t("Integration")).append(": </b>").append((int)Math.round(integThresh)).append(' ');
            if (capThresh > 0 && capThresh < 2) {buf.append(_t("peer"));}
            else {buf.append(_t("peers"));}
            buf.append("</th></tr></thead>\n<tbody>\n<tr><td>")
               .append(ngettext("{0} fast peer", "{0} fast peers", fast))
               .append("</td><td>")
               .append(ngettext("{0} high capacity peer", "{0} high capacity peers", reliable))
               .append("</td><td>")
               .append(ngettext("{0} integrated peer", "{0} integrated peers", integrated))
               .append("</td></tr>\n</tbody>\n</table>\n</div>\n</div>\n"); // thresholds
        } else if (mode == 2) {
            buf.append("<div class=widescroll id=ff>\n<table id=floodfills data-sortable>\n")
               .append("<colgroup></colgroup><colgroup></colgroup><colgroup></colgroup><colgroup></colgroup>")
               .append("<colgroup class=good></colgroup><colgroup class=good></colgroup><colgroup class=good></colgroup>")
               .append("<colgroup class=good></colgroup><colgroup class=good></colgroup><colgroup class=bad></colgroup>")
               .append("<colgroup class=bad></colgroup><colgroup class=bad></colgroup><colgroup class=bad></colgroup>")
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

            for (PeerProfile prof : order) {appendFloodfillPeerRow(buf, prof);}
            buf.append("</tbody>\n</table>\n</div>\n");
        }
        if (mode == 0 && !isAdvanced) {
            buf.append("<h3 class=tabletitle>").append(_t("Definitions")).append("</h3>\n<table id=profile_defs>\n<tbody>\n<tr><td><b>")
               .append(_t("caps")).append(":</b></td><td>").append(_t("Capabilities in the NetDb, not used to determine profiles"))
               .append("</td></tr>\n<tr id=capabilities_key><td></td><td><table><tbody>")
               .append("<tr>").append("<td><a href=\"/netdb?caps=f\" title=\"")
               .append(_t("Show all routers with this capability in the NetDb"))
               .append("\"><b class=ff>F</b></a></td><td>")
               .append(_t("Floodfill"))
               .append("</td><td><a href=\"/netdb?caps=R\" title=\"")
               .append(_t("Show all routers with this capability in the NetDb"))
               .append("\"><b class=reachable>R</b></a></td><td>")
               .append(_t("Reachable"))
               .append("</td>").append("</tr>\n<tr><td><a href=\"/netdb?caps=N\" title=\"")
               .append(_t("Show all routers with this capability in the NetDb"))
               .append("\"><b>N</b></a></td><td>")
               .append(_t("{0} shared bandwidth", range(Router.MIN_BW_N, Router.MIN_BW_O)))
               .append("</td><td><a href=\"/netdb?caps=O\" title=\"")
               .append(_t("Show all routers with this capability in the NetDb"))
               .append("\"><b>O</b></a></td><td>")
               .append(_t("{0} shared bandwidth", range(Router.MIN_BW_O, Router.MIN_BW_P)))
               .append("</td></tr>\n<tr><td><a href=\"/netdb?caps=P\" title=\"")
               .append(_t("Show all routers with this capability in the NetDb"))
               .append("\"><b>P</b></a></td><td>")
               .append(_t("{0} shared bandwidth", range(Router.MIN_BW_P, Router.MIN_BW_X)))
               .append("</td><td><a href=\"/netdb?caps=X\" title=\"")
               .append(_t("Show all routers with this capability in the NetDb"))
               .append("\"><b>X</b></a></td><td>")
               .append(_t("Over {0} KB/s shared bandwidth", Math.round(Router.MIN_BW_X * 1.024f)))
               .append("</td></tr>\n</tbody>\n</table>\n</td></tr>\n<tr><td><b>")
               .append(_t("status"))
               .append(":</b></td><td>")
               .append(_t("is the peer banned, or unreachable, or failing tunnel tests?"))
               .append("</td></tr>\n<tr><td><b>")
               .append(_t("groups"))
               .append(":</b></td><td>")
               .append(_t("Note: Peers are categorized by the profile organizer based on observable performance, not from capabilities they advertise in the NetDB."))
               .append("<br><span class=\"profilegroup fast\"><b>")
               .append(_t("Fast"))
               .append(":</b>&nbsp; ")
               .append(_t("Peers marked as high capacity that also meet or exceed speed average for all profiled peers."))
               .append("</span><br><span class=\"profilegroup highcap\"><b>")
               .append(_t("High Capacity"))
               .append(":</b>&nbsp; ")
               .append(_t("Peers that meet or exceed tunnel build rate average for all profiled peers."))
               .append("</span><br><span class=\"profilegroup integrated\"><b>")
               .append(_t("Integrated"))
               .append(":</b>&nbsp; ")
               .append(_t("Floodfill peers currently available for NetDb inquiries."))
               .append("</span><br><span class=\"profilegroup standard\"><b>")
               .append(_t("Standard"))
               .append(":</b>&nbsp; ")
               .append(_t("Peers not profiled as high capacity (lower build rate than average peer)."))
               .append("</span></td></tr>\n<tr><td><b>")
               .append(_t("speed"))
               .append(":</b></td><td>")
               .append(_t("Peak throughput (bytes per second) over a 1 minute period that the peer has sustained in a single tunnel."))
               .append("</td></tr>\n<tr><td><b>")
               .append(_t("latency"))
               .append(":</b></td><td>")
               .append(_t("Is the peer responding to tests in a timely fashion? To configure the timeout value: " +
                          "<code>router.peerTestTimeout={n}</code> (value is milliseconds, default 1000ms)"))
               .append("</td></tr>\n<tr><td><b>")
               .append(_t("capacity"))
               .append(":</b></td><td>")
               .append(_t("how many tunnels can we ask them to join in an hour?"))
               .append("</td></tr>\n<tr><td><b>")
               .append(_t("integration"))
               .append(":</b></td><td>")
               .append(_t("how many new peers have they told us about lately?"))
               .append("</td></tr>\n</tbody>\n</table>\n");
        }  // mode < 2
        out.append(buf);
        out.flush();
        buf.setLength(0);
    }

    /**
     * Appends a peer table row to the HTML output.
     */
    private void appendPeerTableRow(StringBuilder buf, PeerProfile prof, Map<String, String> reverseLookupCache, long now, int[] counters) {
        Hash peer = prof.getPeer();
        RouterInfo info = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(peer);
        if (info == null) return;

        // Tier and integration
        int tier = 3;
        boolean isIntegrated = _organizer.isWellIntegrated(peer);
        if (_organizer.isFast(peer)) {
            tier = 1;
            counters[0]++;
            counters[1]++;
        } else if (_organizer.isHighCapacity(peer)) {
            tier = 2;
            counters[1]++;
        }
        if (isIntegrated) counters[2]++;

        // Tunnel stats
        int agreed = Math.round(prof.getTunnelHistory().getLifetimeAgreedTo());
        int rejected = Math.round(prof.getTunnelHistory().getLifetimeRejected());

        // Peer and caps
        String peerHTML = _context.commSystem().renderPeerHTML(peer, false);
        String capsHTML = info != null ? _context.commSystem().renderPeerCaps(peer, false) : "";

        // Version
        String vOpt = info.getOption("router.version");
        String versionHTML = vOpt != null
            ? "<span class=version title=\"" + _t("Show all routers with this version in the NetDb") + "\"><a href=\"/netdb?v=" + vOpt + "\">" + vOpt + "</a></span>"
            : "<span>&ensp;</span>";

        // Host/IP
        String ip = info != null ? Addresses.toString(CommSystemFacadeImpl.getValidIP(info)) : null;
        String rl = null;
        if (ip != null && enableReverseLookups() && _context.router().getUptime() > 30 * 1000) {
            rl = reverseLookupCache.get(ip);
            if (rl == null) {
                rl = _context.commSystem().getCanonicalHostName(ip);
                reverseLookupCache.put(ip, rl);
            }
        }
        if (rl != null && rl.equals("unknown")) rl = ip;

        String hostHTML;
        if (enableReverseLookups() && ip != null && !ip.equals("null")) {
            if (rl != null && !rl.equals("null") && !rl.isEmpty() && rl.length() > 0 && !ip.equals(rl)) {
                String whois = CommSystemFacadeImpl.getDomain(rl);
                String whoisShort = whois.replaceAll("\\(.*?\\)", "").toLowerCase().trim()
                    .replace("latin american and caribbean ip address regional registry", "lacnic")
                    .replace("asia pacific network information centre", "apnic")
                    .replace("mediacom communications corp", "mediacom")
                    .replace(", inc", "").replace(" inc", "").replace(" llc", "").replace("administered by ", "").trim();
                hostHTML = "<span hidden>[XHost]</span><span class=rlookup title=\"" + DataHelper.escapeHTML(whois) + "\">" + whoisShort + "</span>";
            } else if (ip.contains(":")) {
                hostHTML = "<span hidden>[IPv6]</span><span class=host_ipv6>" + ip + "</span>";
            } else {
                hostHTML = "<span class=host_ipv6>" + ip + "</span>";
            }
        } else {
            hostHTML = ip != null ? ip : _t("unknown");
        }

        // Status
        boolean isBanned = _context.banlist().isBanlisted(peer) || _context.banlist().isBanlistedHostile(peer);
        boolean isUnreachable = _context.commSystem().wasUnreachable(peer);
        boolean ok = !(isBanned || isUnreachable);

        RateAverages ra = RateAverages.getTemp();
        Rate failed = prof.getTunnelHistory().getFailedRate().getRate(60 * 60 * 1000);
        long fails = failed.computeAverages(ra, false).getTotalEventCount();
        long bonus = prof.getSpeedBonus();
        long capBonus = prof.getCapacityBonus();

        String statusHTML;
        if (ok && fails == 0) {
            statusHTML = "<span class=ok>" + _t("OK") + "</span>";
        } else {
            StringBuilder status = new StringBuilder("<span class=\"notOk");
            if (isBanned) status.append(" banned");
            if (isUnreachable) status.append(" unreachable");

            if (fails > 0) {
                Rate accepted = prof.getTunnelCreateResponseTime().getRate(60 * 60 * 1000);
                long total = accepted.computeAverages(ra, false).getTotalEventCount() + fails;
                double failPercentage = (double) fails / total * 100;

                if (failPercentage <= 5.0) {
                    if (bonus == 9999999) prof.setSpeedBonus(0);
                    prof.setCapacityBonus(-30);
                }

                boolean failHigh = failPercentage >= 10.0;
                if (failHigh) status.append(" failing").append(failPercentage >= 50.0 ? " fiftyPercent" : "");

                status.append("\" title=\"&bullet; ").append(fails).append('/').append(total).append(' ').append(_t("Test Fails"));
                if (isUnreachable) status.append(" &bullet; ").append(_t("Unreachable"));
                if (isBanned) status.append(" &bullet; ").append(_t("Banned"));

                status.append("\">");

                if (failHigh) status.append("&bullet; ").append(fails).append('/').append(total).append(' ').append(_t("Test Fails"));
                if (isUnreachable) status.append(" &bullet; ").append(_t("Unreachable"));
                if (isBanned) status.append(" &bullet; ").append(_t("Banned"));
            } else if (isUnreachable) {
                status.append("\" title=\"&bullet; ").append(_t("Unreachable")).append("\">");
                if (bonus == 9999999) prof.setSpeedBonus(0);
                prof.setCapacityBonus(-30);
            } else if (isBanned) {
                status.append("\" title=\"&bullet; ").append(_t("Banned")).append("\">");
            }

            status.append("</span>");
            statusHTML = status.toString();
        }

        // Groups
        StringBuilder groups = new StringBuilder("<span class=\"").append(isIntegrated ? "integrated " : "");
        switch (tier) {
            case 1: groups.append("fast\">").append(_t("Fast, High Capacity")); break;
            case 2: groups.append("highcap\">").append(_t("High Capacity")); break;
            case 3: groups.append("standard\">").append(_t("Standard")); break;
            default: groups.append("failing\">").append(_t("Failing")); break;
        }
        if (isIntegrated) groups.append(", ").append(_t("Integrated"));
        groups.append("</span>");

        // Speed
        StringBuilder speed = new StringBuilder();
        int speedValue = (int) Math.round(prof.getSpeedValue());
        if (prof.getSpeedValue() > 0.1) {
            speed.append("<span class=\"");
            if (bonus >= 9999999) speed.append("testOK");
            else if (capBonus == -30) speed.append("testFail");

            if (speedValue >= 9999999) {
                speed.append("\">");
            } else if (speedValue > 1025) {
                speedValue /= 1024;
                speed.append(" kilobytes\">").append(speedValue).append(" K/s");
            } else {
                speed.append(" bytes\">").append(speedValue).append(" B/s");
            }

            if (bonus != 0 && bonus != 9999999) speed.append(bonus > 0 ? " (+": " (").append(bonus).append(')');
            speed.append("</span>");
        } else {
            speed.append("<span hidden>0</span><span class=\"");
            if (bonus >= 9999999) speed.append("testOK ");
            else if (capBonus <= -30) speed.append("testFail ");
            speed.append("nospeed\">&ensp;</span>");
        }

        // Latency
        String latencyHTML = bonus >= 9999999 ? "<span class=lowlatency>✔</span>" :
                             capBonus == -30 ? "<span class=highlatency>✖</span>" :
                             "<span>&ensp;</span>";

        // Tunnel stats
        String agreedHTML = agreed > 0 ? Integer.toString(agreed) : "<span hidden>0</span>";
        String rejectedHTML = rejected > 0 ? Integer.toString(rejected) : "<span hidden>0</span>";

        // Heard times
        String firstHeardHTML = prof.getFirstHeardAbout() > 0
            ? "<span hidden>[" + prof.getFirstHeardAbout() + "]</span>" + formatInterval(now, prof.getFirstHeardAbout())
            : "";
        String lastHeardHTML = "<span hidden>[" + (prof.getLastHeardFrom() - now) + "]</span>" + formatInterval(now, prof.getLastHeardFrom());

        // Links
        String profileLink = "";
        if (prof != null) {
            String peerB64 = peer.toBase64();
            String title = _t("View profile");
            profileLink = "<a class=viewprofile href=\"/viewprofile?peer=" + DataHelper.escapeHTML(peerB64) +
                          "\" title=\"" + DataHelper.escapeHTML(title) +
                          "\" alt=\"[" + DataHelper.escapeHTML(title) +
                          "]\">" + title + "</a>";
        }

        String editLink = "<br><a class=configpeer href=\"/configpeer?peer=" + DataHelper.escapeHTML(peer.toBase64()) +
                         "\" title=\"" + DataHelper.escapeHTML(_t("Configure peer")) +
                         "\" alt=\"[" + DataHelper.escapeHTML(_t("Configure peer")) +
                         "]\">" + _t("Edit") + "</a>";

        buf.append("<tr class=lazy><td nowrap>")
           .append(peerHTML)
           .append("</td><td>")
           .append(capsHTML)
           .append("</td><td>")
           .append(versionHTML)
           .append("</td><td class=host>")
           .append(hostHTML)
           .append("</td><td>")
           .append(statusHTML)
           .append("</td><td class=groups>")
           .append(groups)
           .append("</td><td>")
           .append(speed)
           .append("</td><td class=latency>")
           .append(latencyHTML)
           .append("</td><td>")
           .append(agreedHTML)
           .append("</td><td>")
           .append(rejectedHTML)
           .append("</td><td>")
           .append(firstHeardHTML)
           .append("</td><td>")
           .append(lastHeardHTML)
           .append("</td><td nowrap class=viewedit>")
           .append(profileLink)
           .append(editLink)
           .append("</td></tr>\n");
    }

    /**
     * Appends a floodfill peer row to the HTML output.
     */
    private void appendFloodfillPeerRow(StringBuilder buf, PeerProfile prof) {
        Hash peer = prof.getPeer();
        DBHistory dbh = prof.getDBHistory();
        RouterInfo info = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(peer);

        if (info == null) {return;}

        boolean isFF = info.getCapabilities().indexOf('f') >= 0;
        boolean isUnreachable = info.getCapabilities().indexOf('U') >= 0;
        boolean isBanned = _context.banlist().isBanlisted(peer) || _context.banlist().isBanlistedHostile(peer);

        if (!(dbh != null && isFF && !isUnreachable && !isBanned && prof.getLastHeardFrom() > 0)) {return;}

        RateAverages ra = RateAverages.getTemp();
        long now = _context.clock().now();

        String peerHTML = _context.commSystem().renderPeerHTML(peer, true);

        String hourfail = davg(dbh, 60 * 60 * 1000L, ra);
        String hourfailBar = "<span class=\"percentBarOuter" +
                             (hourfail.equals("0%") ? " nofail" : "") +
                             "\"><span class=percentBarInner style=width:" +
                             hourfail +
                             "><span class=percentBarText>" +
                             hourfail +
                             "</span></span></span>";

        String houravg = avg(prof, 60 * 60 * 1000L, ra);
        String firstHeard = formatInterval(now, prof.getFirstHeardAbout());
        String lastHeard = formatInterval(now, prof.getLastHeardFrom());

        long successfulLookups = dbh.getSuccessfulLookups();
        long failedLookups = dbh.getFailedLookups();

        String lastLookupSuccess = formatInterval(now, dbh.getLastLookupSuccessful());
        String lastLookupFailed = formatInterval(now, dbh.getLastLookupFailed());

        String lastSendSuccess = formatInterval(now, prof.getLastSendSuccessful());
        String lastSendFailed = formatInterval(now, prof.getLastSendFailed());

        String lastStoreSuccess = formatInterval(now, dbh.getLastStoreSuccessful());
        String lastStoreFailed = formatInterval(now, dbh.getLastStoreFailed());

        // Append the entire row in one go for performance
        buf.append("<tr class=lazy><td nowrap>")
           .append(peerHTML)
           .append("</td><td>")
           .append(hourfailBar)
           .append("</td><td><span hidden>[")
           .append(houravg)
           .append("]</span>")
           .append(houravg)
           .append("</td><td><span hidden>[")
           .append(prof.getFirstHeardAbout())
           .append("]</span>")
           .append(firstHeard)
           .append("</td><td><span hidden>[")
           .append(prof.getLastHeardFrom())
           .append(".]</span>")
           .append(lastHeard)
           .append("</td><td><span hidden>[")
           .append(successfulLookups)
           .append("]</span>")
           .append(successfulLookups)
           .append("</td><td><span hidden>[")
           .append(dbh.getLastLookupSuccessful())
           .append("]</span>")
           .append(lastLookupSuccess)
           .append("</td><td><span hidden>[")
           .append(prof.getLastSendSuccessful())
           .append("]</span>")
           .append(lastSendSuccess)
           .append("</td><td><span hidden>[")
           .append(dbh.getLastStoreSuccessful())
           .append("]</span>")
           .append(lastStoreSuccess)
           .append("</td><td><span hidden>[")
           .append(failedLookups)
           .append("]</span>")
           .append(failedLookups)
           .append("</td><td><span hidden>[")
           .append(dbh.getLastLookupFailed())
           .append("]</span>")
           .append(lastLookupFailed)
           .append("</td><td><span hidden>[")
           .append(prof.getLastSendFailed())
           .append("]</span>")
           .append(lastSendFailed)
           .append("</td><td><span hidden>[")
           .append(dbh.getLastStoreFailed())
           .append("]</span>")
           .append(lastStoreFailed)
           .append("</td></tr>\n");
    }

    private class ProfileComparator extends ProfComparator {
        public int compare(PeerProfile left, PeerProfile right) {
            if (_context.profileOrganizer().isFast(left.getPeer())) {
                if (_context.profileOrganizer().isFast(right.getPeer())) {return super.compare(left, right);}
                else {return -1;} // fast comes first
            } else if (_context.profileOrganizer().isHighCapacity(left.getPeer())) {
                if (_context.profileOrganizer().isFast(right.getPeer())) {return 1;}
                else if (_context.profileOrganizer().isHighCapacity(right.getPeer())) {return super.compare(left, right);}
                else {return -1;}
            } else {
                if (_context.profileOrganizer().isFast(right.getPeer())) {return 1;}
                else if (_context.profileOrganizer().isHighCapacity(right.getPeer())) {return 1;}
                else {return super.compare(left, right);}
            }
        }
    }

    /**
     *  Used for floodfill-only page
     *  As of 0.9.29, sorts in true binary order, not base64 string
     *  @since 0.9.8
     */
    private static class ProfComparator implements Comparator<PeerProfile>, Serializable {
        public int compare(PeerProfile left, PeerProfile right) {
            return HashComparator.comp(left.getPeer(), right.getPeer());
        }
    }

    private final static DecimalFormat _fmt = new DecimalFormat("###,##0.00");
    private final static String num(double num) { synchronized (_fmt) { return _fmt.format(num); } }
    private final static String NA = "&ensp;";

    private String avg (PeerProfile prof, long rate, RateAverages ra) {
            RateStat rs = prof.getDbResponseTime();
            if (rs == null) {return _t(NA);}
            Rate r = rs.getRate(rate);
            if (r == null) {return _t(NA);}
            r.computeAverages(ra, false);
            if (ra.getTotalEventCount() == 0) {return _t(NA);}
            return DataHelper.formatDuration2(Math.round(ra.getAverage()));
    }

    private String davg (DBHistory dbh, long rate, RateAverages ra) {
            RateStat rs = dbh != null ? dbh.getFailedLookupRate() : null;
            if (rs == null) {return "0%";}
            Rate r = rs.getRate(rate);
            if (r == null) {return "0%";}
            r.computeAverages(ra, false);
            if (ra.getTotalEventCount() <= 0) {return "0%";}
            double avg = 0.5 + 100 * ra.getAverage();
            return ((int) avg) + "%";
    }

    /** @since 0.9.21 */
    private String formatInterval(long now, long then) {
        if (then <= 0) {return _t(NA);}
        // avoid 0 or negative
        if (now <= then) {return DataHelper.formatDuration2(1);}
        return DataHelper.formatDuration2(now - then);
    }

    /** translate a string */
    private String _t(String s) {return Messages.getString(s, _context);}

    private String _t(String s, Object o) {return Messages.getString(s, o, _context);}

    /** translate (ngettext) @since 0.8.5 */
    public String ngettext(String s, String p, int n) {
        return Messages.getString(n, s, p, _context);
    }

}