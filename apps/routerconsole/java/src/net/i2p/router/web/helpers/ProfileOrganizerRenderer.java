package net.i2p.router.web.helpers;

import static net.i2p.router.web.helpers.TunnelRenderer.range;

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
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateAverages;
import net.i2p.stat.RateStat;
import net.i2p.util.Addresses;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.DBHistory;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.router.transport.CommSystemFacadeImpl;
import net.i2p.router.web.Messages;
import net.i2p.util.LHMCache;

/**
 * Helper class to refactor HTML rendering from out of the ProfileOrganizer
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
            if (prof == null) {continue;}
            int agreed = Math.round(prof.getTunnelHistory().getLifetimeAgreedTo());
            int rejected = Math.round(prof.getTunnelHistory().getLifetimeRejected());
            RouterInfo info = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(peer);
            boolean isFF = info != null && info.getCapabilities().indexOf('f') >= 0;
            if (_organizer.getUs().equals(peer) || prof.getLastHeardFrom() <= 0 || (agreed <= 0 && rejected <= 0 && prof.getFirstHeardAbout() <= 0)) {continue;}
            if (mode != 2) {
                boolean isActive = prof.getIsActive() || prof.getLastSendSuccessful() > hideBefore || prof.getLastHeardFrom() > hideBefore;
                boolean underAttack = _organizer.isLowBuildSuccess();
                if (!isActive && prof.getLastHeardFrom() <= hideBefore && prof.getFirstHeardAbout() < now - 60*60*1000) {
                    if (!underAttack || !_organizer.isFast(peer)) {
                        older++;
                        continue;
                    }
                }
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
        StringBuilder buf = new StringBuilder(32*1024);

        // Cache for reverse DNS lookups
        LHMCache<String, String> reverseLookupCache = new LHMCache<>(50);

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
            buf.append("<div class=widescroll id=peerprofiles>\n<table id=profilelist data-sort-direction=descending>\n")
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
               .append("<th data-sort-method=number>").append(_t("Speed")).append("</th>")
               .append("<th class=latency data-sort-method=number>").append(_t("Low Latency")).append("</th>")
               .append("<th title=\"").append(_t("Tunnels peer has agreed to participate in"))
               .append("\" data-sort-method=number>").append(_t("Accepted")).append("</th>")
               .append("<th title=\"").append(_t("Tunnels peer has refused to participate in"))
               .append("\" data-sort-method=number>").append(_t("Rejected")).append("</th>")
               .append("<th data-sort-method=number>").append(_t("First Heard About")).append("</th>")
               .append("<th data-sort-method=number>").append(_t("Last Heard From")).append("</th>")
               .append("<th data-sort-method=none>").append(_t("View/Edit")).append("</th>")
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
                } else {tier = 3;}
                if (_organizer.isWellIntegrated(peer)) {
                    isIntegrated = true;
                    integrated++;
                }
                buf.append("<tr class=lazy><td nowrap>");
                buf.append(_context.commSystem().renderPeerHTML(peer, false));
                RouterInfo info = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(peer);
                buf.append("</td><td>");
                if (info != null) {buf.append(_context.commSystem().renderPeerCaps(peer, false));}
                buf.append("</td><td>");
                String v = info != null ? info.getOption("router.version") : null;
                if (v != null) {
                    buf.append("<span class=version title=\"").append(_t("Show all routers with this version in the NetDb"))
                       .append("\"><a href=\"/netdb?v=").append(DataHelper.stripHTML(v)).append("\">").append(DataHelper.stripHTML(v))
                       .append("</a></span>");
                } else {buf.append("<span>&ensp;</span>");}
                buf.append("</td><td class=host>");
                long uptime = _context.router().getUptime();
                String ip = (info != null) ? Addresses.toString(CommSystemFacadeImpl.getCompatibleIP(info)) : null;
                String rl = null;
                if (ip != null && enableReverseLookups() && uptime > 30*1000) {
                    if (reverseLookupCache.containsKey(ip)) {
                        rl = reverseLookupCache.get(ip);
                    } else {
                        rl = _context.commSystem().getCanonicalHostName(ip);
                        reverseLookupCache.put(ip, rl);
                    }
                }
                if (rl != null && rl.equals("unknown")) {rl = ip;}
                if (enableReverseLookups()) {
                    if (rl != null && !rl.equals("null") && !rl.isEmpty() && rl.length() != 0 && !ip.toString().equals(rl)) {
                        String whois = CommSystemFacadeImpl.getDomain(rl);
                        String whoisShort = whois.replaceAll("\\(.*?\\)", "").toLowerCase().trim();
                        whoisShort = whoisShort.replace("latin american and caribbean ip address regional registry", "lacnic")
                                               .replace("asia pacific network information centre", "apnic")
                                               .replace("mediacom communications corp", "mediacom")
                                               .replace(", inc", "")
                                               .replace(" inc", "")
                                               .replace(" llc", "")
                                               .replace("administered by ", "")
                                               .trim();
                        buf.append("<span hidden>[XHost]</span><span class=rlookup title=\"").append(whois).append("\">").append(whoisShort);
                    } else if (ip == "null" || ip == null) {buf.append("<span>").append(_t("unknown"));}
                    else {
                        if (ip != null && ip.contains(":")) {buf.append("<span hidden>[IPv6]</span>");}
                        buf.append("<span class=host_ipv6>").append(ip);
                    }
                    buf.append("</span>");
                } else {buf.append(ip != null ? ip : _t("unknown"));}
                buf.append("</td><td>");
                boolean ok = true;
                boolean isBanned = false;
                boolean isUnreachable = false;
                if (_context.banlist().isBanlisted(peer)) {
                    ok = false;
                    isBanned = true;
                }
                if (_context.commSystem().wasUnreachable(peer)) {
                    ok = false;
                    isUnreachable = true;
                }
                RateAverages ra = RateAverages.getTemp();
                Rate failed = prof.getTunnelHistory().getFailedRate().getRate(RateConstants.ONE_HOUR);
                long fails = failed.computeAverages(ra, false).getTotalEventCount();
                long bonus = prof.getSpeedBonus();
                long capBonus = prof.getCapacityBonus();
                if (ok && fails == 0) {buf.append("<span class=ok>").append(_t("OK")).append("</span>");}
                else if (!ok) {
                    buf.append("<span class=\"notOk").append(isBanned ? " banned" : "")
                       .append(isUnreachable ? " unreachable" : "");

                    if (fails > 0) {
                        Rate accepted = prof.getTunnelCreateResponseTime().getRate(RateConstants.ONE_HOUR);
                        long total = fails + accepted.computeAverages(ra, false).getTotalEventCount();
                        double failPercentage = (double) fails / total * 100;

                        if (failPercentage <= 5.0) { // don't demote if less than 5%
                            if (bonus == 9999999) {prof.setSpeedBonus(0);}
                            prof.setCapacityBonus(-30);
                        }

                        boolean failHigh = failPercentage >= 10.0;
                        if (failHigh) {
                            buf.append(" failing").append(failPercentage >= 50.0 ? " fiftyPercent" : "");
                        }
                        buf.append("\" title=\"");
                        buf.append("\u2022 ").append(fails).append('/').append(total).append(' ').append(_t("Test Fails"));
                        if (isUnreachable) buf.append(" \u2022 ").append(_t("Unreachable"));
                        if (isBanned) buf.append(" \u2022 ").append(_t("Banned"));
                        buf.append("\">");

                        if (failHigh) {
                            buf.append("\u2022 ").append(fails).append('/').append(total).append(' ').append(_t("Test Fails"));
                        }
                        if (isUnreachable) buf.append(" \u2022 ").append(_t("Unreachable"));
                        if (isBanned) buf.append(" \u2022 ").append(_t("Banned"));
                    } else if (isUnreachable) {
                        buf.append("\" title=\"\u2022 ").append(_t("Unreachable"));
                        if (bonus == 9999999) {prof.setSpeedBonus(0);}
                        prof.setCapacityBonus(-30);
                    } else if (isBanned) {
                        buf.append("\" title=\"\u2022 ").append(_t("Banned"));
                    }
                    buf.append("\"></span>");
                } else {buf.append("<span class=mostPass title=\"").append(_t("Most tests passing")).append("\">&ensp;</span>");}
                buf.append("</td><td class=groups><span class=\"");
                if (isIntegrated) buf.append("integrated ");
                switch (tier) {
                    case 1: buf.append("fast\">").append(_t("Fast, High Capacity")); break;
                    case 2: buf.append("highcap\">").append(_t("High Capacity")); break;
                    case 3: buf.append("standard\">").append(_t("Standard")); break;
                    default: buf.append("failing\">").append(_t("Failing")); break;
                }
                if (isIntegrated) buf.append(", ").append(_t("Integrated"));
                String spd = num(Math.round(prof.getSpeedValue())).replace(",", "");
                String speedApprox = spd.substring(0, spd.indexOf("."));
                int speed = Integer.parseInt(speedApprox);
                buf.append("</span></td><td data-sort=").append(speed).append(">");
                if (prof.getSpeedValue() > 0.1) {
                    buf.append("<span class=\"");
                    if (bonus >= 9999999) {buf.append("testOK ");}
                    else if (capBonus == -30) {buf.append("testFail ");}
                    if (speed >= 9999999) {speed = speed - 9999999;}
                    if (speed > 1025) {
                        speed = speed / 1024;
                        buf.append("kilobytes\">");
                        buf.append(speed).append(" K/s");
                    } else {
                        buf.append("bytes\">");
                        buf.append(speed).append(" B/s");
                    }
                    if (bonus != 0 && bonus != 9999999) {
                        if (bonus > 0) {buf.append(" (+");}
                        else {buf.append(" (");}
                        buf.append(bonus).append(')');
                    }
                    buf.append("</span>");
                } else {
                    buf.append("<span hidden>0</span><span class=\"");
                    if (bonus >= 9999999) {buf.append("testOK ");}
                    else if (capBonus <= -30) {buf.append("testFail ");}
                    buf.append("nospeed\">&ensp;</span>");
                }
                int score = 0;
                if (bonus >= 9999999) {score = 2;}
                else if (capBonus == -30) {score = 1;}
                buf.append("</td><td class=latency data-sort=").append(score).append(">");
                if (bonus >= 9999999) {buf.append("<span class=lowlatency>✔</span>");}
                else if (capBonus == -30) {buf.append("<span class=highlatency>✖</span>");}
                else {buf.append("<span>&ensp;</span>");}
                int agreed = Math.round(prof.getTunnelHistory().getLifetimeAgreedTo());
                int rejected = Math.round(prof.getTunnelHistory().getLifetimeRejected());
                buf.append("</td><td data-sort=")
                   .append(prof.getTunnelHistory().getLifetimeAgreedTo())
                   .append(">")
                   .append(agreed)
                   .append("</td><td data-sort=")
                   .append(prof.getTunnelHistory().getLifetimeRejected())
                   .append(">")
                   .append(rejected)
                   .append("</td><td data-sort=")
                   .append(prof.getFirstHeardAbout())
                   .append(">");
                now = _context.clock().now();
                if (prof.getFirstHeardAbout() > 0) {
                   buf.append(formatInterval(now, prof.getFirstHeardAbout()));
                }
                buf.append("</td><td data-sort=").append(prof.getLastHeardFrom() - now).append(">")
                   .append(formatInterval(now, prof.getLastHeardFrom())).append("</td><td nowrap class=viewedit>");
                String viewProfile = _t("View profile");
                String configurePeer = _t("Configure Peer");
                if (prof != null) {
                    buf.append("<a class=viewprofile href=\"/viewprofile?peer=")
                       .append(peer.toBase64())
                       .append("\" title=\"")
                       .append(viewProfile)
                       .append("\" alt=\"[")
                       .append(viewProfile)
                       .append("]\">")
                       .append(_t("Profile"))
                       .append("</a>");
                }
                buf.append("<br><a class=configpeer href=\"/configpeer?peer=")
                   .append(peer.toBase64())
                   .append("\" title=\"")
                   .append(configurePeer)
                   .append("\" alt=\"[")
                   .append(configurePeer)
                   .append("]\">")
                   .append(_t("Edit"))
                   .append("</a></td></tr>\n");
            }
            buf.append("</tbody>\n</table>\n<div id=peer_thresholds>\n<h3 class=tabletitle>")
               .append(_t("Thresholds"))
               .append("</h3>\n<table id=thresholds>\n<thead><tr><th><b>")
               .append(_t("Speed"))
               .append(": </b>");
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
            buf.append("<div class=widescroll id=ff>\n<table id=floodfills data-sort-direction=descending>\n")
               .append("<colgroup></colgroup><colgroup></colgroup><colgroup></colgroup><colgroup></colgroup>")
               .append("<colgroup class=good></colgroup><colgroup class=good></colgroup><colgroup class=good></colgroup>")
               .append("<colgroup class=good></colgroup><colgroup class=good></colgroup><colgroup class=bad></colgroup>")
               .append("<colgroup class=bad></colgroup><colgroup class=bad></colgroup><colgroup class=bad></colgroup>")
               .append("<thead class=smallhead><tr>")
               .append("<th data-sort-default data-sort-direction=ascending>").append(_t("Peer")).append("</th>")
               .append("<th data-sort-direction=ascending data-sort-method=number>").append(_t("1h Fail Rate").replace("Rate","")).append("</th>")
               .append("<th data-sort-method=number>").append(_t("1h Resp. Time")).append("</th>")
               .append("<th data-sort-method=number>").append(_t("First Heard About")).append("</th>")
               .append("<th data-sort-method=number>").append(_t("Last Heard From")).append("</th>")
               .append("<th data-sort-method=number>").append(_t("Good Lookups")).append("</th>")
               .append("<th data-sort-method=number>").append(_t("Last Good Lookup")).append("</th>")
               .append("<th data-sort-method=number>").append(_t("Last Good Send")).append("</th>")
               .append("<th data-sort-method=number>").append(_t("Last Good Store")).append("</th>")
               .append("<th data-sort-method=number>").append(_t("Bad Lookups")).append("</th>")
               .append("<th data-sort-method=number>").append(_t("Last Bad Lookup")).append("</th>")
               .append("<th data-sort-method=number>").append(_t("Last Bad Send")).append("</th>")
               .append("<th data-sort-method=number>").append(_t("Last Bad Store")).append("</th>")
               .append("</tr></thead>\n<tbody id=ffProfiles>\n");
            RateAverages ra = RateAverages.getTemp();
            for (PeerProfile prof : order) {
                Hash peer = prof.getPeer();
                DBHistory dbh = prof.getDBHistory();
                RouterInfo info = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(peer);
                boolean isBanned = _context.banlist().isBanlisted(peer) || _context.banlist().isBanlistedHostile(peer);
                boolean isUnreachable = info != null && info.getCapabilities().indexOf('U') >= 0;
                boolean isFF = info != null && info.getCapabilities().indexOf('f') >= 0;
                int displayed = 0;
                boolean isResponding = prof.getDbResponseTime() != null;
                boolean isGood = prof.getLastSendSuccessful() > 0 && isResponding &&
                                 (dbh != null && dbh.getLastStoreSuccessful() > 0 ||
                                 dbh.getLastLookupSuccessful() > 0);
                //if (dbh != null && isFF && !isUnreachable && !isBanned && isGood) {
                if (dbh != null && isFF && !isUnreachable && !isBanned && prof.getLastHeardFrom() > 0) {
                    displayed++;
                    String integration = num(prof.getIntegrationValue()).replace(".00", "");
                    String hourfail = davg(dbh, 60*60*1000l, ra);
                    String hourfailValue = hourfail.replace("%", "");
                    String dayfail = davg(dbh, 24*60*60*1000l, ra);
                    now = _context.clock().now();
                    long heard = prof.getFirstHeardAbout();

                    buf.append("<tr class=lazy><td nowrap>")
                       .append(_context.commSystem().renderPeerHTML(peer, true))
                       .append("</td><td data-sort=")
                       .append(hourfailValue)
                       .append("><span class=\"percentBarOuter");
                    if (hourfail.equals("0%")) {buf.append(" nofail");}
                    buf.append("\"><span class=percentBarInner style=\"width:")
                       .append(hourfail)
                       .append("\"><span class=percentBarText>")
                       .append(hourfail)
                       .append("</span></span></span></td><td data-sort=")
                       .append(avg(prof, 60*60*1000l, ra))
                       .append(">")
                       .append(avg(prof, 60*60*1000l, ra))
                       .append("</td><td data-sort=")
                       .append(heard)
                       .append(">")
                       .append(formatInterval(now, heard))
                       .append("</td><td data-sort=")
                       .append(prof.getLastHeardFrom())
                       .append(">")
                       .append(formatInterval(now, prof.getLastHeardFrom()))
                       .append("</td><td data-sort=")
                       .append(dbh.getSuccessfulLookups())
                       .append(">")
                       .append(dbh.getSuccessfulLookups())
                       .append("</td><td data-sort=")
                       .append(dbh.getLastLookupSuccessful())
                       .append(">")
                       .append(formatInterval(now, dbh.getLastLookupSuccessful()))
                       .append("</td><td data-sort=")
                       .append(prof.getLastSendSuccessful())
                       .append(">")
                       .append(formatInterval(now, prof.getLastSendSuccessful()))
                       .append("</td><td data-sort=")
                       .append(dbh.getLastStoreSuccessful())
                       .append(">")
                       .append(formatInterval(now, dbh.getLastStoreSuccessful()))
                       .append("</td><td data-sort=")
                       .append(dbh.getFailedLookups())
                       .append(">")
                       .append(dbh.getFailedLookups())
                       .append("</td><td data-sort=")
                       .append(dbh.getLastLookupFailed())
                       .append(">")
                       .append(formatInterval(now, dbh.getLastLookupFailed()))
                       .append("</td><td data-sort=")
                       .append(prof.getLastSendFailed())
                       .append(">")
                       .append(formatInterval(now, prof.getLastSendFailed()))
                       .append("</td><td data-sort=")
                       .append(dbh.getLastStoreFailed())
                       .append(">")
                       .append(formatInterval(now, dbh.getLastStoreFailed()))
                       .append("</td></tr>\n");
                }
            }
            buf.append("</tbody>\n</table>\n</div>\n");
        }
        if (mode == 0 && !isAdvanced) {
            buf.append("<h3 class=tabletitle>")
               .append(_t("Definitions"))
               .append("</h3>\n<table id=profile_defs>\n<tbody>\n<tr><td><b>")
               .append(_t("caps"))
               .append(":</b></td><td>")
               .append(_t("Capabilities in the NetDb, not used to determine profiles"))
               .append("</td></tr>\n<tr id=capabilities_key><td></td><td><table><tbody><tr><td><a href=\"/netdb?caps=f\" title=\"")
               .append(_t("Show all routers with this capability in the NetDb"))
               .append("\"><b class=ff>F</b></a></td><td>")
               .append(_t("Floodfill"))
               .append("</td><td><a href=\"/netdb?caps=R\" title=\"")
               .append(_t("Show all routers with this capability in the NetDb"))
               .append("\"><b class=reachable>R</b></a></td><td>")
               .append(_t("Reachable"))
               .append("</td></tr>\n<tr><td><a href=\"/netdb?caps=N\" title=\"")
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